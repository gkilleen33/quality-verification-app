package com.qualityverifier.fundi.ui.assess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.location.LocationSource
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.images.UNREADABLE_PHOTO
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildFundiOpeningMessage
import com.qualityverifier.text.buildSubmissionText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.previousRecord
import com.qualityverifier.text.priorIssues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * What went wrong, in words the maker can act on.
 *
 * [retryable] marks a turn that failed on its way to the model, which Retry resends. A
 * photograph that would not process is not one, and nor is a sign-in failure: sending the
 * same turn again cannot fix either.
 */
data class AssessError(val message: String, val retryable: Boolean = false)

/** What a photograph being taken is for, so a kept one goes to the right place. */
sealed interface PhotoTarget {
    /**
     * The opening photograph, sent with the intake once kept. It carries the intake
     * because the intake is sent only then, and a photograph under review has not been
     * kept yet.
     */
    data class Opening(
        val intake: FundiIntake,
        val pieceLabel: String?,
        val workedOn: List<WorkerSlot>,
    ) : PhotoTarget

    data class PlanShot(val index: Int) : PhotoTarget
}

/**
 * A photograph just taken and held back for the maker to look at, because the on-device
 * check thinks it may be too dark or blurred to read. Kagua's `CaptureReview`, with
 * Fundi Bora's targets.
 */
data class PhotoReview(val path: String, val warning: String, val target: PhotoTarget)

/**
 * One assessment of one piece, from the opening photograph to the fix plan.
 *
 * A leaner cousin of Kagua's ChatViewModel rather than a reuse of it. The buyer's version
 * also carries an intake questionnaire, comparisons between two pieces, the evaluator
 * questionnaire and sharing — none of which a maker standing over their own bench has any
 * use for. What the two genuinely share is already shared: the camera and plan runner are
 * `:capture`, and storage, photo processing and its quality check, upload dedup and the
 * retry are `:core`.
 *
 * The turn goes to `v1/fundi/chat`, chosen once in [AppContainer] rather than here,
 * because the endpoint is what selects the coaching prompt.
 */
class AssessViewModel(
    private val sessionId: String,
    private val itemType: ItemType,
    /**
     * The physical piece. A first assessment uses its own session id; a re-assessment
     * passes the piece it is returning to, so the server links the two.
     */
    private val pieceId: String,
    /** Whether this is a piece coming back, which decides which purposes are offered. */
    val returning: Boolean,
    private val sessions: SessionRepository,
    private val chat: ChatService,
    private val images: SessionImageStore,
    private val profiles: FundiProfiles,
    private val workers: WorkerRepository,
    /**
     * The one location fix for a new assessment, as Kagua takes it. The default records
     * nothing, so tests about conversations need not stub a GPS.
     */
    private val location: LocationSource = LocationSource.None,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** The roster, for the picker. Archived workers are left out there, not here. */
    val roster: StateFlow<List<Worker>> =
        workers.observeWorkers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Who worked on this piece last time, as the picker's starting answer on a returning
     * piece. The same people usually do the same parts; the manager changes what differs.
     */
    private val _previousWorkers = MutableStateFlow<List<WorkerSlot>>(emptyList())
    val previousWorkers: StateFlow<List<WorkerSlot>> = _previousWorkers.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> =
        sessions.observeMessages(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<AssessError?>(null)
    val error: StateFlow<AssessError?> = _error.asStateFlow()

    /**
     * True when the maker's last turn never got a reply, and nothing is in flight. Kagua's
     * `ChatViewModel.unansweredTurn`, for the same reason: [error] lives only in this
     * object, so a turn that failed before the app was closed, or the app killed mid-request,
     * left nothing to say the turn was unanswered. The stored conversation still knows: a
     * session whose last message is the maker's is an unfinished turn.
     *
     * Offered, never acted on. A plan's photographs cost money to send, and somebody
     * reopening an assessment may only want to read it. [retry] is safe even if the turn
     * did land: the server keys a turn on its message id and returns the stored reply.
     */
    val unansweredTurn: StateFlow<Boolean> =
        combine(messages, _sending) { list, sending ->
            // Not while a request is in flight: the turn is stored before it is sent, so
            // during a normal send the conversation looks exactly like this failure.
            !sending && list.lastOrNull()?.role == Role.USER
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _run = MutableStateFlow<PlanRun?>(null)
    val run: StateFlow<PlanRun?> = _run.asStateFlow()

    /** A photograph the quality check flagged, until the maker keeps or retakes it. */
    private val _review = MutableStateFlow<PhotoReview?>(null)
    val review: StateFlow<PhotoReview?> = _review.asStateFlow()

    /**
     * Whether the intake has been sent, with the opening photograph if one was kept. The
     * screen leaves the camera then, and never shows it again for this assessment. Its own
     * flag rather than [sending]: a send that fails at once could rise and fall between two
     * frames and never be seen. It is also what makes [start] happen once: the view model
     * outlives the screen, so a rebuilt screen that walks the maker through the intake
     * again cannot send a second one.
     */
    private val _openingSent = MutableStateFlow(false)
    val openingSent: StateFlow<Boolean> = _openingSent.asStateFlow()

    /**
     * Whether a photograph just taken is still being processed. The camera keeps its
     * shutter busy until it is not: `CaptureScreen` frees the shutter as soon as the file
     * is saved, and a second photograph taken meanwhile raced the first. Two opening
     * photographs could each send the intake.
     */
    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    /**
     * The photograph being processed, or the last one. Kept after it is cancelled: a
     * cancelled job still finishes the write it is in, and a new photograph waits for it.
     */
    private var preparing: Job? = null

    /** Plans already answered, so a sent run is never re-offered. */
    private val fulfilled = mutableSetOf<String>()

    /**
     * The language chosen at intake, which the capture and verdict wording follow — the
     * same rule Kagua keeps, so a Kiswahili conversation does not get English headings.
     * Read back from the stored intake when a finished assessment is reopened.
     */
    private val _language = MutableStateFlow(AssessmentLanguage.ENGLISH)
    val language: StateFlow<AssessmentLanguage> = _language.asStateFlow()

    /** The purpose chosen at intake, for labelling the opening turn once it is sent. */
    private val _purpose = MutableStateFlow<FundiPurpose?>(null)
    val purpose: StateFlow<FundiPurpose?> = _purpose.asStateFlow()

    /** Findings from this piece's earlier assessments, offered when checking a fix. */
    /** What the last assessment of this piece found, for the opening turn. See [previousRecord]. */
    private var lastRecord: String? = null

    private val _priorIssues = MutableStateFlow<List<PriorIssue>>(emptyList())
    val priorIssues: StateFlow<List<PriorIssue>> = _priorIssues.asStateFlow()

    /** What the piece is called so far, prefilled into the intake. Null until named. */
    private val _pieceLabel = MutableStateFlow<String?>(null)
    val pieceLabel: StateFlow<String?> = _pieceLabel.asStateFlow()

    /**
     * Whether this assessment already exists, or null until that is known.
     *
     * Asked rather than inferred from the message list, which arrives asynchronously:
     * inferred, a finished assessment being reopened would flash the intake for a frame
     * before its conversation loaded, and a tap in that frame would start a second intake
     * over a finished one.
     */
    private val _existing = MutableStateFlow<Boolean?>(null)
    val existing: StateFlow<Boolean?> = _existing.asStateFlow()

    /**
     * This assessment's point, once it has arrived, until there is a session row to put it
     * in. The row is written only when the intake is sent, and a fix outdoors can arrive
     * before that; written into no row it would be lost (`SessionDao.setLocation` is an
     * update).
     */
    private var heldFix: LocationFix? = null
    private var rowExists = false
    private var pointWritten = false

    init {
        viewModelScope.launch {
            val exists = sessions.sessionExists(sessionId)
            _existing.value = exists
            // Only for an assessment that is beginning. Reopening one from Home must not
            // record where it was read, which would be both wrong and a second point.
            if (!exists) captureLocation()
        }
        viewModelScope.launch {
            sessions.startOf(sessionId)?.intakeCode?.let(FundiIntake::decode)?.let { (lang, why) ->
                _language.value = lang
                _purpose.value = why
            }
        }
        viewModelScope.launch { _pieceLabel.value = sessions.pieceLabel(pieceId) }
        if (returning) loadPriorIssues()
    }

    /**
     * Every finding recorded for this piece, newest assessment first.
     *
     * Read from this handset's own copies of the earlier conversations. Those are what
     * the maker read, and they are here with no signal — which is where a fix gets
     * checked, standing over the piece in the workshop.
     */
    private fun loadPriorIssues() {
        viewModelScope.launch {
            val earlier = sessions.observeSummaries().first()
                .filter { it.piece == pieceId && it.id != sessionId }
                .sortedByDescending { it.updatedAt }
            val turnsBySession = earlier.map { summary ->
                sessions.messagesOnce(summary.id)
                    .filter { it.role == Role.ASSISTANT }
                    .asReversed()
                    .map { it.text }
            }
            _priorIssues.value = priorIssues(turnsBySession.flatten())
            // The most recent assessment that recorded anything, rather than simply the
            // most recent: a check that was abandoned before its verdict says nothing.
            lastRecord = turnsBySession.firstNotNullOfOrNull { previousRecord(it, FundiLabels.ENGLISH) }
            // The people, from the most recent assessment whatever it recorded: who was
            // in the shop is a fact about the piece, not about the verdict.
            earlier.firstOrNull()?.let { _previousWorkers.value = workers.workersOf(it.id) }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Destination for the next shot. The camera screen writes straight into it. */
    fun newCaptureFile(): File? = runCatching { images.newImageFile(sessionId) }.getOrNull()

    /**
     * A person added inside the picker. On the roster at once; the server hears of the
     * name with the turn, and of the number and kind on the next roster sync, since the
     * turn carries only what the opening message needs.
     */
    fun addWorker(details: WorkerDetails, onAdded: (Worker) -> Unit) {
        viewModelScope.launch { onAdded(workers.add(details)) }
    }

    /**
     * Sends the completed intake, and the opening photograph if there is one.
     *
     * This is the first request of the assessment. Nothing before it reaches the model:
     * the language, the purpose and the details are all held here until the maker has
     * answered every one, so an abandoned intake costs nothing — the same rule Kagua
     * keeps.
     *
     * The turn is marked composed. The model needs the whole of it — purpose, workshop,
     * every tool marked owned, borrowed or none — but shown word for word it reads as the
     * app printing its own prompt, which is what the first build did.
     *
     * A profile that will not load does not block the assessment. Coaching that has to
     * guess at the tools is worse coaching, but it is better than refusing to look at a
     * piece somebody is standing over.
     *
     * [pieceLabel] is what the maker called the piece at the intake, or null when they
     * skipped it. The repository decides whether that names a new piece or renames an
     * old one.
     */
    fun start(
        intake: FundiIntake,
        photoPath: String?,
        pieceLabel: String? = null,
        /** Who worked on the piece, from the picker. Empty when skipped. */
        workedOn: List<WorkerSlot> = emptyList(),
    ) {
        // Once per assessment. A second intake would be a second opening turn in the same
        // session, read by the model as the maker starting over mid-plan.
        if (_openingSent.value || _sending.value || !intake.isComplete) return
        _openingSent.value = true
        _sending.value = true
        _language.value = intake.language
        _purpose.value = intake.purpose
        viewModelScope.launch {
            _error.value = null
            try {
                val profile = (profiles.load() as? ProfileOutcome.Loaded)?.profile
                    ?: FundiProfile()
                if (!sessions.sessionExists(sessionId)) {
                    sessions.createSession(
                        sessionId = sessionId,
                        itemType = itemType,
                        pieceId = pieceId,
                        intakeCode = FundiIntake.encode(intake),
                        pieceLabel = pieceLabel,
                    )
                    _pieceLabel.value = pieceLabel
                    // Recorded as already sent: the opening turn carries the set, and the
                    // server writes it when it creates the session.
                    workers.recordWorkersOf(sessionId, workedOn)
                }
                // Before the opening turn is stored, so a fix that has already arrived
                // goes out with it: the point reaches the server only inside a turn.
                rowExists = true
                writePointIfReady()
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildFundiOpeningMessage(intake, profile, FundiLabels.ENGLISH, lastRecord),
                    attachments = listOfNotNull(
                        photoPath?.let { Attachment(UUID.randomUUID().toString(), it) },
                    ),
                    composed = true,
                )
                deliver()
            } finally {
                _sending.value = false
            }
        }
    }

    /** A turn in the maker's own words — answering the one check, usually. */
    fun send(text: String) {
        if (text.isBlank() || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                sessions.appendUserMessage(sessionId, text.trim(), emptyList())
                deliver()
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Sends the last turn again, exactly as it is stored: Retry after a failure, and "Send
     * again" on an assessment reopened with its last turn unanswered. Kagua's
     * `ChatViewModel.retry`.
     *
     * Nothing is rebuilt and nothing is stored. The turn on the phone keeps its id, text,
     * photographs and their metadata sidecars, and `ServerChatService` sends the newest
     * user turn of what is stored, so the request carries the same `message_id` and the
     * same photographs. Rebuilding would mint a new id, and the server would pay for a turn
     * it may already have answered.
     *
     * That is also why the opening turn goes through here and not through [start]. The
     * session row, the people and the location point were all written the first time, and
     * the point reaches the server from the row with every turn; [start] stays once per
     * assessment.
     *
     * Only a turn still waiting on its reply. Sent after a reply has landed, the server
     * would return that reply again and it would be stored twice.
     *
     * Not guarded against a first attempt the server is still waiting on Claude for, after
     * the phone timed out or was closed: with no reply stored yet the server attempts the
     * turn again, and both attempts may be charged (F19, a known risk left for later).
     */
    fun retry() {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                if (sessions.messagesOnce(sessionId).lastOrNull()?.role == Role.USER) deliver()
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Picks up a plan the assistant has just sent, unless it has already been answered.
     *
     * Driven from the message list rather than from the reply, so a plan survives the
     * screen being rebuilt — the conversation is the state, and the run is derived.
     */
    fun offerPlanFrom(message: ChatMessage) {
        if (message.role != Role.ASSISTANT) return
        if (message.id in fulfilled || _run.value != null) return
        val plan = parseAssistantContent(message.text).plan ?: return
        _run.value = PlanRun(plan = plan, sourceMessageId = message.id)
    }

    /**
     * Offers the plan in the conversation's newest message, if that is the assistant's.
     *
     * The newest message, not the newest assistant message: a plan with any turn after it
     * has been answered. [fulfilled] knows that only for runs sent from this view model, so
     * an assessment reopened on an unanswered run submission would otherwise offer the plan
     * again, beside "Send again", and ask for photographs already on the stored turn. The
     * rule Kagua's `ChatViewModel` keeps.
     */
    fun offerPlanFromLatest(conversation: List<ChatMessage>) {
        conversation.lastOrNull()?.let(this::offerPlanFrom)
    }

    /**
     * Prepares a photograph the camera has just written, then keeps it or holds it for
     * review. The same path as Kagua's (`ChatViewModel.onPhotoCaptured`), so both apps
     * send Claude the same kind of picture.
     *
     * The file is rewritten in place before anything else sees it. That file is what the
     * turn attaches, what the upload hashes and what Claude is sent, so the SHA-256 is of
     * the processed photograph. Until v0.4.0 Fundi Bora sent the camera's file as it was:
     * full size, its rotation only an EXIF tag, and the camera's metadata still inside.
     *
     * The check is advisory, so it only interrupts: a photograph it cannot measure, or
     * measures as fine, is kept without a tap, and the maker works through the shot list
     * without confirming every frame.
     *
     * One photograph at a time. The shutter waits on [processing]; this is the backstop
     * for a tap that landed before it did. A second photograph taken while the first is
     * processed or under review is deleted, never kept: kept, it would land on whatever
     * the camera shows next, an opening photograph on a plan shot.
     */
    fun onPhotoCaptured(file: File, target: PhotoTarget) {
        if (preparing?.isCompleted == false || _review.value != null) {
            viewModelScope.launch { withContext(io) { images.delete(file) } }
            return
        }
        _processing.value = true
        val job = viewModelScope.launch {
            val prepared = withContext(io) { file.length() > 0 && images.normaliseInPlace(file) }
            if (!prepared) {
                withContext(io) { images.delete(file) }
                _error.value = AssessError(UNREADABLE_PHOTO)
                return@launch
            }
            val warning = withContext(io) { images.measureQuality(file)?.warning }
            if (warning != null) {
                _review.value = PhotoReview(file.absolutePath, warning, target)
            } else {
                accept(file.absolutePath, target)
            }
        }
        preparing = job
        // Runs once the processing has finished writing, so the delete cannot race it.
        // The shutter is freed here too, and only here: a cancelled job is still writing.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) images.delete(file)
            _processing.value = false
        }
    }

    /**
     * The maker left the camera, or the screen moved to another stage. A photograph still
     * being processed, or held for review, was never kept, so it is dropped. Without this,
     * an opening photograph that finished processing after the maker went back to the
     * intake would send the intake anyway.
     */
    fun leaveCamera() {
        preparing?.cancel()
        discardReviewedPhoto()
    }

    /** Keeps a photograph the check complained about. The maker has looked at it; they decide. */
    fun keepReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        accept(reviewed.path, reviewed.target)
    }

    /** Retakes it: the file is deleted and nothing is attached. */
    fun discardReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        viewModelScope.launch { withContext(io) { images.delete(File(reviewed.path)) } }
    }

    private fun accept(path: String, target: PhotoTarget) {
        // An opening photograph that arrives once the intake is out has nothing to go
        // with. Sent anyway, it would start the assessment a second time mid-plan.
        if (target is PhotoTarget.Opening && _openingSent.value) {
            viewModelScope.launch { withContext(io) { images.delete(File(path)) } }
            return
        }
        // A retake that worked answers the earlier "could not be read".
        if (_error.value?.message == UNREADABLE_PHOTO) _error.value = null
        when (target) {
            is PhotoTarget.Opening -> start(target.intake, path, target.pieceLabel, target.workedOn)
            is PhotoTarget.PlanShot -> attachShot(target.index, path)
        }
    }

    fun attachShot(index: Int, path: String) {
        _run.update { it?.copy(shots = it.shots + (index to path)) }
    }

    /** A shot that could not be taken. Recorded as skipped, never as absent. */
    fun skipShot(index: Int) {
        _run.update { it?.copy(shots = it.shots + (index to null)) }
    }

    fun retakeShot(index: Int) {
        val existing = _run.value?.shots?.get(index)
        _run.update { it?.copy(shots = it.shots.minus(index)) }
        if (existing != null) {
            viewModelScope.launch { withContext(io) { images.delete(File(existing)) } }
        }
    }

    fun answerTest(index: Int, answer: String) {
        _run.update { it?.copy(answers = it.answers + (index to answer)) }
    }

    /**
     * A test that did not happen.
     *
     * Null rather than absent, and it reaches the model as "not done": a heavy wardrobe
     * nobody could tip must not read as a wardrobe with a clean underside.
     */
    fun skipTest(index: Int) {
        _run.update { it?.copy(answers = it.answers + (index to null)) }
    }

    /**
     * Sends the whole run as one turn.
     *
     * One request rather than one per photograph. Every turn re-sends the earlier images,
     * so a shot-by-shot flow cost tokens growing with the square of the shot count.
     */
    fun submitRun() {
        val current = _run.value ?: return
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildSubmissionText(
                        current.plan,
                        current.shots,
                        current.answers,
                        ReportLabels.forLanguage(_language.value.code),
                    ),
                    attachments = current.takenPaths.map {
                        Attachment(UUID.randomUUID().toString(), it)
                    },
                    // A shot-by-shot list of what was photographed and what was skipped.
                    // The model needs it; the maker has just done it.
                    composed = true,
                )
                // Marked answered before the request, not after. A failed request must
                // not re-offer a plan whose photographs are already on a stored turn.
                fulfilled += current.sourceMessageId
                _run.value = null
                deliver()
            } finally {
                _sending.value = false
            }
        }
    }

    /** Abandons a run and deletes the photographs taken for it. */
    fun discardRun() {
        val current = _run.value ?: return
        fulfilled += current.sourceMessageId
        _run.value = null
        val paths = current.takenPaths
        viewModelScope.launch { withContext(io) { paths.forEach { images.delete(File(it)) } } }
    }

    /**
     * Starts the one fix for this assessment, if the maker agreed and Android allows it.
     *
     * [LocationSource.isAvailable] reads the switch, the maker's answer, the permission and
     * the phone's location services, all silently: nothing here ever asks Android, so no
     * assessment shows a permission prompt. A permission given "Only this time" and since
     * lapsed reads as refused, and nothing is recorded.
     *
     * Its own coroutine, and nothing joins it, as in Kagua: the fix settles while the maker
     * answers the intake and takes photographs. Failure is silence; an assessment must not
     * stall or show an error because a GPS fix did not arrive.
     */
    private fun captureLocation() {
        if (!location.isAvailable) return
        viewModelScope.launch {
            val fix = runCatching { location.capture() }.getOrNull() ?: return@launch
            heldFix = fix
            writePointIfReady()
        }
    }

    /**
     * Writes the point once both it and the row exist, and only once. The next turn sent
     * reads it from the row (`ServerChatService`), which is how it reaches the server.
     */
    private suspend fun writePointIfReady() {
        val fix = heldFix ?: return
        if (!rowExists || pointWritten) return
        pointWritten = true
        runCatching { sessions.recordLocation(sessionId, fix) }
    }

    private suspend fun deliver() {
        when (val result = chat.send(sessionId, itemType, sessions.messagesOnce(sessionId))) {
            is ChatResult.Success -> sessions.appendAssistantMessage(sessionId, result.text)
            // A sign-in failure is the one a resend cannot fix; Kagua offers Settings there.
            is ChatResult.Failure -> _error.value = AssessError(
                message = result.message,
                retryable = result.kind != ChatErrorKind.AUTH,
            )
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            sessionId: String,
            itemType: ItemType,
            pieceId: String,
            returning: Boolean,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AssessViewModel(
                    sessionId = sessionId,
                    itemType = itemType,
                    pieceId = pieceId,
                    returning = returning,
                    sessions = container.sessionRepository,
                    chat = container.chatService,
                    images = container.images,
                    profiles = container.fundiProfiles,
                    workers = container.workerRepository,
                    location = container.locationCapture,
                )
            }
        }
    }
}

package com.qualityverifier.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.location.LocationSource
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildComparisonRequest
import com.qualityverifier.text.buildIntakeMessage
import com.qualityverifier.text.buildSubmissionText
import com.qualityverifier.text.parseAssistantContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** An image the user has attached but not yet sent. */
data class PendingImage(val id: String, val path: String)

/**
 * A just-taken photo held back for the user to look at, because the on-device check
 * thinks it may be unusable. Only ever set when there is something to say — a photo that
 * measures fine is attached without interrupting the shot-by-shot flow.
 */
data class CaptureReview(
    val path: String,
    val warning: String,
    val target: CaptureTarget,
)

/** What a photo being taken is for, so a kept one goes to the right place. */
sealed interface CaptureTarget {
    /** Attached to the composer, to send with whatever the user types. */
    data object Composer : CaptureTarget

    /** Sent with the intake, so the assistant can see the piece before planning. */
    data object Opening : CaptureTarget

    data class PlanShot(val index: Int) : CaptureTarget
}

/**
 * A collection run in progress: the plan the assistant issued, and what has been
 * gathered against it so far.
 *
 * [shots] and [answers] are keyed by index into the plan's own lists. Presence in the
 * map means the step has been dealt with; a **null value means it was skipped**, which
 * is a real outcome and gets said out loud in the submitted turn. A heavy wardrobe
 * nobody could tip over must not read as a wardrobe with a clean underside.
 */
data class PlanRun(
    val plan: AssessmentPlan,
    val sourceMessageId: String,
    val shots: Map<Int, String?> = emptyMap(),
    val answers: Map<Int, String?> = emptyMap(),
) {
    val nextShot: Int? get() = plan.photos.indices.firstOrNull { it !in shots }
    val nextTest: Int? get() = plan.tests.indices.firstOrNull { it !in answers }
    val photosDone: Boolean get() = nextShot == null
    val isComplete: Boolean get() = nextShot == null && nextTest == null
    val photosTaken: Int get() = plan.photos.indices.count { shots[it] != null }

    /** Paths in plan order, which is the order the assistant expects to see them. */
    val takenPaths: List<String> get() = plan.photos.indices.mapNotNull { shots[it] }
}

data class ChatError(val kind: ChatErrorKind, val message: String)

class ChatViewModel(
    private val sessionId: String,
    private val declaredItemType: ItemType?,
    /**
     * Intake answers carried in from the assessment this one was started from, so a
     * second piece in the same shop asks for its price and nothing else. Null for an
     * assessment started from the grid, which gets the whole intake.
     */
    intakePrefill: AssessmentContext? = null,
    /** The assessment this one was started from, when it was started from one. */
    declaredPreviousSessionId: String? = null,
    private val sessions: SessionRepository,
    private val chat: ChatService,
    /**
     * Whether this account is one of our evaluators, read at the moment it is needed.
     *
     * A function rather than a value because the answer can change while this object is
     * alive: an account promoted in the portal, or — the case that actually bit — one that
     * registered seconds ago, before the phone had asked. Capturing it at construction
     * meant a brand-new evaluator finished their first assessment and was never asked for
     * the review it existed to produce.
     */
    private val isTester: () -> Boolean = { false },
    /**
     * Sends any review this phone is still holding.
     *
     * A function rather than the sync object itself so that this stays a chat screen: all
     * it needs is "try to flush that now", and a test does not have to build a sync with a
     * fake client to answer five questions.
     */
    private val pushReviews: suspend () -> Unit = {},
    /**
     * The one location fix for a new assessment.
     *
     * An interface with a do-nothing default so the tests, which are about conversations,
     * do not each have to stub a GPS. The default's [LocationSource.isAvailable] is false,
     * so nothing is even launched.
     */
    private val captureFix: LocationSource = LocationSource.None,
    private val images: SessionImageStore,
    /**
     * Where file work happens. Injected so that tests can drive it with the same
     * scheduler as the main dispatcher — otherwise a capture's normalise-and-measure
     * step runs on a real thread pool and no amount of advancing the test clock waits
     * for it.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = sessions.observeMessages(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pending = MutableStateFlow<List<PendingImage>>(emptyList())
    val pending: StateFlow<List<PendingImage>> = _pending.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error.asStateFlow()

    /**
     * UI-level advisory (permission refused, and similar) — distinct from [error],
     * which describes a failed send and therefore offers a retry.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _itemType = MutableStateFlow(declaredItemType)
    val itemType: StateFlow<ItemType?> = _itemType.asStateFlow()

    private val _review = MutableStateFlow<CaptureReview?>(null)
    val review: StateFlow<CaptureReview?> = _review.asStateFlow()

    private val _run = MutableStateFlow<PlanRun?>(null)
    val run: StateFlow<PlanRun?> = _run.asStateFlow()

    /** True from the moment a run is submitted until the reply lands. Drives Inspecting. */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    /**
     * The run being inspected. Held separately from [run], which is cleared on submit so
     * that the plan card stops offering buttons, while the Inspecting screen still needs
     * the photos and answers to show what is in flight.
     */
    private val _submittedRun = MutableStateFlow<PlanRun?>(null)
    val submittedRun: StateFlow<PlanRun?> = _submittedRun.asStateFlow()

    /**
     * True when the customer's last turn never got a reply, and nothing is in flight.
     *
     * Derived from the stored conversation rather than from [error], which lives only in
     * this object. A turn that failed while the customer was looking at the screen offers
     * a retry; the same turn after they closed the app offered nothing, because the error
     * had gone with the ViewModel. The database still knew — a session whose last message
     * is the customer's is an unfinished turn by definition — and this reads that instead.
     *
     * It also covers the app being killed mid-request, which until now was
     * indistinguishable from a finished conversation.
     *
     * Offered, never acted on: a turn carrying nine photos costs real money, and somebody
     * reopening a report is usually there to read it rather than to spend. [retry] is safe
     * to press even if the turn did land, because the server is idempotent on the message
     * id and returns the stored reply.
     */
    val unansweredTurn: StateFlow<Boolean> =
        combine(messages, _sending, _submitting) { list, sending, submitting ->
            // Not while a request is in flight: during a normal send the customer's
            // message is already stored and the reply has not arrived yet, which looks
            // exactly like the failure this detects.
            !sending && !submitting && list.lastOrNull()?.role == Role.USER
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether this evaluator has a review left to give on this assessment.
     *
     * Only true for an evaluator account, only once the assessment has reached a verdict,
     * and only while no review is already stored for it. Answering twice corrects the
     * first answer rather than being refused, but the prompt stops appearing.
     */
    private val _reviewDue = MutableStateFlow(false)
    val reviewDue: StateFlow<Boolean> = _reviewDue.asStateFlow()

    /** True while the questionnaire is on screen. */
    private val _reviewing = MutableStateFlow(false)
    val reviewing: StateFlow<Boolean> = _reviewing.asStateFlow()

    /** Plans already collected against, so a run is never offered twice. */
    private val fulfilled = mutableSetOf<String>()

    /**
     * True when this conversation has not started yet, so the app should ask its own
     * questions before sending anything.
     *
     * Read once from the database rather than derived from [messages], which starts empty
     * and would flash the intake over an existing conversation for a frame.
     */
    private val _needsIntake = MutableStateFlow(false)
    val needsIntake: StateFlow<Boolean> = _needsIntake.asStateFlow()

    /**
     * A finished intake waiting on its opening photo.
     *
     * A full assessment sends one photo of the whole piece with the context, so that the
     * assistant can check the item's protocol against the piece in front of the customer
     * before committing to seven shots. The item protocols describe a *typical* table, and
     * the one being bought may be on welded steel legs.
     *
     * The app takes that photo rather than asking the assistant to request it. Asked for
     * in the prompt it was simply ignored — the pull towards issuing the whole plan at once
     * was stronger — and doing it here costs one fewer round trip anyway.
     */
    private val _awaitingOpeningPhoto = MutableStateFlow<AssessmentContext?>(null)
    val awaitingOpeningPhoto: StateFlow<AssessmentContext?> = _awaitingOpeningPhoto.asStateFlow()

    /** Prefilled answers for the intake. Read once, when the screen decides what to ask. */
    val intakePrefill: AssessmentContext? = intakePrefill

    /**
     * This assessment's own answers, for the next piece to inherit.
     *
     * Held here and in the session row both: in memory for the assessment that has just
     * been done, and in the row so that a report reopened from history can still start
     * the next piece without asking five questions again.
     */
    private val _carryForward = MutableStateFlow(intakePrefill?.takeIf { it.isComplete })
    val carryForward: StateFlow<AssessmentContext?> = _carryForward.asStateFlow()

    /**
     * The verdict on the piece this one was started from, once there is one to compare
     * against. Null means no comparison is offered — there was no earlier piece, it was
     * a different kind of thing, or its assessment never reached a verdict.
     */
    private val _previousVerdict = MutableStateFlow<Verdict?>(null)
    val previousVerdict: StateFlow<Verdict?> = _previousVerdict.asStateFlow()

    /** True once a comparison has been asked for, so it is not asked for twice. */
    private val _comparisonRequested = MutableStateFlow(false)
    val comparisonRequested: StateFlow<Boolean> = _comparisonRequested.asStateFlow()

    /**
     * Not a StateFlow: nothing renders it, and it has to be readable synchronously when
     * the session row is written. Resolved from the row when a conversation is reopened.
     */
    private var previousSessionId: String? = declaredPreviousSessionId

    /** What kind of piece that earlier assessment was, so a like-for-like check can be redone. */
    private var previousItemType: ItemType? = null

    init {
        viewModelScope.launch {
            // One read for everything the row remembers: the protocol, the piece this
            // one came from, and the answers to carry into the next piece.
            val start = sessions.startOf(sessionId)
            if (declaredItemType == null) {
                // Reopened from history: the item type lives in the session row.
                _itemType.value = start?.itemType
            }
            if (_carryForward.value == null) _carryForward.value = start?.intake
            previousSessionId = previousSessionId ?: start?.previousSessionId
            val isNewAssessment = sessions.messagesOnce(sessionId).isEmpty()
            _needsIntake.value = isNewAssessment
            loadPreviousVerdict()
            // Only for an assessment that is actually beginning. Reopening a report from
            // history must not record where it was read, which would be both wrong and a
            // second point per assessment.
            if (isNewAssessment) captureLocation()
        }
        // Recomputed on every change rather than once at startup, so a flag that arrived
        // late still opens the questionnaire. The screen decides *when* to offer it, from
        // whether a verdict exists; this decides only whether there is one to offer.
        viewModelScope.launch {
            messages.collect {
                _reviewDue.value = isTester() && !sessions.hasPendingTesterFeedback(sessionId)
            }
        }
        // A plan in the newest assistant turn opens a run. Watching the message list
        // rather than the send path means a conversation reopened from history picks up
        // where it left off instead of stranding an unanswered plan.
        viewModelScope.launch {
            messages.collect { list ->
                val last = list.lastOrNull() ?: return@collect
                if (last.role != Role.ASSISTANT || last.id in fulfilled) return@collect
                if (_run.value?.sourceMessageId == last.id) return@collect
                val plan = parseAssistantContent(last.text).plan ?: return@collect
                _run.value = PlanRun(plan, last.id)
            }
        }
    }

    /**
     * Starts the conversation from what the app collected, in one request.
     *
     * This replaces the old behaviour of firing a request the moment the screen opened.
     * That was there to solve a real problem — the walkthrough used to begin only once
     * the customer typed something, because the API requires the first turn to be theirs
     * — but it solved it by making the first thing anybody saw a spinner, and then spent
     * three more round trips on questions no model was needed for. The intake keeps the
     * fix and drops the waiting: the customer's first turn is their own answers.
     */
    fun submitIntake(
        context: AssessmentContext,
        labels: ReportLabels,
        /**
         * The protocol the intake settled on. The home grid offers one chair entry and
         * the intake asks whether it is upholstered, so the type that gets stored is not
         * always the type the grid handed over.
         */
        resolvedItemType: ItemType = _itemType.value ?: ItemType.OTHER,
    ) {
        if (_sending.value) return
        _needsIntake.value = false
        _itemType.value = resolvedItemType
        // Kept only when it is whole. Half an intake carried into the next piece would
        // silently answer questions nobody answered.
        _carryForward.value = context.takeIf { it.isComplete }
        // The intake can land on a different protocol than the grid handed over — a chair
        // with cushions is not the same piece as a bare one — and that can stop the
        // earlier assessment being a like-for-like comparison after all.
        if (previousItemType != null && previousItemType != resolvedItemType) {
            _previousVerdict.value = null
        }

        // A full assessment collects its opening photo first. A rapid one does not: its
        // whole point is speed, and its plan is two wide photos anyway. An abandoned
        // intake does not either, since nobody has said which it is.
        if (context.depth == AssessmentDepth.FULL) {
            _awaitingOpeningPhoto.value = context
            return
        }
        send(context, labels, openingPhoto = null)
    }

    /**
     * Abandons the opening photo and starts the conversation without it.
     *
     * Reached by backing out of that camera. Better than trapping somebody there; the
     * assistant just plans from the item's protocol as written, without having seen the
     * piece.
     */
    fun skipOpeningPhoto() {
        val context = _awaitingOpeningPhoto.value ?: return
        _awaitingOpeningPhoto.value = null
        send(context, labelsFor(context), openingPhoto = null)
    }

    /**
     * A complete intake always names its language, so this needs no device fallback: the
     * depth is only set on the last question, and reaching it means every earlier one was
     * answered.
     */
    private fun labelsFor(context: AssessmentContext): ReportLabels =
        ReportLabels.forLanguage(context.language?.code)

    private fun send(
        context: AssessmentContext,
        labels: ReportLabels,
        openingPhoto: String?,
    ) {
        viewModelScope.launch {
            _error.value = null
            _sending.value = true
            try {
                val type = _itemType.value ?: ItemType.OTHER
                if (!sessions.sessionExists(sessionId)) {
                    sessions.createSession(
                        sessionId = sessionId,
                        itemType = type,
                        previousSessionId = previousSessionId,
                        intake = context,
                    )
                }
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildIntakeMessage(context, labels),
                    attachments = listOfNotNull(
                        openingPhoto?.let { Attachment(UUID.randomUUID().toString(), it) },
                    ),
                )
                deliver(type)
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Reads the verdict on the piece this assessment was started from.
     *
     * Only offered for two pieces of the same kind. "Which of these two tables is better
     * made" is a question worth answering; a chair against a table is not a comparison,
     * it is two separate assessments, and pretending otherwise would produce a paragraph
     * of invented differences.
     */
    private suspend fun loadPreviousVerdict() {
        val previous = previousSessionId ?: return
        val start = sessions.startOf(previous) ?: return
        if (start.itemType != _itemType.value) return
        previousItemType = start.itemType
        // The last verdict in that conversation: a later one supersedes an earlier one,
        // the same rule the share button follows.
        _previousVerdict.value = sessions.messagesOnce(previous)
            .asReversed()
            .firstNotNullOfOrNull { parseAssistantContent(it.text).verdict }
            ?.takeIf { it.isRenderable }
    }

    /**
     * Asks for the two pieces to be compared, carrying the earlier one's findings into
     * this conversation.
     *
     * Sent as an ordinary turn, so the customer can see what was asked on their behalf
     * and can follow it up in their own words.
     */
    fun compareWithPrevious(labels: ReportLabels) {
        val previous = _previousVerdict.value ?: return
        if (_sending.value || _comparisonRequested.value) return
        _comparisonRequested.value = true
        send(
            buildComparisonRequest(
                previousItemName = labels.itemName(_itemType.value ?: ItemType.OTHER),
                previous = previous,
                labels = labels,
            )
        )
    }

    /** Destination for the next shot. The camera screen writes straight into it. */
    fun newCaptureFile(): File? = runCatching { images.newImageFile(sessionId) }.getOrNull()

    fun answerTest(testIndex: Int, answer: String) {
        _run.update { current -> current?.copy(answers = current.answers + (testIndex to answer)) }
    }

    fun skipTest(testIndex: Int) {
        _run.update { current -> current?.copy(answers = current.answers + (testIndex to null)) }
    }

    fun skipShot(shotIndex: Int) {
        _run.update { current -> current?.copy(shots = current.shots + (shotIndex to null)) }
    }

    /** Drops a shot so it can be taken again. The old file goes with it. */
    fun retakeShot(shotIndex: Int) {
        val existing = _run.value?.shots?.get(shotIndex)
        _run.update { current -> current?.copy(shots = current.shots.minus(shotIndex)) }
        if (existing != null) {
            viewModelScope.launch { withContext(io) { images.delete(File(existing)) } }
        }
    }

    fun changeTestAnswer(testIndex: Int) {
        _run.update { current -> current?.copy(answers = current.answers.minus(testIndex)) }
    }

    /**
     * Sends the whole run as one turn: every photo in plan order, plus the test answers
     * and anything that could not be done.
     *
     * One request instead of one per step. The old shot-by-shot flow cost a round trip
     * per photo, and because each turn re-sent every earlier image, its token cost grew
     * with the square of the shot count.
     */
    fun submitRun(labels: ReportLabels) {
        val current = _run.value ?: return
        if (_sending.value || _submitting.value) return

        viewModelScope.launch {
            _error.value = null
            _submittedRun.value = current
            _submitting.value = true
            _sending.value = true
            try {
                val type = _itemType.value ?: ItemType.OTHER
                if (!sessions.sessionExists(sessionId)) sessions.createSession(sessionId, type)
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildSubmissionText(
                        current.plan,
                        current.shots,
                        current.answers,
                        labels,
                    ),
                    attachments = current.takenPaths.map {
                        Attachment(UUID.randomUUID().toString(), it)
                    },
                )
                // Marked fulfilled before the request, not after: a failed request must
                // not re-offer a plan whose photos are already attached to a sent turn.
                fulfilled += current.sourceMessageId
                _run.value = null
                deliver(type)
            } finally {
                _sending.value = false
                _submitting.value = false
                _submittedRun.value = null
            }
        }
    }

    /** Abandons a run and deletes the photos taken for it. */
    fun discardRun() {
        val current = _run.value ?: return
        fulfilled += current.sourceMessageId
        _run.value = null
        val paths = current.takenPaths
        viewModelScope.launch {
            withContext(io) { paths.forEach { images.delete(File(it)) } }
        }
    }

    /**
     * Normalises a freshly captured file and either attaches it or holds it for review.
     *
     * The check is advisory, so it only ever interrupts: a photo it cannot measure, or
     * measures as fine, goes straight into the pending list so the user can keep working
     * through the shot list without confirming every frame.
     */
    fun onPhotoCaptured(file: File, target: CaptureTarget = CaptureTarget.Composer) {
        viewModelScope.launch {
            val prepared = withContext(io) {
                file.length() > 0 && images.normaliseInPlace(file)
            }
            if (!prepared) {
                withContext(io) { images.delete(file) }
                _notice.value = "That photo could not be read. Please take it again."
                return@launch
            }
            val warning = withContext(io) { images.measureQuality(file)?.warning }
            if (warning != null) {
                _review.value = CaptureReview(file.absolutePath, warning, target)
            } else {
                accept(file, target)
            }
        }
    }

    /** Keeps a photo the check complained about. The user has looked at it; they decide. */
    fun keepReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        accept(File(reviewed.path), reviewed.target)
    }

    private fun accept(file: File, target: CaptureTarget) {
        when (target) {
            CaptureTarget.Composer -> attach(file)
            CaptureTarget.Opening -> {
                val context = _awaitingOpeningPhoto.value ?: return
                _awaitingOpeningPhoto.value = null
                send(context, labelsFor(context), openingPhoto = file.absolutePath)
            }
            is CaptureTarget.PlanShot -> _run.update { current ->
                current?.copy(shots = current.shots + (target.index to file.absolutePath))
            }
        }
    }

    fun discardReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        viewModelScope.launch {
            withContext(io) { images.delete(File(reviewed.path)) }
        }
    }

    private fun attach(file: File) {
        _pending.update { it + PendingImage(UUID.randomUUID().toString(), file.absolutePath) }
    }

    fun onGalleryPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = withContext(io) {
                uris.mapNotNull { images.importFromUri(sessionId, it) }
            }
            if (imported.size < uris.size) {
                _error.value = ChatError(
                    ChatErrorKind.REQUEST,
                    "Some photos could not be opened and were skipped.",
                )
            }
            _pending.update { current ->
                current + imported.map { PendingImage(UUID.randomUUID().toString(), it.absolutePath) }
            }
        }
    }

    fun removePending(id: String) {
        val target = _pending.value.firstOrNull { it.id == id } ?: return
        _pending.update { list -> list.filterNot { it.id == id } }
        viewModelScope.launch {
            withContext(io) { images.delete(File(target.path)) }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun startReview() {
        if (_reviewDue.value) _reviewing.value = true
    }

    /** Closes the questionnaire without answering. The prompt stays for next time. */
    fun dismissReview() {
        _reviewing.value = false
    }

    /**
     * Stores an evaluator's review and closes the questionnaire.
     *
     * Written locally and sent by the sync rather than posted here: an evaluator finishes
     * an assessment in a workshop, which is exactly where there is no signal, and a review
     * lost to a failed request cannot be reconstructed — nobody remembers three days later
     * whether the assistant confused a dowel with a tenon.
     *
     * Stored first, then flushed. The questionnaire closes on the write, not on the send,
     * so the evaluator is never made to wait for a request — but the send is attempted
     * here rather than left for whenever they next open Reports, which is what kept the
     * first real review off the server entirely.
     */
    fun submitReview(
        mistakes: String,
        mistakesDetail: String?,
        adviceStars: Int,
        itemQuality: Int,
        extraFeedback: String?,
    ) {
        viewModelScope.launch {
            sessions.recordTesterFeedback(
                LocalTesterFeedback(
                    sessionId = sessionId,
                    mistakes = mistakes,
                    mistakesDetail = mistakesDetail?.takeIf { it.isNotBlank() },
                    adviceStars = adviceStars,
                    itemQuality = itemQuality,
                    extraFeedback = extraFeedback?.takeIf { it.isNotBlank() },
                ),
            )
            _reviewing.value = false
            _reviewDue.value = false
            // After the questionnaire has closed, so a slow or absent network is invisible
            // to the evaluator. Best-effort by construction: the answers are already on
            // disk, so a failure here — no signal, or they leave the screen straight away —
            // costs nothing but a delay, and must not take the app down in front of the one
            // person whose job is to judge it.
            runCatching { pushReviews() }
        }
    }

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = _pending.value
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_sending.value) return

        viewModelScope.launch {
            _error.value = null
            _sending.value = true
            try {
                val type = _itemType.value ?: ItemType.OTHER
                if (!sessions.sessionExists(sessionId)) {
                    sessions.createSession(sessionId, type)
                }
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = trimmed,
                    attachments = attachments.map { Attachment(it.id, it.path) },
                )
                _pending.value = emptyList()
                deliver(type)
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Re-sends the conversation as it already stands. Used after a network failure,
     * where the user's turn is already saved and must not be duplicated.
     */
    fun retry() {
        if (_sending.value) return
        viewModelScope.launch {
            _error.value = null
            _sending.value = true
            try {
                deliver(_itemType.value ?: ItemType.OTHER)
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Starts the one location fix for this assessment, if the customer left that on.
     *
     * Its own coroutine, and nothing joins it. The fix takes up to ninety seconds to
     * settle, which is time the customer spends answering the intake and taking
     * photographs — so it costs them nothing and is never waited on. A turn sent before
     * the fix lands carries no location and the next one carries it.
     *
     * Failure is silence on purpose. This is optional research data, and an assessment
     * must not show an error, stall, or behave differently because a GPS fix did not
     * arrive.
     */
    private fun captureLocation() {
        if (!captureFix.isAvailable) return
        viewModelScope.launch {
            runCatching {
                captureFix.capture()?.let { fix -> sessions.recordLocation(sessionId, fix) }
            }
        }
    }

    private suspend fun deliver(type: ItemType) {
        val history = sessions.messagesOnce(sessionId)
        when (val result = chat.send(sessionId, type, history)) {
            is ChatResult.Success -> sessions.appendAssistantMessage(sessionId, result.text)
            is ChatResult.Failure -> _error.value = ChatError(result.kind, result.message)
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            sessionId: String,
            itemType: ItemType?,
            intakePrefill: AssessmentContext? = null,
            previousSessionId: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    sessionId = sessionId,
                    declaredItemType = itemType,
                    intakePrefill = intakePrefill,
                    declaredPreviousSessionId = previousSessionId,
                    sessions = container.sessionRepository,
                    chat = container.chatService,
                    images = container.images,
                    isTester = { container.isTester },
                    pushReviews = { container.assessmentSync.pushReviews() },
                    captureFix = container.locationCapture,
                )
            }
        }
    }
}

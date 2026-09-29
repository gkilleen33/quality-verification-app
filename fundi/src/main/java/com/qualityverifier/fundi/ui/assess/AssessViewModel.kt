package com.qualityverifier.fundi.ui.assess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.domain.Role
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildFundiOpeningMessage
import com.qualityverifier.text.buildSubmissionText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.priorIssues
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What went wrong, in words the maker can act on. */
data class AssessError(val message: String)

/**
 * One assessment of one piece, from the opening photograph to the fix plan.
 *
 * A leaner cousin of Kagua's ChatViewModel rather than a reuse of it. The buyer's version
 * also carries an intake questionnaire, comparisons between two pieces, the evaluator
 * questionnaire and sharing — none of which a maker standing over their own bench has any
 * use for. What the two genuinely share is already shared: the camera and plan runner are
 * `:capture`, and storage, upload dedup and the retry are `:core`.
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
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> =
        sessions.observeMessages(sessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<AssessError?>(null)
    val error: StateFlow<AssessError?> = _error.asStateFlow()

    private val _run = MutableStateFlow<PlanRun?>(null)
    val run: StateFlow<PlanRun?> = _run.asStateFlow()

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
    private val _priorIssues = MutableStateFlow<List<PriorIssue>>(emptyList())
    val priorIssues: StateFlow<List<PriorIssue>> = _priorIssues.asStateFlow()

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

    init {
        viewModelScope.launch { _existing.value = sessions.sessionExists(sessionId) }
        viewModelScope.launch {
            sessions.startOf(sessionId)?.intakeCode?.let(FundiIntake::decode)?.let { (lang, why) ->
                _language.value = lang
                _purpose.value = why
            }
        }
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
            val turns = earlier.flatMap { summary ->
                sessions.messagesOnce(summary.id)
                    .filter { it.role == Role.ASSISTANT }
                    .asReversed()
                    .map { it.text }
            }
            _priorIssues.value = priorIssues(turns)
        }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Destination for the next shot. The camera screen writes straight into it. */
    fun newCaptureFile(): File? = runCatching { images.newImageFile(sessionId) }.getOrNull()

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
     */
    fun start(intake: FundiIntake, photoPath: String?) {
        if (_sending.value || !intake.isComplete) return
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
                    )
                }
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildFundiOpeningMessage(intake, profile, FundiLabels.ENGLISH),
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

    private suspend fun deliver() {
        when (val result = chat.send(sessionId, itemType, sessions.messagesOnce(sessionId))) {
            is ChatResult.Success -> sessions.appendAssistantMessage(sessionId, result.text)
            is ChatResult.Failure -> _error.value = AssessError(result.message)
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
                )
            }
        }
    }
}

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
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.Role
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildFundiContextMessage
import com.qualityverifier.text.buildSubmissionText
import com.qualityverifier.text.parseAssistantContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun dismissError() {
        _error.value = null
    }

    /** Destination for the next shot. The camera screen writes straight into it. */
    fun newCaptureFile(): File? = runCatching { images.newImageFile(sessionId) }.getOrNull()

    /**
     * Opens the assessment with a photograph of the whole piece and the maker's context.
     *
     * The context is a message rather than a header, so the maker can read what was said
     * about them — and `prompts/fundi-master.txt` expects it in the opening turn, with
     * each tool marked owned, borrowed or none.
     *
     * A profile that will not load does not block the assessment. Coaching that has to
     * guess at the tools is worse coaching, but it is better than refusing to look at a
     * piece somebody is standing over.
     */
    fun start(photoPath: String) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                val profile = (profiles.load() as? ProfileOutcome.Loaded)?.profile
                    ?: FundiProfile()
                if (!sessions.sessionExists(sessionId)) {
                    sessions.createSession(sessionId = sessionId, itemType = itemType)
                }
                sessions.appendUserMessage(
                    sessionId = sessionId,
                    text = buildFundiContextMessage(profile, FundiLabels.ENGLISH),
                    attachments = listOf(Attachment(UUID.randomUUID().toString(), photoPath)),
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
                sessions.appendUserMessage(sessionId, text, emptyList())
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
                        ReportLabels.ENGLISH,
                    ),
                    attachments = current.takenPaths.map {
                        Attachment(UUID.randomUUID().toString(), it)
                    },
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AssessViewModel(
                    sessionId = sessionId,
                    itemType = itemType,
                    sessions = container.sessionRepository,
                    chat = container.chatService,
                    images = container.images,
                    profiles = container.fundiProfiles,
                )
            }
        }
    }
}

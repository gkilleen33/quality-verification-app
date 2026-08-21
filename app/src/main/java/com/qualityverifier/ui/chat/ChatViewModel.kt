package com.qualityverifier.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.text.ReportLabels
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
    /** Which planned shot this belongs to, or null for a photo taken outside a plan. */
    val shotIndex: Int? = null,
)

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
    private val sessions: SessionRepository,
    private val chat: ChatService,
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

    /** Plans already collected against, so a run is never offered twice. */
    private val fulfilled = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            if (declaredItemType == null) {
                // Reopened from history: the item type lives in the session row.
                _itemType.value = sessions.itemTypeOf(sessionId)
            }
            openConversationIfEmpty()
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
     * Asks the assistant to open the conversation, so the walkthrough starts as soon as
     * the screen appears rather than waiting for the user to type something first.
     *
     * Keyed on the conversation being empty, not on the session being new: a session
     * whose opening request failed has a row but no messages, and reopening it should
     * try again rather than leave the user staring at an empty screen with no way in.
     */
    private suspend fun openConversationIfEmpty() {
        if (_sending.value) return
        if (sessions.messagesOnce(sessionId).isNotEmpty()) return

        val type = _itemType.value ?: ItemType.OTHER
        _sending.value = true
        try {
            if (!sessions.sessionExists(sessionId)) {
                sessions.createSession(sessionId, type)
            }
            deliver(type)
        } finally {
            _sending.value = false
        }
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
    fun onPhotoCaptured(file: File, shotIndex: Int? = null) {
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
                _review.value = CaptureReview(file.absolutePath, warning, shotIndex)
            } else {
                accept(file, shotIndex)
            }
        }
    }

    /** Keeps a photo the check complained about. The user has looked at it; they decide. */
    fun keepReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        accept(File(reviewed.path), reviewed.shotIndex)
    }

    private fun accept(file: File, shotIndex: Int?) {
        if (shotIndex == null) {
            attach(file)
        } else {
            _run.update { current ->
                current?.copy(shots = current.shots + (shotIndex to file.absolutePath))
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
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    sessionId = sessionId,
                    declaredItemType = itemType,
                    sessions = container.sessionRepository,
                    chat = container.chatService,
                    images = container.images,
                )
            }
        }
    }
}

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
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
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
data class CaptureReview(val path: String, val warning: String)

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

    init {
        viewModelScope.launch {
            if (declaredItemType == null) {
                // Reopened from history: the item type lives in the session row.
                _itemType.value = sessions.itemTypeOf(sessionId)
            }
            openConversationIfEmpty()
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

    /**
     * Normalises a freshly captured file and either attaches it or holds it for review.
     *
     * The check is advisory, so it only ever interrupts: a photo it cannot measure, or
     * measures as fine, goes straight into the pending list so the user can keep working
     * through the shot list without confirming every frame.
     */
    fun onPhotoCaptured(file: File) {
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
                _review.value = CaptureReview(file.absolutePath, warning)
            } else {
                attach(file)
            }
        }
    }

    /** Keeps a photo the check complained about. The user has looked at it; they decide. */
    fun keepReviewedPhoto() {
        val reviewed = _review.value ?: return
        _review.value = null
        attach(File(reviewed.path))
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

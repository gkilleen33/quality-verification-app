package com.qualityverifier.data.chat

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType

/**
 * Sends a conversation and returns Claude's reply.
 *
 * This was the main Phase 2 seam, and it held: the implementation swapped from talking
 * to `api.anthropic.com` with an on-device key to posting one turn to our own server with
 * a JWT, and no caller changed. Nobody here ever supplied a key, a URL or a system prompt,
 * which is why the swap was one file in AppContainer.
 */
interface ChatService {
    suspend fun send(
        sessionId: String,
        itemType: ItemType,
        history: List<ChatMessage>,
    ): ChatResult
}

sealed interface ChatResult {
    data class Success(val text: String) : ChatResult
    data class Failure(val kind: ChatErrorKind, val message: String) : ChatResult
}

enum class ChatErrorKind {
    /** Key missing or rejected — the UI offers a route to Settings. */
    AUTH,

    /** No connectivity or timeout. The user's turn is kept so it can be retried. */
    NETWORK,
    RATE_LIMIT,
    SERVER,

    /** Malformed request or oversized payload — surfaces the API's own message. */
    REQUEST,
    UNKNOWN,
}

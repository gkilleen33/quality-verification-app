package com.qualityverifier.data.chat

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType

/**
 * Sends a conversation and returns Claude's reply.
 *
 * This is the main Phase 2 seam. The Phase 1 implementation talks to
 * `api.anthropic.com` with the on-device key; Phase 2 posts to
 * `POST /sessions/:id/messages` on the server with a JWT. Callers see no difference —
 * they never supply a key, a URL, or a system prompt.
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

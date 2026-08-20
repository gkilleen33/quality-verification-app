package com.qualityverifier.data.session

import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.flow.Flow

/**
 * Conversation history.
 *
 * Phase 1 is Room-only. Phase 2 keeps this interface and adds server sync behind it,
 * so the home and chat screens are unaffected.
 */
interface SessionRepository {
    fun observeSummaries(): Flow<List<SessionSummary>>

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    suspend fun messagesOnce(sessionId: String): List<ChatMessage>

    suspend fun itemTypeOf(sessionId: String): ItemType?

    /** Creates the session row. Called on the first send, not on item selection. */
    suspend fun createSession(sessionId: String, itemType: ItemType)

    suspend fun sessionExists(sessionId: String): Boolean

    suspend fun appendUserMessage(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
    ): ChatMessage

    suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage

    /** Removes a message — used to roll back a failed turn the user chose not to retry. */
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteSession(sessionId: String)

    /** Housekeeping: drop image files belonging to sessions that were never saved. */
    suspend fun pruneOrphanImages()
}

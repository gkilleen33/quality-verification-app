package com.qualityverifier.data.session

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.SessionStart
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

    /**
     * What an existing assessment was started with. Null when there is no row yet, which
     * is the normal state until the first send.
     */
    suspend fun startOf(sessionId: String): SessionStart?

    /**
     * Creates the session row. Called on the first send, not on item selection.
     *
     * [previousSessionId] and [intake] are what make one assessment lead into the next:
     * the link back is what the comparison reads, and the answers are what the next
     * piece's intake carries forward instead of asking again.
     */
    suspend fun createSession(
        sessionId: String,
        itemType: ItemType,
        previousSessionId: String? = null,
        intake: AssessmentContext? = null,
    )

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

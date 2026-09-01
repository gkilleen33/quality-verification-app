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

    /** Ids already stored, so a sync only fetches what is missing. */
    suspend fun knownSessions(): Map<String, Long>

    /**
     * Writes an assessment fetched from the server, replacing any local copy.
     *
     * Ids come from the server, which got them from a phone, so this is idempotent: the
     * same assessment synced twice produces one row.
     */
    suspend fun writeSynced(session: SyncedSession, messages: List<SyncedMessage>)

    /**
     * Deletions the server has not been told about yet.
     *
     * Kept because the local row is gone the moment somebody taps delete, and without a
     * record the server would keep its copy indefinitely — making the seven days we
     * promise a customer untrue whenever the delete happened offline.
     */
    suspend fun pendingRemoteDeletes(): List<String>

    suspend fun recordPendingRemoteDelete(sessionId: String)

    suspend fun clearPendingRemoteDelete(sessionId: String)
}

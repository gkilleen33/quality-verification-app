package com.qualityverifier.data.session

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
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
     * Records where the assessment was made. A no-op once one is already recorded.
     *
     * Separate from [createSession] because the fix arrives minutes later — it resolves
     * while the customer answers the intake — and nothing waits for it.
     */
    suspend fun recordLocation(sessionId: String, fix: LocationFix)

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

    /**
     * Stores an evaluator's review of one assessment, to be sent on the next sync.
     *
     * Local first, because an evaluator finishes an assessment in a workshop and that is
     * exactly where there is no signal. A review that failed to send and was lost would
     * mean the walkthrough happened and the measurement did not, and it cannot be
     * reconstructed later.
     */
    suspend fun recordTesterFeedback(feedback: LocalTesterFeedback)

    suspend fun pendingTesterFeedback(): List<LocalTesterFeedback>

    /** Whether this assessment already has a review waiting to be sent. */
    suspend fun hasPendingTesterFeedback(sessionId: String): Boolean

    suspend fun clearTesterFeedback(sessionId: String)

    /**
     * Records that this phone dropped a report but left the server's copy alone.
     *
     * Kept so the next pull does not fetch it back. A report that reappears after being
     * deleted reads as the delete having failed, whatever we told them about our copy.
     */
    suspend fun dismissLocally(sessionId: String)

    suspend fun dismissedSessions(): Set<String>
}

/** An evaluator's review as the phone holds it, before it reaches the server. */
data class LocalTesterFeedback(
    val sessionId: String,
    /** "yes" | "no" | "unsure" */
    val mistakes: String,
    val mistakesDetail: String?,
    val adviceStars: Int,
    val itemQuality: Int,
    val extraFeedback: String?,
)

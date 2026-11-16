package com.qualityverifier.data.session

import com.qualityverifier.data.db.ReviewHelpfulEntity
import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.sync.SyncClient
import kotlinx.coroutines.flow.Flow

/**
 * "Was this review helpful?" (server V36, release scope row 7): a tester's Yes or No after a
 * Fundi Bora verdict, one per assessment. Stored here first, sent at once, and kept for the
 * next sync if the send fails, since testers work in workshops with no signal.
 *
 * Never part of the conversation: nothing here writes a message, so the answer cannot reach
 * Claude.
 */
class ReviewAnswers(
    private val dao: SessionDao,
    /** Null in tests that send nothing. */
    private val client: SyncClient?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The answer given on this phone for [sessionId], or null before one. */
    fun answerFor(sessionId: String): Flow<Boolean?> = dao.reviewHelpful(sessionId)

    /** Records the first answer for [sessionId]; a later one is ignored. Then tries to send it. */
    suspend fun answer(sessionId: String, helpful: Boolean) {
        dao.insertReviewHelpful(ReviewHelpfulEntity(sessionId, helpful, answeredAt = now()))
        push()
    }

    /** Sends every answer still waiting. Returns how many went. */
    suspend fun push(): Int {
        val client = client ?: return 0
        var sent = 0
        for (pending in dao.unsyncedReviewHelpful()) {
            if (client.answerReviewHelpful(pending.sessionId, pending.helpful)) {
                dao.markReviewHelpfulSynced(pending.sessionId, now())
                sent++
            }
        }
        return sent
    }

    /** Whether no answer is still waiting to be sent, for the sync's report. */
    suspend fun allSent(): Boolean = dao.unsyncedReviewHelpful().isEmpty()
}

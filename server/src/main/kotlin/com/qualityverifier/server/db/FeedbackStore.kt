package com.qualityverifier.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/** What an evaluator made of one assessment. */
data class TesterFeedback(
    val sessionId: String,
    /** "yes" | "no" | "unsure" — did the assistant get something wrong. */
    val mistakes: String,
    val mistakesDetail: String?,
    /** 1-5, where 1 is no help at all. */
    val adviceStars: Int,
    /** 1-10 on the furniture, where 10 has no defects. */
    val itemQuality: Int,
    val extraFeedback: String?,
) {
    companion object {
        val ALLOWED_MISTAKES = setOf("yes", "no", "unsure")

        /**
         * Why this is not acceptable, or null when it is.
         *
         * Checked on the server rather than trusted from the phone. The table has the same
         * constraints, but a violation there arrives as a 500 and tells the evaluator
         * nothing — and a research instrument that silently drops a submission is worse
         * than one that refuses it.
         */
        fun problemWith(feedback: TesterFeedback): String? = when {
            feedback.mistakes !in ALLOWED_MISTAKES -> "mistakes must be yes, no or unsure"
            feedback.adviceStars !in 1..5 -> "advice_stars must be 1 to 5"
            feedback.itemQuality !in 1..10 -> "item_quality must be 1 to 10"
            else -> null
        }
    }
}

/**
 * The evaluators' critiques of the assistant.
 *
 * Its own store rather than a corner of ChatStore: ChatStore serves the assessment itself,
 * and this is research instrumentation written by a different kind of account. Keeping them
 * apart means a mistake in one cannot widen the other.
 */
interface FeedbackStore {
    /**
     * Records or replaces the critique for one assessment.
     *
     * Returns false when the session is not this user's, which is answered as a 404 for the
     * same reason a session is: distinguishing "not yours" from "does not exist" is how an
     * id space gets enumerated.
     */
    suspend fun save(userId: String, feedback: TesterFeedback): Boolean

    suspend fun feedbackFor(sessionId: String): TesterFeedback?
}

class PostgresFeedbackStore(private val dataSource: DataSource) : FeedbackStore {

    override suspend fun save(userId: String, feedback: TesterFeedback): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // The ownership check is part of the insert rather than a query before it.
                // A select-then-insert would let a session change hands in between, and
                // more practically means one round trip instead of two on a mobile
                // connection that has already waited for a vision request.
                connection.prepareStatement(
                    """
                    insert into tester_feedback (
                        session_id, user_id, mistakes, mistakes_detail,
                        advice_stars, item_quality, extra_feedback
                    )
                    select s.id, ?::uuid, ?, ?, ?, ?, ?
                      from sessions s
                     where s.id = ?::uuid and s.user_id = ?::uuid
                    on conflict (session_id) do update set
                        mistakes        = excluded.mistakes,
                        mistakes_detail = excluded.mistakes_detail,
                        advice_stars    = excluded.advice_stars,
                        item_quality    = excluded.item_quality,
                        extra_feedback  = excluded.extra_feedback,
                        updated_at      = now()
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setString(2, feedback.mistakes)
                    statement.setString(3, feedback.mistakesDetail)
                    statement.setInt(4, feedback.adviceStars)
                    statement.setInt(5, feedback.itemQuality)
                    statement.setString(6, feedback.extraFeedback)
                    statement.setString(7, feedback.sessionId)
                    statement.setString(8, userId)
                    // Zero rows means the select matched nothing: no such session, or not
                    // theirs. Either way the answer to the caller is the same.
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun feedbackFor(sessionId: String): TesterFeedback? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select session_id::text, mistakes, mistakes_detail,
                           advice_stars, item_quality, extra_feedback
                      from tester_feedback where session_id = ?::uuid
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else TesterFeedback(
                            sessionId = rows.getString(1),
                            mistakes = rows.getString(2),
                            mistakesDetail = rows.getString(3),
                            adviceStars = rows.getInt(4),
                            itemQuality = rows.getInt(5),
                            extraFeedback = rows.getString(6),
                        )
                    }
                }
            }
        }
}

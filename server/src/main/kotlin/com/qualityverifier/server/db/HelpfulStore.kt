package com.qualityverifier.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * A tester's "Was this review helpful?" after a Fundi Bora verdict (V36, release scope row 7).
 *
 * Its own store rather than [FeedbackStore]: Kagua's critique is a form with several answers
 * that a later submission replaces, and this is one yes or no that the first answer settles.
 * Nothing that builds a Claude request reads it.
 */
interface HelpfulStore {
    /**
     * Records [helpful] for the account's own Fundi Bora assessment, if it has a verdict and
     * no answer yet. A second answer records nothing, whatever it says.
     */
    suspend fun answer(userId: String, sessionId: String, helpful: Boolean): HelpfulAnswer
}

sealed interface HelpfulAnswer {
    /** The answer now held, which is the first one sent. */
    data class Held(val helpful: Boolean) : HelpfulAnswer
    /** Not this account's Fundi Bora assessment, or not one with a verdict. */
    data object NoSuchVerdict : HelpfulAnswer
}

class PostgresHelpfulStore(private val dataSource: DataSource) : HelpfulStore {

    override suspend fun answer(userId: String, sessionId: String, helpful: Boolean): HelpfulAnswer =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // The ownership and verdict checks are part of the insert, as in
                // PostgresFeedbackStore: one round trip, and no gap between check and write.
                connection.prepareStatement(
                    """
                    insert into fundi_review_helpful (session_id, user_id, helpful)
                    select s.id, s.user_id, ?
                      from sessions s
                     where s.id = ?::uuid and s.user_id = ?::uuid and s.audience = 'fundi'
                       and s.verdict_level_id is not null and s.client_deleted_at is null
                    on conflict (session_id) do nothing
                    """.trimIndent()
                ).use { statement ->
                    statement.setBoolean(1, helpful)
                    statement.setString(2, sessionId)
                    statement.setString(3, userId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    select h.helpful
                      from fundi_review_helpful h
                      join sessions s on s.id = h.session_id
                     where h.session_id = ?::uuid and s.user_id = ?::uuid
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, userId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) HelpfulAnswer.Held(rows.getBoolean(1)) else HelpfulAnswer.NoSuchVerdict
                    }
                }
            }
        }
}

package com.qualityverifier.server.db

import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.chat.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

sealed interface SessionAccess {
    data class Ok(val created: Boolean) : SessionAccess
    /** The session exists and belongs to somebody else. Answered as a 404, never a 403. */
    data object NotYours : SessionAccess
}

/** A turn already dealt with, so a retry returns the stored reply instead of paying twice. */
data class StoredReply(val messageId: String, val text: String)

/**
 * What the chat route needs from storage.
 *
 * An interface for the same reason AuthStore is one: the decisions worth testing are in
 * the route — refusing another user's session, refusing before spending upstream when a
 * photo is missing, handing back a stored reply instead of paying for it twice — and none
 * of them need Postgres to state. The SQL is verified against the real database.
 */
interface ChatStore {
    suspend fun ensureSession(
        sessionId: String,
        userId: String,
        itemTypeId: String,
        previousSessionId: String?,
        intakeAnswers: String?,
        promptSha: String?,
    ): SessionAccess

    suspend fun appendUserTurn(
        sessionId: String,
        messageId: String,
        text: String,
        blobHashes: List<String>,
    ): Boolean

    suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply?

    suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage>

    suspend fun appendAssistantTurn(
        sessionId: String,
        text: String,
        preview: String,
        verdictLevelId: String?,
        verdictLanguage: String?,
    ): String

    suspend fun recordUsage(
        userId: String,
        sessionId: String?,
        model: String?,
        usage: TokenUsage?,
        httpStatus: Int?,
        latencyMillis: Long,
        errorKind: String?,
    )
}

class PostgresChatStore(private val dataSource: DataSource) : ChatStore {

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Creates the session if it does not exist, or confirms it belongs to this user.
     *
     * The id comes from the phone, so this is the boundary that stops one customer
     * posting into another's assessment. A session belonging to somebody else answers
     * exactly as a session that does not exist: telling the difference would let anybody
     * enumerate which ids are real.
     */
    override suspend fun ensureSession(
        sessionId: String,
        userId: String,
        itemTypeId: String,
        previousSessionId: String?,
        intakeAnswers: String?,
        promptSha: String?,
    ): SessionAccess = tx { connection ->
        val owner = connection.prepareStatement(
            "select user_id::text from sessions where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (owner != null) {
            return@tx if (owner == userId) SessionAccess.Ok(created = false) else SessionAccess.NotYours
        }

        connection.prepareStatement(
            """
            insert into sessions (
                id, user_id, item_type_id, previous_session_id, intake_answers, prompt_sha
            ) values (?::uuid, ?::uuid, ?, ?::uuid, ?, ?)
            on conflict (id) do nothing
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, userId)
            statement.setString(3, itemTypeId)
            statement.setString(4, previousSessionId)
            statement.setString(5, intakeAnswers)
            statement.setString(6, promptSha)
            statement.executeUpdate()
        }
        SessionAccess.Ok(created = true)
    }

    /**
     * Stores the customer's turn, keyed on the id the phone generated.
     *
     * Idempotent by that id: a retry after a lost response must not append the turn
     * twice. Returns false when the turn was already there, which the route uses to
     * decide whether the upstream call still needs making.
     */
    override suspend fun appendUserTurn(
        sessionId: String,
        messageId: String,
        text: String,
        blobHashes: List<String>,
    ): Boolean = tx { connection ->
        val inserted = connection.prepareStatement(
            """
            insert into messages (id, session_id, role, text, ordinal)
            select ?::uuid, ?::uuid, 'USER', ?,
                   coalesce(max(ordinal), -1) + 1 from messages where session_id = ?::uuid
            on conflict (id) do nothing
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, messageId)
            statement.setString(2, sessionId)
            statement.setString(3, text)
            statement.setString(4, sessionId)
            statement.executeUpdate() > 0
        }
        if (!inserted) return@tx false

        blobHashes.forEachIndexed { index, sha ->
            connection.prepareStatement(
                """
                insert into attachments (id, message_id, sha256, mime_type, ordinal)
                values (gen_random_uuid(), ?::uuid, ?, 'image/jpeg', ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, messageId)
                statement.setString(2, sha.lowercase())
                statement.setInt(3, index)
                statement.executeUpdate()
            }
        }
        touch(connection, sessionId, text)
        true
    }

    /** The reply already stored after this turn, if the customer is retrying. */
    override suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select m.id::text, m.text from messages m
                    where m.session_id = ?::uuid and m.role = 'ASSISTANT'
                      and m.ordinal > (select ordinal from messages where id = ?::uuid)
                    order by m.ordinal limit 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, userMessageId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) StoredReply(rows.getString(1), rows.getString(2)) else null
                    }
                }
            }
        }

    /**
     * The whole conversation, in the order the request has to reproduce.
     *
     * Ordered by (ordinal, id) at both levels: message order is the conversation, and
     * attachment order is the order the customer took the photos, which the protocols
     * refer to by position. Both have to be reproducible turn after turn or the cached
     * prefix stops matching.
     */
    override suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val messages = mutableListOf<ChatMessage>()
                connection.prepareStatement(
                    """
                    select id::text, role, text, created_at
                    from messages where session_id = ?::uuid order by ordinal, id
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            messages += ChatMessage(
                                id = rows.getString(1),
                                role = if (rows.getString(2) == "USER") Role.USER else Role.ASSISTANT,
                                text = rows.getString(3),
                                createdAt = rows.getTimestamp(4).time,
                            )
                        }
                    }
                }
                val attachments = mutableMapOf<String, MutableList<Attachment>>()
                connection.prepareStatement(
                    """
                    select a.id::text, a.message_id::text, a.sha256, a.mime_type
                    from attachments a join messages m on m.id = a.message_id
                    where m.session_id = ?::uuid order by a.message_id, a.ordinal, a.id
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            attachments.getOrPut(rows.getString(2)) { mutableListOf() } += Attachment(
                                id = rows.getString(1),
                                path = blobPath(rows.getString(3)),
                                mimeType = rows.getString(4),
                            )
                        }
                    }
                }
                messages.map { it.copy(attachments = attachments[it.id].orEmpty()) }
            }
        }

    override suspend fun appendAssistantTurn(
        sessionId: String,
        text: String,
        preview: String,
        verdictLevelId: String?,
        verdictLanguage: String?,
    ): String = tx { connection ->
        val id = connection.prepareStatement(
            """
            insert into messages (id, session_id, role, text, ordinal)
            select gen_random_uuid(), ?::uuid, 'ASSISTANT', ?,
                   coalesce(max(ordinal), -1) + 1 from messages where session_id = ?::uuid
            returning id::text
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, text)
            statement.setString(3, sessionId)
            statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
        touch(connection, sessionId, preview)
        if (verdictLevelId != null) {
            connection.prepareStatement(
                "update sessions set verdict_level_id = ?, verdict_language = ? where id = ?::uuid"
            ).use { statement ->
                statement.setString(1, verdictLevelId)
                statement.setString(2, verdictLanguage)
                statement.setString(3, sessionId)
                statement.executeUpdate()
            }
        }
        id
    }

    /**
     * One row per upstream call, written whether it succeeded or not — a failed call can
     * still have burned tokens, and a bill nobody can account for is worse than a slow one.
     */
    override suspend fun recordUsage(
        userId: String,
        sessionId: String?,
        model: String?,
        usage: TokenUsage?,
        httpStatus: Int?,
        latencyMillis: Long,
        errorKind: String?,
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into usage_events (
                    user_id, session_id, model, input_tokens, output_tokens,
                    cache_read_tokens, cache_creation_tokens, http_status, latency_ms, error_kind
                ) values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, sessionId)
                statement.setString(3, model)
                statement.setInt(4, usage?.inputTokens ?: 0)
                statement.setInt(5, usage?.outputTokens ?: 0)
                statement.setInt(6, usage?.cacheReadTokens ?: 0)
                statement.setInt(7, usage?.cacheCreationTokens ?: 0)
                if (httpStatus == null) statement.setNull(8, java.sql.Types.INTEGER)
                else statement.setInt(8, httpStatus)
                statement.setInt(9, latencyMillis.toInt())
                statement.setString(10, errorKind)
                statement.executeUpdate()
            }
            Unit
        }
    }

    private fun touch(connection: Connection, sessionId: String, preview: String) {
        connection.prepareStatement(
            "update sessions set updated_at = now(), preview_text = ? where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, preview.take(160))
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }
    }
}

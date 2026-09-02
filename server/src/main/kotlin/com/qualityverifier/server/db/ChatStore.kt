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

    /**
     * This account has started its allowance of assessments for the day.
     *
     * Only ever returned for a *new* assessment. An assessment already under way carries
     * on to the end: the earlier turns have been paid for either way, and cutting somebody
     * off halfway is both the most expensive moment to stop and the most useless answer to
     * give somebody standing in a workshop.
     */
    data class DailyLimitReached(val limit: Int) : SessionAccess
}

/** A turn already dealt with, so a retry returns the stored reply instead of paying twice. */
data class StoredReply(val messageId: String, val text: String)

/** A session as the phone needs it back — enough to rebuild the reports list. */
data class SessionRow(
    val id: String,
    val itemTypeId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
    val messageCount: Int,
    val verdictLevelId: String?,
    val verdictLanguage: String?,
    val previousSessionId: String?,
    val intakeAnswers: String?,
)

data class MessageRow(
    val id: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    val createdAt: Long,
    val blobs: List<String>,
)

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
        /** Assessments allowed per day. Zero or less disables the check. */
        dailyLimit: Int,
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

    /** Everything this user has that they have not deleted. */
    suspend fun sessionsFor(userId: String): List<SessionRow>

    /** Null when the session does not exist or is not theirs — the caller answers 404 either way. */
    suspend fun sessionDetail(userId: String, sessionId: String): Pair<SessionRow, List<MessageRow>>?

    /**
     * Marks a session deleted by the customer. Returns false when it was not theirs.
     *
     * A flag, not a delete: the row stays for the retention window, which is the whole
     * point of having one.
     */
    suspend fun markClientDeleted(userId: String, sessionId: String): Boolean

    /**
     * Whether [userId] has a live assessment that refers to the photo [sha].
     *
     * Blob storage is content-addressed, so the hash is the only thing identifying a
     * photo and nothing about it is scoped to an account. Without this check, being
     * signed in as anybody would be enough to read anybody's photographs given their
     * hash — and hashes travel: they are in server logs, in the admin portal, and in
     * research exports.
     *
     * Deleted sessions do not count. A customer who deletes a report is told it is gone
     * from their phone; continuing to serve its photographs to that same account would
     * make the delete button a lie in the one direction that matters.
     */
    suspend fun blobBelongsTo(userId: String, sha: String): Boolean
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
        dailyLimit: Int,
    ): SessionAccess = tx { connection ->
        val owner = connection.prepareStatement(
            "select user_id::text from sessions where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (owner != null) {
            // An assessment already under way is never refused, whatever the count says.
            return@tx if (owner == userId) SessionAccess.Ok(created = false) else SessionAccess.NotYours
        }

        if (dailyLimit > 0) {
            // Serialise new assessments per user for the rest of this transaction. Without
            // it, two requests arriving together both read the same count and both insert,
            // and a limit that can be exceeded by racing is not a limit.
            connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))").use {
                it.setString(1, userId)
                it.execute()
            }
            val startedToday = connection.prepareStatement(
                """
                select count(*)::int from sessions
                where user_id = ?::uuid
                  and (created_at at time zone 'Africa/Kampala')::date
                      = (now() at time zone 'Africa/Kampala')::date
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
            // The customer's own day, not UTC's. Uganda and Kenya are both UTC+3, so one
            // zone covers everybody; on UTC the allowance would reset at 3am local, which
            // is defensible but makes "resets at midnight" untrue.
            if (startedToday >= dailyLimit) return@tx SessionAccess.DailyLimitReached(dailyLimit)
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


    override suspend fun sessionsFor(userId: String): List<SessionRow> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select s.id::text, s.item_type_id,
                           (extract(epoch from s.created_at) * 1000)::bigint,
                           (extract(epoch from s.updated_at) * 1000)::bigint,
                           s.preview_text, count(m.id)::int,
                           s.verdict_level_id, s.verdict_language,
                           s.previous_session_id::text, s.intake_answers
                    from sessions s left join messages m on m.session_id = s.id
                    where s.user_id = ?::uuid and s.client_deleted_at is null
                    group by s.id
                    order by s.updated_at desc
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { rows ->
                        val out = mutableListOf<SessionRow>()
                        while (rows.next()) out += rows.toSessionRow()
                        out
                    }
                }
            }
        }

    override suspend fun sessionDetail(
        userId: String,
        sessionId: String,
    ): Pair<SessionRow, List<MessageRow>>? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val session = connection.prepareStatement(
                """
                select s.id::text, s.item_type_id,
                       (extract(epoch from s.created_at) * 1000)::bigint,
                       (extract(epoch from s.updated_at) * 1000)::bigint,
                       s.preview_text, count(m.id)::int,
                       s.verdict_level_id, s.verdict_language,
                       s.previous_session_id::text, s.intake_answers
                from sessions s left join messages m on m.session_id = s.id
                where s.id = ?::uuid and s.user_id = ?::uuid and s.client_deleted_at is null
                group by s.id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.toSessionRow() else null
                }
            } ?: return@withContext null

            val blobs = mutableMapOf<String, MutableList<String>>()
            connection.prepareStatement(
                """
                select a.message_id::text, a.sha256 from attachments a
                join messages m on m.id = a.message_id
                where m.session_id = ?::uuid order by a.message_id, a.ordinal, a.id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        blobs.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getString(2)
                    }
                }
            }

            val messages = mutableListOf<MessageRow>()
            connection.prepareStatement(
                """
                select id::text, role, text, ordinal,
                       (extract(epoch from created_at) * 1000)::bigint
                from messages where session_id = ?::uuid order by ordinal, id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val id = rows.getString(1)
                        messages += MessageRow(
                            id = id,
                            role = rows.getString(2),
                            text = rows.getString(3),
                            ordinal = rows.getInt(4),
                            createdAt = rows.getLong(5),
                            blobs = blobs[id].orEmpty(),
                        )
                    }
                }
            }
            session to messages
        }
    }

    override suspend fun markClientDeleted(userId: String, sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    update sessions set client_deleted_at = now()
                    where id = ?::uuid and user_id = ?::uuid and client_deleted_at is null
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, userId)
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun blobBelongsTo(userId: String, sha: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select exists (
                        select 1
                        from attachments a
                        join messages m on m.id = a.message_id
                        join sessions s on s.id = m.session_id
                        where a.sha256 = ?
                          and s.user_id = ?::uuid
                          and s.client_deleted_at is null
                    )
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sha)
                    statement.setString(2, userId)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
        }

    private fun java.sql.ResultSet.toSessionRow() = SessionRow(
        id = getString(1),
        itemTypeId = getString(2),
        createdAt = getLong(3),
        updatedAt = getLong(4),
        preview = getString(5),
        messageCount = getInt(6),
        verdictLevelId = getString(7),
        verdictLanguage = getString(8),
        previousSessionId = getString(9),
        intakeAnswers = getString(10),
    )

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

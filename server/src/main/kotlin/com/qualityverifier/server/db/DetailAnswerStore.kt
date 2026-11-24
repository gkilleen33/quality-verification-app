package com.qualityverifier.server.db

import com.qualityverifier.text.DetailKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.sql.DataSource

/** A detail answer as the tap that opens it needs it: its row and its words. */
data class StoredDetail(val id: String, val text: String)

/**
 * One detail answer to store (V37): the answer to one detail button of one improvement in a
 * Fundi Bora diagnosis, prefetched when the diagnosis arrived or asked for on a tap.
 */
data class NewDetailAnswer(
    val sessionId: String,
    /** The reply whose improvement it answers; null when no stored diagnosis names the title. */
    val sourceMessageId: String?,
    /** The improvement's place in that reply's findings, from 0; null as above. */
    val findingIndex: Int?,
    val findingTitle: String,
    val kind: DetailKind,
    /** "prefetch" or "tap". */
    val source: String,
    val text: String,
    val model: String?,
    /** The thinking effort the call asked for, or null for the API's default. */
    val effort: String?,
    val promptSha: String?,
    val usageEventId: Long?,
    /** For a tap, the assistant turn that showed it, which is its first opening. */
    val openedMessageId: String? = null,
)

/**
 * Every detail answer, prefetched or tapped (row 38; Nathan, 23 November 2026).
 *
 * Kept apart from `messages` on purpose: an answer joins the conversation, and so the history
 * sent to Claude, only when the fundi opens it, as the composed turn and reply a tap made
 * before v0.7.0. An answer never opened never reaches Claude. This table is the research
 * record of all of them, opened or not, which from prompt E (#107) is the only place a
 * finding's cause and habit are written unless a fundi asks.
 */
interface DetailAnswerStore {
    /** Stores one answer and returns its id. */
    suspend fun save(answer: NewDetailAnswer): String

    /**
     * The newest stored answer to [kind] of the improvement titled [title] in this session,
     * or null. The title is matched as the phone sends it, trimmed.
     */
    suspend fun find(sessionId: String, kind: DetailKind, title: String): StoredDetail?

    /**
     * Records that the fundi opened [answerId], shown as [messageId]. The first opening stands:
     * a second tap on the same answer leaves the time and the message as they were.
     */
    suspend fun markOpened(answerId: String, messageId: String)
}

class PostgresDetailAnswerStore(private val dataSource: DataSource) : DetailAnswerStore {

    override suspend fun save(answer: NewDetailAnswer): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into fundi_detail_answers (
                    id, session_id, source_message_id, finding_index, finding_title, detail_kind,
                    source, answer_text, model, effort, prompt_sha, usage_event_id,
                    opened_at, opened_message_id
                ) values (
                    ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    case when ?::uuid is null then null else now() end, ?::uuid
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, answer.sessionId)
                statement.setString(3, answer.sourceMessageId)
                if (answer.findingIndex == null) statement.setNull(4, java.sql.Types.INTEGER)
                else statement.setInt(4, answer.findingIndex)
                statement.setString(5, answer.findingTitle)
                statement.setString(6, answer.kind.id)
                statement.setString(7, answer.source)
                statement.setString(8, answer.text)
                statement.setString(9, answer.model)
                statement.setString(10, answer.effort)
                statement.setString(11, answer.promptSha)
                if (answer.usageEventId == null) statement.setNull(12, java.sql.Types.BIGINT)
                else statement.setLong(12, answer.usageEventId)
                statement.setString(13, answer.openedMessageId)
                statement.setString(14, answer.openedMessageId)
                statement.executeUpdate()
            }
        }
        id
    }

    override suspend fun find(sessionId: String, kind: DetailKind, title: String): StoredDetail? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select id::text, answer_text from fundi_detail_answers
                     where session_id = ?::uuid and detail_kind = ? and finding_title = ?
                       and answer_text is not null
                     order by created_at desc
                     limit 1
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, kind.id)
                    statement.setString(3, title.trim())
                    statement.executeQuery().use { rows ->
                        if (rows.next()) StoredDetail(rows.getString(1), rows.getString(2)) else null
                    }
                }
            }
        }

    override suspend fun markOpened(answerId: String, messageId: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update fundi_detail_answers
                   set opened_at = now(), opened_message_id = ?::uuid
                 where id = ?::uuid and opened_at is null
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, messageId)
                statement.setString(2, answerId)
                statement.executeUpdate()
            }
            Unit
        }
    }
}

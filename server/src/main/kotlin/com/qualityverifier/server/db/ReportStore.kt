package com.qualityverifier.server.db

import com.qualityverifier.domain.WorkPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

/**
 * What the PDF reports read, and the one row each report writes (V32).
 *
 * Its own store rather than more methods on ChatStore or FundiStore: a report reads a whole
 * piece at once, every assessment with its replies, photos and workers, which no other
 * route does, and every fake in the chat and fundi tests would otherwise grow methods they
 * never call. The SQL is checked against the real database.
 */
interface ReportStore {
    /** The piece, or null unless it is this account's: the route answers 404 either way. */
    suspend fun piece(userId: String, pieceId: String): ReportPiece?

    /**
     * Every assessment of the piece, oldest first, with its replies, photos and workers.
     * Client-deleted ones are included and marked: a customer report shows every
     * assessment, and the part and full reports leave the deleted ones out themselves.
     */
    suspend fun sessionsOf(pieceId: String): List<ReportSession>

    /** The name the account registered with, printed as who made the report. Null once deleted. */
    suspend fun accountName(userId: String): String?

    /** Writes the row for one report issued, cached or rendered, and returns its id. */
    suspend fun record(row: ReportRow): String

    /** Whether this account is linked (V31's two-sided match) to at least one studio, so it can send "My manager". */
    suspend fun hasLinkedStudio(userId: String): Boolean

    /**
     * The roster entry [workerId] of [userId]'s roster, with its name, when the entry is
     * live and the match holds for the account its number belongs to. Null when the entry
     * is not this account's (the route answers 404) or not linked (403): a report on a
     * worker's work exists only while the link does.
     */
    suspend fun linkedWorker(userId: String, workerId: String): LinkedWorker?

    /** Whether [workerId] is an entry on [userId]'s roster at all, linked or not. */
    suspend fun ownsWorker(userId: String, workerId: String): Boolean

    /**
     * Every piece of [userId] with an assessment that names [workerId], each with every
     * assessment of the piece (so a named one is placed among the others), oldest piece
     * first by its first naming.
     */
    suspend fun piecesNaming(userId: String, workerId: String): List<PieceSessions>
}

/** A roster entry the match holds for. [name] is non-null: a cleared name's number is cleared too, which ends the link. */
data class LinkedWorker(val workerId: String, val name: String)

data class PieceSessions(val piece: ReportPiece, val sessions: List<ReportSession>)

data class ReportPiece(
    val id: String,
    val userId: String,
    val itemTypeId: String,
    val label: String?,
)

/** One assessment as stored: its replies in conversation order, its photos, and its workers. */
data class ReportSession(
    val id: String,
    /** Epoch millis. */
    val createdAt: Long,
    val clientDeleted: Boolean,
    val intakeAnswers: String?,
    val verdictLanguage: String?,
    val messageCount: Int,
    /** The assistant's replies, oldest first, each with the id of its message. */
    val replies: List<ReportReply>,
    /** Every photo's hash, in the order the maker took them: message order, then attachment order. */
    val photos: List<String>,
    /** Who worked on it, as `session_workers` records it, in insertion order. */
    val workers: List<ReportWorker>,
)

data class ReportReply(val messageId: String, val text: String)

/**
 * One `session_workers` row with its name. A null [workerId] is the manager; a null [part]
 * is the whole piece; a null [name] is a roster entry whose name `anonymise_user` cleared,
 * which no report prints.
 */
data class ReportWorker(val workerId: String?, val part: WorkPart?, val name: String?)

/** The row for one report, as V32 stores it. [defectSnapshot] is the JSON text. */
data class ReportRow(
    val pieceId: String?,
    val userId: String,
    val kind: String,
    val sessionIds: List<String>,
    val defectRefs: List<String>,
    val defectSnapshot: String,
    val parts: List<String>?,
    val workerId: String?,
    val printedNames: List<String>,
    val sha256: String,
    val byteSize: Int,
)

class PostgresReportStore(private val dataSource: DataSource) : ReportStore {

    private suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { dataSource.connection.use(block) }

    override suspend fun piece(userId: String, pieceId: String): ReportPiece? = read { connection ->
        connection.prepareStatement(
            "select id::text, user_id::text, item_type_id, label from pieces where id = ?::uuid and user_id = ?::uuid"
        ).use { statement ->
            statement.setString(1, pieceId)
            statement.setString(2, userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) ReportPiece(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
                else null
            }
        }
    }

    override suspend fun sessionsOf(pieceId: String): List<ReportSession> = read { connection ->
        val replies = mutableMapOf<String, MutableList<ReportReply>>()
        connection.prepareStatement(
            """
            select m.session_id::text, m.id::text, m.text
              from messages m join sessions s on s.id = m.session_id
             where s.piece_id = ?::uuid and m.role = 'ASSISTANT'
             order by m.session_id, m.ordinal, m.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, pieceId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    replies.getOrPut(rows.getString(1)) { mutableListOf() } +=
                        ReportReply(rows.getString(2), rows.getString(3))
                }
            }
        }

        // Message order, then the order the maker took the photos within a message: the
        // first photo of the first turn is the opening shot the report prints first.
        val photos = mutableMapOf<String, MutableList<String>>()
        connection.prepareStatement(
            """
            select m.session_id::text, a.sha256
              from attachments a
              join messages m on m.id = a.message_id
              join sessions s on s.id = m.session_id
             where s.piece_id = ?::uuid
             order by m.session_id, m.ordinal, m.id, a.ordinal, a.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, pieceId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    photos.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getString(2).lowercase()
                }
            }
        }

        val workers = mutableMapOf<String, MutableList<ReportWorker>>()
        connection.prepareStatement(
            """
            select sw.session_id::text, sw.worker_id::text, sw.part, w.name
              from session_workers sw
              join sessions s on s.id = sw.session_id
              left join workers w on w.id = sw.worker_id
             where s.piece_id = ?::uuid
             order by sw.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, pieceId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    workers.getOrPut(rows.getString(1)) { mutableListOf() } += ReportWorker(
                        workerId = rows.getString(2),
                        part = rows.getString(3)?.let(WorkPart::fromId),
                        name = rows.getString(4),
                    )
                }
            }
        }

        connection.prepareStatement(
            """
            select s.id::text, (extract(epoch from s.created_at) * 1000)::bigint,
                   (s.client_deleted_at is not null), s.intake_answers, s.verdict_language,
                   (select count(*)::int from messages m where m.session_id = s.id)
              from sessions s
             where s.piece_id = ?::uuid
             order by s.created_at, s.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, pieceId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ReportSession>()
                while (rows.next()) {
                    val id = rows.getString(1)
                    out += ReportSession(
                        id = id,
                        createdAt = rows.getLong(2),
                        clientDeleted = rows.getBoolean(3),
                        intakeAnswers = rows.getString(4),
                        verdictLanguage = rows.getString(5),
                        messageCount = rows.getInt(6),
                        replies = replies[id].orEmpty(),
                        photos = photos[id].orEmpty(),
                        workers = workers[id].orEmpty(),
                    )
                }
                out
            }
        }
    }

    override suspend fun accountName(userId: String): String? = read { connection ->
        connection.prepareStatement("select display_name from users where id = ?::uuid").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    override suspend fun record(row: ReportRow): String = read { connection ->
        connection.prepareStatement(
            """
            insert into reports (
                piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot,
                parts, worker_id, printed_names, sha256, byte_size
            ) values (
                ?::uuid, ?::uuid, ?, ?::uuid[], ?::text[], ?::jsonb, ?::text[], ?::uuid, ?::text[], ?, ?
            )
            returning id::text
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, row.pieceId)
            statement.setString(2, row.userId)
            statement.setString(3, row.kind)
            // Sent as text[] and cast element by element in SQL, which pgjdbc handles for
            // every array here; a uuid[] parameter would need a driver-specific type.
            statement.setArray(4, connection.createArrayOf("text", row.sessionIds.toTypedArray()))
            statement.setArray(5, connection.createArrayOf("text", row.defectRefs.toTypedArray()))
            statement.setString(6, row.defectSnapshot)
            if (row.parts == null) statement.setNull(7, Types.ARRAY)
            else statement.setArray(7, connection.createArrayOf("text", row.parts.toTypedArray()))
            statement.setString(8, row.workerId)
            statement.setArray(9, connection.createArrayOf("text", row.printedNames.toTypedArray()))
            statement.setString(10, row.sha256)
            statement.setInt(11, row.byteSize)
            statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
    }

    override suspend fun hasLinkedStudio(userId: String): Boolean = read { connection ->
        connection.prepareStatement("select exists (select 1 from worker_links where worker_user_id = ?::uuid)").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    override suspend fun linkedWorker(userId: String, workerId: String): LinkedWorker? = read { connection ->
        // Linked by the pair, as FundiStore.listWorkers reads it: the match holds for this
        // roster's account and the account the entry's number belongs to.
        connection.prepareStatement(
            """
            select w.name from workers w
             where w.id = ?::uuid and w.user_id = ?::uuid
               and w.archived_at is null and w.phone is not null and w.name is not null
               and exists (
                   select 1 from worker_links l join users u on u.id = l.worker_user_id
                    where l.studio_user_id = w.user_id and u.phone = w.phone
               )
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workerId)
            statement.setString(2, userId)
            statement.executeQuery().use { rows -> if (rows.next()) LinkedWorker(workerId, rows.getString(1)) else null }
        }
    }

    override suspend fun ownsWorker(userId: String, workerId: String): Boolean = read { connection ->
        connection.prepareStatement("select exists (select 1 from workers where id = ?::uuid and user_id = ?::uuid)").use { statement ->
            statement.setString(1, workerId)
            statement.setString(2, userId)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    override suspend fun piecesNaming(userId: String, workerId: String): List<PieceSessions> {
        val pieces = read { connection ->
            connection.prepareStatement(
                """
                select p.id::text, p.user_id::text, p.item_type_id, p.label
                  from pieces p
                 where p.user_id = ?::uuid
                   and exists (
                       select 1 from sessions s join session_workers sw on sw.session_id = s.id
                        where s.piece_id = p.id and sw.worker_id = ?::uuid
                   )
                 order by (
                     select min(s.created_at) from sessions s join session_workers sw on sw.session_id = s.id
                      where s.piece_id = p.id and sw.worker_id = ?::uuid
                 ), p.id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, workerId)
                statement.setString(3, workerId)
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ReportPiece>()
                    while (rows.next()) out += ReportPiece(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
                    out
                }
            }
        }
        return pieces.map { PieceSessions(it, sessionsOf(it.id)) }
    }
}

package com.qualityverifier.server.reports

import com.qualityverifier.server.Config
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

/**
 * What the pilot's read-only measures share: arguments, the connection, the rows they
 * read, and CSV.
 *
 * Each measure is a pure function over these rows, unit-tested, and a thin `main` that
 * reads them, run on the box like `admin/CreateAdmin.kt`:
 *
 *     java -cp /opt/kagua/kagua.jar com.qualityverifier.server.reports.ProblemLinkageKt
 */

/** A Fundi Bora session and what the measures read from it. */
internal data class StoredSession(
    val id: String,
    val userId: String?,
    val pieceId: String?,
    val itemTypeId: String,
    /** Epoch millis. */
    val createdAt: Long,
    val intakeAnswers: String?,
    val byTester: Boolean,
    val deletedByUser: Boolean,
    /** In conversation order, oldest first. */
    val assistantTexts: List<String>,
)

/** The arguments every measure takes: `--since=YYYY-MM-DD` and the measure's own flags. */
internal class CommandArgs(
    private val usage: String,
    args: Array<String>,
    flags: Set<String>,
) {
    /** Epoch millis of midnight UTC on the `--since` date, or null for every session. */
    val since: Long?
    val flags: Set<String>

    init {
        var since: Long? = null
        val given = mutableSetOf<String>()
        for (arg in args) {
            when {
                arg.startsWith("--since=") -> {
                    val date = runCatching { LocalDate.parse(arg.removePrefix("--since=")) }.getOrNull()
                        ?: fail("--since takes a date, YYYY-MM-DD")
                    since = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                arg in flags -> given += arg
                else -> fail("unknown argument $arg")
            }
        }
        this.since = since
        this.flags = given
    }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        System.err.println("usage: $usage")
        exitProcess(2)
    }
}

/**
 * Runs [block] on one connection inside a read-only transaction, then rolls it back.
 *
 * Read-only twice over: Postgres refuses any write in a `READ ONLY` transaction, and the
 * transaction never commits. A plain data source rather than the server's pool, because
 * Hikari logs to stdout at start and these commands print their CSV there.
 */
internal fun <T> readOnly(block: (Connection) -> T): T {
    val config = Config.fromEnvironment().database
    if (config == null) {
        System.err.println("No database configured: KAGUA_DB_PASSWORD is not set in this environment.")
        exitProcess(1)
    }
    val source = PGSimpleDataSource().apply {
        setURL(config.url)
        user = config.user
        password = config.password
    }
    return source.connection.use { connection ->
        connection.autoCommit = false
        connection.isReadOnly = true
        try {
            block(connection)
        } finally {
            connection.rollback()
        }
    }
}

/** Every Fundi Bora session created on or after [since], oldest first, with its replies. */
internal fun fundiSessions(connection: Connection, since: Long?): List<StoredSession> {
    val replies = mutableMapOf<String, MutableList<String>>()
    connection.prepareStatement(
        """
        select m.session_id::text, m.text
          from messages m join sessions s on s.id = m.session_id
         where s.audience = 'fundi'
           and m.role = 'ASSISTANT'
           and (?::bigint is null or s.created_at >= to_timestamp(?::bigint / 1000.0))
         order by m.session_id, m.ordinal
        """.trimIndent()
    ).use { statement ->
        setSince(statement, since)
        statement.executeQuery().use { rows ->
            while (rows.next()) replies.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getString(2)
        }
    }

    return connection.prepareStatement(
        """
        select s.id::text, s.user_id::text, s.piece_id::text, s.item_type_id,
               (extract(epoch from s.created_at) * 1000)::bigint, s.intake_answers,
               coalesce(u.is_tester, false), (s.client_deleted_at is not null)
          from sessions s left join users u on u.id = s.user_id
         where s.audience = 'fundi'
           and (?::bigint is null or s.created_at >= to_timestamp(?::bigint / 1000.0))
         order by s.created_at, s.id
        """.trimIndent()
    ).use { statement ->
        setSince(statement, since)
        statement.executeQuery().use { rows ->
            val out = mutableListOf<StoredSession>()
            while (rows.next()) {
                val id = rows.getString(1)
                out += StoredSession(
                    id = id,
                    userId = rows.getString(2),
                    pieceId = rows.getString(3),
                    itemTypeId = rows.getString(4),
                    createdAt = rows.getLong(5),
                    intakeAnswers = rows.getString(6),
                    byTester = rows.getBoolean(7),
                    deletedByUser = rows.getBoolean(8),
                    assistantTexts = replies[id].orEmpty(),
                )
            }
            out
        }
    }
}

/** Binds [since] to the two placeholders of the `created_at` filter the measures share. */
internal fun setSince(statement: java.sql.PreparedStatement, since: Long?) {
    for (index in 1..2) {
        if (since == null) statement.setNull(index, java.sql.Types.BIGINT)
        else statement.setLong(index, since)
    }
}

/** One CSV row, quoted as RFC 4180 asks: only where a field needs it. */
internal fun csvRow(fields: List<Any?>): String = fields.joinToString(",") { field ->
    val text = field?.toString().orEmpty()
    if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

/** UTC, to the second: `2026-11-10T14:03:22Z`. */
internal fun isoTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

/** A share to three places, or blank where there is nothing to divide. */
internal fun share(count: Int, outOf: Int): String =
    if (outOf == 0) "" else "%.3f".format(java.util.Locale.ROOT, count.toDouble() / outOf)

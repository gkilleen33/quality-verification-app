package com.qualityverifier.server.db

import com.qualityverifier.server.reports.KaguaReportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * The customer's report links on a shop's tablet (V40, release scope row 31): one row per
 * link, which holds the token's hash and never the token, and the one assessment each PDF is
 * drawn from.
 *
 * Its own store rather than more methods on ReportStore or ChatStore: the public route that
 * downloads through it has no account and no piece, and needs nothing either of those reads.
 * The SQL is checked against the real database; the rules (30 minutes, three downloads, five
 * links an assessment) are `ShopReportService`'s, and [claimDownload] repeats them in its
 * WHERE so two downloads at once cannot both take the last one.
 */
interface ShopReportStore {
    /** V39's `shop_session_in_progress` for [userId]: the one assessment a shop's tablet may act on. */
    suspend fun sessionInProgress(userId: String): String?

    /**
     * The assessment as the PDF prints it, or null when it is gone or marked deleted. Reads
     * nothing about the account: no name, business name or number.
     */
    suspend fun session(sessionId: String): KaguaReportSession?

    /** How many links [sessionId] has had, used up or not. */
    suspend fun linkCount(sessionId: String): Int

    /** Writes the row for a new link. [ShopReportLinkRow.downloads] is zero. */
    suspend fun createLink(link: ShopReportLinkRow)

    /** The link whose token hashes to [tokenSha256], whether it still works or not. */
    suspend fun link(tokenSha256: String): ShopReportLinkRow?

    /**
     * Records one download at [at], only while the link has not expired at [at] and has had
     * fewer than [maxDownloads]. False when it took none: the caller answers as for an expired
     * link.
     */
    suspend fun claimDownload(tokenSha256: String, at: Long, maxDownloads: Int): Boolean
}

/** One V40 row. Times are epoch millis. */
data class ShopReportLinkRow(
    val tokenSha256: String,
    val sessionId: String,
    val userId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val downloads: Int = 0,
)

class PostgresShopReportStore(private val dataSource: DataSource) : ShopReportStore {

    private suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { dataSource.connection.use(block) }

    override suspend fun sessionInProgress(userId: String): String? = read { connection ->
        // The rule is V39's function, as ChatStore.shopSessionInProgress reads it.
        connection.prepareStatement("select shop_session_in_progress(?::uuid)::text").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    override suspend fun session(sessionId: String): KaguaReportSession? = read { connection ->
        val replies = connection.prepareStatement(
            """
            select m.text from messages m
             where m.session_id = ?::uuid and m.role = 'ASSISTANT'
             order by m.ordinal, m.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        connection.prepareStatement(
            """
            select s.id::text, s.item_type_id, (extract(epoch from s.created_at) * 1000)::bigint, s.verdict_language
              from sessions s
             where s.id = ?::uuid and s.client_deleted_at is null
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                KaguaReportSession(
                    id = rows.getString(1),
                    itemTypeId = rows.getString(2),
                    createdAt = rows.getLong(3),
                    verdictLanguage = rows.getString(4),
                    replies = replies,
                )
            }
        }
    }

    override suspend fun linkCount(sessionId: String): Int = read { connection ->
        connection.prepareStatement("select count(*)::int from shop_report_links where session_id = ?::uuid").use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    override suspend fun createLink(link: ShopReportLinkRow) {
        read { connection ->
            connection.prepareStatement(
                """
                insert into shop_report_links (token_sha256, session_id, user_id, created_at, expires_at)
                values (?, ?::uuid, ?::uuid, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, link.tokenSha256)
                statement.setString(2, link.sessionId)
                statement.setString(3, link.userId)
                statement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(link.createdAt)))
                statement.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(link.expiresAt)))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun link(tokenSha256: String): ShopReportLinkRow? = read { connection ->
        connection.prepareStatement(
            """
            select token_sha256, session_id::text, user_id::text,
                   (extract(epoch from created_at) * 1000)::bigint,
                   (extract(epoch from expires_at) * 1000)::bigint,
                   cardinality(downloaded_at)
              from shop_report_links
             where token_sha256 = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tokenSha256)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                ShopReportLinkRow(
                    tokenSha256 = rows.getString(1),
                    sessionId = rows.getString(2),
                    userId = rows.getString(3),
                    createdAt = rows.getLong(4),
                    expiresAt = rows.getLong(5),
                    downloads = rows.getInt(6),
                )
            }
        }
    }

    override suspend fun claimDownload(tokenSha256: String, at: Long, maxDownloads: Int): Boolean = read { connection ->
        // One statement, so two downloads at once cannot both take the third: the row lock
        // makes the second re-read the count the first wrote.
        connection.prepareStatement(
            """
            update shop_report_links
               set downloaded_at = downloaded_at || ?::timestamptz
             where token_sha256 = ?
               and expires_at > ?::timestamptz
               and cardinality(downloaded_at) < ?
            """.trimIndent()
        ).use { statement ->
            val time = Timestamp.from(Instant.ofEpochMilli(at))
            statement.setTimestamp(1, time)
            statement.setString(2, tokenSha256)
            statement.setTimestamp(3, time)
            statement.setInt(4, maxDownloads)
            statement.executeUpdate() > 0
        }
    }
}

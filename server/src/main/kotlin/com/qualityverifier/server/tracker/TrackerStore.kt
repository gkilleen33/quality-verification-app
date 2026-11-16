package com.qualityverifier.server.tracker

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.server.mpesa.CounterpartyCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import javax.sql.DataSource

/**
 * One PesaLog transaction as the server stores it (V33). As for Fundi Bora's, the
 * counterparty is already a code: the route codes it before anything reaches a store.
 */
data class TrackerTransactionRow(
    val network: MobileMoneyNetwork,
    val code: String,
    val direction: MpesaDirection,
    val type: MpesaType,
    val amountKes: BigDecimal,
    val feeKes: BigDecimal?,
    val balanceAfterKes: BigDecimal?,
    val occurredAtMillis: Long,
    val counterparty: CounterpartyCode?,
    val parserVersion: Int,
    val appVersion: Int,
)

/** What the person says one transaction was for. Both null clears it. No piece: pieces are Fundi Bora's. */
data class TrackerAnnotation(val category: MpesaCategory?, val comment: String?)

enum class TrackerAnnotationOutcome {
    SAVED,

    /** No transaction with that network and code on this account: not uploaded yet, or not theirs. */
    NO_SUCH_TRANSACTION,

    /** The category is not one the transaction's direction allows. */
    CATEGORY_DOES_NOT_FIT,
}

/** One network's count, for one day, of mobile money messages PesaLog could not read. */
data class TrackerUnreadDay(
    val network: MobileMoneyNetwork,
    val day: LocalDate,
    val unread: Int,
    val parserVersion: Int,
    val appVersion: Int,
)

/**
 * How many rows a deletion removed, which is all `tracker_deletions` keeps of them, and
 * whether it withdrew the agreement to send.
 */
data class TrackerDeletion(val transactions: Int, val unreadDays: Int, val sendingWithdrawn: Boolean = false)

/**
 * PesaLog's records. An interface for the same reason as MpesaStore: the rules worth testing
 * live in the routes, and stating them needs a fake.
 *
 * **Deletion and uploads never interleave.** Each write below takes a transaction-scoped
 * advisory lock on the account first, and each upload checks, under that lock, that the
 * account's newest `tracker_send` row is still an agreement. A deletion withdraws that
 * agreement under the same lock. So an upload the route let through a moment before the
 * deletion either lands before it, and is deleted, or finds sending withdrawn, and stores
 * nothing; it never lands after the deletion and survives it.
 */
interface TrackerStore {
    /**
     * Inserts each transaction the account does not hold yet, by network and code. One
     * already held is left as it is, annotations included. Stores nothing once the newest
     * `tracker_send` row is not an agreement. Returns how many rows were new.
     */
    suspend fun insertTransactions(userId: String, rows: List<TrackerTransactionRow>): Int

    /** Replaces the category and comment on one transaction, and stamps annotated_at. */
    suspend fun annotate(
        userId: String,
        network: MobileMoneyNetwork,
        code: String,
        annotation: TrackerAnnotation,
    ): TrackerAnnotationOutcome

    /** Replaces each network and day's count with the phone's. Nothing once sending is withdrawn. */
    suspend fun saveUnread(userId: String, days: List<TrackerUnreadDay>)

    /**
     * Deletes every transaction and unread count the account holds, withdraws the agreement
     * to send if it stands, and records in `tracker_deletions` that the deletion happened and
     * how many rows went, all in one transaction. Without the withdrawal, the next upload
     * would start refilling what the person just asked to have deleted. No consent row is
     * changed or removed; the withdrawal is a new one, as in Settings.
     */
    suspend fun deleteAll(userId: String, audience: Audience, appVersion: Int): TrackerDeletion
}

class PostgresTrackerStore(private val dataSource: DataSource) : TrackerStore {

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun insertTransactions(userId: String, rows: List<TrackerTransactionRow>): Int = tx { connection ->
        connection.lockAccount(userId)
        if (!connection.sendingAgreed(userId)) return@tx 0
        connection.prepareStatement(
            """
            insert into tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, fee_kes, balance_after_kes,
                occurred_at, counterparty_code, counterparty_basis, parser_version, app_version
            ) values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, network, code) do nothing
            """.trimIndent()
        ).use { statement ->
            rows.forEach { row ->
                statement.setString(1, userId)
                statement.setString(2, row.network.id)
                statement.setString(3, row.code)
                statement.setString(4, row.direction.id)
                statement.setString(5, row.type.id)
                statement.setBigDecimal(6, row.amountKes)
                statement.setNullableDecimal(7, row.feeKes)
                statement.setNullableDecimal(8, row.balanceAfterKes)
                statement.setTimestamp(9, Timestamp(row.occurredAtMillis))
                statement.setString(10, row.counterparty?.code)
                statement.setString(11, row.counterparty?.basis?.id)
                statement.setInt(12, row.parserVersion)
                statement.setInt(13, row.appVersion)
                statement.addBatch()
            }
            // A conflict counts 0, so the sum is the number of new rows.
            statement.executeBatch().sumOf { maxOf(it, 0) }
        }
    }

    override suspend fun annotate(
        userId: String,
        network: MobileMoneyNetwork,
        code: String,
        annotation: TrackerAnnotation,
    ): TrackerAnnotationOutcome = tx { connection ->
        val direction = connection.prepareStatement(
            "select direction from tracker_transactions where user_id = ?::uuid and network = ? and code = ? for update"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, network.id)
            statement.setString(3, code)
            statement.executeQuery().use { rows ->
                if (rows.next()) MpesaDirection.fromId(rows.getString(1)) else null
            }
        } ?: return@tx TrackerAnnotationOutcome.NO_SUCH_TRANSACTION

        if (annotation.category != null && !annotation.category.allowedFor(direction)) {
            return@tx TrackerAnnotationOutcome.CATEGORY_DOES_NOT_FIT
        }
        connection.prepareStatement(
            """
            update tracker_transactions
               set category = ?, comment = ?, annotated_at = now()
             where user_id = ?::uuid and network = ? and code = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, annotation.category?.id)
            statement.setString(2, annotation.comment)
            statement.setString(3, userId)
            statement.setString(4, network.id)
            statement.setString(5, code)
            statement.executeUpdate()
        }
        TrackerAnnotationOutcome.SAVED
    }

    override suspend fun saveUnread(userId: String, days: List<TrackerUnreadDay>): Unit = tx { connection ->
        connection.lockAccount(userId)
        if (!connection.sendingAgreed(userId)) return@tx
        connection.prepareStatement(
            """
            insert into tracker_unread_counts (user_id, network, day, unread, parser_version, app_version)
            values (?::uuid, ?, ?, ?, ?, ?)
            on conflict (user_id, network, day) do update set
                unread = excluded.unread,
                parser_version = excluded.parser_version,
                app_version = excluded.app_version,
                updated_at = now()
            """.trimIndent()
        ).use { statement ->
            days.forEach { day ->
                statement.setString(1, userId)
                statement.setString(2, day.network.id)
                statement.setObject(3, day.day)
                statement.setInt(4, day.unread)
                statement.setInt(5, day.parserVersion)
                statement.setInt(6, day.appVersion)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        Unit
    }

    override suspend fun deleteAll(userId: String, audience: Audience, appVersion: Int): TrackerDeletion = tx { connection ->
        connection.lockAccount(userId)
        // ConsentStore.withdraw's statement, here so it shares this transaction and lock.
        val withdrawn = connection.prepareStatement(
            """
            insert into consents (user_id, kind, action, audience, app_version)
            select ?::uuid, 'tracker_send', 'withdrawn', ?, ?
             where (select action from consents
                     where user_id = ?::uuid and kind = 'tracker_send'
                     order by id desc limit 1) = 'agreed'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, audience.id)
            statement.setInt(3, appVersion)
            statement.setString(4, userId)
            statement.executeUpdate() > 0
        }
        fun delete(table: String): Int =
            connection.prepareStatement("delete from $table where user_id = ?::uuid").use { statement ->
                statement.setString(1, userId)
                statement.executeUpdate()
            }
        val deletion = TrackerDeletion(
            transactions = delete("tracker_transactions"),
            unreadDays = delete("tracker_unread_counts"),
            sendingWithdrawn = withdrawn,
        )
        connection.prepareStatement(
            """
            insert into tracker_deletions (user_id, transactions_deleted, unread_days_deleted, app_version)
            values (?::uuid, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setInt(2, deletion.transactions)
            statement.setInt(3, deletion.unreadDays)
            statement.setInt(4, appVersion)
            statement.executeUpdate()
        }
        deletion
    }
}

/** Serialises this account's PesaLog writes until the transaction ends (see [TrackerStore]). */
private fun Connection.lockAccount(userId: String) {
    prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
        statement.setString(1, "tracker:$userId")
        statement.executeQuery().close()
    }
}

/** Whether the account's newest `tracker_send` row is an agreement, read under [lockAccount]. */
private fun Connection.sendingAgreed(userId: String): Boolean =
    prepareStatement(
        "select action from consents where user_id = ?::uuid and kind = 'tracker_send' order by id desc limit 1"
    ).use { statement ->
        statement.setString(1, userId)
        statement.executeQuery().use { rows -> rows.next() && rows.getString(1) == "agreed" }
    }

private fun PreparedStatement.setNullableDecimal(index: Int, value: BigDecimal?) {
    if (value == null) setNull(index, Types.NUMERIC) else setBigDecimal(index, value)
}

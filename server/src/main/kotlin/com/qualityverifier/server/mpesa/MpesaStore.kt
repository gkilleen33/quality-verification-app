package com.qualityverifier.server.mpesa

import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
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
 * One transaction as the server stores it (V29). The counterparty is already a code: the
 * route codes it before anything reaches a store, so no store ever holds the plain value.
 */
data class MpesaTransactionRow(
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

/** What the fundi says one transaction was for. Every field null clears it. */
data class MpesaAnnotation(
    val category: MpesaCategory?,
    val pieceId: String?,
    val comment: String?,
)

/** Why an annotation was not written. */
enum class AnnotationOutcome {
    SAVED,

    /** No transaction with that code on this account: not uploaded yet, or not theirs. */
    NO_SUCH_TRANSACTION,

    /** The piece is not this account's, or is not on the server yet. */
    NO_SUCH_PIECE,

    /** The category is not one the transaction's direction allows. */
    CATEGORY_DOES_NOT_FIT,
}

/** One day's count of M-Pesa messages the phone could not read. */
data class UnreadDay(val day: LocalDate, val unread: Int, val parserVersion: Int, val appVersion: Int)

/**
 * Fundi Bora's M-Pesa records. An interface for the same reason as the other stores: the
 * rules worth testing (who may upload, what a re-upload does) live in the routes, and
 * stating them needs a fake.
 */
interface MpesaStore {
    /**
     * Inserts each transaction the account does not hold yet, by code. A code already held
     * is left exactly as it is, annotations included, so a retried batch changes nothing.
     * Returns how many rows were new.
     */
    suspend fun insertTransactions(userId: String, rows: List<MpesaTransactionRow>): Int

    /** Replaces the fundi's category, piece and comment on one transaction, and stamps annotated_at. */
    suspend fun annotate(userId: String, code: String, annotation: MpesaAnnotation): AnnotationOutcome

    /** Replaces each day's count with the phone's. */
    suspend fun saveUnread(userId: String, days: List<UnreadDay>)
}

class PostgresMpesaStore(private val dataSource: DataSource) : MpesaStore {

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

    override suspend fun insertTransactions(userId: String, rows: List<MpesaTransactionRow>): Int = tx { connection ->
        connection.prepareStatement(
            """
            insert into mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, fee_kes, balance_after_kes,
                occurred_at, counterparty_code, counterparty_basis, parser_version, app_version
            ) values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (user_id, code) do nothing
            """.trimIndent()
        ).use { statement ->
            rows.forEach { row ->
                statement.setString(1, userId)
                statement.setString(2, row.code)
                statement.setString(3, row.direction.id)
                statement.setString(4, row.type.id)
                statement.setBigDecimal(5, row.amountKes)
                statement.setNullableDecimal(6, row.feeKes)
                statement.setNullableDecimal(7, row.balanceAfterKes)
                statement.setTimestamp(8, Timestamp(row.occurredAtMillis))
                statement.setString(9, row.counterparty?.code)
                statement.setString(10, row.counterparty?.basis?.id)
                statement.setInt(11, row.parserVersion)
                statement.setInt(12, row.appVersion)
                statement.addBatch()
            }
            // A conflict counts 0, so the sum is the number of new rows.
            statement.executeBatch().sumOf { maxOf(it, 0) }
        }
    }

    override suspend fun annotate(userId: String, code: String, annotation: MpesaAnnotation): AnnotationOutcome =
        tx { connection ->
            val direction = connection.prepareStatement(
                "select direction from mpesa_transactions where user_id = ?::uuid and code = ? for update"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, code)
                statement.executeQuery().use { rows ->
                    if (rows.next()) MpesaDirection.fromId(rows.getString(1)) else null
                }
            } ?: return@tx AnnotationOutcome.NO_SUCH_TRANSACTION

            if (annotation.category != null && !annotation.category.allowedFor(direction)) {
                return@tx AnnotationOutcome.CATEGORY_DOES_NOT_FIT
            }
            if (annotation.pieceId != null) {
                val ours = connection.prepareStatement(
                    "select 1 from pieces where id = ?::uuid and user_id = ?::uuid"
                ).use { statement ->
                    statement.setString(1, annotation.pieceId)
                    statement.setString(2, userId)
                    statement.executeQuery().use { it.next() }
                }
                if (!ours) return@tx AnnotationOutcome.NO_SUCH_PIECE
            }

            connection.prepareStatement(
                """
                update mpesa_transactions
                   set category = ?, piece_id = ?::uuid, comment = ?, annotated_at = now()
                 where user_id = ?::uuid and code = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, annotation.category?.id)
                if (annotation.pieceId == null) statement.setNull(2, Types.VARCHAR) else statement.setString(2, annotation.pieceId)
                statement.setString(3, annotation.comment)
                statement.setString(4, userId)
                statement.setString(5, code)
                statement.executeUpdate()
            }
            AnnotationOutcome.SAVED
        }

    override suspend fun saveUnread(userId: String, days: List<UnreadDay>): Unit = tx { connection ->
        connection.prepareStatement(
            """
            insert into mpesa_unread_counts (user_id, day, unread, parser_version, app_version)
            values (?::uuid, ?, ?, ?, ?)
            on conflict (user_id, day) do update set
                unread = excluded.unread,
                parser_version = excluded.parser_version,
                app_version = excluded.app_version,
                updated_at = now()
            """.trimIndent()
        ).use { statement ->
            days.forEach { day ->
                statement.setString(1, userId)
                statement.setObject(2, day.day)
                statement.setInt(3, day.unread)
                statement.setInt(4, day.parserVersion)
                statement.setInt(5, day.appVersion)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        Unit
    }
}

private fun PreparedStatement.setNullableDecimal(index: Int, value: BigDecimal?) {
    if (value == null) setNull(index, Types.NUMERIC) else setBigDecimal(index, value)
}

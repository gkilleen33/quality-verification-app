package com.qualityverifier.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One M-Pesa transaction Fundi Bora read from a message received after the fundi agreed
 * (MpesaIntake). Never the message itself.
 *
 * Keyed on the M-Pesa code, so the live receiver and the inbox scan reading one message
 * make one row. The whole table goes with the rest of the database when a different
 * account signs in (LocalAccountGuard), so the code needs no account beside it.
 */
@Entity(tableName = "mpesa_transactions", indices = [Index("occurredAt"), Index("pieceId")])
data class MpesaTransactionEntity(
    @PrimaryKey val code: String,
    /** MpesaDirection.id */
    val direction: String,
    /** MpesaType.id */
    val type: String,
    val amountKes: Double,
    val feeKes: Double?,
    val balanceAfterKes: Double?,
    val occurredAt: Long,
    /** On the phone only, to show the fundi who it was. The server receives these and keeps a code. */
    val counterpartyName: String?,
    val counterpartyPhone: String?,
    val parserVersion: Int,
    /** When this phone stored it. */
    val storedAt: Long,
    /** When the server confirmed it holds the row. Null until then; uploaded only while sending is on. */
    val uploadedAt: Long? = null,
    /** MpesaCategory.id, the fundi's answer to "What was it for?" */
    val category: String? = null,
    val pieceId: String? = null,
    val comment: String? = null,
    /** When the fundi last changed the three fields above. Null when they never have. */
    val annotatedAt: Long? = null,
    /** The [annotatedAt] the server last confirmed. Behind [annotatedAt] means the note is still to send. */
    val annotationSyncedAt: Long? = null,
)

/**
 * How many M-Pesa messages arriving on one Nairobi day the parser could not read: a count
 * and nothing else, so the parser's coverage can be measured without any text leaving the
 * phone.
 */
@Entity(tableName = "mpesa_unread_days")
data class MpesaUnreadDayEntity(
    /** "2026-11-12" */
    @PrimaryKey val day: String,
    val unread: Int,
    /** Raised on every new count; behind it, [syncedAt] means the day is still to send. */
    val updatedAt: Long,
    val syncedAt: Long? = null,
)

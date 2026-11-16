package com.qualityverifier.tracker.data

import androidx.room.Entity
import androidx.room.Index

/**
 * One transaction PesaLog read from a mobile money notification received after the person
 * agreed (TrackerIntake). Never the message itself.
 *
 * Keyed on network and code, as the server is (V33), so a message read twice, from an
 * updated notification, a repost, or a listener reconnecting, makes one row. The whole table
 * goes when a different account signs in (TrackerContainer's account guard).
 */
@Entity(tableName = "transactions", primaryKeys = ["network", "code"], indices = [Index("occurredAt")])
data class TransactionEntity(
    /** MobileMoneyNetwork.id */
    val network: String,
    val code: String,
    /** MpesaDirection.id */
    val direction: String,
    /** MpesaType.id */
    val type: String,
    val amountKes: Double,
    val feeKes: Double?,
    val balanceAfterKes: Double?,
    val occurredAt: Long,
    /** On the phone only, to show the person who it was. The server receives one and keeps a code. */
    val counterpartyName: String?,
    val counterpartyPhone: String?,
    val parserVersion: Int,
    val storedAt: Long,
    /** When the server confirmed it holds the row. Null until then. */
    val uploadedAt: Long? = null,
    /** MpesaCategory.id */
    val category: String? = null,
    val comment: String? = null,
    val annotatedAt: Long? = null,
    val annotationSyncedAt: Long? = null,
)

/** How many mobile money messages from one network, on one Nairobi day, PesaLog could not read. */
@Entity(tableName = "unread_days", primaryKeys = ["network", "day"])
data class UnreadDayEntity(
    val network: String,
    /** "2026-11-12" */
    val day: String,
    val unread: Int,
    val updatedAt: Long,
    val syncedAt: Long? = null,
)

/**
 * A message already counted as unread, so the same notification read again (updated in
 * place, reposted, or read again when the listener reconnects) is not counted twice. [key]
 * is a SHA-256 of the network, the time and the text: the text itself is not kept. Pruned
 * after a few days, which is longer than any notification stays on screen.
 */
@Entity(tableName = "counted_unread")
data class CountedUnreadEntity(
    @androidx.room.PrimaryKey val key: String,
    val countedAt: Long,
)

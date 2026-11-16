package com.qualityverifier.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/tracker/status`: where a PesaLog account stands with PesaLog's terms and its two
 * questions. PesaLog reads it after signing in, and after a reinstall, so it does not ask
 * again what was already agreed.
 */
@Serializable
data class TrackerStatusResponse(
    /** True when either box of PesaLog's current terms is missing. Every data route refuses until it is false. */
    @SerialName("terms_required") val termsRequired: Boolean,
    /** PesaLog's current terms and where to read each language. Null when the server holds none. */
    val terms: MeTerms?,
    /** Whether the newest tracker_transfer row is an agreement. */
    @SerialName("transfer_agreed") val transferAgreed: Boolean,
    @SerialName("read_agreed") val readAgreed: Boolean,
    @SerialName("send_agreed") val sendAgreed: Boolean,
)

/** `POST /v1/tracker/transactions`: a batch of parsed transactions. */
@Serializable
data class TrackerUploadRequest(val transactions: List<TrackerTransactionDto> = emptyList()) {
    override fun toString(): String = "TrackerUploadRequest(${transactions.size} transactions)"
}

/**
 * One transaction as the phone parsed it from a notification. Times in epoch millis, amounts
 * in shillings. As [MpesaTransactionDto], the counterparty leaves this object only as a code,
 * and [toString] leaves it out.
 */
@Serializable
data class TrackerTransactionDto(
    /** MobileMoneyNetwork.id: `mpesa` or `airtel`. */
    val network: String,
    val code: String,
    val direction: String,
    val type: String,
    @SerialName("amount_kes") val amountKes: Double,
    @SerialName("fee_kes") val feeKes: Double? = null,
    @SerialName("balance_after_kes") val balanceAfterKes: Double? = null,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("counterparty_phone") val counterpartyPhone: String? = null,
    @SerialName("counterparty_name") val counterpartyName: String? = null,
    @SerialName("parser_version") val parserVersion: Int,
) {
    override fun toString(): String =
        "TrackerTransactionDto(network=$network, code=$code, direction=$direction, type=$type)"
}

/** A transaction's identity: its code is unique only within its network. */
@Serializable
data class TrackerKey(val network: String, val code: String)

/** Which transactions the server now holds and which it refused, as [MpesaUploadResponse]. */
@Serializable
data class TrackerUploadResponse(
    val accepted: List<TrackerKey>,
    val rejected: List<TrackerKey>,
    val inserted: Int,
)

/** `PUT /v1/tracker/transactions/{network}/{code}`: the person's category and comment. Null clears. */
@Serializable
data class TrackerAnnotationDto(
    val category: String? = null,
    val comment: String? = null,
) {
    override fun toString(): String = "TrackerAnnotationDto(category=$category)"
}

/** `PUT /v1/tracker/unread`: per network and day, how many messages the phone could not read. */
@Serializable
data class TrackerUnreadRequest(val days: List<TrackerUnreadDayDto> = emptyList())

@Serializable
data class TrackerUnreadDayDto(
    val network: String,
    /** Nairobi time, "2026-11-12". */
    val day: String,
    val unread: Int,
    @SerialName("parser_version") val parserVersion: Int,
)

/** `DELETE /v1/tracker/records`: how many rows went, which is also what the server keeps of them. */
@Serializable
data class TrackerDeletionResponse(
    @SerialName("transactions_deleted") val transactionsDeleted: Int,
    @SerialName("unread_days_deleted") val unreadDaysDeleted: Int,
    /** True when the deletion also withdrew the agreement to send, which was standing. */
    @SerialName("sending_withdrawn") val sendingWithdrawn: Boolean,
)

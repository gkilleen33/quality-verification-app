package com.qualityverifier.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /v1/fundi/mpesa/transactions`: a batch of parsed M-Pesa transactions. */
@Serializable
data class MpesaUploadRequest(val transactions: List<MpesaTransactionDto> = emptyList()) {
    override fun toString(): String = "MpesaUploadRequest(${transactions.size} transactions)"
}

/**
 * One transaction as the phone parsed it. Times in epoch millis, amounts in shillings.
 *
 * The counterparty arrives as a number or a name and leaves this object only as a code
 * (CounterpartyCoder). [toString] leaves both out, so a log line or an exception message
 * that prints this object cannot carry them.
 */
@Serializable
data class MpesaTransactionDto(
    val code: String,
    val direction: String,
    val type: String,
    @SerialName("amount_kes") val amountKes: Double,
    @SerialName("fee_kes") val feeKes: Double? = null,
    @SerialName("balance_after_kes") val balanceAfterKes: Double? = null,
    @SerialName("occurred_at") val occurredAt: Long,
    /** Sent instead of [counterpartyName] whenever the message prints a number. */
    @SerialName("counterparty_phone") val counterpartyPhone: String? = null,
    @SerialName("counterparty_name") val counterpartyName: String? = null,
    @SerialName("parser_version") val parserVersion: Int,
) {
    override fun toString(): String = "MpesaTransactionDto(code=$code, direction=$direction, type=$type)"
}

/**
 * Which codes the server now holds and which it refused. A code already held from an
 * earlier upload is accepted, so the phone can mark everything it sent in [accepted] as
 * done; a refused row would be refused again, so it is not worth retrying either.
 */
@Serializable
data class MpesaUploadResponse(
    val accepted: List<String>,
    val rejected: List<String>,
    /** How many of [accepted] were new to the server. */
    val inserted: Int,
)

/** `PUT /v1/fundi/mpesa/transactions/{code}`: the fundi's category, piece and comment. Null clears. */
@Serializable
data class MpesaAnnotationDto(
    val category: String? = null,
    @SerialName("piece_id") val pieceId: String? = null,
    val comment: String? = null,
) {
    override fun toString(): String = "MpesaAnnotationDto(category=$category, piece_id=$pieceId)"
}

/** `PUT /v1/fundi/mpesa/unread`: per day, how many M-Pesa messages the phone could not read. */
@Serializable
data class MpesaUnreadRequest(val days: List<MpesaUnreadDayDto> = emptyList())

@Serializable
data class MpesaUnreadDayDto(
    /** The day the messages arrived, Nairobi time, "2026-11-12". */
    val day: String,
    val unread: Int,
    @SerialName("parser_version") val parserVersion: Int,
)

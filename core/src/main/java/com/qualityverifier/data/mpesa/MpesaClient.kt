package com.qualityverifier.data.mpesa

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.db.MpesaTransactionEntity
import com.qualityverifier.data.db.MpesaUnreadDayEntity
import com.qualityverifier.text.MpesaConsentText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** What an upload of transactions came to. */
sealed interface UploadOutcome {
    /** The server holds [accepted]; it refused [rejected] and would refuse them again. */
    data class Done(val accepted: List<String>, val rejected: List<String>) : UploadOutcome

    /** The server has no agreement to sending on record (`403 mpesa_send_required`). */
    data object SendingRefused : UploadOutcome

    /** Offline, a 5xx, a 426, or the routes not mounted yet: everything stays for next time. */
    data object Later : UploadOutcome
}

/** What sending one note came to. */
enum class NoteOutcome {
    SAVED,

    /** Refused for good (a 400): sending it again would be refused again. */
    DROPPED,

    /** Offline, the transaction or piece not on the server yet, a 5xx: kept for next time. */
    LATER,
    SENDING_REFUSED,
}

sealed interface ConsentOutcome {
    data object Agreed : ConsentOutcome
    data object Offline : ConsentOutcome
    data object Failed : ConsentOutcome
}

/**
 * The M-Pesa routes (V29) and the two M-Pesa consents. An interface so [MpesaRepository]
 * can be tested with a fake, as TermsApi is.
 */
interface MpesaApi {
    suspend fun upload(rows: List<MpesaTransactionEntity>): UploadOutcome
    suspend fun annotate(row: MpesaTransactionEntity): NoteOutcome

    /** True when the server took the counts; null when it refused sending; false to retry. */
    suspend fun unread(days: List<MpesaUnreadDayEntity>, parserVersion: Int): Boolean?

    /** Agrees to question [kind] (`mpesa_read` or `mpesa_send`), sending the hash of its text. */
    suspend fun agree(kind: String): ConsentOutcome

    /** True once the server has recorded the withdrawal of [kind]. */
    suspend fun withdraw(kind: String): Boolean
}

/**
 * Through the shared client and the single-flight token provider, as every other call is
 * (see [AuthenticatedHttp]). The counterparty's number or name travels in an upload over
 * HTTPS and nowhere else; the server keeps a code. Nothing here logs a body.
 */
class MpesaClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MpesaApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun upload(rows: List<MpesaTransactionEntity>): UploadOutcome = withContext(io) {
        val body = json.encodeToString(UploadBody(rows.map { it.toBody() })).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/mpesa/transactions")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> runCatching {
                    val answer = json.decodeFromString<UploadAnswer>(response.body?.string().orEmpty())
                    UploadOutcome.Done(answer.accepted, answer.rejected)
                }.getOrElse { UploadOutcome.Later }
                response.refusesSending() -> UploadOutcome.SendingRefused
                else -> {
                    Log.i(TAG, "Upload not taken: HTTP ${response.code}")
                    UploadOutcome.Later
                }
            }
        } ?: UploadOutcome.Later
    }

    override suspend fun annotate(row: MpesaTransactionEntity): NoteOutcome = withContext(io) {
        val body = json.encodeToString(NoteBody(row.category, row.pieceId, row.comment)).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/mpesa/transactions/" + row.code)
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> NoteOutcome.SAVED
                response.refusesSending() -> NoteOutcome.SENDING_REFUSED
                response.code == 400 -> {
                    Log.w(TAG, "Server refused a note (400); dropping it")
                    NoteOutcome.DROPPED
                }
                else -> NoteOutcome.LATER
            }
        } ?: NoteOutcome.LATER
    }

    override suspend fun unread(days: List<MpesaUnreadDayEntity>, parserVersion: Int): Boolean? = withContext(io) {
        val body = json.encodeToString(UnreadBody(days.map { UnreadDayBody(it.day, it.unread, parserVersion) }))
            .toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/mpesa/unread")
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> true
                response.refusesSending() -> null
                else -> false
            }
        } ?: false
    }

    override suspend fun agree(kind: String): ConsentOutcome = withContext(io) {
        val text = MpesaConsentText.textFor(kind) ?: return@withContext ConsentOutcome.Failed
        val body = json.encodeToString(
            ConsentBody(
                version = MpesaConsentText.VERSION,
                language = MpesaConsentText.LANGUAGE,
                textSha256 = MpesaConsentText.sha256Of(text),
                kinds = listOf(kind),
            ),
        ).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/me/consents")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        }?.use { response ->
            if (response.isSuccessful) {
                ConsentOutcome.Agreed
            } else {
                Log.w(TAG, "Agreement to $kind refused: HTTP ${response.code}")
                ConsentOutcome.Failed
            }
        } ?: ConsentOutcome.Offline
    }

    override suspend fun withdraw(kind: String): Boolean = withContext(io) {
        val body = json.encodeToString(WithdrawalBody(kind)).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/me/consents/withdrawal")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        }?.use { it.isSuccessful } ?: false
    }

    /** `403 mpesa_send_required`, or `not_a_fundi`, which no retry will change either. */
    private fun Response.refusesSending(): Boolean = code == 403 && peekBody(1024).string().let {
        it.contains("mpesa_send_required") || it.contains("not_a_fundi")
    }

    private companion object {
        const val TAG = "MpesaClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * The number when the message printed one, otherwise the name, never both: the server
 * codes whichever it gets, and the second would only be one more thing to carry.
 */
private fun MpesaTransactionEntity.toBody() = TransactionBody(
    code = code,
    direction = direction,
    type = type,
    amountKes = amountKes,
    feeKes = feeKes,
    balanceAfterKes = balanceAfterKes,
    occurredAt = occurredAt,
    counterpartyPhone = counterpartyPhone,
    counterpartyName = counterpartyName.takeIf { counterpartyPhone == null },
    parserVersion = parserVersion,
)

// The wire shapes, mirroring the server's MpesaDto.kt. The server reads them strictly, so
// nothing here may carry a field it does not know.

@Serializable
private data class UploadBody(val transactions: List<TransactionBody>)

@Serializable
private data class TransactionBody(
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
)

@Serializable
private data class UploadAnswer(
    val accepted: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
)

@Serializable
private data class NoteBody(
    val category: String? = null,
    @SerialName("piece_id") val pieceId: String? = null,
    val comment: String? = null,
)

@Serializable
private data class UnreadBody(val days: List<UnreadDayBody>)

@Serializable
private data class UnreadDayBody(
    val day: String,
    val unread: Int,
    @SerialName("parser_version") val parserVersion: Int,
)

@Serializable
private data class ConsentBody(
    val version: String,
    val language: String,
    @SerialName("text_sha256") val textSha256: String,
    val kinds: List<String>,
)

@Serializable
private data class WithdrawalBody(val kind: String)

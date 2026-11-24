package com.qualityverifier.tracker.data

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.terms.AgreeOutcome
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.terms.TermsInfo
import com.qualityverifier.data.terms.TermsLanguageInfo
import com.qualityverifier.data.terms.WithdrawOutcome
import com.qualityverifier.text.TrackerConsentText
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
import java.io.IOException

/** A transaction's identity, as the server names it back. */
data class TransactionKey(val network: String, val code: String)

sealed interface UploadOutcome {
    /** The server holds [accepted]; it refused [rejected] and would refuse them again. */
    data class Done(val accepted: List<TransactionKey>, val rejected: List<TransactionKey>) : UploadOutcome

    /** The server holds no agreement to send, or to PesaLog's terms, for this account. */
    data object SendingRefused : UploadOutcome

    /** Offline, a 5xx, a 426, or the routes not mounted yet: everything stays for next time. */
    data object Later : UploadOutcome
}

enum class NoteOutcome { SAVED, DROPPED, LATER, SENDING_REFUSED }

sealed interface ConsentOutcome {
    data object Agreed : ConsentOutcome
    data object Offline : ConsentOutcome
    data object Failed : ConsentOutcome
}

/** Where the account stands with PesaLog's two questions, from `/v1/tracker/status`. */
data class QuestionAnswers(val readAgreed: Boolean, val sendAgreed: Boolean)

sealed interface DeleteOutcome {
    data class Deleted(val transactions: Int, val unreadDays: Int, val sendingWithdrawn: Boolean) : DeleteOutcome
    data object Offline : DeleteOutcome
    data object Failed : DeleteOutcome
}

/** Whether the signed-in account can use PesaLog at all. */
enum class AccountCheck {
    /** A Fundi Bora account: PesaLog's routes answer it. */
    USABLE,

    /** A Kagua account, which every PesaLog route refuses (`403 not_a_fundi`). */
    NOT_A_FUNDI,

    /** The server could not be asked. Taken as usable; the next request says otherwise if not. */
    UNKNOWN,
}

/**
 * The `/v1/tracker/` routes (V33) and PesaLog's two questions. An interface so the repository
 * can be tested with a fake.
 */
interface TrackerApi {
    suspend fun upload(rows: List<TransactionEntity>): UploadOutcome
    suspend fun annotate(row: TransactionEntity): NoteOutcome

    /** True when the server took the counts; null when it refused sending; false to retry. */
    suspend fun unread(days: List<UnreadDayEntity>, parserVersion: Int): Boolean?

    /** Agrees to question [kind] (`tracker_read` or `tracker_send`), sending the hash of its text. */
    suspend fun agree(kind: String): ConsentOutcome

    /** True once the server has recorded the withdrawal of [kind]. */
    suspend fun withdraw(kind: String): Boolean

    /** The account's answers on the server, or null when it could not be reached. */
    suspend fun answers(): QuestionAnswers?

    suspend fun deleteRecords(): DeleteOutcome

    suspend fun accountCheck(): AccountCheck
}

/**
 * Through :core's AuthenticatedHttp and the one token provider, as every Fundi Bora call is.
 * The counterparty's number or name travels in an upload over HTTPS and nowhere else; the
 * server keeps a code. Nothing here logs a body.
 */
class TrackerClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackerApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun upload(rows: List<TransactionEntity>): UploadOutcome = withContext(io) {
        val body = json.encodeToString(UploadBody(rows.map { it.toBody() })).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/transactions")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> runCatching {
                    val answer = json.decodeFromString<UploadAnswer>(response.body?.string().orEmpty())
                    UploadOutcome.Done(answer.accepted.map { it.key() }, answer.rejected.map { it.key() })
                }.getOrElse { UploadOutcome.Later }
                response.refusesSending() -> UploadOutcome.SendingRefused
                else -> {
                    Log.i(TAG, "Upload not taken: HTTP ${response.code}")
                    UploadOutcome.Later
                }
            }
        } ?: UploadOutcome.Later
    }

    override suspend fun annotate(row: TransactionEntity): NoteOutcome = withContext(io) {
        val body = json.encodeToString(NoteBody(row.category, row.comment)).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/transactions/${row.network}/${row.code}")
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> NoteOutcome.SAVED
                response.refusesSending() -> NoteOutcome.SENDING_REFUSED
                response.code == 400 -> NoteOutcome.DROPPED
                // no_such_transaction for a row this phone uploaded: the server refused the row.
                response.code == 404 && response.peekBody(1024).string().contains("no_such_transaction") ->
                    NoteOutcome.DROPPED
                else -> NoteOutcome.LATER
            }
        } ?: NoteOutcome.LATER
    }

    override suspend fun unread(days: List<UnreadDayEntity>, parserVersion: Int): Boolean? = withContext(io) {
        val body = json.encodeToString(
            UnreadBody(days.map { UnreadDayBody(it.network, it.day, it.unread, parserVersion) }),
        ).toRequestBody(JSON)
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/unread")
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> true
                response.refusesSending() -> null
                // Refused as invalid: sending the same rows again would be refused again.
                response.code == 400 -> {
                    Log.w(TAG, "Server refused unread counts (400); dropping them")
                    true
                }
                else -> false
            }
        } ?: false
    }

    override suspend fun agree(kind: String): ConsentOutcome = withContext(io) {
        val text = TrackerConsentText.textFor(kind) ?: return@withContext ConsentOutcome.Failed
        val body = json.encodeToString(
            ConsentBody(
                version = TrackerConsentText.VERSION,
                language = TrackerConsentText.LANGUAGE,
                textSha256 = TrackerConsentText.sha256Of(text),
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

    override suspend fun answers(): QuestionAnswers? = status(http, baseUrl, json)?.let {
        QuestionAnswers(readAgreed = it.readAgreed, sendAgreed = it.sendAgreed)
    }

    override suspend fun deleteRecords(): DeleteOutcome = withContext(io) {
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/records")
                .addHeader("Authorization", "Bearer $token").delete().build()
        }?.use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Deletion refused: HTTP ${response.code}")
                return@use DeleteOutcome.Failed
            }
            runCatching {
                val answer = json.decodeFromString<DeletionAnswer>(response.body?.string().orEmpty())
                DeleteOutcome.Deleted(answer.transactionsDeleted, answer.unreadDaysDeleted, answer.sendingWithdrawn)
            }.getOrElse { DeleteOutcome.Failed }
        } ?: DeleteOutcome.Offline
    }

    override suspend fun accountCheck(): AccountCheck = withContext(io) {
        http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/status")
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            when {
                response.isSuccessful -> AccountCheck.USABLE
                response.code == 403 && response.peekBody(1024).string().contains("not_a_fundi") -> AccountCheck.NOT_A_FUNDI
                else -> AccountCheck.UNKNOWN
            }
        } ?: AccountCheck.UNKNOWN
    }

    /**
     * No agreement to send, or a Kagua account: no retry changes these. `tracker_terms_required`
     * is not one of them: the agreement to send still stands, and uploads resume once the
     * person agrees to new terms, so that refusal is a "later".
     */
    private fun Response.refusesSending(): Boolean = code == 403 && peekBody(1024).string().let {
        it.contains("tracker_send_required") || it.contains("not_a_fundi")
    }

    private companion object {
        const val TAG = "TrackerClient"
    }
}

/**
 * PesaLog's terms screen's routes: `/v1/tracker/status` for where the account stands with
 * PesaLog's terms, and `/v1/me/consents` with PesaLog's two boxes, `tracker_terms` and
 * `tracker_transfer`. The same interface as Fundi Bora's TermsClient, so the terms screen
 * ported from Fundi Bora calls it unchanged.
 */
class TrackerTermsClient(
    private val client: OkHttpClient,
    tokens: TokenProvider,
    /**
     * Where PesaLog keeps its last answer about its own terms (TrackerPreference). Not the
     * token store's terms flag: AuthClient writes Fundi Bora's answer there at every sign-in.
     */
    private val remember: (termsRequired: Boolean) -> Unit,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TermsApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun status(): TermsInfo? = status(http, baseUrl, json)?.let { body ->
        TermsInfo(
            required = body.termsRequired,
            version = body.terms?.version?.takeIf { it.isNotBlank() },
            languages = body.terms?.languages.orEmpty().map { TermsLanguageInfo(it.language, it.sha256, it.path) },
            transferAgreed = body.transferAgreed,
        )
    }?.also { remember(it.required) }

    // Unauthenticated: the text of the terms is not personal.
    override suspend fun text(path: String): ByteArray? = withContext(io) {
        try {
            val request = Request.Builder().url(baseUrl + path.trimStart('/')).get().build()
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun agree(version: String, language: String, sha256: String): AgreeOutcome = withContext(io) {
        val body = json.encodeToString(
            ConsentBody(version, language, sha256, kinds = listOf(TrackerConsentText.TERMS_KIND, TrackerConsentText.TRANSFER_KIND)),
        ).toRequestBody(JSON)
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/me/consents")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        } ?: return@withContext AgreeOutcome.Offline
        response.use {
            when {
                it.isSuccessful -> {
                    remember(false)
                    AgreeOutcome.Agreed
                }
                it.code == 409 -> AgreeOutcome.TextChanged
                else -> {
                    Log.w(TAG, "Agreement refused: HTTP ${it.code}")
                    AgreeOutcome.Failed
                }
            }
        }
    }

    /** PesaLog offers no separate withdrawal of its transfer box: turning off sending is that (terms section 10). */
    override suspend fun withdraw(): WithdrawOutcome = WithdrawOutcome.Failed

    private companion object {
        const val TAG = "TrackerTermsClient"
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

private suspend fun status(http: AuthenticatedHttp, baseUrl: String, json: Json): StatusBody? =
    withContext(Dispatchers.IO) {
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/tracker/status")
                .addHeader("Authorization", "Bearer $token").get().build()
        } ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@use null
            runCatching { json.decodeFromString<StatusBody>(it.body!!.string()) }.getOrNull()
        }
    }

/**
 * The name, the number and the masked number, each when the message printed it (0.2.0, row 32).
 * 0.1.0 sent the number or else the name, never both, so the server could make only one code;
 * from V42 it makes a name code and a masked-number code too, and a send carries both the name
 * and the full number while a receipt carries the name and the masked one. The server's
 * existing code is still made from the number when there is one, so it does not change.
 * A field that is null is left out (explicitNulls = false), so a row with nothing masked is
 * the same body 0.1.0 sent, plus the name.
 */
private fun TransactionEntity.toBody() = TransactionBody(
    network = network,
    code = code,
    direction = direction,
    type = type,
    amountKes = amountKes,
    feeKes = feeKes,
    balanceAfterKes = balanceAfterKes,
    occurredAt = occurredAt,
    counterpartyPhone = counterpartyPhone,
    counterpartyName = counterpartyName,
    counterpartyMasked = counterpartyMasked,
    parserVersion = parserVersion,
)

// The wire shapes, mirroring the server's TrackerDto.kt. The server reads them strictly, so
// nothing here may carry a field it does not know.

@Serializable
private data class UploadBody(val transactions: List<TransactionBody>)

@Serializable
private data class TransactionBody(
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
    /**
     * New in 0.2.0 (V42). A server from before V42 refuses a body that holds it: its reader
     * refuses unknown keys. Such a batch is answered 400 and kept for the next push (Later).
     */
    @SerialName("counterparty_masked") val counterpartyMasked: String? = null,
    @SerialName("parser_version") val parserVersion: Int,
)

@Serializable
private data class KeyBody(val network: String = "", val code: String = "") {
    fun key() = TransactionKey(network, code)
}

@Serializable
private data class UploadAnswer(
    val accepted: List<KeyBody> = emptyList(),
    val rejected: List<KeyBody> = emptyList(),
)

@Serializable
private data class NoteBody(val category: String? = null, val comment: String? = null)

@Serializable
private data class UnreadBody(val days: List<UnreadDayBody>)

@Serializable
private data class UnreadDayBody(
    val network: String,
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

@Serializable
private data class StatusBody(
    @SerialName("terms_required") val termsRequired: Boolean = true,
    val terms: StatusTermsBody? = null,
    @SerialName("transfer_agreed") val transferAgreed: Boolean = false,
    @SerialName("read_agreed") val readAgreed: Boolean = false,
    @SerialName("send_agreed") val sendAgreed: Boolean = false,
)

@Serializable
private data class StatusTermsBody(
    val version: String = "",
    val languages: List<StatusLanguageBody> = emptyList(),
)

@Serializable
private data class StatusLanguageBody(
    val language: String = "",
    val sha256: String = "",
    val path: String = "",
)

@Serializable
private data class DeletionAnswer(
    @SerialName("transactions_deleted") val transactionsDeleted: Int = 0,
    @SerialName("unread_days_deleted") val unreadDaysDeleted: Int = 0,
    @SerialName("sending_withdrawn") val sendingWithdrawn: Boolean = false,
)

package com.qualityverifier.data.terms

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
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
import java.io.IOException

/** One language of the current terms: the hash an agreement sends back, and where to read it. */
data class TermsLanguageInfo(val language: String, val sha256: String, val path: String)

/**
 * What `/v1/me` says about the account's terms.
 *
 * @property required true while either agreement to the current terms is missing.
 * @property version null for an app with no terms.
 * @property transferAgreed whether Settings should offer to withdraw the transfer agreement.
 */
data class TermsInfo(
    val required: Boolean,
    val version: String?,
    val languages: List<TermsLanguageInfo>,
    val transferAgreed: Boolean,
)

sealed interface AgreeOutcome {
    data object Agreed : AgreeOutcome

    /** The version or the text changed while the screen was open. Reload and ask again. */
    data object TextChanged : AgreeOutcome

    data object Offline : AgreeOutcome

    data object Failed : AgreeOutcome
}

sealed interface WithdrawOutcome {
    data object Withdrawn : WithdrawOutcome
    data object Offline : WithdrawOutcome
    data object Failed : WithdrawOutcome
}

/**
 * The terms routes, as Fundi Bora's terms screen and Settings call them.
 *
 * An interface so the view models can be tested with a fake, as [com.qualityverifier.data.fundi.FundiProfiles] is.
 */
interface TermsApi {
    /** The account's current terms, or null when the server could not be reached. */
    suspend fun status(): TermsInfo?

    /** A published text's exact bytes, or null when it could not be fetched. */
    suspend fun text(path: String): ByteArray?

    /** Both agreements, for the text whose [sha256] the phone computed from the bytes shown. */
    suspend fun agree(version: String, language: String, sha256: String): AgreeOutcome

    /** Withdraws the transfer agreement. */
    suspend fun withdraw(): WithdrawOutcome
}

/**
 * In `:core` for the reason [com.qualityverifier.data.fundi.FundiProfileClient] is: every
 * authenticated call goes through the one single-flight token provider. Kagua links it and
 * never calls it.
 *
 * Every answer about the terms is also written to the token store, so Fundi Bora's first
 * screen after a restart follows the latest one.
 */
class TermsClient(
    private val client: OkHttpClient,
    tokens: TokenProvider,
    private val store: TokenStore,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TermsApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun status(): TermsInfo? = withContext(io) {
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/me")
                .addHeader("Authorization", "Bearer $token").get().build()
        } ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@use null
            runCatching { json.decodeFromString<MeBody>(it.body!!.string()).toInfo() }
                .onFailure { error -> Log.w(TAG, "Could not read the terms from /v1/me", error) }
                .getOrNull()
        }?.also { store.setTermsRequired(it.required) }
    }

    // Unauthenticated: the text of the terms is not personal.
    override suspend fun text(path: String): ByteArray? = withContext(io) {
        try {
            val request = Request.Builder().url(baseUrl + path.trimStart('/')).get().build()
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun agree(version: String, language: String, sha256: String): AgreeOutcome =
        withContext(io) {
            val body = json.encodeToString(
                ConsentBody(version, language, sha256, kinds = listOf(KIND_TERMS, KIND_TRANSFER)),
            ).toRequestBody(JSON)
            val response = http.send { token ->
                Request.Builder().url(baseUrl + "v1/me/consents")
                    .addHeader("Authorization", "Bearer $token").post(body).build()
            } ?: return@withContext AgreeOutcome.Offline
            response.use {
                when {
                    it.isSuccessful -> {
                        store.setTermsRequired(false)
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

    override suspend fun withdraw(): WithdrawOutcome = withContext(io) {
        val body = json.encodeToString(WithdrawalBody(KIND_TRANSFER)).toRequestBody(JSON)
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/me/consents/withdrawal")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        } ?: return@withContext WithdrawOutcome.Offline
        response.use {
            if (it.isSuccessful) {
                store.setTermsRequired(true)
                WithdrawOutcome.Withdrawn
            } else {
                Log.w(TAG, "Withdrawal refused: HTTP ${it.code}")
                WithdrawOutcome.Failed
            }
        }
    }

    private companion object {
        const val TAG = "TermsClient"
        const val KIND_TERMS = "terms"
        const val KIND_TRANSFER = "transfer"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

// The wire shapes, mirroring the server's MeResponse, ConsentRequest and WithdrawalRequest.

@Serializable
private data class MeBody(
    @SerialName("terms_required") val termsRequired: Boolean = false,
    val terms: MeTermsBody? = null,
    @SerialName("transfer_agreed") val transferAgreed: Boolean = false,
)

@Serializable
private data class MeTermsBody(
    val version: String = "",
    val languages: List<MeTermsLanguageBody> = emptyList(),
)

@Serializable
private data class MeTermsLanguageBody(
    val language: String = "",
    val sha256: String = "",
    val path: String = "",
)

private fun MeBody.toInfo() = TermsInfo(
    required = termsRequired,
    version = terms?.version?.takeIf { it.isNotBlank() },
    languages = terms?.languages.orEmpty().map { TermsLanguageInfo(it.language, it.sha256, it.path) },
    transferAgreed = transferAgreed,
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

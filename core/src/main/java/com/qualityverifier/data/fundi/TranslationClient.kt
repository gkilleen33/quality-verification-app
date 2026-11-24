package com.qualityverifier.data.fundi

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.domain.AssessmentLanguage
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

/** What asking for an assessment's replies in the other language came back with. */
sealed interface TranslationOutcome {
    /**
     * Every translated reply, by the SHA-256 of the original reply's text
     * (`ReplyTranslation.keyOf`), and how many replies the server could not translate.
     */
    data class Translated(val byKey: Map<String, String>, val failed: Int) : TranslationOutcome

    /** Offline, the server or Claude is unwell, or the assessment is not on the server yet. */
    data object Unavailable : TranslationOutcome
}

/** The English and Kiswahili switch (v0.7.0, release scope row 29). An interface so the screen can be tested. */
fun interface TranslationApi {
    /** The replies of assessment [sessionId] in [language], translated by the server on first asking. */
    suspend fun translate(sessionId: String, language: AssessmentLanguage): TranslationOutcome
}

@Serializable
private data class TranslationBody(val language: String)

@Serializable
private data class TranslationItemBody(
    @SerialName("source_sha256") val sourceSha256: String = "",
    val text: String = "",
)

@Serializable
private data class TranslationReply(
    val translations: List<TranslationItemBody> = emptyList(),
    val failed: List<String> = emptyList(),
)

/**
 * `POST /v1/fundi/sessions/{id}/translations`. The first time anybody asks, the server
 * translates each reply with a Claude call, which can take a minute for a long assessment; it
 * answers within 150 seconds, inside the shared client's 200-second read timeout. Every later
 * ask is answered from what the server stored.
 */
class TranslationClient(
    client: OkHttpClient,
    tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TranslationApi {
    private val http = AuthenticatedHttp(client, tokens, TAG)

    override suspend fun translate(sessionId: String, language: AssessmentLanguage): TranslationOutcome = withContext(io) {
        val body = json.encodeToString(TranslationBody.serializer(), TranslationBody(language.code)).toRequestBody(JSON)
        val response = http.send { token ->
            Request.Builder().url(baseUrl + "v1/fundi/sessions/" + sessionId + "/translations")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
        } ?: return@withContext TranslationOutcome.Unavailable
        response.use {
            if (!it.isSuccessful) return@withContext TranslationOutcome.Unavailable
            runCatching {
                val reply = json.decodeFromString(TranslationReply.serializer(), it.body!!.string())
                TranslationOutcome.Translated(reply.translations.associate { item -> item.sourceSha256 to item.text }, reply.failed.size)
            }.getOrElse { error ->
                Log.w(TAG, "Could not read the translations", error)
                TranslationOutcome.Unavailable
            }
        }
    }

    private companion object {
        const val TAG = "TranslationClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

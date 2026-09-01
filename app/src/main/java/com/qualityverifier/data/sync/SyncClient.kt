package com.qualityverifier.data.sync

import android.util.Log
import com.qualityverifier.data.auth.TokenProvider
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

@Serializable
data class RemoteSession(
    val id: String,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val preview: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("verdict_level_id") val verdictLevelId: String? = null,
    @SerialName("verdict_language") val verdictLanguage: String? = null,
    @SerialName("previous_session_id") val previousSessionId: String? = null,
    @SerialName("intake_answers") val intakeAnswers: String? = null,
)

@Serializable
data class RemoteMessage(
    val id: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    @SerialName("created_at") val createdAt: Long,
    val blobs: List<String> = emptyList(),
)

@Serializable
data class RemoteSessionDetail(val session: RemoteSession, val messages: List<RemoteMessage>)

@Serializable
private data class SessionListBody(val sessions: List<RemoteSession> = emptyList())

@Serializable
private data class PasswordBody(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/**
 * Reading assessments back from the server, and the two account actions.
 *
 * Every call carries a token and retries once after a 401, through the same single-flight
 * provider the chat client uses — so a phone reopened after a while cannot fire several
 * refreshes at once and get itself signed out.
 *
 * The chat client keeps its own copy of that retry rather than sharing this one, because
 * its retry has to re-upload photos as well as re-authenticate.
 */
class SyncClient(
    private val client: OkHttpClient,
    private val tokens: TokenProvider,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Null means the list could not be fetched — offline, or the server is unwell. */
    suspend fun sessions(): List<RemoteSession>? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/sessions")
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            if (!response.isSuccessful) return@use null
            runCatching {
                json.decodeFromString<SessionListBody>(response.body?.string().orEmpty()).sessions
            }.getOrNull()
        }
    }

    suspend fun session(id: String): RemoteSessionDetail? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/sessions/" + id)
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            if (!response.isSuccessful) return@use null
            runCatching {
                json.decodeFromString<RemoteSessionDetail>(response.body?.string().orEmpty())
            }.getOrNull()
        }
    }

    suspend fun blob(sha256: String): ByteArray? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/blobs/" + sha256)
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }

    /**
     * Tells the server the customer deleted this assessment, which starts the retention
     * clock. Returns true when the server has been told — including when it says 404,
     * since that means it has nothing to mark.
     *
     * False means "not yet", and the caller should try again later: the local copy is
     * already gone, so without a retry the server would keep its copy indefinitely and
     * the seven days we promise a customer would be a fiction.
     */
    suspend fun deleteSession(id: String): Boolean = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/sessions/" + id)
                .addHeader("Authorization", "Bearer $token").delete().build()
        }?.use { response ->
            response.isSuccessful || response.code == 404
        } ?: false
    }

    suspend fun changePassword(current: String, new: String): PasswordOutcome = withContext(io) {
        val body = json.encodeToString(PasswordBody(current, new))
        val response = authenticated { token ->
            Request.Builder().url(baseUrl + "v1/auth/password")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON)).build()
        } ?: return@withContext PasswordOutcome.Unavailable

        response.use {
            when {
                it.isSuccessful -> PasswordOutcome.Changed
                it.code == 401 -> PasswordOutcome.WrongPassword
                it.code == 400 -> PasswordOutcome.TooShort
                else -> PasswordOutcome.Unavailable
            }
        }
    }

    suspend fun deleteAccount(): Boolean = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/account")
                .addHeader("Authorization", "Bearer $token").delete().build()
        }?.use { it.isSuccessful } ?: false
    }

    /**
     * Sends a request with a token, refreshing once if the server refuses it.
     *
     * Returns an unclosed response, so every caller uses `use {}`. Null means there was no
     * token, or the request never completed.
     */
    private suspend fun authenticated(build: (String) -> Request): Response? {
        val token = tokens.accessToken() ?: return null
        val first = execute(build(token)) ?: return null
        if (first.code != 401) return first
        first.close()

        // Single-flight: if another call already refreshed, this gets the new token
        // without spending the refresh token again — which the server would read as theft.
        val refreshed = tokens.refreshAfterUnauthorized(token) ?: return null
        return execute(build(refreshed))
    }

    /** Null on a network failure. Anything else is the server's answer, including a 5xx. */
    private fun execute(request: Request): Response? = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        Log.i(TAG, "Sync request failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "SyncClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

sealed interface PasswordOutcome {
    data object Changed : PasswordOutcome
    data object WrongPassword : PasswordOutcome
    data object TooShort : PasswordOutcome
    data object Unavailable : PasswordOutcome
}

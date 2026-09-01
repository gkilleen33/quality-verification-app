package com.qualityverifier.data.auth

import android.util.Log
import com.qualityverifier.data.chat.ChatErrorKind
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

@Serializable
private data class RegisterBody(
    @SerialName("invite_code") val inviteCode: String,
    val phone: String,
    val password: String,
    val name: String,
    @SerialName("account_type") val accountType: String,
    @SerialName("business_name") val businessName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyMetres: Double? = null,
    @SerialName("user_agent") val userAgent: String? = null,
)

@Serializable
private data class SignInBody(
    val phone: String,
    val password: String,
    @SerialName("user_agent") val userAgent: String? = null,
)

@Serializable
private data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class TokenBody(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("user_id") val userId: String = "",
)

@Serializable
private data class ErrorBody(val error: String = "", val detail: String? = null)

/** What the caller needs to show a person, rather than what the server said. */
sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val kind: ChatErrorKind, val message: String) : AuthResult
}

/**
 * Registration, sign-in and refresh against our own server.
 *
 * Deliberately not sharing an interceptor with the chat client: these three calls are the
 * only ones that must *not* attach an access token or trigger a refresh, and building
 * that exception into a shared pipeline is how a refresh ends up recursing into itself.
 */
class AuthClient(
    private val client: OkHttpClient,
    private val store: TokenStore,
    private val baseUrl: String,
    private val json: Json,
    private val deviceName: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun register(
        inviteCode: String,
        phone: String,
        password: String,
        name: String,
        accountType: String,
        businessName: String?,
        latitude: Double?,
        longitude: Double?,
        accuracyMetres: Double?,
    ): AuthResult = post(
        path = "v1/auth/register",
        body = json.encodeToString(
            RegisterBody(
                inviteCode = inviteCode.trim(),
                phone = phone.trim(),
                password = password,
                name = name.trim(),
                accountType = accountType,
                businessName = businessName?.trim()?.takeIf { it.isNotBlank() },
                latitude = latitude,
                longitude = longitude,
                accuracyMetres = accuracyMetres,
                userAgent = deviceName,
            )
        ),
    )

    suspend fun signIn(phone: String, password: String): AuthResult = post(
        path = "v1/auth/sign-in",
        body = json.encodeToString(SignInBody(phone.trim(), password, deviceName)),
    )

    /**
     * Exchanges a refresh token. Returns an outcome rather than a result because the
     * caller — [TokenProvider] — has to tell "refused, sign in again" from "offline, keep
     * what we have", and those lead to opposite actions.
     */
    suspend fun refresh(refreshToken: String): RefreshOutcome = withContext(io) {
        val request = Request.Builder()
            .url(baseUrl + "v1/auth/refresh")
            .post(json.encodeToString(RefreshBody(refreshToken)).toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString<TokenBody>(text)
                        RefreshOutcome.Renewed(
                            accessToken = decoded.accessToken,
                            expiresInSeconds = decoded.expiresIn,
                            refreshToken = decoded.refreshToken,
                            userId = decoded.userId,
                        )
                    }
                    // 401 is the only answer that means the token is genuinely dead. A
                    // 500 or a 503 must not sign somebody out for a server having a bad
                    // afternoon.
                    response.code == 401 -> RefreshOutcome.Rejected
                    else -> {
                        Log.w(TAG, "Refresh got HTTP ${response.code}")
                        RefreshOutcome.Unavailable
                    }
                }
            }
        } catch (e: IOException) {
            RefreshOutcome.Unavailable
        } catch (e: Exception) {
            // A malformed 200 is not a dead token either.
            Log.w(TAG, "Refresh failed unexpectedly", e)
            RefreshOutcome.Unavailable
        }
    }

    private suspend fun post(path: String, body: String): AuthResult = withContext(io) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val decoded = json.decodeFromString<TokenBody>(text)
                    if (decoded.accessToken.isBlank() || decoded.refreshToken.isBlank()) {
                        return@use AuthResult.Failure(
                            ChatErrorKind.UNKNOWN,
                            "Something went wrong. Please try again.",
                        )
                    }
                    store.save(
                        accessToken = decoded.accessToken,
                        expiresInSeconds = decoded.expiresIn,
                        refreshToken = decoded.refreshToken,
                        userId = decoded.userId,
                    )
                    AuthResult.Success
                } else {
                    AuthResult.Failure(kindFor(response.code), messageFor(response.code, text))
                }
            }
        } catch (e: IOException) {
            AuthResult.Failure(
                ChatErrorKind.NETWORK,
                "No internet connection. Please try again when you're back online.",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auth request failed", e)
            AuthResult.Failure(ChatErrorKind.UNKNOWN, "Something went wrong. Please try again.")
        }
    }

    private fun kindFor(status: Int): ChatErrorKind = when (status) {
        400, 409 -> ChatErrorKind.REQUEST
        401, 403 -> ChatErrorKind.AUTH
        429 -> ChatErrorKind.RATE_LIMIT
        in 500..599 -> ChatErrorKind.SERVER
        else -> ChatErrorKind.UNKNOWN
    }

    /**
     * Wording for a person, keyed on the server's error code rather than passed through.
     *
     * The server's own strings are deliberately terse and sometimes deliberately vague —
     * "invite_unusable" covers unknown, revoked and already-redeemed so the endpoint
     * cannot be used to test guessed codes. What a customer needs is a sentence telling
     * them what to do next.
     */
    private fun messageFor(status: Int, body: String): String {
        val code = runCatching { json.decodeFromString<ErrorBody>(body).error }.getOrNull()
        return when (code) {
            "invite_unusable" ->
                "That invite code can't be used. Check it, or ask us for a new one."
            "phone_taken" ->
                "That number already has an account. Sign in instead."
            "invalid_credentials" ->
                "That number and password don't match. Please try again."
            "locked" ->
                "Too many attempts. Please wait a few minutes and try again."
            "invalid_request" ->
                runCatching { json.decodeFromString<ErrorBody>(body).detail }.getOrNull()
                    ?: "Please check what you've entered."
            else -> when {
                status >= 500 -> "Our server is having trouble. Please try again shortly."
                else -> "Something went wrong. Please try again."
            }
        }
    }

    private companion object {
        const val TAG = "AuthClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

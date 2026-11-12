package com.qualityverifier.data.sync

import android.util.Log
import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.WorkerSlot
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
    /** Fundi Bora's piece, so a reinstalled phone regroups its history correctly. */
    @SerialName("piece_id") val pieceId: String? = null,
    /** What the maker calls the piece, so a reinstall shows the name again. */
    @SerialName("piece_label") val pieceLabel: String? = null,
    /** Who worked on it, by roster id and part. Names come from [SyncClient.workers]. */
    val workers: List<RemoteSessionWorker> = emptyList(),
)

@Serializable
data class RemoteSessionWorker(
    @SerialName("worker_id") val workerId: String? = null,
    val part: String? = null,
)

@Serializable
data class RemoteWorker(
    val id: String,
    val name: String? = null,
    @SerialName("archived_at") val archivedAt: Long? = null,
    /** E.164, or null. Absent from a server before V30, which reads the same. */
    val phone: String? = null,
    /** `employee` or `temporary`; absent from a server before V30, which is an employee. */
    val employment: String? = null,
    /** The server's two-sided match (V31); absent from a server before it. */
    val linked: Boolean = false,
)

@Serializable
private data class WorkerListBody(val workers: List<RemoteWorker> = emptyList())

/** One studio this account works at (V31), as the server holds it. */
@Serializable
data class RemoteStudio(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    @SerialName("archived_at") val archivedAt: Long? = null,
    val linked: Boolean = false,
)

@Serializable
private data class StudioListBody(val studios: List<RemoteStudio> = emptyList())

@Serializable
private data class StudioUpsertBody(val name: String, val phone: String, val archived: Boolean)

/**
 * The number goes on every write, blank for none: the server keeps a stored number only
 * when the field is absent, which is how an app from before V30 writes, so a number
 * removed here has to be sent as blank to be removed there.
 */
@Serializable
private data class WorkerUpsertBody(
    val name: String,
    val archived: Boolean,
    val phone: String,
    val employment: String,
)

@Serializable
private data class WorkerSlotBody(
    val id: String? = null,
    val name: String? = null,
    val part: String? = null,
)

@Serializable
private data class SessionWorkersBody(val workers: List<WorkerSlotBody>)

@Serializable
data class RemoteMessage(
    val id: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    @SerialName("created_at") val createdAt: Long,
    val blobs: List<String> = emptyList(),
    val composed: Boolean = false,
)

@Serializable
data class RemoteSessionDetail(val session: RemoteSession, val messages: List<RemoteMessage>)

@Serializable
private data class SessionListBody(val sessions: List<RemoteSession> = emptyList())

@Serializable
private data class PieceLabelBody(val label: String? = null)

@Serializable
private data class PasswordBody(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/** The token pair a password change returns, so the phone that made it stays signed in. */
@Serializable
private data class PasswordChangedBody(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("user_id") val userId: String = "",
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

    /**
     * Sends an evaluator's review.
     *
     * Returns true when the server has it, and **also** when the server refuses it as
     * unusable — a 400 or a 404 means this review will never be accepted, so retrying it on
     * every sync forever would be a queue that never drains. Only a transport failure, a
     * 5xx, or a 426 (this build is below the server's minimum) is worth keeping.
     */
    suspend fun submitTesterFeedback(feedback: LocalTesterFeedback): Boolean = withContext(io) {
        val body = json.encodeToString(
            TesterFeedbackBody(
                sessionId = feedback.sessionId,
                mistakes = feedback.mistakes,
                mistakesDetail = feedback.mistakesDetail,
                adviceStars = feedback.adviceStars,
                itemQuality = feedback.itemQuality,
                extraFeedback = feedback.extraFeedback,
            ),
        ).toRequestBody(JSON)
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/tester-feedback")
                .addHeader("Authorization", "Bearer $token").post(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> true
                // This build is below the server's minimum. Not a verdict on the review:
                // kept, and sent by the updated app.
                response.code == UPGRADE_REQUIRED -> false
                // Permanently unacceptable. Dropped rather than retried for ever.
                response.code in 400..499 -> {
                    Log.w(TAG, "Server refused evaluator feedback (${response.code}); dropping it")
                    true
                }
                else -> false
            }
        } ?: false
    }

    /**
     * Tells the server what the maker calls a piece. Null clears the name.
     *
     * Returns true when the server has it, and **also** when it will never take it: a
     * 404 means the piece is not on the server, which happens when its first turn has
     * not gone yet, and that turn carries the name itself; any other 4xx is a name the
     * server refuses outright. Only a transport failure or a 5xx is worth retrying,
     * the same rule as [submitTesterFeedback].
     */
    suspend fun renamePiece(pieceId: String, label: String?): Boolean = withContext(io) {
        val body = json.encodeToString(PieceLabelBody(label)).toRequestBody(JSON)
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/fundi/pieces/" + pieceId)
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> true
                // Below the minimum: kept for the updated app, as in submitTesterFeedback.
                response.code == UPGRADE_REQUIRED -> false
                response.code in 400..499 -> {
                    if (response.code != 404) {
                        Log.w(TAG, "Server refused a piece name (${response.code}); dropping it")
                    }
                    true
                }
                else -> false
            }
        } ?: false
    }

    /** The roster as the server holds it, archived included. Null when it could not be fetched. */
    suspend fun workers(): List<RemoteWorker>? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/fundi/workers")
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            if (!response.isSuccessful) return@use null
            runCatching {
                json.decodeFromString<WorkerListBody>(response.body?.string().orEmpty()).workers
            }.getOrNull()
        }
    }

    /**
     * Adds, renames or archives a worker on the server, with their number and kind. True
     * when it has it, or will never take it (any 4xx but 426); false for a transport
     * failure, a 5xx, or a 426, which retry.
     */
    suspend fun upsertWorker(
        workerId: String,
        name: String,
        archived: Boolean,
        phone: String? = null,
        employment: Employment = Employment.EMPLOYEE,
    ): Boolean = withContext(io) {
        val body = json.encodeToString(
            WorkerUpsertBody(name, archived, phone = phone.orEmpty(), employment = employment.id),
        ).toRequestBody(JSON)
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/fundi/workers/" + workerId)
                .addHeader("Authorization", "Bearer $token").put(body).build()
        }?.use { response ->
            when {
                response.isSuccessful -> true
                // Below the minimum: kept. Marking it synced would leave the server without
                // this worker, and every later session naming them would be refused.
                response.code == UPGRADE_REQUIRED -> false
                response.code in 400..499 -> {
                    Log.w(TAG, "Server refused a worker (${response.code}); dropping it")
                    true
                }
                else -> false
            }
        } ?: false
    }

    /** The studios this account works at, as the server holds them. Null when it could not be fetched. */
    suspend fun studios(): List<RemoteStudio>? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/fundi/studios")
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            // A server before V31 has no such route: a 404 reads as an empty list that
            // must not be merged, so null, the same as unreachable.
            if (!response.isSuccessful) return@use null
            runCatching {
                json.decodeFromString<StudioListBody>(response.body?.string().orEmpty()).studios
            }.getOrNull()
        }
    }

    /**
     * Adds, edits or archives a studio entry on the server. True when it has it, or will
     * never take it (any 4xx but 404 and 426); false for a transport failure, a 5xx, a
     * 426, or a 404, which means a server before V31 and is worth keeping for the one
     * that follows.
     */
    suspend fun upsertStudio(studioId: String, name: String, phone: String, archived: Boolean): Boolean =
        withContext(io) {
            val body = json.encodeToString(StudioUpsertBody(name, phone, archived)).toRequestBody(JSON)
            authenticated { token ->
                Request.Builder().url(baseUrl + "v1/fundi/studios/" + studioId)
                    .addHeader("Authorization", "Bearer $token").put(body).build()
            }?.use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 404 -> false
                    response.code == UPGRADE_REQUIRED -> false
                    response.code in 400..499 -> {
                        Log.w(TAG, "Server refused a studio entry (${response.code}); dropping it")
                        true
                    }
                    else -> false
                }
            } ?: false
        }

    /**
     * Replaces who worked on a session. The same retry rule as [upsertWorker], except a
     * 404, which here means the session has not reached the server yet (its opening turn
     * is still to go) and is worth retrying.
     */
    suspend fun replaceSessionWorkers(sessionId: String, workers: List<WorkerSlot>): Boolean =
        withContext(io) {
            val body = json.encodeToString(
                SessionWorkersBody(workers.map { WorkerSlotBody(it.workerId, it.name, it.part?.id) }),
            ).toRequestBody(JSON)
            authenticated { token ->
                Request.Builder().url(baseUrl + "v1/sessions/" + sessionId + "/workers")
                    .addHeader("Authorization", "Bearer $token").put(body).build()
            }?.use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 404 -> false
                    // Below the minimum: kept for the updated app.
                    response.code == UPGRADE_REQUIRED -> false
                    response.code in 400..499 -> {
                        Log.w(TAG, "Server refused a session's workers (${response.code}); dropping them")
                        true
                    }
                    else -> false
                }
            } ?: false
        }

    /** Whether this account is one of our evaluators. Null when the server was unreachable. */
    suspend fun isTester(): Boolean? = withContext(io) {
        authenticated { token ->
            Request.Builder().url(baseUrl + "v1/me")
                .addHeader("Authorization", "Bearer $token").get().build()
        }?.use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string() ?: return@use null
            runCatching { json.decodeFromString<MeBody>(text).isTester }.getOrNull()
        }
    }

    suspend fun changePassword(current: String, new: String): PasswordOutcome = withContext(io) {
        val body = json.encodeToString(PasswordBody(current, new))
        // Noted before sending, so an answer that arrives after a sign-out is not saved.
        val since = tokens.generation()
        val response = authenticated { token ->
            Request.Builder().url(baseUrl + "v1/auth/password")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON)).build()
        } ?: return@withContext PasswordOutcome.Unavailable

        response.use {
            when {
                it.isSuccessful -> {
                    adoptReplacementTokens(since, it.body?.string().orEmpty())
                    PasswordOutcome.Changed
                }
                it.code == 401 -> PasswordOutcome.WrongPassword
                it.code == 400 -> PasswordOutcome.TooShort
                else -> PasswordOutcome.Unavailable
            }
        }
    }

    /**
     * Saves the token pair a password change returns.
     *
     * The server revokes every refresh token the account holds, this phone's included, so
     * without the new pair the next refresh is refused and the phone signs out with no
     * explanation. A server from before the pair existed answers 204 with no body; the
     * password has still changed, so that is still [PasswordOutcome.Changed], and the phone
     * signs out at its next refresh as it always did.
     */
    private suspend fun adoptReplacementTokens(since: Long, text: String) {
        if (text.isBlank()) return
        val pair = runCatching { json.decodeFromString<PasswordChangedBody>(text) }
            .onFailure { e -> Log.w(TAG, "Could not read the tokens after a password change", e) }
            .getOrNull() ?: return
        // The user id too, as AuthClient requires at sign-in: a pair saved without one
        // leaves the phone unable to tell whose local data it holds.
        if (pair.accessToken.isBlank() || pair.refreshToken.isBlank() || pair.userId.isBlank()) return
        tokens.adopt(
            since,
            accessToken = pair.accessToken,
            expiresInSeconds = pair.expiresIn,
            refreshToken = pair.refreshToken,
            userId = pair.userId,
        )
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
    // Token, single-flight refresh and the one retry, in AuthenticatedHttp so that the
    // Fundi Bora profile client uses the same one rather than a third hand-rolled copy.
    private val http = AuthenticatedHttp(client, tokens, TAG)

    private suspend fun authenticated(build: (String) -> Request): Response? = http.send(build)

    private companion object {
        const val TAG = "SyncClient"

        /**
         * 426: this build is below the minimum the server serves. Every 4xx branch above
         * checks it first, because the rest of that range means "never", and this one
         * means "not from this build": the queued item has to survive until the update.
         */
        const val UPGRADE_REQUIRED = 426
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

sealed interface PasswordOutcome {
    data object Changed : PasswordOutcome
    data object WrongPassword : PasswordOutcome
    data object TooShort : PasswordOutcome
    data object Unavailable : PasswordOutcome
}

@Serializable
private data class TesterFeedbackBody(
    @SerialName("session_id") val sessionId: String,
    val mistakes: String,
    @SerialName("mistakes_detail") val mistakesDetail: String?,
    @SerialName("advice_stars") val adviceStars: Int,
    @SerialName("item_quality") val itemQuality: Int,
    @SerialName("extra_feedback") val extraFeedback: String?,
)

@Serializable
private data class MeBody(
    @SerialName("is_tester") val isTester: Boolean = false,
)

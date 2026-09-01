package com.qualityverifier.data.chat

import android.util.Log
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
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
import java.security.MessageDigest

@Serializable
private data class ChatBody(
    @SerialName("session_id") val sessionId: String,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
    val blobs: List<String>,
)

@Serializable
private data class ChatReply(
    @SerialName("message_id") val messageId: String = "",
    val text: String = "",
)

@Serializable
private data class MissingBlobs(val missing: List<String> = emptyList())

/**
 * Phase 2's [ChatService]: posts one turn to our server instead of the whole
 * conversation to Anthropic.
 *
 * Two differences from the direct client matter to a customer on a mobile connection.
 * The conversation is not re-sent — the server holds it — and photographs are uploaded
 * once, by content hash, rather than re-encoded into every subsequent turn. A nine-photo
 * assessment went from tens of megabytes to a few.
 *
 * The system prompt does not appear here at all. The server assembles it from the
 * protocols on GitHub, so a client cannot substitute one and spend our budget on it.
 */
class ServerChatService(
    private val client: OkHttpClient,
    private val tokens: TokenProvider,
    private val images: ImageBytesSource,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ChatService {

    override suspend fun send(
        sessionId: String,
        itemType: ItemType,
        history: List<ChatMessage>,
    ): ChatResult = withContext(io) {
        // Only the newest customer turn goes on the wire. Its id is the idempotency key,
        // so a retry after a lost response returns the stored reply rather than paying
        // for a second vision request.
        val turn = history.lastOrNull { it.role == Role.USER }
            ?: return@withContext ChatResult.Failure(
                ChatErrorKind.REQUEST,
                "Nothing to send yet.",
            )

        val hashes = mutableListOf<String>()
        for (attachment in turn.attachments) {
            val bytes = images.bytesForUpload(java.io.File(attachment.path))
            if (bytes == null) {
                // Skipped rather than failing the turn, matching the server's own
                // tolerance. A photo that cannot be read costs one image.
                Log.w(TAG, "Skipping an unreadable attachment")
                continue
            }
            val sha = sha256(bytes)
            hashes += sha
            when (val upload = ensureUploaded(sha, bytes)) {
                null -> Unit
                else -> return@withContext upload
            }
        }

        val body = json.encodeToString(
            ChatBody(sessionId, itemType.id, turn.id, turn.text, hashes)
        )
        // One retry, and only for the two conditions a retry can actually fix: a token
        // that has just been refreshed, or photos the server turned out not to have.
        postTurn(body, turn, hashes, allowRetry = true)
    }

    private suspend fun postTurn(
        body: String,
        turn: ChatMessage,
        hashes: List<String>,
        allowRetry: Boolean,
    ): ChatResult {
        val token = tokens.accessToken()
            ?: return ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")

        val request = Request.Builder()
            .url(baseUrl + "v1/chat")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val decoded = json.decodeFromString<ChatReply>(text)
                        if (decoded.text.isBlank()) {
                            ChatResult.Failure(ChatErrorKind.UNKNOWN, "No answer came back.")
                        } else {
                            ChatResult.Success(decoded.text)
                        }
                    }

                    response.code == 401 && allowRetry -> {
                        // Single-flight: if another request already refreshed, this gets
                        // the new token without spending the refresh token again.
                        val refreshed = tokens.refreshAfterUnauthorized(token)
                        if (refreshed == null) {
                            ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")
                        } else {
                            postTurn(body, turn, hashes, allowRetry = false)
                        }
                    }

                    response.code == 409 && allowRetry -> {
                        // The server does not have a photo we named. It says which, so
                        // upload those and try once more rather than failing a turn the
                        // customer has already spent minutes on.
                        val missing = runCatching {
                            json.decodeFromString<MissingBlobs>(text).missing
                        }.getOrNull().orEmpty()
                        Log.w(TAG, "Server is missing ${missing.size} photo(s); re-uploading")
                        val reuploaded = reupload(missing, turn)
                        if (reuploaded != null) reuploaded
                        else postTurn(body, turn, hashes, allowRetry = false)
                    }

                    else -> failureFor(response.code)
                }
            }
        } catch (e: IOException) {
            ChatResult.Failure(
                ChatErrorKind.NETWORK,
                "No internet connection. Your answers were saved — tap retry when you're back online.",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Chat request failed", e)
            ChatResult.Failure(ChatErrorKind.UNKNOWN, "Something went wrong. Please try again.")
        }
    }

    /** Uploads named hashes again by finding the matching local file. */
    private suspend fun reupload(missing: List<String>, turn: ChatMessage): ChatResult? {
        val wanted = missing.map { it.lowercase() }.toSet()
        for (attachment in turn.attachments) {
            val bytes = images.bytesForUpload(java.io.File(attachment.path)) ?: continue
            val sha = sha256(bytes)
            if (sha in wanted) {
                ensureUploaded(sha, bytes, force = true)?.let { return it }
            }
        }
        return null
    }

    /**
     * Puts a photo on the server if it is not already there.
     *
     * HEAD first so nothing is uploaded twice — that check is the whole reason a
     * conversation stops carrying its photographs. Returns null on success, or the
     * failure to hand back.
     */
    private fun ensureUploaded(sha: String, bytes: ByteArray, force: Boolean = false): ChatResult? {
        if (!force) {
            val head = Request.Builder().url(blobUrl(sha)).head().build()
            val present = runCatching {
                client.newCall(head).execute().use { it.isSuccessful }
            }.getOrElse { return networkFailure() }
            if (present) return null
        }

        val put = Request.Builder()
            .url(blobUrl(sha))
            .put(bytes.toRequestBody(JPEG))
            .build()
        return runCatching {
            client.newCall(put).execute().use { response ->
                if (response.isSuccessful) null else failureFor(response.code)
            }
        }.getOrElse { networkFailure() }
    }

    private fun blobUrl(sha: String) = baseUrl + "v1/blobs/" + sha

    private fun networkFailure() = ChatResult.Failure(
        ChatErrorKind.NETWORK,
        "No internet connection. Your answers were saved — tap retry when you're back online.",
    )

    /**
     * The server's wording never reaches a customer: it is written for an operator and can
     * name a model or a quota. These are written for somebody standing in a shop.
     */
    private fun failureFor(status: Int): ChatResult = when (status) {
        401, 403 -> ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")
        404 -> ChatResult.Failure(
            ChatErrorKind.REQUEST,
            "This assessment could not be found on our server.",
        )
        413 -> ChatResult.Failure(ChatErrorKind.REQUEST, "That photo is too large to send.")
        429 -> ChatResult.Failure(
            ChatErrorKind.RATE_LIMIT,
            "Too many requests just now. Please try again in a moment.",
        )
        502, 503, 504 -> ChatResult.Failure(
            ChatErrorKind.SERVER,
            "The assistant is busy. Please try again in a moment.",
        )
        in 500..599 -> ChatResult.Failure(
            ChatErrorKind.SERVER,
            "Our server had a problem. Please try again.",
        )
        else -> ChatResult.Failure(ChatErrorKind.UNKNOWN, "Something went wrong. Please try again.")
    }

    private companion object {
        const val TAG = "ServerChatService"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

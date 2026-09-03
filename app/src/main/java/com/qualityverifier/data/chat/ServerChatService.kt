package com.qualityverifier.data.chat

import android.util.Log
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.text.encodeIntake
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
import java.security.MessageDigest

@Serializable
private data class ChatBody(
    @SerialName("session_id") val sessionId: String,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
    val blobs: List<String>,
    /**
     * Only used by the server when it first creates the session row, so sending them on
     * every turn is harmless and means no turn is the special one that has to carry them.
     */
    @SerialName("previous_session_id") val previousSessionId: String? = null,
    @SerialName("intake_answers") val intakeAnswers: String? = null,
)

@Serializable
private data class ChatReply(
    @SerialName("message_id") val messageId: String = "",
    val text: String = "",
)

@Serializable
private data class MissingBlobs(val missing: List<String> = emptyList())

/** Just enough of the server's error shape to tell one 404 from another. */
@Serializable
private data class ErrorEnvelope(val error: String = "")

/** One increment of a streamed reply. Mirrors ChatDelta on the server. */
@Serializable
private data class StreamDelta(val text: String = "")

/** The end of a streamed reply, carrying the whole of it. Mirrors ChatDone. */
@Serializable
private data class StreamDone(
    @SerialName("message_id") val messageId: String = "",
    val text: String = "",
)

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
    /**
     * Reads the session's own metadata — which assessment it followed, and the intake
     * answers — so the server's copy is not missing them.
     *
     * A function rather than the whole SessionRepository: this needs two fields of one
     * row, and depending on the entire repository would couple two seams together for no
     * benefit. ChatService.send does not carry them, and changing that signature would
     * push the problem out to every caller instead.
     */
    private val sessionStart: suspend (String) -> SessionStart?,
    private val baseUrl: String,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ChatService {

    override suspend fun send(
        sessionId: String,
        itemType: ItemType,
        history: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
    ): ChatResult = withContext(io) {
        // Only the newest customer turn goes on the wire. Its id is the idempotency key,
        // so a retry after a lost response returns the stored reply rather than paying
        // for a second vision request.
        val turn = history.lastOrNull { it.role == Role.USER }
            ?: return@withContext ChatResult.Failure(
                ChatErrorKind.REQUEST,
                "Nothing to send yet.",
            )

        // Obtained before the uploads, not just before the POST. The blob routes are
        // behind the same authentication as everything else — the first device test of
        // this path failed on exactly that, because HEAD and PUT were built without the
        // header and a 401 on a photo reads to the customer as "please sign in again".
        val token = tokens.accessToken()
            ?: return@withContext ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")

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
            when (val upload = ensureUploaded(sha, bytes, token)) {
                null -> Unit
                else -> return@withContext upload
            }
        }

        // Looked up rather than passed in: by the time a turn is sent the row exists,
        // and the caller would otherwise have to thread this through the seam.
        val start = runCatching { sessionStart(sessionId) }.getOrNull()
        val body = json.encodeToString(
            ChatBody(
                sessionId = sessionId,
                itemTypeId = itemType.id,
                messageId = turn.id,
                text = turn.text,
                blobs = hashes,
                previousSessionId = start?.previousSessionId,
                intakeAnswers = start?.intake?.let(::encodeIntake),
            )
        )
        // One retry, and only for the two conditions a retry can actually fix: a token
        // that has just been refreshed, or photos the server turned out not to have.
        postTurn(body, turn, hashes, allowRetry = true, onDelta = onDelta)
    }

    private suspend fun postTurn(
        body: String,
        turn: ChatMessage,
        hashes: List<String>,
        allowRetry: Boolean,
        onDelta: suspend (String) -> Unit,
        /**
         * Set after a 404 on the streaming route, which means a server that predates it.
         * Only ever tried once, and only for that: falling back on any other failure
         * would turn one paid-for turn into two.
         */
        streaming: Boolean = true,
    ): ChatResult {
        val token = tokens.accessToken()
            ?: return ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")

        val path = if (streaming) "v1/chat/stream" else "v1/chat"
        val request = Request.Builder()
            .url(baseUrl + path)
            .addHeader("Authorization", "Bearer $token")
            .apply { if (streaming) addHeader("Accept", "text/event-stream") }
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // Read lazily for a stream and eagerly otherwise: calling string() on a
                // streamed body would wait for the last byte, which is precisely the wait
                // being removed.
                if (streaming && response.isSuccessful) {
                    return@use readStream(response, onDelta)
                }
                val text = response.body?.string().orEmpty()
                when {
                    // A server without the streaming route. Nothing has been spent and
                    // nothing has been shown, so the old route is a clean second attempt.
                    //
                    // Distinguished from the route's own 404 by the body: an unmatched
                    // route in Ktor answers with nothing at all, while "this session is
                    // not yours" answers with an error code. Retrying that one on
                    // /v1/chat would only earn the same 404 from the other route.
                    streaming && response.code == 404 && !namesAnError(text) -> {
                        Log.i(TAG, "No streaming route on this server; using /v1/chat")
                        postTurn(body, turn, hashes, allowRetry, onDelta, streaming = false)
                    }

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
                            postTurn(body, turn, hashes, false, onDelta, streaming)
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
                        else postTurn(body, turn, hashes, false, onDelta, streaming)
                    }

                    else -> failureFor(response.code, text)
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

    /**
     * Reads a streamed reply, forwarding each increment as it lands.
     *
     * Three events, and only `done` produces a result worth storing. A stream that ends
     * without one is a reply that was cut off: the server discards its half of it too, so
     * a retry re-asks rather than replaying a truncated verdict for ever.
     *
     * The text handed back is the one `done` carried, not the increments added up. They
     * should be identical, and when they are not it is because a delta was lost — in which
     * case the server's copy is the one both sides should agree on.
     */
    private suspend fun readStream(
        response: Response,
        onDelta: suspend (String) -> Unit,
    ): ChatResult {
        val source = response.body?.source()
            ?: return ChatResult.Failure(ChatErrorKind.UNKNOWN, "No answer came back.")

        var event = ""
        var done: StreamDone? = null
        var failed = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    when (event) {
                        EVENT_DELTA -> runCatching {
                            json.decodeFromString<StreamDelta>(payload).text
                        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { onDelta(it) }

                        EVENT_DONE -> done = runCatching {
                            json.decodeFromString<StreamDone>(payload)
                        }.getOrNull()

                        EVENT_ERROR -> failed = true
                    }
                }
                // A blank line ends an event. Clearing the name here means a stray data
                // line outside an event is ignored rather than read as the last one.
                line.isBlank() -> event = ""
            }
            if (done != null || failed) break
        }

        return when {
            done != null && done.text.isNotBlank() -> ChatResult.Success(done.text)
            failed -> ChatResult.Failure(
                ChatErrorKind.SERVER,
                "The assistant is unavailable right now. Please try again.",
            )
            // Ended without either. On a mobile connection this is usually the connection,
            // so it is worded as one and the turn stays retryable.
            else -> ChatResult.Failure(
                ChatErrorKind.NETWORK,
                "The answer stopped part way. Your answers were saved — tap retry.",
            )
        }
    }

    /** Uploads named hashes again by finding the matching local file. */
    private suspend fun reupload(missing: List<String>, turn: ChatMessage): ChatResult? {
        val wanted = missing.map { it.lowercase() }.toSet()
        for (attachment in turn.attachments) {
            val bytes = images.bytesForUpload(java.io.File(attachment.path)) ?: continue
            val sha = sha256(bytes)
            if (sha in wanted) {
                val token = tokens.accessToken()
                    ?: return ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")
                ensureUploaded(sha, bytes, token, force = true)?.let { return it }
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
    private fun ensureUploaded(
        sha: String,
        bytes: ByteArray,
        token: String,
        force: Boolean = false,
    ): ChatResult? {
        if (!force) {
            val head = Request.Builder()
                .url(blobUrl(sha))
                .addHeader("Authorization", "Bearer $token")
                .head()
                .build()
            val present = runCatching {
                client.newCall(head).execute().use { it.isSuccessful }
            }.getOrElse { return networkFailure() }
            if (present) return null
        }

        val put = Request.Builder()
            .url(blobUrl(sha))
            .addHeader("Authorization", "Bearer $token")
            .put(bytes.toRequestBody(JPEG))
            .build()
        return runCatching {
            client.newCall(put).execute().use { response ->
                if (response.isSuccessful) null else failureFor(response.code)
            }
        }.getOrElse { networkFailure() }
    }

    /**
     * Whether a body is one of our own error envelopes rather than an empty response.
     *
     * Only used to tell "no such route" from "no such session", both of which are 404.
     */
    private fun namesAnError(body: String): Boolean = runCatching {
        json.decodeFromString<ErrorEnvelope>(body).error.isNotBlank()
    }.getOrDefault(false)

    private fun blobUrl(sha: String) = baseUrl + "v1/blobs/" + sha

    private fun networkFailure() = ChatResult.Failure(
        ChatErrorKind.NETWORK,
        "No internet connection. Your answers were saved — tap retry when you're back online.",
    )

    /**
     * The server's wording never reaches a customer: it is written for an operator and can
     * name a model or a quota. These are written for somebody standing in a shop.
     */
    private fun failureFor(status: Int, body: String = ""): ChatResult = when (status) {
        401, 403 -> ChatResult.Failure(ChatErrorKind.AUTH, "Please sign in again.")
        404 -> ChatResult.Failure(
            ChatErrorKind.REQUEST,
            "This assessment could not be found on our server.",
        )
        413 -> ChatResult.Failure(ChatErrorKind.REQUEST, "That photo is too large to send.")
        429 -> if (body.contains(DAILY_LIMIT)) {
            // A different thing from a transient 429, and "try again in a moment" would be
            // a false statement: the allowance resets at midnight, not in a minute. The
            // number comes from the server so the two cannot drift apart.
            ChatResult.Failure(
                ChatErrorKind.RATE_LIMIT,
                dailyLimitFrom(body),
            )
        } else {
            ChatResult.Failure(
                ChatErrorKind.RATE_LIMIT,
                "Too many requests just now. Please try again in a moment.",
            )
        }
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

    /**
     * "limit is 20 per day" — the server's operator wording, turned into something a
     * customer standing in a workshop can act on. If the number cannot be found the
     * sentence still works without it, because a message that says nothing is worse than
     * one missing a digit.
     */
    private fun dailyLimitFrom(body: String): String {
        val limit = Regex("limit is (\\d+) per day").find(body)?.groupValues?.get(1)
        return if (limit != null) {
            "You have assessed $limit pieces today, which is the daily limit. " +
                "You can start again tomorrow."
        } else {
            "You have reached the number of assessments allowed today. " +
                "You can start again tomorrow."
        }
    }

    private companion object {
        const val TAG = "ServerChatService"

        // The three event names /v1/chat/stream sends. Kept as constants on both sides
        // rather than typed once here and once there.
        const val EVENT_DELTA = "delta"
        const val EVENT_DONE = "done"
        const val EVENT_ERROR = "error"

        /** The server's error code for the per-account daily allowance. */
        const val DAILY_LIMIT = "daily_limit_reached"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JPEG = "image/jpeg".toMediaType()

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

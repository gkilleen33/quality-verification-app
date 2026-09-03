package com.qualityverifier.server.routes

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.UpstreamError
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Writer
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("com.qualityverifier.server.chat")

fun Route.chatRoutes(
    store: ChatStore,
    blobs: BlobStore,
    claude: ClaudeClient,
    prompts: PromptRepository,
    /** Assessments one account may start per day. Zero or less means no limit. */
    dailyAssessmentLimit: Int,
    /** The higher allowance for one of our own evaluators. */
    testerDailyAssessmentLimit: Int,
) {
    authenticate("jwt") {

        route("/v1/blobs/{sha256}") {

            // Lets the phone skip an upload it does not need. This is what turns
            // re-sending the conversation into re-sending only its text.
            head {
                val sha = call.parameters["sha256"].orEmpty()
                if (!BlobStore.isValidHash(sha)) {
                    call.respond(HttpStatusCode.BadRequest); return@head
                }
                call.respond(if (blobs.exists(sha)) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            put {
                val sha = call.parameters["sha256"].orEmpty()
                if (!BlobStore.isValidHash(sha)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_hash", "The path must be a SHA-256 in hex."),
                    )
                    return@put
                }
                // Read into memory: nginx caps the body at 25MB and a photo is a few
                // hundred kilobytes, so this is bounded well below the heap. Streaming to
                // disk first would mean writing before the hash is verified.
                val bytes = call.receiveStream().readBytes()
                when (val result = blobs.put(sha, bytes)) {
                    BlobStore.PutResult.Stored -> call.respond(HttpStatusCode.Created)
                    BlobStore.PutResult.AlreadyPresent -> call.respond(HttpStatusCode.OK)
                    BlobStore.PutResult.TooLarge -> call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ErrorResponse("too_large", "A photo may not exceed 8MB."),
                    )
                    is BlobStore.PutResult.HashMismatch -> call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("hash_mismatch", "The bytes do not match the hash given."),
                    )
                }
            }
        }

        post("/v1/chat") {
            val turn = prepareTurn(
                call, store, blobs, prompts, dailyAssessmentLimit, testerDailyAssessmentLimit,
            ) ?: return@post

            if (turn is PreparedTurn.Replay) {
                call.respond(ChatResponse(turn.messageId, turn.text))
                return@post
            }
            val ready = turn as PreparedTurn.Ready

            val startedAt = System.currentTimeMillis()
            val result = claude.send(ready.systemPrompt, ready.history) { attachment ->
                blobs.read(java.io.File(attachment.path).nameWithoutExtension)
            }
            val elapsed = System.currentTimeMillis() - startedAt

            when (result) {
                is ClaudeResult.Success -> {
                    val messageId = storeReply(store, ready, result, elapsed)
                    call.respond(ChatResponse(messageId, result.text))
                }

                is ClaudeResult.Failure -> {
                    recordFailure(store, ready, result, elapsed)
                    // Our upstream's message never reaches the phone: it can name a model,
                    // a quota or an account, none of which are the customer's business.
                    call.respond(statusFor(result.error), ErrorResponse("upstream_unavailable"))
                }
            }
        }

        /**
         * The same turn, answered as it is written.
         *
         * A second route rather than a flag on the first. The phone that is installed
         * today posts to `/v1/chat` and reads one JSON body; it has to keep working
         * through a deploy, and a client that asked for a stream and got a JSON object —
         * or the reverse — would fail in the least debuggable way possible, mid-assessment
         * in a workshop. Two routes make the contract per-URL and let the phone fall back
         * on a 404 if it ever meets an older server.
         *
         * Everything before the upstream call is shared with `/v1/chat` — see
         * [prepareTurn]. The daily limit in particular must not be enforceable on one
         * path and not the other.
         */
        post("/v1/chat/stream") {
            val turn = prepareTurn(
                call, store, blobs, prompts, dailyAssessmentLimit, testerDailyAssessmentLimit,
            ) ?: return@post

            // Every refusal above answered with JSON and a real status code, because none
            // of them had started a stream. From here the status is 200 and a failure has
            // to travel as an event.
            //
            // nginx buffers proxied responses by default, which would hold the whole
            // stream until it finished and reproduce today's blank wait with more moving
            // parts. This header switches it off for this response only — chosen over a
            // `location` block in the site config because it ships with the jar, so a
            // deploy cannot half-apply it.
            call.response.header("X-Accel-Buffering", "no")
            call.response.header(HttpHeaders.CacheControl, "no-store")

            call.respondTextWriter(ContentType.Text.EventStream) {
                if (turn is PreparedTurn.Replay) {
                    log.info("Replaying a stored reply for message {}", turn.messageId)
                    sse(EVENT_DONE, Json.encodeToString(ChatDone(turn.messageId, turn.text)))
                    return@respondTextWriter
                }
                val ready = turn as PreparedTurn.Ready

                val startedAt = System.currentTimeMillis()
                val result = claude.stream(
                    systemPrompt = ready.systemPrompt,
                    history = ready.history,
                    imageBytes = { attachment ->
                        blobs.read(java.io.File(attachment.path).nameWithoutExtension)
                    },
                    onDelta = { chunk -> sse(EVENT_DELTA, Json.encodeToString(ChatDelta(chunk))) },
                )
                val elapsed = System.currentTimeMillis() - startedAt

                when (result) {
                    is ClaudeResult.Success -> {
                        val messageId = storeReply(store, ready, result, elapsed)
                        sse(EVENT_DONE, Json.encodeToString(ChatDone(messageId, result.text)))
                    }

                    is ClaudeResult.Failure -> {
                        recordFailure(store, ready, result, elapsed)
                        // Nothing is stored for a stream that failed part way, even though
                        // some of the reply reached the phone. Storing it would make the
                        // truncation permanent: the message id is the idempotency key, so
                        // the retry would replay the half-written verdict for ever instead
                        // of producing a whole one. Discarding matches what /v1/chat has
                        // always done on an upstream failure, and the customer's own turn
                        // stays stored either way, so nobody re-takes photographs.
                        sse(EVENT_ERROR, Json.encodeToString(ErrorResponse("upstream_unavailable")))
                    }
                }
            }
        }
    }
}

/** What a streamed reply's events are called. Mirrored in ServerChatService on the phone. */
private const val EVENT_DELTA = "delta"
private const val EVENT_DONE = "done"
private const val EVENT_ERROR = "error"

/**
 * Writes one SSE event and pushes it out.
 *
 * The flush is the entire point of the feature. Without it the writer buffers and the
 * customer waits exactly as long as before, having paid for the plumbing.
 */
private suspend fun Writer.sse(event: String, data: String) {
    write("event: $event\n")
    write("data: $data\n\n")
    flush()
}

/** A turn that passed every check and is ready to spend money. */
private sealed interface PreparedTurn {
    data class Ready(
        val userId: String,
        val sessionId: String,
        val systemPrompt: String,
        val history: List<ChatMessage>,
    ) : PreparedTurn

    /** A turn we have already answered; the stored reply, not a second upstream call. */
    data class Replay(val messageId: String, val text: String) : PreparedTurn
}

/**
 * Everything both chat routes do before calling Claude: authenticate, validate, check the
 * session belongs to this account, apply the daily limit, and store the customer's turn.
 *
 * Shared rather than copied. Two routes with their own copies of a quota check is how an
 * account ends up with an unmetered path to our budget.
 *
 * @return null when it has already answered the call, in which case the caller must stop.
 *   Every such answer is ordinary JSON with a real status code — deliberately, since none
 *   of these refusals has begun a stream yet.
 */
private suspend fun prepareTurn(
    call: ApplicationCall,
    store: ChatStore,
    blobs: BlobStore,
    prompts: PromptRepository,
    dailyAssessmentLimit: Int,
    testerDailyAssessmentLimit: Int,
): PreparedTurn? {
    val userId = call.principal<JWTPrincipal>()?.subject
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
        return null
    }

    val request = call.receive<ChatRequest>()
    val itemType = ItemType.fromId(request.itemTypeId)
    if (request.sessionId.isBlank() || request.messageId.isBlank() || itemType == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("invalid_request", "session_id, message_id and a known item_type_id are required"),
        )
        return null
    }
    if (request.text.isBlank() && request.blobs.isEmpty()) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("invalid_request", "a turn needs text or a photo"),
        )
        return null
    }
    if (request.blobs.any { !BlobStore.isValidHash(it) }) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_hash"))
        return null
    }

    // Refuse before spending anything upstream if a photo is missing. Dropping it
    // silently would produce an assessment of eight photos that reads as if it
    // were of nine, and the customer would never know which one was ignored.
    val missing = request.blobs.filterNot { blobs.exists(it) }
    if (missing.isNotEmpty()) {
        call.respond(HttpStatusCode.Conflict, MissingBlobsResponse(missing = missing))
        return null
    }

    // The system prompt is assembled here from the protocols on GitHub, and
    // whatever the client sends is irrelevant — a client that could supply its own
    // would be spending our budget on a prompt of its choosing.
    val systemPrompt = prompts.systemPromptFor(itemType)

    val access = store.ensureSession(
        sessionId = request.sessionId,
        userId = userId,
        itemTypeId = itemType.id,
        previousSessionId = request.previousSessionId,
        intakeAnswers = request.intakeAnswers,
        promptSha = sha256Of(systemPrompt),
        dailyLimit = dailyAssessmentLimit,
        testerDailyLimit = testerDailyAssessmentLimit,
    )
    if (access is SessionAccess.NotYours) {
        // 404, never 403: telling the difference would let anybody enumerate
        // which session ids exist.
        log.warn("User {} tried to post into another user's session", userId)
        call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
        return null
    }
    if (access is SessionAccess.DailyLimitReached) {
        // Refused before any request to Claude, which is the entire point.
        log.info("User {} reached the daily limit of {}", userId, access.limit)
        call.respond(
            HttpStatusCode.TooManyRequests,
            ErrorResponse("daily_limit_reached", "limit is ${access.limit} per day"),
        )
        return null
    }

    val isNewTurn = store.appendUserTurn(
        sessionId = request.sessionId,
        messageId = request.messageId,
        text = request.text,
        blobHashes = request.blobs,
    )
    if (!isNewTurn) {
        // The phone is retrying a turn we already have. If the reply is stored,
        // hand it back rather than paying for it twice; if it is not, the earlier
        // attempt failed upstream and falling through re-attempts it.
        store.replyAfter(request.sessionId, request.messageId)?.let { reply ->
            return PreparedTurn.Replay(reply.messageId, reply.text)
        }
    }

    return PreparedTurn.Ready(
        userId = userId,
        sessionId = request.sessionId,
        systemPrompt = systemPrompt,
        history = store.history(request.sessionId) { sha -> blobs.pathFor(sha).absolutePath },
    )
}

/** Stores a finished reply and its cost. Identical for a streamed and a whole answer. */
private suspend fun storeReply(
    store: ChatStore,
    turn: PreparedTurn.Ready,
    result: ClaudeResult.Success,
    elapsedMillis: Long,
): String {
    // Parsed here so the reports list can badge a verdict without
    // re-reading the conversation — the same reason the phone does it.
    val parsed = parseAssistantContent(result.text)
    val messageId = store.appendAssistantTurn(
        sessionId = turn.sessionId,
        text = result.text,
        preview = parsed.verdict?.headline?.ifBlank { null }
            ?: markdownToPlainText(parsed.prose.ifBlank { result.text }),
        verdictLevelId = parsed.verdict?.level?.id,
        verdictLanguage = parsed.verdict?.language,
    )
    store.recordUsage(
        turn.userId, turn.sessionId, result.model, result.usage,
        httpStatus = 200, latencyMillis = elapsedMillis, errorKind = null,
    )
    return messageId
}

private suspend fun recordFailure(
    store: ChatStore,
    turn: PreparedTurn.Ready,
    result: ClaudeResult.Failure,
    elapsedMillis: Long,
) {
    // Recorded even when the reply is discarded: tokens generated before a stream broke
    // were billed to us, and a usage table that only counts successes understates spend.
    store.recordUsage(
        turn.userId, turn.sessionId, null, result.usage,
        httpStatus = result.httpStatus, latencyMillis = elapsedMillis,
        errorKind = result.error.name,
    )
}

private fun statusFor(error: UpstreamError): HttpStatusCode = when (error) {
    UpstreamError.RATE_LIMIT -> HttpStatusCode.TooManyRequests
    UpstreamError.OVERLOADED, UpstreamError.AUTH -> HttpStatusCode.ServiceUnavailable
    UpstreamError.NETWORK -> HttpStatusCode.GatewayTimeout
    UpstreamError.SERVER -> HttpStatusCode.BadGateway
    UpstreamError.REQUEST, UpstreamError.UNKNOWN -> HttpStatusCode.InternalServerError
}

private fun sha256Of(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

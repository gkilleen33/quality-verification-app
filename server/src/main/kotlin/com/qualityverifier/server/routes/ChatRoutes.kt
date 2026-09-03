package com.qualityverifier.server.routes

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.ItemType
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.UpstreamError
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
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
            val userId = call.principal<JWTPrincipal>()?.subject
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return@post
            }

            val request = call.receive<ChatRequest>()
            val itemType = ItemType.fromId(request.itemTypeId)
            if (request.sessionId.isBlank() || request.messageId.isBlank() || itemType == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "session_id, message_id and a known item_type_id are required"),
                )
                return@post
            }
            if (request.text.isBlank() && request.blobs.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "a turn needs text or a photo"),
                )
                return@post
            }
            if (request.blobs.any { !BlobStore.isValidHash(it) }) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_hash"))
                return@post
            }

            // Refuse before spending anything upstream if a photo is missing. Dropping it
            // silently would produce an assessment of eight photos that reads as if it
            // were of nine, and the customer would never know which one was ignored.
            val missing = request.blobs.filterNot { blobs.exists(it) }
            if (missing.isNotEmpty()) {
                call.respond(HttpStatusCode.Conflict, MissingBlobsResponse(missing = missing))
                return@post
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
                return@post
            }
            if (access is SessionAccess.DailyLimitReached) {
                // Refused before any request to Claude, which is the entire point.
                log.info("User {} reached the daily limit of {}", userId, access.limit)
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("daily_limit_reached", "limit is ${access.limit} per day"),
                )
                return@post
            }

            // Recorded before the upstream call, and never allowed to affect it. A failure here
    // must not cost the customer their turn: this is optional research data attached to
    // an assessment somebody has spent minutes on.
    request.locationOrNull?.let { location ->
        runCatching { store.recordSessionLocation(request.sessionId, userId, location) }
            .onFailure { log.warn("Could not record the assessment location", it) }
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
                    log.info("Replaying a stored reply for message {}", request.messageId)
                    call.respond(ChatResponse(reply.messageId, reply.text))
                    return@post
                }
            }

            val history = store.history(request.sessionId) { sha -> blobs.pathFor(sha).absolutePath }

            val startedAt = System.currentTimeMillis()
            val result = claude.send(systemPrompt, history) { attachment ->
                blobs.read(java.io.File(attachment.path).nameWithoutExtension)
            }
            val elapsed = System.currentTimeMillis() - startedAt

            when (result) {
                is ClaudeResult.Success -> {
                    // Parsed here so the reports list can badge a verdict without
                    // re-reading the conversation — the same reason the phone does it.
                    val parsed = parseAssistantContent(result.text)
                    val messageId = store.appendAssistantTurn(
                        sessionId = request.sessionId,
                        text = result.text,
                        preview = parsed.verdict?.headline?.ifBlank { null }
                            ?: markdownToPlainText(parsed.prose.ifBlank { result.text }),
                        verdictLevelId = parsed.verdict?.level?.id,
                        verdictLanguage = parsed.verdict?.language,
                    )
                    store.recordUsage(
                        userId, request.sessionId, result.model, result.usage,
                        httpStatus = 200, latencyMillis = elapsed, errorKind = null,
                    )
                    call.respond(ChatResponse(messageId, result.text))
                }

                is ClaudeResult.Failure -> {
                    store.recordUsage(
                        userId, request.sessionId, null, result.usage,
                        httpStatus = result.httpStatus, latencyMillis = elapsed,
                        errorKind = result.error.name,
                    )
                    // The customer's turn stays stored, so a retry does not ask them to
                    // take the photos again.
                    val status = when (result.error) {
                        UpstreamError.RATE_LIMIT -> HttpStatusCode.TooManyRequests
                        UpstreamError.OVERLOADED, UpstreamError.AUTH -> HttpStatusCode.ServiceUnavailable
                        UpstreamError.NETWORK -> HttpStatusCode.GatewayTimeout
                        UpstreamError.SERVER -> HttpStatusCode.BadGateway
                        UpstreamError.REQUEST, UpstreamError.UNKNOWN -> HttpStatusCode.InternalServerError
                    }
                    // Our upstream's message never reaches the phone: it can name a model,
                    // a quota or an account, none of which are the customer's business.
                    call.respond(status, ErrorResponse("upstream_unavailable"))
                }
            }
        }
    }
}

private fun sha256Of(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

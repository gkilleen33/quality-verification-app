package com.qualityverifier.server.routes

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ItemType
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.UpstreamError
import com.qualityverifier.server.db.AuthStore
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
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("com.qualityverifier.server.chat")

/**
 * Assessments, one turn at a time.
 *
 * **The route is the audience, and that is the whole design.** There are two endpoints
 * because there are two apps, and each one hard-codes the prompt it serves: Kagua's
 * `/v1/chat` passes [Audience.BUYER] and Fundi Bora's `/v1/fundi/chat` passes
 * [Audience.FUNDI]. Neither reads anything to decide.
 *
 * It used to be one endpoint that resolved the audience from the account. That worked, and
 * it was the wrong shape: it meant Kagua's own endpoint contained a live path to the
 * coaching prompt, reachable by a data change with no code change and no client involved.
 * A buyer standing in a furniture shop being told how to re-glue the joint they are
 * inspecting is not a failure any test would have caught, because nothing about it is an
 * error. Now the Kagua app has no code path there at all.
 *
 * The account is still checked, but as a guard rather than a selector — see
 * [requireAudience]. A mismatch is refused instead of quietly serving the other app's
 * prompt, which is what made the old shape a selector in the first place.
 */
fun Route.chatRoutes(
    store: ChatStore,
    auth: AuthStore,
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

        // Kagua. Hard-coded to the buying prompt; there is no branch here to get wrong.
        post("/v1/chat") {
            assessmentTurn(
                Audience.BUYER, store, auth, blobs, claude, prompts,
                dailyAssessmentLimit, testerDailyAssessmentLimit,
            )
        }

        // Fundi Bora. Same pipeline, same camera, same tables — the inward-pointing
        // prompt, and an endpoint Kagua's app does not know exists.
        post("/v1/fundi/chat") {
            assessmentTurn(
                Audience.FUNDI, store, auth, blobs, claude, prompts,
                dailyAssessmentLimit, testerDailyAssessmentLimit,
            )
        }
    }
}

/**
 * One turn of an assessment, for whichever audience the caller's route named.
 *
 * Shared body rather than two copies: everything below the prompt choice is identical for
 * both apps — the same missing-photo refusal, the same replay of a stored reply, the same
 * truncation handling — and two copies of the money-spending path would drift.
 */
private suspend fun RoutingContext.assessmentTurn(
    audience: Audience,
    store: ChatStore,
    auth: AuthStore,
    blobs: BlobStore,
    claude: ClaudeClient,
    prompts: PromptRepository,
    dailyAssessmentLimit: Int,
    testerDailyAssessmentLimit: Int,
) {
            val userId = call.principal<JWTPrincipal>()?.subject
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return
            }

            val request = call.receive<ChatRequest>()
            val itemType = ItemType.fromId(request.itemTypeId)
            if (request.sessionId.isBlank() || request.messageId.isBlank() || itemType == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "session_id, message_id and a known item_type_id are required"),
                )
                return
            }
            if (request.text.isBlank() && request.blobs.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "a turn needs text or a photo"),
                )
                return
            }
            if (request.blobs.any { !BlobStore.isValidHash(it) }) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_hash"))
                return
            }

            // Refuse before spending anything upstream if a photo is missing. Dropping it
            // silently would produce an assessment of eight photos that reads as if it
            // were of nine, and the customer would never know which one was ignored.
            val missing = request.blobs.filterNot { blobs.exists(it) }
            if (missing.isNotEmpty()) {
                call.respond(HttpStatusCode.Conflict, MissingBlobsResponse(missing = missing))
                return
            }

            // A guard, not a selector. The audience came from the route; this only
            // refuses an account that belongs to the other app, so neither prompt can be
            // reached by an account that is not for it.
            if (!requireAudience(audience, auth, userId)) return

            // The system prompt is assembled here from the protocols on GitHub, and
            // whatever the client sends is irrelevant — a client that could supply its own
            // would be spending our budget on a prompt of its choosing.
            val systemPrompt = prompts.systemPromptFor(itemType, audience)

            val access = store.ensureSession(
                sessionId = request.sessionId,
                userId = userId,
                itemTypeId = itemType.id,
                previousSessionId = request.previousSessionId,
                intakeAnswers = request.intakeAnswers,
                promptSha = sha256Of(systemPrompt),
                audience = audience,
                dailyLimit = dailyAssessmentLimit,
                testerDailyLimit = testerDailyAssessmentLimit,
            )
            if (access is SessionAccess.NotYours) {
                // 404, never 403: telling the difference would let anybody enumerate
                // which session ids exist.
                log.warn("User {} tried to post into another user's session", userId)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return
            }
            if (access is SessionAccess.DailyLimitReached) {
                // Refused before any request to Claude, which is the entire point.
                log.info("User {} reached the daily limit of {}", userId, access.limit)
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("daily_limit_reached", "limit is ${access.limit} per day"),
                )
                return
            }

            // Recorded before the upstream call, and never allowed to affect it. A failure
            // here must not cost the customer their turn: this is optional research data
            // attached to an assessment somebody has spent minutes on.
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
                    return
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
                        // Already parsed, and until now thrown away. Null when there is
                        // no verdict, which is not the same as a verdict that found
                        // nothing wrong — see V15.
                        defectCount = parsed.verdict?.defects?.size,
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
                        UpstreamError.SERVER, UpstreamError.TRUNCATED -> HttpStatusCode.BadGateway
                        UpstreamError.REQUEST, UpstreamError.UNKNOWN -> HttpStatusCode.InternalServerError
                    }
                    // Our upstream's message never reaches the phone: it can name a model,
                    // a quota or an account, none of which are the customer's business.
                    // The code does, though — a cut-off answer needs different wording
                    // from an assistant that is busy, and the phone reads this to choose.
                    val code = if (result.error == UpstreamError.TRUNCATED) {
                        "answer_truncated"
                    } else {
                        "upstream_unavailable"
                    }
                    call.respond(status, ErrorResponse(code))
                }
            }
}

/**
 * Refuses an account that belongs to the other app.
 *
 * A guard and never a selector: the audience is already fixed by the route, so this can
 * only turn a request away, never redirect it to a different prompt. That is the whole
 * difference from what this replaced.
 *
 * 403 rather than 404: the two apps have entirely independent accounts, so a Kagua account
 * reaching Fundi Bora's endpoint is a client bug worth naming, and there is no id here
 * whose existence could be leaked by saying so.
 */
private suspend fun RoutingContext.requireAudience(
    audience: Audience,
    auth: AuthStore,
    userId: String,
): Boolean {
    val actual = auth.findUser(userId)?.audience
    if (actual == audience) return true
    log.warn("Account {} ({}) tried to use the {} assessment endpoint", userId, actual, audience)
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("wrong_app"))
    return false
}

private fun sha256Of(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

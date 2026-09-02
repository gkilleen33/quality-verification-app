package com.qualityverifier.server.routes

import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.db.TesterFeedback
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.SessionRow
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qualityverifier.server.sync")

/**
 * Reading assessments back, and the two account actions.
 *
 * Until this existed the server only ever wrote: it held every assessment and the phone
 * could not see any of them, so a reinstall lost a customer's whole history while a
 * perfectly good copy sat on disk.
 */
fun Route.syncRoutes(
    chat: ChatStore,
    auth: AuthStore,
    blobs: BlobStore,
    feedbackStore: FeedbackStore,
) {
    authenticate("jwt") {

        get("/v1/sessions") {
            val userId = call.userId() ?: return@get call.unauthorized()
            call.respond(SessionListDto(chat.sessionsFor(userId).map { it.toDto() }))
        }

        get("/v1/sessions/{id}") {
            val userId = call.userId() ?: return@get call.unauthorized()
            val id = call.parameters["id"].orEmpty()
            // A non-UUID reaches Postgres as `?::uuid` and throws, which StatusPages turns
            // into a 500. It is the same "no such thing" as any other unknown id, so it
            // gets the same answer.
            if (!isUuid(id)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return@get
            }
            val detail = chat.sessionDetail(userId, id)
            if (detail == null) {
                // Same answer for "does not exist" and "belongs to somebody else".
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return@get
            }
            call.respond(SessionDetailDto(detail.first.toDto(), detail.second.map { it.toDto() }))
        }

        delete("/v1/sessions/{id}") {
            val userId = call.userId() ?: return@delete call.unauthorized()
            val id = call.parameters["id"].orEmpty()
            if (!isUuid(id)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return@delete
            }
            if (!chat.markClientDeleted(userId, id)) {
                // Idempotent from the client's point of view: already deleted, never
                // existed, or not theirs all mean "stop asking".
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return@delete
            }
            log.info("Session {} marked deleted by its owner", id)
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * Serves a photo back so a reinstalled app can rebuild a conversation.
         *
         * Ownership is checked, not assumed. An earlier version of this route treated the
         * hash itself as the capability, on the reasoning that knowing a SHA-256 means
         * having had the bytes. That reasoning is wrong here: this very API hands hashes
         * out in session detail, and they will also appear in the admin portal, in
         * research exports and in logs. A hash is an identifier, not a secret, so the
         * account asking has to be one the photo belongs to.
         */
        get("/v1/blobs/{sha256}") {
            val userId = call.userId() ?: return@get call.unauthorized()
            val sha = call.parameters["sha256"].orEmpty()
            if (!BlobStore.isValidHash(sha)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_hash"))
                return@get
            }
            // Being signed in is not enough. The store is content-addressed, so a hash
            // alone carries no owner; without this, any account could read any photo it
            // knew the hash of. 404 rather than 403, for the same reason as a session:
            // 403 would confirm the photo exists.
            if (!chat.blobBelongsTo(userId, sha)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_blob"))
                return@get
            }
            val bytes = blobs.read(sha)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_blob"))
                return@get
            }
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }

        /**
         * An evaluator's critique of an assessment they just did.
         *
         * Restricted to evaluator accounts. Not because a customer's opinion is unwelcome,
         * but because these rows are a research instrument: a mix of staff critiques and
         * unsolicited customer ratings in one table is a dataset nobody can use, and the
         * app only ever shows these questions to a tester anyway. A non-tester reaching
         * here is a client bug or somebody poking, and both deserve the same answer.
         */
        post("/v1/tester-feedback") {
            val userId = call.userId() ?: return@post call.unauthorized()
            val user = auth.findUser(userId)
            if (user == null || user.disabled) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return@post
            }
            if (!user.isTester) {
                log.warn("Non-tester {} tried to submit evaluator feedback", userId)
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_tester"))
                return@post
            }

            val request = call.receive<TesterFeedbackRequest>()
            val feedback = TesterFeedback(
                sessionId = request.sessionId,
                mistakes = request.mistakes,
                mistakesDetail = request.mistakesDetail?.takeIf { it.isNotBlank() },
                adviceStars = request.adviceStars,
                itemQuality = request.itemQuality,
                extraFeedback = request.extraFeedback?.takeIf { it.isNotBlank() },
            )
            TesterFeedback.problemWith(feedback)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", problem))
                return@post
            }
            if (!isUuid(feedback.sessionId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "session_id"))
                return@post
            }

            if (!feedbackStore.save(userId, feedback)) {
                // Not theirs, or gone. 404 rather than 403, as everywhere else here.
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                return@post
            }
            log.info("Evaluator feedback recorded for session {}", feedback.sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/auth/password") {
            val userId = call.userId() ?: return@post call.unauthorized()
            val request = call.receive<ChangePasswordRequest>()
            if (request.newPassword.length < 8) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "password must be at least 8 characters"),
                )
                return@post
            }

            // The current password is required even though the caller already holds a
            // valid token: a token can be lifted from an unlocked phone, and a password
            // change is what would lock the owner out of their own account.
            val hash = auth.passwordHashFor(userId)
            if (hash == null || !Passwords.verify(request.currentPassword, hash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
                return@post
            }

            auth.setPasswordHash(userId, Passwords.hash(request.newPassword))
            // Every other device signs out. A password change is usually a response to
            // somebody else having had access.
            val revoked = auth.revokeChain(userId)
            log.info("Password changed for {}; revoked {} token(s)", userId, revoked)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/v1/account") {
            val userId = call.userId() ?: return@delete call.unauthorized()
            auth.markAccountDeleted(userId)
            log.info("Account {} marked deleted by its owner", userId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.userId(): String? =
    principal<JWTPrincipal>()?.subject

private suspend fun io.ktor.server.application.ApplicationCall.unauthorized() =
    respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))

private fun SessionRow.toDto() = SessionSummaryDto(
    id = id,
    itemTypeId = itemTypeId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    preview = preview,
    messageCount = messageCount,
    verdictLevelId = verdictLevelId,
    verdictLanguage = verdictLanguage,
    previousSessionId = previousSessionId,
    intakeAnswers = intakeAnswers,
)

private fun MessageRow.toDto() = MessageDto(
    id = id,
    role = role,
    text = text,
    ordinal = ordinal,
    createdAt = createdAt,
    blobs = blobs,
)

/**
 * Whether this could be a session id at all.
 *
 * Postgres casts these with `?::uuid`, which throws on anything malformed — so without a
 * check, a junk id in a path is a 500 instead of a 404.
 */
private fun isUuid(value: String): Boolean =
    runCatching { java.util.UUID.fromString(value) }.isSuccess

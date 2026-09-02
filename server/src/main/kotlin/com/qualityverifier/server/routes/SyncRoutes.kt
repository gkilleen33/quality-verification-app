package com.qualityverifier.server.routes

import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.db.AuthStore
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
fun Route.syncRoutes(chat: ChatStore, auth: AuthStore, blobs: BlobStore) {
    authenticate("jwt") {

        get("/v1/sessions") {
            val userId = call.userId() ?: return@get call.unauthorized()
            call.respond(SessionListDto(chat.sessionsFor(userId).map { it.toDto() }))
        }

        get("/v1/sessions/{id}") {
            val userId = call.userId() ?: return@get call.unauthorized()
            val id = call.parameters["id"].orEmpty()
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

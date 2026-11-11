package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.Terms
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.terms.Agreement
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.TermsLanguage
import com.qualityverifier.text.MpesaConsentText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qualityverifier.server.terms")

/**
 * `GET /terms/{app}/{version}/{language}`: a published terms text, byte for byte as the
 * index pins it, so a phone can check the hash before showing it.
 *
 * Unauthenticated, as the text of the terms is not personal and a phone may need it before
 * its token is refreshed. The hash is the ETag, so a phone that already holds the file can
 * tell without downloading it again.
 */
fun Route.termsTextRoutes(terms: Terms) {
    get("/terms/{app}/{version}/{language}") {
        val audience = call.parameters["app"]?.let { Audience.fromId(it) }
        val language = TermsLanguage.fromId(call.parameters["language"])
        val version = call.parameters["version"].orEmpty()
        val file = if (audience != null && language != null) terms.texts.file(audience, version, language) else null
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            return@get
        }
        call.response.headers.append(HttpHeaders.ETag, "\"${file.sha256}\"")
        call.respondBytes(file.bytes, ContentType("text", "markdown").withCharset(Charsets.UTF_8))
    }
}

/**
 * Recording agreement and withdrawal. Both are on the terms gate's open list: an account
 * that has not agreed has to be able to agree.
 *
 * Every row carries the app version from the request's header, which says which build's
 * screen applied the rules (both boxes unticked, enabled only at the end of the text). A
 * request without it is refused, since a row without it would prove less than the rest.
 */
fun Route.consentRoutes(users: AuthStore, terms: Terms) {
    authenticate("jwt") {

        post("/v1/me/consents") {
            val user = call.principal<JWTPrincipal>()?.subject?.let { users.findUser(it) }
            if (user == null || user.disabled) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return@post
            }
            val request = call.receive<ConsentRequest>()
            val appVersion = AppVersionHeaders.parseVersion(call.request.headers[AppVersionHeaders.VERSION])
            if (appVersion == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "X-App-Version is required"))
                return@post
            }
            // One M-Pesa question at a time: each has its own text, so its own hash.
            val mpesaKind = request.kinds.singleOrNull()?.let(ConsentKind::fromId)
                ?.takeIf { it == ConsentKind.MPESA_READ || it == ConsentKind.MPESA_SEND }
            if (mpesaKind != null) {
                call.agreeToMpesa(user, mpesaKind, request, appVersion, terms)
                return@post
            }
            // Both boxes or nothing: an agreement to the terms without the transfer is not
            // one Fundi Bora can work under, and the screen cannot send one.
            if (request.kinds.toSet() != ConsentKind.TERMS_SCREEN.map { it.id }.toSet() ||
                request.kinds.size != ConsentKind.TERMS_SCREEN.size
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "kinds must be exactly terms and transfer, or one M-Pesa question"),
                )
                return@post
            }
            val current = terms.gate.currentVersion(user.audience)
            if (current == null || request.version != current) {
                // The text changed while the screen was open. The phone reloads and asks again.
                call.respond(HttpStatusCode.Conflict, ErrorResponse("terms_version_changed"))
                return@post
            }
            val language = TermsLanguage.fromId(request.language)
            val file = language?.let { terms.texts.file(user.audience, current, it) }
            if (language == null || file == null || file.sha256 != request.textSha256.lowercase()) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("terms_text_mismatch"))
                return@post
            }
            terms.consents.agree(
                user.id,
                Agreement(
                    // From the account, never from the request.
                    audience = user.audience,
                    version = current,
                    language = language,
                    textSha256 = file.sha256,
                    appVersion = appVersion,
                ),
                ConsentKind.TERMS_SCREEN,
            )
            terms.gate.forget(user.id)
            log.info("Recorded agreement to {} terms {} ({}) for user {}", user.audience.id, current, language.id, user.id)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/v1/me/consents/withdrawal") {
            val user = call.principal<JWTPrincipal>()?.subject?.let { users.findUser(it) }
            if (user == null || user.disabled) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return@post
            }
            val request = call.receive<WithdrawalRequest>()
            val appVersion = AppVersionHeaders.parseVersion(call.request.headers[AppVersionHeaders.VERSION])
            if (appVersion == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "X-App-Version is required"))
                return@post
            }
            // Withdrawing the terms themselves is deleting the account, which Settings does.
            val kind = ConsentKind.fromId(request.kind)?.takeIf { it in ConsentKind.WITHDRAWABLE }
            if (kind == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "only the transfer agreement and the M-Pesa questions can be withdrawn"),
                )
                return@post
            }
            // Sending was only ever allowed on top of reading, so stopping reading stops
            // sending too: the newest rows never say "send" without "read".
            val withdrawn = if (kind == ConsentKind.MPESA_READ) {
                listOf(ConsentKind.MPESA_SEND, ConsentKind.MPESA_READ)
            } else {
                listOf(kind)
            }
            // Idempotent: a second withdrawal finds nothing to withdraw and writes nothing.
            withdrawn.forEach {
                if (terms.consents.withdraw(user.id, user.audience, it, appVersion)) {
                    log.info("Recorded withdrawal of {} for user {}", it.id, user.id)
                }
            }
            terms.gate.forget(user.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Records agreement to one M-Pesa question. Fundi Bora only: Kagua reads no messages.
 *
 * The phone sends the hash of the question it showed; it must be one of the texts in
 * [MpesaConsentText.published], so the row names words the fundi actually read. Sending
 * is asked only after a yes to reading, and the server holds the same order.
 */
private suspend fun ApplicationCall.agreeToMpesa(
    user: UserRow,
    kind: ConsentKind,
    request: ConsentRequest,
    appVersion: Int,
    terms: Terms,
) {
    if (user.audience != Audience.FUNDI) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_fundi"))
        return
    }
    val language = TermsLanguage.fromId(request.language)
    val sha = request.textSha256.lowercase()
    val published = MpesaConsentText.published[Triple(kind.id, request.version, request.language)]
    if (language == null || published == null || published != sha) {
        respond(HttpStatusCode.Conflict, ErrorResponse("mpesa_text_mismatch"))
        return
    }
    if (kind == ConsentKind.MPESA_SEND && !terms.gate.agreed(user.id, ConsentKind.MPESA_READ)) {
        respond(HttpStatusCode.Conflict, ErrorResponse("mpesa_read_required"))
        return
    }
    terms.consents.agree(
        user.id,
        Agreement(
            audience = user.audience,
            version = request.version,
            language = language,
            textSha256 = sha,
            appVersion = appVersion,
        ),
        listOf(kind),
    )
    terms.gate.forget(user.id)
    log.info("Recorded agreement to {} {} for user {}", kind.id, request.version, user.id)
    respond(HttpStatusCode.NoContent)
}

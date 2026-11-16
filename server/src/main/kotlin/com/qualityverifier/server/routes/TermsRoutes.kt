package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.Terms
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.terms.Agreement
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.TermsApp
import com.qualityverifier.server.terms.TermsLanguage
import com.qualityverifier.text.MpesaConsentText
import com.qualityverifier.text.TrackerConsentText
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
 * `GET /v1/terms/{app}/{version}/{language}`: a published terms text, byte for byte as the
 * index pins it, so a phone can check the hash before showing it.
 *
 * Unauthenticated, as the text of the terms is not personal and a phone may need it before
 * its token is refreshed. The hash is the ETag, so a phone that already holds the file can
 * tell without downloading it again.
 *
 * Under `/v1/` because the production nginx passes only `/v1/`, `/api/`, `/admin` and
 * `/healthz` to this process. The first path, `/terms/...`, never reached it: nginx answered
 * 404 itself. Nobody noticed until a new terms version (2026-11-16) made phones download a
 * text instead of using their bundled copy, and every fundi was asked to agree to terms
 * their phone could not load. The phone fetches whatever path `/v1/me` names
 * ([Terms.describe]), so moving the path there fixes v0.5.0 phones with no new APK.
 * `/terms/...` stays mounted for any client that kept the old path.
 */
fun Route.termsTextRoutes(terms: Terms) {
    get("/v1/terms/{app}/{version}/{language}") { respondTermsText(call, terms) }
    get("/terms/{app}/{version}/{language}") { respondTermsText(call, terms) }
}

private suspend fun respondTermsText(call: ApplicationCall, terms: Terms) {
    // An audience's id, or `tracker` for PesaLog's terms (TermsApp).
    val app = TermsApp.fromId(call.parameters["app"])
    val language = TermsLanguage.fromId(call.parameters["language"])
    val version = call.parameters["version"].orEmpty()
    val file = if (app != null && language != null) terms.texts.file(app, version, language) else null
    if (file == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
        return
    }
    call.response.headers.append(HttpHeaders.ETag, "\"${file.sha256}\"")
    call.respondBytes(file.bytes, ContentType("text", "markdown").withCharset(Charsets.UTF_8))
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
            // One question at a time: each has its own text, so its own hash.
            val question = request.kinds.singleOrNull()?.let(ConsentKind::fromId)
            when (question) {
                ConsentKind.MPESA_READ, ConsentKind.MPESA_SEND -> {
                    call.agreeToMpesa(user, question, request, appVersion, terms)
                    return@post
                }
                ConsentKind.TRACKER_READ, ConsentKind.TRACKER_SEND -> {
                    call.agreeToTrackerQuestion(user, question, request, appVersion, terms)
                    return@post
                }
                else -> Unit
            }
            // PesaLog's terms screen: its two boxes, as Fundi Bora's below.
            if (request.kinds.sorted() == ConsentKind.TRACKER_TERMS_SCREEN.map { it.id }.sorted()) {
                call.agreeToTrackerTerms(user, request, appVersion, terms)
                return@post
            }
            // Both boxes or nothing: an agreement to the terms without the transfer is not
            // one Fundi Bora can work under, and the screen cannot send one.
            if (request.kinds.toSet() != ConsentKind.TERMS_SCREEN.map { it.id }.toSet() ||
                request.kinds.size != ConsentKind.TERMS_SCREEN.size
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "invalid_request",
                        "kinds must be exactly terms and transfer, tracker_terms and tracker_transfer, or one question",
                    ),
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
                    ErrorResponse(
                        "invalid_request",
                        "only a transfer agreement, the M-Pesa questions and PesaLog's questions can be withdrawn",
                    ),
                )
                return@post
            }
            // Sending was only ever allowed on top of reading, so stopping reading stops
            // sending too: the newest rows never say "send" without "read". The same for
            // PesaLog's two questions.
            val withdrawn = when (kind) {
                ConsentKind.MPESA_READ -> listOf(ConsentKind.MPESA_SEND, ConsentKind.MPESA_READ)
                ConsentKind.TRACKER_READ -> listOf(ConsentKind.TRACKER_SEND, ConsentKind.TRACKER_READ)
                else -> listOf(kind)
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

/**
 * Records agreement to both boxes of PesaLog's terms screen (V33). A Fundi Bora account
 * only: PesaLog signs in with one. The rules are Fundi Bora's: the current version, a
 * published language, and the hash of the exact file the phone showed.
 *
 * `consents.audience` is still the account's, `fundi`; what says these rows are PesaLog's
 * is their kinds.
 */
private suspend fun ApplicationCall.agreeToTrackerTerms(
    user: UserRow,
    request: ConsentRequest,
    appVersion: Int,
    terms: Terms,
) {
    if (user.audience != Audience.FUNDI) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_fundi"))
        return
    }
    val current = terms.gate.currentTrackerVersion()
    if (current == null || request.version != current) {
        respond(HttpStatusCode.Conflict, ErrorResponse("terms_version_changed"))
        return
    }
    val language = TermsLanguage.fromId(request.language)
    val file = language?.let { terms.texts.file(TermsApp.TRACKER, current, it) }
    if (language == null || file == null || file.sha256 != request.textSha256.lowercase()) {
        respond(HttpStatusCode.Conflict, ErrorResponse("terms_text_mismatch"))
        return
    }
    terms.consents.agree(
        user.id,
        Agreement(
            audience = user.audience,
            version = current,
            language = language,
            textSha256 = file.sha256,
            appVersion = appVersion,
        ),
        ConsentKind.TRACKER_TERMS_SCREEN,
    )
    terms.gate.forget(user.id)
    log.info("Recorded agreement to PesaLog's terms {} ({}) for user {}", current, language.id, user.id)
    respond(HttpStatusCode.NoContent)
}

/**
 * Records agreement to one of PesaLog's two questions, against [TrackerConsentText.published],
 * as [agreeToMpesa] does for Fundi Bora's. Both are asked after PesaLog's terms, and sending
 * after reading; the server holds the same order.
 */
private suspend fun ApplicationCall.agreeToTrackerQuestion(
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
    val published = TrackerConsentText.published[Triple(kind.id, request.version, request.language)]
    if (language == null || published == null || published != sha) {
        respond(HttpStatusCode.Conflict, ErrorResponse("tracker_text_mismatch"))
        return
    }
    if (terms.gate.trackerStatusFor(user.id).required) {
        respond(HttpStatusCode.Conflict, ErrorResponse("tracker_terms_required"))
        return
    }
    if (kind == ConsentKind.TRACKER_SEND && !terms.gate.agreed(user.id, ConsentKind.TRACKER_READ)) {
        respond(HttpStatusCode.Conflict, ErrorResponse("tracker_read_required"))
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

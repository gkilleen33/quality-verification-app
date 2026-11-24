package com.qualityverifier.server.routes

import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.server.chat.ReplyTranslator
import com.qualityverifier.server.db.AuthStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The body of `POST /v1/fundi/sessions/{id}/translations`: the language to show, "en" or "sw". */
@Serializable
data class TranslationRequest(val language: String = "")

/** One reply's translation, named by the SHA-256 of the original reply's text. */
@Serializable
data class TranslationItem(
    @SerialName("source_sha256") val sourceSha256: String,
    val text: String,
)

/**
 * Every translated reply of the assessment, and the hashes of replies with no translation
 * (the call failed, the check refused it, or it was still being written). The phone shows
 * those in their original language and says so.
 */
@Serializable
data class TranslationResponse(
    val language: String,
    val translations: List<TranslationItem>,
    val failed: List<String>,
)

/**
 * Fundi Bora's English and Kiswahili switch (v0.7.0, release scope row 29). The first tap on
 * the other language translates the assessment's replies; later taps, on any phone signed in
 * to the account, get the stored ones with no call to Claude. See [ReplyTranslator].
 *
 * A new route, so no phone before v0.7.0 calls it, and nothing an older phone reads (the turn,
 * the session list, a session's messages) carries a translation. Behind the terms gate like
 * every other assessment route: not in TermsGate.OPEN.
 */
fun Route.translationRoutes(translator: ReplyTranslator, auth: AuthStore) {
    authenticate("jwt") {
        post("/v1/fundi/sessions/{id}/translations") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            // A shop's tablet is a Kagua account, and Kagua has no switch.
            if (!call.requireFundi(auth, userId)) return@post

            val sessionId = call.parameters["id"].orEmpty()
            val request = call.receive<TranslationRequest>()
            val language = AssessmentLanguage.entries.firstOrNull { it.code == request.language }
            if (!isUuid(sessionId) || language == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "the path needs a session id, and language must be \"en\" or \"sw\""),
                )
            }
            // 404 for another account's assessment as for none, as the chat route answers.
            val outcome = translator.translate(userId, sessionId, language)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
            call.respond(
                TranslationResponse(
                    language = language.code,
                    translations = outcome.translations.entries.sortedBy { it.key }.map { TranslationItem(it.key, it.value) },
                    failed = outcome.failed,
                ),
            )
        }
    }
}

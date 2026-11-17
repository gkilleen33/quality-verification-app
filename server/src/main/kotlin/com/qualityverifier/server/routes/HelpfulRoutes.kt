package com.qualityverifier.server.routes

import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.HelpfulAnswer
import com.qualityverifier.server.db.HelpfulStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** The body of `PUT /v1/fundi/sessions/{id}/helpful`, and its answer: the answer the server holds. */
@Serializable
data class HelpfulDto(val helpful: Boolean)

/**
 * "Was this review helpful?" (V36, release scope row 7): a tester's Yes or No after a Fundi
 * Bora verdict, one per assessment.
 *
 * Answered with the answer held, which is the first one sent: a phone that sends again after
 * a lost reply, or sends a different answer, is told what was kept rather than refused, so
 * its retry ends. 403 for an account that is not a tester, 404 for an assessment that is not
 * the account's own or has no verdict.
 */
fun Route.helpfulRoutes(helpful: HelpfulStore, auth: AuthStore) {
    authenticate("jwt") {
        put("/v1/fundi/sessions/{id}/helpful") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@put
            if (auth.findUser(userId)?.isTester != true) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_tester"))
            }
            val sessionId = call.parameters["id"].orEmpty()
            if (!isUuid(sessionId)) {
                return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "id must be a uuid"))
            }
            val body = call.receive<HelpfulDto>()
            when (val held = helpful.answer(userId, sessionId, body.helpful)) {
                is HelpfulAnswer.Held -> call.respond(HttpStatusCode.OK, HelpfulDto(held.helpful))
                HelpfulAnswer.NoSuchVerdict -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_verdict"))
            }
        }
    }
}

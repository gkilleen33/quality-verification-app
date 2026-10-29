package com.qualityverifier.server.routes

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.FundiStore
import com.qualityverifier.server.db.PieceLabels
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qualityverifier.server.fundi")

/**
 * The maker's setup answers: workshop, tools and goals.
 *
 * Held here so they survive a reinstall and so they are part of the research record. The
 * phone keeps its own copy and is what actually writes them into the opening turn of an
 * assessment — see `buildFundiContextMessage` — because the profile is a description of
 * the maker and belongs in the conversation where they can read it.
 */
fun Route.fundiRoutes(store: FundiStore, auth: AuthStore) {
    authenticate("jwt") {

        get("/v1/fundi/profile") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"),
                )
            if (!call.requireFundi(auth, userId)) return@get

            val profile = store.profileFor(userId)
            if (profile == null) {
                // Registered but never set up. A 404 rather than an empty profile, because
                // the app has to tell "they answered nothing" from "they have not been
                // asked yet" — the first is a profile to leave alone, the second is a
                // setup flow to run.
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_profile"))
                return@get
            }
            call.respond(profile.toDto())
        }

        put("/v1/fundi/profile") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(
                    HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"),
                )
            if (!call.requireFundi(auth, userId)) return@put

            val profile = call.receive<FundiProfileDto>().toDomain()
            store.saveProfile(userId, profile)
            log.info(
                "Fundi profile saved for {}: {} tools, {} goals",
                userId, profile.tools.size, profile.goals.size,
            )
            call.respond(HttpStatusCode.OK, profile.toDto())
        }

        // The shop's GPS point, sent only when the maker said they were at the shop and
        // agreed to it being recorded. Its own endpoint because it is its own consent.
        put("/v1/fundi/workshop-location") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(
                    HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"),
                )
            if (!call.requireFundi(auth, userId)) return@put

            val fix = call.receive<WorkshopLocationDto>()
            if (!fix.isPlausible) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_location", "latitude, longitude and accuracy_m out of range"),
                )
                return@put
            }
            store.saveWorkshopLocation(userId, fix.latitude, fix.longitude, fix.accuracyMetres)
            log.info("Workshop location saved for {} (±{} m)", userId, fix.accuracyMetres.toInt())
            call.respond(HttpStatusCode.NoContent)
        }

        // What the maker calls a piece. The first assessment's opening turn names it;
        // this is the rename from the piece's card, and the only way a name changes.
        put("/v1/fundi/pieces/{id}") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@put call.respond(
                    HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"),
                )
            if (!call.requireFundi(auth, userId)) return@put

            // Cast with ?::uuid below, so a malformed id must be refused here rather
            // than thrown inside Postgres. It is the same "no such piece" as a stranger's.
            val pieceId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))

            val label = call.receive<PieceLabelDto>().label?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null && PieceLabels.length(label) > PieceLabels.MAX_LENGTH) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("label_too_long", "at most ${PieceLabels.MAX_LENGTH} characters"),
                )
                return@put
            }

            if (!store.renamePiece(userId, pieceId, label)) {
                // 404, never 403: a piece id is a client-made uuid, and telling "not
                // yours" from "none" would let anybody probe which ids exist.
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
                return@put
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Refuses a Kagua account outright.
 *
 * The two apps have entirely independent accounts, so a buyer reaching this endpoint is a
 * client bug rather than an attack, and 403 with a code it can act on beats a silent
 * no-op. It cannot change what the account *is* — that is `users.audience`, fixed at
 * registration — so the only thing stopping a buyer here is a workshop profile hanging off
 * an account nothing will ever read it for.
 */
private suspend fun ApplicationCall.requireFundi(auth: AuthStore, userId: String): Boolean {
    val user = auth.findUser(userId)
    if (user?.audience == Audience.FUNDI) return true
    log.warn("Non-fundi account {} tried to use a Fundi Bora endpoint", userId)
    respond(HttpStatusCode.Forbidden, ErrorResponse("not_a_fundi"))
    return false
}

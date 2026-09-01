package com.qualityverifier.server.routes

import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Tokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("com.qualityverifier.server.auth")

/** How long a phone can go without signing in again. */
private val REFRESH_LIFETIME: Duration = Duration.ofDays(60)

fun Route.authRoutes(store: AuthStore, accessTokens: AccessTokens) {

    route("/v1/auth") {

        post("/register") {
            val request = call.receive<RegisterRequest>()
            request.validate()?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", problem))
                return@post
            }

            val outcome = store.register(
                Registration(
                    inviteCode = request.inviteCode.trim(),
                    displayName = request.name.trim(),
                    accountType = request.accountType,
                    businessName = request.businessName?.trim(),
                    latitude = request.latitude,
                    longitude = request.longitude,
                    accuracyMetres = request.accuracyMetres,
                )
            )

            when (outcome) {
                // One answer for unknown, revoked and already-redeemed. Distinguishing
                // them would turn this endpoint into an oracle for testing guessed
                // codes, and a tester who mistypes theirs is going to ask us anyway.
                RegisterOutcome.InviteUnusable -> call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("invite_unusable", "That invite code cannot be used."),
                )

                is RegisterOutcome.Created -> {
                    log.info("Registered user {}", outcome.userId)
                    call.respond(
                        HttpStatusCode.Created,
                        issueTokens(store, accessTokens, outcome.userId, request.userAgent),
                    )
                }
            }
        }

        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            if (request.refreshToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
                return@post
            }

            val stored = store.findRefresh(request.refreshToken)
            if (stored == null || stored.revoked || stored.expiresAt.isBefore(Instant.now())) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_grant"))
                return@post
            }

            // A token that has already been spent coming back means either a replay or
            // a stolen token being used behind the holder's back. We cannot tell which,
            // and the safe reading of the ambiguity is that the account is compromised:
            // drop every live token and make them sign in again.
            if (stored.spent) {
                val revoked = store.revokeChain(stored.userId)
                log.warn(
                    "Spent refresh token replayed for user {}; revoked {} token(s)",
                    stored.userId, revoked,
                )
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_grant"))
                return@post
            }

            val user = store.findUser(stored.userId)
            if (user == null || user.disabled) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_grant"))
                return@post
            }

            call.respond(
                issueTokens(store, accessTokens, stored.userId, request.userAgent, replaces = stored.id)
            )
        }
    }

    authenticate("jwt") {
        get("/v1/me") {
            val userId = call.principal<JWTPrincipal>()?.subject
            val user = userId?.let { store.findUser(it) }
            if (user == null || user.disabled) {
                // A token that verifies for a user who no longer exists is the one case
                // where a valid signature is not enough.
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                return@get
            }
            call.respond(
                MeResponse(
                    userId = user.id,
                    name = user.displayName,
                    accountType = user.accountType,
                    businessName = user.businessName,
                )
            )
        }
    }
}

private suspend fun issueTokens(
    store: AuthStore,
    accessTokens: AccessTokens,
    userId: String,
    userAgent: String?,
    replaces: String? = null,
): TokenResponse {
    val refresh = Tokens.mint()
    store.issueRefresh(
        userId = userId,
        token = refresh,
        expiresAt = Instant.now().plus(REFRESH_LIFETIME),
        userAgent = userAgent?.take(200),
        replaces = replaces,
    )
    val access = accessTokens.issue(userId)
    return TokenResponse(
        accessToken = access.token,
        expiresIn = access.expiresInSeconds,
        refreshToken = refresh,
        userId = userId,
    )
}

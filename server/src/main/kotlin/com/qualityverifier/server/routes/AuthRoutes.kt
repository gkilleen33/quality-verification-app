package com.qualityverifier.server.routes

import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Passwords
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

/** How long a phone can go without signing in again. Sliding: each refresh renews it. */
private val REFRESH_LIFETIME: Duration = Duration.ofDays(60)

/**
 * Ten wrong passwords, then fifteen minutes. Generous enough that a customer who has
 * genuinely forgotten which of two passwords they used is not locked out, tight enough
 * that online guessing is hopeless — especially with Argon2 already making each attempt
 * cost about 50ms.
 */
private const val LOCKOUT_THRESHOLD = 10
private val LOCKOUT: Duration = Duration.ofMinutes(15)

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
                    phone = request.phone.trim(),
                    passwordHash = Passwords.hash(request.password),
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

                // Told plainly, unlike the invite. It reveals nothing an attacker could
                // not learn by attempting to sign in, and the alternative is a customer
                // who cannot work out why registering fails when they already have an
                // account.
                RegisterOutcome.PhoneTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        "phone_taken",
                        "That number already has an account. Sign in instead.",
                    ),
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

        post("/sign-in") {
            val request = call.receive<SignInRequest>()
            request.validate()?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", problem))
                return@post
            }

            val credentials = store.credentialsForPhone(request.phone.trim())

            // No account for that number: burn the same work a real verification costs
            // before answering. Otherwise "no such account" returns in microseconds and a
            // real one takes ~50ms, which enumerates who has an account regardless of how
            // generic the error message is.
            if (credentials?.passwordHash == null) {
                Passwords.burnEquivalentWork(request.password)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
                return@post
            }

            // Checked before the password, so a locked account cannot be used as an
            // oracle for guessing it.
            val lockedUntil = credentials.lockedUntil
            if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
                log.warn("Sign-in attempt on locked account {}", credentials.userId)
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("locked", "Too many attempts. Try again later."),
                )
                return@post
            }

            if (credentials.disabled || !Passwords.verify(request.password, credentials.passwordHash)) {
                if (!credentials.disabled) {
                    val failures = store.recordFailedSignIn(
                        credentials.userId, LOCKOUT, LOCKOUT_THRESHOLD,
                    )
                    if (failures >= LOCKOUT_THRESHOLD) {
                        log.warn("Locked account {} after {} failures", credentials.userId, failures)
                    }
                }
                // One answer for a wrong password and a disabled account.
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
                return@post
            }

            store.clearFailedSignIns(credentials.userId)
            log.info("Signed in user {}", credentials.userId)
            call.respond(issueTokens(store, accessTokens, credentials.userId, request.userAgent))
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
                    isTester = user.isTester,
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

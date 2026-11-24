package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.Terms
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.auth.Tokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.terms.ConsentKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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

fun Route.authRoutes(store: AuthStore, accessTokens: AccessTokens, terms: Terms? = null) {

    route("/v1/auth") {

        post("/register") {
            // Normalised before validation, so 0712… is checked as the +254712… it becomes.
            val request = call.receive<RegisterRequest>().let { it.copy(phone = normalisePhone(it.phone)) }
            request.validate()?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", problem))
                return@post
            }

            val outcome = store.register(
                Registration(
                    inviteCode = request.inviteCode.trim(),
                    phone = request.phone,
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
            // The same normalisation as register and V21, or a number stored in its
            // rewritten form would never match what the person types.
            val request = call.receive<SignInRequest>().let { it.copy(phone = normalisePhone(it.phone)) }
            request.validate()?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", problem))
                return@post
            }

            // One number may hold a Kagua and a Fundi Bora account (V38). The app that is
            // signing in chooses which one the password is checked against, by the X-App-Id
            // header every request from v0.4.0 on carries; it never creates an account or
            // chooses a prompt, which stays the invite code's (V16).
            val accounts = store.credentialsForPhone(request.phone)
            val audience = signInAudience(call.request.headers[AppVersionHeaders.APP])
            val credentials = when {
                audience != null -> accounts.firstOrNull { it.audience == audience }
                // A phone that does not say which app it is, as before V38: the number's
                // one account, whichever app it is for.
                // It costs two password checks whatever the number holds, as the two-account
                // case below does, so the time taken never says whether there are two.
                accounts.size <= 1 -> {
                    Passwords.burnEquivalentWork(request.password)
                    accounts.firstOrNull()
                }
                else -> {
                    call.refuseUnnamedAppWithTwoAccounts(store, accounts, request.password)
                    return@post
                }
            }

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
            // Open before agreeing (TermsGate.OPEN): this is where the phone learns it has
            // terms to agree to.
            val status = terms?.gate?.statusFor(user.id, user.audience)
            suspend fun fundiAgreed(kind: ConsentKind): Boolean =
                user.audience == Audience.FUNDI && terms?.gate?.agreed(user.id, kind) == true
            call.respond(
                MeResponse(
                    userId = user.id,
                    name = user.displayName,
                    accountType = user.accountType,
                    businessName = user.businessName,
                    isTester = user.isTester,
                    termsRequired = status?.required == true,
                    terms = status?.version?.let { version -> terms?.describe(user.audience, version) },
                    transferAgreed = status?.transferAgreed == true,
                    mpesaReadAgreed = fundiAgreed(ConsentKind.MPESA_READ),
                    mpesaSendAgreed = fundiAgreed(ConsentKind.MPESA_SEND),
                    phone = user.phone,
                )
            )
        }
    }
}

/**
 * Which account an app signs in to (V38): Kagua's buyer account, and the Fundi Bora account
 * for Fundi Bora and for PesaLog, which signs in with it (V33). Null for a request that does
 * not name a known app, which is how a phone older than v0.4.0 signs in.
 */
internal fun signInAudience(appHeader: String?): Audience? = when (AppId.fromId(appHeader)) {
    AppId.KAGUA -> Audience.BUYER
    AppId.FUNDI, AppId.TRACKER -> Audience.FUNDI
    null -> null
}

/**
 * Shown by a phone that does not name its app when its number holds two accounts. Sent as the
 * detail of `invalid_request`, because every release from v0.1.0 shows that detail as it is
 * (AuthClient.messageFor), and a phone this old knows no newer code.
 */
internal const val UPDATE_TO_SIGN_IN =
    "This number has a Kagua account and a Fundi Bora account. Update the app to sign in."

/**
 * A sign-in that does not name its app, for a number with a Kagua and a Fundi Bora account.
 *
 * Neither account is chosen: a guess would sign somebody in to the other app's account. The
 * password is checked first, and the work is padded to two checks on every unnamed sign-in, so
 * only somebody who knows one of the two passwords learns that the number holds two accounts. A wrong password is answered and counted as on any account,
 * against each account it was tried on, and a locked account is not tried at all, as the
 * single-account path does.
 */
private suspend fun ApplicationCall.refuseUnnamedAppWithTwoAccounts(
    store: AuthStore,
    accounts: List<Credentials>,
    password: String,
) {
    val now = Instant.now()
    val usable = accounts.filter { !it.disabled && it.passwordHash != null }
    val unlocked = usable.filter { it.lockedUntil?.isAfter(now) != true }
    if (usable.isNotEmpty() && unlocked.isEmpty()) {
        respond(
            HttpStatusCode.TooManyRequests,
            ErrorResponse("locked", "Too many attempts. Try again later."),
        )
        return
    }
    // Exactly two password checks' work, every account tried with no short cut, so a number
    // with two accounts takes as long as one with a single account (see the caller).
    val matched = unlocked.map { Passwords.verify(password, it.passwordHash!!) }.any { it }
    repeat(2 - unlocked.size) { Passwords.burnEquivalentWork(password) }
    if (matched) {
        log.info("Sign-in with no app named for a number with two accounts; asked to update")
        respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", UPDATE_TO_SIGN_IN))
        return
    }
    unlocked.forEach { store.recordFailedSignIn(it.userId, LOCKOUT, LOCKOUT_THRESHOLD) }
    respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_credentials"))
}

/**
 * A new refresh token and access token for [userId]. Internal rather than private because
 * a password change issues the calling phone a pair too (see syncRoutes), and two copies
 * of this would drift apart on lifetime.
 */
internal suspend fun issueTokens(
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

package com.qualityverifier.server.routes

import com.qualityverifier.server.admin.AdminSession
import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.api.ApiKeyStore
import com.qualityverifier.server.admin.apiKeysPage
import com.qualityverifier.server.admin.AdminStore
import com.qualityverifier.server.admin.Enrolment
import com.qualityverifier.server.admin.Overview
import com.qualityverifier.server.admin.Totp
import com.qualityverifier.server.admin.adminsPage
import com.qualityverifier.server.admin.assessmentsPage
import com.qualityverifier.server.admin.auditPage
import com.qualityverifier.server.admin.conversationPage
import com.qualityverifier.server.admin.invitesPage
import com.qualityverifier.server.admin.loginPage
import com.qualityverifier.server.admin.overviewPage
import com.qualityverifier.server.admin.twoFactorPage
import com.qualityverifier.server.admin.usersPage
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.blobs.BlobStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.http.Parameters
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

private val log = LoggerFactory.getLogger("com.qualityverifier.server.admin")


/**
 * Headers on every admin response.
 *
 * A route-scoped plugin rather than a line in each handler: there are twenty routes under
 * /admin and the one that forgot would be the one serving photographs.
 *
 * `no-store` because these pages hold customer photographs and a CSRF token. The default is
 * a browser that keeps them on disk and re-shows them from the back button after sign-out,
 * which is the one moment somebody expects them gone.
 *
 * The CSP is worth having precisely because this portal renders text a customer typed. There
 * is no JavaScript on any of these pages, so `script-src` resolving to 'none' via
 * `default-src` costs nothing and means an escaping mistake stays a rendering bug instead of
 * becoming account takeover. `style-src 'unsafe-inline'` is needed for the one inline
 * stylesheet, and inline CSS cannot exfiltrate a session cookie.
 */
private val AdminSecurityHeaders = createRouteScopedPlugin("AdminSecurityHeaders") {
    onCall { call ->
        val headers = call.response.headers
        headers.append("Cache-Control", "no-store, no-cache, must-revalidate")
        headers.append("Pragma", "no-cache")
        headers.append("X-Content-Type-Options", "nosniff")
        headers.append("X-Frame-Options", "DENY")
        // No referrer at all: a URL here can name a session id, and there is nowhere these
        // pages need to send one.
        headers.append("Referrer-Policy", "no-referrer")
        headers.append(
            "Content-Security-Policy",
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; " +
                "form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
        )
    }
}

/** Five attempts, then fifteen minutes. Tighter than the customer path: there are three of them. */
private const val MAX_FAILURES = 5
private val LOCKOUT = Duration.ofMinutes(15)

private const val PAGE_SIZE = 50

/**
 * The admin portal.
 *
 * Deliberately not built: an arbitrary SQL box. It is remote code execution and a bulk
 * exfiltration tool in one text field, and no amount of authentication in front changes
 * that. Ad-hoc queries are what psql over SSM is for, run by somebody who already has
 * server access.
 *
 * Everything under /admin except the two login forms requires a session that has passed
 * both factors and has not timed out. That check is one function, [requireAdmin], and every
 * route calls it as its first statement — a route that forgets is a route that serves
 * customer photographs to anybody.
 */
fun Route.adminRoutes(
    store: AdminStore,
    blobs: BlobStore,
    feedback: FeedbackStore,
    apiKeys: ApiKeyStore,
    /**
     * Whether the remembered-browser cookie is marked Secure. True everywhere real.
     *
     * Passed in rather than derived from the request scheme. nginx terminates TLS and
     * proxies to this process over plain http on loopback, and XForwardedHeaders is not
     * installed — clientIp() reads the header by hand — so `origin.scheme` here is "http"
     * in production. Deriving it would have shipped a cookie that grants a thirty-day
     * second-factor bypass without the Secure flag.
     */
    secureCookie: Boolean = true,
) {

    route("/admin") {

        install(AdminSecurityHeaders)

        // ------------------------------------------------------------- sign in

        get("/login") {
            if (call.sessions.get<AdminSession>()?.fullyAuthenticated(now()) == true) {
                return@get call.respondRedirect("/admin")
            }
            call.respondHtml { loginPage(null) }
        }

        post("/login") {
            val form = call.receiveParameters()
            val email = form["email"]?.trim().orEmpty()
            val password = form["password"].orEmpty()

            val credentials = store.credentialsFor(email)
            // The same answer whether the email is unknown, the password is wrong or the
            // account is disabled. Distinguishing them turns the form into a way to find
            // out who the admins are.
            val refusal = "Those details were not right."

            if (credentials == null) {
                // Still spend the time an Argon2id verification would take. Answering an
                // unknown email measurably faster is how a login form leaks its user list.
                Passwords.verify(password, DUMMY_HASH)
                log.info("Admin sign-in attempt for an unknown email")
                return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(refusal) }
            }
            if (credentials.disabled) {
                log.warn("Disabled admin {} tried to sign in", credentials.email)
                return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(refusal) }
            }
            if (credentials.lockedUntil?.isAfter(java.time.Instant.now()) == true) {
                log.warn("Locked admin {} tried to sign in", credentials.email)
                return@post call.respondHtml(HttpStatusCode.TooManyRequests) {
                    loginPage("Too many attempts. Try again in a few minutes.")
                }
            }
            if (!Passwords.verify(password, credentials.passwordHash)) {
                val failures = store.recordFailure(credentials.id, LOCKOUT, MAX_FAILURES)
                log.warn("Bad password for admin {} ({} failures)", credentials.email, failures)
                return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(refusal) }
            }
            if (credentials.totpSecret == null) {
                // Should not happen: the secret is generated when the account is created.
                log.error("Admin {} has no TOTP secret; refusing", credentials.email)
                return@post call.respondHtml(HttpStatusCode.InternalServerError) {
                    loginPage("This account is not set up. Ask another admin.")
                }
            }

            // A browser this admin asked to be remembered skips the code. Never skips
            // enrolment: an account that has not confirmed a secret has no second factor
            // to remember, and letting a cookie stand in for one would mean an account
            // that never enrols anything.
            val remembered = credentials.totpConfirmed && trustedDeviceFor(store, call, credentials.id)

            // Password proved. Nothing is readable yet unless a remembered browser
            // already settled the second factor.
            call.sessions.set(
                AdminSession(
                    adminId = credentials.id,
                    email = credentials.email,
                    secondFactorDone = remembered,
                    enrolling = !credentials.totpConfirmed,
                    signedInAt = now(),
                    lastSeenAt = now(),
                    csrfToken = AdminSession.newCsrfToken(),
                ),
            )
            if (remembered) {
                store.recordSignIn(credentials.id)
                store.audit(credentials.id, credentials.email, "sign-in", detail = "remembered browser", ip = call.clientIp())
                log.info("Admin {} signed in on a remembered browser", credentials.email)
                return@post call.respondRedirect("/admin")
            }
            call.respondRedirect("/admin/2fa")
        }

        get("/2fa") {
            val session = call.sessions.get<AdminSession>()
            if (session == null || session.expired(now())) {
                return@get call.respondRedirect("/admin/login")
            }
            if (session.secondFactorDone && !session.enrolling) {
                return@get call.respondRedirect("/admin")
            }
            val enrolment = if (session.enrolling) enrolmentFor(store, session.email) else null
            call.respondHtml { twoFactorPage(null, enrolment) }
        }

        post("/2fa") {
            val session = call.sessions.get<AdminSession>()
            if (session == null || session.expired(now())) {
                return@post call.respondRedirect("/admin/login")
            }
            val form = call.receiveParameters()
            val code = form["code"].orEmpty()
            val credentials = store.credentialsFor(session.email)
            val secret = credentials?.totpSecret
            if (credentials == null || secret == null || credentials.disabled) {
                call.sessions.clear<AdminSession>()
                return@post call.respondRedirect("/admin/login")
            }
            if (!Totp.verify(secret, code)) {
                // Counted against the same lockout as a bad password: otherwise the second
                // factor is a six-digit number with unlimited guesses.
                val failures = store.recordFailure(credentials.id, LOCKOUT, MAX_FAILURES)
                log.warn("Bad 2FA code for admin {} ({} failures)", session.email, failures)
                val enrolment = if (session.enrolling) enrolmentFor(store, session.email) else null
                return@post call.respondHtml(HttpStatusCode.Unauthorized) {
                    twoFactorPage("That code was not right.", enrolment)
                }
            }

            if (session.enrolling) store.confirmTotp(credentials.id)
            store.recordSignIn(credentials.id)
            if (form["remember"] != null) {
                rememberDevice(store, call, credentials.id, credentials.email, secureCookie)
            }
            call.sessions.set(
                session.copy(
                    secondFactorDone = true,
                    enrolling = false,
                    lastSeenAt = now(),
                    // A new token now the session has changed privilege. Cheap, and it
                    // means a token seen before the second factor cannot be replayed after.
                    csrfToken = AdminSession.newCsrfToken(),
                ),
            )
            store.audit(credentials.id, credentials.email, "sign-in", ip = call.clientIp())
            call.respondRedirect("/admin")
        }

        post("/logout") {
            val session = call.sessions.get<AdminSession>()
            // CSRF-checked: a forced logout is only a nuisance, but the check costs nothing
            // and a form without one is a habit worth not forming.
            if (session != null && session.csrfMatches(call.receiveParameters()["csrf"])) {
                store.audit(session.adminId, session.email, "sign-out", ip = call.clientIp())
            }
            call.sessions.clear<AdminSession>()
            call.respondRedirect("/admin/login")
        }

        // ------------------------------------------------------------- pages

        get("") {
            val session = requireAdmin() ?: return@get
            val overview = store.overview().copy(recentAudit = store.auditTrail(10, 0).items)
            call.respondHtml { overviewPage(session, overview) }
        }

        get("/users") {
            val session = requireAdmin() ?: return@get
            val offset = call.offset()
            val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
            val page = store.users(PAGE_SIZE, offset, search)
            if (search != null) {
                store.audit(session.adminId, session.email, "search-users", detail = search, ip = call.clientIp())
            }
            call.respondHtml { usersPage(session, page, offset, PAGE_SIZE, search) }
        }

        get("/assessments") {
            val session = requireAdmin() ?: return@get
            val offset = call.offset()
            val user = call.request.queryParameters["user"]
            val item = call.request.queryParameters["item"]
            val testersOnly = call.request.queryParameters["testers"] == "1"
            val page = store.sessions(PAGE_SIZE, offset, user, item, testersOnly)
            call.respondHtml { assessmentsPage(session, page, offset, PAGE_SIZE, testersOnly) }
        }

        get("/assessments/{id}") {
            val session = requireAdmin() ?: return@get
            val id = call.parameters["id"].orEmpty()
            val header = store.sessionHeader(id)
                ?: return@get call.respondText("No such assessment.", status = HttpStatusCode.NotFound)
            // Audited here rather than per photo. Opening the page is the act worth
            // recording; one row per thumbnail would bury it in its own noise.
            store.audit(session.adminId, session.email, "read-assessment", target = id, ip = call.clientIp())
            val turns = store.conversation(id)
            // Fetched with the page rather than behind a second request: it is a single row
            // keyed on the session, and a button that needed a round trip to say "there
            // isn't one" would be worse than a page that already knows.
            val critique = feedback.feedbackFor(id)
            call.respondHtml { conversationPage(session, header, turns, critique) }
        }

        get("/photos/{sha}") {
            requireAdmin() ?: return@get
            val sha = call.parameters["sha"].orEmpty()
            if (!BlobStore.isValidHash(sha)) {
                return@get call.respond(HttpStatusCode.BadRequest)
            }
            // Must be a photo the system actually knows about, not any file whose name
            // happens to be 64 hex characters.
            if (!store.blobExists(sha)) return@get call.respond(HttpStatusCode.NotFound)
            val bytes = blobs.read(sha) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }

        get("/audit") {
            val session = requireAdmin() ?: return@get
            val offset = call.offset()
            val trail = store.auditTrail(PAGE_SIZE, offset)
            call.respondHtml { auditPage(session, trail, offset, PAGE_SIZE) }
        }

        // ------------------------------------------------------------- invites

        get("/invites") {
            val session = requireAdmin() ?: return@get
            val invites = store.invites()
            call.respondHtml { invitesPage(session, invites, null) }
        }

        post("/invites") {
            val (session, form) = requireCsrf(store) ?: return@post
            val label = form["label"]?.trim()?.takeIf { it.isNotEmpty() }
            val code = newInviteCode()
            val grantsTester = form["tester"] != null
            store.createInvite(code, label, grantsTester)
            store.audit(
                session.adminId, session.email, "create-invite", target = code,
                // Recorded in the audit line rather than only in the row: who was made an
                // evaluator, and by whom, is the part somebody would come back asking about.
                detail = listOfNotNull(label, "evaluator".takeIf { grantsTester }).joinToString(" — ")
                    .takeIf { it.isNotEmpty() },
                ip = call.clientIp(),
            )
            val invites = store.invites()
            val notice = if (grantsTester) "Created $code for an evaluator." else "Created $code."
            call.respondHtml { invitesPage(session, invites, notice) }
        }

        post("/invites/{code}/revoke") {
            val (session, _) = requireCsrf(store) ?: return@post
            val code = call.parameters["code"].orEmpty()
            val revoked = store.revokeInvite(code)
            if (revoked) {
                store.audit(session.adminId, session.email, "revoke-invite", target = code, ip = call.clientIp())
            }
            val invites = store.invites()
            call.respondHtml {
                invitesPage(session, invites, if (revoked) "Revoked $code." else "Nothing to revoke.")
            }
        }

        // ------------------------------------------------------------- admins

        get("/admins") {
            val session = requireAdmin() ?: return@get
            respondAdmins(session, store, null, null)
        }

        post("/admins") {
            val (session, form) = requireCsrf(store) ?: return@post
            val email = form["email"]?.trim().orEmpty()
            val name = form["name"]?.trim().orEmpty()
            val password = form["password"].orEmpty()

            val problem = when {
                email.isBlank() || !email.contains("@") -> "That does not look like an email address."
                name.isBlank() -> "A name is needed."
                password.length < 12 ->
                    "The temporary password needs at least 12 characters — this account can " +
                        "read every customer's photographs."
                else -> null
            }
            if (problem != null) {
                return@post respondAdmins(session, store, problem, null, status = HttpStatusCode.BadRequest)
            }

            val created = store.createAdmin(email, name, Passwords.hash(password), session.adminId)
            if (created == null) {
                return@post respondAdmins(session, store, "There is already an admin with that email.", null, status = HttpStatusCode.Conflict)
            }
            val (_, secret) = created
            store.audit(session.adminId, session.email, "create-admin", target = email, ip = call.clientIp())
            respondAdmins(
                session,
                store,
                "Added $email. They set up their authenticator when they first sign in.",
                Enrolment(secret, Totp.provisioningUri(secret, email)),
            )
        }

        post("/admins/{id}/disable") {
            val (session, _) = requireCsrf(store) ?: return@post
            val id = call.parameters["id"].orEmpty()
            if (id == session.adminId) {
                return@post respondAdmins(session, store, "You cannot disable yourself.", null, status = HttpStatusCode.BadRequest)
            }
            // Refuse to leave nobody able to sign in. Recovering from that needs a script
            // over SSM, which is exactly the situation this portal exists to avoid.
            if (store.activeAdminCount() <= 1) {
                return@post respondAdmins(session, store, "That is the last admin who can sign in.", null, status = HttpStatusCode.BadRequest)
            }
            store.setDisabled(id, disabled = true)
            store.audit(session.adminId, session.email, "disable-admin", target = id, ip = call.clientIp())
            respondAdmins(session, store, "Disabled.", null)
        }

        post("/admins/{id}/enable") {
            val (session, _) = requireCsrf(store) ?: return@post
            val id = call.parameters["id"].orEmpty()
            store.setDisabled(id, disabled = false)
            store.audit(session.adminId, session.email, "enable-admin", target = id, ip = call.clientIp())
            respondAdmins(session, store, "Enabled.", null)
        }

        /**
         * Resets another admin's second factor, for a lost authenticator.
         *
         * Three rules, each covering a different way this could be the attack rather than
         * the remedy:
         *
         * - **Never your own.** Somebody holding a borrowed session would otherwise move
         *   the second factor onto their own phone without ever knowing the password.
         * - **The password again**, even though the caller is already signed in. This is
         *   the one action in the portal that hands out a working credential.
         * - **Forget their remembered browsers.** Otherwise the person who lost their
         *   authenticator keeps signing in on a remembered machine and never enrols the new
         *   secret, leaving an account whose second factor exists only in the database.
         */
        post("/admins/{id}/reset-2fa") {
            val (session, form) = requireCsrf(store) ?: return@post
            val id = call.parameters["id"].orEmpty()

            if (id == session.adminId) {
                log.warn("Admin {} tried to reset their own 2FA", session.email)
                return@post respondAdmins(
                    session, store,
                    "You cannot reset your own 2FA. Ask another admin, or use the box.",
                    null, status = HttpStatusCode.Forbidden,
                )
            }
            val actor = store.credentialsFor(session.email)
            if (actor == null || !Passwords.verify(form["password"].orEmpty(), actor.passwordHash)) {
                log.warn("Failed password confirmation on a 2FA reset by {}", session.email)
                return@post respondAdmins(
                    session, store, "That password was not right.",
                    null, status = HttpStatusCode.Unauthorized,
                )
            }

            val target = store.admins().firstOrNull { it.id == id }
            val secret = if (target != null) store.resetTotp(id) else null
            if (target == null || secret == null) {
                return@post respondAdmins(
                    session, store, "That account cannot be reset.",
                    null, status = HttpStatusCode.NotFound,
                )
            }
            val forgotten = store.revokeTrustedDevices(id)
            store.audit(
                session.adminId, session.email, "reset-2fa", target = target.email,
                detail = "forgot $forgotten remembered browser(s)", ip = call.clientIp(),
            )
            log.warn("Admin {} reset 2FA for {}", session.email, target.email)
            respondAdmins(
                session, store,
                "New secret for ${target.email}. They keep their existing password.",
                Enrolment(secret, Totp.provisioningUri(secret, target.email)),
            )
        }

        /** Forgets every remembered browser for the signed-in admin. */
        post("/devices/revoke") {
            val (session, _) = requireCsrf(store) ?: return@post
            val forgotten = store.revokeTrustedDevices(session.adminId)
            call.clearDeviceCookie()
            store.audit(
                session.adminId, session.email, "revoke-devices",
                detail = "$forgotten browser(s)", ip = call.clientIp(),
            )
            respondAdmins(session, store, "Forgot $forgotten remembered browser(s).", null)
        }

        /**
         * Marks an account as one of our evaluators, or stops it being one.
         *
         * Needed as well as the invite checkbox: somebody hired after they registered
         * should not need a second account, and a code handed out with the wrong box
         * ticked would otherwise be unfixable.
         *
         * Audited both ways. The flag decides whose assessments count as pilot findings,
         * so a silent change to it would quietly alter a research result.
         */
        post("/users/{id}/tester") {
            val (session, form) = requireCsrf(store) ?: return@post
            val id = call.parameters["id"].orEmpty()
            val makeTester = form["tester"] == "1"
            if (!isUuid(id) || !store.setTester(id, makeTester)) {
                return@post respondUsers(
                    session, store, "That account could not be updated.",
                    status = HttpStatusCode.NotFound,
                )
            }
            store.audit(
                session.adminId, session.email,
                if (makeTester) "mark-tester" else "unmark-tester",
                target = id, ip = call.clientIp(),
            )
            respondUsers(
                session, store,
                if (makeTester) "Marked as an evaluator." else "No longer an evaluator.",
            )
        }

        // ------------------------------------------------------- API keys

        get("/api-keys") {
            val session = requireAdmin() ?: return@get
            // Fetched before respondHtml: the HTML builder is not a coroutine body, so a
            // suspend call inside it does not compile.
            val keys = apiKeys.keys()
            call.respondHtml { apiKeysPage(session, keys, null, null) }
        }

        /**
         * Mints a key and shows it once.
         *
         * Shown once because only its SHA-256 is stored. A key that could be re-read from
         * the portal would mean a stolen admin session hands over the whole corpus without
         * leaving a "key created" line in the audit log.
         */
        post("/api-keys") {
            val (session, form) = requireCsrf(store) ?: return@post
            val label = form["label"]?.trim()?.takeIf { it.isNotEmpty() }
            if (label == null) {
                val keys = apiKeys.keys()
                return@post call.respondHtml(HttpStatusCode.BadRequest) {
                    apiKeysPage(session, keys, "Give the key a label first.", null)
                }
            }
            val created = apiKeys.create(label, session.adminId)
            store.audit(
                session.adminId, session.email, "create-api-key",
                target = created.prefix, detail = label, ip = call.clientIp(),
            )
            log.warn("Admin {} created API key {} ({})", session.email, created.prefix, label)
            val keys = apiKeys.keys()
            call.respondHtml { apiKeysPage(session, keys, null, created.secret) }
        }

        post("/api-keys/{id}/revoke") {
            val (session, _) = requireCsrf(store) ?: return@post
            val id = call.parameters["id"].orEmpty()
            if (!isUuid(id) || !apiKeys.revoke(id)) {
                val keys = apiKeys.keys()
                return@post call.respondHtml(HttpStatusCode.NotFound) {
                    apiKeysPage(session, keys, "That key could not be revoked.", null)
                }
            }
            store.audit(session.adminId, session.email, "revoke-api-key", target = id, ip = call.clientIp())
            val keys = apiKeys.keys()
            call.respondHtml { apiKeysPage(session, keys, "Revoked.", null) }
        }

        post("/password") {
            val (session, form) = requireCsrf(store) ?: return@post
            val current = form["current"].orEmpty()
            val next = form["next"].orEmpty()
            val credentials = store.credentialsFor(session.email)
            if (credentials == null || !Passwords.verify(current, credentials.passwordHash)) {
                return@post respondAdmins(session, store, "That current password was not right.", null, status = HttpStatusCode.Unauthorized)
            }
            if (next.length < 12) {
                return@post respondAdmins(session, store, "The new password needs at least 12 characters.", null, status = HttpStatusCode.BadRequest)
            }
            store.setPasswordHash(credentials.id, Passwords.hash(next))
            // A password change is usually a response to somebody else having had access,
            // and a remembered browser needs only the password. Leaving them would mean
            // the change did nothing on the machine that mattered.
            val forgotten = store.revokeTrustedDevices(credentials.id)
            call.clearDeviceCookie()
            store.audit(session.adminId, session.email, "change-own-password", detail = "forgot \$forgotten remembered browser(s)", ip = call.clientIp())
            respondAdmins(session, store, "Password changed. Remembered browsers were forgotten.", null)
        }

        // ------------------------------------------------------------- export

        get("/export/assessment/{id}") {
            val session = requireAdmin() ?: return@get
            val id = call.parameters["id"].orEmpty()
            val withPhotos = call.request.queryParameters["photos"] == "true"
            val header = store.sessionHeader(id)
                ?: return@get call.respondText("No such assessment.", status = HttpStatusCode.NotFound)
            val turns = store.conversation(id)

            val payload = buildJsonObject {
                put("assessment_id", JsonPrimitive(header.id))
                put("item_type_id", JsonPrimitive(header.itemTypeId))
                put("created_at", JsonPrimitive(header.createdAt.toString()))
                put("verdict_level_id", JsonPrimitive(header.verdictLevelId))
                put("deleted_by_customer", JsonPrimitive(header.clientDeleted))
                put("turns", turnsJson(turns, withPhotos, blobs))
            }
            store.audit(
                session.adminId, session.email, "export-assessment", target = id,
                detail = if (withPhotos) "with photos" else "without photos", ip = call.clientIp(),
            )
            call.respondText(
                Json.encodeToString(JsonObject.serializer(), payload),
                ContentType.Application.Json,
            )
        }
    }
}

private fun turnsJson(
    turns: List<com.qualityverifier.server.admin.AdminMessageRow>,
    withPhotos: Boolean,
    blobs: BlobStore,
): JsonArray = buildJsonArray {
    turns.forEach { turn ->
        add(
            buildJsonObject {
                put("role", JsonPrimitive(turn.role))
                put("text", JsonPrimitive(turn.text))
                put("created_at", JsonPrimitive(turn.createdAt.toString()))
                put("photo_hashes", buildJsonArray { turn.photoHashes.forEach { add(JsonPrimitive(it)) } })
                if (withPhotos) {
                    // Base64 inline rather than a zip. An export is read by a script far
                    // more often than by a person, and one self-contained file beats an
                    // archive whose manifest has to be matched back up by hand.
                    put(
                        "photos_base64",
                        buildJsonArray {
                            turn.photoHashes.forEach { sha ->
                                blobs.read(sha)?.let {
                                    add(JsonPrimitive(Base64.getEncoder().encodeToString(it)))
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}


/**
 * Renders the admins page.
 *
 * A helper because the page is the answer to eight different outcomes — created, refused,
 * disabled, password changed — and the HTML builder is not a suspend context, so the list
 * has to be fetched before it opens. Eight hoists would be eight chances to fetch it twice.
 */
private suspend fun RoutingContext.respondAdmins(
    session: AdminSession,
    store: AdminStore,
    notice: String?,
    secret: Enrolment? = null,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val admins = store.admins()
    val devices = store.trustedDevices(session.adminId)
    call.respondHtml(status) { adminsPage(session, admins, notice, secret, devices) }
}

/**
 * The gate on every page that shows customer data.
 *
 * Returns null having already answered, so a caller writes
 * `val session = requireAdmin() ?: return@get` and cannot accidentally carry on. Also
 * rolls the session forward, which is what makes the idle timeout an idle timeout rather
 * than a fixed one.
 */
/**
 * Re-renders the user list with a notice. Mirrors respondAdmins, for the same reason: the
 * page needs a fresh read after a change, and a redirect would lose the message.
 */
private suspend fun RoutingContext.respondUsers(
    session: AdminSession,
    store: AdminStore,
    notice: String?,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val page = store.users(PAGE_SIZE, 0, null)
    call.respondHtml(status) { usersPage(session, page, 0, PAGE_SIZE, null, notice) }
}

private suspend fun RoutingContext.requireAdmin(): AdminSession? {
    val session = call.sessions.get<AdminSession>()
    if (session == null || !session.fullyAuthenticated(now())) {
        if (session != null) call.sessions.clear<AdminSession>()
        call.respondRedirect("/admin/login")
        return null
    }
    call.sessions.set(session.copy(lastSeenAt = now()))
    return session
}

/**
 * [requireAdmin] plus the CSRF token.
 *
 * Returns the form as well as the session, because the body can only be read once — Ktor
 * throws RequestAlreadyConsumedException on a second receiveParameters, so a route that
 * checked the token here and then re-read the form would 500 on every submission. Handing
 * the parsed form back is what stops that being a trap for the next route added.
 */
private suspend fun RoutingContext.requireCsrf(store: AdminStore): Pair<AdminSession, Parameters>? {
    val session = requireAdmin() ?: return null
    val form = call.receiveParameters()
    val supplied = form["csrf"]
    if (!session.csrfMatches(supplied)) {
        log.warn("CSRF check failed for admin {} on {}", session.email, call.request.local.uri)
        store.audit(
            session.adminId, session.email, "csrf-rejected",
            target = call.request.local.uri, ip = call.clientIp(),
        )
        call.respond(HttpStatusCode.Forbidden)
        return null
    }
    return session to form
}

private fun io.ktor.server.application.ApplicationCall.offset(): Int =
    request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

/**
 * The client's address for the audit log.
 *
 * X-Forwarded-For, because nginx is the only thing that reaches this process and the
 * direct peer is therefore always 127.0.0.1. Only the first hop is taken and only for a
 * log line — nothing is authorised on the strength of it, which is what makes trusting a
 * header acceptable here.
 */
private fun io.ktor.server.application.ApplicationCall.clientIp(): String? =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
        ?: request.local.remoteHost

private fun now() = System.currentTimeMillis()

/**
 * The remembered-browser cookie.
 *
 * Not a Ktor session: there is nothing to sign, because the value carries no claims. It is
 * 32 bytes of CSPRNG output whose only meaning is a row in admin_trusted_devices, so a
 * forged one is a lookup miss rather than something to validate. Only the SHA-256 is
 * stored, so a database read does not yield a working cookie.
 */
private const val DEVICE_COOKIE = "kagua_admin_device"

/** Thirty days, matching what the checkbox promises. */
private val TRUST_DURATION: Duration = Duration.ofDays(30)

private val deviceRandom = java.security.SecureRandom()

private fun hashToken(token: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * True when this request carries a live remembered-browser cookie for [adminId].
 *
 * The row is looked up by hash and then checked to belong to this admin. Both matter: the
 * hash proves the cookie is one we issued, and the ownership check stops one admin's cookie
 * standing in for another's second factor.
 */
private suspend fun trustedDeviceFor(
    store: AdminStore,
    call: io.ktor.server.application.ApplicationCall,
    adminId: String,
): Boolean {
    val token = call.request.cookies[DEVICE_COOKIE] ?: return false
    val device = store.trustedDevice(hashToken(token)) ?: return false
    if (device.adminId != adminId) return false
    store.touchTrustedDevice(device.id)
    return true
}

private suspend fun rememberDevice(
    store: AdminStore,
    call: io.ktor.server.application.ApplicationCall,
    adminId: String,
    email: String,
    secureCookie: Boolean,
) {
    val token = ByteArray(32).also(deviceRandom::nextBytes)
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val expires = java.time.Instant.now().plus(TRUST_DURATION)
    // The label is only ever displayed. Truncated because a user agent is attacker-supplied
    // and unbounded, and this one goes in a page and a log line.
    val label = call.request.headers["User-Agent"]?.take(120)
    store.trustDevice(adminId, hashToken(token), label, expires)
    call.response.cookies.append(
        io.ktor.http.Cookie(
            name = DEVICE_COOKIE,
            value = token,
            path = "/admin",
            httpOnly = true,
            secure = secureCookie,
            maxAge = TRUST_DURATION.seconds.toInt(),
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
    store.audit(adminId, email, "device-remembered", detail = label, ip = call.clientIp())
}

private fun io.ktor.server.application.ApplicationCall.clearDeviceCookie() {
    response.cookies.append(
        io.ktor.http.Cookie(name = DEVICE_COOKIE, value = "", path = "/admin", maxAge = 0),
    )
}

private suspend fun enrolmentFor(store: AdminStore, email: String): Enrolment? {
    val secret = store.credentialsFor(email)?.totpSecret ?: return null
    return Enrolment(secret, Totp.provisioningUri(secret, email))
}

private val inviteRandom = SecureRandom()

/**
 * A code somebody can read down a phone line.
 *
 * No vowels, no 0/O or 1/I/L: these are dictated to testers, and a code that turns into a
 * support call has failed at its one job.
 */
private fun newInviteCode(): String {
    val alphabet = "BCDFGHJKMNPQRSTVWXYZ23456789"
    return (1..8).map { alphabet[inviteRandom.nextInt(alphabet.length)] }
        .joinToString("")
        .chunked(4)
        .joinToString("-")
}

/**
 * A real Argon2id hash of a value nobody knows, used to spend the same time verifying an
 * unknown email as a known one.
 */
private val DUMMY_HASH: String by lazy {
    Passwords.hash(Base64.getEncoder().encodeToString(ByteArray(32).also(inviteRandom::nextBytes)))
}

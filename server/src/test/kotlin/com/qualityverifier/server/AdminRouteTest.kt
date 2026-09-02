package com.qualityverifier.server

import com.qualityverifier.server.admin.AdminCredentials
import com.qualityverifier.server.admin.AdminMessageRow
import com.qualityverifier.server.admin.AdminRow
import com.qualityverifier.server.admin.AdminSessionRow
import com.qualityverifier.server.admin.AdminStore
import com.qualityverifier.server.admin.AuditRow
import com.qualityverifier.server.admin.Base32
import com.qualityverifier.server.admin.InviteRow
import com.qualityverifier.server.admin.Overview
import com.qualityverifier.server.admin.Page
import com.qualityverifier.server.admin.Totp
import com.qualityverifier.server.admin.TrustedDevice
import com.qualityverifier.server.admin.UserRow
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.blobs.BlobStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/**
 * The admin portal's gates.
 *
 * This portal can read every customer's conversation and open their photographs, so the
 * tests worth writing are the refusals: no session, half a session, a session that skipped
 * the second factor, a form without a CSRF token. Each is one line away from a page that
 * hands workshop photographs to anybody who asks.
 */
class AdminRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secret = Base32.encode("12345678901234567890".toByteArray())

    // ------------------------------------------------------------------ refusals

    @Test
    fun `every page redirects to the login form without a session`() = testApplication {
        val app = withAdmin(FakeAdminStore())

        for (path in listOf(
            "/admin", "/admin/users", "/admin/assessments", "/admin/invites",
            "/admin/admins", "/admin/audit",
            "/admin/assessments/2ff77920-3928-46e5-8a77-5e16c1e901c6",
            "/admin/photos/${"a".repeat(64)}",
            "/admin/export/assessment/2ff77920-3928-46e5-8a77-5e16c1e901c6",
        )) {
            val response = app.get(path)
            assertEquals("for $path", HttpStatusCode.Found, response.status)
            assertEquals("for $path", "/admin/login", response.headers["Location"])
        }
    }

    @Test
    fun `a password alone reaches no customer data`() = testApplication {
        // The state between the two forms. If /admin served a page here, the second factor
        // would be decoration.
        val store = FakeAdminStore()
        val app = withAdmin(store)

        app.signInPassword()

        val response = app.get("/admin")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/admin/login", response.headers["Location"])
    }

    @Test
    fun `the wrong code leaves the session unusable`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signInPassword()

        val refused = app.submitForm("/admin/2fa", parameters { append("code", "000000") })

        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("/admin/login", app.get("/admin").headers["Location"])
        // Counted against the lockout: otherwise the second factor is six digits with
        // unlimited guesses.
        assertEquals(1, store.failures)
    }

    @Test
    fun `a bad password and an unknown email are answered the same way`() = testApplication {
        // Any difference here turns the form into a way to find out who the admins are.
        val app = withAdmin(FakeAdminStore())

        val unknown = app.submitForm(
            "/admin/login",
            parameters { append("email", "nobody@example.com"); append("password", "whatever12345") },
        )
        val wrong = app.submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", "not-the-password") },
        )

        assertEquals(unknown.status, wrong.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(unknown.bodyAsText(), wrong.bodyAsText())
    }

    @Test
    fun `a disabled admin cannot sign in even with the right password`() = testApplication {
        val app = withAdmin(FakeAdminStore(disabled = true))

        val response = app.submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", PASSWORD) },
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a locked admin is told to wait rather than let through`() = testApplication {
        val app = withAdmin(FakeAdminStore(lockedUntil = Instant.now().plusSeconds(600)))

        val response = app.submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", PASSWORD) },
        )

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `both factors together open the portal`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)

        app.signIn()

        val response = app.get("/admin")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Overview"))
        assertEquals("the sign-in is recorded", 1, store.signIns)
        assertTrue(store.audits.any { it.action == "sign-in" })
    }

    @Test
    fun `opening a conversation is written to the audit log`() = testApplication {
        // The control that makes misuse detectable rather than theoretical. Without it,
        // "we would have noticed" is the whole of the protection.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val response = app.get("/admin/assessments/$SESSION_ID")

        assertEquals(HttpStatusCode.OK, response.status)
        val entry = store.audits.firstOrNull { it.action == "read-assessment" }
        assertNotNull("opening a conversation must be recorded", entry)
        assertEquals(SESSION_ID, entry!!.target)
    }

    @Test
    fun `the conversation shows the photos inline`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val body = app.get("/admin/assessments/$SESSION_ID").bodyAsText()

        // Visible without a click-to-reveal: judging whether an assessment was accurate
        // means seeing what the assistant saw.
        assertTrue(body, body.contains("/admin/photos/$PHOTO_SHA"))
        assertTrue(body, body.contains("<img"))
    }

    @Test
    fun `customer text is escaped, not rendered`() = testApplication {
        // Chat text is whatever somebody typed. Rendered raw on a page that also holds a
        // CSRF token, a script tag would be stored XSS into account takeover.
        val store = FakeAdminStore(
            conversation = listOf(
                AdminMessageRow(
                    role = "USER",
                    text = "<script>alert('x')</script> and <img src=x onerror=y>",
                    createdAt = Instant.now(),
                    photoHashes = emptyList(),
                ),
            ),
        )
        val app = withAdmin(store)
        app.signIn()

        val body = app.get("/admin/assessments/$SESSION_ID").bodyAsText()

        assertFalse("a script tag must not survive", body.contains("<script>alert"))
        assertTrue("it should be visible as text", body.contains("&lt;script&gt;"))
    }

    @Test
    fun `admin responses are not cached and run no scripts`() = testApplication {
        // The back button after sign-out is the one moment somebody expects customer
        // photographs to be gone, and it is a browser cache decision rather than ours.
        val app = withAdmin(FakeAdminStore())
        app.signIn()

        for (path in listOf("/admin", "/admin/assessments/$SESSION_ID", "/admin/login")) {
            val headers = app.get(path).headers
            assertTrue("$path must not be stored", headers["Cache-Control"]!!.contains("no-store"))
            assertEquals("$path", "no-referrer", headers["Referrer-Policy"])
            assertEquals("$path", "nosniff", headers["X-Content-Type-Options"])
            val csp = headers["Content-Security-Policy"]!!
            // No JavaScript anywhere on these pages, so an escaping mistake should stay a
            // rendering bug rather than becoming account takeover.
            assertTrue("$path: $csp", csp.contains("default-src 'none'"))
            assertTrue("$path: $csp", csp.contains("form-action 'self'"))
            assertTrue("$path: $csp", csp.contains("frame-ancestors 'none'"))
        }
    }

    // ------------------------------------------------- remembered browsers

    @Test
    fun `a remembered browser skips the code but still needs the password`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        // First sign-in, asking to be remembered. The client keeps the cookie.
        app.signInPassword()
        app.submitForm(
            "/admin/2fa",
            parameters { append("code", currentCode()); append("remember", "on") },
        )
        assertEquals("the browser should have been remembered", 1, store.devicesTrusted)

        // Sign in again: password only, no code submitted.
        app.signOut()
        app.signInPassword()

        // Straight in, without visiting /admin/2fa.
        assertEquals(HttpStatusCode.OK, app.get("/admin/invites").status)
        assertTrue(
            "the sign-in should be recorded as using a remembered browser",
            store.audits.any { it.action == "sign-in" && it.detail == "remembered browser" },
        )
    }

    @Test
    fun `a bad password is still refused on a remembered browser`() = testApplication {
        // The cookie replaces the second factor, not the first.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signInPassword()
        app.submitForm("/admin/2fa", parameters { append("code", currentCode()); append("remember", "on") })
        app.signOut()

        val response = app.submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", "wrong") },
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(HttpStatusCode.Found, app.get("/admin/invites").status)
    }

    @Test
    fun `one admin's remembered browser is not another admin's second factor`() = testApplication {
        // The cookie is looked up by hash and then checked against the account signing in.
        // Without that check, any issued cookie would satisfy anybody's second factor.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signInPassword()
        app.submitForm("/admin/2fa", parameters { append("code", currentCode()); append("remember", "on") })
        app.signOut()
        // Re-point the stored device at somebody else, leaving the browser's cookie intact.
        val hash = store.trusted.keys.single()
        store.trusted[hash] = OTHER_ADMIN_ID

        app.signInPassword()

        // Half a session only: the code is still required.
        assertEquals(HttpStatusCode.Found, app.get("/admin/invites").status)
    }

    @Test
    fun `changing a password forgets remembered browsers`() = testApplication {
        // A password change is usually a response to somebody else having had access, and a
        // remembered browser needs only the password.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()
        val csrf = app.csrfToken()

        app.submitForm(
            "/admin/password",
            parameters {
                append("csrf", csrf)
                append("current", PASSWORD)
                append("next", "a-brand-new-long-password")
            },
        )

        assertTrue(ADMIN_ID in store.devicesRevokedFor)
    }

    @Test
    fun `an admin can forget their own remembered browsers`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val csrf = app.csrfToken()
        app.submitForm("/admin/devices/revoke", parameters { append("csrf", csrf) })

        assertTrue(ADMIN_ID in store.devicesRevokedFor)
        assertTrue(store.audits.any { it.action == "revoke-devices" })
    }

    @Test
    fun `the remembered-browser cookie is HttpOnly, SameSite and Secure by default`() = testApplication {
        // Secure comes from an explicit flag, not from the request scheme: nginx proxies to
        // this process over plain http, so deriving it would ship a cookie granting a
        // thirty-day second-factor bypass that a browser would send in clear.
        val store = FakeAdminStore()
        application {
            module(
                version = "test",
                database = null,
                // No secureCookie override — production's default is what is under test.
                admin = Admin(store, BlobStore(folder.newFolder()), "a-signing-key-long-enough-to-be-real"),
            )
        }
        val app = createClient { followRedirects = false }

        // No cookie jar: a Secure cookie is not returned over http, so the session is
        // carried by hand. Without this the flow never reaches the device cookie and the
        // test passes by checking nothing — which is how it read on the first attempt.
        val login = app.submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", PASSWORD) },
        )
        val sessionCookie = login.headers.getAll("Set-Cookie").orEmpty()
            .first { it.startsWith("kagua_admin=") }
            .substringBefore(';')

        val response = app.submitForm(
            "/admin/2fa",
            parameters { append("code", currentCode()); append("remember", "on") },
        ) {
            header(HttpHeaders.Cookie, sessionCookie)
        }

        assertEquals("the browser should have been remembered", 1, store.devicesTrusted)
        val setCookie = response.headers.getAll("Set-Cookie").orEmpty()
            .first { it.startsWith(DEVICE_COOKIE_NAME) }
        assertTrue("must be Secure: $setCookie", setCookie.contains("Secure", ignoreCase = true))
        assertTrue("must be HttpOnly: $setCookie", setCookie.contains("HttpOnly", ignoreCase = true))
        assertTrue("must be SameSite=Strict: $setCookie", setCookie.contains("SameSite=Strict"))
        assertTrue("must be scoped to /admin: $setCookie", setCookie.contains("Path=/admin"))
        // Thirty days, so a browser stops presenting it even if the row outlives the check.
        assertTrue("must carry a max age: $setCookie", setCookie.contains("Max-Age=2592000"))
    }

    // ------------------------------------------------------------ 2FA reset

    @Test
    fun `resetting another admin's 2FA needs your own password`() = testApplication {
        // The one action in the portal that hands out a working credential, so a borrowed
        // session is not enough on its own.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val csrf = app.csrfToken()
        val response = app.submitForm(
            "/admin/admins/$OTHER_ADMIN_ID/reset-2fa",
            parameters { append("csrf", csrf); append("password", "not-my-password") },
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("nothing should have been reset", 0, store.totpResets)
    }

    @Test
    fun `you cannot reset your own 2FA`() = testApplication {
        // Otherwise somebody holding a borrowed session moves the second factor onto their
        // own phone without ever knowing the password.
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val csrf = app.csrfToken()
        val response = app.submitForm(
            "/admin/admins/$ADMIN_ID/reset-2fa",
            parameters { append("csrf", csrf); append("password", PASSWORD) },
        )

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(0, store.totpResets)
    }

    @Test
    fun `a reset issues a new secret and forgets the target's remembered browsers`() = testApplication {
        // The forgetting is the part that matters: a remembered browser would let the
        // person who lost their authenticator keep signing in and never enrol the new
        // secret, leaving an account whose second factor exists only in the database.
        val store = FakeAdminStore()
        store.trusted["some-hash"] = OTHER_ADMIN_ID
        val app = withAdmin(store)
        app.signIn()

        val csrf = app.csrfToken()
        val response = app.submitForm(
            "/admin/admins/$OTHER_ADMIN_ID/reset-2fa",
            parameters { append("csrf", csrf); append("password", PASSWORD) },
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, store.totpResets)
        assertTrue(OTHER_ADMIN_ID in store.devicesRevokedFor)
        assertTrue(
            "the reset must name who did it and to whom",
            store.audits.any { it.action == "reset-2fa" && it.target == "other@example.com" },
        )
        // Shown once, to the admin who performed it — they hand it over in person.
        assertTrue(response.bodyAsText().contains("other@example.com"))
    }

    @Test
    fun `a reset without a CSRF token does nothing`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        app.submitForm(
            "/admin/admins/$OTHER_ADMIN_ID/reset-2fa",
            parameters { append("password", PASSWORD) },
        )

        assertEquals(0, store.totpResets)
    }

    @Test
    fun `the enrolment page shows a scannable QR and the typed secret`() = testApplication {
        val store = FakeAdminStore(totpConfirmed = false)
        val app = withAdmin(store)
        app.signInPassword()

        val body = app.get("/admin/2fa").bodyAsText()

        assertTrue("no QR on the page", body.contains("<svg"))
        assertTrue("no fallback secret", body.contains(secret))
        assertTrue("should offer to remember the browser", body.contains("""name="remember""""))
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    fun `a mutating form without the token is refused`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()

        val response = app.submitForm("/admin/invites", parameters { append("label", "someone") })

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("nothing may be created", 0, store.invitesCreated)
        assertTrue("and it is recorded", store.audits.any { it.action == "csrf-rejected" })
    }

    @Test
    fun `a mutating form with the token is accepted`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()
        val token = app.csrfToken()

        val response = app.submitForm(
            "/admin/invites",
            parameters { append("label", "a tester"); append("csrf", token) },
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, store.invitesCreated)
        assertTrue(store.audits.any { it.action == "create-invite" })
    }

    @Test
    fun `a stale token from another session does not work`() = testApplication {
        val app = withAdmin(FakeAdminStore())
        app.signIn()

        val response = app.submitForm(
            "/admin/invites",
            parameters { append("label", "x"); append("csrf", "clearly-not-the-token") },
        )

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ------------------------------------------------------------------ policy

    @Test
    fun `the last admin who can sign in cannot be disabled`() = testApplication {
        // Recovering from nobody being able to sign in needs a script over SSM, which is
        // the situation this portal exists to avoid.
        val store = FakeAdminStore(activeAdmins = 1)
        val app = withAdmin(store)
        app.signIn()
        val token = app.csrfToken()

        val response = app.submitForm(
            "/admin/admins/00000000-0000-0000-0000-000000000009/disable",
            parameters { append("csrf", token) },
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, store.disabledCalls)
    }

    @Test
    fun `an admin cannot disable themselves`() = testApplication {
        val store = FakeAdminStore(activeAdmins = 3)
        val app = withAdmin(store)
        app.signIn()
        val token = app.csrfToken()

        val response = app.submitForm(
            "/admin/admins/$ADMIN_ID/disable",
            parameters { append("csrf", token) },
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, store.disabledCalls)
    }

    @Test
    fun `a new admin needs a long password, because of what the account can read`() = testApplication {
        val store = FakeAdminStore()
        val app = withAdmin(store)
        app.signIn()
        val token = app.csrfToken()

        val response = app.submitForm(
            "/admin/admins",
            parameters {
                append("name", "Someone"); append("email", "new@example.com")
                append("password", "short"); append("csrf", token)
            },
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, store.adminsCreated)
    }

    @Test
    fun `a photo the system does not know about is not served from disk`() = testApplication {
        // Content-addressed storage plus a path that takes a hash is exactly the shape
        // where "any 64 hex characters" quietly becomes "any file in the blob directory".
        val blobs = BlobStore(folder.newFolder())
        val orphan = BlobStore.hash("not referenced by anything".toByteArray())
        runBlocking { blobs.put(orphan, "not referenced by anything".toByteArray()) }
        val app = withAdmin(FakeAdminStore(knownBlobs = emptySet()), blobs)
        app.signIn()

        assertEquals(HttpStatusCode.NotFound, app.get("/admin/photos/$orphan").status)
        assertEquals(HttpStatusCode.BadRequest, app.get("/admin/photos/not-a-hash").status)
    }

    @Test
    fun `an export leaves photos out unless asked, and says which it did`() = testApplication {
        val bytes = "a photograph".toByteArray()
        val blobs = BlobStore(folder.newFolder())
        val sha = BlobStore.hash(bytes)
        runBlocking { blobs.put(sha, bytes) }
        val store = FakeAdminStore(
            conversation = listOf(
                AdminMessageRow("USER", "here it is", Instant.now(), listOf(sha)),
            ),
            knownBlobs = setOf(sha),
        )
        val app = withAdmin(store, blobs)
        app.signIn()

        val without = app.get("/admin/export/assessment/$SESSION_ID").bodyAsText()
        val with = app.get("/admin/export/assessment/$SESSION_ID?photos=true").bodyAsText()

        assertFalse("photos must be left out by default", without.contains("photos_base64"))
        assertTrue("the hash is still there", without.contains(sha))
        assertTrue("asked for, they are included", with.contains("photos_base64"))
        // Both are audited, and the entry says which kind it was — an export with photos
        // is a different thing to have taken away.
        val exports = store.audits.filter { it.action == "export-assessment" }
        assertEquals(2, exports.size)
        assertTrue(exports.any { it.detail == "without photos" })
        assertTrue(exports.any { it.detail == "with photos" })
    }

    // ------------------------------------------------------------------ harness

    private fun ApplicationTestBuilder.withAdmin(
        store: AdminStore,
        blobs: BlobStore = BlobStore(folder.newFolder()),
    ): HttpClient {
        application {
            module(
                version = "test",
                database = null,
                admin = Admin(store, blobs, "a-signing-key-long-enough-to-be-real", secureCookie = false),
            )
        }
        return createClient {
            install(HttpCookies)
            // Redirects are the assertion in several tests, so they must not be followed.
            followRedirects = false
        }
    }

    private suspend fun HttpClient.signInPassword() {
        submitForm(
            "/admin/login",
            parameters { append("email", "admin@example.com"); append("password", PASSWORD) },
        )
    }

    private suspend fun HttpClient.signIn() {
        signInPassword()
        submitForm("/admin/2fa", parameters { append("code", currentCode()) })
    }

    /** Signs out the way the page does. Keeps the device cookie — only the session goes. */
    private suspend fun HttpClient.signOut() {
        val csrf = csrfToken()
        submitForm("/admin/logout", parameters { append("csrf", csrf) })
    }

    private fun currentCode(): String =
        Totp.generate("12345678901234567890".toByteArray(), System.currentTimeMillis() / 1000 / 30)

    /** Scrapes the token out of a rendered page, the way a browser would submit it. */
    private suspend fun HttpClient.csrfToken(): String {
        val body = get("/admin/invites").bodyAsText()
        return Regex("""name="csrf" value="([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("no CSRF token on the page")
    }

    private inner class FakeAdminStore(
        private val disabled: Boolean = false,
        private val lockedUntil: Instant? = null,
        private val activeAdmins: Int = 3,
        private val conversation: List<AdminMessageRow> = listOf(
            AdminMessageRow("USER", "I am buying this.", Instant.now(), listOf(PHOTO_SHA)),
            AdminMessageRow("ASSISTANT", "Here is the plan.", Instant.now(), emptyList()),
        ),
        private val knownBlobs: Set<String> = setOf(PHOTO_SHA),
        private val totpConfirmed: Boolean = true,
    ) : AdminStore {
        val audits = mutableListOf<AuditRow>()
        var failures = 0
            private set
        var signIns = 0
            private set
        var invitesCreated = 0
            private set
        var adminsCreated = 0
            private set
        var disabledCalls = 0
            private set
        var totpResets = 0
            private set
        var devicesRevokedFor = mutableListOf<String>()
            private set
        /** Hash -> the admin it belongs to. Set by a test to simulate a remembered browser. */
        val trusted = mutableMapOf<String, String>()
        var devicesTrusted = 0
            private set

        override suspend fun overview() = Overview(1, 1, 1, 1, 1, emptyList())

        override suspend fun credentialsFor(email: String): AdminCredentials? =
            if (email.lowercase() != "admin@example.com") null
            else AdminCredentials(
                id = ADMIN_ID,
                email = "admin@example.com",
                name = "An Admin",
                passwordHash = PASSWORD_HASH,
                totpSecret = secret,
                totpConfirmed = totpConfirmed,
                lockedUntil = lockedUntil,
                disabled = disabled,
            )

        override suspend fun createAdmin(
            email: String, name: String, passwordHash: String, createdBy: String?,
        ): Pair<String, String>? {
            adminsCreated++
            return "new-id" to Totp.newSecret()
        }

        override suspend fun confirmTotp(adminId: String) = Unit
        override suspend fun recordSignIn(adminId: String) { signIns++ }
        override suspend fun recordFailure(adminId: String, lockFor: Duration, threshold: Int): Int {
            failures++
            return failures
        }

        override suspend fun setPasswordHash(adminId: String, hash: String) = Unit

        override suspend fun resetTotp(adminId: String): String? {
            totpResets++
            return if (adminId == OTHER_ADMIN_ID) Totp.newSecret() else null
        }

        override suspend fun trustDevice(
            adminId: String, tokenHash: String, label: String?, expiresAt: Instant,
        ) {
            devicesTrusted++
            trusted[tokenHash] = adminId
        }

        override suspend fun trustedDevice(tokenHash: String): TrustedDevice? =
            trusted[tokenHash]?.let {
                TrustedDevice(
                    id = "device-1", adminId = it, label = "a browser",
                    createdAt = Instant.now(), lastUsedAt = null,
                    expiresAt = Instant.now().plusSeconds(3600),
                )
            }

        override suspend fun touchTrustedDevice(id: String) = Unit

        override suspend fun trustedDevices(adminId: String) =
            trusted.filterValues { it == adminId }.map {
                TrustedDevice("device-1", adminId, "a browser", Instant.now(), null, Instant.now().plusSeconds(3600))
            }

        override suspend fun revokeTrustedDevices(adminId: String): Int {
            devicesRevokedFor += adminId
            val before = trusted.size
            trusted.entries.removeAll { it.value == adminId }
            return before - trusted.size
        }
        override suspend fun setDisabled(adminId: String, disabled: Boolean) { disabledCalls++ }
        override suspend fun admins() = listOf(
            AdminRow(ADMIN_ID, "admin@example.com", "An Admin", Instant.now(), null, null, false, true),
            AdminRow(OTHER_ADMIN_ID, "other@example.com", "Other Admin", Instant.now(), null, null, false, true),
        )

        override suspend fun activeAdminCount() = activeAdmins
        override suspend fun invites() = listOf(
            InviteRow("ABCD-2345", "a tester", Instant.now(), null, 0),
        )

        override suspend fun createInvite(code: String, label: String?): Boolean {
            invitesCreated++
            return true
        }

        override suspend fun revokeInvite(code: String) = true
        override suspend fun users(limit: Int, offset: Int, search: String?) = Page(
            listOf(
                UserRow("u1", "+256700000000", "A Buyer", "individual", null, Instant.now(), 1, false),
            ),
            hasMore = false,
        )

        override suspend fun sessions(limit: Int, offset: Int, userId: String?, itemTypeId: String?) =
            Page(listOf(header()), hasMore = false)

        override suspend fun sessionHeader(sessionId: String) =
            if (sessionId == SESSION_ID) header() else null

        private fun header() = AdminSessionRow(
            id = SESSION_ID, itemTypeId = "wooden-table", userPhone = "+256700000000",
            userName = "A Buyer", createdAt = Instant.now(), updatedAt = Instant.now(),
            messageCount = 2, verdictLevelId = "fair", photoCount = 1, clientDeleted = false,
        )

        override suspend fun conversation(sessionId: String) = conversation
        override suspend fun blobExists(sha: String) = sha in knownBlobs

        override suspend fun audit(
            adminId: String?, adminEmail: String, action: String,
            target: String?, detail: String?, ip: String?,
        ) {
            audits += AuditRow(adminEmail, action, target, detail, ip, Instant.now())
        }

        override suspend fun auditTrail(limit: Int, offset: Int) = Page(audits.toList(), hasMore = false)
    }

    private companion object {
        const val PASSWORD = "a-long-enough-password"
        val PASSWORD_HASH: String = Passwords.hash(PASSWORD)
        const val ADMIN_ID = "11111111-2222-3333-4444-555555555555"
        /** A second admin, the one a reset is performed against. */
        const val OTHER_ADMIN_ID = "99999999-8888-7777-6666-555555555555"
        const val SESSION_ID = "2ff77920-3928-46e5-8a77-5e16c1e901c6"
        val PHOTO_SHA = "b".repeat(64)
        const val DEVICE_COOKIE_NAME = "kagua_admin_device"
    }
}

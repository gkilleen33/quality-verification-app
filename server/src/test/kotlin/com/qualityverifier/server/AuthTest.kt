package com.qualityverifier.server

import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Tokens
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.RegisterRequest
import com.qualityverifier.server.routes.SignInRequest
import com.qualityverifier.server.routes.validate
import com.qualityverifier.server.routes.validatePhone
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AuthTest {

    // ---------------------------------------------------------------- validation

    @Test
    fun `a business account must name the business`() {
        val request = valid(accountType = "business").copy(businessName = null)
        assertEquals("business_name is required for a business account", request.validate())
    }

    @Test
    fun `an individual account needs no business name`() {
        assertNull(
            valid().validate()
        )
    }

    @Test
    fun `a location must arrive whole or not at all`() {
        // Two of the three would store a point whose accuracy we do not know, which is
        // the one thing the accuracy column exists to prevent.
        val partial = valid().copy(latitude = 0.3341, longitude = 32.6206)
        assertEquals(
            "latitude, longitude and accuracy_m must be sent together or not at all",
            partial.validate(),
        )
        assertNull(partial.copy(accuracyMetres = 8.0).validate())
    }

    @Test
    fun `an implausible or useless fix is rejected`() {
        val base = valid()
        assertEquals(
            "latitude out of range",
            base.copy(latitude = 95.0, longitude = 0.0, accuracyMetres = 5.0).validate(),
        )
        // 5km is a district, not a location. Storing it would let "workshops near me"
        // place a shop on the wrong side of Kampala.
        assertEquals(
            "accuracy_m is too coarse to store",
            base.copy(latitude = 0.3, longitude = 32.6, accuracyMetres = 9000.0).validate(),
        )
    }

    @Test
    fun `an unknown account type is not accepted`() {
        assertEquals(
            "account_type must be individual or business",
            valid(accountType = "charity").validate(),
        )
    }

    // ---------------------------------------------------------------- tokens

    @Test
    fun `refresh tokens are stored as hashes, not tokens`() {
        val token = Tokens.mint()
        val hash = Tokens.hash(token)

        assertNotEquals(token, hash)
        assertEquals(64, hash.length)
        assertTrue(Tokens.matches(token, hash))
        assertTrue(!Tokens.matches(Tokens.mint(), hash))
    }

    @Test
    fun `minted tokens do not repeat`() {
        val seen = (1..200).map { Tokens.mint() }.toSet()
        assertEquals(200, seen.size)
    }

    @Test
    fun `an access token verifies, carries its subject, and expires`() {
        val user = UUID.randomUUID().toString()
        val tokens = AccessTokens("a-signing-key-long-enough-to-be-real")

        val issued = tokens.issue(user)
        assertEquals(user, tokens.verifier.verify(issued.token).subject)
        assertEquals(900L, issued.expiresInSeconds)

        // Signed with a different key, the same token must not verify — the check that
        // catches a deployment reading the wrong parameter.
        val other = AccessTokens("a-different-signing-key-entirely")
        val rejected = runCatching { other.verifier.verify(issued.token) }
        assertTrue("a token signed elsewhere must not verify", rejected.isFailure)
    }

    @Test
    fun `an expired access token is rejected`() {
        val past = Instant.now().minus(Duration.ofHours(2))
        val tokens = AccessTokens("k".repeat(40), lifetime = Duration.ofMinutes(15), now = { past })
        val issued = tokens.issue(UUID.randomUUID().toString())

        assertTrue(runCatching { tokens.verifier.verify(issued.token) }.isFailure)
    }

    // ---------------------------------------------------------------- routes

    @Test
    fun `registering returns tokens and the me endpoint accepts them`() = testApplication {
        val store = FakeAuthStore()
        val app = withAuth(store)

        val registered = app.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                valid(accountType = "business").copy(
                    latitude = 0.3341, longitude = 32.6206, accuracyMetres = 8.0,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, registered.status)
        val access = Regex("\"access_token\":\"([^\"]+)\"")
            .find(registered.bodyAsText())!!.groupValues[1]

        val me = app.get("/v1/me") { header(HttpHeaders.Authorization, "Bearer $access") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText(), me.bodyAsText().contains("Nakawa Furniture"))
    }

    @Test
    fun `me refuses a request with no token, and one signed elsewhere`() = testApplication {
        val app = withAuth(FakeAuthStore())

        assertEquals(HttpStatusCode.Unauthorized, app.get("/v1/me").status)

        val forged = AccessTokens("not-the-servers-key").issue(UUID.randomUUID().toString())
        val response = app.get("/v1/me") {
            header(HttpHeaders.Authorization, "Bearer ${forged.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `an unusable invite is refused without saying why`() = testApplication {
        val store = FakeAuthStore(inviteUsable = false)
        val app = withAuth(store)

        val response = app.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(valid().copy(inviteCode = "TAKEN"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        // Unknown, revoked and already-redeemed all answer the same, so the endpoint
        // cannot be used to test guessed codes.
        assertTrue(body, body.contains("invite_unusable"))
        assertTrue("must not distinguish the reason", !body.contains("redeem"))
    }

    @Test
    fun `refreshing rotates the token and retires the old one`() = testApplication {
        val store = FakeAuthStore()
        val app = withAuth(store)
        val first = store.seedRefresh("user-1")

        val response = app.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to first))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rotated = Regex("\"refresh_token\":\"([^\"]+)\"")
            .find(response.bodyAsText())!!.groupValues[1]
        assertNotEquals("the refresh token must change on use", first, rotated)
        assertTrue("the spent token must be marked used", store.spent.contains(Tokens.hash(first)))
    }

    @Test
    fun `replaying a spent refresh token revokes every token for that user`() = testApplication {
        // The security-critical path: we cannot tell a replay from a theft, and the safe
        // reading of the ambiguity is that the account is compromised.
        val store = FakeAuthStore()
        val app = withAuth(store)
        val token = store.seedRefresh("user-1", spent = true)

        val response = app.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to token))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(listOf("user-1"), store.revoked)
    }

    @Test
    fun `an expired or revoked refresh token is refused and revokes nothing`() = testApplication {
        val store = FakeAuthStore()
        val app = withAuth(store)
        val expired = store.seedRefresh("user-1", expiresAt = Instant.now().minusSeconds(60))

        val response = app.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to expired))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // An honest client whose token simply aged out should not have its other
        // sessions destroyed; only a replay means something is wrong.
        assertTrue(store.revoked.isEmpty())
    }

    @Test
    fun `a disabled user cannot refresh`() = testApplication {
        val store = FakeAuthStore(userDisabled = true)
        val app = withAuth(store)
        val token = store.seedRefresh("user-1")

        val response = app.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to token))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `auth routes are absent rather than broken when auth is not configured`() = testApplication {
        // A 404 says "this deployment has no auth"; a 500 on every sign-in says nothing.
        application { module(version = "test", database = null, auth = null) }

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/me").status)
    }

    // ---------------------------------------------------------------- passwords

    @Test
    fun `a password verifies against its own hash and nothing else`() {
        val hash = Passwords.hash("correct horse battery")

        assertTrue(Passwords.verify("correct horse battery", hash))
        assertTrue(!Passwords.verify("Correct horse battery", hash))
        assertTrue(!Passwords.verify("", hash))
    }

    @Test
    fun `the same password hashes differently every time`() {
        // Distinct salts, so two people with the same password do not share a hash and
        // a leaked table cannot be attacked once for many accounts.
        assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))
    }

    @Test
    fun `the hash records its own parameters so the cost can be raised later`() {
        val hash = Passwords.hash("x")
        assertTrue(hash, hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
    }

    @Test
    fun `a corrupt stored hash fails one sign-in rather than throwing`() {
        // A bad row should cost one person one attempt, not take the endpoint down.
        listOf("", "not-a-hash", "\$argon2id\$v=19\$m=bad,t=2,p=1\$c2FsdA\$aGFzaA", "\$bcrypt\$x")
            .forEach { assertTrue(it, !Passwords.verify("x", it)) }
    }

    // ---------------------------------------------------------------- sign-in

    @Test
    fun `phone numbers must be international, so one person cannot become two`() {
        assertNull(validatePhone("+256700123456"))
        assertEquals(
            "phone must be in international format, starting with +",
            validatePhone("0700123456"),
        )
        assertEquals("phone is not a valid number", validatePhone("+0700123456"))
        assertEquals("phone is required", validatePhone(" "))
    }

    @Test
    fun `signing in with the right password issues tokens and clears the counter`() = testApplication {
        val store = FakeAuthStore().apply { seedAccount("+256700123456", PASSWORD) }
        val app = withAuth(store)

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700123456", password = PASSWORD))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("access_token"))
        assertEquals(listOf("user-1"), store.cleared)
    }

    @Test
    fun `a wrong password counts a failure and says nothing useful`() = testApplication {
        val store = FakeAuthStore().apply { seedAccount("+256700123456", PASSWORD) }
        val app = withAuth(store)

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700123456", password = "wrong"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
        assertEquals(listOf("user-1"), store.failures)
    }

    @Test
    fun `an unknown number answers exactly as a wrong password does`() = testApplication {
        val app = withAuth(FakeAuthStore())

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700999999", password = PASSWORD))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `a locked account is refused before the password is even checked`() = testApplication {
        // Checked first so a locked account cannot be used as an oracle for guessing.
        val store = FakeAuthStore().apply {
            seedAccount("+256700123456", PASSWORD, lockedUntil = Instant.now().plusSeconds(600))
        }
        val app = withAuth(store)

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700123456", password = PASSWORD))
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(store.failures.isEmpty())
    }

    @Test
    fun `an expired lock lets a correct password through`() = testApplication {
        val store = FakeAuthStore().apply {
            seedAccount("+256700123456", PASSWORD, lockedUntil = Instant.now().minusSeconds(1))
        }
        val app = withAuth(store)

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700123456", password = PASSWORD))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `a disabled account cannot sign in even with the right password`() = testApplication {
        val store = FakeAuthStore(userDisabled = true)
            .apply { seedAccount("+256700123456", PASSWORD) }
        val app = withAuth(store)

        val response = app.post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(phone = "+256700123456", password = PASSWORD))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // No failure counted against an account that could never have succeeded.
        assertTrue(store.failures.isEmpty())
    }

    @Test
    fun `an already-registered number is told to sign in instead`() = testApplication {
        // Unlike the invite, this is said plainly: it reveals nothing an attacker could
        // not learn by trying to sign in, and the alternative is a customer stuck on a
        // registration form that will never work.
        val app = withAuth(FakeAuthStore(phoneTaken = true))

        val response = app.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(valid())
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("phone_taken"))
    }

    @Test
    fun `registration requires a usable phone number and a long enough password`() {
        assertEquals(
            "phone must be in international format, starting with +",
            valid().copy(phone = "0700123456").validate(),
        )
        assertEquals(
            "password must be at least 8 characters",
            valid().copy(password = "short").validate(),
        )
    }

    // ---------------------------------------------------------------- harness

    private fun ApplicationTestBuilder.withAuth(store: FakeAuthStore) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(store, AccessTokens(SIGNING_KEY)),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private class FakeAuthStore(
        private val inviteUsable: Boolean = true,
        private val userDisabled: Boolean = false,
        private val phoneTaken: Boolean = false,
    ) : AuthStore {
        private val refreshes = mutableMapOf<String, StoredRefresh>()
        private val credentials = mutableMapOf<String, Credentials>()
        val spent = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val passwordChanges = mutableListOf<String>()
        val accountsDeleted = mutableListOf<String>()

        fun seedAccount(
            phone: String,
            password: String,
            lockedUntil: Instant? = null,
        ) {
            credentials[phone] = Credentials(
                userId = "user-1",
                passwordHash = Passwords.hash(password),
                lockedUntil = lockedUntil,
                failedAttempts = 0,
                disabled = userDisabled,
            )
        }

        fun seedRefresh(
            userId: String,
            spent: Boolean = false,
            expiresAt: Instant = Instant.now().plusSeconds(3600),
        ): String {
            val token = Tokens.mint()
            refreshes[Tokens.hash(token)] = StoredRefresh(
                id = UUID.randomUUID().toString(),
                userId = userId,
                expiresAt = expiresAt,
                spent = spent,
                revoked = false,
            )
            return token
        }

        override suspend fun register(registration: Registration): RegisterOutcome = when {
            phoneTaken -> RegisterOutcome.PhoneTaken
            !inviteUsable -> RegisterOutcome.InviteUnusable
            else -> RegisterOutcome.Created("user-1")
        }

        override suspend fun credentialsForPhone(phone: String) = credentials[phone]

        override suspend fun recordFailedSignIn(
            userId: String,
            lockFor: Duration,
            threshold: Int,
        ): Int {
            failures += userId
            return failures.count { it == userId }
        }

        override suspend fun clearFailedSignIns(userId: String) {
            cleared += userId
        }

        override suspend fun passwordHashFor(userId: String) =
            credentials.values.firstOrNull { it.userId == userId }?.passwordHash

        override suspend fun setPasswordHash(userId: String, passwordHash: String) {
            passwordChanges += userId
        }

        override suspend fun markAccountDeleted(userId: String) {
            accountsDeleted += userId
        }

        override suspend fun findUser(userId: String) = UserRow(
            id = userId,
            displayName = "Grady",
            accountType = "business",
            businessName = "Nakawa Furniture",
            disabled = userDisabled,
        )

        override suspend fun issueRefresh(
            userId: String,
            token: String,
            expiresAt: Instant,
            userAgent: String?,
            replaces: String?,
        ): String {
            refreshes[Tokens.hash(token)] = StoredRefresh(
                UUID.randomUUID().toString(), userId, expiresAt, spent = false, revoked = false,
            )
            if (replaces != null) {
                refreshes.entries.firstOrNull { it.value.id == replaces }?.let { entry ->
                    spent += entry.key
                    refreshes[entry.key] = entry.value.copy(spent = true)
                }
            }
            return UUID.randomUUID().toString()
        }

        override suspend fun findRefresh(token: String) = refreshes[Tokens.hash(token)]

        override suspend fun revokeChain(userId: String): Int {
            revoked += userId
            return 1
        }
    }

    private companion object {
        const val SIGNING_KEY = "a-signing-key-long-enough-to-be-real"
        const val PASSWORD = "correct horse battery"

        /** Valid in every field, so a test changes only the one it is about. */
        fun valid(accountType: String = "individual") = RegisterRequest(
            inviteCode = "GOOD",
            phone = "+256700123456",
            password = PASSWORD,
            name = "Grady",
            accountType = accountType,
            businessName = if (accountType == "business") "Nakawa Furniture" else null,
        )
    }
}

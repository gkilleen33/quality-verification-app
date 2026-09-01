package com.qualityverifier.server

import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Tokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.RegisterRequest
import com.qualityverifier.server.routes.validate
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
        val request = RegisterRequest(
            inviteCode = "ABC", name = "Grady", accountType = "business",
        )
        assertEquals("business_name is required for a business account", request.validate())
    }

    @Test
    fun `an individual account needs no business name`() {
        assertNull(
            RegisterRequest(inviteCode = "ABC", name = "Grady", accountType = "individual").validate()
        )
    }

    @Test
    fun `a location must arrive whole or not at all`() {
        // Two of the three would store a point whose accuracy we do not know, which is
        // the one thing the accuracy column exists to prevent.
        val partial = RegisterRequest(
            inviteCode = "ABC", name = "G", accountType = "individual",
            latitude = 0.3341, longitude = 32.6206,
        )
        assertEquals(
            "latitude, longitude and accuracy_m must be sent together or not at all",
            partial.validate(),
        )
        assertNull(partial.copy(accuracyMetres = 8.0).validate())
    }

    @Test
    fun `an implausible or useless fix is rejected`() {
        val base = RegisterRequest(inviteCode = "A", name = "G", accountType = "individual")
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
            RegisterRequest(inviteCode = "A", name = "G", accountType = "charity").validate(),
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
                RegisterRequest(
                    inviteCode = "GOOD", name = "Grady", accountType = "business",
                    businessName = "Nakawa Furniture",
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
            setBody(RegisterRequest(inviteCode = "TAKEN", name = "G", accountType = "individual"))
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
    ) : AuthStore {
        private val refreshes = mutableMapOf<String, StoredRefresh>()
        val spent = mutableListOf<String>()
        val revoked = mutableListOf<String>()

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

        override suspend fun register(registration: Registration): RegisterOutcome =
            if (inviteUsable) RegisterOutcome.Created("user-1") else RegisterOutcome.InviteUnusable

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
    }
}

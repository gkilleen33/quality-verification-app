package com.qualityverifier.server

import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Tokens
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.PostgresAuthStore
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.RegisterRequest
import com.qualityverifier.server.routes.SignInRequest
import com.qualityverifier.server.routes.UPDATE_TO_SIGN_IN
import com.qualityverifier.server.routes.normalisePhone
import com.qualityverifier.server.routes.opensFundiShop
import com.qualityverifier.server.routes.signInAudience
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
        // place a shop on the wrong side of Nairobi.
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
    fun `the sign-up check refuses a revoked invite code`() {
        // A deleted account's code is revoked by anonymise_user (V26, held by
        // MigrationsTest), and this filter is what then refuses it: a revoked code returns no
        // row, register() answers no row with InviteUnusable, and the route answers that with
        // 403 (the test above). Checked on the statement because these tests have no database.
        val sql = PostgresAuthStore.REDEEMABLE_INVITE
        assertTrue(
            sql,
            Regex("""(?i)\bfrom invite_codes where code = \? and revoked_at is null\b""")
                .containsMatchIn(sql),
        )
        // Nothing after the filter that could let a revoked code back in.
        assertTrue(sql, !Regex("""(?i)\bor\b""").containsMatchIn(sql))
        // The row lock makes a sign-up wait for a delete in flight and re-check revoked_at.
        assertTrue(sql, Regex("""(?i)\bfor share\s*$""").containsMatchIn(sql))
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
    fun `every way a Kenyan number is typed becomes the one stored form`() {
        // The same rules as V21, which rewrote the stored numbers. If these drift apart, a
        // rewritten account stops matching what its owner types.
        listOf(
            "+254712345678", "0712345678", "0712 345 678", "254712345678",
            "00254712345678", "+2540712345678", "+254 (0)712-345-678", "002540712345678",
        ).forEach { assertEquals(it, "+254712345678", normalisePhone(it)) }
        assertEquals("+254110123456", normalisePhone("0110 123 456"))
        // Numbers from elsewhere keep their country code; only separators go.
        assertEquals("+256700123456", normalisePhone("+256 (700) 123-456"))
        assertEquals("+16175550100", normalisePhone("+1 617 555 0100"))
        // Something that is not a number is passed through for validatePhone to refuse.
        assertEquals("07123", normalisePhone("07123"))
        assertEquals(
            "phone must be in international format, starting with +",
            validatePhone(normalisePhone("07123")),
        )
    }

    @Test
    fun `a number typed with the trunk zero signs in to the account stored without it`() =
        testApplication {
            // An account V21 rewrote from +2540712… to +254712…, signed into the old way.
            val store = FakeAuthStore().apply { seedAccount("+254712345678", PASSWORD) }
            val app = withAuth(store)

            val response = app.post("/v1/auth/sign-in") {
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(phone = "+2540712345678", password = PASSWORD))
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `registration stores the normalised number`() = testApplication {
        val store = FakeAuthStore()
        val app = withAuth(store)

        val response = app.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(valid().copy(phone = "0712 345 678"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(listOf("+254712345678"), store.registeredPhones)
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

    // ------------------------------------------- one number, one account per app (row 30)

    @Test
    fun `row 30 -each app names the account it signs in to`() {
        assertEquals(Audience.BUYER, signInAudience("kagua"))
        assertEquals(Audience.FUNDI, signInAudience("fundi"))
        // PesaLog signs in with the Fundi Bora account (V33).
        assertEquals(Audience.FUNDI, signInAudience("tracker"))
        assertNull(signInAudience(null))
        assertNull(signInAudience("something-else"))
    }

    @Test
    fun `row 30 -Kagua signs in to the number's Kagua account`() = testApplication {
        val app = withAuth(shopOwner())

        val response = app.signIn(app = "kagua", password = KAGUA_PASSWORD)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(KAGUA_USER))
    }

    @Test
    fun `row 30 -Fundi Bora signs in to the number's Fundi Bora account`() = testApplication {
        val app = withAuth(shopOwner())

        val response = app.signIn(app = "fundi", password = FUNDI_PASSWORD)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(FUNDI_USER))
    }

    @Test
    fun `row 30 -PesaLog signs in to the number's Fundi Bora account`() = testApplication {
        val app = withAuth(shopOwner())

        val response = app.signIn(app = "tracker", password = FUNDI_PASSWORD)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(FUNDI_USER))
    }

    @Test
    fun `row 30 -the other app's password does not open this app's account`() = testApplication {
        // The Fundi Bora password typed into a Kagua older than v0.7.0 (no version sent) is
        // checked against the Kagua account only, and counts as a failure there. From v0.7.0
        // it opens the Fundi Bora account's shop instead (row 53, below).
        val store = shopOwner()
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
        assertEquals(listOf(KAGUA_USER), store.failures)
    }

    @Test
    fun `row 30 -an app is never signed in to the other app's account`() = testApplication {
        // A number with only a Fundi Bora account, signing in to a Kagua older than v0.7.0 with
        // that account's own password: refused as an unknown number is, rather than opening a
        // Fundi Bora account in Kagua as the server did before V38. Kagua from v0.7.0 opens its
        // shop account instead, never the Fundi Bora account itself (row 53, below).
        val store = FakeAuthStore().apply {
            seedAccount(SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER)
        }
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
        assertTrue(store.cleared.isEmpty())
    }

    @Test
    fun `row 30 -an old phone with no app named signs in to the number's one account`() =
        testApplication {
            val store = FakeAuthStore().apply {
                seedAccount(SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER)
            }
            val app = withAuth(store)

            val response = app.signIn(app = null, password = FUNDI_PASSWORD)

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(FUNDI_USER))
        }

    @Test
    fun `row 30 -an old phone with no app named is asked to update when the number has two accounts`() =
        testApplication {
            val store = shopOwner()
            val app = withAuth(store)

            val response = app.signIn(app = null, password = KAGUA_PASSWORD)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            // invalid_request, whose detail every release from v0.1.0 shows as it is.
            assertTrue(body.contains("invalid_request"))
            assertTrue(body.contains(UPDATE_TO_SIGN_IN))
            assertTrue(!body.contains("access_token"))
            // Neither account signed in to, and the right password counted against neither.
            assertTrue(store.cleared.isEmpty())
            assertTrue(store.failures.isEmpty())
        }

    @Test
    fun `row 30 -an old phone with a wrong password learns nothing about the second account`() =
        testApplication {
            val store = shopOwner()
            val app = withAuth(store)

            val response = app.signIn(app = null, password = "wrong password")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("invalid_credentials"))
            assertTrue(!body.contains(UPDATE_TO_SIGN_IN))
            // Counted on both, so guessing without naming an app is locked out as fast.
            assertEquals(setOf(KAGUA_USER, FUNDI_USER), store.failures.toSet())
        }

    // ------------------------------- a fundi's Fundi Bora details open Kagua as a shop (row 53)

    @Test
    fun `row 53 - only Kagua builds that run shop mode may open a Fundi Bora shop`() {
        assertTrue(opensFundiShop("kagua", "8"))
        assertTrue(opensFundiShop("kagua", "12"))
        assertTrue(!opensFundiShop("kagua", "7"))
        // No version: a phone older than v0.4.0.
        assertTrue(!opensFundiShop("kagua", null))
        assertTrue(!opensFundiShop("fundi", "8"))
        assertTrue(!opensFundiShop("tracker", "8"))
        assertTrue(!opensFundiShop(null, "8"))
    }

    @Test
    fun `row 53 - sign-in with Fundi Bora details creates then reuses the shop account`() =
        testApplication {
            val store = fundiOnly()
            val app = withAuth(store)

            val first = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)
            assertEquals(HttpStatusCode.OK, first.status)
            val body = first.bodyAsText()
            assertTrue(body, body.contains("\"user_id\":\"$FUNDI_SHOP\""))
            // Kagua enters shop mode from the sign-in answer itself (row 12).
            assertTrue(body, body.contains("\"shop_mode\":true"))
            assertEquals(1, store.shopsMade)

            val second = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)
            assertEquals(HttpStatusCode.OK, second.status)
            assertTrue(second.bodyAsText().contains("\"user_id\":\"$FUNDI_SHOP\""))
            assertEquals("the second sign-in reuses the shop account", 1, store.shopsMade)
            // The account whose password matched has its failures cleared, not the shop's.
            assertEquals(listOf(FUNDI_USER, FUNDI_USER), store.cleared)
            assertTrue(store.failures.isEmpty())
        }

    @Test
    fun `row 53 - a Kagua account's own password still wins`() = testApplication {
        val store = shopOwner()
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = KAGUA_PASSWORD, version = SHOP_KAGUA)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"user_id\":\"$KAGUA_USER\""))
        assertEquals(0, store.shopsMade)
        assertTrue(store.failures.isEmpty())

        // The Fundi Bora password on the same number opens the shop instead.
        val shop = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)
        assertTrue(shop.bodyAsText().contains("\"user_id\":\"$FUNDI_SHOP\""))
    }

    @Test
    fun `row 53 - a Kagua account's own password wins when both accounts share it`() = testApplication {
        val store = FakeAuthStore().apply {
            seedAccount(SHOP_NUMBER, PASSWORD, audience = Audience.BUYER, userId = KAGUA_USER)
            seedAccount(SHOP_NUMBER, PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER)
        }
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = PASSWORD, version = SHOP_KAGUA)

        assertTrue(response.bodyAsText().contains("\"user_id\":\"$KAGUA_USER\""))
        assertEquals(0, store.shopsMade)
    }

    @Test
    fun `row 53 - a wrong password is refused and counted against each account tried`() =
        testApplication {
            val store = shopOwner()
            val app = withAuth(store)

            val response = app.signIn(app = "kagua", password = "wrong password", version = SHOP_KAGUA)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("invalid_credentials"))
            assertEquals(setOf(KAGUA_USER, FUNDI_USER), store.failures.toSet())
            assertEquals(2, store.failures.size)
            assertEquals(0, store.shopsMade)
        }

    @Test
    fun `row 53 - a wrong password on a Fundi Bora number is counted on the Fundi Bora account`() =
        testApplication {
            val store = fundiOnly()
            val app = withAuth(store)

            val response = app.signIn(app = "kagua", password = "wrong password", version = SHOP_KAGUA)

            // The same answer as an unknown number.
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("invalid_credentials"))
            assertEquals(listOf(FUNDI_USER), store.failures)
            assertEquals(0, store.shopsMade)
        }

    @Test
    fun `row 53 - a locked Fundi Bora account is not tried`() = testApplication {
        val store = FakeAuthStore().apply {
            seedAccount(SHOP_NUMBER, KAGUA_PASSWORD, audience = Audience.BUYER, userId = KAGUA_USER)
            seedAccount(
                SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER,
                lockedUntil = Instant.now().plusSeconds(600),
            )
        }
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // Tried on the Kagua account only, and counted there.
        assertEquals(listOf(KAGUA_USER), store.failures)
        assertEquals(0, store.shopsMade)
    }

    @Test
    fun `row 53 - a number whose accounts are all locked is told to wait`() = testApplication {
        val store = FakeAuthStore().apply {
            seedAccount(
                SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER,
                lockedUntil = Instant.now().plusSeconds(600),
            )
        }
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(store.failures.isEmpty())
        assertEquals(0, store.shopsMade)
    }

    @Test
    fun `row 53 - an unknown number answers as a wrong password does`() = testApplication {
        val store = FakeAuthStore()
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
    }

    @Test
    fun `row 53 - a disabled shop account is refused and nothing is counted`() = testApplication {
        val store = fundiOnly().apply { shopDisabled = true }
        val app = withAuth(store)

        val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = SHOP_KAGUA)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_credentials"))
        assertTrue(!response.bodyAsText().contains("access_token"))
        assertTrue(store.failures.isEmpty())
    }

    @Test
    fun `row 53 - an older Kagua build is refused as before`() = testApplication {
        val store = fundiOnly()
        val app = withAuth(store)

        listOf("7", null).forEach { version ->
            val response = app.signIn(app = "kagua", password = FUNDI_PASSWORD, version = version)
            assertEquals("Kagua at versionCode $version", HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("invalid_credentials"))
        }
        assertEquals(0, store.shopsMade)
        assertTrue(store.cleared.isEmpty())
    }

    @Test
    fun `row 53 - Fundi Bora and PesaLog still sign in to the Fundi Bora account`() = testApplication {
        val store = fundiOnly()
        val app = withAuth(store)

        listOf("fundi", "tracker").forEach { id ->
            val response = app.signIn(app = id, password = FUNDI_PASSWORD, version = SHOP_KAGUA)
            assertTrue(id, response.bodyAsText().contains("\"user_id\":\"$FUNDI_USER\""))
        }
        assertEquals(0, store.shopsMade)
    }

    /** A shop owner with a Kagua account and a Fundi Bora account on one number. */
    private fun shopOwner() = FakeAuthStore().apply {
        seedAccount(SHOP_NUMBER, KAGUA_PASSWORD, audience = Audience.BUYER, userId = KAGUA_USER)
        seedAccount(SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER)
    }

    /** A fundi with a Fundi Bora account and no Kagua account. */
    private fun fundiOnly() = FakeAuthStore().apply {
        seedAccount(SHOP_NUMBER, FUNDI_PASSWORD, audience = Audience.FUNDI, userId = FUNDI_USER)
    }

    private suspend fun io.ktor.client.HttpClient.signIn(
        app: String?,
        password: String,
        version: String? = null,
    ) =
        post("/v1/auth/sign-in") {
            contentType(ContentType.Application.Json)
            if (app != null) header(AppVersionHeaders.APP, app)
            if (version != null) header(AppVersionHeaders.VERSION, version)
            setBody(SignInRequest(phone = SHOP_NUMBER, password = password))
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
        /** By number: one account, or one per app (V38). */
        private val credentials = mutableMapOf<String, MutableList<Credentials>>()
        val spent = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val passwordChanges = mutableListOf<String>()
        val accountsDeleted = mutableListOf<String>()
        val registeredPhones = mutableListOf<String>()

        fun seedAccount(
            phone: String,
            password: String,
            lockedUntil: Instant? = null,
            audience: Audience = Audience.BUYER,
            userId: String = "user-1",
        ) {
            credentials.getOrPut(phone) { mutableListOf() } += Credentials(
                userId = userId,
                passwordHash = Passwords.hash(password),
                lockedUntil = lockedUntil,
                failedAttempts = 0,
                disabled = userDisabled,
                audience = audience,
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

        override suspend fun register(registration: Registration): RegisterOutcome {
            registeredPhones += registration.phone
            return when {
                phoneTaken -> RegisterOutcome.PhoneTaken
                !inviteUsable -> RegisterOutcome.InviteUnusable
                else -> RegisterOutcome.Created("user-1")
            }
        }

        override suspend fun credentialsForPhone(phone: String): List<Credentials> =
            credentials[phone].orEmpty()

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
            credentials.values.flatten().firstOrNull { it.userId == userId }?.passwordHash

        override suspend fun setPasswordHash(userId: String, passwordHash: String) {
            passwordChanges += userId
        }

        override suspend fun markAccountDeleted(userId: String) {
            accountsDeleted += userId
        }

        /** Each Fundi Bora account's Kagua shop account (V43, row 53), as the store makes them. */
        private val shops = mutableMapOf<String, String>()
        var shopsMade = 0
            private set

        /** The shop account exists but is disabled, so shopAccountFor answers null. */
        var shopDisabled = false

        override suspend fun shopAccountFor(fundiUserId: String): String? {
            if (shopDisabled) return null
            return shops.getOrPut(fundiUserId) {
                shopsMade++
                "shop-of-$fundiUserId"
            }
        }

        override suspend fun findUser(userId: String): UserRow {
            val owner = shops.entries.firstOrNull { it.value == userId }?.key
            return UserRow(
                id = userId,
                displayName = "Grady",
                accountType = "business",
                businessName = "Nakawa Furniture",
                disabled = userDisabled,
                shopMode = owner != null,
                shopOfUserId = owner,
            )
        }

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

        /** Row 30's shop owner: one number, a Kagua account and a Fundi Bora account. */
        const val SHOP_NUMBER = "+254712000111"
        const val KAGUA_USER = "kagua-account"
        const val FUNDI_USER = "fundi-account"
        const val KAGUA_PASSWORD = "the shop tablet password"
        const val FUNDI_PASSWORD = "the workshop password"

        /** Row 53: the shop account the fake store makes for FUNDI_USER. */
        const val FUNDI_SHOP = "shop-of-$FUNDI_USER"

        /** Kagua v0.7.0's versionCode, the first that runs shop mode. */
        const val SHOP_KAGUA = "8"

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

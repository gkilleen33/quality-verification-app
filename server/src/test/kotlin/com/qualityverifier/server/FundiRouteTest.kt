package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolChange
import com.qualityverifier.domain.ToolChangeReason
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.domain.toolChanges
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.FundiStore
import com.qualityverifier.server.db.RecordedToolChange
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The maker's setup answers.
 *
 * Two things are worth asserting here and the rest is plumbing: that a Kagua account
 * cannot reach these endpoints at all, and that a vocabulary this build has never heard of
 * costs one tool rather than the whole profile. Prompts are data and the tool list will
 * grow, so a newer app talking to an older server is the ordinary case, not the edge one.
 */
class FundiRouteTest {

    @Test
    fun `a fundi can save and read back their profile`() = testApplication {
        val store = FakeFundiStore()
        val app = withFundi(store, audience = Audience.FUNDI)

        val saved = app.put("/v1/fundi/profile") {
            auth(FUNDI)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"workshop":{"works_at":"Gikomba","makes":"stools","years_in_trade":8},
                 "tools":[{"kind":"chisels","ownership":"owned"},
                          {"kind":"clamps","ownership":"none"}],
                 "goals":["zero_comebacks"]}
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, saved.status)
        val stored = store.saved.getValue(FUNDI)
        assertEquals("Gikomba", stored.workshop.worksAt)
        assertEquals(8, stored.workshop.yearsInTrade)
        assertEquals(listOf(ToolKind.CLAMPS), stored.missing)
        assertEquals(setOf(FundiGoal.ZERO_COMEBACKS), stored.goals)

        val read = app.get("/v1/fundi/profile") { auth(FUNDI) }
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.bodyAsText().contains("Gikomba"))
    }

    // The two apps have entirely independent accounts. A buyer here is a client bug, and
    // the workshop row it would write is one nothing will ever read.
    @Test
    fun `a Kagua account is refused`() = testApplication {
        val store = FakeFundiStore()
        val app = withFundi(store, audience = Audience.BUYER)

        val response = app.put("/v1/fundi/profile") {
            auth(FUNDI)
            contentType(ContentType.Application.Json)
            setBody("""{"tools":[{"kind":"chisels","ownership":"owned"}]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("not_a_fundi"))
        assertTrue("nothing may be written", store.saved.isEmpty())
    }

    @Test
    fun `a Kagua account cannot read one either`() = testApplication {
        val app = withFundi(FakeFundiStore(), audience = Audience.BUYER)

        assertEquals(
            HttpStatusCode.Forbidden,
            app.get("/v1/fundi/profile") { auth(FUNDI) }.status,
        )
    }

    // Registered but never set up. Distinct from a profile with nothing in it: the first
    // is a setup flow to run, the second is a set of answers to leave alone.
    @Test
    fun `no profile yet is a 404, not an empty one`() = testApplication {
        val app = withFundi(FakeFundiStore(), audience = Audience.FUNDI)

        val response = app.get("/v1/fundi/profile") { auth(FUNDI) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("no_profile"))
    }

    @Test
    fun `a tool this build has never heard of costs one tool, not the profile`() = testApplication {
        val store = FakeFundiStore()
        val app = withFundi(store, audience = Audience.FUNDI)

        val response = app.put("/v1/fundi/profile") {
            auth(FUNDI)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"tools":[{"kind":"chisels","ownership":"owned"},
                          {"kind":"laser_level","ownership":"owned"},
                          {"kind":"clamps","ownership":"upside_down"}],
                 "goals":["zero_comebacks","world_domination"]}
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val stored = store.saved.getValue(FUNDI)
        assertEquals(listOf(ToolKind.CHISELS), stored.tools.map { it.kind })
        assertEquals(setOf(FundiGoal.ZERO_COMEBACKS), stored.goals)
    }

    @Test
    fun `no token is a 401`() = testApplication {
        val app = withFundi(FakeFundiStore(), audience = Audience.FUNDI)

        assertEquals(HttpStatusCode.Unauthorized, app.get("/v1/fundi/profile").status)
    }

    // Why a tool went is the part worth having, so it has to survive the wire. An
    // unrecognised reason costs the reason and never the change: losing "they sold it"
    // because the word for why was new would be the worse trade.
    @Test
    fun `why a tool went reaches the store, and an unknown reason does not cost the change`() =
        testApplication {
            val store = FakeFundiStore(
                existing = FundiProfile(
                    workshop = Workshop(makes = "stools"),
                    tools = listOf(
                        OwnedTool(ToolKind.CIRCULAR_SAW, ToolOwnership.OWNED),
                        OwnedTool(ToolKind.ROUTER, ToolOwnership.OWNED),
                    ),
                ),
            )
            val app = withFundi(store, audience = Audience.FUNDI)

            val response = app.put("/v1/fundi/profile") {
                auth(FUNDI)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"tools":[
                      {"kind":"circular_saw","ownership":"none","change_reason":"sold",
                       "change_note":"bad month"},
                      {"kind":"router","ownership":"none","change_reason":"repossessed"}]}
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val changes = store.toolHistory(FUNDI).map { it.change }
            assertEquals(2, changes.size)

            val saw = changes.single { it.kind == ToolKind.CIRCULAR_SAW }
            assertEquals(ToolOwnership.OWNED, saw.from)
            assertEquals(ToolChangeReason.SOLD, saw.reason)
            assertEquals("bad month", saw.note)

            val router = changes.single { it.kind == ToolKind.ROUTER }
            assertEquals(ToolOwnership.NONE, router.to)
            assertNull("an unknown reason is dropped, the change is not", router.reason)
        }

    // The rule the first version of this broke: it deleted every tool row and reinserted,
    // so a tool left out of an answer vanished along with any record it had existed.
    @Test
    fun `a tool left out of the answer keeps its place`() = testApplication {
        val store = FakeFundiStore(
            existing = FundiProfile(
                workshop = Workshop(makes = "stools"),
                tools = listOf(OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED)),
            ),
        )
        val app = withFundi(store, audience = Audience.FUNDI)

        app.put("/v1/fundi/profile") {
            auth(FUNDI)
            contentType(ContentType.Application.Json)
            setBody("""{"tools":[{"kind":"drill","ownership":"owned"}]}""")
        }

        assertEquals(
            "chisels were not mentioned, so nothing happened to them",
            listOf(ToolKind.DRILL),
            store.toolHistory(FUNDI).map { it.change.kind },
        )
    }

    // rents_tools and the day rate are for the deferred marketplace, so they round-trip
    // rather than being quietly dropped — but nothing sends them to the model.
    @Test
    fun `the rental answers survive the round trip`() = testApplication {
        val profile = FundiProfile(
            workshop = Workshop(makes = "stools", rentsTools = true),
            tools = listOf(OwnedTool(ToolKind.ROUTER, ToolOwnership.OWNED, dayRateKes = 400)),
        )
        val app = withFundi(FakeFundiStore(existing = profile), audience = Audience.FUNDI)

        val body = app.get("/v1/fundi/profile") { auth(FUNDI) }.bodyAsText()

        assertTrue(body.contains("\"rents_tools\":true"))
        assertTrue(body.contains("\"day_rate_kes\":400"))
    }

    // ---------------------------------------------------------------- harness

    private fun ApplicationTestBuilder.withFundi(
        store: FundiStore,
        audience: Audience,
    ) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(FakeAuth(audience), AccessTokens(KEY)),
                fundi = Fundi(store),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(userId: String) {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(userId).token}")
    }

    private class FakeFundiStore(private val existing: FundiProfile? = null) : FundiStore {
        val saved = mutableMapOf<String, FundiProfile>()
        val history = mutableMapOf<String, List<RecordedToolChange>>()

        override suspend fun profileFor(userId: String): FundiProfile? =
            saved[userId] ?: existing

        override suspend fun saveProfile(userId: String, profile: FundiProfile) {
            // The real store derives the history from what it already holds; this only
            // needs to prove the route hands the reason down, which toolChanges then uses.
            val before = (saved[userId] ?: existing)?.tools.orEmpty()
            history[userId] = toolChanges(before, profile.tools)
                .map { RecordedToolChange(it, changedAtMillis = 0L) }
            saved[userId] = profile
        }

        override suspend fun toolHistory(userId: String) = history[userId].orEmpty()
    }

    private class FakeAuth(private val audience: Audience) : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = UserRow(
            userId, "Antony", "individual", null,
            disabled = false, isTester = false, audience = audience,
        )

        override suspend fun issueRefresh(
            userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?,
        ) = "r"

        override suspend fun findRefresh(token: String): StoredRefresh? = null
        override suspend fun revokeChain(userId: String) = 0
        override suspend fun credentialsForPhone(phone: String): Credentials? = null
        override suspend fun recordFailedSignIn(userId: String, lockFor: Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String): String? = null
        override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
        override suspend fun markAccountDeleted(userId: String) = Unit
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        const val FUNDI = "11111111-2222-3333-4444-555555555555"
    }
}

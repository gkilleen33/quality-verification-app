package com.qualityverifier.server

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ItemType
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.HelpfulAnswer
import com.qualityverifier.server.db.HelpfulStore
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/**
 * "Was this review helpful?" (V36, release scope row 7): a tester's Yes or No after a Fundi
 * Bora verdict, one per assessment, the first answer kept. Nothing here reaches Claude: the
 * Claude client in this harness fails the test if it is called.
 */
class HelpfulRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a tester's first answer is kept, and a second is told what was kept`() = testApplication {
        val store = FakeHelpful()
        val app = withHelpful(store)

        val first = app.put("/v1/fundi/sessions/$SESSION/helpful") { auth(TESTER); body(true) }
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("\"helpful\":true"))

        val second = app.put("/v1/fundi/sessions/$SESSION/helpful") { auth(TESTER); body(false) }
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue(second.bodyAsText().contains("\"helpful\":true"))
        assertEquals(mapOf(SESSION to true), store.held)
    }

    @Test
    fun `an account that is not a tester is refused before anything is stored`() = testApplication {
        val store = FakeHelpful()
        val app = withHelpful(store)
        val response = app.put("/v1/fundi/sessions/$SESSION/helpful") { auth(MAKER); body(true) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(store.held.isEmpty())
    }

    @Test
    fun `an assessment that is not the tester's, or has no verdict, is not found`() = testApplication {
        val app = withHelpful(FakeHelpful())
        val response = app.put("/v1/fundi/sessions/$NO_VERDICT/helpful") { auth(TESTER); body(true) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        val malformed = app.put("/v1/fundi/sessions/nope/helpful") { auth(TESTER); body(true) }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    // ---------------------------------------------------------------- harness

    private fun ApplicationTestBuilder.withHelpful(store: FakeHelpful) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(Accounts, AccessTokens(KEY)),
                chat = Chat(
                    Stub(ChatStore::class.java).instance,
                    BlobStore(folder.newFolder()),
                    Stub(ClaudeClient::class.java).instance,
                    Stub(PromptRepository::class.java).instance,
                    NoFeedback,
                    helpful = store,
                ),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(userId: String) {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(userId).token}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.body(helpful: Boolean) {
        contentType(ContentType.Application.Json)
        setBody("""{"helpful":$helpful}""")
    }

    /** The store's rule in memory: the tester's own [SESSION] has a verdict; the first answer stands. */
    private class FakeHelpful : HelpfulStore {
        val held = mutableMapOf<String, Boolean>()
        override suspend fun answer(userId: String, sessionId: String, helpful: Boolean): HelpfulAnswer {
            if (userId != TESTER || sessionId != SESSION) return HelpfulAnswer.NoSuchVerdict
            return HelpfulAnswer.Held(held.getOrPut(sessionId) { helpful })
        }
    }

    private object Accounts : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = UserRow(
            userId, "Wanjirũ", "individual", null, disabled = false, isTester = userId == TESTER, audience = Audience.FUNDI,
        )
        override suspend fun issueRefresh(userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?) = "r"
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
        const val TESTER = "11111111-2222-3333-4444-555555555555"
        const val MAKER = "11111111-2222-3333-4444-666666666666"
        const val SESSION = "aaaaaaaa-2222-3333-4444-555555555555"
        const val NO_VERDICT = "bbbbbbbb-2222-3333-4444-555555555555"
    }
}

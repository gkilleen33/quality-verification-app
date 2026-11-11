package com.qualityverifier.sync

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.data.sync.SyncClient
import com.qualityverifier.domain.WorkerSlot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A 426 keeps queued work queued.
 *
 * The four writes below treat a 4xx as "the server will never take this" and answer true,
 * which tells AssessmentSync to delete the local row or mark it synced. A 426 means
 * something else: this build is below the server's minimum, and the same item sent by the
 * updated app will be accepted. Answering true to it would delete an evaluator's review,
 * or mark a worker synced that the server never received, so every later session naming
 * that worker would be refused for good.
 */
class SyncClient426Test {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private object SignedIn : TokenStore {
        override fun accessToken() = "token"
        override fun refreshToken() = "refresh"
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId(): String? = "u"
        override fun isTester() = true
        override fun setTester(value: Boolean) = Unit
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun clear() = Unit
    }

    private fun client() = SyncClient(
        client = OkHttpClient(),
        tokens = TokenProvider(
            store = SignedIn,
            refresher = { RefreshOutcome.Renewed("token", 900, "refresh", "u") },
        ),
        baseUrl = server.url("/").toString(),
        json = Json { ignoreUnknownKeys = true },
    )

    private fun upgradeRequired() = MockResponse()
        .setResponseCode(426)
        .setBody("""{"error":"upgrade_required"}""")

    private val review = LocalTesterFeedback(
        sessionId = "c0ffee00-0000-4000-8000-000000000001",
        mistakes = "no",
        mistakesDetail = null,
        adviceStars = 5,
        itemQuality = 8,
        extraFeedback = null,
    )

    @Test
    fun `a review refused with 426 stays queued`() = runTest {
        server.enqueue(upgradeRequired())

        assertFalse(client().submitTesterFeedback(review))
    }

    @Test
    fun `a piece name refused with 426 stays queued`() = runTest {
        server.enqueue(upgradeRequired())

        assertFalse(client().renamePiece("c0ffee00-0000-4000-8000-000000000002", "Hotel table"))
    }

    @Test
    fun `a roster change refused with 426 is not marked synced`() = runTest {
        server.enqueue(upgradeRequired())

        assertFalse(client().upsertWorker("c0ffee00-0000-4000-8000-000000000003", "Otieno", archived = false))
    }

    @Test
    fun `a session's workers refused with 426 stay queued`() = runTest {
        server.enqueue(upgradeRequired())

        val sent = client().replaceSessionWorkers(
            "c0ffee00-0000-4000-8000-000000000001",
            listOf(WorkerSlot(workerId = "c0ffee00-0000-4000-8000-000000000003")),
        )

        assertFalse(sent)
    }

    @Test
    fun `any other 4xx is still dropped, as before`() = runTest {
        // The 426 check sits in front of the 4xx rule; it must not replace it, or a review
        // the server will never accept would be retried on every sync for ever.
        server.enqueue(MockResponse().setResponseCode(400))

        assertTrue(client().submitTesterFeedback(review))
    }
}

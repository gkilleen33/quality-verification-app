package com.qualityverifier.shop

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.sync.ReportLinkOutcome
import com.qualityverifier.data.sync.SyncClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The shop tablet's request for the customer's report link (release scope row 31), against the
 * real SyncClient and a stand-in server.
 */
class ShopReportLinkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SyncClient

    private object SignedIn : TokenStore {
        override fun accessToken() = "access"
        override fun refreshToken() = "refresh"
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId() = "shop"
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun clear() = Unit
    }

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        client = SyncClient(
            client = OkHttpClient(),
            tokens = TokenProvider(store = SignedIn, refresher = { RefreshOutcome.Rejected }),
            baseUrl = server.url("/").toString(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `row 31 - the code holds the tablet's own server address and the path the server gives`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"path":"v1/r/AbCdEfGhIjKlMnOpQrSt_-","expires_at":1794468600000,"max_downloads":3}"""),
        )

        val outcome = client.issueReportLink("50000000-0000-4000-8000-0000000000c1")

        assertEquals(
            ReportLinkOutcome.Ready(url = server.url("/").toString() + "v1/r/AbCdEfGhIjKlMnOpQrSt_-", expiresAt = 1_794_468_600_000L),
            outcome,
        )
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions/50000000-0000-4000-8000-0000000000c1/report-link", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
    }

    @Test
    fun `row 31 - a path outside the report route is never put in a code`() = runTest {
        listOf(
            """{"path":"https://elsewhere.example/v1/r/AbCdEfGhIjKlMnOpQrSt_-","expires_at":1}""",
            """{"path":"v1/sessions","expires_at":1}""",
            """{"path":"v1/r/../admin","expires_at":1}""",
            "not json",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            assertEquals(body, ReportLinkOutcome.Failed, client.issueReportLink("s"))
        }
    }

    @Test
    fun `row 31 - a refusal or no connection is a failure the customer can try again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no_such_session"}"""))
        assertEquals(ReportLinkOutcome.Failed, client.issueReportLink("s"))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ReportLinkOutcome.Failed, client.issueReportLink("s"))
        // No server at all: a stand-in started and stopped, so its port refuses.
        val gone = MockWebServer().apply { start(); shutdown() }
        val offline = SyncClient(
            client = OkHttpClient(),
            tokens = TokenProvider(store = SignedIn, refresher = { RefreshOutcome.Rejected }),
            baseUrl = gone.url("/").toString(),
            json = Json { ignoreUnknownKeys = true },
        )
        assertEquals(ReportLinkOutcome.Failed, offline.issueReportLink("s"))
    }

    @Test
    fun `row 31 - the server's cap of five links is told apart from a failure`() = runTest {
        // Trying again will not help, so the tablet says so rather than offering a retry.
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"too_many_links"}"""))
        assertEquals(ReportLinkOutcome.NoMore, client.issueReportLink("s"))
    }
}

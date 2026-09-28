package com.qualityverifier.auth

import com.qualityverifier.data.auth.AuthenticatedHttp
import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one retry, and the token it retries with.
 *
 * Untested while it lived inside SyncClient, and worth testing now that a second client
 * depends on it. The failure it guards against is not a crash: the server rotates refresh
 * tokens and reads a spent one coming back as theft, revoking every token for the
 * account. A retry that refreshed with the token it already had would sign somebody out
 * of their own phone, and would do it only under concurrency.
 */
class AuthenticatedHttpTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private class FakeStore(
        private var access: String? = "first",
    ) : TokenStore {
        var refresh: String? = "r1"
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId(): String? = "u"
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun save(
            accessToken: String,
            expiresInSeconds: Long,
            refreshToken: String,
            userId: String,
        ) {
            access = accessToken
            refresh = refreshToken
        }
        override fun clear() {
            access = null
            refresh = null
        }
    }

    private fun httpWith(store: FakeStore, refreshes: AtomicInteger) = AuthenticatedHttp(
        client = OkHttpClient(),
        tokens = TokenProvider(
            store = store,
            refresher = {
                refreshes.incrementAndGet()
                RefreshOutcome.Renewed("second", 900, "r2", "u")
            },
        ),
        tag = "test",
    )

    private fun get(token: String) = Request.Builder()
        .url(server.url("/thing"))
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()

    @Test
    fun `a good response is returned without refreshing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val refreshes = AtomicInteger()

        val response = httpWith(FakeStore(), refreshes).send(::get)

        assertEquals(200, response?.code)
        assertEquals(0, refreshes.get())
        assertEquals("Bearer first", server.takeRequest().getHeader("Authorization"))
    }

    // The point of the retry: the second attempt must carry the *new* token. Rebuilding
    // the request is why `build` is a lambda rather than a prepared Request.
    @Test
    fun `a 401 is retried with the refreshed token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val refreshes = AtomicInteger()

        val response = httpWith(FakeStore(), refreshes).send(::get)

        assertEquals(200, response?.code)
        assertEquals(1, refreshes.get())
        assertEquals("Bearer first", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer second", server.takeRequest().getHeader("Authorization"))
    }

    // Only once. A loop here would spend a rotated refresh token repeatedly, which is the
    // pattern the server treats as theft.
    @Test
    fun `a second 401 is not retried again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val refreshes = AtomicInteger()

        val response = httpWith(FakeStore(), refreshes).send(::get)

        assertEquals(401, response?.code)
        assertEquals(1, refreshes.get())
    }

    // A 5xx is the server's answer, not a token problem. Retrying it would double the
    // load on a server that is already unwell, and refresh nothing useful.
    @Test
    fun `a server error is handed back untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val refreshes = AtomicInteger()

        val response = httpWith(FakeStore(), refreshes).send(::get)

        assertEquals(503, response?.code)
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `no token at all means no request`() = runTest {
        val refreshes = AtomicInteger()
        val store = FakeStore(access = null).also { it.refresh = null }

        assertNull(httpWith(store, refreshes).send(::get))
        assertEquals(0, server.requestCount)
    }
}

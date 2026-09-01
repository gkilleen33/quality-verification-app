package com.qualityverifier.auth

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight refresh.
 *
 * The server rotates refresh tokens and treats a spent one coming back as theft, revoking
 * every token for that user. So a second concurrent refresh does not merely waste a
 * request — it signs the customer out. These tests exist because that failure would
 * arrive as a support message saying "it logged me out", with nothing in any log to
 * explain it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenProviderTest {

    @Test
    fun `a valid token is handed out without refreshing`() = runTest {
        val store = FakeStore(access = "good", expiresAt = now() + HOUR)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes), ::now)

        assertEquals("good", provider.accessToken())
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `a token close to expiry is replaced before it is refused`() = runTest {
        // Proactive: a nine-photo upload started now must not die half way through.
        val store = FakeStore(access = "stale", expiresAt = now() + 30_000)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes), ::now)

        assertEquals("fresh-1", provider.accessToken())
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `twenty concurrent callers with an expired token cause exactly one refresh`() = runTest {
        // The whole point. Two refreshes here would revoke the chain and sign the user
        // out; twenty would be a rout.
        val store = FakeStore(access = "expired", expiresAt = now() - 1)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes, delayMillis = 50), ::now)

        val results = (1..20).map { async { provider.accessToken() } }.awaitAll()

        assertEquals("exactly one refresh, however many callers", 1, refreshes.get())
        assertTrue("every caller gets a token", results.all { it == "fresh-1" })
    }

    @Test
    fun `concurrent 401 handlers all seeing the same token refresh once`() = runTest {
        // The realistic shape: the phone is reopened, several requests fire, all 401 with
        // the same access token, all call in to fix it.
        val store = FakeStore(access = "refused", expiresAt = now() + HOUR)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes, delayMillis = 50), ::now)

        val results = (1..10).map {
            async { provider.refreshAfterUnauthorized("refused") }
        }.awaitAll()

        assertEquals(1, refreshes.get())
        assertTrue(results.all { it == "fresh-1" })
    }

    @Test
    fun `a caller whose token was already replaced does not refresh again`() = runTest {
        // Arriving late with a stale complaint. Refreshing here would be the second use
        // of a token the server has just retired — the exact revocation trigger.
        val store = FakeStore(access = "already-new", expiresAt = now() + HOUR)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes), ::now)

        val token = provider.refreshAfterUnauthorized("the-old-one")

        assertEquals("already-new", token)
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `a refused refresh token clears everything, because sign-in is the only way back`() =
        runTest {
            val store = FakeStore(access = "expired", expiresAt = now() - 1)
            val provider = TokenProvider(store, { RefreshOutcome.Rejected }, ::now)

            assertNull(provider.accessToken())
            assertNull(store.refreshToken())
            assertNull(store.accessToken())
            assertTrue(!store.isSignedIn())
        }

    @Test
    fun `being offline does not sign anybody out`() = runTest {
        // Losing signal on a bus must not cost a customer their session.
        val store = FakeStore(access = "expired", expiresAt = now() - 1)
        val provider = TokenProvider(store, { RefreshOutcome.Unavailable }, ::now)

        assertNull(provider.accessToken())
        assertEquals("the refresh token must survive", "refresh-0", store.refreshToken())
        assertTrue(store.isSignedIn())
    }

    @Test
    fun `the rotated refresh token replaces the old one`() = runTest {
        // Keeping the old one would guarantee a replay on the next refresh.
        val store = FakeStore(access = "expired", expiresAt = now() - 1)
        val provider = TokenProvider(store, countingRefresher(AtomicInteger()), ::now)

        provider.accessToken()

        assertEquals("refresh-1", store.refreshToken())
        assertEquals("user-1", store.userId())
    }

    @Test
    fun `with no refresh token there is nothing to do`() = runTest {
        val store = FakeStore(access = null, refresh = null, expiresAt = 0)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes), ::now)

        assertNull(provider.accessToken())
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `a second round of refreshes happens when the new token also expires`() = runTest {
        // Single-flight must not mean once-ever.
        var clock = 1_000_000L
        val store = FakeStore(access = "expired", expiresAt = clock - 1)
        val refreshes = AtomicInteger()
        val provider = TokenProvider(store, countingRefresher(refreshes, lifetimeSeconds = 60)) { clock }

        assertEquals("fresh-1", provider.accessToken())
        clock += 120_000
        assertEquals("fresh-2", provider.accessToken())
        assertEquals(2, refreshes.get())
    }

    // ---------------------------------------------------------------- harness

    private fun now(): Long = 1_000_000L

    private fun countingRefresher(
        count: AtomicInteger,
        delayMillis: Long = 0,
        lifetimeSeconds: Long = 900,
    ): suspend (String) -> RefreshOutcome = {
        // The delay widens the window in which a second caller could slip through, so the
        // test would fail if the lock were not held across the whole refresh.
        if (delayMillis > 0) delay(delayMillis)
        val n = count.incrementAndGet()
        RefreshOutcome.Renewed("fresh-$n", lifetimeSeconds, "refresh-$n", "user-$n")
    }

    private class FakeStore(
        access: String?,
        refresh: String? = "refresh-0",
        expiresAt: Long,
    ) : TokenStore {
        private var access: String? = access
        private var refresh: String? = refresh
        private var expiresAt: Long = expiresAt
        private var user: String? = null

        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun userId() = user
        override fun accessTokenExpiresAt() = expiresAt

        override fun save(
            accessToken: String,
            expiresInSeconds: Long,
            refreshToken: String,
            userId: String,
        ) {
            access = accessToken
            refresh = refreshToken
            user = userId
            expiresAt = 1_000_000L + expiresInSeconds * 1000
        }

        override fun clear() {
            access = null
            refresh = null
            user = null
            expiresAt = 0
        }
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}

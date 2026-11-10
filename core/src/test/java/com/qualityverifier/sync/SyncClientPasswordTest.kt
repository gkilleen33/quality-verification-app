package com.qualityverifier.sync

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.sync.PasswordOutcome
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

/**
 * The phone that changes a password stays signed in.
 *
 * The server revokes every refresh token the account holds, this phone's included, and
 * hands this phone a new pair. Dropping that pair would mean the next refresh is refused
 * and the phone signs out with nothing on screen to say why.
 */
class SyncClientPasswordTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private class RecordingStore : TokenStore {
        var refresh: String? = "refresh-before"
        var access: String? = "access-before"
        var user: String? = "u"

        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId() = user
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) {
            access = accessToken
            refresh = refreshToken
            user = userId
        }
        override fun clear() {
            access = null
            refresh = null
            user = null
        }
    }

    private fun client(store: TokenStore) = SyncClient(
        client = OkHttpClient(),
        tokens = TokenProvider(
            store = store,
            refresher = { RefreshOutcome.Rejected },
        ),
        baseUrl = server.url("/").toString(),
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `the new token pair replaces the revoked one`() = runTest {
        val store = RecordingStore()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"access-after","expires_in":900,""" +
                    """"refresh_token":"refresh-after","user_id":"u"}""",
            ),
        )

        assertEquals(PasswordOutcome.Changed, client(store).changePassword("old-password", "new-password"))
        assertEquals("refresh-after", store.refresh)
        assertEquals("access-after", store.access)
    }

    @Test
    fun `an older server's empty answer still counts as changed`() = runTest {
        // Before the pair existed the server answered 204. The password has changed all the
        // same; the phone keeps what it holds and signs out at its next refresh, as it did.
        val store = RecordingStore()
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(PasswordOutcome.Changed, client(store).changePassword("old-password", "new-password"))
        assertEquals("refresh-before", store.refresh)
    }

    @Test
    fun `a pair without a user id is not saved`() = runTest {
        // Sign-in refuses the same answer. A pair saved with a blank id would leave the
        // phone unable to tell whose local data it holds.
        val store = RecordingStore()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"access-after","expires_in":900,""" +
                    """"refresh_token":"refresh-after","user_id":""}""",
            ),
        )

        assertEquals(PasswordOutcome.Changed, client(store).changePassword("old-password", "new-password"))
        assertEquals("refresh-before", store.refresh)
        assertEquals("u", store.user)
    }
}

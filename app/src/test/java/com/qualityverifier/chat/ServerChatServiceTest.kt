package com.qualityverifier.chat

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ImageBytesSource
import com.qualityverifier.data.chat.ServerChatService
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Phase 2 chat client, against a mock server.
 *
 * The behaviours worth pinning are the ones that cost money or a customer's work: not
 * uploading a photo twice, not re-sending the conversation, recovering from a 401 without
 * losing the turn, and recovering from the server having lost a photo rather than throwing
 * away a walkthrough somebody spent five minutes on.
 */
class ServerChatServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(store: TokenStore = FakeStore()): ServerChatService {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return ServerChatService(
            client = client,
            tokens = TokenProvider(store, { RefreshOutcome.Renewed("refreshed", 900, "r2", "u") }),
            images = FakeImages,
            baseUrl = server.url("/").toString(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    private fun history(vararg attachments: Attachment) = listOf(
        ChatMessage("assistant-1", Role.ASSISTANT, "Take some photos"),
        ChatMessage("turn-1", Role.USER, "here they are", attachments.toList()),
    )

    @Test
    fun `only the newest turn is sent, not the conversation`() = runTest {
        // The saving that made this whole design worthwhile: the server holds the history.
        server.enqueue(json("""{"message_id":"m1","text":"thanks"}"""))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertTrue(result is ChatResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"message_id\":\"turn-1\""))
        assertTrue(body, body.contains("here they are"))
        assertTrue("the earlier turn must not be sent", !body.contains("Take some photos"))
    }

    @Test
    fun `a photo already on the server is not uploaded again`() = runTest {
        // HEAD says present, so no PUT. This is what stops a nine-photo assessment
        // re-uploading everything on every turn.
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(json("""{"message_id":"m1","text":"ok"}"""))

        service().send("s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")))

        val methods = listOf(server.takeRequest(), server.takeRequest()).map { it.method }
        assertEquals(listOf("HEAD", "POST"), methods)
    }

    @Test
    fun `a photo the server lacks is uploaded once, then the turn is sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)) // HEAD
        server.enqueue(MockResponse().setResponseCode(201)) // PUT
        server.enqueue(json("""{"message_id":"m1","text":"ok"}"""))

        service().send("s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")))

        val requests = (1..3).map { server.takeRequest() }
        assertEquals(listOf("HEAD", "PUT", "POST"), requests.map { it.method })
        // Content-addressed: the path is the hash of the bytes, not a filename.
        assertTrue(requests[1].path!!.contains(SHA_OF_FAKE_BYTES))
    }

    @Test
    fun `a 401 refreshes once and retries the same turn`() = runTest {
        // The turn must survive: the customer has already taken the photos.
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"message_id":"m1","text":"after refresh"}"""))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertTrue(result is ChatResult.Success)
        assertEquals("after refresh", (result as ChatResult.Success).text)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer stored-access", first.getHeader("Authorization"))
        assertEquals("Bearer refreshed", second.getHeader("Authorization"))
    }

    @Test
    fun `a second 401 gives up rather than looping`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertTrue(result is ChatResult.Failure)
        assertEquals(ChatErrorKind.AUTH, (result as ChatResult.Failure).kind)
        assertEquals("exactly two attempts", 2, server.requestCount)
    }

    @Test
    fun `a 409 re-uploads the named photo and retries`() = runTest {
        // The server lost a photo, or it was never stored. Failing here would throw away
        // a walkthrough somebody spent five minutes on.
        server.enqueue(MockResponse().setResponseCode(200)) // HEAD says present
        server.enqueue(
            json("""{"error":"missing_blobs","missing":["$SHA_OF_FAKE_BYTES"]}""", code = 409)
        )
        server.enqueue(MockResponse().setResponseCode(201)) // forced PUT
        server.enqueue(json("""{"message_id":"m1","text":"second time"}"""))

        val result = service().send(
            "s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")),
        )

        assertTrue(result is ChatResult.Success)
        val methods = (1..4).map { server.takeRequest().method }
        assertEquals(listOf("HEAD", "POST", "PUT", "POST"), methods)
    }

    @Test
    fun `an unreadable photo costs the photo, not the turn`() = runTest {
        server.enqueue(json("""{"message_id":"m1","text":"ok"}"""))

        val result = service().send(
            "s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/missing.jpg")),
        )

        assertTrue(result is ChatResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"blobs\":[]"))
    }

    @Test
    fun `server error wording never reaches the customer`() = runTest {
        // The server's own text is written for an operator and can name a model or a quota.
        server.enqueue(
            json("""{"error":"upstream_unavailable","detail":"quota exhausted on account X"}""", code = 502)
        )

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        val failure = result as ChatResult.Failure
        assertEquals(ChatErrorKind.SERVER, failure.kind)
        assertTrue(failure.message, !failure.message.contains("quota"))
        assertTrue(failure.message, failure.message.contains("try again"))
    }

    @Test
    fun `statuses map to something a person can act on`() = runTest {
        val cases = mapOf(
            429 to ChatErrorKind.RATE_LIMIT,
            404 to ChatErrorKind.REQUEST,
            413 to ChatErrorKind.REQUEST,
            503 to ChatErrorKind.SERVER,
        )
        for ((status, kind) in cases) {
            server.enqueue(MockResponse().setResponseCode(status))
            val result = service().send("s1", ItemType.WOODEN_STOOL, history())
            assertEquals("for $status", kind, (result as ChatResult.Failure).kind)
        }
    }

    @Test
    fun `with no credentials the customer is asked to sign in, and nothing is sent`() = runTest {
        val result = service(FakeStore(access = null, refresh = null))
            .send("s1", ItemType.WOODEN_STOOL, history())

        assertEquals(ChatErrorKind.AUTH, (result as ChatResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a turn with no customer message is refused before any request`() = runTest {
        val result = service().send(
            "s1", ItemType.WOODEN_STOOL,
            listOf(ChatMessage("a", Role.ASSISTANT, "Welcome")),
        )

        assertEquals(ChatErrorKind.REQUEST, (result as ChatResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no system prompt is ever sent`() = runTest {
        // The server assembles it. A field here would be a way for a client to spend our
        // budget on a prompt of its own choosing.
        server.enqueue(json("""{"message_id":"m1","text":"ok"}"""))

        service().send("s1", ItemType.WOODEN_STOOL, history())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, !body.contains("system"))
        assertTrue(body, !body.contains("model"))
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private object FakeImages : ImageBytesSource {
        override fun bytesForUpload(file: File): ByteArray? =
            if (file.name.contains("missing")) null else FAKE_BYTES
    }

    private class FakeStore(
        private var access: String? = "stored-access",
        private var refresh: String? = "stored-refresh",
    ) : TokenStore {
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun userId() = "user-1"
        override fun accessTokenExpiresAt() = System.currentTimeMillis() + 60 * 60 * 1000
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

    private companion object {
        val FAKE_BYTES = byteArrayOf(1, 2, 3, 4)

        /** sha256 of FAKE_BYTES, so the test asserts the real content address. */
        const val SHA_OF_FAKE_BYTES =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    }
}

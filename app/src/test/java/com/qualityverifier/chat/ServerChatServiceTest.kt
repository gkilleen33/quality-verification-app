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
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.Usage
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

    /** What startOf() would return. Null by default; set by the tests that care. */
    private var sessionStart: SessionStart? = null

    private fun service(store: TokenStore = FakeStore()): ServerChatService {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return ServerChatService(
            client = client,
            tokens = TokenProvider(store, { RefreshOutcome.Renewed("refreshed", 900, "r2", "u") }),
            images = FakeImages,
            sessionStart = { sessionStart },
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
        server.enqueue(sse("thanks"))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertTrue(result is ChatResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"message_id\":\"turn-1\""))
        assertTrue(body, body.contains("here they are"))
        assertTrue("the earlier turn must not be sent", !body.contains("Take some photos"))
    }

    @Test
    fun `the reply arrives in pieces, in order`() = runTest {
        // The whole point of the feature: something on screen in about a second instead
        // of a blank wait as long as the answer takes to write.
        server.enqueue(sse("This table ", "has a loose ", "back leg."))
        val seen = mutableListOf<String>()

        val result = service().send("s1", ItemType.WOODEN_STOOL, history()) { seen += it }

        assertEquals(listOf("This table ", "has a loose ", "back leg."), seen)
        assertEquals("This table has a loose back leg.", (result as ChatResult.Success).text)
        assertTrue(server.takeRequest().path, true)
    }

    @Test
    fun `the turn goes to the streaming route`() = runTest {
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history())

        assertEquals("/v1/chat/stream", server.takeRequest().path)
    }

    @Test
    fun `what gets stored is what the server says, not what was accumulated`() = runTest {
        // The deltas are for the eyes; the last event is the record. If a delta is lost
        // the wait looks slightly wrong, and the stored turn still matches the server's
        // copy of the conversation — which is the copy every later turn is built from.
        server.enqueue(sse("partial ", "text", whole = "the whole reply"))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertEquals("the whole reply", (result as ChatResult.Success).text)
    }

    @Test
    fun `an error event mid-stream is a failure, not a short answer`() = runTest {
        // Half a verdict must not be handed back as if it were a verdict.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: delta\ndata: {\"text\":\"Looking at the join\"}\n\n" +
                        "event: error\ndata: {\"error\":\"upstream_unavailable\"}\n\n"
                ),
        )
        val seen = mutableListOf<String>()

        val result = service().send("s1", ItemType.WOODEN_STOOL, history()) { seen += it }

        assertEquals(listOf("Looking at the join"), seen)
        assertEquals(ChatErrorKind.SERVER, (result as ChatResult.Failure).kind)
    }

    @Test
    fun `a stream that stops without finishing is retryable`() = runTest {
        // A dropped connection on a Kampala mobile network. The customer's turn is still
        // stored on both sides, so the wording has to point at retrying rather than at
        // starting the assessment again.
        server.enqueue(truncatedSse("The frame looks "))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        val failure = result as ChatResult.Failure
        assertEquals(ChatErrorKind.NETWORK, failure.kind)
        assertTrue(failure.message, failure.message.contains("retry"))
    }

    @Test
    fun `an older server without the streaming route still answers`() = runTest {
        // A phone updated before the server is. An unmatched route in Ktor answers 404
        // with an empty body, and that — and only that — is worth a second attempt.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json("""{"message_id":"m1","text":"from the old route"}"""))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertEquals("from the old route", (result as ChatResult.Success).text)
        assertEquals(
            listOf("/v1/chat/stream", "/v1/chat"),
            listOf(server.takeRequest().path, server.takeRequest().path),
        )
    }

    @Test
    fun `a 404 that names an error is not retried on the old route`() = runTest {
        // "This session is not yours" is also a 404. Retrying it on /v1/chat would earn
        // the same answer from the other route and spend a second round trip to do it.
        server.enqueue(json("""{"error":"no_such_session"}""", code = 404))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertEquals(ChatErrorKind.REQUEST, (result as ChatResult.Failure).kind)
        assertEquals("one attempt only", 1, server.requestCount)
    }

    @Test
    fun `a photo already on the server is not uploaded again`() = runTest {
        // HEAD says present, so no PUT. This is what stops a nine-photo assessment
        // re-uploading everything on every turn.
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")))

        val methods = listOf(server.takeRequest(), server.takeRequest()).map { it.method }
        assertEquals(listOf("HEAD", "POST"), methods)
    }

    @Test
    fun `a photo the server lacks is uploaded once, then the turn is sent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)) // HEAD
        server.enqueue(MockResponse().setResponseCode(201)) // PUT
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")))

        val requests = (1..3).map { server.takeRequest() }
        assertEquals(listOf("HEAD", "PUT", "POST"), requests.map { it.method })
        // Content-addressed: the path is the hash of the bytes, not a filename.
        assertTrue(requests[1].path!!.contains(SHA_OF_FAKE_BYTES))
    }

    @Test
    fun `every blob request carries the token, not just the turn`() = runTest {
        // The gap that let a real bug through: the blob routes are behind the same
        // authentication as everything else, and asserting the header only on the POST
        // meant HEAD and PUT could go out bare. On a device that surfaced as "please sign
        // in again" the moment a customer submitted photos.
        server.enqueue(MockResponse().setResponseCode(404)) // HEAD
        server.enqueue(MockResponse().setResponseCode(201)) // PUT
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")))

        val requests = (1..3).map { server.takeRequest() }
        assertEquals(listOf("HEAD", "PUT", "POST"), requests.map { it.method })
        requests.forEach { request ->
            assertEquals(
                "${request.method} went out without a token",
                "Bearer stored-access",
                request.getHeader("Authorization"),
            )
        }
    }

    @Test
    fun `a 401 refreshes once and retries the same turn`() = runTest {
        // The turn must survive: the customer has already taken the photos.
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(sse("after refresh"))

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
        server.enqueue(sse("second time"))

        val result = service().send(
            "s1", ItemType.WOODEN_STOOL, history(Attachment("a", "/tmp/one.jpg")),
        )

        assertTrue(result is ChatResult.Success)
        val methods = (1..4).map { server.takeRequest().method }
        assertEquals(listOf("HEAD", "POST", "PUT", "POST"), methods)
    }

    @Test
    fun `an unreadable photo costs the photo, not the turn`() = runTest {
        server.enqueue(sse("ok"))

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
    fun `the daily limit says tomorrow, not in a moment`() = runTest {
        // Both are 429. Telling somebody who is out of assessments for the day to "try
        // again in a moment" sends them back into a workshop to tap retry until they give
        // up, so the two cases must not share a message.
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":"daily_limit_reached","detail":"limit is 20 per day"}"""),
        )

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        val message = (result as ChatResult.Failure).message
        assertEquals(ChatErrorKind.RATE_LIMIT, result.kind)
        assertTrue(message, message.contains("20"))
        assertTrue(message, message.contains("tomorrow"))
        assertTrue("must not suggest retrying now", !message.contains("in a moment"))
    }

    @Test
    fun `a transient 429 still says try again shortly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate_limited"}"""))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        val message = (result as ChatResult.Failure).message
        assertTrue(message, message.contains("moment"))
        assertTrue("must not claim a daily limit", !message.contains("tomorrow"))
    }

    @Test
    fun `a daily limit with no number still reads as a sentence`() = runTest {
        // The wording is parsed out of an operator-facing detail string, so it can change
        // on the server without this app being rebuilt. Losing the digit must not lose
        // the message.
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"error":"daily_limit_reached"}"""),
        )

        val message = (service().send("s1", ItemType.WOODEN_STOOL, history())
            as ChatResult.Failure).message

        assertTrue(message, message.contains("tomorrow"))
        assertTrue(message, message.isNotBlank())
    }

    @Test
    fun `statuses map to something a person can act on`() = runTest {
        val cases = mapOf(
            429 to ChatErrorKind.RATE_LIMIT,
            // Bodied on purpose. A 404 with nothing in it means the streaming route is
            // not there and is retried on the old one — see the fallback test below.
            404 to ChatErrorKind.REQUEST,
            413 to ChatErrorKind.REQUEST,
            503 to ChatErrorKind.SERVER,
        )
        for ((status, kind) in cases) {
            server.enqueue(
                MockResponse().setResponseCode(status).setBody("""{"error":"refused"}"""),
            )
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
    fun `the session's own metadata travels with the turn`() = runTest {
        // Without this the server's copy of a session has no link to the assessment it
        // followed and no record of the intake, which is most of what makes the data
        // useful for research later.
        sessionStart = SessionStart(
            itemType = ItemType.WOODEN_STOOL,
            previousSessionId = "11111111-2222-3333-4444-555555555555",
            intake = AssessmentContext(
                language = AssessmentLanguage.SWAHILI,
                ownership = Ownership.BUYING,
                usage = Usage.DAILY,
                depth = AssessmentDepth.FULL,
            ),
        )
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(body, body.contains("sw-buying-daily-full"))
    }

    @Test
    fun `a session with no metadata still sends a valid turn`() = runTest {
        // An assessment started from the grid has no previous session, and an intake
        // handed over to the assistant has no answers to carry. Neither is an error.
        sessionStart = SessionStart(ItemType.WOODEN_STOOL, null, null)
        server.enqueue(sse("ok"))

        val result = service().send("s1", ItemType.WOODEN_STOOL, history())

        assertTrue(result is ChatResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, !body.contains("previous_session_id"))
        assertTrue(body, !body.contains("intake_answers"))
    }

    @Test
    fun `no system prompt is ever sent`() = runTest {
        // The server assembles it. A field here would be a way for a client to spend our
        // budget on a prompt of its own choosing.
        server.enqueue(sse("ok"))

        service().send("s1", ItemType.WOODEN_STOOL, history())

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, !body.contains("system"))
        assertTrue(body, !body.contains("model"))
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /**
     * A streamed reply, in the shape /v1/chat/stream sends: some deltas, then `done`
     * carrying the whole thing.
     *
     * [whole] defaults to the deltas joined, which is the normal case. Passing something
     * different is how the tests pin the rule that the client stores what `done` says
     * rather than its own accumulation.
     */
    private fun sse(vararg deltas: String, whole: String? = null, messageId: String = "m1") =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                buildString {
                    deltas.forEach { append("event: delta\ndata: {\"text\":\"$it\"}\n\n") }
                    val text = whole ?: deltas.joinToString("")
                    append("event: done\n")
                    append("data: {\"message_id\":\"$messageId\",\"text\":\"$text\"}\n\n")
                }
            )

    /** A stream that starts and stops: deltas, then the socket closes with no `done`. */
    private fun truncatedSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            buildString {
                deltas.forEach { append("event: delta\ndata: {\"text\":\"$it\"}\n\n") }
            }
        )

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
        private var tester = false
        override fun isTester() = tester
        override fun setTester(value: Boolean) { tester = value }

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

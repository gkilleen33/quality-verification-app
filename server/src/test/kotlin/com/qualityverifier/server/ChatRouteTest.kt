package com.qualityverifier.server

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.chat.UpstreamError
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.routes.ChatRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * The chat route's decisions, none of which need Postgres to state.
 *
 * Every one of these is a case where getting it wrong costs money or leaks data rather
 * than throwing: paying twice for a retried turn, assessing eight photos as if they were
 * nine, or letting one customer post into another's assessment.
 */
class ChatRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an account at its daily limit is refused before Claude is called`() = testApplication {
        // The point of the quota is the request that never happens. A 429 after paying for
        // the assessment would be a worse outcome than no quota at all.
        val store = FakeChatStore(access = SessionAccess.DailyLimitReached(20))
        val claude = FakeClaude(ClaudeResult.Success("should never be reached", TokenUsage(0, 0, 0, 0), "m"))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(response.bodyAsText().contains("daily_limit_reached"))
        assertEquals("nothing may be spent on a refused assessment", 0, claude.calls)
        assertEquals(0, store.assistantTurns)
    }

    @Test
    fun `the refusal names the limit, so the app can say the number`() = testApplication {
        val app = withChat(
            FakeChatStore(access = SessionAccess.DailyLimitReached(7)),
            FakeClaude(ClaudeResult.Success("x", TokenUsage(0, 0, 0, 0), "m")),
        )

        val body = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()

        assertTrue(body, body.contains("7"))
    }

    @Test
    fun `an assessment already under way is never refused`() = testApplication {
        // created = false means the session existed. Whatever today's count is, the earlier
        // turns are already paid for and stopping here would waste them and strand somebody
        // mid-assessment in a workshop.
        val store = FakeChatStore(access = SessionAccess.Ok(created = false))
        val claude = FakeClaude(ClaudeResult.Success("carrying on", TokenUsage(10, 5, 0, 0), "m"))
        val app = withChat(store, claude, dailyLimit = 1)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, claude.calls)
    }

    @Test
    fun `both limits reach the store, so it can pick by account`() = testApplication {
        // The choice is made inside the transaction that counts, because that is where the
        // account is already being read — the route holds a token, not a profile. So the
        // route's job is only to hand both numbers down, and this checks it does.
        val store = FakeChatStore()
        val app = withChat(
            store,
            FakeClaude(ClaudeResult.Success("ok", TokenUsage(0, 0, 0, 0), "m")),
            dailyLimit = 20,
            testerLimit = 50,
        )

        app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(20, store.sawDailyLimit)
        assertEquals(50, store.sawTesterDailyLimit)
    }

    @Test
    fun `the configured limit is what reaches the store`() = testApplication {
        // Guards the plumbing rather than the policy: a limit that stops at the route and
        // never arrives is a quota that silently does nothing.
        val store = FakeChatStore()
        val app = withChat(store, FakeClaude(ClaudeResult.Success("ok", TokenUsage(0, 0, 0, 0), "m")), dailyLimit = 3)

        app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(3, store.sawDailyLimit)
    }

    @Test
    fun `the default limit is twenty`() {
        // Written down in a test because it is a spend decision, not an implementation
        // detail: changing it should require saying so here.
        assertEquals(20, Config.DEFAULT_DAILY_ASSESSMENT_LIMIT)
        assertEquals(
            20,
            Config.fromEnvironment { null }.dailyAssessmentLimit,
        )
    }

    @Test
    fun `the limit can be overridden, and disabled outright`() {
        assertEquals(
            5,
            Config.fromEnvironment { if (it == "KAGUA_DAILY_ASSESSMENT_LIMIT") "5" else null }
                .dailyAssessmentLimit,
        )
        // Zero is the documented escape hatch for a demo. It must not silently fall back
        // to the default, which would make an intentional override look applied.
        assertEquals(
            0,
            Config.fromEnvironment { if (it == "KAGUA_DAILY_ASSESSMENT_LIMIT") "0" else null }
                .dailyAssessmentLimit,
        )
        // Nonsense falls back rather than disabling the quota: a typo in a unit file
        // should not quietly uncap spending.
        assertEquals(
            20,
            Config.fromEnvironment { if (it == "KAGUA_DAILY_ASSESSMENT_LIMIT") "twenty" else null }
                .dailyAssessmentLimit,
        )
    }

    @Test
    fun `a turn is stored, sent upstream, and the reply returned`() = testApplication {
        val store = FakeChatStore()
        val claude = FakeClaude(ClaudeResult.Success("Here is the plan", TokenUsage(10, 5, 0, 900), "m"))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText(), response.bodyAsText().contains("Here is the plan"))
        assertEquals(1, claude.calls)
        assertEquals(1, store.assistantTurns)
        // Written on success too, not only on failure: this is what per-user quotas and
        // the bill are reconciled from.
        assertEquals(1, store.usageRows)
    }

    @Test
    fun `a streamed turn arrives as delta events and is stored once`() = testApplication {
        val store = FakeChatStore()
        val claude = StreamingClaude(listOf("This table ", "wobbles ", "badly."))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat/stream") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // Three increments, then the whole thing once.
        assertEquals("three deltas expected in $body", 3, body.split("event: delta").size - 1)
        assertTrue(body, body.contains("""data: {"text":"This table "}"""))
        assertTrue(body, body.contains("event: done"))
        assertTrue(body, body.contains("This table wobbles badly."))
        // Stored exactly once, at the end — not a row per delta.
        assertEquals(1, store.assistantTurns)
        assertEquals(1, store.usageRows)
    }

    @Test
    fun `the first piece arrives before the reply is finished`() = testApplication {
        // The one property the whole feature rests on, and the one every other test here
        // is blind to: reading a completed body cannot tell a stream from a buffer. The
        // upstream is held after its first delta, so if this text can be read while it is
        // still held, nothing between here and the socket is waiting for the end.
        val gate = CompletableDeferred<Unit>()
        val app = withChat(FakeChatStore(), GatedClaude(gate))

        app.preparePost("/v1/chat/stream") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val early = StringBuilder()
            // Read only as far as the first complete event. Anything more would block on
            // the upstream that is deliberately still held.
            while (!early.contains("The first thing I notice")) {
                val line = channel.readUTF8Line() ?: break
                early.appendLine(line)
            }

            assertTrue(
                "the first delta should be readable while the reply is still being written",
                early.contains("The first thing I notice"),
            )
            assertTrue("the reply cannot be finished yet", !early.contains("event: done"))

            gate.complete(Unit)

            val rest = StringBuilder()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                rest.appendLine(line)
            }
            assertTrue(rest.toString(), rest.contains("event: done"))
            assertTrue(rest.toString(), rest.contains("is the back leg."))
        }
    }

    @Test
    fun `the streamed response tells nginx not to buffer it`() = testApplication {
        // Without this the proxy holds the whole stream until it is finished, which is
        // the blank wait streaming exists to remove — with more moving parts than before.
        val app = withChat(FakeChatStore(), StreamingClaude(listOf("ok")))

        val response = app.post("/v1/chat/stream") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals("no", response.headers["X-Accel-Buffering"])
        assertTrue(
            response.headers["Content-Type"].orEmpty(),
            response.headers["Content-Type"].orEmpty().startsWith("text/event-stream"),
        )
    }

    @Test
    fun `a delta carrying newlines stays one event`() = testApplication {
        // SSE is a line protocol and a verdict has paragraphs in it. Sent raw, one delta
        // would arrive as several data lines and the client would have to guess.
        val app = withChat(FakeChatStore(), StreamingClaude(listOf("Two things.\n\nFirst,")))

        val body = app.post("/v1/chat/stream") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()

        assertTrue(body, body.contains("""data: {"text":"Two things.\n\nFirst,"}"""))
        assertEquals("one delta only", 1, body.split("event: delta").size - 1)
    }

    @Test
    fun `a stream that fails part way stores nothing but still records the spend`() =
        testApplication {
            // Storing the fragment would make the truncation permanent: the message id is
            // the idempotency key, so every retry would replay half a verdict instead of
            // producing a whole one.
            val store = FakeChatStore()
            // Two pieces get through, the third never arrives.
            val claude = StreamingClaude(
                listOf("The back leg ", "is ", "loose at the joint."), failAfter = 2,
            )
            val app = withChat(store, claude)

            val body = app.post("/v1/chat/stream") {
                auth(); contentType(ContentType.Application.Json)
                setBody(request())
            }.bodyAsText()

            assertTrue(body, body.contains("event: error"))
            assertTrue(body, !body.contains("event: done"))
            assertEquals("nothing may be stored", 0, store.assistantTurns)
            // Tokens generated before it broke were billed to us either way.
            assertEquals(1, store.usageRows)
        }

    @Test
    fun `the streaming route enforces the daily limit, in JSON`() = testApplication {
        // The check must not be reachable on one route and not the other. And a refusal
        // that happens before the stream starts is an ordinary status code, not an event:
        // there is no stream yet to put an error into.
        val store = FakeChatStore(access = SessionAccess.DailyLimitReached(limit = 20))
        val claude = StreamingClaude(listOf("should not run"))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat/stream") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(response.bodyAsText(), response.bodyAsText().contains("daily_limit_reached"))
        assertEquals("nothing may be spent", 0, claude.calls)
    }

    @Test
    fun `a retried turn is replayed on the streaming route without paying again`() =
        testApplication {
            val store = FakeChatStore(
                turnAlreadyStored = true,
                storedReply = StoredReply("m-stored", "the stored answer"),
            )
            val claude = StreamingClaude(listOf("should not run"))
            val app = withChat(store, claude)

            val body = app.post("/v1/chat/stream") {
                auth(); contentType(ContentType.Application.Json)
                setBody(request())
            }.bodyAsText()

            assertTrue(body, body.contains("event: done"))
            assertTrue(body, body.contains("the stored answer"))
            assertTrue(body, body.contains("m-stored"))
            assertEquals(0, claude.calls)
        }

    @Test
    fun `a retried turn returns the stored reply without paying again`() = testApplication {
        // The phone retries when a response is lost. Calling upstream again would charge
        // for a full vision request to produce an answer we already have.
        val store = FakeChatStore(turnAlreadyStored = true, storedReply = StoredReply("m1", "already answered"))
        val claude = FakeClaude(ClaudeResult.Success("should not be called", TokenUsage(), null))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json); setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("already answered"))
        assertEquals("upstream must not be called again", 0, claude.calls)
    }

    @Test
    fun `a retried turn with no stored reply is attempted again`() = testApplication {
        // The other half: the turn is stored but the earlier attempt failed upstream, so
        // the customer is owed an answer rather than an echo.
        val store = FakeChatStore(turnAlreadyStored = true, storedReply = null)
        val claude = FakeClaude(ClaudeResult.Success("second attempt", TokenUsage(), null))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json); setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, claude.calls)
    }

    @Test
    fun `posting into another user's session answers 404, not 403`() = testApplication {
        // 403 would confirm the id exists, which is enough to enumerate sessions.
        val store = FakeChatStore(access = SessionAccess.NotYours)
        val claude = FakeClaude(ClaudeResult.Success("x", TokenUsage(), null))
        val app = withChat(store, claude)

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json); setBody(request())
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, claude.calls)
    }

    @Test
    fun `a missing photo is refused before anything is spent upstream`() = testApplication {
        // Dropping it silently would produce an assessment of eight photos that reads as
        // if it were of nine, and the customer would never learn which was ignored.
        val store = FakeChatStore()
        val claude = FakeClaude(ClaudeResult.Success("x", TokenUsage(), null))
        val app = withChat(store, claude)

        val absent = "a".repeat(64)
        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request().copy(blobs = listOf(absent)))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.bodyAsText()
        assertTrue(body, body.contains("missing_blobs"))
        assertTrue("the phone needs to know which", body.contains(absent))
        assertEquals(0, claude.calls)
    }

    @Test
    fun `upstream failures map to a status the phone can act on, and are still billed`() {
        val cases = mapOf(
            UpstreamError.RATE_LIMIT to HttpStatusCode.TooManyRequests,
            UpstreamError.OVERLOADED to HttpStatusCode.ServiceUnavailable,
            UpstreamError.AUTH to HttpStatusCode.ServiceUnavailable,
            UpstreamError.NETWORK to HttpStatusCode.GatewayTimeout,
            UpstreamError.SERVER to HttpStatusCode.BadGateway,
            UpstreamError.REQUEST to HttpStatusCode.InternalServerError,
        )
        // One application per case: a testApplication block installs its module once.
        for ((error, expected) in cases) {
            testApplication {
                val store = FakeChatStore()
                val app = withChat(
                    store,
                    FakeClaude(ClaudeResult.Failure(error, "upstream said something private")),
                )
                val response = app.post("/v1/chat") {
                    auth(); contentType(ContentType.Application.Json); setBody(request())
                }
                assertEquals("for $error", expected, response.status)
                // A failed call can still have burned tokens.
                assertEquals("for $error", 1, store.usageRows)
                // The upstream message can name a model, a quota or an account.
                assertTrue(
                    "leaked the upstream message for $error",
                    !response.bodyAsText().contains("something private"),
                )
            }
        }
    }

    @Test
    fun `a verdict in the reply is recorded so the reports list can badge it`() = testApplication {
        val store = FakeChatStore()
        val reply = """
            The frame is sound.

            ```qv-verdict
            {"verdict":"fair","language":"sw","headline":"Imara kwa ujumla","defects":[]}
            ```
        """.trimIndent()
        val app = withChat(store, FakeClaude(ClaudeResult.Success(reply, TokenUsage(), null)))

        app.post("/v1/chat") { auth(); contentType(ContentType.Application.Json); setBody(request()) }

        assertEquals("fair", store.lastVerdictLevel)
        assertEquals("sw", store.lastVerdictLanguage)
        // The headline is a better preview than the opening words of the prose.
        assertEquals("Imara kwa ujumla", store.lastPreview)
    }

    @Test
    fun `an unknown item type is refused rather than guessed`() = testApplication {
        val app = withChat(FakeChatStore(), FakeClaude(ClaudeResult.Success("x", TokenUsage(), null)))

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request().copy(itemTypeId = "wooden-spaceship"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a turn with neither text nor a photo is refused`() = testApplication {
        val app = withChat(FakeChatStore(), FakeClaude(ClaudeResult.Success("x", TokenUsage(), null)))

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request().copy(text = "", blobs = emptyList()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `chat requires a token`() = testApplication {
        val app = withChat(FakeChatStore(), FakeClaude(ClaudeResult.Success("x", TokenUsage(), null)))

        val response = app.post("/v1/chat") {
            contentType(ContentType.Application.Json); setBody(request())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `the client cannot supply its own system prompt`() = testApplication {
        // There is no field for it, and this asserts the prompt the route used came from
        // the repository — a client able to substitute one would spend our budget on it.
        val prompts = RecordingPrompts()
        val claude = FakeClaude(ClaudeResult.Success("x", TokenUsage(), null))
        val app = withChat(FakeChatStore(), claude, prompts)

        app.post("/v1/chat") { auth(); contentType(ContentType.Application.Json); setBody(request()) }

        assertEquals("PROMPT FOR wooden-table", claude.lastSystemPrompt)
        assertEquals(ItemType.WOODEN_TABLE, prompts.asked)
    }

    // ---------------------------------------------------------------- harness

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(USER).token}")
    }

    private fun request() = ChatRequest(
        sessionId = UUID.randomUUID().toString(),
        itemTypeId = "wooden-table",
        messageId = UUID.randomUUID().toString(),
        text = "I am buying this.",
    )

    private fun ApplicationTestBuilder.withChat(
        store: ChatStore,
        // The interface, not FakeClaude: the streaming tests supply a client that hands
        // its answer over in pieces.
        claude: ClaudeClient,
        prompts: PromptRepository = RecordingPrompts(),
        dailyLimit: Int = Config.DEFAULT_DAILY_ASSESSMENT_LIMIT,
        testerLimit: Int = Config.DEFAULT_TESTER_DAILY_ASSESSMENT_LIMIT,
    ) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(NoAuthStore, AccessTokens(KEY)),
                chat = Chat(
                    store, BlobStore(folder.newFolder()), claude, prompts, NoFeedback,
                    dailyAssessmentLimit = dailyLimit,
                    testerDailyAssessmentLimit = testerLimit,
                ),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private class RecordingPrompts : PromptRepository {
        var asked: ItemType? = null
        override suspend fun systemPromptFor(itemType: ItemType): String {
            asked = itemType
            return "PROMPT FOR ${itemType.id}"
        }
        override suspend fun clearCache() = Unit
    }

    private class FakeClaude(private val result: ClaudeResult) : ClaudeClient {
        var calls = 0
            private set
        var lastSystemPrompt: String? = null
            private set

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
        ): ClaudeResult {
            calls++
            lastSystemPrompt = systemPrompt
            return result
        }
    }

    /**
     * A client that hands over its answer in pieces, as the real one does.
     *
     * [FakeClaude] exercises the default [ClaudeClient.stream], which delivers everything
     * in one delta — right for the route tests that only care that a reply came back, and
     * useless for pinning the event shape.
     */
    private class StreamingClaude(
        private val pieces: List<String>,
        private val failAfter: Int? = null,
    ) : ClaudeClient {
        var calls = 0
            private set

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
        ): ClaudeResult = error("these tests stream")

        override suspend fun stream(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
            onDelta: suspend (String) -> Unit,
        ): ClaudeResult {
            calls++
            pieces.forEachIndexed { index, piece ->
                if (failAfter != null && index >= failAfter) {
                    return ClaudeResult.Failure(
                        UpstreamError.NETWORK, "The answer was cut off",
                        usage = TokenUsage(2, 40, 0, 8340),
                    )
                }
                onDelta(piece)
            }
            return ClaudeResult.Success(pieces.joinToString(""), TokenUsage(2, 90, 0, 8340), "m")
        }
    }

    /**
     * Emits one piece, waits to be released, then emits the rest.
     *
     * Exists for one test: proving the first piece reaches the client while the reply is
     * still being written. Every other streaming test reads a finished body, which cannot
     * tell a real stream from a buffered one.
     */
    private class GatedClaude(private val gate: CompletableDeferred<Unit>) : ClaudeClient {
        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
        ): ClaudeResult = error("this test streams")

        override suspend fun stream(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
            onDelta: suspend (String) -> Unit,
        ): ClaudeResult {
            onDelta("The first thing I notice")
            gate.await()
            onDelta(" is the back leg.")
            return ClaudeResult.Success(
                "The first thing I notice is the back leg.", TokenUsage(2, 90, 0, 8340), "m",
            )
        }
    }

    private class FakeChatStore(
        private val access: SessionAccess = SessionAccess.Ok(created = true),
        private val turnAlreadyStored: Boolean = false,
        private val storedReply: StoredReply? = null,
    ) : ChatStore {
        var assistantTurns = 0
            private set
        var usageRows = 0
            private set
        var lastVerdictLevel: String? = null
            private set
        var lastVerdictLanguage: String? = null
            private set
        var lastPreview: String? = null
            private set

        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String,
            previousSessionId: String?, intakeAnswers: String?, promptSha: String?,
            dailyLimit: Int, testerDailyLimit: Int,
        ) = access.also {
            sawDailyLimit = dailyLimit
            sawTesterDailyLimit = testerDailyLimit
        }

        /** What the route passed down, so a test can prove the config reaches the store. */
        var sawDailyLimit: Int? = null
            private set
        var sawTesterDailyLimit: Int? = null
            private set

        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>,
        ) = !turnAlreadyStored

        override suspend fun replyAfter(sessionId: String, userMessageId: String) = storedReply

        override suspend fun history(sessionId: String, blobPath: (String) -> String) =
            listOf(ChatMessage("m", Role.USER, "I am buying this."))

        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String,
            verdictLevelId: String?, verdictLanguage: String?,
        ): String {
            assistantTurns++
            lastPreview = preview
            lastVerdictLevel = verdictLevelId
            lastVerdictLanguage = verdictLanguage
            return "assistant-1"
        }

        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?,
            httpStatus: Int?, latencyMillis: Long, errorKind: String?,
        ) {
            usageRows++
        }

        override suspend fun sessionsFor(userId: String) = emptyList<com.qualityverifier.server.db.SessionRow>()
        override suspend fun sessionDetail(userId: String, sessionId: String) = null
        override suspend fun markClientDeleted(userId: String, sessionId: String) = false
        override suspend fun blobBelongsTo(userId: String, sha: String) = false
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        val USER: String = UUID.randomUUID().toString()
    }
}

/** Auth is installed so the chat routes can authenticate; none of these tests use it. */
private object NoAuthStore : com.qualityverifier.server.db.AuthStore {
    override suspend fun register(registration: com.qualityverifier.server.db.Registration) =
        com.qualityverifier.server.db.RegisterOutcome.InviteUnusable
    override suspend fun findUser(userId: String) = null
    override suspend fun issueRefresh(
        userId: String, token: String, expiresAt: java.time.Instant,
        userAgent: String?, replaces: String?,
    ) = "unused"
    override suspend fun findRefresh(token: String) = null
    override suspend fun revokeChain(userId: String) = 0
    override suspend fun credentialsForPhone(phone: String) = null
    override suspend fun recordFailedSignIn(userId: String, lockFor: java.time.Duration, threshold: Int) = 0
    override suspend fun clearFailedSignIns(userId: String) = Unit
    override suspend fun passwordHashFor(userId: String): String? = null
    override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
    override suspend fun markAccountDeleted(userId: String) = Unit
}

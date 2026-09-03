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
import com.qualityverifier.server.routes.SessionLocation
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.routes.ChatRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
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
    fun `an assessment's location is recorded, and never sent upstream`() = testApplication {
        val store = FakeChatStore()
        val claude = FakeClaude(ClaudeResult.Success("Here is the plan", TokenUsage(), "m"))
        val app = withChat(store, claude)

        app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request(latitude = 0.3476, longitude = 32.5825, accuracyM = 12.0))
        }

        assertEquals(1, store.locations.size)
        assertEquals(0.3476, store.locations.single().latitude, 1e-9)
        assertEquals(32.5825, store.locations.single().longitude, 1e-9)
        assertEquals(12.0, store.locations.single().accuracyMetres, 1e-9)
        // The guarantee that matters. history() selects id, role, text and created_at
        // from messages; a column on sessions is not in that query, so a coordinate
        // cannot reach the model. Asserted against the prompt and the conversation the
        // client was actually given.
        assertTrue(claude.lastSystemPrompt.orEmpty(), !claude.lastSystemPrompt.orEmpty().contains("32.58"))
        assertTrue(claude.lastHistoryText, !claude.lastHistoryText.contains("32.58"))
        assertTrue(claude.lastHistoryText, !claude.lastHistoryText.contains("0.3476"))
    }

    @Test
    fun `a turn with no location records nothing`() = testApplication {
        // The common case: switched off, or no fix indoors. It must not write a row and
        // must not fail the turn.
        val store = FakeChatStore()
        val app = withChat(store, FakeClaude(ClaudeResult.Success("ok", TokenUsage(), "m")))

        val response = app.post("/v1/chat") {
            auth(); contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(store.locations.isEmpty())
    }

    @Test
    fun `a partial or absurd location is dropped, not refused`() = testApplication {
        // Optional data on a turn somebody spent minutes taking photographs for. Failing
        // their assessment over a bad coordinate would be the wrong trade.
        val store = FakeChatStore()
        val app = withChat(store, FakeClaude(ClaudeResult.Success("ok", TokenUsage(), "m")))
        val cases = listOf(
            request(latitude = 0.3476, longitude = 32.5825),                       // no accuracy
            request(latitude = 0.3476, accuracyM = 12.0),                           // no longitude
            request(latitude = 91.0, longitude = 32.5825, accuracyM = 12.0),        // off the planet
            request(latitude = 0.3476, longitude = 32.5825, accuracyM = 0.0),       // no accuracy at all
            request(latitude = 0.3476, longitude = 32.5825, accuracyM = 9000.0),    // a district
        )
        // One application, five turns: each request() carries its own session id, and the
        // store accumulates, so a single assertion at the end covers all of them.
        for (body in cases) {
            val response = app.post("/v1/chat") {
                auth(); contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals("the turn must still succeed", HttpStatusCode.OK, response.status)
        }
        assertTrue("nothing should have been stored: ${store.locations}", store.locations.isEmpty())
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

    private fun request(
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyM: Double? = null,
    ) = ChatRequest(
        sessionId = UUID.randomUUID().toString(),
        itemTypeId = "wooden-table",
        messageId = UUID.randomUUID().toString(),
        text = "I am buying this.",
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracyM,
    )

    private fun ApplicationTestBuilder.withChat(
        store: ChatStore,
        claude: FakeClaude,
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

        /** Everything the model was told, flattened, so a test can assert an absence. */
        var lastHistoryText: String = ""
            private set

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
        ): ClaudeResult {
            calls++
            lastSystemPrompt = systemPrompt
            lastHistoryText = history.joinToString("\n") { it.text }
            return result
        }
    }

    private class FakeChatStore(
        private val access: SessionAccess = SessionAccess.Ok(created = true),
        private val turnAlreadyStored: Boolean = false,
        private val storedReply: StoredReply? = null,
    ) : ChatStore {
        override suspend fun recordSessionLocation(
            sessionId: String,
            userId: String,
            location: SessionLocation,
        ) {
            locations += location
        }

        /** What the route tried to store, so a test can assert it was or was not called. */
        val locations = mutableListOf<SessionLocation>()

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

package com.qualityverifier.server

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.BenchDraft
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.PhotoShelf
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.BenchPhoto
import com.qualityverifier.server.db.BenchRecord
import com.qualityverifier.server.db.BenchStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.Digest
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.PieceStudio
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.SessionRow
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.BENCH_PHOTOS_PER_DAY
import com.qualityverifier.server.routes.SessionLocation
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The bench photo at setup (V35, release scope row 5): five a day outside the assessment
 * allowance, one Claude call per photo however often the phone retries, the call billed as
 * a bench call, and nothing written to the fundi's tools. The route has no tool store to
 * write to: the confirmed list reaches `fundi_tools` only through the profile.
 */
class BenchRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val photo = "a bench".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }

    @Test
    fun `a bench photo is drafted once, retried for free, and billed as a bench call`() = testApplication {
        val bench = FakeBench()
        val usage = UsageOnly()
        val claude = BenchClaude()
        val app = withBench(bench, usage, claude)
        val id = UUID.randomUUID().toString()

        val first = app.post("/v1/fundi/bench-photos") { auth(); body(id) }
        assertEquals(HttpStatusCode.OK, first.status)
        val draft = Json.decodeFromString(BenchDraft.serializer(), first.bodyAsText())
        assertEquals(listOf("clamps", "chisels"), draft.tools.map { it.kind })
        assertEquals(1, claude.calls)
        assertEquals(listOf("bench" to Audience.FUNDI), usage.calls)
        // The draft is kept with the photo, as parsed.
        assertTrue(bench.drafts.getValue(id).contains("clamps"))

        // The phone retries after a lost answer: the stored draft, no second call, no second photo.
        val again = app.post("/v1/fundi/bench-photos") { auth(); body(id) }
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(1, claude.calls)
        assertEquals(1, bench.rows.size)
    }

    @Test
    fun `the sixth bench photo of the day is refused before anything is spent`() = testApplication {
        val bench = FakeBench()
        val claude = BenchClaude()
        val app = withBench(bench, UsageOnly(), claude)
        repeat(BENCH_PHOTOS_PER_DAY) {
            assertEquals(HttpStatusCode.OK, app.post("/v1/fundi/bench-photos") { auth(); body(UUID.randomUUID().toString()) }.status)
        }
        val sixth = app.post("/v1/fundi/bench-photos") { auth(); body(UUID.randomUUID().toString()) }
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertTrue(sixth.bodyAsText().contains("bench_limit_reached"))
        assertEquals(BENCH_PHOTOS_PER_DAY, claude.calls)
    }

    @Test
    fun `a photo the server does not hold, or a malformed request, is refused before anything is spent`() = testApplication {
        val claude = BenchClaude()
        val app = withBench(FakeBench(), UsageOnly(), claude, storePhoto = false)
        val missing = app.post("/v1/fundi/bench-photos") { auth(); body(UUID.randomUUID().toString()) }
        assertEquals(HttpStatusCode.Conflict, missing.status)
        val malformed = app.post("/v1/fundi/bench-photos") {
            auth(); contentType(ContentType.Application.Json); setBody("""{"id":"x","sha256":"$sha"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(0, claude.calls)
    }

    // ---------------------------------------------------------------- harness

    private fun ApplicationTestBuilder.withBench(
        bench: FakeBench,
        usage: UsageOnly,
        claude: BenchClaude,
        storePhoto: Boolean = true,
    ) = run {
        val blobs = BlobStore(folder.newFolder())
        if (storePhoto) runBlocking { blobs.put(sha, photo) }
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(Fundis, AccessTokens(KEY)),
                chat = Chat(usage, blobs, claude, FixedPrompts, NoFeedback, bench = bench),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(FUNDI).token}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.body(id: String) {
        contentType(ContentType.Application.Json)
        setBody("""{"id":"$id","sha256":"$sha"}""")
    }

    /** The store's rules in memory: one row per id, five a day. */
    private class FakeBench : BenchStore {
        val rows = mutableMapOf<String, String>()
        val drafts = mutableMapOf<String, String>()
        override suspend fun record(
            userId: String, photoId: String, sha256: String, location: SessionLocation?, dailyLimit: Int,
        ): BenchRecord {
            rows[photoId]?.let { owner ->
                return if (owner == userId) BenchRecord.Ready(BenchPhoto(photoId, sha256, drafts[photoId])) else BenchRecord.NotYours
            }
            if (rows.values.count { it == userId } >= dailyLimit) return BenchRecord.LimitReached(dailyLimit)
            rows[photoId] = userId
            return BenchRecord.Ready(BenchPhoto(photoId, sha256, null))
        }
        override suspend fun saveDraft(photoId: String, draftJson: String, promptSha: String, model: String?) {
            drafts[photoId] = draftJson
        }
    }

    private class BenchClaude : ClaudeClient {
        var calls = 0
        override suspend fun send(
            systemPrompt: String, history: List<ChatMessage>, imageBytes: (Attachment) -> ByteArray?, photos: PhotoShelf?,
        ): ClaudeResult {
            calls++
            // The photo reaches the call as the one attachment of the one turn.
            check(history.single().attachments.single().let(imageBytes) != null)
            return ClaudeResult.Success(
                "```qv-bench\n{\"tools\":[{\"kind\":\"clamps\",\"seen\":\"right\"},{\"kind\":\"chisels\"}]}\n```",
                TokenUsage(10, 5, 0, 0), "m",
            )
        }
    }

    /** Only usage is recorded by the bench route; nothing else of the chat store is touched. */
    private class UsageOnly : ChatStore {
        val calls = mutableListOf<Pair<String, Audience>>()
        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?, httpStatus: Int?,
            latencyMillis: Long, errorKind: String?, audience: Audience, callKind: String,
        ) {
            calls += callKind to audience
        }
        private fun no(): Nothing = error("the bench route does not touch this")
        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String, previousSessionId: String?, intakeAnswers: String?,
            promptSha: String?, audience: Audience, dailyLimit: Int, testerDailyLimit: Int, pieceId: String?,
            pieceLabel: String?, pieceStudio: PieceStudio, isFinal: Boolean, workers: List<WorkerSlot>, appVersionCode: Int?,
        ): SessionAccess = no()
        override suspend fun replaceSessionWorkers(userId: String, sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun recordSessionLocation(sessionId: String, userId: String, location: SessionLocation) = no()
        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>, composed: Boolean,
            photoMeta: Map<String, JsonObject>,
        ) = no()
        override suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply? = no()
        override suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage> = no()
        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String, verdictLevelId: String?, verdictLanguage: String?,
            defectCount: ((List<String>) -> Int?)?,
        ): String = no()
        override suspend fun saveDigest(sessionId: String, digest: Digest) = no()
        override suspend fun digests(sessionId: String): List<Digest> = no()
        override suspend fun sessionsFor(userId: String): List<SessionRow> = no()
        override suspend fun sessionDetail(userId: String, sessionId: String): Pair<SessionRow, List<MessageRow>>? = no()
        override suspend fun markClientDeleted(userId: String, sessionId: String): Boolean = no()
        override suspend fun blobBelongsTo(userId: String, sha: String): Boolean = no()
    }

    private object FixedPrompts : PromptRepository {
        override suspend fun systemPromptFor(itemType: ItemType, audience: Audience) = "PROMPT"
        override suspend fun compactionPrompt() = "NOTES"
        override suspend fun clearCache() = Unit
    }

    private object Fundis : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = UserRow(
            userId, "Wanjirũ", "individual", null, disabled = false, isTester = false, audience = Audience.FUNDI,
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
        const val FUNDI = "11111111-2222-3333-4444-555555555555"
    }
}

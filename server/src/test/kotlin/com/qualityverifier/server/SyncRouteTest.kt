package com.qualityverifier.server

import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.auth.Passwords
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.routes.SessionLocation
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.SessionRow
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.ChangePasswordRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import com.qualityverifier.server.db.TesterFeedback
import io.ktor.client.request.get
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
import java.time.Duration
import java.time.Instant

/**
 * Reading assessments back, and the two account actions.
 *
 * Everything here is an ownership or credential decision. Getting one wrong shows one
 * customer another's photographs, or locks somebody out of their own account.
 */
class SyncRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the list contains only this user's assessments`() = testApplication {
        val chat = FakeChatStore(sessions = listOf(row(SESSION)))
        val app = withSync(chat, FakeAuth())

        val response = app.get("/v1/sessions") { auth(MINE) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(MINE, chat.askedFor)
        assertTrue(response.bodyAsText().contains(SESSION))
    }

    @Test
    fun `somebody else's assessment is a 404, not a 403`() = testApplication {
        // 403 would confirm the id exists, which is all somebody needs to enumerate them.
        val app = withSync(FakeChatStore(detail = null), FakeAuth())

        val response = app.get("/v1/sessions/11111111-1111-1111-1111-111111111111") { auth(MINE) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `an assessment comes back with its messages and photo hashes in order`() = testApplication {
        val chat = FakeChatStore(
            detail = row(SESSION) to listOf(
                MessageRow("m1", "USER", "here they are", 0, 1L, listOf("aa".repeat(32), "bb".repeat(32))),
                MessageRow("m2", "ASSISTANT", "thanks", 1, 2L, emptyList()),
            )
        )
        val app = withSync(chat, FakeAuth())

        val body = app.get("/v1/sessions/$SESSION") { auth(MINE) }.bodyAsText()

        // Order matters: the protocols refer to photos by position.
        assertTrue(body, body.indexOf("aa".repeat(32)) < body.indexOf("bb".repeat(32)))
        assertTrue(body, body.contains("\"ordinal\":0"))
    }

    @Test
    fun `an id that is not a uuid is a 404, not a 500`() = testApplication {
        // These ids are cast with `?::uuid`, so anything malformed throws inside Postgres
        // and StatusPages turns it into a 500. It is the same "no such thing" as any other
        // unknown id and deserves the same answer.
        val app = withSync(FakeChatStore(), FakeAuth())

        assertEquals(
            HttpStatusCode.NotFound,
            app.get("/v1/sessions/not-a-uuid") { auth(MINE) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            app.delete("/v1/sessions/not-a-uuid") { auth(MINE) }.status,
        )
    }

    @Test
    fun `deleting an assessment marks it rather than removing it`() = testApplication {
        // The flag is what starts the retention clock. A hard delete here would make the
        // 7-day window we tell customers about a fiction.
        val chat = FakeChatStore(deleteSucceeds = true)
        val app = withSync(chat, FakeAuth())

        val response = app.delete("/v1/sessions/$SESSION") { auth(MINE) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(SESSION, chat.deleted)
    }

    @Test
    fun `deleting an assessment that is not yours changes nothing`() = testApplication {
        val chat = FakeChatStore(deleteSucceeds = false)
        val app = withSync(chat, FakeAuth())

        assertEquals(HttpStatusCode.NotFound, app.delete("/v1/sessions/theirs") { auth(MINE) }.status)
    }

    @Test
    fun `changing a password requires the current one, even with a valid token`() = testApplication {
        // A token can be lifted from an unlocked phone, and a password change is exactly
        // what would lock the owner out of their own account.
        val auth = FakeAuth(currentPassword = "the real one")
        val app = withSync(FakeChatStore(), auth)

        val response = app.post("/v1/auth/password") {
            this.auth(MINE); contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest("a guess", "a new long password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(auth.passwordChanges.isEmpty())
    }

    @Test
    fun `changing a password signs every other device out`() = testApplication {
        // A password change is usually a response to somebody else having had access.
        val auth = FakeAuth(currentPassword = "the real one")
        val app = withSync(FakeChatStore(), auth)

        val response = app.post("/v1/auth/password") {
            this.auth(MINE); contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest("the real one", "a new long password"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(listOf(MINE), auth.passwordChanges)
        assertEquals(listOf(MINE), auth.revoked)
    }

    @Test
    fun `a short new password is refused`() = testApplication {
        val auth = FakeAuth(currentPassword = "the real one")
        val app = withSync(FakeChatStore(), auth)

        val response = app.post("/v1/auth/password") {
            this.auth(MINE); contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest("the real one", "short"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(auth.passwordChanges.isEmpty())
    }

    @Test
    fun `deleting an account marks it and revokes its tokens together`() = testApplication {
        // An account marked deleted whose tokens still work is not deleted.
        val auth = FakeAuth()
        val app = withSync(FakeChatStore(), auth)

        assertEquals(HttpStatusCode.NoContent, app.delete("/v1/account") { this.auth(MINE) }.status)
        assertEquals(listOf(MINE), auth.accountsDeleted)
    }

    @Test
    fun `every sync route refuses an unauthenticated caller`() = testApplication {
        val app = withSync(FakeChatStore(), FakeAuth())

        assertEquals(HttpStatusCode.Unauthorized, app.get("/v1/sessions").status)
        assertEquals(HttpStatusCode.Unauthorized, app.get("/v1/sessions/x").status)
        assertEquals(HttpStatusCode.Unauthorized, app.delete("/v1/sessions/x").status)
        assertEquals(HttpStatusCode.Unauthorized, app.delete("/v1/account").status)
        assertEquals(HttpStatusCode.Unauthorized, app.get("/v1/blobs/${"a".repeat(64)}").status)
    }

    @Test
    fun `a photo belonging to another account is a 404, not a download`() = testApplication {
        // The bug this covers shipped: the route checked that you were signed in and then
        // served any hash to anybody. Content addressing was mistaken for access control,
        // but the hash is an identifier, not a secret — session detail returns hashes, and
        // so will the admin portal and research exports.
        val blobs = BlobStore(folder.newFolder())
        val bytes = "somebody else's workshop".toByteArray()
        val sha = BlobStore.hash(bytes)
        kotlinx.coroutines.runBlocking { blobs.put(sha, bytes) }
        // The bytes exist and STRANGER holds a valid token. Only ownership is missing.
        val app = withSync(FakeChatStore(ownedBlobs = mapOf(MINE to setOf(sha))), FakeAuth(), blobs)

        val response = app.get("/v1/blobs/$sha") { auth(STRANGER) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(
            "the body must not carry the photo",
            response.bodyAsText().let { it.contains("no_such_blob") && !it.contains("workshop") },
        )
    }

    @Test
    fun `a photo from a deleted report stops being served to its own owner`() = testApplication {
        // The delete dialog promises the report is gone from the phone. Still serving its
        // photographs to that account would make the promise false in the one direction a
        // customer would notice. The bytes stay on disk for the seven-day window; what
        // stops is serving them over this API.
        val blobs = BlobStore(folder.newFolder())
        val bytes = "a photo from a deleted report".toByteArray()
        val sha = BlobStore.hash(bytes)
        kotlinx.coroutines.runBlocking { blobs.put(sha, bytes) }
        // No live session refers to it — which is what the real query returns once
        // client_deleted_at is set.
        val app = withSync(FakeChatStore(ownedBlobs = emptyMap()), FakeAuth(), blobs)

        assertEquals(HttpStatusCode.NotFound, app.get("/v1/blobs/$sha") { auth(MINE) }.status)
        assertTrue("the bytes are still on disk for the retention window", blobs.read(sha) != null)
    }

    @Test
    fun `a photo can be fetched back, and a malformed hash cannot`() = testApplication {
        val blobs = BlobStore(folder.newFolder())
        val bytes = "a photograph".toByteArray()
        val sha = BlobStore.hash(bytes)
        kotlinx.coroutines.runBlocking { blobs.put(sha, bytes) }
        val app = withSync(FakeChatStore(ownedBlobs = mapOf(MINE to setOf(sha))), FakeAuth(), blobs)

        assertEquals(HttpStatusCode.OK, app.get("/v1/blobs/$sha") { auth(MINE) }.status)
        // Hash-shaped but not hex. A path-traversal attempt cannot be tested here — the
        // HTTP client normalises "../" out before the request is sent — and is covered
        // directly by BlobStoreTest against isValidHash.
        assertEquals(
            HttpStatusCode.BadRequest,
            app.get("/v1/blobs/${"z".repeat(64)}") { auth(MINE) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            app.get("/v1/blobs/${"c".repeat(64)}") { auth(MINE) }.status,
        )
    }

    // ------------------------------------------------- evaluator feedback

    @Test
    fun `an evaluator's critique is recorded against the assessment`() = testApplication {
        val feedback = RecordingFeedback()
        val app = withSync(FakeChatStore(), FakeAuth(isTester = true), feedback = feedback)

        val response = app.post("/v1/tester-feedback") {
            auth(MINE)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"session_id":"$SESSION","mistakes":"yes","mistakes_detail":"Called a dowel a tenon",
                 "advice_stars":4,"item_quality":7,"extra_feedback":"Useful on the joints"}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val saved = feedback.saved.single()
        assertEquals(SESSION, saved.second.sessionId)
        assertEquals(MINE, saved.first)
        assertEquals("yes", saved.second.mistakes)
        assertEquals(4, saved.second.adviceStars)
        assertEquals(7, saved.second.itemQuality)
    }

    @Test
    fun `a customer cannot submit evaluator feedback`() = testApplication {
        // These rows are a research instrument. A mix of staff critiques and unsolicited
        // customer ratings in one table is a dataset nobody can use.
        val feedback = RecordingFeedback()
        val app = withSync(FakeChatStore(), FakeAuth(isTester = false), feedback = feedback)

        val response = app.post("/v1/tester-feedback") {
            auth(MINE)
            contentType(ContentType.Application.Json)
            setBody("""{"session_id":"$SESSION","mistakes":"no","advice_stars":5,"item_quality":8}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue("nothing may be written", feedback.saved.isEmpty())
    }

    @Test
    fun `out-of-range answers are refused with a reason`() = testApplication {
        // The table has the same constraints, but a violation there is a 500 that tells the
        // evaluator nothing — and a research instrument that silently drops a submission is
        // worse than one that refuses it.
        val feedback = RecordingFeedback()
        val app = withSync(FakeChatStore(), FakeAuth(isTester = true), feedback = feedback)

        val cases = listOf(
            """{"session_id":"$SESSION","mistakes":"maybe","advice_stars":3,"item_quality":5}""",
            """{"session_id":"$SESSION","mistakes":"no","advice_stars":6,"item_quality":5}""",
            """{"session_id":"$SESSION","mistakes":"no","advice_stars":0,"item_quality":5}""",
            """{"session_id":"$SESSION","mistakes":"no","advice_stars":3,"item_quality":11}""",
            """{"session_id":"$SESSION","mistakes":"no","advice_stars":3,"item_quality":0}""",
        )
        cases.forEach { body ->
            val response = app.post("/v1/tester-feedback") {
                auth(MINE)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
        }
        assertTrue("nothing may be written", feedback.saved.isEmpty())
    }

    @Test
    fun `feedback on somebody else's assessment is a 404`() = testApplication {
        // The store reports the ownership failure; the route must not turn it into a 403.
        val app = withSync(
            FakeChatStore(), FakeAuth(isTester = true),
            feedback = RecordingFeedback(accepts = false),
        )

        val response = app.post("/v1/tester-feedback") {
            auth(MINE)
            contentType(ContentType.Application.Json)
            setBody("""{"session_id":"$SESSION","mistakes":"no","advice_stars":3,"item_quality":5}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `feedback needs a token`() = testApplication {
        val app = withSync(FakeChatStore(), FakeAuth(isTester = true))

        val response = app.post("/v1/tester-feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"session_id":"$SESSION","mistakes":"no","advice_stars":3,"item_quality":5}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private class RecordingFeedback(private val accepts: Boolean = true) : FeedbackStore {
        val saved = mutableListOf<Pair<String, TesterFeedback>>()

        override suspend fun save(userId: String, feedback: TesterFeedback): Boolean {
            if (!accepts) return false
            saved += userId to feedback
            return true
        }

        override suspend fun feedbackFor(sessionId: String) =
            saved.lastOrNull { it.second.sessionId == sessionId }?.second
    }

    // ---------------------------------------------------------------- harness

    private fun io.ktor.client.request.HttpRequestBuilder.auth(userId: String) {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(userId).token}")
    }

    private fun row(id: String) = SessionRow(
        id = id, itemTypeId = "wooden-stool", createdAt = 1L, updatedAt = 2L,
        preview = "a preview", messageCount = 2, verdictLevelId = "fair",
        verdictLanguage = "en", previousSessionId = null, intakeAnswers = "en-buying-daily-full",
    )

    private fun ApplicationTestBuilder.withSync(
        chat: ChatStore,
        auth: AuthStore,
        blobs: BlobStore = BlobStore(folder.newFolder()),
        feedback: FeedbackStore = NoFeedback,
    ) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(auth, AccessTokens(KEY)),
                // The sync routes mount alongside chat, so a Chat is supplied with a
                // Claude client that would fail if called — none of these routes call it,
                // and a stub that throws would catch it if one ever did.
                chat = Chat(chat, blobs, UnusedClaude, UnusedPrompts, feedback),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private object UnusedClaude : com.qualityverifier.server.chat.ClaudeClient {
        override suspend fun send(
            systemPrompt: String,
            history: List<com.qualityverifier.domain.ChatMessage>,
            imageBytes: (com.qualityverifier.domain.Attachment) -> ByteArray?,
        ) = error("no sync route should reach the model")
    }

    private object UnusedPrompts : com.qualityverifier.data.prompts.PromptRepository {
        override suspend fun systemPromptFor(itemType: com.qualityverifier.domain.ItemType) =
            error("no sync route should need a prompt")
        override suspend fun clearCache() = Unit
    }

    private class FakeChatStore(
        private val sessions: List<SessionRow> = emptyList(),
        private val detail: Pair<SessionRow, List<MessageRow>>? = null,
        private val deleteSucceeds: Boolean = true,
        /**
         * Photo hashes this user owns, keyed by user id. Empty by default so a test has
         * to opt in — a route that forgot the ownership check cannot then pass silently.
         */
        private val ownedBlobs: Map<String, Set<String>> = emptyMap(),
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

        var askedFor: String? = null
            private set
        var deleted: String? = null
            private set

        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String,
            previousSessionId: String?, intakeAnswers: String?, promptSha: String?,
            dailyLimit: Int, testerDailyLimit: Int,
        ) = SessionAccess.Ok(created = true)

        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>,
        ) = true

        override suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply? = null
        override suspend fun history(sessionId: String, blobPath: (String) -> String) = emptyList<com.qualityverifier.domain.ChatMessage>()
        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String,
            verdictLevelId: String?, verdictLanguage: String?,
        ) = "a1"

        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?,
            httpStatus: Int?, latencyMillis: Long, errorKind: String?,
        ) = Unit

        override suspend fun sessionsFor(userId: String): List<SessionRow> {
            askedFor = userId
            return sessions
        }

        override suspend fun sessionDetail(userId: String, sessionId: String) = detail

        override suspend fun blobBelongsTo(userId: String, sha: String): Boolean =
            ownedBlobs[userId]?.contains(sha) == true

        override suspend fun markClientDeleted(userId: String, sessionId: String): Boolean {
            if (deleteSucceeds) deleted = sessionId
            return deleteSucceeds
        }
    }

    private class FakeAuth(
        private val currentPassword: String? = null,
        private val isTester: Boolean = false,
    ) : AuthStore {
        val passwordChanges = mutableListOf<String>()
        val accountsDeleted = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) =
            UserRow(userId, "Grady", "individual", null, disabled = false, isTester = isTester)

        override suspend fun issueRefresh(
            userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?,
        ) = "r"

        override suspend fun findRefresh(token: String): StoredRefresh? = null
        override suspend fun revokeChain(userId: String): Int {
            revoked += userId
            return 1
        }

        override suspend fun credentialsForPhone(phone: String): Credentials? = null
        override suspend fun recordFailedSignIn(userId: String, lockFor: Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String) = currentPassword?.let(Passwords::hash)
        override suspend fun setPasswordHash(userId: String, passwordHash: String) {
            passwordChanges += userId
        }

        override suspend fun markAccountDeleted(userId: String) {
            accountsDeleted += userId
            revoked += userId
        }
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        const val MINE = "11111111-2222-3333-4444-555555555555"

        /**
         * A real UUID, because the routes cast ids with `?::uuid`.
         *
         * These tests used "mine", which no client could ever send and which now gets the
         * 404 that junk ids get. A readable placeholder was hiding the fact that a
         * malformed id reached Postgres and came back as a 500.
         */
        const val SESSION = "2ff77920-3928-46e5-8a77-5e16c1e901c6"

        /** A second, entirely valid account. Signed in, just not the owner. */
        const val STRANGER = "99999999-8888-7777-6666-555555555555"
    }
}

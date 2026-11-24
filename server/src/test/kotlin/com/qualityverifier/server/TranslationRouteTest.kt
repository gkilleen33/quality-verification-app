package com.qualityverifier.server

import com.qualityverifier.data.chat.AnthropicRequest
import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.PhotoShelf
import com.qualityverifier.server.chat.ReplyTranslator
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.NewTranslation
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.SessionRow
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.db.TranslationStore
import com.qualityverifier.server.routes.ChatRequest
import com.qualityverifier.server.routes.TranslationRequest
import com.qualityverifier.server.routes.TranslationResponse
import com.qualityverifier.text.ReplyTranslation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The English and Kiswahili switch on a Fundi Bora assessment (release scope row 29): the route
 * that translates an assessment's replies, keeps each translation, and serves it again; and the
 * rule that a translation never reaches the conversation Claude is sent.
 */
class TranslationRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val diagnosis = """
        The back rail moves. Put it right first.
        ```fb-diagnosis
        {"language":"en","findings":[
          {"title":"Glue the back rail to its posts","severity":"serious","area":"structural","dimension":"joint_fit",
           "fix_line":"Open the joint, glue it and clamp it.","customer_line":"A rail that flexes makes customers offer less.",
           "row_line":"A flexing rail makes customers offer less."},
          {"title":"Sand the top smooth","severity":"minor","area":"surface","dimension":"finishing",
           "fix_line":"Sand the top with 180 grit.","customer_line":"A rough top looks unfinished.","row_line":"A rough top looks unfinished."}
        ],"one_check":""}
        ```
    """.trimIndent()

    private val diagnosisSw = """
        Reli ya nyuma inasogea. Irekebishe kwanza.
        ```fb-diagnosis
        {"language":"en","findings":[
          {"title":"Gundisha reli ya nyuma kwenye nguzo zake","severity":"serious","area":"structural","dimension":"joint_fit",
           "fix_line":"Fungua kiungo, weka gundi na ukibane.","customer_line":"Reli inayopinda inawafanya wateja watoe kidogo.",
           "row_line":"Reli inayopinda inawafanya wateja watoe kidogo."},
          {"title":"Piga msasa juu iwe laini","severity":"minor","area":"surface","dimension":"finishing",
           "fix_line":"Piga msasa juu kwa 180.","customer_line":"Juu korofi inaonekana haijakamilika.","row_line":"Juu korofi inaonekana haijakamilika."}
        ],"one_check":""}
        ```
    """.trimIndent()

    private val answer = "The glue was spread thin, so the joint opened."
    private val answerSw = "Gundi ilienezwa kidogo, kwa hiyo kiungo kilifunguka."

    @Test
    fun `row 29 - a translation is stored once and served again without a Claude call`() = testApplication {
        val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis, answer) }
        val translations = MemoryTranslations()
        val claude = FakeClaude(mapOf(diagnosis to diagnosisSw, answer to answerSw))
        val client = withRoutes(store, translations, claude)

        val first = client.translate(SESSION, "sw")
        assertEquals(HttpStatusCode.OK, first.first)
        assertEquals(2, claude.translationCalls.get())
        assertEquals(
            mapOf(ReplyTranslation.keyOf(diagnosis) to diagnosisSw, ReplyTranslation.keyOf(answer) to answerSw),
            first.second.translations.associate { it.sourceSha256 to it.text },
        )
        assertTrue(first.second.failed.isEmpty())
        assertEquals(2, translations.saved.size)
        assertTrue(translations.saved.all { it.language == "sw" && it.model == "claude-sonnet-5-5" && it.promptSha != null })

        // Again, as a second phone signed in to the account would: from the store, no call.
        val second = client.translate(SESSION, "sw")
        assertEquals(first.second, second.second)
        assertEquals(2, claude.translationCalls.get())
        assertEquals(2, translations.saved.size)

        // Each call billed to Fundi Bora as a translation, outside any assessment's allowance.
        assertEquals(List(2) { "translation" to Audience.FUNDI }, store.usage.toList())
        assertEquals(0, store.ensured.get())
        // And asked of the translation prompt, at low effort, with one turn and no photos.
        assertTrue(claude.translationRequests.all { it.effort == ReplyTranslator.EFFORT && it.history.size == 1 && it.photos == null })
        assertTrue(claude.translationRequests.all { it.history.single().text.startsWith("Translate this reply into Kiswahili.") })
    }

    @Test
    fun `row 29 - a translated block with a changed enum or a missing finding is refused`() {
        val changedEnum = diagnosisSw.replace("\"severity\":\"serious\"", "\"severity\":\"kubwa\"")
        val start = diagnosisSw.indexOf(",\n  {\"title\":\"Piga msasa")
        val end = diagnosisSw.indexOf("\n],\"one_check\"")
        val missingFinding = diagnosisSw.removeRange(start, end)
        assertTrue(start > 0 && end > start)

        listOf(changedEnum, missingFinding).forEach { bad -> testApplication {
            val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis) }
            val translations = MemoryTranslations()
            val client = withRoutes(store, translations, FakeClaude(mapOf(diagnosis to bad)))

            val (status, body) = client.translate(SESSION, "sw")
            assertEquals(HttpStatusCode.OK, status)
            // Nothing stored and nothing served: the phone keeps the original and says so.
            assertTrue(body.translations.isEmpty())
            assertEquals(listOf(ReplyTranslation.keyOf(diagnosis)), body.failed)
            assertTrue(translations.saved.isEmpty())
            // The call is still billed.
            assertEquals(listOf("translation" to Audience.FUNDI), store.usage.toList())
        } }
    }

    @Test
    fun `row 29 - the next turn's request to Claude is byte-identical with and without a stored translation`() {
        fun nextTurnRequest(translateFirst: Boolean): Pair<String, String> {
            var request = ""
            var translatedText = ""
            testApplication {
                val store = MemoryChatStore()
                val translations = MemoryTranslations()
                val claude = FakeClaude(mapOf(diagnosis to diagnosisSw), assessmentReplies = ArrayDeque(listOf(diagnosis, answer)))
                val client = withRoutes(store, translations, claude)

                assertEquals(HttpStatusCode.OK, client.turn("u-1", "I have finished this sofa. Please evaluate it."))
                if (translateFirst) {
                    val (status, body) = client.translate(SESSION, "sw")
                    assertEquals(HttpStatusCode.OK, status)
                    translatedText = body.translations.single().text
                    assertEquals(1, translations.saved.size)
                }
                assertEquals(HttpStatusCode.OK, client.turn("u-2", "Why did the rail move?"))
                request = claude.assessmentRequests.last()
                // The conversation stored is the original, whatever was translated.
                assertEquals(listOf("u-1", "a-1", "u-2", "a-2"), store.messages(SESSION).map { it.id })
                assertEquals(diagnosis, store.messages(SESSION)[1].text)
            }
            return request to translatedText
        }

        val (without, _) = nextTurnRequest(translateFirst = false)
        val (with, translated) = nextTurnRequest(translateFirst = true)
        assertEquals(diagnosisSw, translated)
        assertEquals(without, with)
        assertFalse("the translation reached Claude", with.contains("Reli ya nyuma"))
        assertTrue(with.contains("The back rail moves."))
    }

    @Test
    fun `row 29 - an old phone ignores the new route - the session it reads back is the same with a translation stored`() = testApplication {
        val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis, answer) }
        val translations = MemoryTranslations()
        val client = withRoutes(store, translations, FakeClaude(mapOf(diagnosis to diagnosisSw, answer to answerSw)))

        val before = client.get("/v1/sessions/$SESSION") { auth() }
        assertEquals(HttpStatusCode.OK, before.status)
        val beforeText = before.bodyAsText()
        assertEquals(HttpStatusCode.OK, client.translate(SESSION, "sw").first)
        assertEquals(2, translations.saved.size)
        val after = client.get("/v1/sessions/$SESSION") { auth() }
        assertEquals(beforeText, after.bodyAsText())
        assertFalse(after.bodyAsText().contains("Reli ya nyuma"))
    }

    @Test
    fun `row 29 - only a Fundi Bora account, on its own assessment, in English or Kiswahili`() = testApplication {
        val store = MemoryChatStore().apply { seed(SESSION, OTHER_USER, diagnosis) }
        val claude = FakeClaude(mapOf(diagnosis to diagnosisSw))
        val client = withRoutes(store, MemoryTranslations(), claude)
        assertEquals(HttpStatusCode.NotFound, client.translate(SESSION, "sw").first)
        assertEquals(HttpStatusCode.BadRequest, client.translate(SESSION, "fr").first)
        assertEquals(HttpStatusCode.BadRequest, client.translate("not-a-uuid", "sw").first)
        assertEquals(0, claude.translationCalls.get())
    }

    @Test
    fun `row 29 - a Kagua account, a shop's tablet included, is refused the route`() = testApplication {
        val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis) }
        val claude = FakeClaude(mapOf(diagnosis to diagnosisSw))
        val client = withRoutes(store, MemoryTranslations(), claude, audience = Audience.BUYER)
        assertEquals(HttpStatusCode.Forbidden, client.translate(SESSION, "sw").first)
        assertEquals(0, claude.translationCalls.get())
    }

    @Test
    fun `row 29 - a reply that will not translate cleanly is tried at most three times`() = testApplication {
        val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis) }
        val bad = diagnosisSw.replace("\"area\":\"surface\"", "\"area\":\"uso\"")
        val claude = FakeClaude(mapOf(diagnosis to bad))
        val client = withRoutes(store, MemoryTranslations(), claude)
        repeat(5) { assertEquals(HttpStatusCode.OK, client.translate(SESSION, "sw").first) }
        assertEquals(ReplyTranslator.MAX_ATTEMPTS, claude.translationCalls.get())
    }

    @Test
    fun `row 29 - two requests at once share one call per reply`() = runBlocking {
        val store = MemoryChatStore().apply { seed(SESSION, USER, diagnosis) }
        val translations = MemoryTranslations()
        val gate = CompletableDeferred<Unit>()
        val claude = FakeClaude(mapOf(diagnosis to diagnosisSw), gate = gate)
        val translator = ReplyTranslator(store, translations, claude, Prompts(), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

        val a = async(Dispatchers.Default) { translator.translate(USER, SESSION, AssessmentLanguage.SWAHILI) }
        val b = async(Dispatchers.Default) { translator.translate(USER, SESSION, AssessmentLanguage.SWAHILI) }
        withTimeout(10_000) { while (claude.translationCalls.get() < 1) delay(10) }
        delay(200)
        gate.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, claude.translationCalls.get())
        assertEquals(1, translations.saved.size)
    }

    // ---------------------------------------------------------------- harness

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(USER).token}")
    }

    private suspend fun HttpClient.translate(session: String, language: String): Pair<HttpStatusCode, TranslationResponse> {
        val response = post("/v1/fundi/sessions/$session/translations") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(TranslationRequest(language))
        }
        val body = if (response.status == HttpStatusCode.OK) response.body<TranslationResponse>()
        else TranslationResponse(language, emptyList(), emptyList())
        return response.status to body
    }

    private suspend fun HttpClient.turn(messageId: String, text: String): HttpStatusCode =
        post("/v1/fundi/chat") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(sessionId = SESSION, itemTypeId = "wooden-table", messageId = messageId, text = text))
        }.status

    private fun ApplicationTestBuilder.withRoutes(
        store: ChatStore,
        translations: TranslationStore,
        claude: FakeClaude,
        audience: Audience = Audience.FUNDI,
    ): HttpClient {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(Account(audience), AccessTokens(KEY)),
                chat = Chat(
                    store, BlobStore(folder.newFolder()), claude, Prompts(), NoFeedback,
                    translations = translations,
                ),
            )
        }
        return createClient { install(ClientContentNegotiation) { json() } }
    }

    private class Prompts : PromptRepository {
        override suspend fun systemPromptFor(itemType: ItemType, audience: Audience) = "FUNDI PROMPT FOR ${itemType.id}"
        override suspend fun compactionPrompt() = "WRITE YOUR NOTES"
        override suspend fun clearCache() = Unit
    }

    private data class Request(val history: List<ChatMessage>, val photos: PhotoShelf?, val effort: String?)

    /**
     * Answers a translation call from [translated], by the reply between its tags, and an
     * assessment turn with the next of [assessmentReplies]. Records each assessment request as
     * AnthropicClient would send it, with the same assembly and serialiser.
     */
    private class FakeClaude(
        private val translated: Map<String, String>,
        private val assessmentReplies: ArrayDeque<String> = ArrayDeque(),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ClaudeClient {
        val translationCalls = AtomicInteger()
        val translationRequests = CopyOnWriteArrayList<Request>()
        val assessmentRequests = CopyOnWriteArrayList<String>()

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
            photos: PhotoShelf?,
        ): ClaudeResult = send(systemPrompt, history, imageBytes, photos, null)

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
            photos: PhotoShelf?,
            effort: String?,
        ): ClaudeResult {
            if (systemPrompt == DefaultPrompts.FUNDI_TRANSLATE) {
                translationCalls.incrementAndGet()
                translationRequests += Request(history, photos, effort)
                gate?.await()
                val original = history.single().text.substringAfter("<reply>\n").substringBeforeLast("\n</reply>")
                val text = translated[original] ?: error("no translation for the reply")
                return ClaudeResult.Success(text, TokenUsage(100, 100), "claude-sonnet-5-5")
            }
            assessmentRequests += AnthropicRequest.json.encodeToString(
                AnthropicRequest.build(systemPrompt, history, imageBytes, { "" }, effort = effort),
            )
            return ClaudeResult.Success(assessmentReplies.removeFirst(), TokenUsage(1000, 200), "claude-sonnet-5-5")
        }
    }

    private class MemoryTranslations : TranslationStore {
        val saved = CopyOnWriteArrayList<NewTranslation>()

        override suspend fun find(sessionId: String, language: String): Map<String, String> =
            saved.filter { it.sessionId == sessionId && it.language == language }.associate { it.sourceSha256 to it.text }

        override suspend fun save(translation: NewTranslation) {
            if (saved.none { it.sessionId == translation.sessionId && it.language == translation.language &&
                    it.sourceSha256 == translation.sourceSha256 }
            ) {
                saved += translation
            }
        }
    }

    /** The conversation as PostgresChatStore keeps it, in memory, with ids fixed by position. */
    private class MemoryChatStore : ChatStore {
        private val owners = ConcurrentHashMap<String, String>()
        private val conversations = ConcurrentHashMap<String, MutableList<ChatMessage>>()
        val usage = CopyOnWriteArrayList<Pair<String, Audience>>()
        val ensured = AtomicInteger()
        private var replies = 0

        fun messages(sessionId: String): List<ChatMessage> = conversations[sessionId].orEmpty().toList()

        /** A session of [owner]'s whose conversation is a turn, then each of [replies] after a turn. */
        fun seed(sessionId: String, owner: String, vararg replies: String) {
            owners[sessionId] = owner
            val list = conversations.getOrPut(sessionId) { mutableListOf() }
            replies.forEachIndexed { index, reply ->
                list += ChatMessage("seed-u$index", Role.USER, "turn $index")
                list += ChatMessage("seed-a$index", Role.ASSISTANT, reply)
            }
        }

        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String, previousSessionId: String?,
            intakeAnswers: String?, promptSha: String?, audience: Audience, dailyLimit: Int,
            testerDailyLimit: Int, pieceId: String?, pieceLabel: String?,
            pieceStudio: com.qualityverifier.server.db.PieceStudio, isFinal: Boolean,
            workers: List<WorkerSlot>, appVersionCode: Int?,
        ): SessionAccess {
            ensured.incrementAndGet()
            val owner = owners.putIfAbsent(sessionId, userId)
            return if (owner != null && owner != userId) SessionAccess.NotYours else SessionAccess.Ok(created = owner == null)
        }

        override suspend fun replaceSessionWorkers(userId: String, sessionId: String, workers: List<WorkerSlot>) = true
        override suspend fun recordSessionLocation(
            sessionId: String, userId: String, location: com.qualityverifier.server.routes.SessionLocation,
        ) = Unit

        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>,
            composed: Boolean, photoMeta: Map<String, JsonObject>,
        ): Boolean {
            val list = conversations.getOrPut(sessionId) { mutableListOf() }
            if (list.any { it.id == messageId }) return false
            list += ChatMessage(messageId, Role.USER, text, composed = composed)
            return true
        }

        override suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply? = null

        override suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage> = messages(sessionId)

        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String, verdictLevelId: String?,
            verdictLanguage: String?, defectCount: ((List<String>) -> Int?)?,
        ): String {
            val id = "a-${++replies}"
            conversations.getOrPut(sessionId) { mutableListOf() } += ChatMessage(id, Role.ASSISTANT, text)
            return id
        }

        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?,
            httpStatus: Int?, latencyMillis: Long, errorKind: String?, audience: Audience, callKind: String,
        ) {
            this.usage += callKind to audience
        }

        override suspend fun saveDigest(sessionId: String, digest: com.qualityverifier.server.db.Digest) = Unit
        override suspend fun digests(sessionId: String) = emptyList<com.qualityverifier.server.db.Digest>()
        override suspend fun sessionsFor(userId: String) = emptyList<SessionRow>()

        override suspend fun sessionDetail(userId: String, sessionId: String): Pair<SessionRow, List<MessageRow>>? {
            if (owners[sessionId] != userId) return null
            val list = messages(sessionId)
            val row = SessionRow(
                id = sessionId, itemTypeId = "wooden-table", createdAt = 1L, updatedAt = 2L,
                preview = "", messageCount = list.size, verdictLevelId = null, verdictLanguage = null,
                previousSessionId = null, intakeAnswers = null,
            )
            return row to list.mapIndexed { index, m ->
                MessageRow(m.id, if (m.role == Role.USER) "USER" else "ASSISTANT", m.text, index, 1L, emptyList(), m.composed)
            }
        }

        override suspend fun markClientDeleted(userId: String, sessionId: String) = false
        override suspend fun blobBelongsTo(userId: String, sha: String) = false
    }

    /** One account, of [audience], signed in as [USER]. */
    private class Account(private val audience: Audience) : com.qualityverifier.server.db.AuthStore {
        override suspend fun register(registration: com.qualityverifier.server.db.Registration) =
            com.qualityverifier.server.db.RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = com.qualityverifier.server.db.UserRow(
            id = userId, displayName = "A Fundi", accountType = "individual",
            businessName = null, disabled = false, isTester = false, audience = audience,
            shopMode = audience == Audience.BUYER,
        )
        override suspend fun issueRefresh(
            userId: String, token: String, expiresAt: java.time.Instant, userAgent: String?, replaces: String?,
        ) = "unused"
        override suspend fun findRefresh(token: String) = null
        override suspend fun revokeChain(userId: String) = 0
        override suspend fun credentialsForPhone(phone: String) = emptyList<com.qualityverifier.server.db.Credentials>()
        override suspend fun recordFailedSignIn(userId: String, lockFor: java.time.Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String): String? = null
        override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
        override suspend fun markAccountDeleted(userId: String) = Unit
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        val USER: String = java.util.UUID.randomUUID().toString()
        val OTHER_USER: String = java.util.UUID.randomUUID().toString()
        val SESSION: String = java.util.UUID.randomUUID().toString()
    }
}

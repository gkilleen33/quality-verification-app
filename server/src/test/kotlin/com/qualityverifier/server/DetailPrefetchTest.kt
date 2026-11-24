package com.qualityverifier.server

import com.qualityverifier.data.chat.AnthropicRequest
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.DetailPrefetcher
import com.qualityverifier.server.chat.PhotoShelf
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.chat.UpstreamError
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.DetailAnswerStore
import com.qualityverifier.server.db.NewDetailAnswer
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.StoredDetail
import com.qualityverifier.text.DetailKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The detail answers written when a Fundi Bora diagnosis arrives (release scope row 38), and
 * the effort they ask for (row 50). The route that schedules them and answers a tap from them
 * is in ChatRouteTest; this holds the prefetcher itself to its rules.
 */
class DetailPrefetchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val diagnosisReply = """
        The back rail moves, and the top is rough near the edge.
        ```fb-diagnosis
        {"findings":[
          {"title":"Glue and nail the back rail to its posts","severity":"serious","area":"joint","dimension":"joint_fit"},
          {"title":"Sand the top's front edge smooth","severity":"minor","area":"finish","dimension":"finish_prep"},
          {"title":"Fill the gap under the left arm","severity":"cosmetic","area":"finish","dimension":"finish_prep"}
        ]}
        ```
    """.trimIndent()

    /** What the diagnosis turn sent: the opening and the photographs, then the plan's reply and the run. */
    private val requestMessages = listOf(
        ChatMessage("u1", Role.USER, "I have finished this sofa."),
        ChatMessage("a1", Role.ASSISTANT, "Take these photos."),
        ChatMessage("u2", Role.USER, "Here are the shots.", composed = true),
    )

    private val titles = listOf(
        "Glue and nail the back rail to its posts",
        "Sand the top's front edge smooth",
        "Fill the gap under the left arm",
    )

    private fun diagnosis(effort: String? = "medium") = DetailPrefetcher.Diagnosis(
        userId = "user-1",
        sessionId = "session-1",
        systemPrompt = "FUNDI PROMPT",
        promptSha = "sha",
        requestMessages = requestMessages,
        photos = null,
        replyMessageId = "reply-1",
        replyText = diagnosisReply,
        findingTitles = titles,
        effort = effort,
    )

    private fun prefetcher(claude: ClaudeClient, details: FakeDetails, store: UsageStore = UsageStore()) = DetailPrefetcher(
        store = store,
        details = details,
        claude = claude,
        blobs = BlobStore(folder.newFolder()),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun `row 38 - a diagnosis starts six detail calls in parallel, for the first two improvements, and no more`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val claude = RecordingClaude(gate = gate)
        val details = FakeDetails()
        prefetcher(claude, details).schedule(diagnosis())

        // All six are waiting on Claude at once: none waited for another to finish.
        withTimeout(10_000) { while (claude.inFlight.get() < 6) delay(10) }
        delay(100)
        assertEquals(6, claude.inFlight.get())
        gate.complete(Unit)
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }

        assertEquals(6, claude.requests.size)
        val asked = claude.requests.map { it.history.last().text }.toSet()
        val expected = titles.take(2).flatMap { title -> DetailKind.entries.map { it.request(title) } }.toSet()
        assertEquals(expected, asked)
        assertFalse("the third improvement is answered on tap", asked.any { it.endsWith(titles[2]) })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), details.saved.map { it.findingIndex }.sortedBy { it })
        assertTrue(details.saved.all { it.source == "prefetch" && it.sourceMessageId == "reply-1" && it.openedMessageId == null })
    }

    @Test
    fun `row 38 - the prefetch requests share the diagnosis request's prefix byte for byte`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        prefetcher(claude, details).schedule(diagnosis())
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }

        // Assembled as AnthropicClient assembles them, with the same serialiser.
        fun json(history: List<ChatMessage>, effort: String?) = AnthropicRequest.json.encodeToString(
            AnthropicRequest.build("FUNDI PROMPT", history, { null }, { "" }, effort = effort),
        )
        val diagnosisRequest = json(requestMessages, "medium")
        val prefixMessages = requestMessages + ChatMessage("reply-1", Role.ASSISTANT, diagnosisReply)

        claude.requests.forEach { request ->
            assertEquals("the diagnosis request, then its reply, then one turn", prefixMessages, request.history.dropLast(1))
            assertEquals("FUNDI PROMPT", request.systemPrompt)
            val sent = json(request.history, request.effort)
            // Everything the diagnosis request sent up to and including the text of its last
            // block, where its breakpoint sat, is the start of every prefetch, byte for byte:
            // each reads what the diagnosis request cached. The marker itself moves to the
            // prefetch's own last block, as it moves on every turn.
            val lastBlock = "\"text\":\"Here are the shots.\""
            val cached = diagnosisRequest.substringBefore(lastBlock) + lastBlock
            assertTrue(sent, sent.startsWith(cached))
            // One rolling breakpoint, on the last block, as on every turn, plus the system's.
            assertEquals(2, Regex("cache_control").findAll(sent).count())
            assertTrue("the effort is part of the request", sent.endsWith("\"output_config\":{\"effort\":\"medium\"}}"))
        }
        // And the six differ only in their last turn.
        assertEquals(1, claude.requests.map { it.history.dropLast(1) }.toSet().size)
    }

    @Test
    fun `row 38 - a compacted diagnosis's photo shelf, and so its tool, goes with every prefetch`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        val shelf = PhotoShelf(listOf(Attachment("p1", "/blobs/abc.jpg")))
        prefetcher(claude, details).schedule(diagnosis().copy(photos = shelf))
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }
        // Tools render ahead of the system prompt, so a prefetch without the shelf would
        // miss the whole cached prefix.
        assertTrue(claude.requests.all { it.photos === shelf })
    }

    @Test
    fun `row 38 - an assessment's prefetches stop at its cap, and later taps ask Claude`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        val prefetcher = DetailPrefetcher(
            store = UsageStore(), details = details, claude = claude,
            blobs = BlobStore(folder.newFolder()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            perSession = 6,
        )
        prefetcher.schedule(diagnosis())
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }

        // A corrected diagnosis with new improvements, past the cap.
        prefetcher.schedule(diagnosis().copy(replyMessageId = "reply-2", findingTitles = listOf("Re-cut the left leg", "Oil the top")))
        delay(300)
        assertEquals(6, claude.requests.size)
        assertNull(prefetcher.answerFor("session-1", DetailKind.CAUSE, "Re-cut the left leg"))
    }

    @Test
    fun `row 50 - the prefetches ask for the fundi effort`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        prefetcher(claude, details).schedule(diagnosis(effort = "medium"))
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }
        assertEquals(setOf("medium"), claude.requests.map { it.effort }.toSet())
        assertTrue(details.saved.all { it.effort == "medium" })
    }

    @Test
    fun `row 38 - each answer is stored once, and a diagnosis sent again with the same improvements asks nothing`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        val prefetcher = prefetcher(claude, details)
        prefetcher.schedule(diagnosis())
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }

        prefetcher.schedule(diagnosis().copy(replyMessageId = "reply-2"))
        delay(300)
        assertEquals(6, claude.requests.size)
        assertEquals(6, details.saved.size)
    }

    @Test
    fun `row 38 - a tap on a stored answer makes no Claude call`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        val prefetcher = prefetcher(claude, details)
        prefetcher.schedule(diagnosis())
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }

        val stored = prefetcher.answerFor("session-1", DetailKind.CAUSE, "  ${titles[0]} ")
        assertEquals("answer to ${DetailKind.CAUSE.request(titles[0])}", stored?.text)
        assertEquals("no call beyond the six", 6, claude.requests.size)
    }

    @Test
    fun `row 38 - a tap while the answer is being written waits for it and makes no second call`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val claude = RecordingClaude(gate = gate)
        val details = FakeDetails()
        val prefetcher = prefetcher(claude, details)
        prefetcher.schedule(diagnosis())
        withTimeout(10_000) { while (claude.inFlight.get() < 6) delay(10) }

        val tap = async(Dispatchers.Default) { prefetcher.answerFor("session-1", DetailKind.NEXT_TIME, titles[1]) }
        delay(200)
        assertFalse("still writing", tap.isCompleted)
        gate.complete(Unit)

        assertEquals("answer to ${DetailKind.NEXT_TIME.request(titles[1])}", withTimeout(10_000) { tap.await() }?.text)
        assertEquals(6, claude.requests.size)
    }

    @Test
    fun `row 38 - a prefetch that fails is logged, not retried, and the tap then asks Claude`() = runBlocking {
        val claude = RecordingClaude(result = ClaudeResult.Failure(UpstreamError.OVERLOADED, "busy", httpStatus = 529))
        val details = FakeDetails()
        val usage = UsageStore()
        val prefetcher = prefetcher(claude, details, usage)
        prefetcher.schedule(diagnosis())
        withTimeout(10_000) { while (usage.rows.size < 6) delay(10) }
        delay(200)

        assertEquals(6, claude.requests.size)
        assertTrue(details.saved.isEmpty())
        assertTrue(usage.rows.all { it == "detail_prefetch" to Audience.FUNDI })
        assertNull("nothing stored, so the route asks Claude", prefetcher.answerFor("session-1", DetailKind.CAUSE, titles[0]))
    }

    @Test
    fun `row 38 - each prefetch is billed to Fundi Bora under its own call kind`() = runBlocking {
        val claude = RecordingClaude()
        val details = FakeDetails()
        val usage = UsageStore()
        prefetcher(claude, details, usage).schedule(diagnosis())
        withTimeout(10_000) { while (details.saved.size < 6) delay(10) }
        assertEquals(List(6) { "detail_prefetch" to Audience.FUNDI }, usage.rows.toList())
        assertTrue(details.saved.all { it.usageEventId != null })
    }

    // ------------------------------------------------------------------ fakes

    private data class Request(
        val systemPrompt: String,
        val history: List<ChatMessage>,
        val photos: PhotoShelf?,
        val effort: String?,
    )

    /** Answers each detail with its own request's text, after [gate] if given. */
    private class RecordingClaude(
        private val gate: CompletableDeferred<Unit>? = null,
        private val result: ClaudeResult? = null,
    ) : ClaudeClient {
        val requests = CopyOnWriteArrayList<Request>()
        val inFlight = AtomicInteger()

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
            requests += Request(systemPrompt, history, photos, effort)
            inFlight.incrementAndGet()
            try {
                gate?.await()
            } finally {
                inFlight.decrementAndGet()
            }
            return result ?: ClaudeResult.Success("answer to ${history.last().text}", TokenUsage(10, 5, 0, 90), "claude-sonnet-5-5")
        }
    }

    private class FakeDetails : DetailAnswerStore {
        val saved = CopyOnWriteArrayList<NewDetailAnswer>()
        val opened = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun save(answer: NewDetailAnswer): String {
            saved += answer
            return "detail-${saved.size}"
        }

        override suspend fun find(sessionId: String, kind: DetailKind, title: String): StoredDetail? =
            saved.lastOrNull { it.sessionId == sessionId && it.kind == kind && it.findingTitle == title.trim() }
                ?.let { StoredDetail("detail-${saved.indexOf(it) + 1}", it.text) }

        override suspend fun markOpened(answerId: String, messageId: String) {
            opened += answerId to messageId
        }
    }

    /** Only the usage rows: nothing else of the chat store is the prefetcher's to touch. */
    private class UsageStore : ChatStore {
        val rows = CopyOnWriteArrayList<Pair<String, Audience>>()
        private val ids = AtomicInteger()

        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?,
            httpStatus: Int?, latencyMillis: Long, errorKind: String?, audience: Audience, callKind: String,
        ) {
            rows += callKind to audience
        }

        override suspend fun recordUsageReturningId(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?,
            httpStatus: Int?, latencyMillis: Long, errorKind: String?, audience: Audience, callKind: String,
        ): Long {
            rows += callKind to audience
            return ids.incrementAndGet().toLong()
        }

        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String, previousSessionId: String?,
            intakeAnswers: String?, promptSha: String?, audience: Audience, dailyLimit: Int,
            testerDailyLimit: Int, pieceId: String?, pieceLabel: String?,
            pieceStudio: com.qualityverifier.server.db.PieceStudio, isFinal: Boolean,
            workers: List<WorkerSlot>, appVersionCode: Int?,
        ): SessionAccess = error("the prefetcher never starts an assessment, so it never counts against the allowance")

        override suspend fun replaceSessionWorkers(userId: String, sessionId: String, workers: List<WorkerSlot>) = error("unused")
        override suspend fun recordSessionLocation(
            sessionId: String, userId: String, location: com.qualityverifier.server.routes.SessionLocation,
        ) = error("unused")
        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>,
            composed: Boolean, photoMeta: Map<String, JsonObject>,
        ): Boolean = error("a prefetch never enters the conversation")
        override suspend fun replyAfter(sessionId: String, userMessageId: String) = error("unused")
        override suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage> =
            error("the prefetcher uses the diagnosis request's own messages")
        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String, verdictLevelId: String?,
            verdictLanguage: String?, defectCount: ((List<String>) -> Int?)?,
        ): String = error("a prefetch never enters the conversation")
        override suspend fun saveDigest(sessionId: String, digest: com.qualityverifier.server.db.Digest) = error("unused")
        override suspend fun digests(sessionId: String) = error("unused")
        override suspend fun sessionsFor(userId: String) = error("unused")
        override suspend fun sessionDetail(userId: String, sessionId: String) = error("unused")
        override suspend fun markClientDeleted(userId: String, sessionId: String) = error("unused")
        override suspend fun blobBelongsTo(userId: String, sha: String) = error("unused")
    }
}

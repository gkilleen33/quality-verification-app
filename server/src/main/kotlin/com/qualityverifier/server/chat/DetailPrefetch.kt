package com.qualityverifier.server.chat

import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.DetailAnswerStore
import com.qualityverifier.server.db.NewDetailAnswer
import com.qualityverifier.server.db.StoredDetail
import com.qualityverifier.text.DetailKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Writes the detail answers for the first improvements of a Fundi Bora diagnosis before
 * anybody taps, so a tap opens at once (release scope row 38; Nathan, 18 and 23 November
 * 2026; Grady agreed in `#i-firms-ai-qualityupgrading`).
 *
 * Since prompt E (#107) each improvement has detail buttons, and a tap sent a composed turn
 * and waited 5 to 12 seconds for Claude. Now, the moment a diagnosis is stored, this starts
 * one call per button for each of the first [IMPROVEMENTS] improvements, in parallel, and
 * stores each answer in `fundi_detail_answers` (V37). Improvements after those are answered
 * on tap, as before.
 *
 * **The conversation does not change.** A prefetched answer is stored beside the
 * conversation, never in it. When the fundi taps, the phone sends the same composed turn it
 * always has; the chat route finds the stored answer and stores it as the reply, with no
 * Claude call ([answerFor]). An answer never opened never enters the history any later turn
 * sends to Claude.
 *
 * **The prompt cache.** Each call is the diagnosis turn's own request, byte for byte (the
 * same system prompt, the same messages in the same view, the same photo shelf and the same
 * effort), plus the diagnosis reply and the button's composed turn at the end, with the one
 * rolling breakpoint `AnthropicRequest` always puts on the last block. So every call reads
 * the prefix the diagnosis request cached, and the calls differ from each other only in
 * their last turn. They start after the diagnosis is stored, never before.
 *
 * **The allowance** counts assessments started (`ensureSession`), which nothing here
 * touches, so prefetches leave it as taps do.
 *
 * **Failure.** A call that fails is logged in `usage_events` and not tried again here (the
 * client's own single retry on an overloaded API aside); the tap then asks Claude as before.
 * A fundi who leaves the assessment cancels nothing: the calls run on the server's own scope.
 *
 * In memory, as [Compactor] is: a restart loses the calls still running and nothing else,
 * and a tap on one of them asks Claude as it did before this existed.
 */
class DetailPrefetcher(
    private val store: ChatStore,
    private val details: DetailAnswerStore,
    private val claude: ClaudeClient,
    private val blobs: BlobStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How long a tap waits for an answer still being written before asking Claude itself. */
    private val wait: Duration = 3.minutes,
    /** The buttons prefetched, in the order the phone shows them. */
    private val kinds: List<DetailKind> = DetailKind.entries,
) {
    private val log = LoggerFactory.getLogger(DetailPrefetcher::class.java)
    private val pending = ConcurrentHashMap<String, Deferred<Unit>>()

    /** What the diagnosis turn sent Claude, and what came back, for [schedule]. */
    data class Diagnosis(
        val userId: String,
        val sessionId: String,
        val systemPrompt: String,
        val promptSha: String?,
        /** The messages the diagnosis request sent, in the view it sent them. */
        val requestMessages: List<ChatMessage>,
        /** The photo shelf it offered, if the conversation was compacted. */
        val photos: PhotoShelf?,
        /** The stored reply, by its id, and its text as stored. */
        val replyMessageId: String,
        val replyText: String,
        /** Every finding in the reply's diagnosis, by title, in order. */
        val findingTitles: List<String>,
        val effort: String?,
    )

    /** Starts the calls for [diagnosis]'s first improvements. Returns at once. */
    fun schedule(diagnosis: Diagnosis) {
        val prefix = diagnosis.requestMessages + ChatMessage(
            id = diagnosis.replyMessageId,
            role = Role.ASSISTANT,
            text = diagnosis.replyText,
        )
        diagnosis.findingTitles
            .mapIndexedNotNull { index, title -> title.trim().takeIf { it.isNotEmpty() }?.let { index to it } }
            .take(IMPROVEMENTS)
            .forEach { (index, title) ->
                kinds.forEach { kind ->
                    val key = keyOf(diagnosis.sessionId, kind, title)
                    // A title already being written for this session (a diagnosis sent again
                    // with the same improvement) is not paid for twice.
                    if (pending[key]?.isActive == true) return@forEach
                    val job = scope.async {
                        runCatching { prefetch(diagnosis, prefix, index, title, kind) }
                            .onFailure { log.warn("Could not prefetch {} for session {}", kind.id, diagnosis.sessionId, it) }
                        Unit
                    }
                    pending[key] = job
                    job.invokeOnCompletion { pending.remove(key, job) }
                }
            }
    }

    /**
     * The stored answer to a tap on [kind] of [title], waiting first for one still being
     * written. Null when there is none, and the tap then asks Claude as before.
     */
    suspend fun answerFor(sessionId: String, kind: DetailKind, title: String): StoredDetail? {
        pending[keyOf(sessionId, kind, title.trim())]?.let { job ->
            if (withTimeoutOrNull(wait) { job.await() } == null) {
                log.warn("A prefetched {} for session {} took longer than {}; asking on the tap", kind.id, sessionId, wait)
                return null
            }
        }
        return details.find(sessionId, kind, title.trim())
    }

    private suspend fun prefetch(
        diagnosis: Diagnosis,
        prefix: List<ChatMessage>,
        index: Int,
        title: String,
        kind: DetailKind,
    ) {
        // Asked once per session, title and button: a second diagnosis that names the same
        // improvement reads the answer already stored.
        if (details.find(diagnosis.sessionId, kind, title) != null) return
        val turn = ChatMessage(id = "detail-prefetch", role = Role.USER, text = kind.request(title), composed = true)

        val startedAt = System.currentTimeMillis()
        val result = claude.send(diagnosis.systemPrompt, prefix + turn, ::readBlob, diagnosis.photos, diagnosis.effort)
        val elapsed = System.currentTimeMillis() - startedAt

        when (result) {
            is ClaudeResult.Success -> {
                val usageId = store.recordUsageReturningId(
                    diagnosis.userId, diagnosis.sessionId, result.model, result.usage,
                    httpStatus = 200, latencyMillis = elapsed, errorKind = null,
                    audience = Audience.FUNDI, callKind = CALL_KIND,
                )
                details.save(
                    NewDetailAnswer(
                        sessionId = diagnosis.sessionId,
                        sourceMessageId = diagnosis.replyMessageId,
                        findingIndex = index,
                        findingTitle = title,
                        kind = kind,
                        source = SOURCE_PREFETCH,
                        text = result.text,
                        model = result.model,
                        effort = diagnosis.effort,
                        promptSha = diagnosis.promptSha,
                        usageEventId = usageId,
                    ),
                )
                log.info("Prefetched {} of improvement {} for session {} in {} ms", kind.id, index + 1, diagnosis.sessionId, elapsed)
            }
            is ClaudeResult.Failure -> store.recordUsage(
                diagnosis.userId, diagnosis.sessionId, null, result.usage,
                httpStatus = result.httpStatus, latencyMillis = elapsed,
                errorKind = result.error.name, audience = Audience.FUNDI, callKind = CALL_KIND,
            )
        }
    }

    private fun readBlob(attachment: Attachment): ByteArray? =
        blobs.read(File(attachment.path).nameWithoutExtension)

    private fun keyOf(sessionId: String, kind: DetailKind, title: String) = "$sessionId|${kind.id}|$title"

    companion object {
        /** The improvements prefetched: the first two the reply lists (Nathan, 23 November 2026). */
        const val IMPROVEMENTS = 2

        /** `usage_events.call_kind` for a prefetch (V37). A tap's call stays "turn". */
        const val CALL_KIND = "detail_prefetch"

        /** `fundi_detail_answers.source`. */
        const val SOURCE_PREFETCH = "prefetch"
        const val SOURCE_TAP = "tap"
    }
}

package com.qualityverifier.server.chat

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.Digest
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
import kotlin.time.Duration.Companion.seconds

/**
 * What the model is sent on a turn: the conversation as stored, or its compacted form.
 *
 * Compacted once a verdict or a diagnosis has been given. From then on a turn carries the
 * model's own notes on the assessment, the reply they cover — verbatim, so a verdict can
 * be corrected from exactly what it said — and whatever has been said since. The
 * photographs before the notes stay on the [photos] shelf, one tool call away.
 */
data class ModelView(val messages: List<ChatMessage>, val photos: PhotoShelf?) {
    val isCompacted: Boolean get() = photos != null
}

object Compaction {

    /**
     * The header on the notes when they stand in for the conversation. The model wrote
     * them, but it reads them as a user turn, so it is told whose they are.
     */
    const val NOTES_HEADER =
        "[Written by the app, not the person you are talking to.] Your own notes on this " +
            "assessment so far, which replace the earlier messages and their photographs. " +
            "To look at a photograph again, use view_photos with its number.\n\n"

    /** Every photograph in the conversation, in the order it was attached. */
    fun photosOf(history: List<ChatMessage>): List<Attachment> =
        history.filter { it.role == Role.USER }.flatMap { it.attachments }

    /**
     * The newest notes that still fit [history], applied; or the history as it is.
     *
     * "Still fit" is two checks. The reply the notes cover must be in the conversation,
     * and the photographs must not have changed in number up to that point, because the
     * notes refer to them by number. A mismatch means the notes are describing some other
     * conversation, and the honest thing is to send this one in full.
     */
    fun forModel(history: List<ChatMessage>, digests: List<Digest>): ModelView {
        for (digest in digests) {
            val at = history.indexOfFirst { it.id == digest.messageId }
            if (at < 0 || history[at].role != Role.ASSISTANT) continue
            if (photosOf(history.subList(0, at + 1)).size != digest.photoCount) continue
            val notes = ChatMessage(
                id = "notes-${digest.messageId}",
                role = Role.USER,
                text = NOTES_HEADER + digest.text,
                composed = true,
            )
            return ModelView(
                messages = listOf(notes) + history.subList(at, history.size),
                photos = PhotoShelf(photosOf(history)),
            )
        }
        return ModelView(history, photos = null)
    }

    /**
     * The request for notes, appended as a user turn after the reply they will cover.
     *
     * Ends with the photo list, written from the conversation rather than left to the
     * model to count: the numbers have to match the ones [PhotoShelf] will later resolve,
     * and an off-by-one here would show the model the wrong joint when it looks again.
     */
    fun requestTurn(instruction: String, history: List<ChatMessage>, view: ModelView): ChatMessage {
        val visible = view.messages.map { it.id }.toSet()
        var next = 1
        val lines = history.filter { it.role == Role.USER && it.attachments.isNotEmpty() }.map { message ->
            val first = next
            val last = next + message.attachments.size - 1
            next = last + 1
            val which = if (first == last) "Photo $first" else "Photos $first to $last"
            val opening = message.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
            val where = when {
                opening == null -> "attached to a message with no text"
                else -> "attached to the message that begins \"$opening\""
            }
            // A second set of notes, after a correction, is written from a conversation that
            // already starts with the first set; the photographs those cover are not attached.
            val shown = if (message.id in visible) "" else " (described in your earlier notes; not attached now)"
            "- $which: $where$shown."
        }
        return ChatMessage(
            id = "compact-request",
            role = Role.USER,
            text = instruction.trimEnd() + "\n" + lines.joinToString("\n"),
            composed = true,
        )
    }

    /**
     * Whether a reply is worth compacting after.
     *
     * A verdict or a diagnosis is the assessment given; what follows is questions about it.
     * A conversation with no photographs has nothing to save by compacting.
     */
    fun worthCompacting(hasVerdict: Boolean, hasDiagnosis: Boolean, history: List<ChatMessage>): Boolean =
        (hasVerdict || hasDiagnosis) && photosOf(history).isNotEmpty()
}

/**
 * Writes an assessment's notes in the background, straight after the reply they cover.
 *
 * Started the moment the reply is stored, so it is usually finished by the time anybody
 * has read the verdict and typed a question. When it is not, the next turn waits for it —
 * [awaitPending] — rather than paying for the full conversation and then paying again
 * for the notes.
 *
 * The request is the turn that was just answered, plus the reply, plus the instruction:
 * the same system prompt and the same message prefix, so nearly all of it is read back
 * from the prompt cache instead of being processed again.
 *
 * In memory, deliberately. A restart loses a pending job and nothing else: the next turn
 * finds no notes and sends the conversation in full, which is exactly what happened before
 * any of this existed.
 */
class Compactor(
    private val store: ChatStore,
    private val claude: ClaudeClient,
    private val prompts: PromptRepository,
    private val blobs: BlobStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val wait: Duration = 90.seconds,
) {
    private val log = LoggerFactory.getLogger(Compactor::class.java)
    private val pending = ConcurrentHashMap<String, Deferred<Unit>>()

    fun schedule(userId: String, sessionId: String, systemPrompt: String, replyMessageId: String) {
        val job = scope.async {
            runCatching { compact(userId, sessionId, systemPrompt, replyMessageId) }
                .onFailure { log.warn("Could not write notes for session {}", sessionId, it) }
            Unit
        }
        pending[sessionId] = job
        job.invokeOnCompletion { pending.remove(sessionId, job) }
    }

    /** Waits for notes being written for this session, if any. Gives up after [wait]. */
    suspend fun awaitPending(sessionId: String) {
        val job = pending[sessionId] ?: return
        if (withTimeoutOrNull(wait) { job.await() } == null) {
            log.warn("Notes for session {} took longer than {}; sending the full conversation", sessionId, wait)
        }
    }

    private suspend fun compact(userId: String, sessionId: String, systemPrompt: String, replyMessageId: String) {
        val history = store.history(sessionId) { sha -> blobs.pathFor(sha).absolutePath }
        val upTo = history.indexOfFirst { it.id == replyMessageId }
        if (upTo < 0) return
        val covered = history.subList(0, upTo + 1)
        val view = Compaction.forModel(covered, store.digests(sessionId))
        val request = Compaction.requestTurn(prompts.compactionPrompt(), covered, view)

        val startedAt = System.currentTimeMillis()
        val result = claude.send(systemPrompt, view.messages + request, ::readBlob, view.photos)
        val elapsed = System.currentTimeMillis() - startedAt

        when (result) {
            is ClaudeResult.Success -> {
                store.saveDigest(
                    sessionId,
                    Digest(replyMessageId, result.text, Compaction.photosOf(covered).size),
                )
                store.recordUsage(
                    userId, sessionId, result.model, result.usage,
                    httpStatus = 200, latencyMillis = elapsed, errorKind = null, callKind = CALL_KIND,
                )
                log.info("Wrote notes for session {} in {} ms", sessionId, elapsed)
            }
            is ClaudeResult.Failure -> store.recordUsage(
                userId, sessionId, null, result.usage,
                httpStatus = result.httpStatus, latencyMillis = elapsed,
                errorKind = result.error.name, callKind = CALL_KIND,
            )
        }
    }

    private fun readBlob(attachment: Attachment): ByteArray? =
        blobs.read(File(attachment.path).nameWithoutExtension)

    private companion object {
        const val CALL_KIND = "digest"
    }
}

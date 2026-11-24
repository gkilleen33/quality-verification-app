package com.qualityverifier.server.chat

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.NewTranslation
import com.qualityverifier.server.db.TranslationStore
import com.qualityverifier.text.ReplyTranslation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Translates a Fundi Bora assessment's replies into the other language when the maker first
 * switches (v0.7.0, release scope row 29; Nathan, 16 November 2026: translate when toggled,
 * not every reply in advance).
 *
 * **What it translates.** Every reply Claude wrote in the assessment, as stored: the replies
 * to the maker's turns, the detail answers the maker opened and the answers on an
 * improvement's page, which are all replies. Not a detail answer that was prefetched and never
 * opened, which nobody reads, and not the maker's own words.
 *
 * **One call per reply, once.** Each translation is kept (`message_translations`, V41) and
 * served from there to every phone signed in to the account, with no second call. Two taps
 * at once share one call. A reply whose translation fails is tried again on the next tap, at
 * most [MAX_ATTEMPTS] times in this process, so a reply Claude cannot translate cleanly does
 * not cost a call on every tap.
 *
 * **Never in the conversation.** A translation is written to its own table and read only by
 * [translate]. Nothing here touches `messages`, and the call is a conversation of its own (the
 * translation prompt and one turn), so the assessment's cached prefix is untouched.
 *
 * **Checked before it is kept** ([ReplyTranslation.problem]): the same blocks, the same JSON
 * shape, the same enums and numbers. A translation that fails the check is not stored, and the
 * phone keeps the original and says so.
 *
 * Billed as `usage_events.call_kind` 'translation', to Fundi Bora, outside the daily allowance,
 * which counts assessments started.
 */
class ReplyTranslator(
    private val store: ChatStore,
    private val translations: TranslationStore,
    private val claude: ClaudeClient,
    private val prompts: PromptRepository,
    /** The calls run here, so a phone that gives up on the request does not cancel them. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How long a request waits for its calls before answering with what is ready. */
    private val wait: Duration = WAIT,
    parallelism: Int = MAX_IN_FLIGHT,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private val log = LoggerFactory.getLogger(ReplyTranslator::class.java)
    private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val inFlight = Semaphore(parallelism)

    /**
     * What a request gets back: every stored translation of the assessment's replies, by the
     * hash of the original's text, and the hashes of replies with none yet (failed, refused by
     * the check, or still being written when [wait] ran out).
     */
    data class Outcome(val translations: Map<String, String>, val failed: List<String>)

    /**
     * Translates every reply of the assessment [sessionId] that has no translation into [into]
     * yet, and returns them all. Null when the assessment is not this account's.
     */
    suspend fun translate(userId: String, sessionId: String, into: AssessmentLanguage): Outcome? {
        val (_, rows) = store.sessionDetail(userId, sessionId) ?: return null
        // One translation per text: a reply stored twice (a replayed turn) is one call.
        val replies = rows.filter { it.role == ASSISTANT && it.text.isNotBlank() }
            .distinctBy { ReplyTranslation.keyOf(it.text) }
        val keys = replies.map { ReplyTranslation.keyOf(it.text) }
        val stored = translations.find(sessionId, into.code)
        val missing = replies.filter { ReplyTranslation.keyOf(it.text) !in stored }
        if (missing.isEmpty()) return Outcome(stored.filterKeys { it in keys }, emptyList())

        val jobs = missing.mapNotNull { reply -> start(userId, sessionId, reply, into) }
        // join, not await: a job that failed has already been logged, and the answer lists it.
        withTimeoutOrNull(wait) { jobs.forEach { it.join() } }
            ?: log.info("Translations for session {} took longer than {}; answering with what is ready", sessionId, wait)
        val now = translations.find(sessionId, into.code)
        return Outcome(now.filterKeys { it in keys }, keys.filterNot { it in now })
    }

    /** The call for [reply], or the one already running for it; null once it has failed too often. */
    private fun start(userId: String, sessionId: String, reply: MessageRow, into: AssessmentLanguage): Deferred<Unit>? {
        val key = "$sessionId|${into.code}|${ReplyTranslation.keyOf(reply.text)}"
        pending[key]?.takeIf { !it.isCompleted }?.let { return it }
        if ((failures[key]?.get() ?: 0) >= maxAttempts) return null
        // Lazy, so a request that loses the race below never starts a call of its own.
        val job = scope.async(start = CoroutineStart.LAZY) {
            val ok = runCatching { translateOne(userId, sessionId, reply, into) }
                .onFailure { log.warn("Could not translate a reply of session {}", sessionId, it) }
                .getOrDefault(false)
            if (!ok) failures.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            Unit
        }
        while (true) {
            val existing = pending.putIfAbsent(key, job) ?: break
            if (!existing.isCompleted) return existing
            // A finished call's entry not yet removed: replace it.
            if (pending.replace(key, existing, job)) break
        }
        job.invokeOnCompletion { pending.remove(key, job) }
        job.start()
        return job
    }

    /** One call. True when a translation was kept. */
    private suspend fun translateOne(userId: String, sessionId: String, reply: MessageRow, into: AssessmentLanguage): Boolean {
        val prompt = prompts.translationPrompt()
        val turn = ChatMessage(id = "translate-${reply.id}", role = Role.USER, text = ReplyTranslation.request(reply.text, into))
        val (result, elapsed) = inFlight.withPermit {
            val startedAt = System.currentTimeMillis()
            claude.send(prompt, listOf(turn), { null }, null, EFFORT) to (System.currentTimeMillis() - startedAt)
        }
        return when (result) {
            is ClaudeResult.Success -> {
                val usageId = store.recordUsageReturningId(
                    userId, sessionId, result.model, result.usage,
                    httpStatus = 200, latencyMillis = elapsed, errorKind = null,
                    audience = Audience.FUNDI, callKind = CALL_KIND,
                )
                val text = ReplyTranslation.answerOf(result.text)
                val problem = ReplyTranslation.problem(reply.text, text)
                if (problem != null) {
                    // The reason names a block and a field, never the reply's words.
                    log.warn("Refused a translation of a reply of session {} into {}: {}", sessionId, into.code, problem)
                    return false
                }
                translations.save(
                    NewTranslation(
                        sessionId = sessionId,
                        messageId = reply.id,
                        sourceSha256 = ReplyTranslation.keyOf(reply.text),
                        language = into.code,
                        text = text,
                        model = result.model,
                        promptSha = sha256Of(prompt),
                        usageEventId = usageId,
                    ),
                )
                log.info("Translated a reply of session {} into {} in {} ms", sessionId, into.code, elapsed)
                true
            }
            is ClaudeResult.Failure -> {
                store.recordUsage(
                    userId, sessionId, null, result.usage,
                    httpStatus = result.httpStatus, latencyMillis = elapsed,
                    errorKind = result.error.name, audience = Audience.FUNDI, callKind = CALL_KIND,
                )
                false
            }
        }
    }

    companion object {
        /** `usage_events.call_kind` for a translation (V41). */
        const val CALL_KIND = "translation"

        /**
         * The thinking effort a translation asks for. Translating needs little reasoning, and
         * low keeps a long diagnosis's translation quick; the model is the assessments' own
         * (AnthropicRequest.MODEL), so the two use the same words for the same things.
         */
        const val EFFORT = "low"

        /** Translation calls in flight at once, server-wide. */
        const val MAX_IN_FLIGHT = 8

        /** Failed calls for one reply and language before the server stops trying, until a restart. */
        const val MAX_ATTEMPTS = 3

        /**
         * Under nginx's 180 seconds and the phone's 200-second read timeout, so the phone gets
         * an answer, with the replies still being written listed as not translated yet.
         */
        val WAIT: Duration = 150.seconds

        private const val ASSISTANT = "ASSISTANT"

        private fun sha256Of(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

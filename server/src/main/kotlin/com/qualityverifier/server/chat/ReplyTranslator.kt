package com.qualityverifier.server.chat

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
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
 * **One call per reply, once per prompt.** Each translation is kept (`message_translations`,
 * V41) with the fingerprint of the prompt that wrote it, and served from there to every phone
 * signed in to the account, with no second call, for as long as that prompt is the current
 * one. Two taps at once share one call. A reply whose translation the check refuses is tried
 * again on the next tap, at most [MAX_ATTEMPTS] refusals in this process, so a reply Claude
 * cannot translate cleanly does not cost a call on every tap; a call that failed on its way
 * (overloaded, the network) is not counted. The call carries no cache breakpoint.
 *
 * **Never in the conversation.** A translation is written to its own table and read only by
 * [translate]. Nothing here touches `messages`, and the call is a conversation of its own (the
 * translation prompt and one turn), so the assessment's cached prefix is untouched.
 *
 * **Checked before it is kept** ([ReplyTranslation.problem]): the same blocks, the same JSON
 * shape, the same enums, the same numbers in prose and values, as many paragraphs, and a length
 * in proportion. A translation that fails the check is not stored, and the phone keeps the
 * original and says so.
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

    /** What a request gets back. */
    sealed interface Outcome {
        /**
         * Every stored translation of the assessment's replies by the current prompt, by the
         * hash of the original's text, and the hashes of replies with none yet (failed,
         * refused by the check, or still being written when [wait] ran out).
         */
        data class Translated(val translations: Map<String, String>, val failed: List<String>) : Outcome

        /**
         * The assessment's intake chose this language, so Claude already wrote every reply in
         * it: nothing to translate, and no call is made. The phone shows those replies as written.
         */
        data object SameLanguage : Outcome
    }

    /**
     * Translates every reply of the assessment [sessionId] that has no translation into [into]
     * by the current translation prompt yet, and returns them all. Null when the assessment is
     * not this account's.
     */
    suspend fun translate(userId: String, sessionId: String, into: AssessmentLanguage): Outcome? {
        val (session, rows) = store.sessionDetail(userId, sessionId) ?: return null
        // An assessment from before the intake code was stored has no language here; both are
        // then allowed, as the phone, which knows it, asks only for the other.
        if (FundiIntake.decode(session.intakeAnswers)?.first == into) return Outcome.SameLanguage
        // Read once per request, so every reply of it is translated, stored and served by the
        // same prompt. A new prompt (row 16's glossary) has a new fingerprint: the translations
        // the old one wrote are no longer served, and each reply is translated again on the
        // next switch.
        val prompt = prompts.translationPrompt()
        val promptSha = sha256Of(prompt)
        // One translation per text: a reply stored twice (a replayed turn) is one call.
        val replies = rows.filter { it.role == ASSISTANT && it.text.isNotBlank() }
            .distinctBy { ReplyTranslation.keyOf(it.text) }
        val keys = replies.map { ReplyTranslation.keyOf(it.text) }
        val stored = translations.find(sessionId, into.code, promptSha)
        val missing = replies.filter { ReplyTranslation.keyOf(it.text) !in stored }
        if (missing.isEmpty()) return Outcome.Translated(stored.filterKeys { it in keys }, emptyList())

        val jobs = missing.mapNotNull { reply -> start(userId, sessionId, reply, into, prompt, promptSha) }
        // join, not await: a job that failed has already been logged, and the answer lists it.
        withTimeoutOrNull(wait) { jobs.forEach { it.join() } }
            ?: log.info("Translations for session {} took longer than {}; answering with what is ready", sessionId, wait)
        val now = translations.find(sessionId, into.code, promptSha)
        return Outcome.Translated(now.filterKeys { it in keys }, keys.filterNot { it in now })
    }

    /** What one call came to. Only [REFUSED] counts toward [maxAttempts]. */
    private enum class Attempt {
        KEPT,

        /** Claude wrote a translation and the check refused it: likely to happen again. */
        REFUSED,

        /**
         * The call did not return a translation: overloaded, rate-limited, the network, a
         * database error. Transient, so not counted; the next tap tries again.
         */
        FAILED,
    }

    /** The call for [reply], or the one already running for it; null once it has been refused too often. */
    private fun start(
        userId: String,
        sessionId: String,
        reply: MessageRow,
        into: AssessmentLanguage,
        prompt: String,
        promptSha: String,
    ): Deferred<Unit>? {
        // The prompt's fingerprint is part of the key, so a new prompt starts with no refusals.
        val key = "$sessionId|${into.code}|${ReplyTranslation.keyOf(reply.text)}|$promptSha"
        pending[key]?.takeIf { !it.isCompleted }?.let { return it }
        if ((failures[key]?.get() ?: 0) >= maxAttempts) return null
        // Lazy, so a request that loses the race below never starts a call of its own.
        val job = scope.async(start = CoroutineStart.LAZY) {
            val attempt = runCatching { translateOne(userId, sessionId, reply, into, prompt, promptSha) }
                .onFailure { log.warn("Could not translate a reply of session {}", sessionId, it) }
                .getOrDefault(Attempt.FAILED)
            if (attempt == Attempt.REFUSED) failures.getOrPut(key) { AtomicInteger() }.incrementAndGet()
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

    /** One call. */
    private suspend fun translateOne(
        userId: String,
        sessionId: String,
        reply: MessageRow,
        into: AssessmentLanguage,
        prompt: String,
        promptSha: String,
    ): Attempt {
        val turn = ChatMessage(id = "translate-${reply.id}", role = Role.USER, text = ReplyTranslation.request(reply.text, into))
        val (result, elapsed) = inFlight.withPermit {
            val startedAt = System.currentTimeMillis()
            // No cache breakpoint: each reply is translated once per language and prompt, so
            // nothing would read a cached prefix, and a one-hour write costs twice the input.
            claude.sendUncached(prompt, listOf(turn), EFFORT) to (System.currentTimeMillis() - startedAt)
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
                    return Attempt.REFUSED
                }
                translations.save(
                    NewTranslation(
                        sessionId = sessionId,
                        messageId = reply.id,
                        sourceSha256 = ReplyTranslation.keyOf(reply.text),
                        language = into.code,
                        text = text,
                        model = result.model,
                        promptSha = promptSha,
                        usageEventId = usageId,
                    ),
                )
                log.info("Translated a reply of session {} into {} in {} ms", sessionId, into.code, elapsed)
                Attempt.KEPT
            }
            is ClaudeResult.Failure -> {
                store.recordUsage(
                    userId, sessionId, null, result.usage,
                    httpStatus = result.httpStatus, latencyMillis = elapsed,
                    errorKind = result.error.name, audience = Audience.FUNDI, callKind = CALL_KIND,
                )
                // Already retried inside the client where that helps (overloaded, rate limit,
                // server); not the translation's fault, so the next tap may try again.
                Attempt.FAILED
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

        /**
         * Translations Claude wrote that the check refused, for one reply, language and prompt,
         * before the server stops asking, until a restart. A call that failed on its way
         * (overloaded, the network) does not count.
         */
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

package com.qualityverifier.text

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.Role

/**
 * The three buttons under a Fundi Bora finding, and the message each one sends.
 *
 * Release scope row 37 (Nathan, 17 November 2026): the first reply no longer writes what
 * happened, the cause and the habit for every finding, so it arrives sooner. The maker asks
 * for each one with a button, and Claude answers that one only. The request is an ordinary
 * turn the app writes (composed), so the server, the history and the cache treat it as any
 * other turn; nothing new goes on the wire.
 *
 * The prefixes are the protocol between the app and `prompts/fundi-master.txt` ("Detail on
 * request"), which names them word for word. They stay in English in a Kiswahili
 * assessment; the prompt says to answer in the assessment's language. Change one side and
 * the other in the same PR.
 */
enum class DetailKind(val requestPrefix: String) {
    /** what_happened: what the photographs and checks show. */
    WHAT_WE_FOUND("More on what we found: "),

    /** manufacturing_cause: the step in the making where it went wrong. */
    CAUSE("More on what's causing this: "),

    /** root_habit: the habit to build. */
    NEXT_TIME("How to upgrade next time: "),
    ;

    /** The turn the app sends for the finding titled [title]. */
    fun request(title: String): String = requestPrefix + title.trim()

    /** The same detail when the reply already carries it: older replies write all three. */
    fun storedIn(finding: Finding): String = when (this) {
        WHAT_WE_FOUND -> finding.whatHappened
        CAUSE -> finding.manufacturingCause
        NEXT_TIME -> finding.rootHabit
    }

    companion object {
        /** The kind and finding title a turn asks about, or null for any other turn. */
        fun parse(text: String): Pair<DetailKind, String>? =
            entries.firstNotNullOfOrNull { kind ->
                if (text.startsWith(kind.requestPrefix)) kind to text.removePrefix(kind.requestPrefix).trim() else null
            }
    }
}

/** One finding's detail of one kind: the finding is named by its title, as the request names it. */
data class DetailKey(val kind: DetailKind, val title: String)

/**
 * What the conversation holds of the detail buttons.
 *
 * @property answers each answered request's reply, as plain text, to show under its finding.
 * @property pending requests sent and not yet answered.
 * @property hidden message ids the conversation list leaves out, because the finding shows
 *   them instead: each request, and each answer that is only the detail.
 */
data class DetailAnswers(
    val answers: Map<DetailKey, String> = emptyMap(),
    val pending: Set<DetailKey> = emptySet(),
    val hidden: Set<String> = emptySet(),
) {
    companion object {
        val NONE = DetailAnswers()
    }
}

/**
 * Pairs each detail request in [messages] with the reply after it.
 *
 * A reply that carries a block (a corrected diagnosis, a fix plan) stays in the
 * conversation, because writing the detail out showed something was wrong and the maker has
 * to see the correction where corrections appear. Its request is still hidden: the maker
 * did not type it. A request that only a maker could have typed, without the composed flag,
 * is an ordinary turn and is left alone.
 */
fun detailAnswersOf(messages: List<ChatMessage>): DetailAnswers {
    val answers = mutableMapOf<DetailKey, String>()
    val pending = mutableSetOf<DetailKey>()
    val hidden = mutableSetOf<String>()
    messages.forEachIndexed { index, message ->
        if (message.role != Role.USER || !message.composed) return@forEachIndexed
        val (kind, title) = DetailKind.parse(message.text) ?: return@forEachIndexed
        val key = DetailKey(kind, title)
        hidden += message.id
        val reply = messages.getOrNull(index + 1)?.takeIf { it.role == Role.ASSISTANT }
        if (reply == null) {
            pending += key
            return@forEachIndexed
        }
        val content = parseAssistantContent(reply.text)
        val onlyProse = !content.sentDiagnosis && content.verdict == null && content.fixPlan == null && content.plan == null
        if (onlyProse) {
            hidden += reply.id
            answers[key] = markdownToPlainText(content.displayProse).trim()
        }
    }
    return DetailAnswers(answers, pending, hidden)
}

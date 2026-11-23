package com.qualityverifier.text

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Role

/**
 * A question the maker asks on one improvement's page (v0.7.0, release scope row 43).
 *
 * The page has its own chat, about that improvement only. The question is an ordinary turn of
 * the assessment's one conversation, so the server, the history and the cache treat it as any
 * other; what marks it is the start of its text, "About improvement N, '<title>': ", which
 * `prompts/fundi-master.txt` ("Questions about one improvement") names word for word. Change
 * one side and the other in the same PR.
 *
 * The phone sends the improvement's [number] and the maker's words; the server finds the title
 * in its own copy of the newest diagnosis and stores [text], so the stored turn names the
 * improvement as the reply Claude wrote names it. The phone stores the same [text] from its own
 * copy of the same reply, and shows that until the next sync.
 *
 * Typed by the maker, so never composed: the prefix is the app's, the words are theirs.
 *
 * @property number the improvement's place in the diagnosis, from 1, counting every finding.
 */
data class ImprovementQuestion(val number: Int, val title: String, val question: String) {

    /** The turn as stored on the phone and on the server. */
    val text: String get() = "$PREFIX$number, '$title': $question"

    /** Which improvement it is about, as the page names it. */
    val key: ImprovementKey get() = ImprovementKey(number, title)

    companion object {
        const val PREFIX = "About improvement "

        /** The question about improvement [number], titled [title], with both trimmed. */
        fun of(number: Int, title: String, question: String) =
            ImprovementQuestion(number, title.trim(), question.trim())

        // Non-greedy on the title: a title is the model's ten-word action and carries no
        // "': ", while the maker's question may. Dot matches newlines, since a typed question can
        // hold them.
        private val PATTERN = Regex("""^About improvement (\d+), '(.*?)': (.*)$""", RegexOption.DOT_MATCHES_ALL)

        /** The question [text] holds, or null for any other turn. */
        fun parse(text: String): ImprovementQuestion? {
            val match = PATTERN.find(text) ?: return null
            val number = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            return ImprovementQuestion(number, match.groupValues[2], match.groupValues[3])
        }

        /**
         * The question about improvement [number] of [diagnosis], or null when the diagnosis has
         * no such improvement. What the server stores for a page question (ChatRoutes), and the
         * reason a number out of range is refused there rather than guessed at.
         */
        fun about(diagnosis: Diagnosis?, number: Int, question: String): ImprovementQuestion? {
            val finding = diagnosis?.findings?.getOrNull(number - 1) ?: return null
            if (finding.title.isBlank()) return null
            return of(number, finding.title, question)
        }
    }
}

/** One improvement as its page names it: its place in the diagnosis and its title. */
data class ImprovementKey(val number: Int, val title: String)

/** One question on an improvement's page, and Claude's answer once it has one. */
data class PageExchange(
    val questionId: String,
    val question: String,
    /** Plain text, or null while it is being written or after a send that failed. */
    val answer: String?,
)

/**
 * What the conversation holds of the improvement pages' chats.
 *
 * @property byImprovement each page's questions and answers, oldest first.
 * @property hidden message ids the conversation list leaves out, because a page shows them
 *   instead: each question, and each answer that is only prose.
 */
data class PageQuestions(
    val byImprovement: Map<ImprovementKey, List<PageExchange>> = emptyMap(),
    val hidden: Set<String> = emptySet(),
) {
    /** How many questions the maker has asked about [key], answered or not. */
    fun countFor(key: ImprovementKey): Int = byImprovement[key]?.size ?: 0

    companion object {
        val NONE = PageQuestions()
    }
}

/**
 * Pairs each page question in [messages] with the reply after it, by improvement.
 *
 * As [detailAnswersOf] does for the buttons: a reply that carries a block (a corrected
 * diagnosis) stays in the conversation, because the corrected list is drawn there; the page
 * still shows the reply's opening, which says what changed. The question is hidden either way.
 * A composed turn is never a page question: only the maker types one.
 */
fun pageQuestionsOf(messages: List<ChatMessage>): PageQuestions {
    val byImprovement = linkedMapOf<ImprovementKey, MutableList<PageExchange>>()
    val hidden = mutableSetOf<String>()
    messages.forEachIndexed { index, message ->
        if (message.role != Role.USER || message.composed) return@forEachIndexed
        val asked = ImprovementQuestion.parse(message.text) ?: return@forEachIndexed
        hidden += message.id
        val reply = messages.getOrNull(index + 1)?.takeIf { it.role == Role.ASSISTANT }
        val answer = reply?.let {
            val content = parseAssistantContent(it.text)
            val onlyProse = !content.sentDiagnosis && content.verdict == null && content.fixPlan == null && content.plan == null
            if (onlyProse) hidden += it.id
            markdownToPlainText(content.displayProse).trim()
        }
        byImprovement.getOrPut(asked.key) { mutableListOf() } += PageExchange(message.id, asked.question, answer)
    }
    return PageQuestions(byImprovement, hidden)
}

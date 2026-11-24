package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * English and Kiswahili on a Fundi Bora assessment (v0.7.0, release scope row 29; Nathan, 16
 * November 2026): the server translates a reply when the maker first switches, keeps the
 * translation beside the reply, and the phone shows it in the reply's place.
 *
 * **A translation never enters the conversation.** It is stored in its own table, never in
 * `messages`, and the history the server sends Claude for the next turn is the original text,
 * byte for byte, so the prompt cache is untouched. On the phone it replaces the reply on the
 * screen only ([translatedConversation]); every turn the app writes from a reply (a detail
 * button, a page question, a reply chip, a plan's answers) is written from the original.
 *
 * Shared because both sides need the same two rules: the server checks a translation with
 * [problem] before storing it, and both name a reply by [keyOf] its text, since the phone's
 * id for a reply is its own and not the server's.
 */
object ReplyTranslation {

    /** The reply's name in a translation request and response: SHA-256 of its text, lowercase hex. */
    fun keyOf(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * The turn the translator is sent: the target language, then the reply between tags, so
     * a reply that itself reads like an instruction is read as text to translate.
     */
    fun request(original: String, into: AssessmentLanguage): String =
        "Translate this reply into ${into.ownName}.\n\n$OPEN_TAG\n$original\n$CLOSE_TAG"

    /** The translator's answer as a reply: the tags taken off if it repeated them, and trimmed. */
    fun answerOf(text: String): String =
        text.trim().removePrefix(OPEN_TAG).removeSuffix(CLOSE_TAG).trim()

    /**
     * Why [translated] cannot be shown in place of [original], or null when it can.
     *
     * The phone draws a reply from its blocks, and the server, the reports and the research all
     * read the enums inside them, so a translation must change only words a person reads
     * (Nathan, 16 November 2026). Checked two ways: every fenced block's JSON has the same shape,
     * with every key, number, boolean and closed-vocabulary value ([CLOSED_KEYS]) unchanged; and
     * the parsed reply draws the same things, with the same number of findings, shots, tests,
     * options and steps, the same severities, areas, dimensions, verdict level and marks.
     */
    fun problem(original: String, translated: String): String? {
        if (translated.isBlank()) return "the translation is empty"
        val before = fencedBlocks(original)
        val after = fencedBlocks(translated)
        if (before.map { it.tag } != after.map { it.tag }) {
            return "the blocks differ: ${before.map { it.tag }} became ${after.map { it.tag }}"
        }
        before.zip(after).forEach { (a, b) -> blockProblem(a, b)?.let { return it } }
        return parsedProblem(parseAssistantContent(original), parseAssistantContent(translated))
    }

    /**
     * Values from a fixed list, which the app, the server or the research reads: they stay in
     * English in a Kiswahili reply. `language` too: it says what Claude wrote in, which a
     * translation does not change.
     */
    val CLOSED_KEYS = setOf(
        "severity", "area", "dimension", "verdict", "level", "language",
        "measured_unit", "diagram", "kind", "id",
    )

    private const val OPEN_TAG = "<reply>"
    private const val CLOSE_TAG = "</reply>"

    /** Fences whose body is one JSON object. */
    private val JSON_TAGS = setOf("qv-plan", "qv-verdict", "fb-diagnosis", "fb-fixplan", "json")

    private val json = Json { isLenient = true }

    private class Block(val tag: String, val body: String)

    /** Every fenced block, in order, with its tag lowercased. An unclosed one runs to the end. */
    private fun fencedBlocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("```")) {
                i++
                continue
            }
            val tag = line.removePrefix("```").trim().lowercase()
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].trim().startsWith("```")) {
                body += lines[j]
                j++
            }
            out += Block(tag, body.joinToString("\n"))
            i = j + 1
        }
        return out
    }

    private fun blockProblem(a: Block, b: Block): String? {
        if (a.tag == "qv-options") {
            val before = a.body.lines().count { it.isNotBlank() }
            val after = b.body.lines().count { it.isNotBlank() }
            return if (before == after) null else "qv-options had $before lines and has $after"
        }
        if (a.tag !in JSON_TAGS) return null
        val before = runCatching { json.parseToJsonElement(a.body.trim()) }.getOrNull()
        val after = runCatching { json.parseToJsonElement(b.body.trim()) }.getOrNull()
        return when {
            // A block Claude wrote that would not parse is dropped by the phone; the translation
            // of it must be dropped the same way, so it may not have been repaired into one.
            before == null -> if (after == null) null else "${a.tag} would not parse and its translation does"
            after == null -> "${a.tag} no longer parses"
            else -> treeProblem(a.tag, before, after, key = null)
        }
    }

    private fun treeProblem(path: String, a: JsonElement, b: JsonElement, key: String?): String? = when {
        a is JsonObject -> {
            if (b !is JsonObject || a.keys != b.keys) {
                "$path has different keys"
            } else {
                a.keys.firstNotNullOfOrNull { k -> treeProblem("$path.$k", a.getValue(k), b.getValue(k), k) }
            }
        }
        a is JsonArray -> {
            if (b !is JsonArray || a.size != b.size) {
                "$path has a different number of items"
            } else {
                a.indices.firstNotNullOfOrNull { i -> treeProblem("$path[$i]", a[i], b[i], key) }
            }
        }
        a is JsonNull -> if (b is JsonNull) null else "$path changed from null"
        a is JsonPrimitive -> when {
            b !is JsonPrimitive || b is JsonNull || a.isString != b.isString -> "$path changed type"
            // A number, a boolean, or a value from a fixed list: exactly as written.
            !a.isString || key in CLOSED_KEYS -> if (a.content == b.content) null else "$path changed"
            // Words: may change, but a blank one stays blank and a written one stays written.
            a.content.isBlank() != b.content.isBlank() -> "$path was emptied or filled"
            else -> null
        }
        else -> null
    }

    /** The reply as the phone draws it: the same cards, the same rows, the same enums. */
    private fun parsedProblem(a: AssistantContent, b: AssistantContent): String? {
        if (a.options.size != b.options.size) return "the reply chips differ in number"
        if (a.sentDiagnosis != b.sentDiagnosis || a.cleanDiagnosis != b.cleanDiagnosis ||
            a.diagnosisUnreadable != b.diagnosisUnreadable
        ) {
            return "the diagnosis differs"
        }
        if ((a.verdict == null) != (b.verdict == null)) return "the verdict differs"
        if ((a.plan == null) != (b.plan == null)) return "the plan differs"
        if ((a.fixPlan == null) != (b.fixPlan == null)) return "the fix plan differs"
        if ((a.diagnosis == null) != (b.diagnosis == null)) return "the diagnosis differs"
        a.verdict?.let { va ->
            val vb = b.verdict!!
            if (va.levelId != vb.levelId || va.language != vb.language) return "the verdict level changed"
            if (va.defects.map { it.severityId to it.area } != vb.defects.map { it.severityId to it.area }) {
                return "the verdict's defects changed"
            }
            if (va.unverified.size != vb.unverified.size || va.questions.size != vb.questions.size) {
                return "the verdict's lists changed"
            }
        }
        a.diagnosis?.let { da ->
            val db = b.diagnosis!!
            if (da.findings.size != db.findings.size) {
                return "the diagnosis had ${da.findings.size} findings and has ${db.findings.size}"
            }
            if (da.awaitingCheck != db.awaitingCheck) return "the one check changed"
            da.findings.zip(db.findings).forEachIndexed { index, (fa, fb) ->
                val same = fa.severityId == fb.severityId && fa.area == fb.area &&
                    fa.dimension == fb.dimension && fa.measuredValue == fb.measuredValue &&
                    fa.measuredUnit == fb.measuredUnit && fa.mark == fb.mark &&
                    fa.title.isBlank() == fb.title.isBlank() && fa.fixLine.isBlank() == fb.fixLine.isBlank()
                if (!same) return "finding ${index + 1} changed"
            }
        }
        a.plan?.let { pa ->
            val pb = b.plan!!
            if (pa.photos.size != pb.photos.size || pa.tests.size != pb.tests.size) return "the plan changed"
            if (pa.tests.map { it.diagram to it.options.size } != pb.tests.map { it.diagram to it.options.size }) {
                return "the plan's tests changed"
            }
        }
        a.fixPlan?.let { fa ->
            val fb = b.fixPlan!!
            val shape = { plan: com.qualityverifier.domain.FixPlan ->
                plan.stages.map { (horizon, stage) -> Triple(horizon, stage.minutes, stage.steps.size) }
            }
            if (shape(fa) != shape(fb)) return "the fix plan changed"
        }
        return null
    }
}

/**
 * A Fundi Bora conversation as the screen shows it in the other language (row 29).
 *
 * @property messages the conversation to draw: each translated reply in its original's place,
 *   with the same id, and each detail request and page question naming its improvement by the
 *   translated title, so the screen's pairing of answers to improvements, which goes by title
 *   ([detailAnswersOf], [pageQuestionsOf]), finds them. Never stored and never sent.
 */
class TranslatedConversation(
    val messages: List<ChatMessage>,
    private val originals: Map<String, String>,
) {
    /**
     * The title Claude wrote for the improvement shown as [shown]. Every turn the app writes
     * about an improvement names it by this, as it does in the original language, so the
     * server's stored answers, the prompt's rules and the conversation are as before.
     */
    fun originalTitle(shown: String): String = originals[shown.trim()] ?: shown.trim()
}

/**
 * [messages] with each reply in [translations] (by message id) shown in place of its original.
 * With no translations, the conversation as it is.
 */
fun translatedConversation(messages: List<ChatMessage>, translations: Map<String, String>): TranslatedConversation {
    if (translations.isEmpty()) return TranslatedConversation(messages, emptyMap())

    // Improvement titles, original to shown and back, from every translated diagnosis, the
    // newest last so it wins. ReplyTranslation.problem has checked each has as many findings.
    val forward = mutableMapOf<String, String>()
    val back = mutableMapOf<String, String>()
    messages.forEach { message ->
        if (message.role != Role.ASSISTANT) return@forEach
        val shown = translations[message.id] ?: return@forEach
        val before = parseAssistantContent(message.text).diagnosis?.findings ?: return@forEach
        val after = parseAssistantContent(shown).diagnosis?.findings ?: return@forEach
        if (before.size != after.size) return@forEach
        before.zip(after).forEach { (a, b) ->
            val original = a.title.trim()
            val translated = b.title.trim()
            if (original.isBlank() || translated.isBlank()) return@forEach
            forward[original] = translated
            forward[ImprovementQuestion.titleOf(original)] = ImprovementQuestion.titleOf(translated)
            back[translated] = original
        }
    }

    val shown = messages.map { message ->
        when {
            message.role == Role.ASSISTANT -> translations[message.id]?.let { message.copy(text = it) } ?: message
            // A detail button's request names its improvement by title (row 37).
            message.composed -> DetailKind.parse(message.text)?.let { (kind, title) ->
                forward[title]?.let { message.copy(text = kind.request(it)) }
            } ?: message
            // A page question names its improvement by number and title (row 43).
            else -> ImprovementQuestion.parse(message.text)?.let { asked ->
                forward[asked.title]?.let { message.copy(text = asked.copy(title = it).text) }
            } ?: message
        }
    }
    return TranslatedConversation(shown, back)
}

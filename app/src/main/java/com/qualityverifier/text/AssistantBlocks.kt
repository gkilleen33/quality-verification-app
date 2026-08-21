package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.Verdict
import kotlinx.serialization.json.Json

/**
 * An assistant turn split into the parts the UI renders differently.
 *
 * The assistant sends one string. Three fenced blocks inside it are meant for the app
 * rather than for the reader: `qv-options` becomes tappable reply chips, `qv-plan`
 * becomes a run of capture and test screens, and `qv-verdict` becomes the verdict cards.
 * All three are stripped out of [prose].
 *
 * When [verdict] is present the prompt has also written the same assessment in prose, so
 * that a parse failure still leaves the customer with a readable answer. [prose] is
 * therefore only worth showing when [verdict] is null — see [displayProse].
 */
data class AssistantContent(
    val prose: String,
    val options: List<String> = emptyList(),
    val verdict: Verdict? = null,
    val plan: AssessmentPlan? = null,
) {
    /**
     * What to put in the message bubble.
     *
     * Empty for a verdict, whose cards say all of it already. For a plan, only the first
     * paragraph: the plan card draws the shots and tests immediately below, so anything
     * after that opening acknowledgement is the same plan a second time. Left whole it
     * buried the start-camera button under a numbered list of every shot, which read as
     * the assistant asking for photos one at a time — exactly the behaviour the plan
     * exists to replace.
     *
     * The prompt also asks for one short paragraph, but that is a request; this is the
     * guarantee.
     */
    val displayProse: String
        get() = when {
            verdict != null -> ""
            plan != null -> prose.substringBefore("\n\n").trim()
            else -> prose
        }
}

private const val FENCE = "```"
private const val OPTIONS_TAG = "qv-options"
private const val VERDICT_TAG = "qv-verdict"
private const val PLAN_TAG = "qv-plan"

/** At most this many chips; more than a handful stops being a choice and starts being a list. */
private const val MAX_OPTIONS = 5

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Splits [text] into prose, reply options and a verdict.
 *
 * Unrecognised fenced blocks are left in [AssistantContent.prose] untouched, so an
 * ordinary markdown code block still renders as one. An unterminated block — which is
 * what a truncated response looks like — is treated as running to the end of the text.
 */
fun parseAssistantContent(text: String): AssistantContent {
    if (!text.contains(FENCE)) return AssistantContent(prose = text)

    val prose = StringBuilder()
    var options = emptyList<String>()
    var verdict: Verdict? = null
    var plan: AssessmentPlan? = null

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val tag = fenceTagAt(lines[i])
        if (tag == null) {
            prose.appendLine(lines[i])
            i++
            continue
        }

        val body = mutableListOf<String>()
        var j = i + 1
        while (j < lines.size && fenceTagAt(lines[j]) == null) {
            body += lines[j]
            j++
        }
        val closed = j < lines.size

        when (tag.lowercase()) {
            OPTIONS_TAG -> options = parseOptions(body)
            PLAN_TAG -> {
                // Same reasoning as the verdict: a block addressed to us that will not
                // parse is dropped, never printed. The prose alongside it lists the
                // shots in words, so the customer is not left with nothing.
                plan = parsePlan(body.joinToString("\n"))
            }
            VERDICT_TAG -> {
                // A block the model addressed to us. If it will not parse, drop it
                // rather than showing raw JSON to somebody standing in a shop — the
                // prose version is there precisely to cover this.
                verdict = parseVerdict(body.joinToString("\n"))
            }
            "json" -> {
                // Could be a verdict the model mislabelled, or could be an ordinary
                // code block. Only claim it when it actually looks like a verdict.
                val parsed = parseVerdict(body.joinToString("\n"))?.takeIf { it.isRenderable }
                if (parsed != null) {
                    verdict = parsed
                } else {
                    prose.appendLine(lines[i])
                    body.forEach(prose::appendLine)
                    if (closed) prose.appendLine(lines[j])
                }
            }
            else -> {
                prose.appendLine(lines[i])
                body.forEach(prose::appendLine)
                if (closed) prose.appendLine(lines[j])
            }
        }
        i = if (closed) j + 1 else j
    }

    return AssistantContent(
        prose = prose.toString().trim(),
        options = options,
        verdict = verdict?.takeIf { it.isRenderable },
        plan = plan?.takeIf { it.isRunnable },
    )
}

/** The info string of a fence line, or null if this is not a fence line. */
private fun fenceTagAt(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith(FENCE)) return null
    return trimmed.removePrefix(FENCE).trim()
}

private val LEADING_MARKER = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")

private fun parseOptions(body: List<String>): List<String> =
    body.asSequence()
        .map { it.replace(LEADING_MARKER, "").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_OPTIONS)
        .toList()

private fun parseVerdict(body: String): Verdict? = decode(body)

private fun parsePlan(body: String): AssessmentPlan? = decode(body)

private inline fun <reified T> decode(body: String): T? {
    val trimmed = body.trim()
    if (!trimmed.startsWith("{")) return null
    return runCatching { json.decodeFromString<T>(trimmed) }.getOrNull()
}

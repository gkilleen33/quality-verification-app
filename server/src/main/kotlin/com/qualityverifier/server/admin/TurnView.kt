package com.qualityverifier.server.admin

import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.Verdict
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.MdBlock
import com.qualityverifier.text.MdInline
import com.qualityverifier.text.MdStyle
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.parseMarkdown
import kotlinx.html.FlowContent
import kotlinx.html.code
import kotlinx.html.DL
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.em
import kotlinx.html.h3
import kotlinx.html.h4
import kotlinx.html.hr
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.ul

/**
 * An assistant turn drawn the way the phone draws it.
 *
 * The portal used to print `turn.text` verbatim, which meant the fenced blocks addressed
 * to the app — `qv-verdict` above all — appeared as raw JSON. That is unreadable, and it
 * is unreadable in exactly the place where somebody is trying to judge whether the
 * assessment was any good: an evaluator's critique sits at the top of the same page.
 *
 * Everything here reuses `:shared` rather than reimplementing it — the same
 * [parseAssistantContent], the same [parseMarkdown], the same [ReportLabels]. That is the
 * point: a portal with its own idea of what a verdict looks like would drift from the
 * handset, and then a reviewer would be judging our rendering rather than the assistant's
 * work. The only thing this file owns is HTML and CSS classes.
 *
 * **Nothing is ever emitted unescaped.** Every string on this page came from a customer
 * or from a model reading a customer's photographs, so it is all untrusted; text goes in
 * through kotlinx.html's escaping `+` operator and never through `unsafe`. Markdown links
 * keep their label and drop their URL, which is what the phone does too — there is no
 * `href` here for a model to write into.
 */
fun FlowContent.assistantBody(text: String) {
    val content = parseAssistantContent(text)
    val verdict = content.verdict

    // Same rule as the chat bubble: a verdict replaces the prose rather than joining it.
    // The prompt writes both, and the prose duplicate exists only to cover a block that
    // will not parse — printing both would show the reviewer the assessment twice.
    if (verdict != null) {
        verdictCards(verdict)
    } else {
        markdownBody(content.displayProse)
    }

    content.plan?.let { planCard(it) }
    if (content.options.isNotEmpty()) optionChips(content.options)

    // The reviewer's escape hatch, collapsed. A rendered verdict hides the prose fallback
    // and the block it was parsed from, and "what did the model actually send" is a fair
    // question to ask of a turn you are grading. Only offered when something was in fact
    // held back, so an ordinary prose turn does not grow a control that reveals itself.
    if (verdict != null || content.plan != null || content.options.isNotEmpty()) {
        details("raw") {
            summary { +"What the model sent" }
            div("text") { +text }
        }
    }
}

// ------------------------------------------------------------------ verdict

private fun FlowContent.verdictCards(verdict: Verdict) {
    // Headings follow the language of the assessment, not the reader's. A Swahili finding
    // under an English heading is the same half-finished look on a laptop as on a phone.
    val labels = ReportLabels.forLanguage(verdict.language)
    div("verdict") {
        div("vcard ${levelClass(verdict.level)}") {
            div("vlabel") { +labels.verdictHeading }
            if (verdict.headline.isNotBlank()) div("vhead") { +verdict.headline }
            if (verdict.summary.isNotBlank()) p("vsum") { +verdict.summary }
        }
        verdict.defects.forEach { defectCard(it, labels) }
        if (verdict.unverified.isNotEmpty()) {
            div("vcard vquiet") {
                div("vlabel") { +labels.couldNotVerifyHeading }
                ul {
                    verdict.unverified.forEach { line -> li { +line } }
                }
            }
        }
        if (verdict.questions.isNotEmpty()) {
            div {
                // Label above the row, not inside it: the row is a flex container, and a
                // heading placed in it becomes another chip-shaped thing beside the chips.
                div("vlabel") { +labels.askAboutThis }
                // Drawn flat, not as buttons. On the phone these are tappable; here there
                // is nobody to ask, and a chip that looks pressable but is not would be a
                // worse lie than a plain list.
                div("chips") {
                    verdict.questions.forEach { question -> span("chip") { +question } }
                }
            }
        }
    }
}

private fun FlowContent.defectCard(defect: Defect, labels: ReportLabels) {
    div("vcard defect") {
        severityChip(defect, labels)
        if (defect.title.isNotBlank()) div("vhead small") { +defect.title }
        dl("field") {
            field(labels.whatISeeHeading, defect.whatISee)
            field(labels.whatItMeansHeading, defect.whatItMeans)
            field(labels.whatToDoHeading, defect.whatToDo)
        }
    }
}

private fun DL.field(heading: String, value: String) {
    if (value.isBlank()) return
    dt { +heading }
    dd { +value }
}

private fun FlowContent.severityChip(defect: Defect, labels: ReportLabels) {
    val parts = listOfNotNull(
        defect.area.takeIf { it.isNotBlank() }?.let(labels::area),
        labels.severity(defect.severity).takeIf { it.isNotBlank() }?.uppercase(),
    )
    if (parts.isEmpty()) return
    // Severity borrows the verdict palette, as on the phone, so a serious defect under a
    // fair overall verdict still reads as serious.
    val level = when (defect.severity) {
        Severity.SERIOUS -> VerdictLevel.SERIOUS
        Severity.MODERATE -> VerdictLevel.FAIR
        else -> VerdictLevel.UNKNOWN
    }
    span("sev ${levelClass(level)}") { +parts.joinToString(" · ") }
}

private fun levelClass(level: VerdictLevel): String = when (level) {
    VerdictLevel.SOUND -> "lv-sound"
    VerdictLevel.FAIR -> "lv-fair"
    VerdictLevel.SERIOUS -> "lv-serious"
    VerdictLevel.UNKNOWN -> "lv-unknown"
}

// --------------------------------------------------------------------- plan

/**
 * The collection run the assistant asked for.
 *
 * Worth drawing rather than hiding: half of judging an assessment is judging what it
 * asked to be shown, and the photographs further down the page are the answer to this
 * list. The phone shows it as a card with a start-camera button; there is nothing to
 * start here, so it is a list.
 */
private fun FlowContent.planCard(plan: AssessmentPlan) {
    val labels = ReportLabels.forLanguage(plan.language)
    div("vcard plan") {
        // The summary is the card's title, as on the handset — the section headings below
        // say what the lists are, so a label above them would only name them twice.
        if (plan.summary.isNotBlank()) div("vhead small") { +plan.summary }
        if (plan.photos.isNotEmpty()) {
            div("vlabel sub") { +labels.photosHeading }
            ol {
                plan.photos.forEach { shot ->
                    li {
                        if (shot.title.isNotBlank()) strong { +shot.title }
                        if (shot.note.isNotBlank()) {
                            +" "
                            span("muted") { +shot.note }
                        }
                        if (shot.instruction.isNotBlank()) div("muted") { +shot.instruction }
                    }
                }
            }
        }
        if (plan.tests.isNotEmpty()) {
            div("vlabel sub") { +labels.testsHeading }
            ol {
                plan.tests.forEach { test ->
                    li {
                        if (test.title.isNotBlank()) strong { +test.title }
                        if (test.subtitle.isNotBlank()) {
                            +" "
                            span("muted") { +test.subtitle }
                        }
                        if (test.instruction.isNotBlank()) div("muted") { +test.instruction }
                        if (test.options.isNotEmpty()) {
                            div("chips") {
                                test.options.forEach { option ->
                                    span("chip") {
                                        +option.label
                                        if (option.detail.isNotBlank()) {
                                            +" — "
                                            +option.detail
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.optionChips(options: List<String>) {
    div("chips") {
        options.forEach { option -> span("chip") { +option } }
    }
}

// ----------------------------------------------------------------- markdown

/**
 * The prose of a turn, with its Markdown rendered rather than shown as punctuation.
 *
 * The phone stopped printing literal `**` years of nothing ago; the portal never did.
 * Uses the same parser, so the two agree on the awkward cases — a single underscore is
 * not emphasis, a single newline inside a paragraph is a real line break.
 */
internal fun FlowContent.markdownBody(text: String) {
    if (text.isBlank()) return
    val blocks = parseMarkdown(text)
    div("md") {
        var i = 0
        while (i < blocks.size) {
            when (val block = blocks[i]) {
                is MdBlock.Paragraph -> { p { inline(block.content) }; i++ }
                is MdBlock.Heading -> {
                    // The page owns h1 and h2. A model heading is a heading inside one
                    // turn of one conversation, and outranking the page title with it
                    // would break the document outline for a screen reader.
                    if (block.level <= 2) h3 { inline(block.content) } else h4 { inline(block.content) }
                    i++
                }
                MdBlock.Rule -> { hr(); i++ }
                // Consecutive items become one list, which is what they were in the
                // source. Rendered one list per item they lose their alignment, and a
                // reader gets a run of single-item lists instead of a list.
                is MdBlock.Bullet -> {
                    val items = listRun(blocks, i, numbered = false)
                    ul { items.forEach { item -> li(item.indentClass) { inline(item.content) } } }
                    i += items.size
                }
                is MdBlock.Numbered -> {
                    val items = listRun(blocks, i, numbered = true)
                    ol {
                        // The model's own first number, so a list it wrote starting at 3
                        // does not silently restart at 1 in front of somebody checking
                        // whether the assistant numbered its steps correctly.
                        items.first().number?.takeIf { it != 1 }
                            ?.let { attributes["start"] = it.toString() }
                        items.forEach { item -> li(item.indentClass) { inline(item.content) } }
                    }
                    i += items.size
                }
            }
        }
    }
}

private class ListItem(val content: MdInline, val indent: Int, val number: Int?) {
    val indentClass: String? get() = if (indent > 0) "ind" else null
}

/** The run of same-kind list items starting at [from]. */
private fun listRun(blocks: List<MdBlock>, from: Int, numbered: Boolean): List<ListItem> {
    val items = mutableListOf<ListItem>()
    var i = from
    while (i < blocks.size) {
        val block = blocks[i]
        val item = when {
            !numbered && block is MdBlock.Bullet -> ListItem(block.content, block.indent, null)
            numbered && block is MdBlock.Numbered ->
                ListItem(block.content, block.indent, block.number)
            else -> null
        } ?: break
        items += item
        i++
    }
    return items
}

/**
 * One inline run, with bold, italic, code and link labels applied.
 *
 * Spans nest and therefore overlap, so this walks the span boundaries and asks which
 * styles cover each stretch, rather than trying to wrap each span in turn — the latter
 * cannot express bold inside italic without emitting crossed tags.
 */
private fun FlowContent.inline(content: MdInline) {
    if (content.spans.isEmpty()) {
        +content.text
        return
    }
    val cuts = sortedSetOf(0, content.text.length)
    content.spans.forEach { span ->
        if (span.start in 0..content.text.length) cuts += span.start
        if (span.end in 0..content.text.length) cuts += span.end
    }
    val points = cuts.toList()
    for (k in 0 until points.size - 1) {
        val from = points[k]
        val to = points[k + 1]
        if (from >= to) continue
        val styles = content.spans
            .filter { it.start <= from && it.end >= to }
            .map { it.style }
            .toSet()
        styled(content.text.substring(from, to), styles)
    }
}

private fun FlowContent.styled(text: String, styles: Set<MdStyle>) {
    when {
        MdStyle.CODE in styles -> code { styled(text, styles - MdStyle.CODE) }
        MdStyle.BOLD in styles -> strong { styled(text, styles - MdStyle.BOLD) }
        MdStyle.ITALIC in styles -> em { styled(text, styles - MdStyle.ITALIC) }
        // The parser dropped the URL, so there is nothing to link to — and deliberately
        // so: an href here would be a model writing a destination into a page an admin
        // is signed in to. Shown as emphasis, the same as on the handset.
        MdStyle.LINK in styles -> span("mdlink") { styled(text, styles - MdStyle.LINK) }
        else -> +text
    }
}

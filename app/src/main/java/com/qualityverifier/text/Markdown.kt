package com.qualityverifier.text

/**
 * A deliberately small Markdown parser for assistant replies.
 *
 * Claude formats its advice with bold, headings and lists, and the chat bubble used to
 * show those markers as literal characters — `**like this**` — which is pure noise for
 * readers with varying literacy. This covers what actually appears in furniture advice:
 * headings, bullet and numbered lists, bold, italic, inline code, links and rules. It is
 * not a CommonMark implementation; tables, block quotes, fenced code and images are
 * rendered as plain text rather than mishandled.
 *
 * The output is a Compose-free intermediate form so the whole parser is unit-testable on
 * the JVM. `MarkdownText` turns it into styled Compose text; [markdownToPlainText]
 * flattens it for places that cannot show styling, such as the history list preview.
 *
 * Two deliberate deviations from CommonMark, both chosen to be less surprising in a chat
 * bubble than the spec would be:
 *  - A single newline inside a paragraph is kept as a line break rather than collapsed to
 *    a space, so a manual break Claude intended is honoured.
 *  - A single underscore never starts emphasis, so `item_wooden_table` survives intact.
 *    Doubled `__bold__` is still recognised.
 */

internal enum class MdStyle { BOLD, ITALIC, CODE, LINK }

/** A style applied to the half-open range [start, end) of [MdInline.text]. */
internal data class MdSpan(val start: Int, val end: Int, val style: MdStyle)

internal data class MdInline(val text: String, val spans: List<MdSpan> = emptyList())

internal sealed interface MdBlock {
    data class Paragraph(val content: MdInline) : MdBlock
    data class Heading(val content: MdInline, val level: Int) : MdBlock
    data class Bullet(val content: MdInline, val indent: Int) : MdBlock
    data class Numbered(val content: MdInline, val number: Int, val indent: Int) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val RULE = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")

internal fun parseMarkdown(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    // Accumulates consecutive plain lines into a single paragraph.
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
            paragraph.clear()
        }
    }

    for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        when {
            line.isBlank() -> flushParagraph()

            RULE.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            HEADING.matches(line) -> {
                flushParagraph()
                val (hashes, text) = HEADING.find(line)!!.destructured
                blocks += MdBlock.Heading(parseInline(text.trim()), hashes.length.coerceAtMost(3))
            }

            NUMBERED.matches(line) -> {
                flushParagraph()
                val (indent, number, text) = NUMBERED.find(line)!!.destructured
                blocks += MdBlock.Numbered(
                    content = parseInline(text.trim()),
                    number = number.toIntOrNull() ?: 1,
                    indent = indent.length / 2,
                )
            }

            BULLET.matches(line) -> {
                flushParagraph()
                val (indent, text) = BULLET.find(line)!!.destructured
                blocks += MdBlock.Bullet(parseInline(text.trim()), indent.length / 2)
            }

            // An indented line straight after a list item is that item's continuation,
            // not a new paragraph — otherwise a wrapped bullet breaks out of the list.
            line.firstOrNull()?.isWhitespace() == true && paragraph.isEmpty() &&
                blocks.lastOrNull().isListItem() -> {
                blocks[blocks.lastIndex] = blocks.last().appendingText(line.trim())
            }

            else -> paragraph += line
        }
    }
    flushParagraph()
    return blocks
}

private fun MdBlock?.isListItem() = this is MdBlock.Bullet || this is MdBlock.Numbered

/** Re-parses the joined text so emphasis split across a wrapped line still resolves. */
private fun MdBlock.appendingText(more: String): MdBlock = when (this) {
    is MdBlock.Bullet -> copy(content = parseInline(content.text + " " + more))
    is MdBlock.Numbered -> copy(content = parseInline(content.text + " " + more))
    else -> this
}

internal fun parseInline(raw: String): MdInline {
    val text = StringBuilder()
    val spans = mutableListOf<MdSpan>()
    appendInline(raw, text, spans)
    return MdInline(text.toString(), spans)
}

private fun appendInline(raw: String, out: StringBuilder, spans: MutableList<MdSpan>) {
    var i = 0
    while (i < raw.length) {
        val consumed = when {
            raw.startsWith("**", i) -> emphasis(raw, i, "**", MdStyle.BOLD, out, spans)
            raw.startsWith("__", i) -> emphasis(raw, i, "__", MdStyle.BOLD, out, spans)
            raw.startsWith("*", i) -> emphasis(raw, i, "*", MdStyle.ITALIC, out, spans)
            raw.startsWith("`", i) -> code(raw, i, out, spans)
            raw.startsWith("[", i) -> link(raw, i, out, spans)
            else -> 0
        }
        if (consumed > 0) {
            i += consumed
        } else {
            out.append(raw[i])
            i++
        }
    }
}

/** Returns the number of characters consumed, or 0 if this is not a valid delimiter pair. */
private fun emphasis(
    raw: String,
    at: Int,
    delimiter: String,
    style: MdStyle,
    out: StringBuilder,
    spans: MutableList<MdSpan>,
): Int {
    val contentStart = at + delimiter.length
    val close = raw.indexOf(delimiter, contentStart)
    // An unmatched or empty delimiter is literal text, not markup.
    if (close <= contentStart) return 0
    val start = out.length
    appendInline(raw.substring(contentStart, close), out, spans)
    spans += MdSpan(start, out.length, style)
    return close + delimiter.length - at
}

private fun code(raw: String, at: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
    val close = raw.indexOf('`', at + 1)
    if (close <= at + 1) return 0
    val start = out.length
    // Code spans are literal: no nested parsing.
    out.append(raw, at + 1, close)
    spans += MdSpan(start, out.length, MdStyle.CODE)
    return close + 1 - at
}

/** `[label](url)` keeps the label and drops the URL — the app has nowhere to open it. */
private fun link(raw: String, at: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
    val labelEnd = raw.indexOf(']', at + 1)
    if (labelEnd <= at + 1) return 0
    if (labelEnd + 1 >= raw.length || raw[labelEnd + 1] != '(') return 0
    val urlEnd = raw.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return 0
    val start = out.length
    appendInline(raw.substring(at + 1, labelEnd), out, spans)
    spans += MdSpan(start, out.length, MdStyle.LINK)
    return urlEnd + 1 - at
}

/**
 * Strips formatting down to readable plain text, for contexts that cannot render styling.
 *
 * Applied before the history preview is truncated, not after: truncating first can cut a
 * `**` pair in half, leaving a stray marker that no parser would then recognise.
 */
internal fun markdownToPlainText(raw: String): String =
    parseMarkdown(raw).joinToString("\n") { block ->
        when (block) {
            is MdBlock.Paragraph -> block.content.text
            is MdBlock.Heading -> block.content.text
            is MdBlock.Bullet -> block.content.text
            is MdBlock.Numbered -> "${block.number}. ${block.content.text}"
            MdBlock.Rule -> ""
        }
    }.trim()

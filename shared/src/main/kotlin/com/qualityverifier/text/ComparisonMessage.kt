package com.qualityverifier.text

import com.qualityverifier.domain.Verdict

/**
 * The customer's turn asking for two pieces to be compared.
 *
 * Somebody choosing between two stools in the same shop wants to know which one is
 * better made, and by then the app holds a finished verdict on each. This carries the
 * earlier one into the conversation about the later one.
 *
 * It is a message in the customer's own conversation rather than a hidden field, for the
 * same reason the intake is: they can see exactly what was sent on their behalf, and
 * [com.qualityverifier.data.chat.ChatService] stays a plain-text call.
 *
 * What travels is the earlier piece's **recorded findings** — the verdict, each defect as
 * it was observed, and what could not be checked. Not its photographs: the second
 * assessment's own images are already in the conversation, and re-sending eight more
 * would double the cost of the turn for evidence the assistant has already read once.
 *
 * That leaves the two pieces unevenly evidenced, which is a real limitation rather than a
 * detail: this piece can be looked at again, the earlier one only recalled. The master
 * prompt carries the discipline that follows from it — a defect recorded on one piece and
 * not the other is not proof the other is clean.
 */
fun buildComparisonRequest(
    previousItemName: String,
    previous: Verdict,
    labels: ReportLabels,
): String = buildString {
    appendLine(labels.compareIntro(previousItemName))

    val level = labels.verdictWord(previous).uppercase()
    if (previous.headline.isBlank()) {
        appendLine("${labels.shareVerdict}: $level")
    } else {
        appendLine("${labels.shareVerdict}: $level — ${previous.headline}")
    }
    if (previous.summary.isNotBlank()) appendLine(previous.summary)

    if (previous.defects.isNotEmpty()) {
        appendLine()
        appendLine(labels.compareFoundHeading)
        previous.defects.forEachIndexed { index, defect ->
            val tags = listOfNotNull(
                labels.severity(defect.severity).lowercase().takeIf { it.isNotBlank() },
                labels.area(defect.area).lowercase().takeIf { defect.area.isNotBlank() },
            ).joinToString(", ")
            val heading = listOfNotNull(
                defect.title.takeIf { it.isNotBlank() },
                tags.takeIf { it.isNotBlank() }?.let { "($it)" },
            ).joinToString(" ")
            appendLine("${index + 1}. $heading")
            // What was seen, not what it meant: the observation is the comparable fact,
            // and the assistant can draw the consequence again with both pieces in view.
            defect.whatISee.takeIf { it.isNotBlank() }?.let { appendLine("   $it") }
        }
    }

    if (previous.unverified.isNotEmpty()) {
        appendLine()
        appendLine(labels.shareNotChecked)
        previous.unverified.forEach { appendLine("- $it") }
    }

    appendLine()
    append(labels.compareAsk)
}

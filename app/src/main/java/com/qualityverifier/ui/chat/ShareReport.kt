package com.qualityverifier.ui.chat

import android.content.Context
import android.content.Intent
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ReportLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a verdict as text to paste into WhatsApp.
 *
 * Plain text rather than an image or a PDF: WhatsApp compresses images to the point
 * where a report becomes unreadable, and a text message survives being forwarded,
 * quoted and read on any handset. The layout is deliberately flat for the same reason —
 * indentation is the first thing to go when a message is quoted back.
 */
fun buildShareText(
    itemType: ItemType,
    verdict: Verdict,
    date: String,
    labels: ReportLabels,
): String = buildString {
    appendLine("${labels.shareHeader} · ${labels.itemName(itemType)} · $date")
    val level = labels.level(verdict.level).uppercase()
    if (verdict.headline.isBlank()) {
        appendLine("${labels.shareVerdict}: $level")
    } else {
        appendLine("${labels.shareVerdict}: $level — ${verdict.headline}")
    }
    if (verdict.summary.isNotBlank()) appendLine(verdict.summary)

    if (verdict.defects.isNotEmpty()) {
        appendLine()
        appendLine(labels.shareWhatToLookAt)
        verdict.defects.forEachIndexed { index, defect ->
            val severity = labels.severity(defect.severity).lowercase()
            val heading = listOfNotNull(
                defect.title.takeIf { it.isNotBlank() },
                severity.takeIf { it.isNotBlank() }?.let { "($it)" },
            ).joinToString(" ")
            appendLine("${index + 1}. $heading")
            defect.whatItMeans.takeIf { it.isNotBlank() }?.let { appendLine("   $it") }
            defect.whatToDo.takeIf { it.isNotBlank() }?.let { appendLine("   $it") }
        }
    }

    if (verdict.unverified.isNotEmpty()) {
        appendLine()
        appendLine(labels.shareNotChecked)
        verdict.unverified.forEach { appendLine("- $it") }
    }

    appendLine()
    append(labels.shareSignOff)
}

/**
 * Hands the report to the system share sheet. WhatsApp is the destination in practice,
 * but choosing it here would break on a phone that does not have it installed.
 */
fun shareReport(
    context: Context,
    itemType: ItemType,
    verdict: Verdict,
    at: Long,
    labels: ReportLabels,
) {
    // The date follows the phone rather than the assessment: it is a number the reader
    // recognises either way, and month names in the wrong language would be worse than
    // the phone's own formatting.
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, buildShareText(itemType, verdict, date, labels))
        putExtra(Intent.EXTRA_SUBJECT, "${labels.shareHeader} — ${labels.itemName(itemType)}")
    }
    context.startActivity(Intent.createChooser(intent, "Share this report"))
}

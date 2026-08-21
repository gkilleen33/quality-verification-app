package com.qualityverifier.ui.chat

import android.content.Context
import android.content.Intent
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Verdict
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
): String = buildString {
    appendLine("KAGUA REPORT · ${itemType.displayName} · $date")
    val level = verdict.level.label.uppercase()
    if (verdict.headline.isBlank()) {
        appendLine("VERDICT: $level")
    } else {
        appendLine("VERDICT: $level — ${verdict.headline}")
    }
    if (verdict.summary.isNotBlank()) appendLine(verdict.summary)

    if (verdict.defects.isNotEmpty()) {
        appendLine()
        appendLine("What to look at:")
        verdict.defects.forEachIndexed { index, defect ->
            val severity = defect.severity.label.lowercase()
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
        appendLine("Not checked:")
        verdict.unverified.forEach { appendLine("- $it") }
    }

    appendLine()
    append("Checked with Kagua — jua kabla ya kununua.")
}

/**
 * Hands the report to the system share sheet. WhatsApp is the destination in practice,
 * but choosing it here would break on a phone that does not have it installed.
 */
fun shareReport(context: Context, itemType: ItemType, verdict: Verdict, at: Long) {
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, buildShareText(itemType, verdict, date))
        putExtra(Intent.EXTRA_SUBJECT, "Kagua report — ${itemType.displayName}")
    }
    context.startActivity(Intent.createChooser(intent, "Share this report"))
}

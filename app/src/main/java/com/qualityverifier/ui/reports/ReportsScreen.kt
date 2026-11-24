package com.qualityverifier.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.KaguaScreenLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.rememberKaguaLabels
import com.qualityverifier.ui.rememberReportLabels
import com.qualityverifier.ui.theme.verdictColors

/**
 * My reports (v0.7.1, row 57; Kagua v2 mockup): one card, a row per piece checked, newest
 * first. A row has the verdict as a chip, "<item> · <when>", and the headline under it. An
 * assessment stopped before its verdict is "NOT FINISHED", "You stopped before the photos were
 * done. Tap to carry on.", and opens where it stopped, its photos and test answers kept.
 *
 * Empty, the screen says what goes here and offers the first move (P20). Deleting a report is
 * on the report's own page now, not on its row.
 */
@Composable
fun ReportsScreen(
    contentPadding: PaddingValues,
    onOpenSession: (sessionId: String) -> Unit,
    onCheckFirst: () -> Unit,
) {
    val container = appContainer()
    val viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.factory(container))
    val sessions by viewModel.sessions.collectAsState()
    val words = rememberKaguaLabels()

    LazyColumn(
        Modifier.fillMaxSize().background(Parts.Page),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (sessions.isEmpty()) {
            item { ScreenTitle(words.reportsTitle) }
            item { RowCard(hint = words.reportsEmpty) }
            item { PrimaryButton(words.checkFirstPiece, onClick = onCheckFirst, icon = Icons.Filled.PhotoCamera) }
        } else {
            item { ScreenTitle(words.reportsTitle, line = words.reportsLine) }
            item {
                RowCard {
                    sessions.forEachIndexed { i, session ->
                        // The chip and the item name follow the assessment's language, so they
                        // match the headline under them.
                        val reportLabels = rememberReportLabels(session.verdictLanguage)
                        val row = ReportRows.of(session, reportLabels, words, relativeTime(session.updatedAt))
                        CardRow(
                            row.title,
                            line = row.line,
                            topChip = row.level?.let { verdictChip(it, row.chipLabel) } ?: Chip.notLinked(row.chipLabel),
                            onClick = { onOpenSession(session.id) },
                            divider = i > 0,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A row of My reports, apart from the screen so a test can hold it (row 57). [level] is null
 * for an assessment not finished, whose chip is the neutral "NOT FINISHED".
 */
data class ReportRow(val level: VerdictLevel?, val chipLabel: String, val title: String, val line: String?)

object ReportRows {
    fun of(session: SessionSummary, labels: ReportLabels, words: KaguaScreenLabels, whenText: String): ReportRow {
        val title = "${labels.itemName(session.itemType)} · $whenText"
        val level = session.verdictLevel
            ?: return ReportRow(null, words.notFinished, title, words.notFinishedLine)
        return ReportRow(level, labels.verdictWord(level, session.anythingUnchecked).uppercase(), title, session.preview.ifBlank { null })
    }
}

/** The verdict as a chip in its own colours: the word a customer learned to read. */
@Composable
fun verdictChip(level: VerdictLevel, label: String): Chip {
    val colors = verdictColors(level)
    return Chip(label, colors.container, colors.onContainer)
}

/**
 * "Delete this report?" (row 57, from the report's page): two choices, because the two do
 * different things to somebody else's photographs. Says what we do in each case.
 */
@Composable
fun DeleteReportDialog(
    labels: AuthLabels,
    onKeepOnServer: () -> Unit,
    onDeleteFromServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.deleteReportTitle) },
        text = {
            Column {
                Text(labels.deleteReportBody)
                Spacer(Modifier.height(16.dp))
                // Both choices are full-width buttons in the body, not a confirm and dismiss
                // pair: one is recommended, and that should not make the other harder to find.
                DeleteChoice(labels.deleteReportKeepLabel, labels.deleteReportKeepDetail, onKeepOnServer)
                Spacer(Modifier.height(12.dp))
                DeleteChoice(labels.deleteReportPurgeLabel, labels.deleteReportPurgeDetail, onDeleteFromServer)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.cancel) } },
    )
}

/**
 * One of the two ways to delete a report. Outlined, and the same for both, so neither is
 * louder than the other. The recommendation is in the words.
 */
@Composable
private fun DeleteChoice(label: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.qualityverifier.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.rememberAuthLabels
import com.qualityverifier.ui.rememberReportLabels
import com.qualityverifier.ui.theme.verdictColors

@Composable
fun ReportsScreen(
    contentPadding: PaddingValues,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val container = appContainer()
    val viewModel: ReportsViewModel = viewModel(factory = ReportsViewModel.factory(container))
    val sessions by viewModel.sessions.collectAsState()
    val labels = rememberAuthLabels()
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("My reports", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Your furniture, over time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    "Nothing here yet. Pick a category on the home screen to check a " +
                        "piece of furniture.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    ReportRow(
                        session = session,
                        onClick = { onOpenSession(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(labels.deleteReportTitle) },
            // Two choices rather than one, because the two do different things to somebody
            // else's photographs. Says what we actually do in each case: before Phase 2
            // "removed from this phone" was the whole truth, and a dialog that still
            // implied it would be a false statement.
            text = {
                Column {
                    Text(labels.deleteReportBody)
                    Spacer(Modifier.height(16.dp))
                    // Both options are full-width buttons in the body, not a confirm/dismiss
                    // pair. One is recommended; that should not make the other harder to
                    // find, and a dialog whose real choice hides in the button row is how
                    // people end up picking the one we wanted rather than the one they did.
                    DeleteChoice(
                        label = labels.deleteReportKeepLabel,
                        detail = labels.deleteReportKeepDetail,
                        onClick = {
                            viewModel.delete(session.id, alsoFromServer = false)
                            pendingDelete = null
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    DeleteChoice(
                        label = labels.deleteReportPurgeLabel,
                        detail = labels.deleteReportPurgeDetail,
                        onClick = {
                            viewModel.delete(session.id, alsoFromServer = true)
                            pendingDelete = null
                        },
                    )
                }
            },
            // No confirm button: there is nothing left to confirm once a choice is a tap.
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(labels.cancel) }
            },
        )
    }
}

@Composable
private fun ReportRow(
    session: SessionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The badge and the "in progress" line follow the language the assessment
            // was written in, so they match the preview text underneath them.
            val labels = rememberReportLabels(session.verdictLanguage)
            Column(Modifier.weight(1f)) {
                // Null while an assessment is still running, which is the honest state:
                // a row with no badge has not reached a verdict, and should not look as
                // though it has.
                session.verdictLevel?.let {
                    VerdictBadge(it, session.anythingUnchecked, labels)
                }
                if (session.verdictLevel == null) {
                    Text(
                        labels.inProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${labels.itemName(session.itemType)} · ${relativeTime(session.updatedAt)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (session.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete report")
            }
        }
    }
}

/**
 * The one word that stands for a whole assessment.
 *
 * [anythingUnchecked] softens a clean verdict from "Sound" to "No faults found" — see
 * `ReportLabels.verdictWord`. The colour does not change with it: the level still means
 * what it meant, and a row a customer learned to read as green should stay green.
 */
@Composable
fun VerdictBadge(level: VerdictLevel, anythingUnchecked: Boolean, labels: ReportLabels) {
    val colors = verdictColors(level)
    Surface(
        color = colors.container,
        contentColor = colors.onContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = labels.verdictWord(level, anythingUnchecked).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * One of the two ways to delete a report.
 *
 * Outlined rather than filled, and identical for both, so neither is visually louder than
 * the other. The recommendation is in the words, where somebody can read the reason for it.
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
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

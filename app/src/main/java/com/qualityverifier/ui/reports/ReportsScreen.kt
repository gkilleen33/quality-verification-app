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
            // Says what we actually do, not just the half the customer can see. Before
            // Phase 2 "removed from this phone" was the whole truth; now the server keeps
            // a copy for a week, and a dialog that omitted that would be a false
            // statement about somebody's photographs.
            text = { Text(labels.deleteReportBody) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(session.id)
                    pendingDelete = null
                }) { Text(labels.delete) }
            },
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
                session.verdictLevel?.let { VerdictBadge(it, labels) }
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

@Composable
fun VerdictBadge(level: VerdictLevel, labels: ReportLabels) {
    val colors = verdictColors(level)
    Surface(
        color = colors.container,
        contentColor = colors.onContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = labels.level(level).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

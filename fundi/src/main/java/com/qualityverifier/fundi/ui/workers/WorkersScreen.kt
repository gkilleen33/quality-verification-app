package com.qualityverifier.fundi.ui.workers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels

/**
 * "My workers": the roster, one card per person, archived ones last and greyed.
 *
 * Add opens the worker form ([WorkerForm]): name, a number from contacts or typed, and
 * employee or temporary. Tapping a card opens the same form to edit, with "Archive"
 * beside Save. There is no delete: a past piece keeps the name of whoever worked on it,
 * and an archived worker simply stops appearing in the picker.
 */
@Composable
fun WorkersScreen(onBack: () -> Unit) {
    val container = fundiContainer()
    val model: WorkersViewModel = viewModel(factory = WorkersViewModel.factory(container))
    val roster by model.roster.collectAsState()
    val sorted = remember(roster) { roster.sortedWith(compareBy({ it.archived }, { it.name.lowercase() })) }
    val labels = FundiLabels.ENGLISH

    // Saveable: the contacts app comes in front, and the form must come back with the
    // activity. The edit holds the worker's id for the same reason.
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> roster.firstOrNull { it.id == id } }

    if (adding) {
        WorkerForm(
            labels = labels,
            current = null,
            accepts = model::accepts,
            onSave = { details ->
                adding = false
                model.add(details)
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { worker ->
        val before = WorkerDetails(worker.name, worker.phone, worker.employment)
        WorkerForm(
            labels = labels,
            current = before,
            accepts = model::accepts,
            onSave = { details ->
                editingId = null
                // Saved unchanged, nothing is written and nothing is sent.
                if (details != before) model.edit(worker.id, details)
            },
            onArchive = if (worker.archived) null else {
                {
                    editingId = null
                    model.archive(worker.id)
                }
            },
            onDismiss = { editingId = null },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("My workers", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
        }
        item {
            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(labels.workerAddTitle) }
        }
        if (sorted.isEmpty()) {
            item {
                Text(
                    "Nobody yet. Add the people who work with you, so a report can say " +
                        "who did which part of a piece.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sorted, key = { it.id }) { worker ->
            WorkerRow(worker, labels, onClick = { editingId = worker.id })
        }
        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun WorkerRow(worker: Worker, labels: FundiLabels, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    worker.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (worker.archived) muted else MaterialTheme.colorScheme.onSurface,
                )
                // The kind, and the number when there is one: what the form holds, so the
                // card says what a tap will edit.
                Text(
                    listOfNotNull(labels.nameOf(worker.employment), worker.phone).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            if (worker.archived) {
                Text("Archived", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

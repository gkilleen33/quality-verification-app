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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.fundi.ui.fundiContainer

/**
 * "My workers": the roster, one card per person, archived ones last and greyed.
 *
 * Add is a dialog with one field. Tapping a card opens the same dialog to rename, with
 * "Archive" beside Save. There is no delete: a past piece keeps the name of whoever
 * worked on it, and an archived worker simply stops appearing in the picker.
 */
@Composable
fun WorkersScreen(onBack: () -> Unit) {
    val container = fundiContainer()
    val model: WorkersViewModel = viewModel(factory = WorkersViewModel.factory(container))
    val roster by model.roster.collectAsState()
    val sorted = remember(roster) { roster.sortedWith(compareBy({ it.archived }, { it.name.lowercase() })) }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Worker?>(null) }

    if (adding) {
        WorkerDialog(
            title = "Add a worker",
            current = "",
            accepts = model::accepts,
            onSave = { name ->
                adding = false
                model.add(name)
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { worker ->
        WorkerDialog(
            title = "Rename",
            current = worker.name,
            accepts = model::accepts,
            onSave = { name ->
                editing = null
                if (name != worker.name) model.rename(worker.id, name)
            },
            onArchive = if (worker.archived) null else {
                {
                    editing = null
                    model.archive(worker.id)
                }
            },
            onDismiss = { editing = null },
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
            ) { Text("Add a worker") }
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
            WorkerRow(worker, onClick = { editing = worker })
        }
        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun WorkerRow(worker: Worker, onClick: () -> Unit) {
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
            Text(
                worker.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (worker.archived) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            if (worker.archived) {
                Text(
                    "Archived",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One field, bounded as the server bounds it (V23), with Save and optionally Archive. */
@Composable
private fun WorkerDialog(
    title: String,
    current: String,
    accepts: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (WorkerNames.fits(it)) name = it },
                    singleLine = true,
                    placeholder = { Text("Name or nickname") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text("Archive this worker") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = accepts(name)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

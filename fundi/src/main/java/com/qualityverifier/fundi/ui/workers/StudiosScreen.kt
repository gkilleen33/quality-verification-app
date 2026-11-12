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
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.StudioDetails
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels

/**
 * "Studios I work at" (V31): one card per studio, archived ones last and greyed, in the
 * style of "My workers". Add opens [StudioForm]; tapping a card opens it to edit, with
 * Archive. A card reads "Linked" when the server's two-sided match holds: that studio's
 * roster names this account's number too. Nothing else is shown about the other side.
 */
@Composable
fun StudiosScreen(onBack: () -> Unit) {
    val container = fundiContainer()
    val model: StudiosViewModel = viewModel(factory = StudiosViewModel.factory(container))
    val studios by model.list.collectAsState()
    val sorted = remember(studios) { studios.sortedWith(compareBy({ it.archived }, { it.name.lowercase() })) }
    val labels = FundiLabels.ENGLISH

    // Saveable: the contacts app comes in front, and the form must come back with the
    // activity. The edit holds the entry's id for the same reason.
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> studios.firstOrNull { it.id == id } }

    if (adding) {
        StudioForm(
            labels = labels,
            current = null,
            onSave = { details ->
                adding = false
                model.add(details)
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { studio ->
        val before = StudioDetails(studio.name, studio.phone)
        StudioForm(
            labels = labels,
            current = before,
            onSave = { details ->
                editingId = null
                // Saved unchanged, nothing is written and nothing is sent.
                if (details != before) model.edit(studio.id, details)
            },
            onArchive = if (studio.archived) null else {
                {
                    editingId = null
                    model.archive(studio.id)
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
            Text(labels.studiosTitle, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
        }
        item {
            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(labels.studioAddTitle) }
        }
        if (sorted.isEmpty()) {
            item {
                Text(
                    labels.studiosEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sorted, key = { it.id }) { studio ->
            StudioRow(studio, labels, onClick = { editingId = studio.id })
        }
        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun StudioRow(studio: Studio, labels: FundiLabels, onClick: () -> Unit) {
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
                    studio.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (studio.archived) muted else MaterialTheme.colorScheme.onSurface,
                )
                Text(studio.phone, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            // "Linked" is the server's word, from the last sync; "Archived" wins over it,
            // since an archived entry is never linked.
            val tag = when {
                studio.archived -> labels.workerPageArchived
                studio.linked -> labels.linkedTag
                else -> null
            }
            tag?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (studio.linked && !studio.archived) MaterialTheme.colorScheme.primary else muted,
                )
            }
        }
    }
}

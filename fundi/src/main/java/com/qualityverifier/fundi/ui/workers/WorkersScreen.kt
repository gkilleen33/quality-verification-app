package com.qualityverifier.fundi.ui.workers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
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
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.fundi.ui.home.PieceLines
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.SecondaryButton
import com.qualityverifier.ui.parts.lineStyle

/**
 * "My workers" (v0.7.1, row 44): one card of rows, each a worker with "Employee · 3 pieces"
 * and a chip, "Linked" or "Not linked"; archived workers behind "Show archived (N)" (P26).
 * "Add a worker" is a secondary button above the list, or the primary one when the list is
 * empty (P20).
 *
 * Add opens the worker form ([WorkerForm]); a row opens the worker's page. There is no delete:
 * a past piece keeps the name of whoever worked on it.
 */
@Composable
fun WorkersScreen(backLabel: String, onBack: () -> Unit, onOpen: (Worker) -> Unit) {
    val container = fundiContainer()
    val model: WorkersViewModel = viewModel(factory = WorkersViewModel.factory(container))
    val roster by model.roster.collectAsState()
    val unsent by model.unsent.collectAsState()
    val counts by model.pieceCounts.collectAsState()
    val (active, archived) = remember(roster) { roster.sortedBy { it.name.lowercase() }.partition { !it.archived } }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val labels = FundiLabels.ENGLISH
    val words = FundiScreenLabels.ENGLISH

    // Saveable: the contacts app comes in front, and the form must come back with the activity.
    var adding by rememberSaveable { mutableStateOf(false) }
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

    @Composable
    fun row(worker: Worker, divider: Boolean) {
        CardRow(
            worker.name,
            line = PieceLines.workerRowLine(labels.nameOf(worker.employment), counts[worker.id] ?: 0) +
                if (worker.id in unsent) " · " + labels.notSentYet else "",
            chip = when {
                worker.archived -> Chip.notLinked(words.archived)
                worker.linked -> Chip.linked(words.linked)
                else -> Chip.notLinked(words.notLinked)
            },
            onClick = { onOpen(worker) },
            divider = divider,
        )
    }

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(words.myWorkers)
            if (roster.isEmpty()) {
                // P20: an empty list says what goes there and offers the first move.
                RowCard(hint = words.workersEmpty)
                PrimaryButton(words.addWorker, onClick = { adding = true }, icon = Icons.Filled.Add)
            } else {
                SecondaryButton(words.addWorker, onClick = { adding = true })
                RowCard {
                    active.forEachIndexed { i, worker -> row(worker, divider = i > 0) }
                    if (archived.isNotEmpty()) {
                        CardRow(
                            if (showArchived) labels.archivedHide else words.showArchived(archived.size),
                            onClick = { showArchived = !showArchived },
                            chevron = false,
                            titleColor = Parts.Brand,
                            divider = active.isNotEmpty(),
                        )
                    }
                }
                if (showArchived && archived.isNotEmpty()) {
                    RowCard(heading = words.archived) {
                        archived.forEachIndexed { i, worker -> row(worker, divider = i > 0) }
                    }
                }
            }
        }
    }
}

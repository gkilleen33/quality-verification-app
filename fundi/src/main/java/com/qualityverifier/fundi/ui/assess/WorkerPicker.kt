package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.domain.WorkerSlot

/**
 * "Who worked on this?": the roster as chips, "Me" first, and under each chosen person
 * the parts they did.
 *
 * A person with no part chosen worked on the piece as a whole, which is one slot with a
 * null part. A person with parts chosen is one slot per part. The whole answer is held
 * by the caller as a list of slots, so the intake and the card's edit share this.
 *
 * Nothing here scores anybody: a chip says who was assigned a part, and the manager
 * decides what that means. Archived workers are not offered; they keep their names on
 * the pieces they already did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkerPicker(
    roster: List<Worker>,
    slots: List<WorkerSlot>,
    onChange: (List<WorkerSlot>) -> Unit,
    /** Adds a person to the roster from inside the picker; the caller returns the new worker. */
    onAddWorker: (String) -> Unit,
) {
    val chosen = slots.map { it.workerId }.toSet()
    // The manager is a worker with no id; the roster is everybody else, archived left out.
    val people: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to "Me") +
            roster.filter { !it.archived }.map { it.id to it.name }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { (id, name) ->
                FilterChip(
                    selected = id in chosen,
                    onClick = {
                        onChange(
                            if (id in chosen) slots.filter { it.workerId != id }
                            else slots + WorkerSlot(workerId = id, name = name.takeIf { id != null }),
                        )
                    },
                    label = { Text(name) },
                )
            }
        }

        people.filter { (id, _) -> id in chosen }.forEach { (id, name) ->
            val parts = slots.filter { it.workerId == id }.mapNotNull { it.part }.toSet()
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "$name: which part?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkPart.entries.forEach { part ->
                        FilterChip(
                            selected = part in parts,
                            onClick = {
                                val others = slots.filter { it.workerId != id }
                                val next = if (part in parts) parts - part else parts + part
                                // No part chosen means the whole piece: one slot, null part.
                                val mine = if (next.isEmpty()) {
                                    listOf(WorkerSlot(id, name.takeIf { id != null }))
                                } else {
                                    next.map { WorkerSlot(id, name.takeIf { id != null }, it) }
                                }
                                onChange(others + mine)
                            },
                            label = { Text(partName(part)) },
                        )
                    }
                }
            }
        }

        AddWorkerRow(onAddWorker)
    }
}

@Composable
private fun AddWorkerRow(onAddWorker: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { if (WorkerNames.fits(it)) name = it },
            placeholder = { Text("Add a worker") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                onAddWorker(name.trim())
                name = ""
            },
            enabled = name.isNotBlank(),
        ) { Text("Add") }
    }
}

/** English until FundiLabels gets its Swahili pass (issue #20), like the rest of this app's chrome. */
fun partName(part: WorkPart): String = when (part) {
    WorkPart.FRAME -> "Frame"
    WorkPart.JOINERY -> "Joinery"
    WorkPart.UPHOLSTERY -> "Upholstery"
    WorkPart.FINISHING -> "Finishing"
    WorkPart.ASSEMBLY -> "Assembly"
    WorkPart.OTHER -> "Other"
}

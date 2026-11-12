package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.domain.WorkerParts
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.workers.WorkerForm
import com.qualityverifier.text.FundiLabels

/**
 * "Who worked on this?": the roster as chips, "Me" first, and under each chosen person
 * "Whole piece" and the parts they did.
 *
 * A person on the whole piece is one slot with a null part; a person with parts chosen is
 * one slot per part. "Whole piece" puts on screen what the null part already meant, so a
 * manager can see that choosing nobody's parts is an answer. The rules are
 * [WorkerParts]'s; the whole answer is held by the caller as a list of slots, so the
 * intake and the card's edit share this.
 *
 * Nothing here scores anybody: a chip says who was assigned a part, and the manager
 * decides what that means. Archived workers are not offered; they keep their names on
 * the pieces they already did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkerPicker(
    /** The piece's type, which decides the part chips (no upholstery on a table). */
    itemType: ItemType,
    roster: List<Worker>,
    slots: List<WorkerSlot>,
    onChange: (List<WorkerSlot>) -> Unit,
    /** Adds a person to the roster from inside the picker and hands back the new worker. */
    onAddWorker: (WorkerDetails, (Worker) -> Unit) -> Unit,
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
                    onClick = { onChange(WorkerParts.toggleWorker(slots, id, name.takeIf { id != null })) },
                    label = { Text(name) },
                )
            }
        }

        people.filter { (id, _) -> id in chosen }.forEach { (id, name) ->
            // The manager travels without a name: "Me" is a label, not somebody's name.
            val slotName = name.takeIf { id != null }
            val parts = WorkerParts.partsOf(slots, id)
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "$name: which part?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // First, and selected for a newly chosen worker, because it is what
                    // they are recorded on until a part is chosen.
                    FilterChip(
                        selected = WorkerParts.isWholePiece(slots, id),
                        onClick = { onChange(WorkerParts.wholePiece(slots, id, slotName)) },
                        label = { Text("Whole piece") },
                    )
                    // A part this type does not offer, already held from an older assessment,
                    // stays on screen and selected, so saving does not drop it unseen.
                    WorkerParts.offered(itemType, parts).forEach { part ->
                        FilterChip(
                            selected = part in parts,
                            onClick = { onChange(WorkerParts.togglePart(slots, id, slotName, part)) },
                            label = { Text(partName(part)) },
                        )
                    }
                }
            }
        }

        // Added and chosen in one go: the person was named because they worked on this.
        // The same form as "My workers", so a worker added here can have a number and a
        // kind too, rather than a name to be completed later.
        AddWorkerButton { details ->
            onAddWorker(details) { worker -> onChange(slots + WorkerSlot(worker.id, worker.name)) }
        }
    }
}

@Composable
private fun AddWorkerButton(onAddWorker: (WorkerDetails) -> Unit) {
    val labels = FundiLabels.ENGLISH
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        WorkerForm(
            labels = labels,
            current = null,
            accepts = { WorkerNames.fits(it.trim()) && it.isNotBlank() },
            onSave = { details ->
                adding = false
                onAddWorker(details)
            },
            onDismiss = { adding = false },
        )
    }
    TextButton(onClick = { adding = true }) { Text(labels.workerAddTitle) }
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

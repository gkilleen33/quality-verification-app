package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.PieceNames
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.assess.WorkerPicker
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.fundi.ui.location.AssessmentLocationDialog
import com.qualityverifier.fundi.ui.location.rememberAssessmentLocationAnswer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.MpesaLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.ui.update.UpdateBanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The maker's pieces, and the way to start another.
 *
 * The list comes first because the loop comes back to it: a piece assessed yesterday and
 * repaired this morning is re-assessed from here, and that second pass is what fills the
 * skill file and the two rates. A maker with nothing yet sees the item types and nothing
 * else, which is the same screen with the list empty rather than a different one.
 */
@Composable
fun HomeScreen(
    /** Read the piece's most recent assessment again. */
    onOpen: (SessionSummary) -> Unit,
    /** Start a new assessment of the same piece. */
    onReassess: (SessionSummary) -> Unit,
    onStart: (ItemType) -> Unit,
    /** The roster screen. */
    onWorkers: () -> Unit,
    /** Password, the location switch, sign-out, and account deletion. */
    onSettings: () -> Unit,
) {
    val container = fundiContainer()
    val model: PiecesViewModel = viewModel(factory = PiecesViewModel.factory(container))
    val sessions by model.pieces.collectAsState()
    val roster by model.roster.collectAsState()
    val spending by model.spending.collectAsState()
    val update by container.appVersionCheck.state.collectAsState()
    // One row per physical piece rather than per assessment. A stool assessed on Monday
    // and re-assessed on Tuesday is one stool, and listing it twice would make the maker
    // work out which row is the current one.
    val pieces = remember(sessions) {
        sessions.groupBy { it.piece }
            .map { (_, assessments) -> assessments.maxBy { it.updatedAt } to assessments.size }
            .sortedByDescending { (latest, _) -> latest.updatedAt }
    }

    var choosing by remember { mutableStateOf(pieces.isEmpty()) }

    // Once, for a maker who reached Home without answering the location question: an
    // install from before it existed, a skipped setup, or a sign-in on a new phone. Until
    // they answer, assessments record no point (LocationPreference.records).
    val locationPreference = container.locationPreference
    var askLocation by remember { mutableStateOf(locationPreference.needsAnswer) }
    val answerLocation = rememberAssessmentLocationAnswer(locationPreference) { askLocation = false }
    if (askLocation) {
        AssessmentLocationDialog(FundiLabels.ENGLISH, answerLocation)
    }

    LazyColumn(
        // systemBarsPadding, because MainActivity draws edge to edge: without it the
        // wordmark sits under the notification bar. Applied per screen rather than once
        // around the NavHost, because :capture keeps its camera preview full-bleed and
        // pads only its own controls.
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Above everything, so it is seen; dismissible, so it is not in the way. English,
        // like the rest of this app for now.
        if (update.prompt == UpdatePrompt.AVAILABLE) {
            item {
                UpdateBanner(
                    labels = UpdateLabels.ENGLISH,
                    downloadUrl = update.downloadUrl,
                    onDismiss = container.appVersionCheck::dismissBanner,
                )
            }
        }

        item {
            // Beside the wordmark rather than beside "My workers": that row only exists
            // once there are pieces, and a maker with none still needs a way to sign out.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fundi Bora",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onSettings) { Text("Settings") }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (choosing || pieces.isEmpty()) {
            item {
                Text(
                    // Not "what have you just finished": learning a skill or chasing one
                    // problem does not start from a finished piece.
                    "What are you working on?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ItemType.entries) { type ->
                Button(
                    onClick = { onStart(type) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(type.homeLabel) }
            }
            if (pieces.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { choosing = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Back to my pieces") }
                }
            }
        } else {
            item {
                Button(
                    onClick = { choosing = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Assess a new piece") }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "My pieces",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onWorkers) { Text("My workers") }
                }
            }
            items(pieces, key = { (latest, _) -> latest.piece }) { (latest, count) ->
                PieceRow(
                    latest = latest,
                    assessments = count,
                    onOpen = { onOpen(latest) },
                    onReassess = { onReassess(latest) },
                    onRename = { label -> model.rename(latest.piece, label) },
                    roster = roster,
                    loadWorkers = { model.workersOf(latest.id) },
                    onSetWorkers = { people -> model.setWorkers(latest.id, people) },
                    onAddWorker = model::addWorker,
                    spent = spending[latest.piece].orEmpty(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceRow(
    latest: SessionSummary,
    assessments: Int,
    onOpen: () -> Unit,
    onReassess: () -> Unit,
    /** A new name for the piece, or null to clear it. */
    onRename: (String?) -> Unit,
    roster: List<Worker>,
    /** Who is recorded on the latest assessment, read when the edit opens. */
    loadWorkers: suspend () -> List<WorkerSlot>,
    onSetWorkers: (List<WorkerSlot>) -> Unit,
    onAddWorker: (WorkerDetails, (Worker) -> Unit) -> Unit,
    /** M-Pesa money out assigned to this piece, by category; null for none given yet. */
    spent: Map<MpesaCategory?, Double>,
) {
    var renaming by remember { mutableStateOf(false) }
    var editingWorkers by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    if (editingWorkers) {
        WorkersDialog(
            itemType = latest.itemType,
            roster = roster,
            load = loadWorkers,
            onSave = { people ->
                editingWorkers = false
                onSetWorkers(people)
            },
            onAddWorker = onAddWorker,
            onDismiss = { editingWorkers = false },
        )
    }
    if (renaming) {
        RenameDialog(
            current = latest.pieceLabel,
            onRename = { label ->
                renaming = false
                // Saved unchanged, nothing is written and nothing is sent.
                if (label != latest.pieceLabel) onRename(label)
            },
            onDismiss = { renaming = false },
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The maker's own name for it, else the kind of thing it is.
                Text(
                    latest.pieceLabel ?: latest.itemType.homeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    latest.updatedAt.asDay(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (assessments > 1) {
                Text(
                    "$assessments assessments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // An assessment with one turn is one somebody started and did not finish —
            // a phone call mid-plan, usually. Saying so is the difference between a list
            // they trust and a list of things they cannot account for.
            if (latest.messageCount <= 1) {
                Text(
                    "Not finished",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (latest.preview.isNotBlank()) {
                Text(
                    latest.preview.take(120),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spent.isNotEmpty()) SpentOnPiece(spent)
            // Two buttons and a menu rather than four buttons: on a 360 dp phone four in a
            // row squeezed "Workers" into a column one letter wide. Open and Re-assess are
            // the loop, so they stay in sight; renaming and the workers are occasional.
            Row(Modifier.fillMaxWidth()) {
                // Wraps instead of squeezing: at the largest font size the two labels do
                // not fit beside each other on a narrow phone, and a wrapped button is
                // still readable where a squeezed one is not.
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = onReassess) { Text("Re-assess") }
                }
                // The menu is anchored to the button, so it opens on this card.
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More options for this piece",
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuOpen = false
                                renaming = true
                            },
                        )
                        // The dialog's own title, so the item says what it opens.
                        DropdownMenuItem(
                            text = { Text("Who worked on this?") },
                            onClick = {
                                menuOpen = false
                                editingWorkers = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The piece's spending, one line per category in Grady's order, then money assigned without
 * a category, then the total. Fundi Bora has no screen per piece; its card is that screen.
 */
@Composable
private fun SpentOnPiece(spent: Map<MpesaCategory?, Double>, labels: MpesaLabels = MpesaLabels.ENGLISH) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(labels.spentOnPiece, style = MaterialTheme.typography.labelLarge)
        (MpesaCategory.entries + listOf(null)).forEach { category ->
            val amount = spent[category] ?: return@forEach
            Text(
                "${category?.let(labels::category) ?: labels.noCategory}: ${labels.kes(amount)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (spent.size > 1) {
            Text(
                "${labels.moneyOut}: ${labels.kes(spent.values.sum())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Who worked on this?" for the piece's latest assessment, edited after the fact. The
 * same picker as the intake; the set is replaced whole on save.
 */
@Composable
private fun WorkersDialog(
    /** The piece's type, so the parts offered match the intake's. */
    itemType: ItemType,
    roster: List<Worker>,
    load: suspend () -> List<WorkerSlot>,
    onSave: (List<WorkerSlot>) -> Unit,
    onAddWorker: (WorkerDetails, (Worker) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var slots by remember { mutableStateOf<List<WorkerSlot>?>(null) }
    LaunchedEffect(Unit) { slots = load() }
    val current = slots ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who worked on this?") },
        text = {
            // Scrolls: several people with up to seven chips each outgrow a small phone,
            // and the buttons below must stay reachable.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WorkerPicker(
                    itemType = itemType,
                    roster = roster,
                    slots = current,
                    onChange = { slots = it },
                    onAddWorker = onAddWorker,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One field and two buttons. Saving a blank clears the name, so the card goes back to
 * the item type; there is no separate "remove" to explain.
 */
@Composable
private fun RenameDialog(
    current: String?,
    onRename: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What do you call this piece?") },
        text = {
            OutlinedTextField(
                value = name,
                // The same bound as the intake and the server (V22).
                onValueChange = { if (PieceNames.fits(it)) name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim().ifEmpty { null }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Nairobi, like every other date in this project. */
private fun Long.asDay(): String =
    SimpleDateFormat("d MMM", Locale.UK)
        .apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }
        .format(Date(this))

package com.qualityverifier.fundi.ui.workers

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.fundi.ui.assess.partName
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One worker's page (plan v7 §4.6): the person at the top, then every piece they were
 * named on, newest first, with the parts they did and each assessment of the piece with
 * its date, purpose, verdict and the problems it found.
 *
 * No score and no count: the problems are the piece's, listed as the assessment found
 * them, and the manager judges (Nathan, 4 November 2026). "Edit" opens the same form as
 * "My workers", with Archive; "Open" on a piece opens its newest assessment, as the piece
 * card's Open does. Filters narrow the list to one piece and a range of dates.
 *
 * Read from the phone's own tables, so it works with no signal.
 */
@Composable
fun WorkerPageScreen(
    workerId: String,
    onBack: () -> Unit,
    /** Opens a piece's newest assessment, as Home's "Open" does. */
    onOpenPiece: (SessionSummary) -> Unit,
    /** "Send report": the worker's work across pieces, offered only while the link holds. */
    onSendReport: (workerId: String) -> Unit = {},
) {
    val container = fundiContainer()
    val model: WorkerPageViewModel = viewModel(
        key = "worker-$workerId",
        factory = WorkerPageViewModel.factory(container, workerId),
    )
    val worker by model.worker.collectAsState()
    // Null while the first read runs: nothing is said about the pieces until it is done.
    val loaded by model.pieces.collectAsState()
    val pieces = loaded.orEmpty()
    val shown = model.shown.collectAsState().value.orEmpty()
    val filter by model.filter.collectAsState()
    val labels = FundiLabels.ENGLISH

    // Saveable: the contacts app comes in front of the activity from the form, and the
    // form must come back with it (see WorkerForm).
    var editing by rememberSaveable { mutableStateOf(false) }
    val current = worker
    if (editing && current != null) {
        val before = WorkerDetails(current.name, current.phone, current.employment)
        WorkerForm(
            labels = labels,
            current = before,
            accepts = model::accepts,
            onSave = { details ->
                editing = false
                if (details != before) model.edit(details)
            },
            onArchive = if (current.archived) null else {
                {
                    editing = false
                    model.archive()
                }
            },
            onDismiss = { editing = false },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(current?.name.orEmpty(), style = MaterialTheme.typography.displaySmall)
                    current?.let { person ->
                        Text(
                            listOfNotNull(
                                labels.nameOf(person.employment),
                                person.phone,
                                labels.linkedTag.takeIf { person.linked && !person.archived },
                                labels.workerPageArchived.takeIf { person.archived },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { editing = true }, enabled = current != null) {
                    Text(labels.workerPageEdit)
                }
            }
            // The report of this worker's work across pieces (Nathan, 12 November 2026),
            // only while the two-sided match holds: the server refuses it otherwise.
            if (current != null && current.linked && !current.archived) {
                OutlinedButton(onClick = { onSendReport(current.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(labels.workerPageSendReport)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (pieces.isNotEmpty()) {
            item {
                Filters(
                    labels = labels,
                    pieces = pieces,
                    filter = filter,
                    onChange = model::setFilter,
                )
            }
        }

        if (loaded != null && pieces.isEmpty()) {
            item {
                Text(
                    labels.workerPageNoPieces,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (loaded != null && shown.isEmpty()) {
            item {
                Text(
                    labels.workerPageNoMatch,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(shown, key = { it.pieceId }) { piece ->
            PieceCard(labels, piece, onOpen = { onOpenPiece(piece.latest) })
        }

        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

/**
 * One piece and the parts they did, with an Open button, then each assessment of the
 * piece, newest first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PieceCard(labels: FundiLabels, piece: WorkerPages.Piece, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The maker's own name for it, else the kind of thing it is, as the
                // piece card shows it.
                Text(
                    piece.latest.pieceLabel ?: piece.latest.itemType.homeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onOpen) { Text(labels.workerPageOpen) }
            }
            // What this worker did on the piece: "Whole piece", or the parts, as the
            // picker names them.
            Text(
                piece.parts.joinToString(", ") { part ->
                    part?.let(::partName) ?: labels.workerPageWholePiece
                },
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            piece.assessments.forEach { assessment ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AssessmentLines(labels, assessment)
            }
        }
    }
}

/** Date, purpose and verdict on one line, then the problems as found on the piece. */
@Composable
private fun AssessmentLines(labels: FundiLabels, assessment: WorkerPages.Assessment) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val summary = assessment.summary
    val verdict = summary.verdictLevel?.let { ReportLabels.ENGLISH.verdictWord(it, summary.anythingUnchecked) }
        ?: labels.workerPageNoVerdict
    Text(
        listOf(
            summary.createdAt.asDay(),
            assessment.purpose?.let(labels::nameOf) ?: labels.workerPagePurposeUnknown,
            verdict,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
    val problems = assessment.problems
    if (problems == null) {
        // The newest diagnosis would not decode: the problems are not known, which is
        // not the same as none.
        Text(labels.workerPageProblemsUnknown, style = MaterialTheme.typography.bodySmall, color = muted)
    } else if (problems.isEmpty()) {
        Text(labels.workerPageNoProblems, style = MaterialTheme.typography.bodySmall, color = muted)
    } else {
        problems.forEach { problem ->
            val severity = ReportLabels.ENGLISH.severity(problem.severity)
            Text(
                "• " + problem.title + if (severity.isNotBlank()) " ($severity)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}

/** A piece to narrow to, and a range of days; "Clear filters" once any is set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(
    labels: FundiLabels,
    pieces: List<WorkerPages.Piece>,
    filter: WorkerPages.Filter,
    onChange: (WorkerPages.Filter) -> Unit,
) {
    var choosingPiece by remember { mutableStateOf(false) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }

    val chosen = pieces.firstOrNull { it.pieceId == filter.pieceId }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { choosingPiece = true }) {
                Text(chosen?.let { it.latest.pieceLabel ?: it.latest.itemType.homeLabel } ?: labels.workerPageAllPieces)
            }
            DropdownMenu(expanded = choosingPiece, onDismissRequest = { choosingPiece = false }) {
                DropdownMenuItem(
                    text = { Text(labels.workerPageAllPieces) },
                    onClick = {
                        choosingPiece = false
                        onChange(filter.copy(pieceId = null))
                    },
                )
                pieces.forEach { piece ->
                    DropdownMenuItem(
                        text = { Text(piece.latest.pieceLabel ?: piece.latest.itemType.homeLabel) },
                        onClick = {
                            choosingPiece = false
                            onChange(filter.copy(pieceId = piece.pieceId))
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = { pickingFrom = true }) {
            Text(filter.from?.let { "${labels.workerPageFrom} ${it.asDay()}" } ?: labels.workerPageFrom)
        }
        OutlinedButton(onClick = { pickingTo = true }) {
            Text(filter.to?.let { "${labels.workerPageTo} ${it.asDay()}" } ?: labels.workerPageTo)
        }
        if (!filter.isEmpty) {
            TextButton(onClick = { onChange(WorkerPages.Filter()) }) { Text(labels.workerPageClearFilters) }
        }
    }

    // The picker counts days at UTC midnight, so the bound it is opened on is handed
    // back as the UTC midnight of its Nairobi calendar day; otherwise a From bound, which
    // is 21:00 UTC the day before, would open on the wrong day and move back a day each
    // time it was confirmed unchanged.
    if (pickingFrom) {
        DayPicker(
            initial = filter.from?.let(::utcDayOf),
            onPicked = { day ->
                pickingFrom = false
                onChange(filter.copy(from = day?.let(::startOfDay)))
            },
            onDismiss = { pickingFrom = false },
        )
    }
    if (pickingTo) {
        DayPicker(
            initial = filter.to?.let(::utcDayOf),
            onPicked = { day ->
                pickingTo = false
                onChange(filter.copy(to = day?.let(::endOfDay)))
            },
            onDismiss = { pickingTo = false },
        )
    }
}

/** The system date picker; hands back the chosen day at UTC midnight, as the picker counts days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: Long?, onPicked: (Long?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(state.selectedDateMillis) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

private val nairobi: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")

/**
 * The picker reports a day as UTC midnight. The filter wants that calendar day in
 * Nairobi, which is where the assessments were made, from its first instant to its last.
 */
private fun startOfDay(utcMidnight: Long): Long = nairobiDay(utcMidnight)

private fun endOfDay(utcMidnight: Long): Long = nairobiDay(utcMidnight) + DAY_MILLIS - 1

private fun nairobiDay(utcMidnight: Long): Long {
    val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    val local = java.util.Calendar.getInstance(nairobi).apply {
        clear()
        set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH))
    }
    return local.timeInMillis
}

/** The reverse: the UTC midnight of the Nairobi calendar day [instant] falls on. */
private fun utcDayOf(instant: Long): Long {
    val local = java.util.Calendar.getInstance(nairobi).apply { timeInMillis = instant }
    val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH))
    }
    return utc.timeInMillis
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Nairobi, like every other date in this project. */
private fun Long.asDay(): String =
    SimpleDateFormat("d MMM yyyy", Locale.UK).apply { timeZone = nairobi }.format(Date(this))

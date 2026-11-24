package com.qualityverifier.fundi.ui.workers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerPiece
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.fundi.ui.home.PieceLines
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.SecondaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One worker's page (v0.7.1, row 44): the name, "Employee · Linked · number", "Send report"
 * while linked and "Edit", then two cards, "Their pieces for your studio" (while linked, from
 * the server) and "Pieces you named them on" (this phone's own). Each piece is one row with its
 * name and its line, "4 improvements · checked 20 Nov", and opens the piece's page. Nothing more
 * per piece: no parts, verdicts or problems (Nathan, 24 November 2026).
 *
 * No score and no count of anyone's problems (Nathan, 4 November 2026). "Edit" opens the same
 * form as "My workers", with Archive. The filters narrow the named pieces to one piece and a
 * range of days, as before.
 */
@Composable
fun WorkerPageScreen(
    workerId: String,
    backLabel: String,
    onBack: () -> Unit,
    /** Opens the piece's page: [WorkerPageRows.Row.pieceId], and the worker for their own piece. */
    onOpenPiece: (pieceId: String, theirsOf: String?) -> Unit,
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
    val theirPieces by model.theirPieces.collectAsState()
    val labels = FundiLabels.ENGLISH
    val words = FundiScreenLabels.ENGLISH

    // The worker's own pieces come from the server, and only while the link holds. Asked
    // again each time the page comes back into view, so an assessment just made shows.
    val linkedNow = worker?.let { it.linked && !it.archived } == true
    LaunchedEffect(linkedNow) { if (linkedNow) model.loadTheirPieces() }

    // Saveable: the contacts app comes in front of the activity from the form.
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

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(
                current?.name.orEmpty(),
                line = current?.let { person ->
                    listOfNotNull(
                        labels.nameOf(person.employment),
                        when {
                            person.archived -> words.archived
                            person.linked -> words.linked
                            else -> words.notLinked
                        },
                        person.phone,
                    ).joinToString(" · ")
                },
            )
            // The report of this worker's work across pieces, only while the two-sided match
            // holds: the server refuses it otherwise.
            if (current != null && linkedNow) {
                SecondaryButton(words.sendReport, onClick = { onSendReport(current.id) })
            }
            SecondaryButton(words.edit, onClick = { editing = true }, state = com.qualityverifier.ui.parts.ButtonState.Ready)

            if (linkedNow) {
                when (val state = theirPieces) {
                    WorkerPageViewModel.TheirPieces.Offline ->
                        RowCard(heading = words.theirPiecesForYourStudio, hint = words.theirPiecesOffline)
                    is WorkerPageViewModel.TheirPieces.Loaded -> {
                        val rows = WorkerPageRows.theirs(state.pieces)
                        RowCard(heading = words.theirPiecesForYourStudio, hint = words.noneYet.takeIf { rows.isEmpty() }) {
                            rows.forEachIndexed { i, row ->
                                CardRow(row.title, line = row.line, onClick = { onOpenPiece(row.pieceId, workerId) }, divider = i > 0)
                            }
                        }
                    }
                    else -> RowCard(heading = words.theirPiecesForYourStudio, hint = words.loading)
                }
            }

            if (pieces.isNotEmpty()) {
                Filters(labels = labels, pieces = pieces, filter = filter, onChange = model::setFilter)
            }
            val named = WorkerPageRows.named(shown)
            val hint = when {
                loaded == null -> words.loading
                pieces.isEmpty() -> words.noneYet
                named.isEmpty() -> labels.workerPageNoMatch
                else -> null
            }
            RowCard(heading = words.piecesYouNamedThemOn, hint = hint) {
                named.forEachIndexed { i, row ->
                    CardRow(row.title, line = row.line, onClick = { onOpenPiece(row.pieceId, null) }, divider = i > 0)
                }
            }
        }
    }
}

/** The rows of a worker's page (row 44): one per piece, its name and its line, nothing more. */
object WorkerPageRows {
    data class Row(val pieceId: String, val title: String, val line: String)

    /** The pieces this account named the worker on, one row each, newest first. */
    fun named(pieces: List<WorkerPages.Piece>): List<Row> =
        pieces.distinctBy { it.pieceId }.map { piece ->
            val newest = piece.assessments.firstOrNull()
            Row(
                pieceId = piece.pieceId,
                title = piece.latest.pieceLabel ?: piece.latest.itemType.homeLabel,
                line = PieceLines.line(newest?.problems?.size, (newest?.summary ?: piece.latest).updatedAt),
            )
        }

    /** The worker's own pieces labelled for this studio, from the server, one row each, newest first. */
    fun theirs(pieces: List<WorkerPiece>): List<Row> =
        pieces.distinctBy { it.id }.mapNotNull { piece ->
            val newest = piece.assessments.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            Row(
                pieceId = piece.id,
                title = piece.label ?: ItemType.fromId(piece.itemTypeId)?.homeLabel ?: piece.itemTypeId,
                line = PieceLines.line(if (newest.problemsKnown) newest.problems.size else null, newest.createdAt),
            ) to newest.createdAt
        }.sortedByDescending { it.second }.map { it.first }
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

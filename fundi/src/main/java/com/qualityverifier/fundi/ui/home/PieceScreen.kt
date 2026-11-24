package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qualityverifier.data.reports.WorkerPiecesOutcome
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PieceNames
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerPiece
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.assess.WorkerPicker
import com.qualityverifier.fundi.ui.assess.partName
import com.qualityverifier.fundi.ui.fundiContainer
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

/** What a piece's page opens on (row 56): one of this account's pieces, or a linked worker's. */
data class PieceRef(
    val pieceId: String,
    /** Set when opened from a linked worker's page: the piece is theirs, labelled for this studio. */
    val workerId: String? = null,
)

/**
 * A piece's page (v0.7.1, row 56; today a piece was a card with Open, Re-assess and a menu):
 * its name, who it is for, "Re-assess" and "Send report", a card of its assessments, newest
 * first, each opening that assessment, and on the fundi's own piece "About this piece", whose
 * rows open today's edits as pop-ups with their rules unchanged: the studio changes once, and
 * a linked worker's piece keeps its name.
 *
 * A linked worker's piece (opened from their page, rows 4 and 11) also lists the worker's own
 * assessments of it, from the server; those rows open nothing, since this phone holds only
 * their titles, not the conversation.
 */
@Composable
fun PieceScreen(
    ref: PieceRef,
    model: PiecesViewModel,
    backLabel: String,
    onBack: () -> Unit,
    onOpenAssessment: (SessionSummary) -> Unit,
    onReassess: (SessionSummary) -> Unit,
    onAssessTheirs: (WorkerPiece) -> Unit,
    onSendReport: (PieceSummary) -> Unit,
    onSendReportOnTheirs: (WorkerPiece) -> Unit,
    /** PR 5's Status and Reports sent, under "About this piece". */
    below: @Composable (PieceSummary?) -> Unit = {},
) {
    val container = fundiContainer()
    val words = FundiScreenLabels.ENGLISH
    val labels = FundiLabels.ENGLISH
    val pieces by model.pieces.collectAsState()
    val piece = pieces?.firstOrNull { it.pieceId == ref.pieceId }
    val studios by model.studios.collectAsState()
    val roster by model.roster.collectAsState()
    val unsent by model.unsent.collectAsState()

    // A linked worker's piece: what the server holds of it, while the link holds.
    var theirs by remember(ref) { mutableStateOf<WorkerPiece?>(null) }
    var workerName by remember(ref) { mutableStateOf<String?>(null) }
    LaunchedEffect(ref) {
        val workerId = ref.workerId ?: return@LaunchedEffect
        workerName = roster.firstOrNull { it.id == workerId }?.name
        val outcome = runCatching { container.reports.workerPieces(workerId) }.getOrNull()
        theirs = (outcome as? WorkerPiecesOutcome.Loaded)?.pieces?.pieces?.firstOrNull { it.id == ref.pieceId && it.theirs }
    }
    LaunchedEffect(roster, ref) { ref.workerId?.let { id -> roster.firstOrNull { it.id == id }?.name?.let { workerName = it } } }

    val mine = ref.workerId == null && (piece?.latest?.pieceMine ?: true)
    val name = piece?.let(::pieceName) ?: theirs?.let { it.label ?: ItemType.fromId(it.itemTypeId)?.homeLabel }.orEmpty()
    var editing by remember { mutableStateOf<Edit?>(null) }

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(
                name,
                line = if (mine) words.pieceFor(studioName(piece?.latest, studios, words)) else words.madeBy(workerName),
            )
            if (mine) {
                piece?.let { p ->
                    PrimaryButton(words.reassess, onClick = { onReassess(p.latest) })
                    SecondaryButton(words.sendReport, onClick = { onSendReport(p) })
                }
            } else {
                val worked = theirs
                if (worked != null) {
                    PrimaryButton(words.assessPiece, onClick = { onAssessTheirs(worked) })
                    SecondaryButton(words.sendReport, onClick = { onSendReportOnTheirs(worked) })
                } else piece?.let { p ->
                    // Opened from Home: the manager's own assessment of a worker's piece.
                    PrimaryButton(words.assessPiece, onClick = { onReassess(p.latest) })
                    SecondaryButton(words.sendReport, onClick = { onSendReport(p) })
                }
            }

            val rows = PieceRows.assessments(piece, theirs)
            RowCard(heading = words.assessments, hint = words.loading.takeIf { rows.isEmpty() }) {
                rows.forEachIndexed { i, row ->
                    CardRow(
                        words.assessmentRow(PieceLines.day(row.at), row.theirs),
                        line = row.line,
                        onClick = row.summary?.let { s -> { onOpenAssessment(s) } },
                        divider = i > 0,
                    )
                }
            }

            if (mine && piece != null) {
                val latest = piece.latest
                val onServer = piece.assessments.any { it.summary.messageCount > 1 }
                val canChangeStudio = onServer && studios.any { !it.archived } && !latest.pieceStudioCorrected
                RowCard(heading = words.aboutThisPiece) {
                    CardRow(
                        words.name,
                        line = latest.pieceLabel ?: latest.itemType.homeLabel,
                        chip = Chip.neutral(words.edit),
                        chevron = false,
                        onClick = { editing = Edit.NAME },
                    )
                    CardRow(
                        words.studio,
                        line = studioName(latest, studios, words) +
                            if (latest.pieceStudioCorrected) " · " + words.studioChangeUsed else "",
                        chip = Chip.neutral(words.edit).takeIf { canChangeStudio },
                        chevron = false,
                        onClick = { editing = Edit.STUDIO }.takeIf { canChangeStudio },
                        divider = true,
                    )
                    var who by remember(latest.id) { mutableStateOf<List<WorkerSlot>?>(null) }
                    LaunchedEffect(latest.id, editing) { if (editing == null) who = model.workersOf(latest.id) }
                    CardRow(
                        words.whoWorkedOnThis,
                        line = who?.let { workersText(it, roster, words) } ?: words.loading,
                        chip = Chip.neutral(words.edit),
                        chevron = false,
                        onClick = { editing = Edit.WORKERS },
                        divider = true,
                    )
                }
                if (latest.piece in unsent) {
                    Text(labels.notSentYet, style = lineStyle, color = Parts.Danger)
                }
            }
            below(piece)
        }
    }

    if (piece != null) {
        val latest = piece.latest
        when (editing) {
            Edit.NAME -> RenameDialog(words, latest.pieceLabel, onDismiss = { editing = null }) { label ->
                editing = null
                if (label != latest.pieceLabel) model.rename(latest.piece, label)
            }
            Edit.STUDIO -> StudioDialog(
                words = words,
                labels = labels,
                studios = studios.filter { !it.archived },
                current = latest.pieceStudioId.takeIf { latest.pieceStudioChosen },
                chosen = latest.pieceStudioChosen,
                onDismiss = { editing = null },
            ) { studioId ->
                editing = null
                // Unchanged, nothing is written and the correction is not spent.
                if (!latest.pieceStudioChosen || studioId != latest.pieceStudioId) model.setStudio(latest.piece, studioId)
            }
            Edit.WORKERS -> WorkersDialog(
                words = words,
                itemType = latest.itemType,
                roster = roster,
                load = { model.workersOf(latest.id) },
                onAddWorker = model::addWorker,
                onDismiss = { editing = null },
            ) { people ->
                editing = null
                model.setWorkers(latest.id, people)
            }
            null -> Unit
        }
    }
}

private enum class Edit { NAME, STUDIO, WORKERS }

/** "For: My own job", the studio's name, or "Not chosen yet" for a piece never asked. */
private fun studioName(latest: SessionSummary?, studios: List<Studio>, words: FundiScreenLabels): String = when {
    latest == null || !latest.pieceStudioChosen -> words.notAskedStudio
    latest.pieceStudioId == null -> words.forOwnJob
    else -> studios.firstOrNull { it.id == latest.pieceStudioId }?.name ?: "?"
}

/** "Otieno: frame · Wanjiru: finishing", as the mockup writes who worked on a piece. */
internal fun workersText(slots: List<WorkerSlot>, roster: List<Worker>, words: FundiScreenLabels): String {
    if (slots.isEmpty()) return words.notNamedYet
    return slots.groupBy { it.workerId }.map { (workerId, parts) ->
        // The picker's own words: "Me" for the manager, "Whole piece" for no part.
        val name = workerId?.let { id -> roster.firstOrNull { it.id == id }?.name ?: parts.first().name } ?: "Me"
        val what = parts.map { slot -> slot.part?.let(::partName) ?: "Whole piece" }.map { it.lowercase() }
        "$name: " + what.distinct().joinToString(", ")
    }.joinToString(" · ")
}

/** The rows of a piece's "Assessments" card, kept pure for the tests. */
object PieceRows {
    data class Row(
        val at: Long,
        val improvements: Int?,
        val theirs: Boolean,
        /** This phone's own copy, which the row opens; null for a worker's own assessment. */
        val summary: SessionSummary?,
        /** "4 improvements · checked 20 Nov", or "Not finished · started 20 Nov". */
        val line: String = PieceLines.line(improvements, at),
    )

    /**
     * This phone's assessments of the piece and, on a linked worker's piece, the worker's own
     * from the server, newest first, each once: an assessment this phone holds is listed from
     * its own copy.
     */
    fun assessments(piece: PieceSummary?, theirs: WorkerPiece?): List<Row> {
        val local = piece?.assessments.orEmpty().map {
            Row(it.summary.updatedAt, it.improvements, false, it.summary, PieceLines.line(it))
        }
        val held = piece?.assessments.orEmpty().map { it.summary.id }.toSet()
        val remote = theirs?.assessments.orEmpty()
            .filter { it.byWorker && it.sessionId !in held }
            .map { Row(it.createdAt, if (it.problemsKnown) it.problems.size else null, true, null) }
        return (local + remote).sortedByDescending { it.at }
    }
}

@Composable
private fun RenameDialog(words: FundiScreenLabels, current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FundiLabels.ENGLISH.intakeNameTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    // The same bound as the intake and the server (V22).
                    onValueChange = { if (PieceNames.fits(it)) name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(words.renameFoot, style = lineStyle.copy(fontStyle = FontStyle.Italic), color = Parts.Muted)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifEmpty { null }) }) { Text(words.save, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(words.cancel) } },
    )
}

/**
 * "Which studio is this piece for?": the first answer for a piece never asked, or the one
 * correction (rows 1 and 2), with radio buttons (P25), "You can change the studio once.", and
 * who can see the assessments once a studio is picked.
 */
@Composable
private fun StudioDialog(
    words: FundiScreenLabels,
    labels: FundiLabels,
    studios: List<Studio>,
    current: String?,
    chosen: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    // "" is My own job; null is nothing picked yet, which only a piece never asked starts with.
    var picked by remember { mutableStateOf(if (chosen) current ?: "" else null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.intakeStudioTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (chosen) Text(words.changeStudioOnce, style = lineStyle, color = Parts.Muted)
                studios.forEach { studio ->
                    CardRow(studio.name, radio = picked == studio.id, onClick = { picked = studio.id })
                }
                CardRow(labels.studioOwnJob, radio = picked == "", onClick = { picked = "" })
                studios.firstOrNull { it.id == picked }?.let {
                    Text(words.studioSees(it.name), style = lineStyle.copy(fontStyle = FontStyle.Italic), color = Parts.Muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picked?.ifEmpty { null }) }, enabled = picked != null) {
                Text(words.save, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(words.cancel) } },
    )
}

/** "Who worked on this?" for the piece's latest assessment: the intake's picker; replaced whole on save. */
@Composable
private fun WorkersDialog(
    words: FundiScreenLabels,
    itemType: ItemType,
    roster: List<Worker>,
    load: suspend () -> List<WorkerSlot>,
    onAddWorker: (WorkerDetails, (Worker) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<WorkerSlot>) -> Unit,
) {
    var slots by remember { mutableStateOf<List<WorkerSlot>?>(null) }
    LaunchedEffect(Unit) { slots = load() }
    val current = slots ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(words.whoWorkedOnThis, fontSize = 22.sp) },
        text = {
            // Scrolls: several people with up to seven chips each outgrow a small phone.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WorkerPicker(itemType = itemType, roster = roster, slots = current, onChange = { slots = it }, onAddWorker = onAddWorker)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text(words.save, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(words.cancel) } },
    )
}

package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.qualityverifier.data.session.PieceStudioChoice
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PieceNames
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.text.FundiLabels

private enum class Step { NAME, STUDIO, WORKERS, LANGUAGE, PURPOSE, DETAILS }

/**
 * The questions asked before anything is sent.
 *
 * Buttons, not a form: a maker standing over a piece answers two taps faster than two
 * fields, and a closed set means the model is told something it can act on. Only the
 * purposes that need the maker's own words ask for them.
 *
 * Nothing leaves this composable until [onComplete]. The whole intake is held here, so
 * backing out half way costs nothing and the model never sees half a question — the same
 * rule Kagua keeps.
 */
@Composable
fun IntakeFlow(
    labels: FundiLabels,
    /** A piece coming back is asked different questions from a new one. */
    returning: Boolean,
    /** Findings from the piece's earlier assessments, offered when checking a fix. */
    priorIssues: List<PriorIssue>,
    /** What the piece is already called, prefilled so a returning piece keeps its name. */
    initialName: String?,
    /** What the piece is, which decides the part chips in "Who worked on this?". */
    itemType: ItemType,
    /** The roster, for "Who worked on this?". */
    roster: List<Worker>,
    /** Who worked on the piece last time, as the starting answer. Empty on a new piece. */
    initialWorkers: List<WorkerSlot>,
    onAddWorker: (WorkerDetails, (Worker) -> Unit) -> Unit,
    /**
     * The answers, the piece's name or null when skipped, who worked on it, and the studio
     * it is for ([PieceStudioChoice.NotAsked] when the question was not asked).
     */
    onComplete: (FundiIntake, String?, List<WorkerSlot>, PieceStudioChoice) -> Unit,
    onCancel: () -> Unit,
    /**
     * "Studios I work for", live ones, for "Which studio is this piece for?" (v0.6.0, row
     * 1). Asked only of a new piece, and only of an account with a studio on its list.
     */
    studios: List<Studio> = emptyList(),
    /**
     * False on a linked worker's piece assessed by its studio's manager: the name is the
     * worker's, so it is neither asked nor changed.
     */
    askName: Boolean = true,
    /**
     * The purpose, already decided: the final assessment of the finished piece is a full
     * re-assessment (row 27), so its purpose is not asked.
     */
    fixedPurpose: FundiPurpose? = null,
) {
    var step by remember { mutableStateOf(if (askName) Step.NAME else Step.WORKERS) }
    val askStudio = !returning && studios.isNotEmpty()
    // Nothing chosen to begin with: the fundi picks, so a wrong label is never a default.
    var studio by remember { mutableStateOf<PieceStudioChoice?>(null) }
    // Keyed on the name, which loads a moment after this first composes: without the
    // key a returning piece's name would arrive too late to prefill the field.
    var name by remember(initialName) { mutableStateOf(initialName.orEmpty()) }
    // Keyed for the same reason as the name: the previous set loads a moment later.
    var workedOn by remember(initialWorkers) { mutableStateOf(initialWorkers) }
    var language by remember { mutableStateOf<AssessmentLanguage?>(null) }
    var purpose by remember { mutableStateOf<FundiPurpose?>(null) }
    var details by remember { mutableStateOf("") }

    fun finish(intake: FundiIntake) {
        if (intake.isComplete) {
            onComplete(intake, name.trim().ifEmpty { null }, workedOn, studio ?: PieceStudioChoice.NotAsked)
        }
    }

    /** After the language: the purpose, unless it is already decided. */
    fun afterLanguage(chosen: AssessmentLanguage) {
        val fixed = fixedPurpose
        if (fixed != null && !fixed.needsDetails) finish(FundiIntake(chosen, fixed)) else step = Step.PURPOSE
    }

    IntakeColumn {
        when (step) {
            // The name comes first because it is the one answer that outlives this
            // assessment: the card shows it, and every later report prints it.
            Step.NAME -> {
                Title(labels.intakeNameTitle)
                OutlinedTextField(
                    value = name,
                    // Bounded here as the server bounds it, so a name is never refused
                    // later for being too long.
                    onValueChange = { if (PieceNames.fits(it)) name = it },
                    placeholder = { Text(labels.intakeNameHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { step = if (askStudio) Step.STUDIO else Step.WORKERS },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(labels.intakeContinue) }
                // Skip keeps whatever the piece was called: a returning piece is not
                // unnamed by a maker who did not want to type. Clearing is the card's job.
                Secondary(labels.intakeNameSkip) {
                    name = initialName.orEmpty()
                    step = if (askStudio) Step.STUDIO else Step.WORKERS
                }
                Secondary(labels.setupBack, onCancel)
            }

            // Which studio the piece is for (Nathan, 4 November 2026; row 1): required, with
            // nothing chosen at first. The label decides which manager may see the piece.
            Step.STUDIO -> {
                Title(labels.intakeStudioTitle)
                studios.forEach { each ->
                    Picked(each.name, selected = studio == PieceStudioChoice.Studio(each.id)) {
                        studio = PieceStudioChoice.Studio(each.id)
                    }
                }
                Picked(labels.studioOwnJob, selected = studio == PieceStudioChoice.OwnJob) {
                    studio = PieceStudioChoice.OwnJob
                }
                // One sentence on who can see the assessments (Nathan, 16 November 2026).
                (studio as? PieceStudioChoice.Studio)?.let { chosen ->
                    studios.firstOrNull { it.id == chosen.studioId }?.let {
                        Text(
                            labels.intakeStudioNotice(it.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = { step = Step.WORKERS },
                    enabled = studio != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(labels.intakeContinue) }
                Secondary(labels.setupBack) { step = Step.NAME }
            }

            // Who worked on it, while the manager is standing over the piece and knows.
            // A report on one part of the piece later names these people.
            Step.WORKERS -> {
                Title("Who worked on this?")
                WorkerPicker(
                    itemType = itemType,
                    roster = roster,
                    slots = workedOn,
                    onChange = { workedOn = it },
                    onAddWorker = onAddWorker,
                )
                Button(
                    onClick = { step = Step.LANGUAGE },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(labels.intakeContinue) }
                // Skip records nobody, which reads as "not said" rather than "nobody".
                Secondary(labels.intakeNameSkip) {
                    workedOn = emptyList()
                    step = Step.LANGUAGE
                }
                if (askName) {
                    Secondary(labels.setupBack) { step = if (askStudio) Step.STUDIO else Step.NAME }
                } else {
                    Secondary(labels.setupBack, onCancel)
                }
            }

            Step.LANGUAGE -> {
                Title(labels.intakeLanguageTitle)
                AssessmentLanguage.entries.forEach { choice ->
                    // The language's own name — "Kiswahili", not "Swahili" — since the
                    // person choosing it may not read the other.
                    Choice(choice.ownName) {
                        language = choice
                        afterLanguage(choice)
                    }
                }
                Secondary(labels.setupBack) { step = Step.WORKERS }
            }

            Step.PURPOSE -> {
                Title(if (returning) labels.intakeReturningTitle else labels.intakePurposeTitle)
                val options = if (returning) FundiPurpose.forReturningPiece else FundiPurpose.forNewPiece
                options.forEach { choice ->
                    Choice(labels.nameOf(choice)) {
                        purpose = choice
                        val chosenLanguage = language ?: return@Choice
                        // Straight through when there is nothing more to ask.
                        if (!choice.needsDetails && choice != FundiPurpose.VERIFY_FIX) {
                            finish(FundiIntake(chosenLanguage, choice))
                        } else {
                            details = ""
                            step = Step.DETAILS
                        }
                    }
                }
                Secondary(labels.setupBack) { step = Step.LANGUAGE }
            }

            Step.DETAILS -> {
                val chosenLanguage = language ?: return@IntakeColumn
                val chosen = purpose ?: return@IntakeColumn

                if (chosen == FundiPurpose.VERIFY_FIX && priorIssues.isNotEmpty()) {
                    // Picked from what was recorded rather than described again: the maker
                    // would describe it differently, and the check is only meaningful
                    // against what was actually found.
                    Title(labels.intakeIssueTitle)
                    priorIssues.forEach { issue ->
                        Choice(issue.title) {
                            finish(FundiIntake(chosenLanguage, chosen, issue = issue))
                        }
                    }
                } else {
                    Title(labels.detailsPrompts[chosen] ?: labels.intakeIssueTitle)
                    if (chosen == FundiPurpose.VERIFY_FIX) {
                        // Nothing was saved to pick from — an assessment made before the
                        // findings were kept, or one whose reply never parsed.
                        Text(
                            labels.intakeNoPriorIssues,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { finish(FundiIntake(chosenLanguage, chosen, details = details)) },
                        enabled = details.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(labels.intakeContinue) }
                }
                Secondary(labels.setupBack) { step = Step.PURPOSE }
            }
        }
    }
}

@Composable
private fun IntakeColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Choice(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(label)
    }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/** A choice that stays chosen until another is tapped, for a question with a Continue. */
@Composable
private fun Picked(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(label) }
    }
}

/**
 * "Which studio are you at today?" (v0.6.0, row 9; Nathan, 16 November 2026): before the
 * day's first assessment, one tap, nothing chosen in advance, and no way on without an
 * answer. The fundi's studios, then their own workshop.
 */
@Composable
fun StudioTodayQuestion(
    labels: FundiLabels,
    studios: List<Studio>,
    onAnswer: (studioId: String?) -> Unit,
    onCancel: () -> Unit,
) {
    IntakeColumn {
        Title(labels.todayStudioTitle)
        studios.forEach { each -> Choice(each.name) { onAnswer(each.id) } }
        Choice(labels.todayStudioOwn) { onAnswer(null) }
        Secondary(labels.setupBack, onCancel)
    }
}

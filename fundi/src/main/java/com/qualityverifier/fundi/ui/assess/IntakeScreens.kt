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
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.text.FundiLabels

private enum class Step { LANGUAGE, PURPOSE, DETAILS }

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
    onComplete: (FundiIntake) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.LANGUAGE) }
    var language by remember { mutableStateOf<AssessmentLanguage?>(null) }
    var purpose by remember { mutableStateOf<FundiPurpose?>(null) }
    var details by remember { mutableStateOf("") }

    fun finish(intake: FundiIntake) {
        if (intake.isComplete) onComplete(intake)
    }

    IntakeColumn {
        when (step) {
            Step.LANGUAGE -> {
                Title(labels.intakeLanguageTitle)
                AssessmentLanguage.entries.forEach { choice ->
                    // The language's own name — "Kiswahili", not "Swahili" — since the
                    // person choosing it may not read the other.
                    Choice(choice.ownName) {
                        language = choice
                        step = Step.PURPOSE
                    }
                }
                Secondary(labels.setupBack, onCancel)
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

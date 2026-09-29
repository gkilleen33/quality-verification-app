package com.qualityverifier.fundi.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.FixPlan
import com.qualityverifier.domain.FixStage
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.ToolSuggestion
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.theme.verdictColors

/**
 * The two cards that make Fundi Bora different from Kagua.
 *
 * Kagua's verdict answers "should I hand over money". These answer "what did I do, and
 * what do I do about it" — same photographs, same engine, opposite question. They are the
 * part of the app a carpenter will judge, and the part issue #41 is waiting on.
 *
 * Both draw from already-parsed domain types, so a block that would not parse never
 * reaches here: `parseAssistantContent` drops it and the prose the prompt writes
 * alongside is shown instead. That fallback is why these can assume well-formed input.
 */

@Composable
fun DiagnosisCard(
    diagnosis: Diagnosis,
    labels: FundiLabels,
    /**
     * The word for a severity, from [com.qualityverifier.text.ReportLabels].
     *
     * Passed in rather than duplicated into FundiLabels: a defect the buyer's app calls
     * "Serious" and the maker's app calls something else is one concept with two names,
     * and no analysis spanning both apps could join them.
     */
    severityName: (Severity) -> String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(labels.diagnosisHeading)
            Text(
                labels.diagnosisSubhead,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val primary = diagnosis.primary
            if (primary == null) {
                // A clean piece is an outcome, not an empty state. Saying nothing here
                // would read as the app having failed rather than the work being right.
                Text(labels.nothingFound, style = MaterialTheme.typography.titleMedium)
            } else {
                PrimaryFinding(primary, labels, severityName)
            }

            // Recorded but not coached. Listed so the piece's record is complete and the
            // maker is not surprised later, but without causes: a fundi handed six habits
            // to change changes none of them.
            val alsoFound = diagnosis.findings.drop(1).filter { it.title.isNotBlank() }
            if (alsoFound.isNotEmpty()) {
                Label(labels.alsoFoundHeading)
                alsoFound.forEach { finding ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(finding.title, style = MaterialTheme.typography.bodyMedium)
                        SeverityTag(finding.severity, severityName)
                    }
                }
            }

            if (diagnosis.awaitingCheck) {
                Label(labels.oneCheckHeading)
                Text(diagnosis.oneCheck, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PrimaryFinding(
    finding: Finding,
    labels: FundiLabels,
    severityName: (Severity) -> String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            finding.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        SeverityTag(finding.severity, severityName)
    }

    // The chain the whole card exists for: observation, then the step in the making, then
    // what will repeat it. Each is skipped when blank rather than drawn as an empty
    // heading — a model that returns two of the three still reads as a diagnosis.
    Field(labels.whatHappenedHeading, finding.whatHappened)
    Field(labels.whereItWentWrongHeading, finding.manufacturingCause)
    Field(labels.habitToChangeHeading, finding.rootHabit)

    // Only with its unit. A gap of "3" means nothing and looks precise, which is the
    // same false-precision rule the observations table enforces with a CHECK.
    val value = finding.measuredValue
    if (value != null && finding.measuredUnit.isNotBlank()) {
        val shown = if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        Text(
            "$shown ${finding.measuredUnit}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The verdict on a re-assessment.
 *
 * Fundi Bora issues `qv-verdict` when a maker brings back a piece they have repaired —
 * that block is how the repair gets recorded, and it is what fills the two rates. Without
 * something drawing it the turn would have rendered as nothing at all: `displayProse` is
 * deliberately blank for a verdict, on the assumption that a card is showing instead.
 * Exactly the blank-turn shape of #39, one screen further on.
 *
 * Deliberately plainer than Kagua's verdict cards. A buyer is deciding whether to hand
 * over money and needs the reasoning; a maker wants to know whether the thing they fixed
 * this morning is right now.
 */
@Composable
fun VerdictCard(
    verdict: Verdict,
    labels: ReportLabels,
    modifier: Modifier = Modifier,
) {
    val colours = verdictColors(verdict.level)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colours.container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                // verdictWord, not level: "Sound" overstates a clean result when
                // something could not be checked, and the maker is the one who will
                // believe it.
                labels.verdictWord(verdict).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = colours.onContainer,
            )
            if (verdict.headline.isNotBlank()) {
                Text(
                    verdict.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = colours.onContainer,
                )
            }
            if (verdict.summary.isNotBlank()) {
                Text(
                    verdict.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colours.onContainer,
                )
            }
            verdict.defects.filter { it.title.isNotBlank() }.forEach { defect ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = colours.onContainer)
                    Text(
                        defect.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colours.onContainer,
                    )
                }
            }
        }
    }
}

@Composable
fun FixPlanCard(
    plan: FixPlan,
    labels: FundiLabels,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // One card per horizon rather than one card with three sections. Fixing this
        // piece, changing the habit and practising are three different kinds of advice,
        // and run together they read as nine things to do now — so a fundi does none.
        plan.stages.filter { (_, stage) -> stage.isRenderable }.forEach { (horizon, stage) ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(labels.nameOf(horizon))
                    Text(
                        labels.blurbOf(horizon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (stage.summary.isNotBlank()) {
                        Text(stage.summary, style = MaterialTheme.typography.titleMedium)
                    }
                    StageCost(stage, labels)
                    Steps(stage)
                }
            }
        }
        plan.toolToBuy?.takeIf { it.isRenderable }?.let { ToolCard(it, labels) }
    }
}

@Composable
private fun StageCost(stage: FixStage, labels: FundiLabels) {
    val parts = listOfNotNull(
        stage.minutes?.takeIf { it > 0 }?.let(labels::minutes),
        stage.costKes?.let(labels::cost),
    )
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { Tag(it) }
    }
}

@Composable
private fun Steps(stage: FixStage) {
    val steps = stage.steps.filter { it.isNotBlank() }
    if (steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(step, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolSuggestion, labels: FundiLabels) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(labels.toolHeading)
            Text(
                tool.name.ifBlank { tool.toolKind?.let(labels::nameOf).orEmpty() },
                style = MaterialTheme.typography.titleMedium,
            )
            // A range or nothing. priceRange returns null unless both ends came back and
            // make sense, because a single figure is a number somebody walks into a
            // hardware shop expecting.
            tool.priceRange?.let { range ->
                Text(range, style = MaterialTheme.typography.bodyLarge)
                Text(
                    labels.checkLocally,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(heading: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(heading)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SeverityTag(severity: Severity, severityName: (Severity) -> String) {
    val name = severityName(severity)
    if (name.isBlank()) return
    Tag(name)
}

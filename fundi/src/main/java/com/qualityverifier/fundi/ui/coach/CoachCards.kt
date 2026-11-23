package com.qualityverifier.fundi.ui.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.FixPlan
import com.qualityverifier.domain.FixStage
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.ToolSuggestion
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.AssistantContent
import com.qualityverifier.text.DetailAnswers
import com.qualityverifier.text.DetailKey
import com.qualityverifier.text.DetailKind
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ImprovementRow
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.customersNoticeFirst
import com.qualityverifier.text.problemsOf
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
     * The words for a severity and for a problem's consequence, from Kagua's labels.
     *
     * Passed in rather than duplicated into FundiLabels: a defect the buyer's app calls
     * "Serious" and the maker's app calls something else is one concept with two names,
     * and no analysis spanning both apps could join them. The same holds for "What I
     * see", which the customer report will print under the same problem.
     */
    reportLabels: ReportLabels,
    modifier: Modifier = Modifier,
    /**
     * Opens the photo a finding names, zoomed on its mark (v0.6.0, row 19). Null for a
     * finding with no mark, or whose photo is not on this phone: it then has no button.
     */
    showOnPhoto: (Finding) -> (() -> Unit)? = { null },
    /** The details Claude wrote on request, and those still being written (row 37). */
    details: DetailAnswers = DetailAnswers.NONE,
    /** Asks Claude for one detail of one finding; null where nothing can be sent, such as a past turn. */
    onRequestDetail: ((DetailKind, String) -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A card title at the size of the others (row 41; Nathan, 20 November 2026), not
            // the small all-caps label it was, which was too small to read; the subhead under
            // it, "Not a grade — a cause", went with it.
            Text(labels.diagnosisHeading, style = MaterialTheme.typography.titleMedium)

            if (diagnosis.findings.isEmpty()) {
                // A clean piece is an outcome, not an empty state. Saying nothing here
                // would read as the app having failed rather than the work being right.
                Text(labels.nothingFound, style = MaterialTheme.typography.titleMedium)
            }

            // Every finding with its whole chain, worst first (issue #41). The maker
            // chooses what to work on; the fix plan below is for the first.
            diagnosis.findings.filter { it.title.isNotBlank() }.forEachIndexed { index, finding ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FindingDetail(finding, labels, reportLabels, showOnPhoto(finding), details, onRequestDetail)
            }

            if (diagnosis.awaitingCheck) {
                Label(labels.oneCheckHeading)
                Text(diagnosis.oneCheck, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FindingDetail(
    finding: Finding,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    /** Opens the finding's photo at its mark; null draws no button. */
    onShowOnPhoto: (() -> Unit)?,
    details: DetailAnswers,
    onRequestDetail: ((DetailKind, String) -> Unit)?,
) {
    Row(
        // The whole title is a way in too: a maker reading "Gap at the back left joint"
        // taps the problem, not a button below it.
        Modifier.fillMaxWidth().let { if (onShowOnPhoto != null) it.clickable(onClick = onShowOnPhoto) else it },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            finding.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        SeverityTag(finding.severity, reportLabels::severity)
    }
    onShowOnPhoto?.let { show ->
        TextButton(onClick = show, contentPadding = PaddingValues(0.dp)) {
            Text(labels.seeOnPhoto)
        }
    }

    // Arm 1 (rows 34 and 37): the job, what the customer gains or may offer, and a tool the
    // maker has. A reply from before arm 1 has none of these and is drawn as it always was.
    val armOne = finding.fixLine.isNotBlank()
    if (armOne) {
        Text(finding.fixLine, style = MaterialTheme.typography.bodyLarge)
        if (finding.customerLine.isNotBlank()) {
            Text(
                finding.customerLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (finding.toolLine.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(labels.toolLineLabel)
                Text(finding.toolLine, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DetailButtons(finding, labels, details, onRequestDetail)
    } else {
        // The chain the whole card exists for: observation, then the step in the making,
        // then what will repeat it. Each is skipped when blank rather than drawn as an
        // empty heading — a model that returns two of the three still reads as a diagnosis.
        Field(labels.whatHappenedHeading, finding.whatHappened)
        Field(labels.whereItWentWrongHeading, finding.manufacturingCause)
        Field(labels.habitToChangeHeading, finding.rootHabit)
    }

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

    // The consequence, for replies that write each problem once: the finding carries it,
    // where it used to be repeated in a verdict defect the phone only ever drew the title
    // of. Blank on every finding in the older shape, so those render exactly as they did.
    // Arm 1 leaves these to the customer report: the maker reads fix_line instead.
    if (!armOne) {
        Field(reportLabels.whatISeeHeading, finding.whatISee)
        Field(reportLabels.whatItMeansHeading, finding.whatItMeans)
        Field(reportLabels.whatToDoHeading, finding.whatToDo)
    }
}

/**
 * The detail buttons under an arm 1 finding on the card (row 37). Since v0.7.0 AssessScreen draws
 * an arm 1 reply as the list (ImprovementList), whose pages have their own (ImprovementPage), so
 * this path is kept for the card alone. A tap opens the detail when the reply
 * or an earlier tap already holds it, and otherwise asks Claude for it; a second tap closes
 * it. The detail shows under the buttons, in the finding it belongs to.
 */
@Composable
private fun DetailButtons(
    finding: Finding,
    labels: FundiLabels,
    details: DetailAnswers,
    onRequestDetail: ((DetailKind, String) -> Unit)?,
) {
    // Saved across rotation and scrolling, per finding and button.
    var open by rememberSaveable(finding.title) { mutableStateOf(emptySet<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailKind.entries.forEach { kind ->
            val key = DetailKey(kind, finding.title.trim())
            val text = kind.storedIn(finding).ifBlank { details.answers[key].orEmpty() }
            val writing = key in details.pending
            val isOpen = kind.name in open
            OutlinedButton(
                onClick = {
                    open = if (isOpen) open - kind.name else open + kind.name
                    if (!isOpen && text.isBlank() && !writing) onRequestDetail?.invoke(kind, finding.title.trim())
                },
                enabled = text.isNotBlank() || writing || onRequestDetail != null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(labels.detailLabel(kind), style = MaterialTheme.typography.labelLarge)
            }
            if (isOpen) {
                when {
                    text.isNotBlank() -> Text(text, style = MaterialTheme.typography.bodyLarge)
                    writing -> Text(
                        labels.detailWriting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun FundiLabels.detailLabel(kind: DetailKind): String = when (kind) {
    DetailKind.WHAT_WE_FOUND -> detailWhatWeFound
    DetailKind.CAUSE -> detailCause
    DetailKind.NEXT_TIME -> detailNextTime
    DetailKind.TOOL -> detailTool
}

/**
 * The reply's improvements as a list (v0.7.0, release scope row 43; mockup v2): the callout
 * "What customers will notice first" with the first improvement's customer_line, then one
 * tappable row per improvement in one white block, a thin line between rows, then "One thing
 * to check". A row opens that improvement's page; everything else about it lives there.
 *
 * Every row has the same shape, the first included, though its line repeats the callout's
 * point: a list whose first row differs reads as two kinds of thing.
 */
@Composable
fun ImprovementList(
    diagnosis: Diagnosis,
    rows: List<ImprovementRow>,
    labels: FundiLabels,
    /** The severity words, from Kagua's labels in the assessment's language, as the card uses. */
    reportLabels: ReportLabels,
    /** Opens the page of the row at this position in [rows]. */
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val callout = customersNoticeFirst(rows)
        if (callout.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(labels.customersNoticeFirst, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(callout, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (rows.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                rows.forEachIndexed { position, row ->
                    if (position > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ImprovementRowItem(row, labels, reportLabels, onClick = { onOpen(position) })
                }
            }
        }

        if (diagnosis.awaitingCheck) {
            Label(labels.oneCheckHeading)
            Text(diagnosis.oneCheck, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ImprovementRowItem(row: ImprovementRow, labels: FundiLabels, reportLabels: ReportLabels, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = labels.openImprovement, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SeverityBadge(row.finding.severity, reportLabels)
            Text(row.finding.title, style = MaterialTheme.typography.titleMedium)
            if (row.finding.rowLine.isNotBlank()) {
                Text(
                    row.finding.rowLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.questions > 0) {
                Text(
                    labels.questions(row.questions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Decorative: the row's click label says what a tap does.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The severity as an icon and a word (P11: never by colour alone). The word is Kagua's, in the
 * assessment's language, so a defect has one name across both apps. Nothing for a severity the
 * model left out.
 */
@Composable
internal fun SeverityBadge(severity: Severity, reportLabels: ReportLabels) {
    val name = reportLabels.severity(severity)
    if (name.isBlank()) return
    val icon = when (severity) {
        Severity.SERIOUS -> Icons.Filled.Error
        Severity.MODERATE -> Icons.Filled.Warning
        Severity.MINOR -> Icons.Filled.Info
        Severity.COSMETIC -> Icons.Filled.Brush
        Severity.UNKNOWN -> return
    }
    val tint = if (severity == Severity.SERIOUS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // The word beside it is what a screen reader reads.
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    /** From [verdictCardProblems]; the verdict's own defects are empty in one-list replies. */
    problems: List<Problem>,
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
            problems.forEach { problem ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = colours.onContainer)
                    Text(
                        problem.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colours.onContainer,
                    )
                }
            }
        }
    }
}

/**
 * The problems the verdict card lists for one reply: their titles, one line each.
 *
 * Read through [problemsOf] rather than from the verdict's defects. Replies that write each
 * problem once leave the defects empty and put the problems in the diagnosis, and a card
 * reading only the defects would show a verdict with nothing wrong. Older replies list the
 * defects, which is what this returns for them. Untitled ones have nothing to show.
 */
internal fun verdictCardProblems(content: AssistantContent): List<Problem> =
    problemsOf(content).filter { it.title.isNotBlank() }

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
        // Time only. No cost: we have no price data, and an invented figure is one a
        // maker plans around.
        stage.minutes?.takeIf { it > 0 }?.let(labels::minutes),
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
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
internal fun Label(text: String) {
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

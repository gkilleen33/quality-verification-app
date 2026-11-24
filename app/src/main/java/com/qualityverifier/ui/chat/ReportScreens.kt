package com.qualityverifier.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.Verdict
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.ImprovementPaging
import com.qualityverifier.text.KaguaScreenLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.Callout
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.ChipView
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.SecondaryButton
import com.qualityverifier.ui.reports.verdictChip
import com.qualityverifier.ui.theme.verdictColors

/**
 * The furniture type above each assessment screen (v0.7.1, row 57): 22sp, extra bold, in the
 * brand brown, inset 16dp, so the customer always sees what is being checked.
 */
@Composable
fun FurnitureKicker(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Parts.Brand,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A problem's severity as a chip, in the colours the old cards used for it. */
@Composable
private fun severityChip(severity: Severity, labels: ReportLabels): Chip? {
    val word = labels.severity(severity).takeIf { it.isNotBlank() } ?: return null
    val colors = when (severity) {
        Severity.SERIOUS -> verdictColors(VerdictLevel.SERIOUS)
        Severity.MODERATE -> verdictColors(VerdictLevel.FAIR)
        else -> verdictColors(VerdictLevel.UNKNOWN)
    }
    return Chip(word.replaceFirstChar { it.uppercase() }, colors.container, colors.onContainer)
}

/**
 * The report (row 57; Kagua v2 mockup): the headline as the title, the summary under it, the
 * verdict chip, "What it means for you" (the first problem's meaning), then "What I found",
 * one row per problem opening its page, and "What I could not check". The question chips the
 * verdict suggests stay under it, since the conversation's box stays too.
 */
@Composable
fun ReportView(
    verdict: Verdict,
    labels: ReportLabels,
    words: KaguaScreenLabels,
    onOpenProblem: (Int) -> Unit,
    onAskQuestion: (String) -> Unit,
) {
    val problems = KaguaReport.problems(verdict)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle(verdict.headline.ifBlank { labels.verdictWord(verdict) }, line = verdict.summary.ifBlank { null })
        ChipView(verdictChip(verdict.level, labels.verdictWord(verdict).uppercase()), bold = true)
        KaguaReport.callout(verdict)?.let { Callout(words.whatItMeansForYou, it) }
        if (problems.isNotEmpty()) {
            RowCard(heading = words.whatIFound) {
                problems.forEach { row ->
                    CardRow(
                        row.defect.title,
                        topChip = severityChip(row.defect.severity, labels),
                        line = row.defect.whatItMeans.ifBlank { null },
                        onClick = { onOpenProblem(row.position) },
                        divider = row.position > 0,
                    )
                }
            }
        }
        if (verdict.unverified.isNotEmpty()) {
            RowCard(heading = words.whatICouldNotCheck) {
                verdict.unverified.forEachIndexed { i, line -> CardRow(line, divider = i > 0) }
            }
        }
        if (verdict.questions.isNotEmpty()) {
            RowCard(heading = labels.askAboutThis) {
                verdict.questions.forEachIndexed { i, question ->
                    CardRow(question, onClick = { onAskQuestion(question) }, divider = i > 0, titleColor = Parts.Brand)
                }
            }
        }
    }
}

/**
 * "What next?" and "This report" under a personal account's report (row 57). "Check another
 * <item>" starts the next piece asking only its price; "Compare" shows only on a check
 * started from "Check another"; "Check a different kind of item" is gone, since the Home tab
 * does it. "This report" holds Share and Delete, which used to be the top bar's and the row's.
 */
@Composable
fun ReportActions(
    next: WhatNext,
    itemName: String,
    words: KaguaScreenLabels,
    onCheckAnother: () -> Unit,
    onCompare: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (next.checkAnother) {
            RowCard(heading = words.whatNext) {
                CardRow(words.checkAnother(itemName), line = words.onlyPriceAsked, onClick = onCheckAnother)
                if (next.compare) CardRow(words.compare(itemName), onClick = onCompare, divider = true)
            }
        }
        if (next.share || next.delete) {
            RowCard(heading = words.thisReport) {
                if (next.share) CardRow(words.shareReport, onClick = onShare)
                if (next.delete) CardRow(words.deleteReport, onClick = onDelete, divider = next.share, titleColor = Parts.Danger)
            }
        }
    }
}

/**
 * One problem's page (row 57; Kagua v2 mockup), drawn over the report as a page is in Fundi
 * Bora: "The whole report" and "n of N"; the severity and the title; "What it means for you";
 * "What I see"; "What to ask for"; "Ask about this problem", which opens a box whose question
 * goes into the conversation under the report; then "Previous problem" and "Next problem", or
 * on the last page a filled "Done" that opens the closing screen.
 *
 * "See it on the photo" is not drawn: Kagua's reply does not say which photo shows a problem.
 */
@Composable
fun ProblemPage(
    rows: List<KaguaReport.ProblemRow>,
    position: Int,
    itemName: String,
    labels: ReportLabels,
    words: KaguaScreenLabels,
    /** Sends a question about this problem; null where nothing can be sent. */
    onAsk: ((Defect, String) -> Unit)?,
    onGoTo: (Int) -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val row = rows.getOrNull(position) ?: return
    val defect = row.defect
    var asking by rememberSaveable(position) { mutableStateOf(false) }
    var question by rememberSaveable(position) { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Parts.Page) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            BackBar(words.wholeReport, onClose, right = words.problemOf(position + 1, rows.size))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(itemName, color = Parts.Brand, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
                severityChip(defect.severity, labels)?.let { ChipView(it, fontSize = 15) }
                Text(
                    defect.title,
                    color = Parts.Ink,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                defect.whatItMeans.takeIf { it.isNotBlank() }?.let { Callout(words.whatItMeansForYou, it) }
                Field(words.whatISee, defect.whatISee)
                Field(words.whatToAskFor, defect.whatToDo, strong = true)
                if (onAsk != null) {
                    if (!asking) {
                        SecondaryButton(words.askAboutProblem, onClick = { asking = true }, icon = Icons.AutoMirrored.Outlined.Chat)
                    } else {
                        Text(words.askAboutThis, color = Parts.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = question,
                                onValueChange = { question = it },
                                placeholder = { Text(words.askAboutProblem) },
                                maxLines = 4,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    onAsk(defect, question)
                                    question = ""
                                    asking = false
                                },
                                enabled = question.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Parts.Brand),
                                modifier = Modifier.heightIn(min = 52.dp),
                            ) { Text(words.send) }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val previous = ImprovementPaging.previous(position)
                val forward = KaguaReport.forward(position, rows.size)
                OutlinedButton(
                    onClick = { previous?.let(onGoTo) },
                    enabled = previous != null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text(words.previousProblem, textAlign = TextAlign.Center, color = Parts.Ink) }
                when (forward) {
                    is ImprovementPaging.Forward.Page -> OutlinedButton(
                        onClick = { onGoTo(forward.position) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) { Text(KaguaReport.forwardLabel(words, forward), textAlign = TextAlign.Center, color = Parts.Ink) }
                    ImprovementPaging.Forward.Closing -> Button(
                        onClick = onFinish,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Parts.Brand),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) { Text(KaguaReport.forwardLabel(words, forward), textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, text: String, strong: Boolean = false) {
    if (text.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Parts.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = Parts.Ink, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal)
    }
}

/**
 * After the last problem's "Done" (row 57, P17): "That's all N problems", "Ask the seller to
 * fix each one before you pay.", "Back to the whole report", and on a personal phone "Check
 * another <item>". "The whole report" and the system Back return to the report.
 */
@Composable
fun ProblemsDone(
    count: Int,
    itemName: String,
    words: KaguaScreenLabels,
    /** Null on a shop's tablet, where one customer checks one piece. */
    onCheckAnother: (() -> Unit)?,
    onBackToReport: () -> Unit,
) {
    BackHandler(onBack = onBackToReport)
    Surface(Modifier.fillMaxSize(), color = Parts.Page) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            BackBar(words.wholeReport, onBackToReport)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(itemName, color = Parts.Brand, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
                ScreenTitle(words.thatsAll(count), line = words.askSeller(count))
                PrimaryButton(words.backToWholeReport, onClick = onBackToReport)
                onCheckAnother?.let { SecondaryButton(words.checkAnother(itemName.lowercase()), onClick = it) }
            }
        }
    }
}

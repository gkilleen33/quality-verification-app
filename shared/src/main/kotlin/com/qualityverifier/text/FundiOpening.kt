package com.qualityverifier.text

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PriorIssue

/**
 * The maker's first turn: what they want, then who they are.
 *
 * Purpose first because it changes what the model is being asked to do — a whole-piece
 * plan, or questions about one joint, or coaching on a skill — and everything after it
 * is context for that. The profile and the language line follow, from
 * [buildFundiContextMessage], so there is still one place that decides how a tool list
 * is worded.
 *
 * The language line is the maker's own choice from the intake, not whichever labels the
 * app happens to be drawn in. Fundi Bora's own wording is English only for now (issue
 * #20), but a maker who chose Kiswahili should be answered in it today; the sentence is
 * Kagua's existing one, so nothing new is being guessed at.
 */
fun buildFundiOpeningMessage(
    intake: FundiIntake,
    profile: FundiProfile,
    labels: FundiLabels,
): String {
    val purpose = purposeLine(intake, labels)
    val context = buildFundiContextMessage(profile, labels)
        // The context message ends with the app's default language line. Replaced with
        // the maker's choice rather than having both, which would leave the model with
        // two instructions and a coin to toss.
        .lines()
        .filterNot { it == labels.contextLanguage }
        .joinToString("\n")
        .trim()
    val language = ReportLabels.forLanguage(intake.language.code).intakeSaysUseLanguage
    return listOf(purpose, context, language)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun purposeLine(intake: FundiIntake, labels: FundiLabels): String {
    val details = intake.details.trim()
    return when (intake.purpose) {
        FundiPurpose.EVALUATE -> labels.saysEvaluate
        FundiPurpose.REEVALUATE -> labels.saysReevaluate
        FundiPurpose.WORK_IN_PROGRESS -> labels.saysWorkInProgress(details)
        FundiPurpose.DIAGNOSE -> labels.saysDiagnose(details)
        FundiPurpose.CUSTOMER_RETURN -> labels.saysCustomerReturn(details)
        FundiPurpose.LEARN -> labels.saysLearn(details)
        FundiPurpose.OTHER -> labels.saysOther(details)
        FundiPurpose.NEW_ISSUE -> labels.saysNewIssue(details)
        FundiPurpose.VERIFY_FIX -> {
            // The finding as it was recorded, so the check is against what was found
            // rather than the maker's memory of it. Their own description stands in only
            // when no earlier finding was saved to pick from.
            val issue = intake.issue
            val described = when {
                issue == null -> details
                issue.whatHappened.isBlank() -> issue.title
                else -> "${issue.title}: ${issue.whatHappened}"
            }
            labels.saysVerifyFix(described)
        }
    }
}

/**
 * Every finding recorded for a piece, newest assessment first, each title once.
 *
 * Read from the conversations themselves — the diagnosis findings and the verdict
 * defects — rather than from a table of issues, because the conversation is the record
 * and a second copy is one that can disagree with it.
 *
 * [assistantTurnsNewestFirst] is every assistant turn across the piece's earlier
 * assessments. Duplicate titles keep the most recent wording: if a joint gap was found
 * twice, the second description is the one the maker most recently read.
 */
fun priorIssues(assistantTurnsNewestFirst: List<String>): List<PriorIssue> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<PriorIssue>()
    for (text in assistantTurnsNewestFirst) {
        val content = parseAssistantContent(text)
        val found = content.diagnosis?.findings.orEmpty().map {
            PriorIssue(it.title.trim(), it.whatHappened.trim())
        } + content.verdict?.defects.orEmpty().map {
            PriorIssue(it.title.trim(), it.whatISee.trim())
        }
        for (issue in found) {
            if (issue.title.isBlank()) continue
            if (seen.add(issue.title.lowercase())) out += issue
        }
    }
    return out
}

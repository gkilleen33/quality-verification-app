package com.qualityverifier.text

import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.Verdict

/**
 * The problems a reply found, in the order it gave them.
 *
 * Two shapes of reply exist, and stored replies keep whichever they were written in:
 *
 *  - **Today's**: the diagnosis gives each finding's cause, and the verdict repeats every
 *    problem as a defect with its consequence. The verdict's defects are the list, as they
 *    always have been; each takes its dimension from the finding with the same title.
 *  - **One list** (the Fundi Bora prompt after v0.4.0): the verdict lists no defects, and
 *    each finding carries the consequence itself.
 *
 * The two are told apart by the findings, never by the verdict alone: a diagnosis is in
 * the one-list shape when any finding has a non-blank `what_i_see`, `what_it_means` or
 * `what_to_do` ([writesConsequences]). A verdict with no defects is not enough, because
 * today's shape writes one for a piece the model calls sound, with cosmetic findings
 * beside it that were never meant as problems, and those must stay uncounted and undrawn.
 *
 * So, in order:
 *  1. the verdict lists defects: they are the problems (today's shape);
 *  2. the diagnosis is in the one-list shape: its findings are the problems;
 *  3. a verdict with no defects beside a diagnosis in today's shape: no problems, as before;
 *  4. no verdict at all: the findings, since there is nothing they could be repeated in.
 *
 * A Kagua reply has no diagnosis and reads exactly as its defects.
 *
 * Nothing is filtered. A problem with a blank title is still a problem the count has always
 * included, and each screen already decides for itself whether it can draw one.
 */
fun problemsOf(verdict: Verdict?, diagnosis: Diagnosis?): List<Problem> {
    val defects = verdict?.defects.orEmpty()
    val findings = diagnosis?.findings.orEmpty()
    if (defects.isNotEmpty()) {
        // The first finding of a title, matching how the prompt orders them: worst first.
        val findingByTitle = findings
            .filter { it.title.isNotBlank() }
            .distinctBy { titleKey(it.title) }
            .associateBy { titleKey(it.title) }
        return defects.map { defect ->
            Problem(
                title = defect.title,
                area = defect.area,
                severityId = defect.severityId,
                dimension = findingByTitle[titleKey(defect.title)]?.dimension.orEmpty(),
                whatISee = defect.whatISee,
                whatItMeans = defect.whatItMeans,
                whatToDo = defect.whatToDo,
            )
        }
    }
    if (verdict != null && diagnosis?.writesConsequences != true) return emptyList()
    return findings.map { finding ->
        Problem(
            title = finding.title,
            area = finding.area,
            severityId = finding.severityId,
            dimension = finding.dimension,
            whatISee = finding.whatISee,
            whatItMeans = finding.whatItMeans,
            whatToDo = finding.whatToDo,
        )
    }
}

/** The problems of one reply, from the blocks in that reply alone. */
fun problemsOf(content: AssistantContent): List<Problem> =
    problemsOf(content.verdict, content.diagnosis)

/**
 * What Fundi Bora stores as `sessions.verdict_defect_count`, from the session's newest
 * verdict and newest diagnosis. Null while the session has no verdict, and null when the
 * count cannot be known.
 *
 * Newest of each rather than both from the same reply, because a follow-up often resends
 * one block and not the other. The rule, in order:
 *  1. the newest diagnosis is in the one-list shape ([writesConsequences]): its findings.
 *     A follow-up that resends only the verdict keeps that count, and one that resends only
 *     the diagnosis changes the count of a verdict already given;
 *  2. the newest verdict lists defects: their number, as before v0.4.0;
 *  3. the newest diagnosis block would not decode
 *     ([AssistantContent.diagnosisUnreadable]): null. Its problems are unknown, and
 *     reading past it to an older diagnosis, or calling the piece clean, would record a
 *     number the model never gave;
 *  4. otherwise zero: a verdict with no defects in today's shape, as before v0.4.0.
 *
 * A diagnosis that found nothing counts as the newest diagnosis, with no problems, though
 * the parser drops it from [AssistantContent.diagnosis] for having nothing to draw. So
 * does one that would not decode: the newest reply that sent a diagnosis block decides.
 *
 * Null and zero stay different (V15): zero is a verdict that found nothing; null keeps the
 * assessment out of the two rates, which is right both for diagnose-only and other partial
 * checks and for a verdict whose problems could not be read.
 *
 * [repliesNewestFirst] is every assistant reply in the session, parsed, the newest first,
 * including the one just received. Kagua does not use this: its count is the verdict's
 * defects, and it has no diagnosis.
 */
fun fundiDefectCount(repliesNewestFirst: List<AssistantContent>): Int? {
    val verdict = repliesNewestFirst.firstNotNullOfOrNull { it.verdict } ?: return null
    val diagnosisReply = repliesNewestFirst.firstOrNull { it.sentDiagnosis }
    val diagnosis = diagnosisReply?.diagnosis
    return when {
        diagnosis != null && diagnosis.writesConsequences -> diagnosis.findings.size
        verdict.defects.isNotEmpty() -> verdict.defects.size
        diagnosisReply?.diagnosisUnreadable == true -> null
        else -> 0
    }
}

/**
 * Whether this diagnosis is in the one-list shape: a finding carries its consequence.
 * Today's prompt never asks for these fields on a finding, so they are blank on every
 * reply written before the change.
 */
private val Diagnosis.writesConsequences: Boolean
    get() = findings.any { finding ->
        finding.whatISee.isNotBlank() || finding.whatItMeans.isNotBlank() || finding.whatToDo.isNotBlank()
    }

private fun titleKey(title: String): String = title.trim().lowercase()

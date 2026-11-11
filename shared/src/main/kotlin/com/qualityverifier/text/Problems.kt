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
 * So, in order:
 *  1. the verdict lists defects: they are the problems (today's shape);
 *  2. otherwise the diagnosis's findings, or none when the reply has no diagnosis.
 *
 * Rule 2 covers a verdict with no defects beside findings in either shape, and a diagnosis
 * with no verdict. Today's prompt writes a verdict with no defects for a piece the model
 * calls sound, with cosmetic findings beside it. Those findings are problems too, and
 * count against first time clean (Nathan, 11 November 2026). Telling the shapes apart by
 * the consequence fields would also read a one-list reply as clean whenever the model left
 * all three out, so the fields decide nothing here.
 *
 * A Kagua reply has no diagnosis and reads exactly as its defects; a verdict-only reply
 * of either app with no defects has no problems.
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
 *  4. the newest diagnosis came in the same reply as the newest verdict, or in a newer
 *     one: its findings. This is a verdict with no defects beside findings without
 *     consequence fields, such as today's sound verdict with a cosmetic finding. Cosmetic
 *     findings count against first time clean (Nathan, 11 November 2026), and a one-list
 *     reply whose findings left out all three fields is not clean either;
 *  5. otherwise zero.
 *
 * Rule 4 skips a diagnosis older than the verdict because of today's prompt, which runs
 * until the one-list prompt ships after v0.4.0. A verdict-only follow-up in today's shape
 * ("sound, all fixed", no defects) clears the problems of the diagnosis before it, and
 * counting that diagnosis's findings would bring back problems the model has since fixed.
 * The one-list prompt sends a verdict with its diagnosis every time, so the newest
 * diagnosis is never older there and rule 4 reads it.
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
    // Indices into the list, newest first: a smaller index is a newer reply.
    val verdictIndex = repliesNewestFirst.indexOfFirst { it.verdict != null }
    val verdict = repliesNewestFirst.getOrNull(verdictIndex)?.verdict ?: return null
    val diagnosisIndex = repliesNewestFirst.indexOfFirst { it.sentDiagnosis }
    val diagnosisReply = repliesNewestFirst.getOrNull(diagnosisIndex)
    val diagnosis = diagnosisReply?.diagnosis
    return when {
        diagnosis != null && diagnosis.writesConsequences -> diagnosis.findings.size
        verdict.defects.isNotEmpty() -> verdict.defects.size
        diagnosisReply?.diagnosisUnreadable == true -> null
        diagnosis != null && diagnosisIndex <= verdictIndex -> diagnosis.findings.size
        else -> 0
    }
}

/**
 * Whether this diagnosis is in the one-list shape: a finding carries its consequence.
 * Today's prompt never asks for these fields on a finding, so they are blank on every
 * reply written before the change.
 *
 * Public so the server's session-level readers (`reports/DefectList.kt`, the coverage
 * measure) tell the two shapes apart by this same test rather than a copy of it.
 */
val Diagnosis.writesConsequences: Boolean
    get() = findings.any { finding ->
        finding.whatISee.isNotBlank() || finding.whatItMeans.isNotBlank() || finding.whatToDo.isNotBlank()
    }

private fun titleKey(title: String): String = title.trim().lowercase()

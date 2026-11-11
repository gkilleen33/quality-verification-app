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
 * So: the verdict's defects when it lists any, otherwise the diagnosis's findings. A
 * Kagua reply has no diagnosis and reads exactly as its defects.
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
 * What Fundi Bora stores as `sessions.verdict_defect_count`: how many problems the
 * session's newest verdict and newest diagnosis, read together through [problemsOf], list.
 * Null while the session has no verdict.
 *
 * Newest of each rather than both from the same reply, because a follow-up often resends
 * one block and not the other. In the one-list shape the verdict carries no problems, so
 * a follow-up that resends only the verdict keeps the count from the diagnosis before it,
 * and one that resends only the diagnosis changes the count of a verdict already given.
 * In today's shape the verdict's own defects are the count, exactly as before.
 *
 * A diagnosis that found nothing counts as the newest diagnosis, with no problems, though
 * the parser drops it from [AssistantContent.diagnosis] for having nothing to draw.
 *
 * Null and zero stay different (V15): zero is a verdict that found nothing; null is no
 * verdict, which keeps diagnose-only and other partial checks out of the two rates.
 *
 * [repliesNewestFirst] is every assistant reply in the session, parsed, the newest first,
 * including the one just received. Kagua does not use this: its count is the verdict's
 * defects, and it has no diagnosis.
 */
fun fundiDefectCount(repliesNewestFirst: List<AssistantContent>): Int? {
    val verdict = repliesNewestFirst.firstNotNullOfOrNull { it.verdict } ?: return null
    val diagnosis = repliesNewestFirst.firstNotNullOfOrNull { reply ->
        reply.diagnosis ?: if (reply.cleanDiagnosis) Diagnosis() else null
    }
    return problemsOf(verdict, diagnosis).size
}

private fun titleKey(title: String): String = title.trim().lowercase()

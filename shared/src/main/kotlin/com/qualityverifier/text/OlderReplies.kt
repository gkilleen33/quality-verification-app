package com.qualityverifier.text

import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.Verdict

/**
 * A reply from before prompt E (before 18 November 2026) in the improvement-list design
 * (v0.7.1, release scope row 54; Nathan, 24 November 2026, approving the Fundi Bora v2 mockup).
 *
 * Such a reply has no fix_line, customer_line or row_line, and was drawn as the old card. This
 * draws it as the list instead, by a mapping on the phone: no Claude call, and the stored reply
 * is unchanged. Each improvement's "What the customer thinks" is the finding's what_it_means,
 * and "How to fix it" its what_to_do; the list row shows the title alone, since there is no
 * short line. what_happened, manufacturing_cause and root_habit stay where they are, as the
 * stored answers behind the page's detail buttons.
 *
 * Read through [problemsOf], which knows both reply shapes: in the oldest replies the meaning
 * and the fix are on the verdict's defects and blank on the findings. A defect is matched to
 * its finding by title, as [problemsOf] matches them; by place only when the two lists are the
 * same length and no other finding took it by title, since otherwise a defect at the same
 * place may be another problem.
 *
 * The rows stay the findings, never the defects, so each improvement keeps its number: the
 * number a page question names, and the server reads against the findings.
 *
 * A reply with nothing to fix anywhere (no what_to_do on a finding or a defect) is returned as
 * it is, and keeps the old card: a list whose pages say nothing under "How to fix it" would show
 * less than the card does.
 */
fun olderReplyAsList(verdict: Verdict?, diagnosis: Diagnosis): Diagnosis {
    if (diagnosis.isImprovementList) return diagnosis
    val problems = problemsOf(verdict, diagnosis)
    val matched = matchProblems(diagnosis, problems)
    val mapped = diagnosis.copy(
        findings = diagnosis.findings.mapIndexed { index, finding ->
            val problem = matched[index]
            finding.copy(
                customerLine = finding.whatItMeans.ifBlank { problem?.whatItMeans.orEmpty() }.trim(),
                fixLine = finding.whatToDo.ifBlank { problem?.whatToDo.orEmpty() }.trim(),
                // "More on what we found" reads what_happened. Where the reply stored none, its
                // what_i_see answers the same question, and showing it saves a Claude call.
                whatHappened = finding.whatHappened.ifBlank { finding.whatISee.ifBlank { problem?.whatISee.orEmpty() } }.trim(),
            )
        },
    )
    return if (mapped.isImprovementList) mapped else diagnosis
}

/**
 * Each finding's problem, by the finding's place. By title first, the first problem of a
 * title as [problemsOf] takes it; then, when the two lists are the same length, by place, but
 * only to a problem no finding took by title, so no two findings share one problem's fix.
 */
private fun matchProblems(diagnosis: Diagnosis, problems: List<Problem>): Map<Int, Problem> {
    fun key(title: String) = title.trim().lowercase()
    val firstOfTitle = problems.withIndex()
        .filter { it.value.title.isNotBlank() }
        .distinctBy { key(it.value.title) }
        .associateBy { key(it.value.title) }
    val matched = mutableMapOf<Int, Problem>()
    val taken = mutableSetOf<Int>()
    diagnosis.findings.forEachIndexed { index, finding ->
        val byTitle = firstOfTitle[key(finding.title)]?.takeIf { it.index !in taken }
        if (byTitle != null) {
            matched[index] = byTitle.value
            taken += byTitle.index
        }
    }
    if (problems.size == diagnosis.findings.size) {
        diagnosis.findings.indices
            .filter { it !in matched && it !in taken }
            .forEach { index -> matched[index] = problems[index] }
    }
    return matched
}

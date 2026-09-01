package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentPlan

/**
 * The single user turn that closes a collection run: what was taken, what was answered,
 * and — just as importantly — what was not.
 *
 * Kept as plain text rather than JSON because the assistant reads it, not the app, and
 * because the customer sees it in their own conversation. It is written in the plan's
 * language, so the test names and answers read back exactly as they were shown.
 *
 * Skipped steps are stated explicitly. A step that quietly vanishes would let the
 * assistant assume it passed, which is the one failure mode this whole flow exists to
 * avoid: a heavy wardrobe nobody could tip over must not read as a wardrobe with a clean
 * underside.
 */
fun buildSubmissionText(
    plan: AssessmentPlan,
    shots: Map<Int, String?>,
    answers: Map<Int, String?>,
    labels: ReportLabels,
): String = buildString {
    val taken = plan.photos.indices.count { shots[it] != null }
    if (plan.photos.isNotEmpty()) {
        appendLine(labels.photosTaken(taken, plan.photos.size))
        plan.photos.forEachIndexed { index, shot ->
            val title = shot.title.ifBlank { labels.shotOf(index + 1, plan.photos.size) }
            if (shots[index] != null) {
                appendLine("- $title")
            } else {
                appendLine("- $title: ${labels.notDone}")
            }
        }
    }

    if (plan.tests.isNotEmpty()) {
        if (isNotEmpty()) appendLine()
        appendLine(labels.submissionTestsHeading)
        plan.tests.forEachIndexed { index, test ->
            val title = test.title.ifBlank { labels.testOf(index + 1, plan.tests.size) }
            appendLine("- $title: ${answers[index] ?: labels.notDone}")
        }
    }
}.trimEnd()

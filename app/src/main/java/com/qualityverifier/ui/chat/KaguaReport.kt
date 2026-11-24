package com.qualityverifier.ui.chat

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ImprovementPaging
import com.qualityverifier.text.KaguaScreenLabels

/**
 * What Kagua's v2 report draws (v0.7.1, release scope row 57; Kagua v2 mockup), decided apart
 * from Compose so the rows, the paging and the buttons are tested without a device.
 */
object KaguaReport {

    /** One problem of the report: its place among the problems shown (from 0), and the defect. */
    data class ProblemRow(val position: Int, val defect: Defect)

    /**
     * "What I found": one row per problem the verdict lists, in its order, each opening that
     * problem's page. An untitled defect has nothing to show and no row.
     */
    fun problems(verdict: Verdict): List<ProblemRow> =
        verdict.defects.filter { it.title.isNotBlank() }.mapIndexed { i, defect -> ProblemRow(i, defect) }

    /** The callout "What it means for you": the first problem's meaning, or none. */
    fun callout(verdict: Verdict): String? = problems(verdict).firstOrNull()?.defect?.whatItMeans?.trim()?.ifBlank { null }

    /**
     * Where a problem page's forward button goes: the next problem, or from the last, the
     * closing screen ("That's all N problems", P17). The last page's button is a filled "Done".
     */
    fun forward(position: Int, total: Int): ImprovementPaging.Forward = ImprovementPaging.forward(position, total)

    fun forwardLabel(words: KaguaScreenLabels, forward: ImprovementPaging.Forward): String = when (forward) {
        is ImprovementPaging.Forward.Page -> words.nextProblem
        ImprovementPaging.Forward.Closing -> words.done
    }

    /**
     * The turn "Ask about this problem" sends: the question, with the problem it is about, so
     * the reply answers about that problem. It goes into the conversation, under the report.
     */
    fun questionAbout(defect: Defect, question: String): String = "About \"${defect.title.trim()}\": ${question.trim()}"
}

/**
 * The cards under a personal account's report (row 57): "What next?" and "This report". A
 * shop's tablet has neither: its Done and its QR code stay as they were (rows 12 and 31).
 */
data class WhatNext(
    val checkAnother: Boolean,
    /**
     * "Compare with the last …": only on a check started from "Check another", which has an
     * earlier piece of the same kind with a verdict, and only until it has been asked for.
     */
    val compare: Boolean,
    val share: Boolean,
    val delete: Boolean,
) {
    companion object {
        fun of(
            shopMode: Boolean,
            afterVerdict: Boolean,
            startedFromAnother: Boolean,
            previousHasVerdict: Boolean,
            comparisonRequested: Boolean,
        ): WhatNext {
            val personal = afterVerdict && !shopMode
            return WhatNext(
                checkAnother = personal,
                compare = personal && startedFromAnother && previousHasVerdict && !comparisonRequested,
                share = personal,
                delete = personal,
            )
        }
    }
}

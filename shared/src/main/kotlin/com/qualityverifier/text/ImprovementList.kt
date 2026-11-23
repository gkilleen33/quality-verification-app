package com.qualityverifier.text

import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Finding

/**
 * The Fundi Bora reply as a list of improvements, with a page per improvement (v0.7.0,
 * release scope row 43; mockup v2, "Daybed v2: customer value first", signed off by Nathan
 * and Grady Killeen, 20 to 23 November 2026).
 *
 * What the screen draws is decided here, apart from Compose, so the order, the numbering and
 * the paging are tested without a device.
 */

/**
 * Whether a reply's diagnosis is drawn as the list: prompt E's shape on, whose improvements
 * carry a fix_line (row 37). A reply from before arm 1 has none and keeps the old card.
 */
val Diagnosis.isImprovementList: Boolean get() = findings.any { it.fixLine.isNotBlank() }

/**
 * One row of the list, and the page it opens.
 *
 * @property number the improvement's place in the diagnosis, from 1, counting every finding:
 *   what a page question names ([ImprovementQuestion]), and what the server reads it against.
 * @property questions how many questions the maker has asked on its page.
 */
data class ImprovementRow(
    val number: Int,
    val finding: Finding,
    val questions: Int = 0,
) {
    /** The improvement as its page and its page questions name it. */
    val key: ImprovementKey get() = ImprovementKey(number, finding.title.trim())
}

/**
 * The rows of [diagnosis]'s list, worst first as the prompt orders them, each with its page's
 * question count from [pages]. An untitled finding has nothing to show and no row, but keeps
 * its place in the numbering, so every number still matches the server's.
 */
fun improvementRows(diagnosis: Diagnosis, pages: PageQuestions = PageQuestions.NONE): List<ImprovementRow> =
    diagnosis.findings.mapIndexedNotNull { index, finding ->
        if (finding.title.isBlank()) return@mapIndexedNotNull null
        val row = ImprovementRow(index + 1, finding)
        row.copy(questions = pages.countFor(row.key))
    }

/**
 * The callout above the list, "What customers will notice first": the first improvement's
 * customer_line as it stands (no field of its own), or blank when it has none.
 */
fun customersNoticeFirst(rows: List<ImprovementRow>): String = rows.firstOrNull()?.finding?.customerLine.orEmpty().trim()

/** "Previous improvement" and "Next improvement": positions in the list, stopping at both ends. */
object ImprovementPaging {
    /** The position before [position], or null on the first page. */
    fun previous(position: Int): Int? = (position - 1).takeIf { it >= 0 }

    /** The position after [position] in a list of [total], or null on the last page. */
    fun next(position: Int, total: Int): Int? = (position + 1).takeIf { it < total }
}

package com.qualityverifier.text

import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.ToolKind

/**
 * Fundi Bora's own wording.
 *
 * Separate from [ReportLabels] rather than added to it, for the same reason the `fb-*`
 * fences have their own prefix: this is a different audience's vocabulary. Fundi Bora still
 * uses [ReportLabels] — it reuses `qv-verdict` on a re-assessment, and a verdict needs its
 * headings — so the two are used together rather than one replacing the other.
 *
 * TRANSLATION STATUS: English only, and that is a gap rather than a decision. Kagua's
 * Swahili is unreviewed placeholder copy already (issue #20), and a producer-facing app in
 * Kenya needs that pass more than the buyer's app does, not less. Adding a guessed Swahili
 * [FundiLabels] now would make the gap harder to see, not smaller.
 */
data class FundiLabels(
    val code: String,
    /** Opens the maker's context. Their own words about their own workshop. */
    val contextIntro: String,
    val contextToolsHave: String,
    val contextToolsNone: String,
    val contextGoal: String,
    private val worksAtFormat: String,
    private val yearsFormat: String,
    private val workersFormat: String,
    private val makesFormat: String,
    private val piecesPerMonthFormat: String,
    private val timberFormat: String,
    private val borrowedFormat: String,
    val toolNames: Map<ToolKind, String>,
    val goalNames: Map<FundiGoal, String>,
) {
    fun worksAt(place: String): String = worksAtFormat.replace("{place}", place)
    fun years(count: Int): String = yearsFormat.replace("{n}", count.toString())
    fun workers(count: Int): String = workersFormat.replace("{n}", count.toString())
    fun makes(what: String): String = makesFormat.replace("{what}", what)
    fun piecesPerMonth(count: Int): String =
        piecesPerMonthFormat.replace("{n}", count.toString())
    fun timber(kind: String): String = timberFormat.replace("{kind}", kind)

    /** "a square (borrowed)". Marked because a borrowed tool may not be there next time. */
    fun borrowed(tool: String): String = borrowedFormat.replace("{tool}", tool)

    fun nameOf(kind: ToolKind): String = toolNames[kind] ?: kind.id.replace('_', ' ')
    fun nameOf(goal: FundiGoal): String = goalNames[goal] ?: goal.id.replace('_', ' ')

    companion object {
        val ENGLISH = FundiLabels(
            code = "en",
            contextIntro = "About my workshop:",
            contextToolsHave = "Tools I have:",
            // Said out loud rather than left to the absence of a mention. The prompt has to
            // be able to tell "they told us they have none" from "nobody asked".
            contextToolsNone = "Tools I do not have:",
            contextGoal = "What I want from this:",
            worksAtFormat = "I work at {place}.",
            yearsFormat = "I have been in the trade {n} years.",
            workersFormat = "There are {n} of us working.",
            makesFormat = "I mostly make {what}.",
            piecesPerMonthFormat = "I finish about {n} pieces a month.",
            timberFormat = "I usually work in {kind}.",
            borrowedFormat = "{tool} (borrowed)",
            toolNames = mapOf(
                ToolKind.HAND_SAW to "hand saw",
                ToolKind.HAMMER_MALLET to "hammer or mallet",
                ToolKind.CHISELS to "chisels",
                ToolKind.PLANE_NO4 to "no. 4 plane",
                ToolKind.CIRCULAR_SAW to "circular saw",
                ToolKind.ROUTER to "router",
                ToolKind.MARKING_GAUGE to "marking gauge",
                ToolKind.CLAMPS to "clamps",
                ToolKind.SQUARE to "square",
                ToolKind.DRILL to "drill",
                ToolKind.SANDER to "sander",
                ToolKind.OTHER to "other tools",
            ),
            goalNames = mapOf(
                FundiGoal.PRICE_PER_PIECE to "a better price per piece",
                FundiGoal.MORE_ORDERS to "more orders",
                FundiGoal.ZERO_COMEBACKS to "no pieces coming back for repair",
            ),
        )
    }
}

package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What to do about a defect, over three horizons.
 *
 * The mockup's three sections — "Fix now · 40 min · KSh 0", "Prevent · from the next
 * piece", "Drill · 10 min on Monday" — are three different kinds of advice and the
 * separation is the point. Fixing this piece earns today's money; changing the habit is
 * what stops the defect recurring; the drill is practice. Collapsed into one list they
 * read as nine things to do now, and a fundi does none of them.
 *
 * Every field has a default, for the same reason the other blocks do.
 */
@Serializable
data class FixPlan(
    val language: String = "",
    /** What to do to this piece, today, with the tools they already have. */
    @SerialName("fix_now") val fixNow: FixStage? = null,
    /** What to change from the next piece onwards. Follows from the root habit. */
    val prevent: FixStage? = null,
    /** Ten minutes on offcuts. Optional, and omitted when it would not genuinely help. */
    val drill: FixStage? = null,
    /**
     * A tool worth buying, when its absence is the actual cause.
     *
     * Optional on purpose, and the prompt forbids making a purchase the whole of the fix:
     * there is always something to do with what is already on the bench, and advice that
     * requires money is advice a fundi cannot act on today.
     */
    @SerialName("tool_to_buy") val toolToBuy: ToolSuggestion? = null,
) {
    /**
     * A plan with no immediate step is not a plan. Prevent and drill alone would tell
     * somebody holding a defective piece to do nothing about it.
     */
    val isRunnable: Boolean get() = fixNow?.hasSteps == true

    val stages: List<Pair<FixHorizon, FixStage>>
        get() = listOfNotNull(
            fixNow?.let { FixHorizon.FIX_NOW to it },
            prevent?.let { FixHorizon.PREVENT to it },
            drill?.let { FixHorizon.DRILL to it },
        )
}

/** Which of the three horizons a stage belongs to. Carries no display text: see labels. */
enum class FixHorizon(val id: String) {
    FIX_NOW("fix_now"),
    PREVENT("prevent"),
    DRILL("drill");

    companion object {
        fun fromId(id: String): FixHorizon? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

@Serializable
data class FixStage(
    /** One line: what this stage achieves. */
    val summary: String = "",
    /** An honest estimate. Null when the assistant would be guessing. */
    val minutes: Int? = null,
    // No cost field, deliberately. We have no price data for their suppliers, and an
    // invented figure becomes one a maker plans around. A stored reply from before this
    // that still carries cost_kes parses fine — unknown keys are ignored — and is simply
    // never shown.
    /**
     * The instructions, in order, one action each.
     *
     * The app walks these one at a time while the maker's hands are busy, so a step
     * carrying three actions is three steps. The prompt says so; this is where it shows.
     */
    val steps: List<String> = emptyList(),
) {
    val hasSteps: Boolean get() = steps.any { it.isNotBlank() }

    /** Worth drawing at all: a stage with neither a summary nor steps is empty. */
    val isRenderable: Boolean get() = summary.isNotBlank() || hasSteps
}

/**
 * A tool worth buying, when its absence is what caused the defect. Named, never priced.
 *
 * It used to carry a price range. Dropped: we have no data on what tools cost where a
 * maker buys them, and a number on this card is one somebody walks into a hardware shop
 * expecting. A stored reply that still carries the price fields parses — they are ignored —
 * and nothing shows them.
 */
@Serializable
data class ToolSuggestion(
    /** Matches [ToolKind]; unrecognised values render without a kind rather than failing. */
    val kind: String = "",
    val name: String = "",
) {
    val toolKind: ToolKind? get() = ToolKind.fromId(kind)

    val isRenderable: Boolean get() = name.isNotBlank() || toolKind != null
}

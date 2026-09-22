package com.qualityverifier.domain

/**
 * What a maker told us about their workshop, their tools and what they want.
 *
 * Collected once at setup — the three profile screens in the mockup — and then sent with
 * the opening turn of every assessment, because the coaching is explicitly "with your
 * tools". `prompts/fundi-master.txt` is blunt about it: *a fix that needs four sash cramps
 * is not a fix for somebody who owns none, and proposing it teaches them that you are not
 * paying attention.*
 *
 * Every field here mirrors a column in `V14__fundi_bora.sql`, and every field is optional
 * except the tools. A maker who skipped the workshop questions still gets useful coaching;
 * a maker whose tool list we do not have gets coaching that guesses.
 */
data class FundiProfile(
    val workshop: Workshop = Workshop(),
    /**
     * One entry per tool we asked about, including the ones they do not have.
     *
     * Absence is not the same as [ToolOwnership.NONE] and the difference matters: a tool
     * missing from this list means nobody asked, and a tool marked `NONE` means they told
     * us they have none. Only the second licenses the coaching to work around it. See
     * [com.qualityverifier.text.buildFundiContextMessage], which says both out loud rather
     * than leaving the model to infer either.
     */
    val tools: List<OwnedTool> = emptyList(),
    val goals: Set<FundiGoal> = emptySet(),
) {
    /** Tools they can use on this piece. Borrowed counts; see [ToolOwnership]. */
    val available: List<OwnedTool>
        get() = tools.filter { it.ownership != ToolOwnership.NONE }

    /** Tools they told us they do not have, which is what the fix plan works around. */
    val missing: List<ToolKind>
        get() = tools.filter { it.ownership == ToolOwnership.NONE }.map { it.kind }

    /**
     * True when there is anything worth telling the assistant.
     *
     * An empty profile produces no opening context at all rather than a sentence saying
     * nothing, because a turn that says "here is my context:" and then lists nothing reads
     * as a bug to the maker and as noise to the model.
     */
    val hasAnything: Boolean
        get() = tools.isNotEmpty() || goals.isNotEmpty() || workshop.hasAnything
}

/**
 * The workshop as the maker described it.
 *
 * Free text throughout, deliberately. [works_at][worksAt] is "Gikomba, third row" and
 * [makes] is "beds and wardrobes" — a product line, not an [ItemType], which is the
 * protocol an assessment runs rather than a description of a business.
 */
data class Workshop(
    val worksAt: String? = null,
    val yearsInTrade: Int? = null,
    val workers: Int? = null,
    val makes: String? = null,
    val piecesPerMonth: Int? = null,
    val usualTimber: String? = null,
    /**
     * "Would you rent your tools to nearby fundis when idle?" Asked at setup and acted on
     * only when the marketplace exists, because asking twice is worse than storing early.
     * Never sent to the assistant: it is about a future feature, not about this piece.
     */
    val rentsTools: Boolean = false,
) {
    val hasAnything: Boolean
        get() = !worksAt.isNullOrBlank() || yearsInTrade != null || workers != null ||
            !makes.isNullOrBlank() || piecesPerMonth != null || !usualTimber.isNullOrBlank()
}

/** One tool, and whether they own it, borrow it, or have none. */
data class OwnedTool(
    val kind: ToolKind,
    val ownership: ToolOwnership,
    /**
     * What they would charge to rent it out, in shillings per day. Only meaningful
     * alongside [Workshop.rentsTools], and only used by the deferred marketplace — so
     * this never reaches the assistant either.
     */
    val dayRateKes: Int? = null,
)

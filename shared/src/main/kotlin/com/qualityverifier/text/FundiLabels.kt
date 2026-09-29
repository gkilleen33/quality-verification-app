package com.qualityverifier.text

import com.qualityverifier.domain.FixHorizon
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership

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
    /**
     * Which language to answer in, said out loud in the opening turn.
     *
     * Stated rather than inferred, which is the lesson Kagua already learned: left to
     * guesswork the assistant picks one and then will not change it. Fundi Bora skipped
     * this at first, and the first real assessment came back in Kiswahili to a maker
     * whose whole context was written in English — the model had nothing to go on but
     * the fact that furniture makers in Nairobi often speak Kiswahili.
     *
     * It follows whichever labels are in use, so it becomes Kiswahili the day Kiswahili
     * labels exist rather than needing a second change. It is a starting point, not a
     * cage: the prompt follows the maker if they write in something else.
     */
    val contextLanguage: String,
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
    // ---- the setup flow. Three screens, in the order the mockup asks them.
    val setupWorkshopTitle: String,
    val setupWorkshopBlurb: String,
    val setupWorksAt: String,
    val setupYears: String,
    val setupWorkers: String,
    val setupMakes: String,
    val setupPiecesPerMonth: String,
    val setupTimber: String,
    val setupToolsTitle: String,
    val setupToolsBlurb: String,
    val setupGoalsTitle: String,
    val setupGoalsBlurb: String,
    val ownershipNames: Map<ToolOwnership, String>,
    val setupNext: String,
    val setupBack: String,
    val setupFinish: String,
    val setupSkip: String,
    val setupSaving: String,
    val setupFailed: String,
    // ---- the two cards. The mockup's own words where it has them.
    val diagnosisHeading: String,
    /** "Not a grade — a cause". The one line that says what this screen is for. */
    val diagnosisSubhead: String,
    val whatHappenedHeading: String,
    val whereItWentWrongHeading: String,
    val habitToChangeHeading: String,
    val alsoFoundHeading: String,
    val oneCheckHeading: String,
    val nothingFound: String,
    val horizonNames: Map<FixHorizon, String>,
    val horizonBlurbs: Map<FixHorizon, String>,
    val toolHeading: String,
    val checkLocally: String,
    val noCost: String,
    private val minutesFormat: String,
    private val costFormat: String,
) {
    fun minutes(n: Int): String = minutesFormat.replace("{n}", n.toString())

    /** Zero is a real answer and worth saying out loud: it is what gets the fix done. */
    fun cost(kes: Int): String =
        if (kes <= 0) noCost else costFormat.replace("{n}", kes.toString())

    fun nameOf(horizon: FixHorizon): String = horizonNames.getValue(horizon)
    fun blurbOf(horizon: FixHorizon): String = horizonBlurbs.getValue(horizon)

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
            contextLanguage = "Please answer me in English.",
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
            setupWorkshopTitle = "About your workshop",
            // Every field optional, and the screen says so: a maker who will not say how
            // many pieces they finish should still reach the coaching.
            setupWorkshopBlurb = "All of this is optional. It helps the advice fit the " +
                "work you actually do.",
            setupWorksAt = "Where you work",
            setupYears = "Years in the trade",
            setupWorkers = "People working with you",
            setupMakes = "What you mostly make",
            setupPiecesPerMonth = "Pieces a month",
            setupTimber = "Timber you usually use",
            setupToolsTitle = "Your tools",
            // The one screen that is not optional in spirit, and the blurb says why
            // rather than enforcing it — a fix built around a tool they do not own is
            // the failure the coaching prompt calls out by name.
            setupToolsBlurb = "The advice is built around what you have. Tell us what " +
                "you do not have too — that is how we avoid suggesting it.",
            setupGoalsTitle = "What you want from this",
            setupGoalsBlurb = "Pick any that fit.",
            ownershipNames = mapOf(
                ToolOwnership.OWNED to "Have it",
                ToolOwnership.BORROWED to "Can borrow",
                ToolOwnership.NONE to "Do not have",
            ),
            setupNext = "Next",
            setupBack = "Back",
            setupFinish = "Finish setup",
            setupSkip = "Skip for now",
            setupSaving = "Saving…",
            setupFailed = "Could not save that. Check your connection and try again.",
            diagnosisHeading = "DIAGNOSIS",
            diagnosisSubhead = "Not a grade — a cause",
            whatHappenedHeading = "WHAT HAPPENED",
            whereItWentWrongHeading = "WHERE IT WENT WRONG",
            // "Habit", never the person. The prompt forbids calling a maker careless,
            // and this heading is where that would otherwise creep back in.
            habitToChangeHeading = "THE HABIT TO CHANGE",
            alsoFoundHeading = "ALSO FOUND",
            oneCheckHeading = "ONE THING TO CHECK",
            nothingFound = "Nothing to put right on this one.",
            horizonNames = mapOf(
                FixHorizon.FIX_NOW to "FIX NOW",
                FixHorizon.PREVENT to "PREVENT",
                FixHorizon.DRILL to "DRILL",
            ),
            horizonBlurbs = mapOf(
                FixHorizon.FIX_NOW to "This piece, today.",
                FixHorizon.PREVENT to "From the next piece onwards.",
                FixHorizon.DRILL to "Practice on offcuts.",
            ),
            toolHeading = "A TOOL WORTH BUYING",
            checkLocally = "Prices move. Check with your own supplier.",
            noCost = "No cost",
            minutesFormat = "{n} min",
            costFormat = "KSh {n}",
            goalNames = mapOf(
                FundiGoal.PRICE_PER_PIECE to "a better price per piece",
                FundiGoal.MORE_ORDERS to "more orders",
                FundiGoal.ZERO_COMEBACKS to "no pieces coming back for repair",
            ),
        )
    }
}

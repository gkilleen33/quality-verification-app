package com.qualityverifier.domain

/**
 * A collection run in progress: the plan the assistant issued, and what has been
 * gathered against it so far.
 *
 * [shots] and [answers] are keyed by index into the plan's own lists. Presence in the
 * map means the step has been dealt with; a **null value means it was skipped**, which
 * is a real outcome and gets said out loud in the submitted turn. A heavy wardrobe
 * nobody could tip over must not read as a wardrobe with a clean underside.
 *
 * In `:shared` rather than beside the chat view model, where it was.
 *
 * It is domain and not UI: a plan, and progress against it, with no Android in it and no
 * opinion about how any of it is drawn. Two things follow from that. Both halves of the
 * project run the same collection loop — the producer app's shot runner is the buyer
 * app's, down to "Shot 5 of 7" — so this has to be reachable from both. And the screens
 * that read it (`PlanCard`, `InspectingScreen`) become depend-only-on-`:shared`, which
 * is what makes moving them into a capture module a file move rather than an untangling.
 */
data class PlanRun(
    val plan: AssessmentPlan,
    val sourceMessageId: String,
    val shots: Map<Int, String?> = emptyMap(),
    val answers: Map<Int, String?> = emptyMap(),
) {
    val nextShot: Int? get() = plan.photos.indices.firstOrNull { it !in shots }
    val nextTest: Int? get() = plan.tests.indices.firstOrNull { it !in answers }
    val photosDone: Boolean get() = nextShot == null
    val isComplete: Boolean get() = nextShot == null && nextTest == null
    val photosTaken: Int get() = plan.photos.indices.count { shots[it] != null }

    /** Paths in plan order, which is the order the assistant expects to see them. */
    val takenPaths: List<String> get() = plan.photos.indices.mapNotNull { shots[it] }

    /**
     * The physical test on screen: [revisiting], when the maker has gone Back to an earlier
     * test, or else the first test with no answer (row 40).
     *
     * Held by the screen apart from [nextTest] so that going back deletes nothing: the
     * earlier answer stays recorded, and shows as selected, until the maker chooses another.
     * Choosing one replaces it; the screen then drops [revisiting] and the run carries on
     * from the first test still unanswered.
     */
    fun testShown(revisiting: Int?): Int? = revisiting?.takeIf { it in plan.tests.indices } ?: nextTest

    /** Where Back from test [shown] goes: the test before it, or null to leave the tests. */
    fun testBefore(shown: Int): Int? = (shown - 1).takeIf { it >= 0 && it < plan.tests.size }

    /**
     * The answer to show as selected on test [index]: what the maker chose, or null for a
     * test not yet answered or skipped. A skip is a null answer, so it selects nothing.
     */
    fun answerShown(index: Int): String? = answers[index]
}

/**
 * Where the physical tests screen is (row 40), for Kagua's and Fundi Bora's screens alike.
 *
 * [revisiting] is the earlier test the person went Back to, or null for the first test with
 * no answer. The screens hold one of these and ask it what to show and where Back and an
 * answer go, so the two apps cannot drift apart and the steps can be tested without Compose.
 */
data class TestNavigation(val revisiting: Int? = null) {

    /** The test on screen for [run], or null when every test is answered. */
    fun shown(run: PlanRun): Int? = run.testShown(revisiting)

    /** After Back on the test on screen: the test before it, or null to leave the tests. */
    fun back(run: PlanRun): TestNavigation? = shown(run)?.let(run::testBefore)?.let(::TestNavigation)

    /**
     * After an answer or a skip on the test on screen. The run has recorded it (replacing any
     * earlier answer); the screen goes on to the first test with no answer.
     */
    fun afterAnswer(): TestNavigation = START

    companion object {
        /** On the first test with no answer: where every way into the tests begins. */
        val START = TestNavigation()
    }
}

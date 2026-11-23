package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress against a collection plan.
 *
 * Untested until it moved here, and worth testing now because of one subtlety that runs
 * through every accessor: **a key present with a null value means the step was skipped**,
 * which is a different thing from a step not yet reached. Confusing the two either strands
 * a run that can never complete, or reports a wardrobe nobody could tip over as a
 * wardrobe with a clean underside.
 */
class PlanRunTest {

    private fun plan(photos: Int, tests: Int) = AssessmentPlan(
        photos = (1..photos).map { PlannedShot(title = "Shot $it") },
        tests = (1..tests).map { PlannedTest(title = "Test $it") },
    )

    private fun run(
        photos: Int = 3,
        tests: Int = 2,
        shots: Map<Int, String?> = emptyMap(),
        answers: Map<Int, String?> = emptyMap(),
    ) = PlanRun(plan(photos, tests), sourceMessageId = "m1", shots = shots, answers = answers)

    @Test
    fun `the next step is the first one not yet dealt with`() {
        assertEquals(0, run().nextShot)
        assertEquals(0, run(shots = mapOf(0 to "/a.jpg")).nextTest)
        assertEquals(1, run(shots = mapOf(0 to "/a.jpg")).nextShot)
    }

    @Test
    fun `a skipped step is dealt with, not still waiting`() {
        // The distinction the whole type turns on. A null value means the customer said
        // they could not do it; without this the run would offer that step for ever.
        val skipped = run(shots = mapOf(0 to null))

        assertEquals("a skipped shot must not be offered again", 1, skipped.nextShot)
        assertEquals("but it did not produce a photo", 0, skipped.photosTaken)
        assertTrue(skipped.takenPaths.isEmpty())
    }

    @Test
    fun `a run is only complete when every step has been dealt with`() {
        assertFalse(run().isComplete)
        assertFalse(run(shots = mapOf(0 to "/a", 1 to "/b", 2 to "/c")).isComplete)

        val done = run(
            shots = mapOf(0 to "/a", 1 to null, 2 to "/c"),
            answers = mapOf(0 to "Solid", 1 to null),
        )
        assertTrue("skipped steps still count as dealt with", done.isComplete)
        assertNull(done.nextShot)
        assertNull(done.nextTest)
    }

    @Test
    fun `photos are handed over in plan order, whatever order they were taken in`() {
        // The assistant asked for them in a sequence and reads them back in that
        // sequence. A map does not promise an order, so this must not rely on one.
        val out = run(shots = mapOf(2 to "/third", 0 to "/first", 1 to "/second")).takenPaths

        assertEquals(listOf("/first", "/second", "/third"), out)
    }

    @Test
    fun `skipped shots leave no gap in what is handed over`() {
        val out = run(shots = mapOf(0 to "/first", 1 to null, 2 to "/third")).takenPaths

        assertEquals(listOf("/first", "/third"), out)
    }

    @Test
    fun `photosDone is about the photo stage alone`() {
        // The tests come after the photos, and the camera has to close before they start.
        val shotsOnly = run(shots = mapOf(0 to "/a", 1 to "/b", 2 to "/c"))

        assertTrue(shotsOnly.photosDone)
        assertFalse(shotsOnly.isComplete)
    }

    // ---- Row 40: Back on a physical test opens the test before, with its answer selected.
    // The screens keep the test they went back to (revisiting) and ask the run what to show.
    // Indexes are from 0, so "test 5" on the screen is index 4.

    /** Six tests, the first four answered (the third skipped): the screen shows test 5. */
    private fun onTestFive() = run(
        photos = 0,
        tests = 6,
        answers = mapOf(0 to "Solid", 1 to "Wobbles", 2 to null, 3 to "Gap at the joint"),
    )

    @Test
    fun `row 40 - from test 5, Back shows test 4 with its stored answer`() {
        val run = onTestFive()
        val shown = run.testShown(revisiting = null)
        assertEquals(4, shown)

        val back = run.testBefore(shown!!)
        assertEquals(3, back)
        assertEquals(3, run.testShown(revisiting = back))
        assertEquals("Gap at the joint", run.answerShown(3))
    }

    @Test
    fun `row 40 - going back deletes no answer`() {
        val run = onTestFive()
        run.testBefore(4)
        run.testShown(revisiting = 3)

        assertEquals(4, run.answers.size)
        assertEquals("Gap at the joint", run.answers[3])
        assertEquals(4, run.nextTest)
    }

    @Test
    fun `row 40 - a new answer to test 4 replaces the old one and moves on to the first test with no answer`() {
        // What the screens do on an answer: record it over the old one, then drop revisiting.
        val changed = onTestFive().let { it.copy(answers = it.answers + (3 to "Tight")) }

        assertEquals("Tight", changed.answerShown(3))
        assertEquals(4, changed.answers.size)
        assertEquals("on to test 5, the first with no answer", 4, changed.testShown(revisiting = null))
    }

    @Test
    fun `row 40 - Back on test 1 leaves the tests`() {
        val run = run(photos = 0, tests = 3)
        assertEquals(0, run.testShown(revisiting = null))
        assertNull(run.testBefore(0))
    }

    @Test
    fun `row 40 - going back to a skipped test shows no answer selected`() {
        val run = onTestFive()
        assertEquals(2, run.testShown(revisiting = 2))
        assertNull(run.answerShown(2))
        assertTrue("still counts as dealt with", 2 in run.answers)
    }

    @Test
    fun `row 40 - a revisited index the plan does not have falls back to the next test`() {
        assertEquals(4, onTestFive().testShown(revisiting = 9))
    }

    // The same steps through TestNavigation, which both screens hold: Back, then an answer
    // recorded the way both view models record one (answers + index to answer).

    /** What AssessViewModel.answerTest and Kagua's ChatViewModel.answerTest do to the run. */
    private fun PlanRun.answered(index: Int, answer: String?) = copy(answers = answers + (index to answer))

    @Test
    fun `row 40 - Back from test 5, a new answer to test 4, and on to test 5 with nothing selected`() {
        var run = onTestFive()
        var tests = TestNavigation.START
        assertEquals(4, tests.shown(run))

        tests = tests.back(run)!!
        assertEquals("test 4", 3, tests.shown(run))
        assertEquals("its answer selected", "Gap at the joint", run.answerShown(3))

        run = run.answered(3, "Tight")
        tests = tests.afterAnswer()
        assertEquals("on to test 5", 4, tests.shown(run))
        assertNull("nothing selected on test 5", run.answerShown(4))
        assertEquals("the old answer replaced, none added", "Tight", run.answers[3])
        assertEquals(4, run.answers.size)
    }

    @Test
    fun `row 40 - Back to test 1, then Back again leaves the tests with every answer kept`() {
        val run = onTestFive()
        var tests = TestNavigation.START
        repeat(4) { tests = tests.back(run)!! }
        assertEquals("test 1", 0, tests.shown(run))
        assertNull("Back on test 1 leaves", tests.back(run))
        assertEquals(onTestFive().answers, run.answers)
    }

    @Test
    fun `row 40 - a skip on a revisited test records the skip and moves on`() {
        var run = onTestFive()
        var tests = TestNavigation(revisiting = 1)
        run = run.answered(1, null)
        tests = tests.afterAnswer()
        assertTrue(1 in run.answers)
        assertNull(run.answers[1])
        assertEquals(4, tests.shown(run))
    }

    @Test
    fun `a plan that asks for nothing is already complete`() {
        // A follow-up plan often asks for one test and no photos, or the reverse.
        assertTrue(run(photos = 0, tests = 0).isComplete)
        assertTrue(run(photos = 0, tests = 0).photosDone)
        assertEquals(0, run(photos = 0, tests = 1).photosTaken)
        assertEquals(0, run(photos = 0, tests = 1).nextTest)
    }
}

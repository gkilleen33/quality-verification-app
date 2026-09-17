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

    @Test
    fun `a plan that asks for nothing is already complete`() {
        // A follow-up plan often asks for one test and no photos, or the reverse.
        assertTrue(run(photos = 0, tests = 0).isComplete)
        assertTrue(run(photos = 0, tests = 0).photosDone)
        assertEquals(0, run(photos = 0, tests = 1).photosTaken)
        assertEquals(0, run(photos = 0, tests = 1).nextTest)
    }
}

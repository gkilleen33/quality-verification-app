package com.qualityverifier.tracker.ui.terms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PesaLog's terms screen holds Fundi Bora's rules: boxes only after the end, Continue only with both. */
class TermsStateTest {

    @Test
    fun `the boxes cannot be ticked before the end of the text`() {
        val checks = TermsChecks().tickTerms(true).tickTransfer(true)

        assertFalse(checks.terms || checks.transfer)
        assertFalse(checks.canContinue(busy = false))
    }

    @Test
    fun `after the end both boxes are needed, and reaching it is latched`() {
        val reached = TermsChecks().reached()

        assertFalse(reached.tickTerms(true).canContinue(busy = false))
        val both = reached.tickTerms(true).tickTransfer(true).reached()
        assertTrue(both.canContinue(busy = false))
        assertFalse(both.canContinue(busy = true))
    }

    @Test
    fun `the end counts only once the marker is wholly on screen`() {
        assertFalse(reachedEnd(emptyList(), markerIndex = 5, viewportStart = 0, viewportEnd = 1000))
        assertFalse(reachedEnd(listOf(LaidOutItem(5, 995, 10)), markerIndex = 5, viewportStart = 0, viewportEnd = 1000))
        assertTrue(reachedEnd(listOf(LaidOutItem(5, 900, 1)), markerIndex = 5, viewportStart = 0, viewportEnd = 1000))
    }
}

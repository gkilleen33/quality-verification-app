package com.qualityverifier.fundi.terms

import androidx.compose.runtime.saveable.SaverScope
import com.qualityverifier.fundi.ui.terms.AfterTerms
import com.qualityverifier.fundi.ui.terms.LaidOutItem
import com.qualityverifier.fundi.ui.terms.TermsChecks
import com.qualityverifier.fundi.ui.terms.TermsMode
import com.qualityverifier.fundi.ui.terms.TermsRules
import com.qualityverifier.fundi.ui.terms.TermsStep
import com.qualityverifier.fundi.ui.terms.reachedEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terms screen's rules: when the boxes enable, when Continue works, what survives a
 * rotation, and where the app goes around the screen.
 */
class TermsStateTest {

    // A list 1,000 pixels tall. The end marker is item 20.
    private val viewport = 0 to 1000
    private val marker = 20

    private fun ended(vararg items: LaidOutItem) =
        reachedEnd(items.toList(), marker, viewport.first, viewport.second)

    @Test
    fun `before the first layout nothing is visible, so the boxes are disabled`() {
        assertFalse(ended())
        assertFalse(TermsChecks().boxesEnabled)
    }

    @Test
    fun `a short text that fits places the marker in the first layout`() {
        // The heading, three paragraphs and the marker, all inside the viewport, no scrolling.
        assertTrue(
            reachedEnd(
                listOf(LaidOutItem(0, 0, 100), LaidOutItem(1, 110, 200), LaidOutItem(2, 320, 200), LaidOutItem(3, 530, 1)),
                markerIndex = 3, viewportStart = 0, viewportEnd = 1000,
            )
        )
    }

    @Test
    fun `a long text enables the boxes only once the marker is on screen`() {
        // At the top: items 0 to 4 visible, the marker far below.
        assertFalse(ended(LaidOutItem(0, 0, 300), LaidOutItem(1, 310, 300), LaidOutItem(4, 900, 300)))
        // Scrolled so the marker is just below the bottom edge.
        assertFalse(ended(LaidOutItem(19, 400, 599), LaidOutItem(20, 1000, 1)))
        // Scrolled to the end of the text.
        assertTrue(ended(LaidOutItem(19, 300, 500), LaidOutItem(20, 810, 1), LaidOutItem(21, 820, 50)))
    }

    @Test
    fun `reaching the end is latched, so scrolling back up keeps the boxes enabled`() {
        var checks = TermsChecks()
        if (ended(LaidOutItem(20, 500, 1))) checks = checks.reached()
        // Back at the top: the marker is no longer visible, and nothing un-reaches it.
        if (ended(LaidOutItem(0, 0, 300))) checks = checks.reached()

        assertTrue(checks.boxesEnabled)
    }

    @Test
    fun `ticks before the end are ignored`() {
        val checks = TermsChecks().tickTerms(true).tickTransfer(true)
        assertFalse(checks.terms)
        assertFalse(checks.transfer)
        assertFalse(checks.canContinue(busy = false))
    }

    @Test
    fun `both boxes start unticked, and Continue needs both`() {
        val reached = TermsChecks().reached()
        assertFalse(reached.terms)
        assertFalse(reached.transfer)
        assertFalse(reached.canContinue(busy = false))
        assertFalse(reached.tickTerms(true).canContinue(busy = false))
        assertFalse(reached.tickTransfer(true).canContinue(busy = false))
        assertTrue(reached.tickTerms(true).tickTransfer(true).canContinue(busy = false))
        assertFalse(
            "an untick disables it again",
            reached.tickTerms(true).tickTransfer(true).tickTransfer(false).canContinue(busy = false),
        )
    }

    @Test
    fun `Continue is disabled while a request is in flight, so one tap sends one request`() {
        val ready = TermsChecks().reached().tickTerms(true).tickTransfer(true)
        assertFalse(ready.canContinue(busy = true))
    }

    @Test
    fun `the latch and the ticks survive a rotation`() {
        val before = TermsChecks().reached().tickTerms(true)
        val scope = SaverScope { true }

        val saved = with(TermsChecks.Saver) { scope.save(before) }
        val after = TermsChecks.Saver.restore(saved!!)

        assertEquals(before, after)
        assertTrue(after!!.boxesEnabled)
        assertTrue(after.terms)
        assertFalse(after.transfer)
    }

    @Test
    fun `the first screen follows the last answer`() {
        assertEquals(TermsStep.SIGN_IN, TermsRules.start(signedIn = false, termsRequired = null))
        assertEquals(TermsStep.SIGN_IN, TermsRules.start(signedIn = false, termsRequired = false))
        assertEquals(TermsStep.HOME, TermsRules.start(signedIn = true, termsRequired = false))
        assertEquals(TermsStep.TERMS, TermsRules.start(signedIn = true, termsRequired = true))
        // Upgraded from v0.4.0, or quit at the terms screen before any answer: ask.
        assertEquals(TermsStep.TERMS, TermsRules.start(signedIn = true, termsRequired = null))
    }

    @Test
    fun `after signing in, only an account that agreed skips the terms`() {
        assertEquals(TermsStep.HOME, TermsRules.afterSignIn(termsRequired = false))
        assertEquals(TermsStep.TERMS, TermsRules.afterSignIn(termsRequired = true))
        assertEquals(TermsStep.TERMS, TermsRules.afterSignIn(termsRequired = null))
    }

    @Test
    fun `after the terms, a new account goes to setup and an existing one home`() {
        assertEquals(TermsStep.SETUP, TermsRules.afterTerms(TermsMode.NEW, AfterTerms.SETUP))
        assertEquals(TermsStep.HOME, TermsRules.afterTerms(TermsMode.NEW, AfterTerms.HOME))
        assertEquals(TermsStep.SETUP, TermsRules.afterTerms(TermsMode.RETURNING, AfterTerms.SETUP))
        assertEquals(TermsStep.HOME, TermsRules.afterTerms(TermsMode.RETURNING, AfterTerms.HOME))
    }

    @Test
    fun `changed terms return to the refused screen, and a withdrawal returns home`() {
        assertEquals(TermsStep.BACK, TermsRules.afterTerms(TermsMode.CHANGED, AfterTerms.SETUP))
        assertEquals(TermsStep.BACK, TermsRules.afterTerms(TermsMode.CHANGED, AfterTerms.HOME))
        assertEquals(TermsStep.HOME, TermsRules.afterTerms(TermsMode.WITHDRAWN, AfterTerms.SETUP))
    }

    @Test
    fun `an unknown mode in the route reads as returning`() {
        assertEquals(TermsMode.RETURNING, TermsMode.fromId("nonsense"))
        assertEquals(TermsMode.CHANGED, TermsMode.fromId("changed"))
    }
}

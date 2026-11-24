package com.qualityverifier.shop

import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.TabletEnd
import com.qualityverifier.domain.Usage
import com.qualityverifier.ui.chat.ChatControls
import com.qualityverifier.ui.intake.IntakeProgress
import com.qualityverifier.ui.intake.IntakeQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kagua on a shop's tablet (release scope row 12), the parts that do not need a screen: the
 * intake's questions, the controls after a verdict, the idle watch, and which end leaving the
 * screen is. The wipe itself is tested in :core (ShopTabletWipeTest), and the server's refusals
 * in SyncRouteTest.
 */
class ShopModeTest {

    // ---------------------------------------------------------------- the intake

    @Test
    fun `row 12 - the intake skips the buying question and records buying`() {
        var progress = IntakeProgress.start(needsUpholstery = false, fixedOwnership = Ownership.BUYING)

        assertEquals(IntakeQuestion.LANGUAGE, progress.question)
        progress = progress.copy(language = AssessmentLanguage.ENGLISH)
        // Straight from the language to the price: the customer is buying.
        assertEquals(IntakeQuestion.PRICE, progress.question)
        progress = progress.copy(priceAnswered = true)
        assertEquals(IntakeQuestion.USAGE, progress.question)
        progress = progress.copy(usage = Usage.DAILY)
        assertEquals(IntakeQuestion.DEPTH, progress.question)

        assertEquals(Ownership.BUYING, progress.ownership)
        // Language, price, usage, depth: four questions, none of them the buying one.
        assertEquals(4, progress.totalSteps)
    }

    @Test
    fun `row 12 - Back on the tablet's intake never reaches the buying question`() {
        var progress: IntakeProgress? = IntakeProgress.start(needsUpholstery = true, fixedOwnership = Ownership.BUYING)
            .copy(language = AssessmentLanguage.SWAHILI, upholstered = true, priceAnswered = true)
        val seen = mutableListOf<IntakeQuestion>()
        while (progress != null) {
            seen += progress.question
            assertEquals(Ownership.BUYING, progress.ownership)
            progress = progress.back()
        }
        assertFalse(seen.toString(), IntakeQuestion.OWNERSHIP in seen)
        assertEquals(IntakeQuestion.LANGUAGE, seen.last())
    }

    @Test
    fun `row 12 - a personal intake asks the buying question, counted as in v0_6_1`() {
        // v0.6.1 counted 4 + 1 for a price + 1 for the upholstery question, and showed the
        // counter only once ownership was answered.
        var progress = IntakeProgress.start(needsUpholstery = true, fixedOwnership = null)
        assertFalse(progress.showCounter)
        progress = progress.copy(language = AssessmentLanguage.ENGLISH)
        assertEquals(IntakeQuestion.UPHOLSTERY, progress.question)
        progress = progress.copy(upholstered = false)
        assertEquals(IntakeQuestion.OWNERSHIP, progress.question)
        assertEquals(3, progress.step)

        val buying = progress.copy(ownership = Ownership.BUYING)
        assertTrue(buying.showCounter)
        assertEquals(IntakeQuestion.PRICE, buying.question)
        assertEquals(6, buying.totalSteps)
        assertEquals(buying.totalSteps - 2, buying.step)

        val owning = progress.copy(ownership = Ownership.ALREADY_OWN, priceAnswered = true)
        assertEquals(IntakeQuestion.USAGE, owning.question)
        assertEquals(5, owning.totalSteps)
        assertEquals(owning.totalSteps - 1, owning.step)

        // Back from the price question returns to the buying question.
        assertEquals(IntakeQuestion.OWNERSHIP, buying.back()!!.question)
    }

    // ---------------------------------------------------------------- after the verdict

    @Test
    fun `row 12 - the next-steps card and Share are hidden on the tablet, and Done is shown`() {
        val controls = ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = false)

        assertFalse("Share", controls.share)
        assertFalse("next steps", controls.nextSteps)
        assertTrue("Done", controls.done)
        assertFalse("gallery", controls.gallery)
        assertFalse("Settings from a failed turn", controls.settingsOnError)
    }

    @Test
    fun `row 12 - a personal Kagua account keeps Share and the next-steps card, and has no Done`() {
        val controls = ChatControls.of(shopMode = false, hasVerdict = true, runActive = false, sending = false)

        assertTrue(controls.share)
        assertTrue(controls.nextSteps)
        assertFalse(controls.done)
        assertTrue(controls.gallery)
        assertTrue(controls.settingsOnError)
    }

    @Test
    fun `row 12 - Done waits for the verdict and for nothing to be under way`() {
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = false, runActive = false, sending = false).done)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = true, sending = false).done)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = true).done)
    }

    // ---------------------------------------------------------------- the triggers

    @Test
    fun `row 12 - five minutes with no touch is idle, and a touch starts the five minutes again`() {
        var now = 0L
        val watch = IdleWatch(5L * 60 * 1000) { now }

        now += 4L * 60 * 1000 + 59_000
        assertFalse(watch.isIdle())
        now += 1_000
        assertTrue(watch.isIdle())

        watch.touched()
        assertFalse(watch.isIdle())
        now += 5L * 60 * 1000
        assertTrue(watch.isIdle())
    }

    @Test
    fun `row 12 - the screen going off or locking is screen_lock, anything else is left_app`() {
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = false, keyguardLocked = false))
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = true, keyguardLocked = true))
        assertEquals(TabletEnd.SCREEN_LOCK, endOnLeaving(screenInteractive = false, keyguardLocked = true))
        assertEquals(TabletEnd.LEFT_APP, endOnLeaving(screenInteractive = true, keyguardLocked = false))
    }

    @Test
    fun `row 12 - the four ends are the server's four words`() {
        assertEquals(listOf("done", "left_app", "screen_lock", "idle"), TabletEnd.entries.map { it.id })
        assertNull(TabletEnd.fromId("deleted"))
    }
}

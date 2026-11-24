package com.qualityverifier.ui

import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.sync.SyncStep
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Verdict
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.ImprovementPaging
import com.qualityverifier.text.KaguaScreenLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.KaguaReport
import com.qualityverifier.ui.chat.WhatNext
import com.qualityverifier.ui.home.HomeChrome
import com.qualityverifier.ui.home.HomeOffline
import com.qualityverifier.ui.main.MainTab
import com.qualityverifier.ui.reports.ReportRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kagua's v2 screens (v0.7.1, release scope rows 45, 46 and 57; Kagua v2 mockup). */
class KaguaScreensTest {

    private val words = KaguaScreenLabels.ENGLISH

    @Test
    fun `row 57 - two tabs, Home and My reports, and no Profile`() {
        assertEquals(listOf(MainTab.HOME, MainTab.REPORTS), MainTab.entries.toList())
        assertEquals(listOf("Home", "My reports"), MainTab.entries.map { it.label(words) })
        val personal = HomeChrome.of(shopMode = false)
        assertTrue("the tabs", personal.tabs)
        assertTrue("the gear opens the account's settings", personal.gear)
        assertFalse(personal.ownerControls)
    }

    @Test
    fun `row 12 - a shop's tablet keeps its owner controls and shows no tabs`() {
        val tablet = HomeChrome.of(shopMode = true)
        assertFalse("no tabs: the start screen is the whole app", tablet.tabs)
        assertFalse("no gear", tablet.gear)
        assertTrue("the owner's controls, behind the password", tablet.ownerControls)
        // After the verdict: no What next and no Share, as before.
        val next = WhatNext.of(shopMode = true, afterVerdict = true, startedFromAnother = true, previousHasVerdict = true, comparisonRequested = false)
        assertFalse(next.checkAnother || next.compare || next.share || next.delete)
    }

    @Test
    fun `row 57 - Compare shows only on a check started from Check another`() {
        fun next(fromAnother: Boolean, previous: Boolean = true, requested: Boolean = false) =
            WhatNext.of(shopMode = false, afterVerdict = true, startedFromAnother = fromAnother, previousHasVerdict = previous, comparisonRequested = requested)
        assertTrue(next(fromAnother = true).compare)
        assertFalse("a check started from Home", next(fromAnother = false).compare)
        assertFalse("the earlier piece has no verdict", next(fromAnother = true, previous = false).compare)
        assertFalse("already compared", next(fromAnother = true, requested = true).compare)
        // "Check another" is always there on a personal phone; "Check a different kind" is gone.
        assertTrue(next(fromAnother = false).checkAnother)
        assertEquals("Check another table", words.checkAnother("table"))
        assertEquals("Only the price is asked again.", words.onlyPriceAsked)
    }

    @Test
    fun `row 57 - the report has one row per problem, each opening its page`() {
        val verdict = Verdict(
            levelId = "fair",
            headline = "A flat, hard top, but two leg joints move.",
            defects = listOf(
                Defect(title = "Two leg joints move", severityId = "moderate", whatItMeans = "It may wobble within months."),
                Defect(title = ""),
                Defect(title = "A gap at the lower right corner", severityId = "minor"),
                Defect(title = "Nail heads show through the paint", severityId = "cosmetic"),
            ),
        )
        val rows = KaguaReport.problems(verdict)
        assertEquals(
            listOf("Two leg joints move", "A gap at the lower right corner", "Nail heads show through the paint"),
            rows.map { it.defect.title },
        )
        // A row's tap opens the page at its own place.
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
        assertEquals("It may wobble within months.", KaguaReport.callout(verdict))
        assertEquals("2 of 3", words.problemOf(2, 3))
    }

    @Test
    fun `row 57 - the last problem's Done opens the closing screen`() {
        assertEquals(ImprovementPaging.Forward.Page(1), KaguaReport.forward(0, 3))
        assertEquals(ImprovementPaging.Forward.Closing, KaguaReport.forward(2, 3))
        assertEquals("Next problem", KaguaReport.forwardLabel(words, KaguaReport.forward(0, 3)))
        assertEquals("Done", KaguaReport.forwardLabel(words, KaguaReport.forward(2, 3)))
        assertEquals("That's all 3 problems", words.thatsAll(3))
        assertEquals("Ask the seller to fix each one before you pay.", words.askSeller(3))
        assertEquals("That's the one problem", words.thatsAll(1))
    }

    @Test
    fun `row 57 - an unfinished check reads NOT FINISHED and asks to carry on`() {
        val summary = SessionSummary(id = "s1", itemType = ItemType.UPHOLSTERED_SOFA, createdAt = 1, updatedAt = 2, preview = "", messageCount = 3)
        val row = ReportRows.of(summary, ReportLabels.ENGLISH, words, "Last week")
        assertNull("no verdict colours", row.level)
        assertEquals("NOT FINISHED", row.chipLabel)
        assertEquals("You stopped before the photos were done. Tap to carry on.", row.line)
        assertTrue(row.title.endsWith(" · Last week"))

        val finished = ReportRows.of(summary.copy(verdictLevel = VerdictLevel.FAIR, preview = "Two leg joints move."), ReportLabels.ENGLISH, words, "2 days ago")
        assertEquals(VerdictLevel.FAIR, finished.level)
        assertEquals("Two leg joints move.", finished.line)
    }

    @Test
    fun `row 57 - Home's red box shows only when the server could not be read`() {
        val failed = SyncStatus(failedAt = 5, failed = listOf(SyncStep.PULL))
        assertTrue(HomeOffline.shows(failed, syncing = false))
        assertFalse("not while trying again", HomeOffline.shows(failed, syncing = true))
        assertFalse(HomeOffline.shows(SyncStatus(), syncing = false))
        assertEquals("Kagua can't reach the server", words.offlineTitle)
    }

    @Test
    fun `row 57 - the report's words follow Kiswahili`() {
        assertEquals("sw", KaguaScreenLabels.forLanguage("sw").code)
        assertEquals("en", KaguaScreenLabels.forLanguage("en-KE").code)
        assertEquals("en", KaguaScreenLabels.forLanguage(null).code)
    }
}

package com.qualityverifier.ui

import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.IntakeTopic
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildIntakeMessage
import com.qualityverifier.text.encodeIntake
import com.qualityverifier.ui.intake.IntakePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "A few questions first": every question after the language on one page (v0.7.1, row 58). */
class IntakePageTest {

    private fun chairPage() = IntakePage.start(AssessmentLanguage.ENGLISH, needsUpholstery = true, fixedOwnership = null)

    @Test
    fun `row 58 - one page asks every question, and Start waits for each`() {
        var page = chairPage()
        assertEquals(listOf(IntakeTopic.UPHOLSTERY, IntakeTopic.OWNERSHIP, IntakeTopic.USAGE, IntakeTopic.DEPTH), page.cards)
        assertEquals(4, page.left)
        assertFalse(page.canStart)
        assertEquals("4 questions left.", ReportLabels.ENGLISH.intakeLeft(page.left))
        // In any order, by scrolling.
        page = page.chooseDepth(AssessmentDepth.FULL).chooseUsage(Usage.OCCASIONAL).chooseUpholstery(false)
        assertEquals("1 question left.", ReportLabels.ENGLISH.intakeLeft(page.left))
        page = page.chooseOwnership(Ownership.ALREADY_OWN)
        assertTrue(page.canStart)
    }

    @Test
    fun `row 58 - the price card appears only after I'm buying it`() {
        var page = IntakePage.start(AssessmentLanguage.ENGLISH, needsUpholstery = false, fixedOwnership = null)
        assertFalse(IntakeTopic.PRICE in page.cards)
        page = page.chooseOwnership(Ownership.BUYING)
        assertEquals(listOf(IntakeTopic.OWNERSHIP, IntakeTopic.PRICE, IntakeTopic.USAGE, IntakeTopic.DEPTH), page.cards)
        // The price never holds up the start: blank is "I don't know".
        page = page.chooseUsage(Usage.DAILY).chooseDepth(AssessmentDepth.RAPID)
        assertTrue(page.canStart)
        page = page.typePrice(" 12,500 ")
        assertEquals("12,500", page.toContext().quotedPrice)
        // Changing to "I already own it" takes the card and its price away.
        val owned = page.chooseOwnership(Ownership.ALREADY_OWN)
        assertFalse(IntakeTopic.PRICE in owned.cards)
        assertEquals("", owned.toContext().quotedPrice)
    }

    @Test
    fun `row 58 - a card answered in your own words goes into the first message`() {
        val page = chairPage()
            .explain(IntakeTopic.UPHOLSTERY, "A padded seat on a wooden frame.")
            .chooseOwnership(Ownership.BUYING)
            .priceNotKnown()
            .explain(IntakeTopic.USAGE, "")
            .chooseDepth(AssessmentDepth.FULL)
        // A box opened and left empty is no answer.
        assertEquals(1, page.left)
        val ready = page.explain(IntakeTopic.USAGE, "Mostly for guests.")
        assertTrue(ready.canStart)
        val context = ready.toContext()
        assertNull("explained, so no choice", context.usage)
        assertEquals(
            "About the cushioning: A padded seat on a wooden frame. I am buying this. " +
                "I do not know what price is being asked. About how it will be used: Mostly for guests. " +
                "I would like the full assessment. Please answer me in English.",
            buildIntakeMessage(context, ReportLabels.ENGLISH),
        )
        // Not carried into the next piece: the next check asks every question again.
        assertNull(encodeIntake(context))
    }

    @Test
    fun `row 58 - choosing after explaining replaces the words, and explaining clears the choice`() {
        val chosen = chairPage().chooseUsage(Usage.DAILY)
        val explained = chosen.openExplain(IntakeTopic.USAGE)
        assertNull(explained.usage)
        assertTrue(IntakeTopic.USAGE in explained.open)
        val back = explained.explain(IntakeTopic.USAGE, "Daily, by children.").chooseUsage(Usage.BUSINESS)
        assertEquals(Usage.BUSINESS, back.usage)
        assertFalse(IntakeTopic.USAGE in back.explained)
        assertFalse(IntakeTopic.USAGE in back.open)
    }

    @Test
    fun `row 58 - a cushioning described in words is not carried into the next piece`() {
        // Every other card chosen: still not carried, since the words never settled which
        // chair protocol applies.
        val context = chairPage()
            .explain(IntakeTopic.UPHOLSTERY, "Padded seat, wooden back")
            .chooseOwnership(Ownership.ALREADY_OWN)
            .chooseUsage(Usage.DAILY)
            .chooseDepth(AssessmentDepth.FULL)
            .toContext()
        assertFalse(context.isComplete)
        assertNull(encodeIntake(context))
    }

    @Test
    fun `row 58 - buying told in words keeps the price card, and its price is told`() {
        var page = IntakePage.start(AssessmentLanguage.ENGLISH, needsUpholstery = false, fixedOwnership = null)
            .explain(IntakeTopic.OWNERSHIP, "Buying it for my sister")
        assertTrue(IntakeTopic.PRICE in page.cards)
        page = page.typePrice("4,000").chooseUsage(Usage.OCCASIONAL).chooseDepth(AssessmentDepth.RAPID)
        assertTrue(page.canStart)
        assertEquals(
            "About buying it: Buying it for my sister. The seller is asking 4,000. " +
                "It will only be used occasionally. I would like a rapid assessment. Please answer me in English.",
            buildIntakeMessage(page.toContext(), ReportLabels.ENGLISH),
        )
        // Choosing "I already own it" afterwards takes the price away.
        assertEquals("", page.chooseOwnership(Ownership.ALREADY_OWN).toContext().quotedPrice)
    }

    @Test
    fun `row 58 - words on several lines become one sentence with a full stop`() {
        val page = IntakePage.start(AssessmentLanguage.ENGLISH, needsUpholstery = false, fixedOwnership = null)
            .chooseOwnership(Ownership.BUYING)
            .explain(IntakeTopic.PRICE, "  maybe 5000\n  if I pay cash ")
            .chooseUsage(Usage.DAILY)
            .chooseDepth(AssessmentDepth.FULL)
        assertTrue(buildIntakeMessage(page.toContext(), ReportLabels.ENGLISH).contains("About the price: maybe 5000 if I pay cash. It will get"))
    }

    @Test
    fun `row 58 - a shop's tablet has no buying card`() {
        val page = IntakePage.start(AssessmentLanguage.SWAHILI, needsUpholstery = true, fixedOwnership = Ownership.BUYING)
        assertFalse(IntakeTopic.OWNERSHIP in page.cards)
        assertTrue("buying is recorded, so the price is asked", IntakeTopic.PRICE in page.cards)
        assertEquals(Ownership.BUYING, page.chooseUpholstery(true).chooseUsage(Usage.DAILY).chooseDepth(AssessmentDepth.FULL).toContext().ownership)
    }
}

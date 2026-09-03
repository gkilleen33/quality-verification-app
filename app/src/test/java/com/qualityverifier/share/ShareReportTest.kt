package com.qualityverifier.share

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.buildShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareReportTest {

    @Test
    fun `a shared clean verdict does not claim more than was checked`() {
        // The share text is the highest-stakes place this word appears: it gets forwarded
        // to people who never saw the card, the summary or the couldn't-verify list, and
        // the first line is what they read.
        fun shareOf(verdict: Verdict) = buildShareText(
            itemType = ItemType.WOODEN_STOOL,
            verdict = verdict,
            date = "21 Aug",
            labels = ReportLabels.ENGLISH,
        )
        val clean = Verdict(levelId = "sound", headline = "Nothing wrong with it")

        val certain = shareOf(clean)
        assertTrue(certain, certain.contains("VERDICT: SOUND — Nothing wrong with it"))

        val hedged = shareOf(clean.copy(unverified = listOf("Underside of the seat")))
        assertTrue(hedged, hedged.contains("VERDICT: NO FAULTS FOUND — Nothing wrong with it"))
        assertFalse("must not still claim the piece is sound", hedged.contains("VERDICT: SOUND"))
        // And the gap itself still travels, under its own heading.
        assertTrue(hedged, hedged.contains("Not checked:"))
        assertTrue(hedged, hedged.contains("Underside of the seat"))
    }

    @Test
    fun `a full verdict renders as a flat readable message`() {
        val text = buildShareText(
            itemType = ItemType.WOODEN_STOOL,
            verdict = Verdict(
                levelId = "fair",
                headline = "Solid frame, one thing to sort out",
                summary = "Worth buying if he re-glues that joint first.",
                defects = listOf(
                    Defect(
                        title = "Gap at the rear stretcher joint",
                        severityId = "moderate",
                        whatISee = "The stretcher is not seated fully.",
                        whatItMeans = "It will work loose with daily use.",
                        whatToDo = "Ask for it to be re-glued before you pay.",
                    ),
                ),
                unverified = listOf("Whether the timber is seasoned."),
            ),
            date = "21 Aug",
            labels = ReportLabels.ENGLISH,
        )
        assertEquals(
            """
            KAGUA REPORT · Stool or bench · 21 Aug
            VERDICT: FAIR — Solid frame, one thing to sort out
            Worth buying if he re-glues that joint first.

            What to look at:
            1. Gap at the rear stretcher joint (moderate)
               It will work loose with daily use.
               Ask for it to be re-glued before you pay.

            Not checked:
            - Whether the timber is seasoned.

            Checked with Kagua — jua kabla ya kununua.
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a Swahili assessment shares with Swahili headings`() {
        val text = buildShareText(
            itemType = ItemType.WOODEN_STOOL,
            verdict = Verdict(
                levelId = "serious_concerns",
                language = "sw",
                headline = "Kiungo kilicholegea",
                defects = listOf(
                    Defect(
                        title = "Pengo kwenye kiungo",
                        severityId = "serious",
                        whatItMeans = "Kitalegea zaidi kila siku.",
                    ),
                ),
                unverified = listOf("Kama mbao ilikauka vizuri."),
            ),
            date = "21 Aug",
            labels = ReportLabels.forLanguage("sw"),
        )
        assertTrue(text.startsWith("RIPOTI YA KAGUA · Kigoda · 21 Aug"))
        assertTrue(text.contains("UAMUZI: MATATIZO MAKUBWA — Kiungo kilicholegea"))
        assertTrue(text.contains("Ya kuangalia:"))
        assertTrue(text.contains("(kubwa)"))
        assertTrue(text.contains("Hayakuthibitishwa:"))
        assertTrue(text.contains("Imekaguliwa na Kagua"))
        // Nothing English should survive into a Swahili report.
        assertFalse(text.contains("VERDICT"))
        assertFalse(text.contains("Not checked"))
    }

    @Test
    fun `a clean verdict has no empty sections`() {
        val text = buildShareText(
            itemType = ItemType.WOODEN_TABLE,
            verdict = Verdict(levelId = "sound", headline = "Nothing to worry about"),
            date = "3 Sep",
            labels = ReportLabels.ENGLISH,
        )
        assertTrue(text.startsWith("KAGUA REPORT · Table · 3 Sep"))
        assertTrue(text.contains("VERDICT: SOUND — Nothing to worry about"))
        assertFalse(text.contains("What to look at"))
        assertFalse(text.contains("Not checked"))
    }

    @Test
    fun `an unrecognised level still produces a sendable report`() {
        val text = buildShareText(
            itemType = ItemType.OTHER,
            verdict = Verdict(levelId = "haijulikani", summary = "Needs a closer look."),
            date = "1 Jan",
            labels = ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("VERDICT: ASSESSMENT"))
        assertTrue(text.contains("Needs a closer look."))
    }
}

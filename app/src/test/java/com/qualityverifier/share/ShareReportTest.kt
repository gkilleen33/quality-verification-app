package com.qualityverifier.share

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Verdict
import com.qualityverifier.ui.chat.buildShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareReportTest {

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
    fun `a clean verdict has no empty sections`() {
        val text = buildShareText(
            itemType = ItemType.WOODEN_TABLE,
            verdict = Verdict(levelId = "sound", headline = "Nothing to worry about"),
            date = "3 Sep",
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
        )
        assertTrue(text.contains("VERDICT: ASSESSMENT"))
        assertTrue(text.contains("Needs a closer look."))
    }
}

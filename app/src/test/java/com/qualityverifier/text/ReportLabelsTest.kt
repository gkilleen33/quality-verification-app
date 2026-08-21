package com.qualityverifier.text

import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLabelsTest {

    @Test
    fun `the assessment's language wins over the phone's`() {
        // The whole point: a Swahili assessment on an English handset gets Swahili
        // headings, because the findings beside them are in Swahili.
        assertSame(ReportLabels.SWAHILI, ReportLabels.forLanguage("sw", "en"))
        assertSame(ReportLabels.ENGLISH, ReportLabels.forLanguage("en", "sw"))
    }

    @Test
    fun `the phone's language is only a fallback`() {
        assertSame(ReportLabels.SWAHILI, ReportLabels.forLanguage(null, "sw"))
        assertSame(ReportLabels.SWAHILI, ReportLabels.forLanguage("", "sw"))
        assertSame(ReportLabels.ENGLISH, ReportLabels.forLanguage(null, null))
    }

    @Test
    fun `region tags and language names are accepted`() {
        // The model fills this field in, so it may write any of these forms.
        listOf("sw-KE", "SW", " swa ", "Swahili", "kiswahili").forEach { code ->
            assertSame("failed for $code", ReportLabels.SWAHILI, ReportLabels.forLanguage(code))
        }
        listOf("en-GB", "EN", "English").forEach { code ->
            assertSame("failed for $code", ReportLabels.ENGLISH, ReportLabels.forLanguage(code))
        }
    }

    @Test
    fun `an unrecognised language falls through rather than guessing`() {
        assertSame(ReportLabels.ENGLISH, ReportLabels.forLanguage("fr", "de"))
        // A language we do not have labels for must not silently pick the other one.
        assertSame(ReportLabels.SWAHILI, ReportLabels.forLanguage("fr", "sw"))
    }

    @Test
    fun `the item is named in the report's own language where a term exists`() {
        assertEquals("Kigoda", ReportLabels.SWAHILI.itemName(ItemType.WOODEN_STOOL))
        assertEquals("Stool or bench", ReportLabels.ENGLISH.itemName(ItemType.WOODEN_STOOL))
    }

    @Test
    fun `a category with no sourced Swahili term keeps its English name`() {
        // Falling back beats inventing a word — the same rule ItemType follows.
        assertEquals("Padded chair", ReportLabels.SWAHILI.itemName(ItemType.UPHOLSTERED_CHAIR))
        assertEquals("Something else", ReportLabels.SWAHILI.itemName(ItemType.OTHER))
    }

    @Test
    fun `every level and severity has a word in both languages`() {
        listOf(ReportLabels.ENGLISH, ReportLabels.SWAHILI).forEach { labels ->
            VerdictLevel.entries.forEach { level ->
                assertTrue(
                    "${labels.code} has no word for $level",
                    labels.level(level).isNotBlank(),
                )
            }
            // UNKNOWN is deliberately blank: an unrecognised severity should print
            // nothing rather than a guess.
            Severity.entries.filter { it != Severity.UNKNOWN }.forEach { severity ->
                assertTrue(
                    "${labels.code} has no word for $severity",
                    labels.severity(severity).isNotBlank(),
                )
            }
            assertEquals("", labels.severity(Severity.UNKNOWN))
        }
    }

    @Test
    fun `every area in the prompt schema is translated`() {
        // These are the values the master prompt tells the model to choose from.
        val areas = listOf(
            "structural", "level", "surface", "material", "upholstery", "hardware", "other",
        )
        areas.forEach { area ->
            assertEquals(
                "$area is not translated into Swahili",
                true,
                ReportLabels.SWAHILI.area(area) != area.uppercase(),
            )
        }
    }

    @Test
    fun `an area outside the schema labels itself rather than vanishing`() {
        assertEquals("FASTENINGS", ReportLabels.SWAHILI.area("fastenings"))
        assertEquals("STRUCTURAL", ReportLabels.ENGLISH.area("  Structural "))
    }

    @Test
    fun `the two label sets are complete against each other`() {
        // A heading added to one language and forgotten in the other is the failure
        // this catches: it would ship a card that is half translated.
        val en = ReportLabels.ENGLISH
        val sw = ReportLabels.SWAHILI
        listOf(
            en.verdictHeading to sw.verdictHeading,
            en.couldNotVerifyHeading to sw.couldNotVerifyHeading,
            en.whatISeeHeading to sw.whatISeeHeading,
            en.whatItMeansHeading to sw.whatItMeansHeading,
            en.whatToDoHeading to sw.whatToDoHeading,
            en.sayToSellerHeading to sw.sayToSellerHeading,
            en.askAboutThis to sw.askAboutThis,
            en.inProgress to sw.inProgress,
            en.shareHeader to sw.shareHeader,
            en.shareVerdict to sw.shareVerdict,
            en.shareWhatToLookAt to sw.shareWhatToLookAt,
            en.shareNotChecked to sw.shareNotChecked,
            en.shareSignOff to sw.shareSignOff,
        ).forEach { (english, swahili) ->
            assertTrue("a heading is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }
}

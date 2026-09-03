package com.qualityverifier.text

import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.Verdict
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
    fun `the neutral name does not answer the question being asked`() {
        // The heading over "does this chair have cushioning?" must not already say
        // "Wooden chair".
        assertEquals("Chair", ReportLabels.ENGLISH.neutralItemName(ItemType.WOODEN_CHAIR))
        assertEquals("Kiti", ReportLabels.SWAHILI.neutralItemName(ItemType.WOODEN_CHAIR))
        // Everything else is already unambiguous, so it reads the same either way.
        assertEquals(
            ReportLabels.ENGLISH.itemName(ItemType.WOODEN_TABLE),
            ReportLabels.ENGLISH.neutralItemName(ItemType.WOODEN_TABLE),
        )
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
    fun `counters substitute both numbers in either language`() {
        assertEquals("Shot 3 of 6", ReportLabels.ENGLISH.shotOf(3, 6))
        assertEquals("Picha 3 ya 6", ReportLabels.SWAHILI.shotOf(3, 6))
        assertEquals("Test 1 of 2", ReportLabels.ENGLISH.testOf(1, 2))
        assertEquals("6 of 6 photos taken", ReportLabels.ENGLISH.photosTaken(6, 6))
        assertEquals("Picha 6 kati ya 6 zimepigwa", ReportLabels.SWAHILI.photosTaken(6, 6))
    }

    @Test
    fun `the item is named in English and deliberately not in Swahili`() {
        // "Another table" needs the word for "another" to agree with the noun's class —
        // meza nyingine, but kiti kingine — so the Swahili wording talks about the
        // assessment instead of the furniture and needs no agreement. What must never
        // happen is the placeholder itself reaching a button.
        assertEquals("Check another Table", ReportLabels.ENGLISH.assessAnother("Table"))
        listOf(
            ReportLabels.SWAHILI.assessAnother("Meza"),
            ReportLabels.SWAHILI.compareWith("Meza"),
            ReportLabels.SWAHILI.compareIntro("Meza"),
        ).forEach { assertTrue("a placeholder leaked: $it", !it.contains("{item}")) }
    }

    @Test
    fun `a clean verdict with gaps is not called sound`() {
        // "Sound" reads as a claim that the furniture is good. What the assistant can
        // honestly say is that it found nothing in what it managed to check, and after a
        // rapid assessment that is two photographs.
        assertEquals(
            "No faults found",
            ReportLabels.ENGLISH.verdictWord(VerdictLevel.SOUND, anythingUnchecked = true),
        )
        assertEquals(
            "Sound",
            ReportLabels.ENGLISH.verdictWord(VerdictLevel.SOUND, anythingUnchecked = false),
        )
    }

    @Test
    fun `only a clean verdict is reworded`() {
        // fair and serious_concerns already assert that something is wrong, and something
        // being wrong is not in doubt just because the check was incomplete.
        listOf(VerdictLevel.FAIR, VerdictLevel.SERIOUS, VerdictLevel.UNKNOWN).forEach { level ->
            assertEquals(
                "$level should read the same either way",
                ReportLabels.ENGLISH.level(level),
                ReportLabels.ENGLISH.verdictWord(level, anythingUnchecked = true),
            )
        }
    }

    @Test
    fun `the wording is read off the unverified list, not guessed at`() {
        val clean = Verdict(levelId = "sound", headline = "Nothing wrong with it")
        val withGaps = clean.copy(unverified = listOf("Underside of the seat"))
        assertEquals("Sound", ReportLabels.ENGLISH.verdictWord(clean))
        assertEquals("No faults found", ReportLabels.ENGLISH.verdictWord(withGaps))
        // A defect makes the level fair or worse, so this pair is about the level rather
        // than the gaps: a verdict that found something is never reworded.
        val faulty = Verdict(
            levelId = "fair",
            defects = listOf(Defect(title = "Loose joint")),
            unverified = listOf("Underside of the seat"),
        )
        assertEquals(ReportLabels.ENGLISH.level(VerdictLevel.FAIR), ReportLabels.ENGLISH.verdictWord(faulty))
    }

    @Test
    fun `the reworded verdict exists in Swahili too`() {
        val withGaps = Verdict(levelId = "sound", unverified = listOf("Chini ya kiti"))
        assertEquals(ReportLabels.SWAHILI.noFaultsFound, ReportLabels.SWAHILI.verdictWord(withGaps))
        assertTrue(ReportLabels.SWAHILI.noFaultsFound.isNotBlank())
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
            en.askAboutThis to sw.askAboutThis,
            en.inProgress to sw.inProgress,
            en.noFaultsFound to sw.noFaultsFound,
            en.shareHeader to sw.shareHeader,
            en.shareVerdict to sw.shareVerdict,
            en.shareWhatToLookAt to sw.shareWhatToLookAt,
            en.shareNotChecked to sw.shareNotChecked,
            en.shareSignOff to sw.shareSignOff,
            en.photosHeading to sw.photosHeading,
            en.testsHeading to sw.testsHeading,
            en.startCamera to sw.startCamera,
            en.continueToTests to sw.continueToTests,
            en.sendForInspection to sw.sendForInspection,
            en.retake to sw.retake,
            en.inspecting to sw.inspecting,
            en.stagePreparing to sw.stagePreparing,
            en.stageSending to sw.stageSending,
            en.stageExamining to sw.stageExamining,
            en.seeVerdict to sw.seeVerdict,
            en.submissionTestsHeading to sw.submissionTestsHeading,
            en.notDone to sw.notDone,
            en.cannotDoThis to sw.cannotDoThis,
            en.notSure to sw.notSure,
            en.inThisInspection to sw.inThisInspection,
            en.intakeUpholsteryQuestion to sw.intakeUpholsteryQuestion,
            en.intakeUpholsteryYes to sw.intakeUpholsteryYes,
            en.intakeUpholsteryNo to sw.intakeUpholsteryNo,
            en.intakeOwnershipQuestion to sw.intakeOwnershipQuestion,
            en.intakeBuying to sw.intakeBuying,
            en.intakeAlreadyOwn to sw.intakeAlreadyOwn,
            en.intakePriceQuestion to sw.intakePriceQuestion,
            en.intakePriceSkip to sw.intakePriceSkip,
            en.intakeUsageQuestion to sw.intakeUsageQuestion,
            en.intakeUsageDaily to sw.intakeUsageDaily,
            en.intakeUsageOccasional to sw.intakeUsageOccasional,
            en.intakeUsageBusiness to sw.intakeUsageBusiness,
            en.intakeStart to sw.intakeStart,
            en.intakeNext to sw.intakeNext,
            en.intakeDepthQuestion to sw.intakeDepthQuestion,
            en.intakeDepthFull to sw.intakeDepthFull,
            en.intakeDepthFullDetail to sw.intakeDepthFullDetail,
            en.intakeDepthRapid to sw.intakeDepthRapid,
            en.intakeDepthRapidDetail to sw.intakeDepthRapidDetail,
            en.intakeSomethingElse to sw.intakeSomethingElse,
            en.openingShotInstruction to sw.openingShotInstruction,
            en.intakeSaysFull to sw.intakeSaysFull,
            en.intakeSaysRapid to sw.intakeSaysRapid,
            en.intakeSaysTakeOver to sw.intakeSaysTakeOver,
            en.intakeSaysBuying to sw.intakeSaysBuying,
            en.intakeSaysAlreadyOwn to sw.intakeSaysAlreadyOwn,
            en.intakeSaysPriceUnknown to sw.intakeSaysPriceUnknown,
            en.intakeSaysDaily to sw.intakeSaysDaily,
            en.intakeSaysOccasional to sw.intakeSaysOccasional,
            en.intakeSaysBusiness to sw.intakeSaysBusiness,
            en.intakeSaysUseLanguage to sw.intakeSaysUseLanguage,
            en.intakeCarriedHeading to sw.intakeCarriedHeading,
            en.intakeStartOver to sw.intakeStartOver,
            en.nextStepsHeading to sw.nextStepsHeading,
            en.assessDifferent to sw.assessDifferent,
            en.compareFoundHeading to sw.compareFoundHeading,
            en.compareAsk to sw.compareAsk,
            en.assessAnother("Table") to sw.assessAnother("Meza"),
            en.compareWith("Table") to sw.compareWith("Meza"),
            en.compareIntro("Table") to sw.compareIntro("Meza"),
        ).forEach { (english, swahili) ->
            assertTrue("a heading is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }
}

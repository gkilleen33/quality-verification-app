package com.qualityverifier.text

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.QualityRecord
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The problems of a reply, and Fundi Bora's defect count, in both shapes of reply.
 *
 * Today's replies write each problem twice, as a finding and again as a verdict defect.
 * Replies that write each problem once put the consequence on the finding and leave the
 * verdict's defects empty. Stored replies keep whichever shape they were written in, so
 * every reader has to agree on both. For the old shape the count moves only where a verdict
 * with no defects sits beside findings in the same reply: those findings now count.
 */
class ProblemsTest {

    // ------------------------------------------------------------- the readers

    @Test
    fun `a finding in the one-list shape carries its consequence`() {
        val finding = parseAssistantContent(ONE_LIST_EVALUATION).diagnosis!!.findings.first()
        assertEquals("The back rail does not sit tight against the leg. There is a gap you can see.", finding.whatISee)
        assertEquals("The joint will move each time somebody sits, and work loose within months.", finding.whatItMeans)
        assertEquals("Take the joint apart, fill the gap, glue it and hold it tight until it sets.", finding.whatToDo)
        // Its cause chain is untouched by the new fields.
        assertTrue(finding.manufacturingCause.startsWith("The end of the rail was cut"))
    }

    @Test
    fun `an older finding parses with the consequence blank`() {
        val finding = parseAssistantContent(TODAY_EVALUATION).diagnosis!!.findings.first()
        assertEquals("Gap at the back left joint", finding.title)
        assertEquals("", finding.whatISee)
        assertEquals("", finding.whatItMeans)
        assertEquals("", finding.whatToDo)
    }

    @Test
    fun `today's shape lists the verdict's defects, each once, with the dimension of its finding`() {
        val problems = problemsOf(parseAssistantContent(TODAY_EVALUATION))

        assertEquals(
            listOf("Gap at the back left joint", "Saw marks on the top"),
            problems.map { it.title },
        )
        // The consequence comes from the defect, as it always has.
        assertEquals("There is a gap at the back joint.", problems[0].whatISee)
        assertEquals(Severity.MODERATE, problems[0].severity)
        // The dimension from the finding with the same title.
        assertEquals(SkillDimension.JOINT_FIT, problems[0].skillDimension)
        assertEquals("finishing", problems[1].dimension)
    }

    @Test
    fun `a defect finds its finding's dimension whatever the case and spacing of the title`() {
        val verdict = Verdict(levelId = "fair", defects = listOf(Defect(title = "gap at the back joint ")))
        val diagnosis = Diagnosis(
            findings = listOf(
                Finding(title = "Gap at the back joint", dimension = "joint_fit"),
                // A second finding of the same title does not replace the first.
                Finding(title = "Gap at the back joint", dimension = "finishing"),
            ),
        )
        assertEquals("joint_fit", problemsOf(verdict, diagnosis).single().dimension)
    }

    @Test
    fun `the one-list shape lists the findings, each once, with their consequence`() {
        val content = parseAssistantContent(ONE_LIST_EVALUATION)
        assertTrue("the verdict lists no defects", content.verdict!!.defects.isEmpty())

        val problems = problemsOf(content)

        assertEquals(
            listOf("Gap at the back left joint", "Saw marks on the top"),
            problems.map { it.title },
        )
        assertEquals("joint_fit", problems[0].dimension)
        assertEquals("structural", problems[0].area)
        assertEquals("Sand the top again with a finer paper.", problems[1].whatToDo)
    }

    @Test
    fun `a Kagua verdict reads as its defects`() {
        val verdict = Verdict(
            levelId = "fair",
            defects = listOf(Defect(title = "Loose leg", severityId = "serious", whatISee = "It moves.")),
        )
        val problems = problemsOf(verdict, null)
        assertEquals(1, problems.size)
        assertEquals("Loose leg", problems.single().title)
        assertEquals("It moves.", problems.single().whatISee)
        assertEquals("", problems.single().dimension)
    }

    @Test
    fun `a reply with neither block has no problems`() {
        assertTrue(problemsOf(parseAssistantContent("Send me a photo of the underside.")).isEmpty())
    }

    // ---------------------------------------------------------------- the count

    @Test
    fun `today's shape counts the verdict's defects, as before`() {
        val reply = parseAssistantContent(TODAY_EVALUATION)
        assertEquals(reply.verdict!!.defects.size, fundiDefectCount(listOf(reply)))
        assertEquals(2, fundiDefectCount(listOf(reply)))
    }

    @Test
    fun `the one-list shape counts the findings`() {
        assertEquals(2, fundiDefectCount(listOf(parseAssistantContent(ONE_LIST_EVALUATION))))
    }

    @Test
    fun `a follow-up that resends only the verdict keeps the count of the diagnosis before it`() {
        val replies = listOf(VERDICT_ONLY, ONE_LIST_EVALUATION).map(::parseAssistantContent)
        assertEquals(2, fundiDefectCount(replies))
    }

    @Test
    fun `a follow-up that resends only the diagnosis changes the count of the verdict before it`() {
        val replies = listOf(DIAGNOSIS_ONE_FINDING, ONE_LIST_EVALUATION).map(::parseAssistantContent)
        assertEquals(1, fundiDefectCount(replies))
    }

    @Test
    fun `a follow-up diagnosis that found nothing brings the count to zero`() {
        // The parser drops a clean diagnosis from `diagnosis`, having nothing to draw. Read
        // past it, the count would come from the older diagnosis and bring back two
        // problems the model has since cleared.
        val clean = parseAssistantContent(CLEAN_DIAGNOSIS)
        assertNull(clean.diagnosis)
        assertTrue(clean.cleanDiagnosis)

        val replies = listOf(clean, parseAssistantContent(ONE_LIST_EVALUATION))
        assertEquals(0, fundiDefectCount(replies))
    }

    @Test
    fun `a session with a diagnosis and no verdict has no count`() {
        // Diagnose, a new problem, a fix check: partial checks stay out of the rates.
        val replies = listOf(DIAGNOSIS_ONE_FINDING, DIAGNOSIS_ONE_FINDING).map(::parseAssistantContent)
        assertNull(fundiDefectCount(replies))
    }

    @Test
    fun `a clean verdict with no diagnosis counts zero, not null`() {
        val reply = parseAssistantContent(
            "```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"Nothing wrong\"}\n```",
        )
        assertEquals(0, fundiDefectCount(listOf(reply)))
    }

    @Test
    fun `a sound verdict in today's shape counts the cosmetic finding beside it`() {
        // The verdict lists no defects and the finding carries no consequence, but a
        // cosmetic finding counts against first time clean (Nathan, 11 November 2026).
        val reply = parseAssistantContent(TODAY_SOUND)
        assertEquals(1, fundiDefectCount(listOf(reply)))
    }

    @Test
    fun `a sound verdict in today's shape lists the cosmetic finding beside it`() {
        // The phone's verdict card and the admin portal's both draw it.
        val problems = problemsOf(parseAssistantContent(TODAY_SOUND))
        assertEquals(listOf("Saw marks on the top"), problems.map { it.title })
        assertEquals("finishing", problems.single().dimension)
    }

    @Test
    fun `a one-list reply whose findings leave out the consequence counts its findings`() {
        // All three consequence fields missing does not make the reply clean.
        val reply = parseAssistantContent(ONE_LIST_NO_CONSEQUENCE)
        assertTrue("the verdict lists no defects", reply.verdict!!.defects.isEmpty())
        assertEquals(2, fundiDefectCount(listOf(reply)))
        assertEquals(
            listOf("Gap at the back left joint", "Saw marks on the top"),
            problemsOf(reply).map { it.title },
        )
    }

    @Test
    fun `a sound verdict-only follow-up in today's shape counts zero, not the old defects`() {
        // The newest diagnosis is in today's shape and older than the newest verdict, so its
        // findings were cleared by that verdict and the verdict's defects decide.
        val sound = "```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"All fixed\",\"defects\":[]}\n```"
        val replies = listOf(sound, TODAY_EVALUATION).map(::parseAssistantContent)
        assertEquals(0, fundiDefectCount(replies))
    }

    @Test
    fun `a sound verdict-only follow-up counts zero after a sound verdict with a cosmetic finding`() {
        // The cosmetic finding is older than the newest verdict, so it is not recounted.
        val sound = "```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"All fixed\",\"defects\":[]}\n```"
        val replies = listOf(sound, TODAY_SOUND).map(::parseAssistantContent)
        assertEquals(0, fundiDefectCount(replies))
    }

    @Test
    fun `a diagnosis-only follow-up in today's shape counts its findings beside an older sound verdict`() {
        // The newest diagnosis is newer than the newest verdict, so its findings count.
        val diagnosis =
            "```fb-diagnosis\n{\"findings\":[{\"title\":\"Saw marks on the top\",\"severity\":\"cosmetic\"}]}\n```"
        val sound = "```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"Good work\",\"defects\":[]}\n```"
        val replies = listOf(diagnosis, sound).map(::parseAssistantContent)
        assertEquals(1, fundiDefectCount(replies))
    }

    @Test
    fun `a verdict-only follow-up in today's shape keeps its own defects`() {
        val replies = listOf(TODAY_VERDICT_ONE_DEFECT, TODAY_EVALUATION).map(::parseAssistantContent)
        assertEquals(1, fundiDefectCount(replies))
    }

    @Test
    fun `a diagnosis-only reply in today's shape lists its findings`() {
        // No verdict for them to be repeated in, so the findings are the only list.
        val reply = parseAssistantContent(
            "```fb-diagnosis\n{\"findings\":[{\"title\":\"Saw marks on the top\",\"severity\":\"cosmetic\"}]}\n```",
        )
        assertEquals(listOf("Saw marks on the top"), problemsOf(reply).map { it.title })
    }

    // -------------------------------------------- a diagnosis that will not decode

    @Test
    fun `a diagnosis block that will not decode is marked, and one that decodes is not`() {
        val unreadable = parseAssistantContent(UNREADABLE_DIAGNOSIS)
        assertNull(unreadable.diagnosis)
        assertTrue(unreadable.diagnosisUnreadable)
        assertTrue(unreadable.sentDiagnosis)

        val readable = parseAssistantContent(ONE_LIST_EVALUATION)
        assertTrue(!readable.diagnosisUnreadable)
        val clean = parseAssistantContent(CLEAN_DIAGNOSIS)
        assertTrue(!clean.diagnosisUnreadable)
        assertTrue(clean.sentDiagnosis)
        val none = parseAssistantContent(VERDICT_ONLY)
        assertTrue(!none.diagnosisUnreadable)
        assertTrue(!none.sentDiagnosis)
    }

    @Test
    fun `an unfenced diagnosis that will not decode is marked too`() {
        val reply = parseAssistantContent("fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\",}}")
        assertTrue(reply.diagnosisUnreadable)
    }

    @Test
    fun `a diagnosis that will not decode beside a verdict with no defects has no count`() {
        // Not zero: that would record a piece as clean whose problems nobody could read.
        val reply = parseAssistantContent(UNREADABLE_DIAGNOSIS + "\n\n" + VERDICT_ONLY)
        assertEquals("fair", reply.verdict!!.levelId)
        assertNull(fundiDefectCount(listOf(reply)))
    }

    @Test
    fun `a diagnosis that will not decode is not read past to an older one`() {
        // Beside the verdict...
        val together = parseAssistantContent(UNREADABLE_DIAGNOSIS + "\n\n" + VERDICT_ONLY)
        assertNull(fundiDefectCount(listOf(together, parseAssistantContent(ONE_LIST_EVALUATION))))
        // ...or on its own after one.
        val alone = listOf(UNREADABLE_DIAGNOSIS, ONE_LIST_EVALUATION).map(::parseAssistantContent)
        assertNull(fundiDefectCount(alone))
    }

    @Test
    fun `a diagnosis that will not decode leaves a verdict's own defects as the count`() {
        val reply = parseAssistantContent(UNREADABLE_DIAGNOSIS + "\n\n" + TODAY_VERDICT_ONE_DEFECT)
        assertEquals(1, fundiDefectCount(listOf(reply)))
    }

    @Test
    fun `a readable diagnosis after one that would not decode counts as usual`() {
        val replies = listOf(DIAGNOSIS_ONE_FINDING, UNREADABLE_DIAGNOSIS, ONE_LIST_EVALUATION)
            .map(::parseAssistantContent)
        assertEquals(1, fundiDefectCount(replies))
    }

    @Test
    fun `the first-time-clean and repair rates are the same in either shape`() {
        // A piece with problems the first time and a clean re-assessment after. Each
        // re-assessment is a session of its own, so each count comes from its own replies.
        val cleanReassessment = parseAssistantContent(
            """
            ```fb-diagnosis
            {"findings":[]}
            ```

            ```qv-verdict
            {"verdict":"sound","headline":"The joint is closed now","defects":[]}
            ```
            """.trimIndent(),
        )
        fun pieceFrom(first: String) = QualityRecord.Piece(
            id = "piece",
            defectCounts = listOf(
                fundiDefectCount(listOf(parseAssistantContent(first))),
                fundiDefectCount(listOf(cleanReassessment)),
            ),
        )
        val today = listOf(pieceFrom(TODAY_EVALUATION))
        val oneList = listOf(pieceFrom(ONE_LIST_EVALUATION))

        assertEquals(QualityRecord.Rate(count = 0, outOf = 1), QualityRecord.firstTimeClean(today))
        assertEquals(QualityRecord.firstTimeClean(today), QualityRecord.firstTimeClean(oneList))
        assertEquals(QualityRecord.Rate(count = 1, outOf = 1), QualityRecord.repairsConfirmed(today))
        assertEquals(QualityRecord.repairsConfirmed(today), QualityRecord.repairsConfirmed(oneList))
    }

    private companion object {
        /** A full evaluation in today's shape: each problem as a finding and as a defect. */
        val TODAY_EVALUATION = """
            Two things to look at on this table.

            ```fb-diagnosis
            {
              "language": "en",
              "findings": [
                {
                  "title": "Gap at the back left joint",
                  "area": "structural",
                  "severity": "moderate",
                  "dimension": "joint_fit",
                  "what_happened": "The rail does not sit flat against the leg.",
                  "manufacturing_cause": "The end of the rail was cut at a slight angle.",
                  "root_habit": "The cutting line is drawn on one side only."
                },
                {
                  "title": "Saw marks on the top",
                  "area": "surface",
                  "severity": "cosmetic",
                  "dimension": "finishing"
                }
              ]
            }
            ```

            ```qv-verdict
            {
              "verdict": "fair",
              "language": "en",
              "headline": "Strong table, one joint to close",
              "defects": [
                {
                  "title": "Gap at the back left joint",
                  "area": "structural",
                  "severity": "moderate",
                  "what_i_see": "There is a gap at the back joint.",
                  "what_it_means": "It will loosen.",
                  "what_to_do": "Re-glue and clamp it."
                },
                {
                  "title": "Saw marks on the top",
                  "area": "surface",
                  "severity": "cosmetic",
                  "what_i_see": "Lines across the top.",
                  "what_it_means": "Only looks.",
                  "what_to_do": "Sand it again."
                }
              ]
            }
            ```
        """.trimIndent()

        /** The same evaluation written once: findings carry the consequence; no defects. */
        val ONE_LIST_EVALUATION = """
            Two things to look at on this table.

            ```fb-diagnosis
            {
              "language": "en",
              "findings": [
                {
                  "title": "Gap at the back left joint",
                  "area": "structural",
                  "severity": "moderate",
                  "dimension": "joint_fit",
                  "measured_value": 3,
                  "measured_unit": "mm",
                  "what_happened": "The end of the rail does not sit flat against the leg. There is a gap of about 3mm at the top of the joint, closing towards the bottom. That is an estimate from the photo.",
                  "manufacturing_cause": "The end of the rail was cut at a slight angle instead of straight across, so it touches the leg at the bottom first.",
                  "root_habit": "The cutting line is drawn on one side of the rail and the other three sides are cut by eye.",
                  "what_i_see": "The back rail does not sit tight against the leg. There is a gap you can see.",
                  "what_it_means": "The joint will move each time somebody sits, and work loose within months.",
                  "what_to_do": "Take the joint apart, fill the gap, glue it and hold it tight until it sets."
                },
                {
                  "title": "Saw marks on the top",
                  "area": "surface",
                  "severity": "cosmetic",
                  "dimension": "finishing",
                  "what_i_see": "Lines across the top where the saw ran.",
                  "what_it_means": "Only the look of the piece.",
                  "what_to_do": "Sand the top again with a finer paper."
                }
              ]
            }
            ```

            ```qv-verdict
            {
              "verdict": "fair",
              "language": "en",
              "headline": "Strong table, one joint to close",
              "summary": "Close the back joint before it goes to the customer."
            }
            ```
        """.trimIndent()

        /** A follow-up that resends the verdict and not the diagnosis. */
        val VERDICT_ONLY = """
            ```qv-verdict
            {"verdict":"fair","language":"en","headline":"Still one joint to close"}
            ```
        """.trimIndent()

        /** A follow-up that resends the diagnosis, now with one finding, and no verdict. */
        val DIAGNOSIS_ONE_FINDING = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back left joint","area":"structural","severity":"moderate","dimension":"joint_fit","what_i_see":"A gap at the back joint."}]}
            ```
        """.trimIndent()

        /** Today's shape for a piece the model calls sound: one cosmetic finding, no defects. */
        val TODAY_SOUND = """
            ```fb-diagnosis
            {"findings":[{"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"sound","headline":"Good work","defects":[]}
            ```
        """.trimIndent()

        /** The one-list shape with every consequence field left out: findings, no defects. */
        val ONE_LIST_NO_CONSEQUENCE = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back left joint","area":"structural","severity":"moderate","dimension":"joint_fit"},{"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"Strong table, one joint to close","summary":"Close the back joint first."}
            ```
        """.trimIndent()

        /** A follow-up in today's shape that resends the verdict, now with one defect. */
        val TODAY_VERDICT_ONE_DEFECT = """
            ```qv-verdict
            {"verdict":"fair","headline":"One joint left","defects":[{"title":"Gap at the back left joint","severity":"moderate","what_i_see":"A gap."}]}
            ```
        """.trimIndent()

        /** A diagnosis block cut off mid-object, which will not decode. */
        val UNREADABLE_DIAGNOSIS = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back left joint","severity":"moderate","what_i_see":"A gap
            ```
        """.trimIndent()

        /** A follow-up diagnosis that found nothing after all. */
        val CLEAN_DIAGNOSIS = """
            Looking again, those marks are the grain, not the saw.

            ```fb-diagnosis
            {"findings":[],"one_check":""}
            ```
        """.trimIndent()
    }
}

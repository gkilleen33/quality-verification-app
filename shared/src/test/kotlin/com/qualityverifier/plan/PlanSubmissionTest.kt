package com.qualityverifier.plan

import com.qualityverifier.domain.AssessmentPlan
import com.qualityverifier.domain.PlannedShot
import com.qualityverifier.domain.PlannedTest
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildSubmissionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSubmissionTest {

    private val plan = AssessmentPlan(
        photos = listOf(
            PlannedShot(title = "Full view, front"),
            PlannedShot(title = "Underside"),
        ),
        tests = listOf(
            PlannedTest(title = "The wobble test"),
            PlannedTest(title = "The bottle-top roll"),
        ),
    )

    @Test
    fun `a complete run lists every photo and every answer`() {
        val text = buildSubmissionText(
            plan = plan,
            shots = mapOf(0 to "/a.jpg", 1 to "/b.jpg"),
            answers = mapOf(0 to "A little give", 1 to "Stays put"),
            labels = ReportLabels.ENGLISH,
        )
        assertEquals(
            """
            2 of 2 photos taken
            - Full view, front
            - Underside

            Test results
            - The wobble test: A little give
            - The bottle-top roll: Stays put
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a skipped step says so instead of disappearing`() {
        // The failure this guards: a heavy wardrobe nobody could tip over must not read
        // as a wardrobe with a clean underside.
        val text = buildSubmissionText(
            plan = plan,
            shots = mapOf(0 to "/a.jpg", 1 to null),
            answers = mapOf(0 to "A little give", 1 to null),
            labels = ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("1 of 2 photos taken"))
        assertTrue(text.contains("- Underside: not done"))
        assertTrue(text.contains("- The bottle-top roll: not done"))
    }

    @Test
    fun `unsure and could-not-do are reported as different things`() {
        // Both are checks that did not happen, and neither is a failure -- but they are
        // not the same. "Not sure" means they tried; "not done" means they did not, often
        // because the piece was too heavy to tip alone. The assistant is told to treat
        // both as unverified, and it can only do that if it can see which is which.
        val text = buildSubmissionText(
            plan = plan,
            shots = mapOf(0 to "/a.jpg", 1 to "/b.jpg"),
            answers = mapOf(0 to ReportLabels.ENGLISH.notSure, 1 to null),
            labels = ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("- The wobble test: I'm not sure"))
        assertTrue(text.contains("- The bottle-top roll: not done"))
    }

    @Test
    fun `unsure travels in the assessment's language`() {
        val text = buildSubmissionText(
            plan = plan,
            shots = emptyMap(),
            answers = mapOf(0 to ReportLabels.SWAHILI.notSure, 1 to null),
            labels = ReportLabels.SWAHILI,
        )
        assertTrue(text.contains("Sina uhakika"))
        assertTrue(text.contains("haikufanyika"))
    }

    @Test
    fun `a photos-only follow-up plan has no test section`() {
        val text = buildSubmissionText(
            plan = AssessmentPlan(photos = listOf(PlannedShot(title = "Under the rail"))),
            shots = mapOf(0 to "/c.jpg"),
            answers = emptyMap(),
            labels = ReportLabels.ENGLISH,
        )
        assertEquals("1 of 1 photos taken\n- Under the rail", text)
    }

    @Test
    fun `a tests-only plan has no photo section`() {
        val text = buildSubmissionText(
            plan = AssessmentPlan(tests = listOf(PlannedTest(title = "Rock it"))),
            shots = emptyMap(),
            answers = mapOf(0 to "Solid"),
            labels = ReportLabels.ENGLISH,
        )
        assertEquals("Test results\n- Rock it: Solid", text)
    }

    @Test
    fun `the submission is written in the plan's language`() {
        val text = buildSubmissionText(
            plan = plan,
            shots = mapOf(0 to "/a.jpg", 1 to null),
            answers = mapOf(0 to "Kuna msogeo kidogo", 1 to null),
            labels = ReportLabels.SWAHILI,
        )
        assertTrue(text.startsWith("Picha 1 kati ya 2 zimepigwa"))
        assertTrue(text.contains("Majibu ya majaribio"))
        assertTrue(text.contains("haikufanyika"))
        assertTrue(text.contains("Kuna msogeo kidogo"))
    }

    @Test
    fun `an untitled step still identifies itself by number`() {
        val text = buildSubmissionText(
            plan = AssessmentPlan(photos = listOf(PlannedShot(), PlannedShot())),
            shots = mapOf(0 to "/a.jpg", 1 to null),
            answers = emptyMap(),
            labels = ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("- Shot 1 of 2"))
        assertTrue(text.contains("- Shot 2 of 2: not done"))
    }
}

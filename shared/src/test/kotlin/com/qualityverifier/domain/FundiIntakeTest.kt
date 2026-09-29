package com.qualityverifier.domain

import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.buildFundiOpeningMessage
import com.qualityverifier.text.priorIssues
import com.qualityverifier.text.decodeIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intake: what the maker wants, and what that makes the assessment worth.
 */
class FundiIntakeTest {

    private val labels = FundiLabels.ENGLISH

    // Only a full assessment issues a verdict, and only a verdict counts towards the two
    // rates. A targeted check of one joint that came back clean says nothing about the rest
    // of the piece; recording it as a clean piece would inflate the number a maker is judged
    // on.
    @Test
    fun `only the two full evaluations run the whole-piece assessment`() {
        assertEquals(
            setOf(FundiPurpose.EVALUATE, FundiPurpose.REEVALUATE),
            FundiPurpose.entries.filter { it.fullAssessment }.toSet(),
        )
    }

    @Test
    fun `a new piece and a returning one are offered different questions`() {
        assertEquals(
            listOf(
                FundiPurpose.EVALUATE, FundiPurpose.DIAGNOSE,
                FundiPurpose.LEARN, FundiPurpose.OTHER,
            ),
            FundiPurpose.forNewPiece,
        )
        assertEquals(
            listOf(FundiPurpose.REEVALUATE, FundiPurpose.VERIFY_FIX, FundiPurpose.NEW_ISSUE),
            FundiPurpose.forReturningPiece,
        )
    }

    @Test
    fun `every purpose has a name, a photo instruction, and a prompt if it asks for one`() {
        FundiPurpose.entries.forEach { purpose ->
            assertTrue("no name for $purpose", labels.purposeNames.containsKey(purpose))
            assertTrue("no photo instruction for $purpose", labels.photoInstructions.containsKey(purpose))
            if (purpose.needsDetails || purpose == FundiPurpose.VERIFY_FIX) {
                assertTrue("no details prompt for $purpose", labels.detailsPrompts.containsKey(purpose))
            }
        }
    }

    @Test
    fun `an intake that asks for details is not complete without them`() {
        assertFalse(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.DIAGNOSE).isComplete)
        assertFalse(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.DIAGNOSE, details = "   ").isComplete
        )
        assertTrue(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.DIAGNOSE, details = "loose leg")
                .isComplete
        )
        assertTrue(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE).isComplete)
    }

    @Test
    fun `checking a fix needs either a picked finding or a description`() {
        val bare = FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.VERIFY_FIX)
        assertFalse(bare.isComplete)
        assertTrue(bare.copy(issue = PriorIssue("Joint gap")).isComplete)
        assertTrue(bare.copy(details = "the wobbly leg").isComplete)
    }

    @Test
    fun `the stored code round-trips language and purpose`() {
        val intake = FundiIntake(AssessmentLanguage.SWAHILI, FundiPurpose.VERIFY_FIX)
        assertEquals(
            AssessmentLanguage.SWAHILI to FundiPurpose.VERIFY_FIX,
            FundiIntake.decode(FundiIntake.encode(intake)),
        )
    }

    // The two apps share one column. Neither may misread the other's code.
    @Test
    fun `the two apps cannot misread each other's intake codes`() {
        val fundi = FundiIntake.encode(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE))
        assertNull("Kagua must reject a Fundi Bora code", decodeIntake(fundi))
        assertNull("and this must reject Kagua's", FundiIntake.decode("en-buying-daily-full"))
    }

    // Purpose first: it changes what the model is being asked to do, and the rest is
    // context for that.
    @Test
    fun `the opening turn leads with the purpose and ends with the chosen language`() {
        val text = buildFundiOpeningMessage(
            FundiIntake(AssessmentLanguage.SWAHILI, FundiPurpose.DIAGNOSE, details = "loose leg"),
            FundiProfile(tools = listOf(OwnedTool(ToolKind.CLAMPS, ToolOwnership.NONE))),
            labels,
        )

        assertTrue(text, text.startsWith("I want to diagnose and fix a specific problem: loose leg"))
        assertTrue(text, text.contains("Tools I do not have: clamps."))
        // The maker's choice, not the default of the labels the app is drawn in — and
        // only once, or the model has two instructions and a coin to toss.
        assertTrue(text, text.trimEnd().endsWith("Tafadhali nijibu kwa Kiswahili."))
        assertFalse(text, text.contains("Please answer me in English."))
    }

    @Test
    fun `checking a fix quotes the finding as it was recorded`() {
        val text = buildFundiOpeningMessage(
            FundiIntake(
                AssessmentLanguage.ENGLISH,
                FundiPurpose.VERIFY_FIX,
                issue = PriorIssue("Gapping joint", "A 3mm gap at the back left shoulder"),
            ),
            FundiProfile(),
            labels,
        )

        assertTrue(text, text.contains("Gapping joint: A 3mm gap at the back left shoulder"))
    }

    @Test
    fun `earlier findings come from diagnoses and verdicts, newest first, each once`() {
        val newest = """
            ```fb-diagnosis
            {"findings": [{"title": "Gapping joint", "what_happened": "Now 1mm"}]}
            ```
        """.trimIndent()
        val older = """
            ```qv-verdict
            {"level": "fair", "headline": "h",
             "defects": [{"title": "gapping joint", "what_i_see": "3mm"},
                         {"title": "Rough edge", "what_i_see": "splinters"}]}
            ```
        """.trimIndent()

        val issues = priorIssues(listOf(newest, older))

        assertEquals(listOf("Gapping joint", "Rough edge"), issues.map { it.title })
        assertEquals("the most recent wording wins", "Now 1mm", issues.first().whatHappened)
    }

    @Test
    fun `a conversation with no findings offers nothing to pick`() {
        assertTrue(priorIssues(listOf("Just some advice.")).isEmpty())
    }
}

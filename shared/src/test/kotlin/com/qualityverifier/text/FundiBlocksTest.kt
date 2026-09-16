package com.qualityverifier.text

import com.qualityverifier.domain.MeasurementUnit
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fundi Bora's two blocks, through the same parser the buyer-facing blocks use.
 *
 * The turn is the same shape for both audiences — fences in a message — so the parser is
 * shared. What these tests pin is that adding two tags did not change what the existing
 * ones do, and that the new ones follow the same rules: a block that will not parse is
 * dropped rather than printed, and the prose is suppressed when the cards can speak.
 */
class FundiBlocksTest {

    private val diagnosisTurn = """
        The rear left joint has opened up, and I think I know why.

        ```fb-diagnosis
        {
          "language": "en",
          "one_check": "Was the shoulder cut freehand, or against a fence?",
          "findings": [
            {
              "title": "Gap at the rear left tenon shoulder",
              "area": "structural",
              "severity": "moderate",
              "dimension": "joint_fit",
              "measured_value": 3,
              "measured_unit": "mm",
              "what_happened": "The shoulder does not meet the leg. About 3mm at the top.",
              "manufacturing_cause": "The shoulder was cut out of square, so the tenon seats before the shoulder lands.",
              "root_habit": "The shoulder line is marked on one face and then cut by eye on the other three."
            },
            {
              "title": "Two saw marks left on the apron",
              "area": "surface",
              "severity": "cosmetic"
            }
          ]
        }
        ```
    """.trimIndent()

    private val fixPlanTurn = """
        Here is what I would do.

        ```fb-fixplan
        {
          "language": "en",
          "fix_now": {
            "summary": "Open the joint, shim the shoulder, re-glue and cramp.",
            "minutes": 40,
            "cost_kes": 0,
            "steps": [
              "Work the joint apart.",
              "Plane a shim from a cypress offcut.",
              "Glue the shim to the shoulder, not the tenon.",
              "Pull it up with a rope tourniquet."
            ]
          },
          "prevent": {
            "summary": "Mark the shoulder all the way round before any cut.",
            "steps": ["Square the line onto all four faces from one reference face."]
          },
          "drill": { "summary": "Ten shoulder cuts on offcuts.", "minutes": 10 },
          "tool_to_buy": {
            "kind": "marking_gauge",
            "name": "Marking gauge",
            "price_kes_low": 600,
            "price_kes_high": 900
          }
        }
        ```
    """.trimIndent()

    // ------------------------------------------------------------- diagnosis

    @Test
    fun `a diagnosis carries the cause, not just the defect`() {
        val diagnosis = parseAssistantContent(diagnosisTurn).diagnosis
        assertNotNull(diagnosis)

        val primary = diagnosis!!.primary!!
        assertEquals("Gap at the rear left tenon shoulder", primary.title)
        assertEquals(Severity.MODERATE, primary.severity)
        // The three fields that make this a diagnosis rather than a grade.
        assertTrue(primary.whatHappened.contains("does not meet the leg"))
        assertTrue(primary.manufacturingCause.contains("cut out of square"))
        assertTrue(primary.rootHabit.contains("marked on one face"))
        assertTrue(primary.isCoached)
    }

    @Test
    fun `only the first finding is coached, and the rest are still recorded`() {
        // A fundi handed six habits to change changes none of them. The others exist so
        // the piece's record is complete, which is what the two rates are computed from.
        val diagnosis = parseAssistantContent(diagnosisTurn).diagnosis!!

        assertEquals(2, diagnosis.defectCount)
        assertTrue(diagnosis.findings[0].isCoached)
        assertFalse("the second finding must not carry a habit", diagnosis.findings[1].isCoached)
        assertEquals("Two saw marks left on the apron", diagnosis.findings[1].title)
    }

    @Test
    fun `one check is carried so the app can wait for the answer`() {
        val diagnosis = parseAssistantContent(diagnosisTurn).diagnosis!!
        assertTrue(diagnosis.awaitingCheck)
        assertTrue(diagnosis.oneCheck.contains("freehand"))
    }

    @Test
    fun `a measurement becomes a storable observation, with its unit`() {
        val primary = parseAssistantContent(diagnosisTurn).diagnosis!!.primary!!
        val observation = primary.observation

        assertNotNull(observation)
        assertEquals(SkillDimension.JOINT_FIT, observation!!.dimension)
        assertEquals(3.0, observation.value!!, 1e-9)
        assertEquals(MeasurementUnit.MILLIMETRES, observation.unit)
        // The level is deliberately not taken from the model: mapping millimetres to "L2"
        // is a rubric decision, and the point of storing measurements is that it stays one.
        assertNull(observation.level)
    }

    @Test
    fun `a value without its unit is not an observation`() {
        // Worse than no measurement, because it looks precise. Matches the CHECK on
        // fundi_observations, which refuses the pair half-populated.
        val turn = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap","dimension":"joint_fit","measured_value":3}]}
            ```
        """.trimIndent()

        val primary = parseAssistantContent(turn).diagnosis!!.primary!!
        assertNull(primary.observation)
    }

    @Test
    fun `a clean piece is a diagnosis with no findings, not a failure`() {
        // The outcome the whole app exists to produce. It must not be mistaken for a
        // block that failed to parse.
        val turn = """
            Nothing wrong with this one.

            ```fb-diagnosis
            {"language":"en","findings":[],"one_check":""}
            ```
        """.trimIndent()

        val content = parseAssistantContent(turn)
        // Nothing to render, so the prose stands — which is the right behaviour, because
        // "nothing wrong with this one" is the whole message.
        assertNull(content.diagnosis)
        assertEquals("Nothing wrong with this one.", content.displayProse)
    }

    @Test
    fun `a diagnosis that will not parse is dropped, never printed`() {
        // Same rule as the verdict: raw JSON in front of somebody with glue drying is
        // worse than the prose, which says the same thing.
        val turn = """
            The rear joint has opened up.

            ```fb-diagnosis
            {"findings": [{"title": "Gap at the
            ```
        """.trimIndent()

        val content = parseAssistantContent(turn)
        assertNull(content.diagnosis)
        assertFalse("the JSON leaked into the prose", content.prose.contains("findings"))
        assertEquals("The rear joint has opened up.", content.displayProse)
    }

    @Test
    fun `a diagnosis still leaves prose to show, because nothing draws it yet`() {
        // The defect this exists to prevent. displayProse used to blank for a diagnosis,
        // by analogy with the verdict — but ChatScreen draws cards only for a verdict and
        // falls back to prose for everything else, so the result was a turn with no prose
        // and no cards: a silent gap where the answer should be.
        //
        // Reachable the moment an account gets a fundi_workshops row, which is how anyone
        // would test the coaching prompt before the producer app exists.
        val content = parseAssistantContent(diagnosisTurn)

        assertNotNull(content.diagnosis)
        assertTrue(
            "a build with no diagnosis renderer must still have something to show",
            content.displayProse.isNotBlank(),
        )
        assertTrue(content.displayProse, content.displayProse.contains("rear left joint"))
    }

    @Test
    fun `no parsed block leaves a turn with nothing at all to render`() {
        // The general shape of the bug above: for every block, a caller that cannot draw
        // it must still be able to show the reader something. The verdict is the sole
        // exception, and only because ChatScreen returns early and draws cards for it
        // before looking at the prose.
        val prose = "Here is what I found."
        val bodies = mapOf(
            "fb-diagnosis" to """{"findings":[{"title":"Gap","what_happened":"A gap."}]}""",
            "fb-fixplan" to """{"fix_now":{"summary":"Re-glue it.","steps":["Open it."]}}""",
            "qv-plan" to """{"summary":"Six photos.","photos":[{"title":"Whole piece"}]}""",
        )
        bodies.forEach { (tag, body) ->
            val content = parseAssistantContent("$prose\n\n```$tag\n$body\n```")
            assertTrue(
                "$tag leaves nothing to render",
                content.displayProse.isNotBlank(),
            )
        }
    }

    // -------------------------------------------------------------- fix plan

    @Test
    fun `a fix plan carries three horizons in order`() {
        val plan = parseAssistantContent(fixPlanTurn).fixPlan
        assertNotNull(plan)
        assertTrue(plan!!.isRunnable)

        assertEquals(3, plan.stages.size)
        assertEquals(40, plan.fixNow!!.minutes)
        // Zero is a real answer and worth keeping: "this costs you nothing" is the
        // sentence that gets the fix done.
        assertEquals(0, plan.fixNow!!.costKes)
        assertEquals(4, plan.fixNow!!.steps.size)
        assertTrue(plan.prevent!!.summary.contains("all the way round"))
        assertEquals(10, plan.drill!!.minutes)
    }

    @Test
    fun `a tool suggestion keeps its range rather than a single figure`() {
        val tool = parseAssistantContent(fixPlanTurn).fixPlan!!.toolToBuy!!
        assertEquals(ToolKind.MARKING_GAUGE, tool.toolKind)
        assertEquals("KSh 600–900", tool.priceRange)
    }

    @Test
    fun `a half-written price range is no range at all`() {
        // A fundi walks into a hardware shop with whatever number this produces, so a
        // nonsensical one has to read as absent.
        val cases = listOf(
            """{"fix_now":{"steps":["a"]},"tool_to_buy":{"name":"Gauge","price_kes_low":600}}""",
            """{"fix_now":{"steps":["a"]},"tool_to_buy":{"name":"Gauge","price_kes_low":900,"price_kes_high":600}}""",
            """{"fix_now":{"steps":["a"]},"tool_to_buy":{"name":"Gauge","price_kes_low":0,"price_kes_high":900}}""",
        )
        cases.forEach { body ->
            val plan = parseAssistantContent("```fb-fixplan\n$body\n```").fixPlan
            assertNull("range should be absent for $body", plan!!.toolToBuy!!.priceRange)
        }
    }

    @Test
    fun `a plan with nothing to do now is not a plan`() {
        // Prevent and drill alone tell somebody holding a defective piece to do nothing
        // about it.
        val turn = """
            ```fb-fixplan
            {"prevent":{"summary":"Mark all round."},"drill":{"summary":"Ten cuts."}}
            ```
        """.trimIndent()

        assertNull(parseAssistantContent(turn).fixPlan)
    }

    @Test
    fun `an unrecognised tool kind renders without one rather than failing`() {
        val turn = """
            ```fb-fixplan
            {"fix_now":{"steps":["a"]},"tool_to_buy":{"kind":"biscuit_joiner","name":"Biscuit joiner"}}
            ```
        """.trimIndent()

        val tool = parseAssistantContent(turn).fixPlan!!.toolToBuy!!
        assertNull("an invented kind must not become a ToolKind", tool.toolKind)
        // Still worth drawing: the name is what the fundi reads.
        assertTrue(tool.isRenderable)
    }

    // ----------------------------------------------------- both, and neither

    @Test
    fun `a diagnosis and a fix plan can arrive in one turn`() {
        // The mockup splits them across two screens, but nothing stops one message
        // carrying both, and the parser must not let the second overwrite the first.
        val turn = diagnosisTurn + "\n\n" + fixPlanTurn

        val content = parseAssistantContent(turn)
        assertNotNull(content.diagnosis)
        assertNotNull(content.fixPlan)
        assertEquals(2, content.diagnosis!!.defectCount)
        assertEquals(3, content.fixPlan!!.stages.size)
    }

    @Test
    fun `the buyer-facing blocks are untouched by the two new tags`() {
        // The regression that would matter most: Kagua is in the field, and this parser
        // is the one it uses.
        val turn = """
            This table wobbles.

            ```qv-verdict
            {"verdict":"serious_concerns","headline":"Wobbles badly","defects":[]}
            ```

            ```qv-options
            Tell me more
            Something else
            ```
        """.trimIndent()

        val content = parseAssistantContent(turn)
        assertNotNull(content.verdict)
        assertEquals("Wobbles badly", content.verdict!!.headline)
        assertEquals(listOf("Tell me more", "Something else"), content.options)
        assertNull(content.diagnosis)
        assertNull(content.fixPlan)
    }

    @Test
    fun `a build that has never heard of these tags shows the text`() {
        // parseAssistantContent leaves an unrecognised tag in the prose, which is what
        // makes adding tags safe: an older client degrades to showing the block rather
        // than dropping the turn. Asserted on a tag nothing implements yet.
        val turn = """
            Here is a quote.

            ```fb-quote
            {"total_kes": 5800}
            ```
        """.trimIndent()

        val content = parseAssistantContent(turn)
        assertTrue(content.prose.contains("fb-quote"))
        assertTrue(content.prose.contains("5800"))
    }
}

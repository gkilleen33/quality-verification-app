package com.qualityverifier.text

import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.TestDiagram
import com.qualityverifier.domain.VerdictLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBlocksTest {

    @Test
    fun `plain text passes through untouched`() {
        val content = parseAssistantContent("Send me a photo of the underside.")
        assertEquals("Send me a photo of the underside.", content.prose)
        assertEquals(emptyList<String>(), content.options)
        assertNull(content.verdict)
    }

    @Test
    fun `options block becomes chips and leaves the question in the prose`() {
        val content = parseAssistantContent(
            """
            Push the seat corner to corner. What do you feel?

            ```qv-options
            Solid, no movement
            A little give, corner to corner
            Rocks clearly at the joints
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Solid, no movement",
                "A little give, corner to corner",
                "Rocks clearly at the joints",
            ),
            content.options,
        )
        assertEquals("Push the seat corner to corner. What do you feel?", content.prose)
    }

    @Test
    fun `option bullets and numbering are stripped`() {
        val content = parseAssistantContent("Pick one.\n\n```qv-options\n- Yes\n2. No\n* Maybe\n```")
        assertEquals(listOf("Yes", "No", "Maybe"), content.options)
    }

    @Test
    fun `no more than five options survive`() {
        val body = (1..9).joinToString("\n") { "Choice $it" }
        assertEquals(5, parseAssistantContent("Pick.\n```qv-options\n$body\n```").options.size)
    }

    @Test
    fun `verdict block parses and suppresses the duplicate prose`() {
        val content = parseAssistantContent(VERDICT_MESSAGE)
        val verdict = content.verdict!!
        assertEquals(VerdictLevel.FAIR, verdict.level)
        assertEquals("Solid frame, one thing to sort out", verdict.headline)
        assertEquals(1, verdict.defects.size)
        assertEquals(Severity.MODERATE, verdict.defects[0].severity)
        assertEquals(listOf("Whether the timber is seasoned."), verdict.unverified)

        // The prose is still available, but not shown, because the cards say the same thing.
        assertTrue(content.prose.startsWith("This stool is fair."))
        assertEquals("", content.displayProse)
    }

    @Test
    fun `malformed verdict json falls back to showing the prose`() {
        val content = parseAssistantContent(
            "This stool is fair. The rear joint is open.\n\n```qv-verdict\n{ not json\n```",
        )
        assertNull(content.verdict)
        assertEquals("This stool is fair. The rear joint is open.", content.displayProse)
    }

    @Test
    fun `unterminated verdict block from a truncated reply still parses`() {
        val content = parseAssistantContent(
            "Here is the verdict.\n\n```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"All good\"}",
        )
        assertEquals(VerdictLevel.SOUND, content.verdict!!.level)
    }

    @Test
    fun `an ordinary code block is left in the prose`() {
        val text = "Measure it:\n\n```\n30cm x 40cm\n```"
        assertEquals(text, parseAssistantContent(text).prose)
        assertNull(parseAssistantContent(text).verdict)
    }

    @Test
    fun `a json block that is not a verdict stays in the prose`() {
        val text = "Example:\n\n```json\n{\"unrelated\": 1}\n```"
        val content = parseAssistantContent(text)
        assertNull(content.verdict)
        assertTrue(content.prose.contains("unrelated"))
    }

    @Test
    fun `an unrecognised verdict level renders without pretending to know it`() {
        val content = parseAssistantContent("```qv-verdict\n{\"verdict\":\"mzuri\",\"headline\":\"Nzuri\"}\n```")
        assertEquals(VerdictLevel.UNKNOWN, content.verdict!!.level)
        assertEquals("Nzuri", content.verdict!!.headline)
    }

    @Test
    fun `an empty verdict is treated as no verdict`() {
        val content = parseAssistantContent("Done.\n```qv-verdict\n{\"verdict\":\"sound\"}\n```")
        assertNull(content.verdict)
        assertEquals("Done.", content.displayProse)
    }

    @Test
    fun `both blocks in one message are handled`() {
        val content = parseAssistantContent(
            VERDICT_MESSAGE + "\n\n```qv-options\nAsk about the joint\nShare this report\n```",
        )
        assertEquals(VerdictLevel.FAIR, content.verdict!!.level)
        assertEquals(listOf("Ask about the joint", "Share this report"), content.options)
    }

    @Test
    fun `a plan block becomes a runnable plan`() {
        val content = parseAssistantContent(PLAN_MESSAGE)
        val plan = content.plan!!
        assertEquals("6 photos and 2 quick tests, about two minutes.", plan.summary)
        assertEquals("sw", plan.language)
        assertEquals(2, plan.photos.size)
        assertEquals("Full view, front", plan.photos[0].title)
        assertEquals("Whole stool in frame, arm's length", plan.photos[0].note)
        assertTrue(plan.photos[0].instruction.startsWith("Stand back"))
        assertEquals(1, plan.tests.size)
        assertEquals(TestDiagram.RACKING, plan.tests[0].diagramKind)
        assertEquals(3, plan.tests[0].options.size)
        assertEquals("Solid, no movement", plan.tests[0].options[0].label)
        assertEquals("Frame feels like one piece", plan.tests[0].options[0].detail)
        assertEquals(3, plan.stepCount)
        // The prose survives: it is what the plan screen shows above the list.
        assertTrue(content.prose.startsWith("Here is the plan."))
    }

    @Test
    fun `a follow-up plan may ask for photos only, or tests only`() {
        val photosOnly = parseAssistantContent(
            """
            One more shot.

            ```qv-plan
            {"photos":[{"title":"Under the rail","instruction":"Phone lower, looking up."}]}
            ```
            """.trimIndent(),
        ).plan!!
        assertEquals(1, photosOnly.photos.size)
        assertTrue(photosOnly.tests.isEmpty())
        assertTrue(photosOnly.isRunnable)

        val testsOnly = parseAssistantContent(
            """```qv-plan
            {"tests":[{"title":"Rock it","options":[{"label":"Solid"},{"label":"Wobbles"}]}]}
            ```""".trimIndent(),
        ).plan!!
        assertTrue(testsOnly.photos.isEmpty())
        assertEquals(1, testsOnly.tests.size)
    }

    @Test
    fun `an empty plan is treated as no plan`() {
        val content = parseAssistantContent("Nothing to collect.\n```qv-plan\n{\"photos\":[]}\n```")
        assertNull(content.plan)
        assertEquals("Nothing to collect.", content.displayProse)
    }

    @Test
    fun `a malformed plan is dropped rather than printed`() {
        val content = parseAssistantContent("Take these shots.\n\n```qv-plan\n{ not json\n```")
        assertNull(content.plan)
        // The prose lists the shots in words, so a parse failure is recoverable.
        assertEquals("Take these shots.", content.displayProse)
    }

    @Test
    fun `an unknown diagram name degrades to no diagram`() {
        val plan = parseAssistantContent(
            """```qv-plan
            {"tests":[{"title":"T","diagram":"interpretive-dance","options":[{"label":"a"}]}]}
            ```""".trimIndent(),
        ).plan!!
        // The drawings ship in the app; a prompt naming one this build has never heard
        // of must not render a placeholder box.
        assertNull(plan.tests[0].diagramKind)
        assertEquals("interpretive-dance", plan.tests[0].diagram)
    }

    @Test
    fun `a plan and a verdict never both apply, but both parse`() {
        // Guards the ordering in the renderer: a follow-up plan arriving in the same
        // turn as a verdict would otherwise be silently dropped.
        val content = parseAssistantContent(
            VERDICT_MESSAGE + "\n\n```qv-plan\n{\"photos\":[{\"title\":\"X\"}]}\n```",
        )
        assertEquals(1, content.plan!!.photos.size)
        assertEquals(VerdictLevel.FAIR, content.verdict!!.level)
    }

    @Test
    fun `a plan shows only its opening paragraph, not the plan a second time`() {
        // The assistant writes an acknowledgement and then, left to itself, lists every
        // shot in prose as well. The card draws that list directly underneath, so showing
        // both put the whole plan on screen twice and buried the start-camera button.
        val content = parseAssistantContent(
            """
            Got it, a table for daily use. I'll look hardest at the leg joints.

            Here's what I need: seven photos and four checks.

            1. Whole table, one corner.
            2. Top from directly above.

            ```qv-plan
            {"photos":[{"title":"Whole table"},{"title":"Top from above"}]}
            ```
            """.trimIndent(),
        )
        assertEquals(2, content.plan!!.photos.size)
        assertEquals(
            "Got it, a table for daily use. I'll look hardest at the leg joints.",
            content.displayProse,
        )
        // The full prose is still there for anything that needs it.
        assertTrue(content.prose.contains("Whole table, one corner"))
    }

    @Test
    fun `a plan with a single paragraph of prose keeps all of it`() {
        val content = parseAssistantContent(
            "Right, let's look at the joints.\n```qv-plan\n{\"photos\":[{\"title\":\"A\"}]}\n```",
        )
        assertEquals("Right, let's look at the joints.", content.displayProse)
    }

    @Test
    fun `prose is untouched when the plan block failed to parse`() {
        // The fallback has to stay whole: if the card is not going to appear, the prose
        // list is the only thing telling the customer what to photograph.
        val content = parseAssistantContent(
            """
            Here is the plan.

            1. Whole table.
            2. Underside.

            ```qv-plan
            { not json
            ```
            """.trimIndent(),
        )
        assertNull(content.plan)
        assertTrue(content.displayProse.contains("1. Whole table."))
        assertTrue(content.displayProse.contains("2. Underside."))
    }

    private companion object {
        val PLAN_MESSAGE = """
            Here is the plan. I will guide each shot.

            ```qv-plan
            {
              "summary": "6 photos and 2 quick tests, about two minutes.",
              "language": "sw",
              "photos": [
                {
                  "title": "Full view, front",
                  "note": "Whole stool in frame, arm's length",
                  "instruction": "Stand back far enough that the whole stool is in the frame."
                },
                {
                  "title": "Leg joint, close",
                  "note": "Where a rail meets the leg",
                  "instruction": "Get close enough that the joint fills the frame."
                }
              ],
              "tests": [
                {
                  "title": "The wobble test",
                  "subtitle": "Jaribu kutikisa",
                  "instruction": "Hold two opposite corners and push corner to corner.",
                  "diagram": "racking",
                  "options": [
                    { "label": "Solid, no movement", "detail": "Frame feels like one piece" },
                    { "label": "A little give", "detail": "Corner to corner" },
                    { "label": "Rocks clearly", "detail": "Visible movement at the joints" }
                  ]
                }
              ]
            }
            ```
        """.trimIndent()

        val VERDICT_MESSAGE = """
            This stool is fair. The rear joint is open, which will loosen with daily use.

            ```qv-verdict
            {
              "verdict": "fair",
              "headline": "Solid frame, one thing to sort out",
              "summary": "Worth buying if he re-glues that joint first.",
              "defects": [
                {
                  "title": "Gap at the rear stretcher joint",
                  "area": "structural",
                  "severity": "moderate",
                  "what_i_see": "The stretcher is not seated fully into the leg.",
                  "what_it_means": "It will work loose with daily use.",
                  "what_to_do": "The joint needs opening out, re-gluing and clamping."
                }
              ],
              "unverified": ["Whether the timber is seasoned."],
              "questions": ["Will it get worse?"]
            }
            ```
        """.trimIndent()
    }

    // ---------------------------------------------------------------- streaming

    @Test
    fun `a reply still arriving shows its prose`() {
        assertEquals(
            "This table has a serious problem with its back left joint.",
            streamingProse("This table has a serious problem with its back left joint."),
        )
    }

    @Test
    fun `a half-written fence line is never shown`() {
        // The narrow reason streamingProse exists. parseAssistantContent already drops an
        // unterminated qv-verdict block, so the JSON was never the risk — the risk is the
        // fence line itself arriving a character at a time, because "```qv-verd" is not a
        // tag it recognises and would be echoed into the prose as literal backticks.
        listOf("`", "``", "```", "```q", "```qv-verd", "```qv-verdict").forEach { tail ->
            assertEquals(
                "leaked for tail \"$tail\"",
                "Here is the verdict.",
                streamingProse("Here is the verdict.\n\n$tail"),
            )
        }
    }

    @Test
    fun `nothing is shown once the block has started`() {
        // A real state, not an error: the prose is finished and the assistant is writing
        // the block. The caller shows that it is still working rather than an empty bubble.
        val partial = """
            Here is the verdict.

            ```qv-verdict
            {
              "verdict": "seri
        """.trimIndent()

        assertEquals("Here is the verdict.", streamingProse(partial))
    }

    @Test
    fun `the prose is not cut mid-sentence by an ordinary code block`() {
        // An ordinary markdown fence ends the visible prose too. That is the right
        // trade: a code block in furniture advice is vanishingly rare, and showing one
        // being typed is worth less than never showing a stray fence.
        val partial = "Two things to check.\n\n```\nsome code"

        assertEquals("Two things to check.", streamingProse(partial))
    }

    @Test
    fun `an empty partial stays empty`() {
        assertEquals("", streamingProse(""))
        assertEquals("", streamingProse("```qv-verdict"))
    }
}

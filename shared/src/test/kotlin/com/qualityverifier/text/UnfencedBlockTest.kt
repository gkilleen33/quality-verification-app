package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A block the model wrote without its fence.
 *
 * This happened on the first real Fundi Bora assessment. `fundi-master.txt` asked in
 * prose for "a fenced block" but every example in it showed the tag bare, so the model
 * copied the examples: `qv-plan` on its own line with the JSON under it, no backticks
 * anywhere in the reply. The prompt is fixed, but prompts are data fetched from GitHub
 * and can drift again without a release — and the failure mode is the worst one
 * available, which is a maker reading raw JSON where their plan should be.
 */
class UnfencedBlockTest {

    private val unfencedPlan = """
        Nitahitaji picha sita.

        qv-plan
        {
          "summary": "Six shots",
          "photos": [{"title": "Whole piece", "instruction": "Stand back"}],
          "tests": []
        }
    """.trimIndent()

    @Test
    fun `an unfenced plan is still a plan`() {
        val content = parseAssistantContent(unfencedPlan)

        assertNotNull("the plan has to be found, fence or no fence", content.plan)
        assertEquals(1, content.plan?.photos?.size)
    }

    // The point of finding it: the JSON must not reach the reader.
    @Test
    fun `the JSON does not end up in the prose`() {
        val prose = parseAssistantContent(unfencedPlan).displayProse

        assertFalse(prose, prose.contains("{"))
        assertFalse(prose, prose.contains("photos"))
        assertTrue("and the words the model wrote survive", prose.contains("Nitahitaji"))
    }

    @Test
    fun `an unfenced diagnosis is found too`() {
        val content = parseAssistantContent(
            """
            Hii ni shida ya kupima.

            fb-diagnosis
            {
              "language": "sw",
              "findings": [{"title": "Joint gap", "what_happened": "A 3mm gap"}]
            }
            """.trimIndent()
        )

        assertEquals("Joint gap", content.diagnosis?.primary?.title)
    }

    @Test
    fun `an unfenced fix plan is found too`() {
        val content = parseAssistantContent(
            """
            fb-fixplan
            {"fix_now": {"summary": "Re-glue it", "steps": ["Work the joint apart"]}}
            """.trimIndent()
        )

        assertEquals("Re-glue it", content.fixPlan?.fixNow?.summary)
    }

    // Nested objects must not end the block early, or the decode fails and the tail of
    // the JSON spills into the prose — the exact thing this is here to prevent.
    @Test
    fun `nested objects do not end the block early`() {
        val content = parseAssistantContent(
            """
            fb-fixplan
            {
              "fix_now": {"summary": "Now", "steps": ["a"]},
              "prevent": {"summary": "Next time", "steps": ["b"]}
            }
            """.trimIndent()
        )

        assertEquals("Now", content.fixPlan?.fixNow?.summary)
        assertEquals("Next time", content.fixPlan?.prevent?.summary)
        assertFalse(content.displayProse.contains("prevent"))
    }

    // The conditions have to be tight, or ordinary writing gets eaten. A tag word in a
    // sentence is a sentence.
    @Test
    fun `a tag mentioned in prose is left alone`() {
        val text = "I will send you a qv-plan in a moment."

        val content = parseAssistantContent(text)

        assertNull(content.plan)
        assertEquals(text, content.displayProse)
    }

    @Test
    fun `a bare tag with no object after it is left alone`() {
        val text = "qv-plan\nI could not work out what to ask for."

        val content = parseAssistantContent(text)

        assertNull(content.plan)
        assertTrue(content.displayProse.contains("qv-plan"))
    }

    // The fenced form is still the one the prompt asks for and must keep working.
    @Test
    fun `a properly fenced block is unaffected`() {
        val content = parseAssistantContent(
            """
            Here is the plan.

            ```qv-plan
            {"summary": "Fenced", "photos": [{"title": "One", "instruction": "Do it"}], "tests": []}
            ```
            """.trimIndent()
        )

        assertEquals("Fenced", content.plan?.summary)
        assertFalse(content.displayProse.contains("{"))
    }
}

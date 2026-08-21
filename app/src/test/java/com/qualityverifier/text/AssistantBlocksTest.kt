package com.qualityverifier.text

import com.qualityverifier.domain.Severity
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
        assertEquals("Can you re-glue this joint?", verdict.defects[0].askSeller)
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

    private companion object {
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
                  "what_to_do": "Ask for it to be re-glued before you pay.",
                  "ask_seller": "Can you re-glue this joint?"
                }
              ],
              "unverified": ["Whether the timber is seasoned."],
              "questions": ["Will it get worse?"]
            }
            ```
        """.trimIndent()
    }
}

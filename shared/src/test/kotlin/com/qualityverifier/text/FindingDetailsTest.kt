package com.qualityverifier.text

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail buttons on an improvement: the three of release scope row 37, and row 49's tool. */
class FindingDetailsTest {

    private val diagnosis = ChatMessage(
        id = "a1",
        role = Role.ASSISTANT,
        text = """
            Four improvements.

            ```fb-diagnosis
            {"language":"en","findings":[{"title":"Fix the arm firmly and mend the crack","severity":"moderate","fix_line":"Glue and screw the arm."}]}
            ```
        """.trimIndent(),
    )

    private fun request(id: String, kind: DetailKind, composed: Boolean = true) =
        ChatMessage(id, Role.USER, kind.request("Fix the arm firmly and mend the crack"), composed = composed)

    private fun reply(id: String, text: String) = ChatMessage(id, Role.ASSISTANT, text)

    @Test
    fun `each request names its kind and the finding, and reads back`() {
        DetailKind.entries.forEach { kind ->
            val text = kind.request("  Fix the arm firmly and mend the crack ")
            assertEquals(kind to "Fix the arm firmly and mend the crack", DetailKind.parse(text))
        }
        assertNull(DetailKind.parse("How do I fix the seat slats?"))
    }

    @Test
    fun `an answered request shows under its finding and leaves the conversation`() {
        val details = detailAnswersOf(
            listOf(
                diagnosis,
                request("u1", DetailKind.CAUSE),
                reply("a2", "The arm is likely nailed, not screwed, to the seat frame."),
            ),
        )
        val key = DetailKey(DetailKind.CAUSE, "Fix the arm firmly and mend the crack")
        assertEquals("The arm is likely nailed, not screwed, to the seat frame.", details.answers[key])
        assertEquals(setOf("u1", "a2"), details.hidden)
        assertTrue(details.pending.isEmpty())
    }

    @Test
    fun `a request still waiting is pending, and hidden`() {
        val details = detailAnswersOf(listOf(diagnosis, request("u1", DetailKind.WHAT_WE_FOUND)))
        assertEquals(setOf(DetailKey(DetailKind.WHAT_WE_FOUND, "Fix the arm firmly and mend the crack")), details.pending)
        assertEquals(setOf("u1"), details.hidden)
    }

    @Test
    fun `a reply that corrects the diagnosis stays in the conversation`() {
        val corrected = reply(
            "a2",
            """
                I was wrong about the crack. It is part of the design.

                ```fb-diagnosis
                {"findings":[{"title":"Fix the arm into the seat frame","severity":"minor"}]}
                ```
            """.trimIndent(),
        )
        val details = detailAnswersOf(listOf(diagnosis, request("u1", DetailKind.WHAT_WE_FOUND), corrected))
        assertTrue(details.answers.isEmpty())
        assertEquals(setOf("u1"), details.hidden)
        // Neither answered nor waiting, so the button on the corrected card may ask again.
        // AssessViewModel.requestDetail skips a request only when its key is in one of these.
        assertTrue(details.pending.isEmpty())
    }

    @Test
    fun `the same words typed by the maker are an ordinary turn`() {
        val details = detailAnswersOf(
            listOf(diagnosis, request("u1", DetailKind.NEXT_TIME, composed = false), reply("a2", "Screw it.")),
        )
        assertEquals(DetailAnswers.NONE, details)
    }

    @Test
    fun `an older reply already holds each detail`() {
        val finding = Finding(whatHappened = "A gap.", manufacturingCause = "Cut at an angle.", rootHabit = "Mark all four sides.")
        assertEquals("A gap.", DetailKind.WHAT_WE_FOUND.storedIn(finding))
        assertEquals("Cut at an angle.", DetailKind.CAUSE.storedIn(finding))
        assertEquals("Mark all four sides.", DetailKind.NEXT_TIME.storedIn(finding))
        // No reply field ever held the tool answer: tool_line names a tool the maker has.
        assertEquals("", DetailKind.TOOL.storedIn(finding.copy(toolLine = "Your sander does this faster.")))
    }

    @Test
    fun `row 49 - the tool button sends its own words, under the id the server's CHECK allows`() {
        assertEquals("A tool to buy or rent: ", DetailKind.TOOL.requestPrefix)
        assertEquals("tool", DetailKind.TOOL.id)
        assertEquals("the fourth button, after the three of row 37", DetailKind.entries.last(), DetailKind.TOOL)
        assertEquals(DetailKind.TOOL to "Oil the top", DetailKind.parse("A tool to buy or rent: Oil the top"))
        // Every button now has an id the server stores, and every id a button.
        assertEquals(DetailKind.SERVER_IDS, DetailKind.entries.map { it.id }.toSet())
        assertEquals("A tool to buy or rent", FundiLabels.ENGLISH.detailTool)
    }

    @Test
    fun `row 49 - the prompt answers the tool button, names no price, and prefers a rental`() {
        val rule = DefaultPrompts.FUNDI_MASTER.lineSequence().single { it.startsWith("- \"A tool to buy or rent: <title>\".") }
        assertTrue(rule.contains("name one tool they do not have, to buy or rent, whose lack caused or worsened this defect"))
        assertTrue(rule.contains("Prefer one they could rent nearby"))
        assertTrue(rule.contains("If no tool would help, say so in one sentence"))
        assertTrue(rule.contains("Never give a price"))
        assertTrue(DefaultPrompts.FUNDI_MASTER.contains("Under each improvement the app shows four buttons"))
    }

    @Test
    fun `the prompt answers the exact words the buttons send`() {
        // The prefixes are the protocol between the phone and the prompt; a rewrite of
        // either side that loses the other turns every tap into an ordinary question.
        DetailKind.entries.forEach { kind ->
            assertTrue(
                "the prompt no longer answers \"${kind.requestPrefix.trim()}\"",
                DefaultPrompts.FUNDI_MASTER.contains("\"${kind.requestPrefix}<title>\""),
            )
        }
    }
}

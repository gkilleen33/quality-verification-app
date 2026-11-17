package com.qualityverifier.text

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three detail buttons under a finding (release scope row 37). */
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

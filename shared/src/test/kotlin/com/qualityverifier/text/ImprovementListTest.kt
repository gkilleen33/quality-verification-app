package com.qualityverifier.text

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply as a list of improvements, with a page per improvement and a chat on each page
 * (v0.7.0, release scope row 43; mockup v2, signed off 20 to 23 November 2026).
 */
class ImprovementListTest {

    /** A prompt E reply with the new row_line, three improvements, worst first. */
    private val reply = """
        The daybed is close to selling. Three things to put right first.

        ```fb-diagnosis
        {"language":"en","one_check":"Did you cut the slats by eye?","findings":[
          {"title":"Glue and screw the back rail to the posts","severity":"serious","fix_line":"Glue and screw the back rail.",
           "customer_line":"A firm back rail feels solid. A rail that flexes may make the customer think the daybed is weak, and offer less.",
           "row_line":"A rail that flexes makes customers think the daybed is weak, and offer less."},
          {"title":"Sand the top's front edge smooth","severity":"minor","fix_line":"Sand the front edge.",
           "customer_line":"A smooth edge looks well made. A rough one may look rushed.","row_line":"A rough edge makes customers think it was rushed."},
          {"title":"Fill the gap under the left arm","severity":"cosmetic","fix_line":"Fill the gap with wood filler."}
        ]}
        ```
    """.trimIndent()

    private val diagnosis = requireNotNull(parseAssistantContent(reply).diagnosis)

    @Test
    fun `row 43 - the list shows one row per improvement, in order, with its severity and short line`() {
        val rows = improvementRows(diagnosis)
        assertTrue(diagnosis.isImprovementList)
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertEquals(
            listOf("Glue and screw the back rail to the posts", "Sand the top's front edge smooth", "Fill the gap under the left arm"),
            rows.map { it.finding.title },
        )
        assertEquals(listOf(Severity.SERIOUS, Severity.MINOR, Severity.COSMETIC), rows.map { it.finding.severity })
        assertEquals("A rail that flexes makes customers think the daybed is weak, and offer less.", rows[0].finding.rowLine)
        assertEquals("a reply without row_line shows the title alone", "", rows[2].finding.rowLine)
        // The callout holds the first improvement's customer_line as it stands: no new field.
        assertEquals(diagnosis.findings[0].customerLine, customersNoticeFirst(rows))
    }

    @Test
    fun `row 43 - an untitled improvement has no row but keeps its number`() {
        val content = parseAssistantContent(
            """
            ```fb-diagnosis
            {"findings":[{"title":"","fix_line":"x"},{"title":"Oil the top","fix_line":"Oil it."}]}
            ```
            """.trimIndent(),
        )
        val rows = improvementRows(requireNotNull(content.diagnosis))
        assertEquals(listOf(2), rows.map { it.number })
    }

    @Test
    fun `row 43 - a reply from before arm 1 keeps the old card`() {
        val old = parseAssistantContent(
            """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back joint","severity":"moderate","what_happened":"A gap."}]}
            ```
            """.trimIndent(),
        )
        assertFalse(requireNotNull(old.diagnosis).isImprovementList)
    }

    @Test
    fun `row 43 - Previous and Next stop at the ends`() {
        assertNull(ImprovementPaging.previous(0))
        assertEquals(0, ImprovementPaging.previous(1))
        assertEquals(1, ImprovementPaging.next(0, 3))
        assertEquals(2, ImprovementPaging.next(1, 3))
        assertNull(ImprovementPaging.next(2, 3))
        assertNull("a list of one has neither", ImprovementPaging.next(0, 1))
    }

    @Test
    fun `row 43 - a page question names its improvement and reads back`() {
        val asked = ImprovementQuestion.of(2, "  Sand the top's front edge smooth ", " Can I use a scraper? ")
        assertEquals("About improvement 2, 'Sand the top's front edge smooth': Can I use a scraper?", asked.text)
        assertEquals(asked, ImprovementQuestion.parse(asked.text))
        // The maker's words may hold the same punctuation, and lines.
        val tricky = ImprovementQuestion.of(1, "Oil the top", "Is 'oil': enough?\nOr wax?")
        assertEquals(tricky, ImprovementQuestion.parse(tricky.text))
        assertNull(ImprovementQuestion.parse("How do I fix the seat slats?"))
        assertNull(ImprovementQuestion.parse("About improvement 0, 'x': y"))
    }

    @Test
    fun `row 43 - a title holding the delimiter still files its question under its own page`() {
        val title = "Re-glue the 'back': rail"
        val asked = ImprovementQuestion.of(1, title, "How long to clamp?")
        val read = ImprovementQuestion.parse(asked.text)
        assertEquals("How long to clamp?", read?.question)
        val row = ImprovementRow(1, diagnosis.findings[0].copy(title = title))
        assertEquals(row.key, read?.key)
    }

    @Test
    fun `row 43 - the server names the improvement from the diagnosis, and refuses a number it lacks`() {
        assertEquals(
            "About improvement 1, 'Glue and screw the back rail to the posts': Which screws?",
            ImprovementQuestion.about(diagnosis, 1, "Which screws?")?.text,
        )
        assertNull(ImprovementQuestion.about(diagnosis, 4, "Which screws?"))
        assertNull(ImprovementQuestion.about(null, 1, "Which screws?"))
    }

    @Test
    fun `row 43 - a question from a page is stored on that page, and leaves the conversation`() {
        val question = ImprovementQuestion.of(2, "Sand the top's front edge smooth", "Can I use a scraper?")
        val messages = listOf(
            ChatMessage("a1", Role.ASSISTANT, reply),
            ChatMessage("u2", Role.USER, question.text),
            ChatMessage("a2", Role.ASSISTANT, "Yes, a card scraper works on that edge."),
            ChatMessage("u3", Role.USER, ImprovementQuestion.of(2, "Sand the top's front edge smooth", "And then oil?").text),
        )
        val pages = pageQuestionsOf(messages)
        val key = ImprovementKey(2, "Sand the top's front edge smooth")
        assertEquals(
            listOf(
                PageExchange("u2", "Can I use a scraper?", "Yes, a card scraper works on that edge."),
                PageExchange("u3", "And then oil?", null),
            ),
            pages.byImprovement[key],
        )
        assertEquals(setOf("u2", "a2", "u3"), pages.hidden)
        assertEquals("the row counts both", 2, improvementRows(diagnosis, pages).single { it.number == 2 }.questions)
        assertEquals(0, improvementRows(diagnosis, pages).single { it.number == 1 }.questions)
    }

    @Test
    fun `row 43 - a page answer that corrects the diagnosis stays in the conversation`() {
        val corrected = """
            I was wrong about the edge: it is the grain.

            ```fb-diagnosis
            {"findings":[{"title":"Oil the top","severity":"minor","fix_line":"Oil it."}]}
            ```
        """.trimIndent()
        val messages = listOf(
            ChatMessage("a1", Role.ASSISTANT, reply),
            ChatMessage("u2", Role.USER, ImprovementQuestion.of(2, "Sand the top's front edge smooth", "Is it the grain?").text),
            ChatMessage("a2", Role.ASSISTANT, corrected),
        )
        val pages = pageQuestionsOf(messages)
        assertEquals(setOf("u2"), pages.hidden)
        assertEquals(
            "the page shows what changed",
            "I was wrong about the edge: it is the grain.",
            pages.byImprovement.values.single().single().answer,
        )
    }

    @Test
    fun `row 43 - a composed turn is never a page question`() {
        val messages = listOf(
            ChatMessage("u1", Role.USER, ImprovementQuestion.of(1, "Oil the top", "x").text, composed = true),
            ChatMessage("a1", Role.ASSISTANT, "ok"),
        )
        assertEquals(PageQuestions.NONE, pageQuestionsOf(messages))
    }

    // A v0.6.1 phone reads the same reply with the parser it shipped with: the same
    // parseAssistantContent, whose decoder ignores keys it does not know. row_line is one such
    // key to it, so this reply must still parse with every older field intact.
    @Test
    fun `row 43 - a reply carrying row_line and a key no phone knows still parses, every older field intact`() {
        val content = parseAssistantContent(reply.replace("\"row_line\":", "\"a_later_field\":1,\"row_line\":"))
        val parsed = requireNotNull(content.diagnosis)
        assertEquals(3, parsed.findings.size)
        assertEquals("Glue and screw the back rail.", parsed.findings[0].fixLine)
        assertTrue(parsed.findings[0].customerLine.startsWith("A firm back rail"))
        assertEquals("Did you cut the slats by eye?", parsed.oneCheck)
        assertEquals("The daybed is close to selling. Three things to put right first.", content.displayProse)
    }
}

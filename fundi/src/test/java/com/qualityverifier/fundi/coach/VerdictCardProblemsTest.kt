package com.qualityverifier.fundi.coach

import com.qualityverifier.fundi.ui.coach.verdictCardProblems
import com.qualityverifier.text.parseAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Fundi Bora's verdict card lists, in both shapes of reply.
 *
 * The card is a composable, so its list is tested through the function that builds it.
 * The point is the reply that writes each problem once: its verdict lists no defects, and
 * a card reading only those would tell a maker a piece with two problems has none.
 */
class VerdictCardProblemsTest {

    @Test
    fun `today's shape lists the verdict's defects, as before`() {
        val problems = verdictCardProblems(parseAssistantContent(TODAY_SHAPE))
        assertEquals(listOf("Gap at the back joint", "Saw marks on the top"), problems.map { it.title })
    }

    @Test
    fun `the one-list shape lists every finding once`() {
        val problems = verdictCardProblems(parseAssistantContent(ONE_LIST_SHAPE))
        assertEquals(listOf("Gap at the back joint", "Saw marks on the top"), problems.map { it.title })
        assertEquals("A gap.", problems.first().whatISee)
    }

    @Test
    fun `a sound verdict in today's shape lists its cosmetic finding`() {
        // The finding carries no consequence, but cosmetic findings are problems too
        // (Nathan, 11 November 2026).
        val reply = parseAssistantContent(
            """
            ```fb-diagnosis
            {"findings":[{"title":"Saw marks on the top","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"sound","headline":"Good work","defects":[]}
            ```
            """.trimIndent(),
        )
        assertEquals(listOf("Saw marks on the top"), verdictCardProblems(reply).map { it.title })
    }

    @Test
    fun `a verdict-only follow-up lists nothing of its own`() {
        // The problems are on the earlier turn's cards, still on screen above it.
        val reply = parseAssistantContent("```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"Still the joint\"}\n```")
        assertTrue(verdictCardProblems(reply).isEmpty())
    }

    @Test
    fun `an untitled problem is not listed`() {
        val reply = parseAssistantContent(
            "```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"H\",\"defects\":[{\"title\":\"\"},{\"title\":\"Gap\"}]}\n```",
        )
        assertEquals(listOf("Gap"), verdictCardProblems(reply).map { it.title })
    }

    private companion object {
        val TODAY_SHAPE = """
            ```fb-diagnosis
            {"findings":[
              {"title":"Gap at the back joint","severity":"moderate","dimension":"joint_fit"},
              {"title":"Saw marks on the top","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint to close","defects":[
              {"title":"Gap at the back joint","severity":"moderate","what_i_see":"A gap you can see."},
              {"title":"Saw marks on the top","severity":"cosmetic","what_i_see":"Lines."}]}
            ```
        """.trimIndent()

        val ONE_LIST_SHAPE = """
            ```fb-diagnosis
            {"findings":[
              {"title":"Gap at the back joint","severity":"moderate","dimension":"joint_fit","what_i_see":"A gap.","what_it_means":"It will loosen.","what_to_do":"Re-glue it."},
              {"title":"Saw marks on the top","severity":"cosmetic","dimension":"finishing","what_i_see":"Lines.","what_it_means":"Looks only.","what_to_do":"Sand it."}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint to close"}
            ```
        """.trimIndent()
    }
}

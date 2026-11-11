package com.qualityverifier.server

import com.qualityverifier.server.admin.assistantBody
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The admin portal's turn view lists a reply's problems through problemsOf.
 *
 * The portal draws the verdict and never the diagnosis, so a reply that writes each
 * problem once (findings with their consequence, a verdict with no defects) would show a
 * reviewer a verdict with nothing wrong if the view read only the defects.
 */
class TurnViewTest {

    @Test
    fun `today's shape draws the verdict's defects, each once, as before`() {
        val html = render(TODAY_SHAPE)

        assertEquals(2, html.occurrences("class=\"vcard defect\""))
        assertEquals(1, html.occurrences(problemHeading("Gap at the back joint")))
        assertEquals(1, html.occurrences(problemHeading("Saw marks on the top")))
        // The finding that has no defect is not drawn: the defects are the list.
        assertEquals(0, html.occurrences(problemHeading("Glue left at the joint")))
        assertTrue(html.contains("<dd>A gap you can see.</dd>"))
    }

    @Test
    fun `the one-list shape draws every finding once, with its consequence`() {
        val html = render(ONE_LIST_SHAPE)

        assertEquals(2, html.occurrences("class=\"vcard defect\""))
        assertEquals(1, html.occurrences(problemHeading("Gap at the back joint")))
        assertEquals(1, html.occurrences(problemHeading("Saw marks on the top")))
        assertTrue(html.contains("<dd>A gap.</dd>"))
        assertTrue(html.contains("<dd>It will loosen.</dd>"))
        assertTrue(html.contains("<dd>Sand it.</dd>"))
    }

    @Test
    fun `a sound verdict in today's shape draws its cosmetic finding`() {
        // The finding carries no consequence, but cosmetic findings are problems too
        // (Nathan, 11 November 2026), so the reviewer sees it beside the verdict.
        val html = render(
            """
            ```fb-diagnosis
            {"findings":[{"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"sound","headline":"Good work","defects":[]}
            ```
            """.trimIndent(),
        )

        assertEquals(1, html.occurrences("class=\"vcard defect\""))
        assertEquals(1, html.occurrences(problemHeading("Saw marks on the top")))
    }

    @Test
    fun `a Kagua verdict draws its defects`() {
        val html = render(
            """
            ```qv-verdict
            {"verdict":"fair","headline":"Solid","defects":[{"title":"Loose leg","severity":"serious","what_i_see":"It moves."}]}
            ```
            """.trimIndent(),
        )

        assertEquals(1, html.occurrences(problemHeading("Loose leg")))
        assertTrue(html.contains("<dd>It moves.</dd>"))
    }

    private fun render(text: String): String =
        createHTML(prettyPrint = false).div { assistantBody(text) }

    private fun problemHeading(title: String) = "<div class=\"vhead small\">$title</div>"

    private fun String.occurrences(needle: String): Int = windowed(needle.length).count { it == needle }

    private companion object {
        /** Three findings, two of them repeated as verdict defects with their consequence. */
        val TODAY_SHAPE = """
            ```fb-diagnosis
            {"findings":[
              {"title":"Gap at the back joint","area":"structural","severity":"moderate","dimension":"joint_fit"},
              {"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing"},
              {"title":"Glue left at the joint","area":"surface","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint to close","defects":[
              {"title":"Gap at the back joint","area":"structural","severity":"moderate","what_i_see":"A gap you can see."},
              {"title":"Saw marks on the top","area":"surface","severity":"cosmetic","what_i_see":"Lines."}]}
            ```
        """.trimIndent()

        /** Each problem written once: findings with their consequence, no defects. */
        val ONE_LIST_SHAPE = """
            ```fb-diagnosis
            {"findings":[
              {"title":"Gap at the back joint","area":"structural","severity":"moderate","dimension":"joint_fit","what_i_see":"A gap.","what_it_means":"It will loosen.","what_to_do":"Re-glue it."},
              {"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing","what_i_see":"Lines.","what_it_means":"Looks only.","what_to_do":"Sand it."}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint to close"}
            ```
        """.trimIndent()
    }
}

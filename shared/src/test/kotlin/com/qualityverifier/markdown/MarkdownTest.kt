package com.qualityverifier.markdown

import com.qualityverifier.text.MdBlock
import com.qualityverifier.text.MdSpan
import com.qualityverifier.text.MdStyle
import com.qualityverifier.text.parseInline
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun styled(raw: String) = parseInline(raw)

    // ---- inline ----

    @Test
    fun `bold markers are removed and the range is marked`() {
        val r = styled("Check the **leg joints** carefully")
        assertEquals("Check the leg joints carefully", r.text)
        assertEquals(listOf(MdSpan(10, 20, MdStyle.BOLD)), r.spans)
        assertEquals("leg joints", r.text.substring(10, 20))
    }

    @Test
    fun `double underscore is bold but a single underscore is left alone`() {
        assertEquals("bold", styled("__bold__").text)
        // Would otherwise mangle identifiers like item_wooden_table.
        val r = styled("the file item_wooden_table.jpg")
        assertEquals("the file item_wooden_table.jpg", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `single asterisk is italic`() {
        val r = styled("this is *quite* important")
        assertEquals("this is quite important", r.text)
        assertEquals(listOf(MdSpan(8, 13, MdStyle.ITALIC)), r.spans)
    }

    @Test
    fun `bold wins over italic for a double marker`() {
        val r = styled("**strong**")
        assertEquals("strong", r.text)
        assertEquals(listOf(MdSpan(0, 6, MdStyle.BOLD)), r.spans)
    }

    @Test
    fun `nested emphasis keeps both spans`() {
        val r = styled("**bold with *italic* inside**")
        assertEquals("bold with italic inside", r.text)
        assertTrue(r.spans.contains(MdSpan(10, 16, MdStyle.ITALIC)))
        assertTrue(r.spans.contains(MdSpan(0, 23, MdStyle.BOLD)))
    }

    @Test
    fun `an unmatched marker stays literal`() {
        assertEquals("2 * 3 = 6", styled("2 * 3 = 6").text)
        assertEquals("**unclosed", styled("**unclosed").text)
        assertEquals("a ** b", styled("a ** b").text)
    }

    @Test
    fun `empty emphasis stays literal`() {
        assertEquals("****", styled("****").text)
        assertEquals("**", styled("**").text)
    }

    @Test
    fun `inline code is literal inside`() {
        val r = styled("open `item_**x**.jpg` now")
        assertEquals("open item_**x**.jpg now", r.text)
        // "item_**x**.jpg" is 14 chars starting at index 5.
        assertEquals(listOf(MdSpan(5, 19, MdStyle.CODE)), r.spans)
        assertEquals("item_**x**.jpg", r.text.substring(5, 19))
    }

    @Test
    fun `a link keeps its label and drops the url`() {
        val r = styled("see [the guide](https://example.com/x) here")
        assertEquals("see the guide here", r.text)
        assertEquals(listOf(MdSpan(4, 13, MdStyle.LINK)), r.spans)
    }

    @Test
    fun `malformed links stay literal`() {
        assertEquals("[label] (url)", styled("[label] (url)").text)
        assertEquals("[unclosed", styled("[unclosed").text)
        assertEquals("[a](unclosed", styled("[a](unclosed").text)
    }

    @Test
    fun `plain text passes through untouched`() {
        val raw = "Stand back far enough that all four legs are visible."
        assertEquals(raw, styled(raw).text)
        assertTrue(styled(raw).spans.isEmpty())
    }

    // ---- blocks ----

    @Test
    fun `headings are levelled and capped at three`() {
        val blocks = parseMarkdown("# One\n\n## Two\n\n#### Four")
        val levels = blocks.filterIsInstance<MdBlock.Heading>().map { it.level }
        assertEquals(listOf(1, 2, 3), levels)
    }

    @Test
    fun `bullets accept all three markers`() {
        val blocks = parseMarkdown("- a\n* b\n+ c")
        assertEquals(3, blocks.filterIsInstance<MdBlock.Bullet>().size)
        assertEquals(listOf("a", "b", "c"), blocks.filterIsInstance<MdBlock.Bullet>().map { it.content.text })
    }

    @Test
    fun `numbered lists keep their own numbers`() {
        val blocks = parseMarkdown("1. first\n2. second\n7) seventh")
        val items = blocks.filterIsInstance<MdBlock.Numbered>()
        assertEquals(listOf(1, 2, 7), items.map { it.number })
        assertEquals(listOf("first", "second", "seventh"), items.map { it.content.text })
    }

    @Test
    fun `indentation becomes a nesting level`() {
        val blocks = parseMarkdown("- top\n  - nested")
        val items = blocks.filterIsInstance<MdBlock.Bullet>()
        assertEquals(listOf(0, 1), items.map { it.indent })
    }

    @Test
    fun `a wrapped list item continues rather than starting a paragraph`() {
        val blocks = parseMarkdown("- stand back far enough\n  that all legs are visible")
        assertEquals(1, blocks.size)
        assertEquals(
            "stand back far enough that all legs are visible",
            (blocks.single() as MdBlock.Bullet).content.text,
        )
    }

    @Test
    fun `a blank line separates paragraphs and a single newline is kept as a break`() {
        val blocks = parseMarkdown("one\ntwo\n\nthree")
        assertEquals(2, blocks.size)
        assertEquals("one\ntwo", (blocks[0] as MdBlock.Paragraph).content.text)
        assertEquals("three", (blocks[1] as MdBlock.Paragraph).content.text)
    }

    @Test
    fun `horizontal rules are recognised and a bullet is not mistaken for one`() {
        assertEquals(1, parseMarkdown("---").filterIsInstance<MdBlock.Rule>().size)
        assertEquals(1, parseMarkdown("***").filterIsInstance<MdBlock.Rule>().size)
        // Two dashes is not a rule, and "- x" is a bullet.
        assertTrue(parseMarkdown("--").filterIsInstance<MdBlock.Rule>().isEmpty())
        assertTrue(parseMarkdown("- x").filterIsInstance<MdBlock.Rule>().isEmpty())
    }

    @Test
    fun `empty and whitespace input produce no blocks`() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("   \n\n  ").isEmpty())
    }

    @Test
    fun `windows and mac line endings are handled`() {
        assertEquals(2, parseMarkdown("one\r\n\r\ntwo").size)
        assertEquals(2, parseMarkdown("one\r\rtwo").size)
    }

    @Test
    fun `every span lands inside the rendered text`() {
        // A span out of range would crash the renderer, so this is the key invariant.
        val samples = listOf(
            "**a** *b* `c` [d](e)",
            "***triple***",
            "**a *b* c** d `e` **f**",
            "- **item** one\n- *item* two",
            "# **Heading**\n\ntext with `code` and [link](url)",
            "unbalanced ** and * and ` and [",
        )
        samples.forEach { sample ->
            parseMarkdown(sample).forEach { block ->
                val inline = when (block) {
                    is MdBlock.Paragraph -> block.content
                    is MdBlock.Heading -> block.content
                    is MdBlock.Bullet -> block.content
                    is MdBlock.Numbered -> block.content
                    MdBlock.Rule -> null
                }
                inline?.spans?.forEach { span ->
                    assertTrue(
                        "span $span outside ${inline.text.length} chars for $sample",
                        span.start in 0..span.end && span.end <= inline.text.length,
                    )
                }
            }
        }
    }

    @Test
    fun `a real reply from Claude renders without literal markers`() {
        // Verbatim from the live emulator run that exposed this bug.
        val reply = """
            Hello! Welcome. I'm here to help you check the quality of your table.

            Let's start with the first picture:

            **1. A picture of the whole table.** Please stand back far enough so the
            entire table fits in the frame — all the legs should be visible.

            Whenever you're ready, send that photo.
        """.trimIndent()

        val rendered = parseMarkdown(reply).joinToString("\n") { block ->
            when (block) {
                is MdBlock.Paragraph -> block.content.text
                is MdBlock.Heading -> block.content.text
                is MdBlock.Bullet -> block.content.text
                is MdBlock.Numbered -> block.content.text
                MdBlock.Rule -> "---"
            }
        }
        assertTrue("asterisks survived: $rendered", !rendered.contains("**"))
        assertTrue(rendered.contains("1. A picture of the whole table."))
    }

    // ---- plain-text flattening, used for history previews ----

    @Test
    fun `flattening strips markers and keeps list numbering`() {
        val raw = "Here is what I check:\n\n- **Joints** whether they are tight\n- *Finish* quality\n\n1. First\n2. Second"
        assertEquals(
            "Here is what I check:\nJoints whether they are tight\nFinish quality\n1. First\n2. Second",
            markdownToPlainText(raw),
        )
    }

    @Test
    fun `flattening leaves no markers in a real reply`() {
        val raw = "Sure! Here's a short list:\n\n- **Joints** - whether the places where wood meets are tight\n- **Finish** - whether it was sanded"
        val flat = markdownToPlainText(raw)
        assertTrue("markers survived: $flat", !flat.contains("**"))
        assertTrue(!flat.trimStart().startsWith("- "))
        assertTrue(flat.contains("Joints"))
    }

    @Test
    fun `flattening then truncating cannot leave a stray marker`() {
        // Truncating first would cut "**Joints**" in half; flattening first cannot.
        val raw = "- **Joints and their tightness matter most of all here**"
        val preview = markdownToPlainText(raw).take(20)
        assertTrue("stray marker in $preview", !preview.contains("*"))
    }
}

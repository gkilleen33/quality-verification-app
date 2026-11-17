package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading Claude's bench reply (v0.6.0, row 5): known kinds only, each once, in order. */
class BenchDraftTest {

    @Test
    fun `the qv-bench block is read, unknown kinds dropped and repeats kept once`() {
        val reply = """
            Here is what I see.

            ```qv-bench
            {"tools":[
              {"kind":"clamps","seen":"two G-clamps on the right"},
              {"kind":"laser_level","seen":"a laser on the shelf"},
              {"kind":"chisels"},
              {"kind":"clamps","seen":"another clamp"}
            ]}
            ```
        """.trimIndent()
        val draft = BenchDraft.parse(reply)!!
        assertEquals(listOf("clamps", "chisels"), draft.tools.map { it.kind })
        assertEquals("two G-clamps on the right", draft.tools.first().seen)
    }

    @Test
    fun `a bare JSON reply is read, and an empty list is a draft with nothing in it`() {
        assertEquals(listOf("hand_saw"), BenchDraft.parse("""{"tools":[{"kind":"hand_saw"}]}""")!!.tools.map { it.kind })
        assertEquals(emptyList<BenchTool>(), BenchDraft.parse("```qv-bench\n{\"tools\":[]}\n```")!!.tools)
    }

    @Test
    fun `a reply with no list is no draft`() {
        assertNull(BenchDraft.parse("I cannot see a bench in this photo."))
        assertNull(BenchDraft.parse("```qv-bench\n{\"tools\": nonsense}\n```"))
    }

    @Test
    fun `a long note on where the tool is is cut`() {
        val long = "x".repeat(500)
        val draft = BenchDraft.parse("""{"tools":[{"kind":"drill","seen":"$long"}]}""")!!
        assertEquals(BenchDraft.SEEN_LIMIT, draft.tools.single().seen.length)
    }
}

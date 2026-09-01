package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.assembleSystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptAssemblyTest {

    @Test
    fun `master and item prompt are separated by a blank line`() {
        val result = assembleSystemPrompt("MASTER", "ITEM")
        assertEquals("MASTER\n\nITEM", result)
    }

    @Test
    fun `blank item prompt leaves no trailing whitespace`() {
        // Item prompts ship empty, so this is the common case in Phase 1.
        assertEquals("MASTER", assembleSystemPrompt("MASTER", ""))
        assertEquals("MASTER", assembleSystemPrompt("MASTER", "   \n  "))
    }

    @Test
    fun `trailing newlines in the fetched master file are trimmed`() {
        assertEquals("MASTER\n\nITEM", assembleSystemPrompt("MASTER\n\n", "ITEM\n"))
    }

    @Test
    fun `internal blank lines in the master prompt are preserved`() {
        val master = "Line one\n\nLine two"
        assertEquals("Line one\n\nLine two", assembleSystemPrompt(master, ""))
    }
}

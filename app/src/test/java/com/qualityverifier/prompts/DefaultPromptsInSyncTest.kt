package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.domain.ItemType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * DefaultPrompts.kt is generated from the files under `prompts/`, and the two drifting
 * apart is exactly the failure this guards: the app would keep shipping an old prompt as
 * its offline fallback while the repo showed the new one.
 *
 * If this fails, run: python3 tools/generate_default_prompts.py
 */
class DefaultPromptsInSyncTest {

    private val repoRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "prompts/master.txt").isFile) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate prompts/master.txt above the test working dir" }
    }

    private fun promptFile(relative: String) = File(repoRoot, "prompts/$relative")

    @Test
    fun `compiled-in master matches prompts master file`() {
        val onDisk = promptFile("master.txt").readText().trimEnd('\n')
        assertEquals(
            "DefaultPrompts.MASTER is stale - regenerate it",
            onDisk,
            DefaultPrompts.MASTER,
        )
    }

    @Test
    fun `compiled-in item prompts match their prompt files`() {
        ItemType.entries.forEach { itemType ->
            val onDisk = promptFile("items/${itemType.id}.txt").readText().trimEnd('\n')
            // Empty files are intentionally absent from the map, which reads back as "".
            val expected = if (onDisk.isBlank()) "" else onDisk
            assertEquals(
                "Compiled-in prompt for ${itemType.id} is stale - regenerate it",
                expected,
                DefaultPrompts.forItem(itemType),
            )
        }
    }

    @Test
    fun `upholstered checklists assess the frame as well as the covering`() {
        // The frame is hidden under padding, so these prompts have to reach it
        // indirectly. This is the requirement most easily lost in a future edit.
        listOf(ItemType.UPHOLSTERED_CHAIR, ItemType.UPHOLSTERED_SOFA).forEach { itemType ->
            val prompt = DefaultPrompts.forItem(itemType)
            listOf(
                "Judging the woodwork:",
                "Judging the upholstery:",
                "exposed",
                "creak",
                "foam",
            ).forEach { expected ->
                assert(prompt.contains(expected)) {
                    "${itemType.id} prompt is missing \"$expected\""
                }
            }
        }
    }

    @Test
    fun `every item type has a prompt file`() {
        ItemType.entries.forEach { itemType ->
            assert(promptFile("items/${itemType.id}.txt").isFile) {
                "Missing prompts/items/${itemType.id}.txt for ${itemType.name}"
            }
        }
    }
}

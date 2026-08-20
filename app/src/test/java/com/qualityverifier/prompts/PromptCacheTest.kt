package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.PromptCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class PromptCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_000_000L

    private fun cache() = PromptCache(temp.root, now = { clock })

    @Test
    fun `missing entry reads as null`() {
        assertNull(cache().read("master.txt"))
    }

    @Test
    fun `freshly written entry has zero age`() {
        val cache = cache()
        cache.write("master.txt", "hello")
        val entry = cache.read("master.txt")
        assertEquals("hello", entry?.text)
        assertEquals(0L, entry?.ageMillis)
    }

    @Test
    fun `age grows with the clock`() {
        val cache = cache()
        cache.write("master.txt", "hello")
        clock += TimeUnit.HOURS.toMillis(30)
        assertEquals(TimeUnit.HOURS.toMillis(30), cache.read("master.txt")?.ageMillis)
    }

    @Test
    fun `nested remote paths are flattened into one directory`() {
        val cache = cache()
        cache.write("items/wooden-table.txt", "table")
        assertEquals("table", cache.read("items/wooden-table.txt")?.text)
        assertTrue(temp.root.listFiles()!!.any { it.name == "items_wooden-table.txt" })
    }

    @Test
    fun `clear removes cached entries`() {
        val cache = cache()
        cache.write("master.txt", "hello")
        cache.clear()
        assertNull(cache.read("master.txt"))
    }

    @Test
    fun `empty text round-trips, since item prompts are legitimately empty`() {
        val cache = cache()
        cache.write("items/other.txt", "")
        assertEquals("", cache.read("items/other.txt")?.text)
    }
}

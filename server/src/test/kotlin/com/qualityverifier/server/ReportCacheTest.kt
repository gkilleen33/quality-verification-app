package com.qualityverifier.server

import com.qualityverifier.server.reports.ReportCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

/** The day-long cache of rendered reports (plan v7 §5.4). */
class ReportCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a report put under a key comes back by that key, and nothing else does`() {
        val cache = ReportCache(folder.newFolder("report-cache"))
        val key = ReportCache.keyOf(listOf("1", "customer", "piece"))

        assertNull(cache.get(key))
        cache.put(key, "pdf".toByteArray())

        assertArrayEquals("pdf".toByteArray(), cache.get(key))
        assertNull(cache.get(ReportCache.keyOf(listOf("1", "customer", "other piece"))))
        // The key is the filename, so anything that is not a hash is refused, never used as a path.
        assertNull(cache.get("../etc/passwd"))
    }

    @Test
    fun `the same inputs give the same key, and any change gives another`() {
        val a = ReportCache.keyOf(listOf("1", "customer", "s1,s2", "label"))
        assertEquals(a, ReportCache.keyOf(listOf("1", "customer", "s1,s2", "label")))
        assertTrue(a != ReportCache.keyOf(listOf("1", "customer", "s1,s2", "renamed")))
        assertTrue(a != ReportCache.keyOf(listOf("2", "customer", "s1,s2", "label")))
    }

    @Test
    fun `the sweep deletes files older than a day and keeps the rest`() {
        val root = folder.newFolder("report-cache")
        val cache = ReportCache(root, ttl = Duration.ofHours(24))
        val old = ReportCache.keyOf(listOf("old"))
        val fresh = ReportCache.keyOf(listOf("fresh"))
        cache.put(old, "old".toByteArray())
        cache.put(fresh, "fresh".toByteArray())
        val now = System.currentTimeMillis()
        File(root, "$old.pdf").setLastModified(now - Duration.ofHours(25).toMillis())

        assertEquals(1, cache.sweep(now))

        assertNull(cache.get(old))
        assertArrayEquals("fresh".toByteArray(), cache.get(fresh))
    }
}

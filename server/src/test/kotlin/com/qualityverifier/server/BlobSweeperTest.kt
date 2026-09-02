package com.qualityverifier.server

import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.blobs.BlobSweeper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

/**
 * Enforcing retention on the disk, not just in the database.
 *
 * `purge_expired()` is SQL and cannot touch the filesystem, so deleting an assessment
 * removed the record of its photographs and kept the photographs. The delete dialog told
 * customers the server copy was "deleted for good" after seven days, which was true of the
 * conversation and false of the pictures.
 *
 * The case worth the most care is the shared one. Blobs are deduplicated by hash, so two
 * customers who photograph the same thing share a file — deleting it because one of them
 * went would take a photo the other still has.
 */
class BlobSweeperTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an unreferenced photo past the grace period is deleted`() = runBlocking {
        val blobs = BlobStore(folder.newFolder())
        val orphan = store(blobs, "an assessment that was deleted")
        age(blobs, orphan, days = 30)

        val result = BlobSweeper({ emptySet() }, blobs).sweep()

        assertEquals(1, result.deleted)
        assertFalse("the bytes must be gone", blobs.exists(orphan))
        assertTrue("and the space reported", result.bytesFreed > 0)
    }

    @Test
    fun `a photo another assessment still refers to is kept`() = runBlocking {
        // The dedup case. One hash, two customers: the file outlives either assessment.
        val blobs = BlobStore(folder.newFolder())
        val shared = store(blobs, "a photo two customers both took")
        age(blobs, shared, days = 30)

        val result = BlobSweeper({ setOf(shared) }, blobs).sweep()

        assertEquals(0, result.deleted)
        assertEquals(1, result.kept)
        assertTrue("a live reference must protect the file", blobs.exists(shared))
    }

    @Test
    fun `a recently uploaded photo is held even with no reference yet`() = runBlocking {
        // Photos are uploaded before the turn that references them. Without the grace
        // period the sweep would delete a photo the customer took minutes ago and is
        // about to submit — and after a failed submission, one they will retry tomorrow.
        val blobs = BlobStore(folder.newFolder())
        val justUploaded = store(blobs, "taken a moment ago, not yet submitted")

        val result = BlobSweeper({ emptySet() }, blobs).sweep()

        assertEquals(0, result.deleted)
        assertEquals(1, result.heldByGrace)
        assertTrue(blobs.exists(justUploaded))
    }

    @Test
    fun `the grace boundary is honoured on both sides`() = runBlocking {
        val blobs = BlobStore(folder.newFolder())
        val old = store(blobs, "eight days old")
        val recent = store(blobs, "six days old")
        age(blobs, old, days = 8)
        age(blobs, recent, days = 6)

        val result = BlobSweeper({ emptySet() }, blobs, grace = Duration.ofDays(7)).sweep()

        assertEquals(1, result.deleted)
        assertEquals(1, result.heldByGrace)
        assertFalse(blobs.exists(old))
        assertTrue(blobs.exists(recent))
    }

    @Test
    fun `a mixed corpus is sorted correctly in one pass`() = runBlocking {
        val blobs = BlobStore(folder.newFolder())
        val keep = store(blobs, "still referenced")
        val sweep = store(blobs, "orphaned and old")
        val hold = store(blobs, "orphaned but new")
        age(blobs, keep, days = 40)
        age(blobs, sweep, days = 40)

        val result = BlobSweeper({ setOf(keep) }, blobs).sweep()

        assertEquals(BlobSweeper.Result(deleted = 1, bytesFreed = result.bytesFreed, heldByGrace = 1, kept = 1), result)
        assertTrue(blobs.exists(keep))
        assertFalse(blobs.exists(sweep))
        assertTrue(blobs.exists(hold))
    }

    @Test
    fun `an empty store is a no-op rather than an error`() = runBlocking {
        val result = BlobSweeper({ emptySet() }, BlobStore(folder.newFolder())).sweep()

        assertEquals(BlobSweeper.Result(0, 0, 0, 0), result)
    }

    @Test
    fun `hash case does not decide whether a photo survives`() = runBlocking {
        // Hashes are written lowercase, but a column read is not a guarantee. Comparing
        // case-sensitively would delete a referenced photo, which is the worst outcome
        // available here.
        val blobs = BlobStore(folder.newFolder())
        val referenced = store(blobs, "referenced, reported in upper case")
        age(blobs, referenced, days = 30)

        val result = BlobSweeper({ setOf(referenced.uppercase()) }, blobs).sweep()

        assertEquals("an upper-case reference must still protect the file", 0, result.deleted)
        assertTrue(blobs.exists(referenced))
    }

    @Test
    fun `the fan-out directory is tidied once its last photo goes`() = runBlocking {
        val root = folder.newFolder()
        val blobs = BlobStore(root)
        val only = store(blobs, "the only photo in its bucket")
        age(blobs, only, days = 30)
        val bucket = blobs.pathFor(only).parentFile

        BlobSweeper({ emptySet() }, blobs).sweep()

        assertFalse("an empty bucket should not be left behind", bucket.exists())
    }

    // ---------------------------------------------------------------- harness

    private fun store(blobs: BlobStore, content: String): String {
        val bytes = content.toByteArray()
        val hash = BlobStore.hash(bytes)
        runBlocking { blobs.put(hash, bytes) }
        return hash
    }

    /** Backdates the file, which is what the grace period actually reads. */
    private fun age(blobs: BlobStore, hash: String, days: Long) {
        val file = blobs.pathFor(hash)
        assertTrue(
            "could not backdate $hash",
            file.setLastModified(System.currentTimeMillis() - Duration.ofDays(days).toMillis()),
        )
    }
}

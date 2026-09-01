package com.qualityverifier.server

import com.qualityverifier.server.blobs.BlobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

class BlobStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = BlobStore(folder.newFolder("blobs"))

    @Test
    fun `a blob round-trips under its own hash`() = runTest {
        val store = store()
        val bytes = "a photograph, pretend".toByteArray()
        val sha = BlobStore.hash(bytes)

        assertEquals(BlobStore.PutResult.Stored, store.put(sha, bytes))
        assertTrue(store.exists(sha))
        assertEquals(bytes.toList(), store.read(sha)?.toList())
    }

    @Test
    fun `storing the same blob twice is a no-op, which is the point`() = runTest {
        // This is what stops the phone re-uploading the same nine photos every turn.
        val store = store()
        val bytes = ByteArray(64) { it.toByte() }
        val sha = BlobStore.hash(bytes)

        assertEquals(BlobStore.PutResult.Stored, store.put(sha, bytes))
        assertEquals(BlobStore.PutResult.AlreadyPresent, store.put(sha, bytes))
    }

    @Test
    fun `bytes that do not match the claimed hash are refused`() = runTest {
        // Not optional. Blobs are global and content-addressed, so a client able to store
        // arbitrary bytes under a hash of its choosing could replace another customer's
        // photograph, and every later request naming that hash would get the wrong image.
        val store = store()
        val honest = "the real photo".toByteArray()
        val sha = BlobStore.hash(honest)

        val result = store.put(sha, "something else entirely".toByteArray())

        assertTrue(result is BlobStore.PutResult.HashMismatch)
        assertTrue("nothing may be written on a mismatch", !store.exists(sha))
    }

    @Test
    fun `an oversized blob is refused before anything is written`() = runTest {
        val store = store()
        val big = ByteArray(BlobStore.MAX_BYTES + 1)
        val sha = BlobStore.hash(big)

        assertEquals(BlobStore.PutResult.TooLarge, store.put(sha, big))
        assertTrue(!store.exists(sha))
    }

    @Test
    fun `a hash that is not 64 hex characters is rejected outright`() {
        // The hash becomes a directory name, so this is the path-traversal guard as much
        // as it is input validation.
        listOf(
            "../../etc/passwd",
            "..",
            "/absolute",
            "abc",
            "g".repeat(64),
            "",
            "a".repeat(63),
            "a".repeat(65),
        ).forEach { bad ->
            assertTrue("accepted $bad", !BlobStore.isValidHash(bad))
        }
        assertTrue(BlobStore.isValidHash("a".repeat(64)))
        assertTrue(BlobStore.isValidHash("A".repeat(64)))
    }

    @Test
    fun `reading or checking a malformed hash never touches the filesystem`() {
        val store = store()
        assertTrue(!store.exists("../../../etc/passwd"))
        assertNull(store.read("../../../etc/passwd"))
    }

    @Test
    fun `a missing blob reads as null rather than throwing`() {
        val store = store()
        assertTrue(!store.exists("b".repeat(64)))
        assertNull(store.read("b".repeat(64)))
    }

    @Test
    fun `hashes fan out over subdirectories`() = runTest {
        // 256 directories, so no single one ends up holding the whole corpus. ext4 copes
        // either way, but an `ls` that takes a minute makes an incident worse.
        val store = store()
        val bytes = "x".toByteArray()
        val sha = BlobStore.hash(bytes)
        store.put(sha, bytes)

        val path = store.pathFor(sha)
        assertEquals(sha.take(2), path.parentFile.name)
        assertEquals("$sha.jpg", path.name)
    }

    @Test
    fun `no partial file is left behind after a successful write`() = runTest {
        val store = store()
        val bytes = "photo".toByteArray()
        val sha = BlobStore.hash(bytes)
        store.put(sha, bytes)

        val leftovers = store.pathFor(sha).parentFile.listFiles()!!.filter {
            it.name.endsWith(".part")
        }
        assertTrue("a .part file survived: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `case in the claimed hash does not create a second copy`() = runTest {
        val store = store()
        val bytes = "case test".toByteArray()
        val sha = BlobStore.hash(bytes)

        assertEquals(BlobStore.PutResult.Stored, store.put(sha.uppercase(), bytes))
        // Stored lowercase regardless of what the client sent, so the same content cannot
        // occupy two paths and defeat the deduplication.
        assertTrue(store.exists(sha))
        assertEquals(BlobStore.PutResult.AlreadyPresent, store.put(sha, bytes))
    }
}

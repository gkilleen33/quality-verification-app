package com.qualityverifier.session

import com.qualityverifier.data.session.FileRunProgressStore
import com.qualityverifier.data.session.RunProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** The file a Kagua plan's photos and answers are kept in while they are collected (v0.7.1, row 57). */
class FileRunProgressStoreTest {

    @Test
    fun `row 57 - a run's photos and answers survive a round trip through the file, skipped ones included`() = runTest {
        val root = Files.createTempDirectory("progress").toFile().apply { deleteOnExit() }
        val store = FileRunProgressStore { sessionId -> root.resolve(sessionId).apply { mkdirs() } }
        val kept = RunProgress(
            sourceMessageId = "plan-1",
            shots = mapOf(0 to "/data/images/s1/a.jpg", 1 to null),
            answers = mapOf(0 to "Solid", 2 to null),
        )
        store.save("s1", kept)
        assertEquals(kept, store.load("s1"))
        // Another session reads nothing of it.
        assertNull(store.load("s2"))
        store.clear("s1")
        assertNull(store.load("s1"))
        root.deleteRecursively()
    }
}

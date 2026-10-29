package com.qualityverifier.fundi.workers

import com.qualityverifier.data.workers.ServerWorker
import com.qualityverifier.data.workers.WorkerChange
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.workers.WorkersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The roster screen's decisions: what a name has to be, and that an edit reaches the
 * repository as the kind of change it is. Storage and sync are the repository's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `adding, renaming and archiving reach the roster`() = runTest {
        val roster = FakeRoster()
        val model = WorkersViewModel(roster, sync = null)

        model.add("Kamau")
        advanceUntilIdle()
        val kamau = roster.workers.single()
        assertEquals("Kamau", kamau.name)

        model.rename(kamau.id, "Kamau Otieno")
        model.archive(kamau.id)
        advanceUntilIdle()

        assertEquals("Kamau Otieno", roster.workers.single().name)
        assertTrue(roster.workers.single().archived)
    }

    // The same bound the server keeps (V23), so a name is never refused later.
    @Test
    fun `a name must be non-blank and within the bound`() {
        val model = WorkersViewModel(FakeRoster(), sync = null)

        assertFalse(model.accepts("   "))
        assertTrue(model.accepts("Kamau"))
        assertTrue(model.accepts("ũ".repeat(60)))
        assertFalse(model.accepts("x".repeat(61)))
    }

    private class FakeRoster : WorkerRepository {
        val workers = mutableListOf<Worker>()
        private val stream = MutableStateFlow<List<Worker>>(emptyList())

        override fun observeWorkers(): Flow<List<Worker>> = stream
        override suspend fun add(name: String): Worker {
            val worker = Worker("w${workers.size + 1}", name, archived = false)
            workers += worker
            stream.value = workers.toList()
            return worker
        }
        override suspend fun rename(workerId: String, name: String) {
            replace(workerId) { it.copy(name = name) }
        }
        override suspend fun archive(workerId: String) {
            replace(workerId) { it.copy(archived = true) }
        }
        private fun replace(workerId: String, change: (Worker) -> Worker) {
            val index = workers.indexOfFirst { it.id == workerId }
            workers[index] = change(workers[index])
            stream.value = workers.toList()
        }

        private fun no(): Nothing = error("the roster screen does not touch this")

        override suspend fun unsynced(): List<WorkerChange> = no()
        override suspend fun markSynced(change: WorkerChange) = no()
        override suspend fun mergeFromServer(workers: List<ServerWorker>) = no()
        override suspend fun workersOf(sessionId: String): List<WorkerSlot> = no()
        override suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun pendingSessions(): List<String> = no()
        override suspend fun clearPending(sessionId: String) = no()
        override suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>) = no()
    }
}

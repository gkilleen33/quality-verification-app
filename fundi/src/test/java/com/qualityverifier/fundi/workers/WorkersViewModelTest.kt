package com.qualityverifier.fundi.workers

import com.qualityverifier.data.workers.PendingSessionSet
import com.qualityverifier.data.workers.ServerWorker
import com.qualityverifier.data.workers.WorkerChange
import com.qualityverifier.data.workers.WorkerNaming
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
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
import org.junit.Assert.assertNull
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
    fun `adding, editing and archiving reach the roster`() = runTest {
        val roster = FakeRoster()
        val model = WorkersViewModel(roster, sync = null)

        model.add(WorkerDetails("Kamau", "+254712345678", Employment.TEMPORARY))
        advanceUntilIdle()
        val kamau = roster.workers.single()
        assertEquals("Kamau", kamau.name)
        assertEquals("+254712345678", kamau.phone)
        assertEquals(Employment.TEMPORARY, kamau.employment)

        // The number removed and the kind changed, in the one edit the form makes.
        model.edit(kamau.id, WorkerDetails("Kamau Otieno", null, Employment.EMPLOYEE))
        model.archive(kamau.id)
        advanceUntilIdle()

        val after = roster.workers.single()
        assertEquals("Kamau Otieno", after.name)
        assertNull(after.phone)
        assertEquals(Employment.EMPLOYEE, after.employment)
        assertTrue(after.archived)
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
        override suspend fun add(details: WorkerDetails): Worker {
            val worker = Worker(
                "w${workers.size + 1}", details.name, archived = false,
                phone = details.phone, employment = details.employment,
            )
            workers += worker
            stream.value = workers.toList()
            return worker
        }
        override suspend fun edit(workerId: String, details: WorkerDetails) {
            replace(workerId) {
                it.copy(name = details.name, phone = details.phone, employment = details.employment)
            }
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
        override fun observeNamedOn(workerId: String): Flow<List<WorkerNaming>> = no()
        override suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun pendingSessions(): List<PendingSessionSet> = no()
        override suspend fun clearPending(pending: PendingSessionSet) = no()
        override suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>) = no()
    }
}

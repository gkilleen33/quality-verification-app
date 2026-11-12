package com.qualityverifier.fundi.workers

import com.qualityverifier.data.workers.PendingSessionSet
import com.qualityverifier.data.workers.ServerWorker
import com.qualityverifier.data.workers.WorkerChange
import com.qualityverifier.data.workers.WorkerNaming
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.workers.WorkerPageViewModel
import com.qualityverifier.fundi.ui.workers.WorkerPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The page's view model over fakes: an archived worker's page still opens, the pieces
 * come with each assessment's purpose and problems, and an edit reaches the roster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerPageViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val summaries = MutableStateFlow(
        listOf(
            SessionSummary(
                id = "s1", itemType = ItemType.WOODEN_TABLE, createdAt = 1_000, updatedAt = 1_000,
                preview = "", messageCount = 2, verdictLevel = VerdictLevel.FAIR, pieceId = "s1", pieceLabel = "Hotel table",
            ),
        ),
    )

    private fun model(roster: FakeRoster) = WorkerPageViewModel(
        workerId = "w1",
        workers = roster,
        summaries = summaries,
        messagesOf = { id ->
            listOf(
                ChatMessage("$id-u", Role.USER, "Here it is."),
                ChatMessage(
                    "$id-a", Role.ASSISTANT,
                    "```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"One joint\",\"defects\":[" +
                        "{\"title\":\"Gap at the back joint\",\"area\":\"structural\",\"severity\":\"moderate\"}]}\n```",
                ),
            )
        },
        intakeCodeOf = { "fundi-en-evaluate" },
        sync = null,
    )

    @Test
    fun `an archived worker's page still opens, with their pieces`() = runTest {
        val roster = FakeRoster(
            Worker("w1", "Kamau", archived = true, phone = "+254712345678", employment = Employment.TEMPORARY),
            namings = listOf(WorkerNaming("s1", WorkPart.JOINERY)),
        )
        val model = model(roster)

        val worker = model.worker.filter { it != null }.first()
        assertEquals("Kamau", worker!!.name)
        assertTrue(worker.archived)

        val pieces = model.shown.filter { it.isNotEmpty() }.first()
        val piece = pieces.single()
        assertEquals("Hotel table", piece.latest.pieceLabel)
        assertEquals(listOf(WorkPart.JOINERY), piece.parts)
        val assessment = piece.assessments.single()
        assertEquals(FundiPurpose.EVALUATE, assessment.purpose)
        assertEquals(listOf("Gap at the back joint"), assessment.problems.map { it.title })
    }

    @Test
    fun `a filter on another piece empties the list, and clearing it brings the piece back`() = runTest {
        val roster = FakeRoster(
            Worker("w1", "Kamau", archived = false),
            namings = listOf(WorkerNaming("s1", null)),
        )
        val model = model(roster)
        assertEquals(1, model.shown.filter { it.isNotEmpty() }.first().size)

        model.setFilter(WorkerPages.Filter(pieceId = "other"))
        assertTrue(model.shown.filter { it.isEmpty() }.first().isEmpty())

        model.setFilter(WorkerPages.Filter())
        assertEquals(1, model.shown.filter { it.isNotEmpty() }.first().size)
    }

    @Test
    fun `editing and archiving from the page reach the roster`() = runTest {
        val roster = FakeRoster(Worker("w1", "Kamau", archived = false))
        val model = model(roster)

        model.edit(WorkerDetails("Kamau Otieno", "+254722000111", Employment.TEMPORARY))
        model.archive()
        advanceUntilIdle()

        val after = roster.workers.single()
        assertEquals("Kamau Otieno", after.name)
        assertEquals("+254722000111", after.phone)
        assertTrue(after.archived)
    }

    private class FakeRoster(
        vararg initial: Worker,
        private val namings: List<WorkerNaming> = emptyList(),
    ) : WorkerRepository {
        val workers = initial.toMutableList()
        private val stream = MutableStateFlow(workers.toList())

        override fun observeWorkers(): Flow<List<Worker>> = stream
        override suspend fun namedOn(workerId: String): List<WorkerNaming> = namings
        override suspend fun edit(workerId: String, details: WorkerDetails) {
            replace(workerId) { it.copy(name = details.name, phone = details.phone, employment = details.employment) }
        }
        override suspend fun archive(workerId: String) {
            replace(workerId) { it.copy(archived = true) }
        }
        private fun replace(workerId: String, change: (Worker) -> Worker) {
            val index = workers.indexOfFirst { it.id == workerId }
            workers[index] = change(workers[index])
            stream.value = workers.toList()
        }

        private fun no(): Nothing = error("the worker page does not touch this")

        override suspend fun add(details: WorkerDetails): Worker = no()
        override suspend fun unsynced(): List<WorkerChange> = no()
        override suspend fun markSynced(change: WorkerChange) = no()
        override suspend fun mergeFromServer(workers: List<ServerWorker>) = no()
        override suspend fun workersOf(sessionId: String): List<WorkerSlot> = no()
        override suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>) = no()
        override suspend fun pendingSessions(): List<PendingSessionSet> = no()
        override suspend fun clearPending(pending: PendingSessionSet) = no()
        override suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>) = no()
    }
}

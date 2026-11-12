package com.qualityverifier.fundi.workers

import com.qualityverifier.data.workers.ServerStudio
import com.qualityverifier.data.workers.StudioChange
import com.qualityverifier.data.workers.StudioRepository
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.StudioDetails
import com.qualityverifier.fundi.ui.workers.StudiosViewModel
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
 * The studio list's decisions: an edit reaches the repository as the kind of change it
 * is, and "linked" is read, never written, on this side. Storage and sync are the
 * repository's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudiosViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `adding, editing and archiving reach the list, and nothing here links an entry`() = runTest {
        val repository = FakeStudios()
        val model = StudiosViewModel(repository, sync = null)

        model.add(StudioDetails("Mama Njeri's workshop", "+254700000001"))
        advanceUntilIdle()
        val added = repository.studios.single()
        assertEquals("Mama Njeri's workshop", added.name)
        assertEquals("+254700000001", added.phone)
        assertFalse("the phone never decides a link", added.linked)

        model.edit(added.id, StudioDetails("Njeri", "+254700000002"))
        model.archive(added.id)
        advanceUntilIdle()

        val after = repository.studios.single()
        assertEquals("Njeri", after.name)
        assertEquals("+254700000002", after.phone)
        assertTrue(after.archived)
    }

    private class FakeStudios : StudioRepository {
        val studios = mutableListOf<Studio>()
        private val stream = MutableStateFlow<List<Studio>>(emptyList())

        override fun observeStudios(): Flow<List<Studio>> = stream
        override suspend fun add(details: StudioDetails): Studio {
            val studio = Studio("s${studios.size + 1}", details.name, details.phone, archived = false)
            studios += studio
            stream.value = studios.toList()
            return studio
        }
        override suspend fun edit(studioId: String, details: StudioDetails) {
            replace(studioId) { it.copy(name = details.name, phone = details.phone) }
        }
        override suspend fun archive(studioId: String) {
            replace(studioId) { it.copy(archived = true, linked = false) }
        }
        private fun replace(studioId: String, change: (Studio) -> Studio) {
            val index = studios.indexOfFirst { it.id == studioId }
            studios[index] = change(studios[index])
            stream.value = studios.toList()
        }

        private fun no(): Nothing = error("the studio screen does not touch this")

        override suspend fun unsynced(): List<StudioChange> = no()
        override suspend fun markSynced(change: StudioChange) = no()
        override suspend fun mergeFromServer(studios: List<ServerStudio>) = no()
    }
}

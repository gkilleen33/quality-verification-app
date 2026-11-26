package com.qualityverifier.fundi.ui.workers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.data.workers.WorkerNaming
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The manager's roster: the people whose work the reports will name.
 *
 * Read from the local database, so the list and every edit work with no signal; the
 * server hears on the next sync. Archived workers stay in the list, greyed, because the
 * pieces they worked on still name them and a manager may want to see who that was.
 */
class WorkersViewModel(
    private val workers: WorkerRepository,
    /** Null in tests, where nothing is sent. */
    private val sync: AssessmentSync?,
    /** Every assessment on this phone, so each worker's pieces can be counted (row 44). */
    summaries: Flow<List<SessionSummary>> = flowOf(emptyList()),
) : ViewModel() {

    /**
     * How many of this phone's pieces name each worker, for "Employee · 3 pieces" (row 44).
     * A piece counts once however many of its assessments name them. A linked worker's own
     * pieces are on the server and not counted here: the row would wait on the network.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pieceCounts: StateFlow<Map<String, Int>> =
        workers.observeWorkers().flatMapLatest { roster ->
            if (roster.isEmpty()) {
                flowOf(emptyList<Pair<String, List<WorkerNaming>>>())
            } else {
                combine(roster.map { worker -> workers.observeNamedOn(worker.id).map { worker.id to it } }) { it.toList() }
            }
        }.combine(summaries) { named, list ->
            val pieceOf = list.associate { it.id to it.piece }
            named.associate { (workerId, namings) -> workerId to namings.mapNotNull { pieceOf[it.sessionId] }.toSet().size }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val roster: StateFlow<List<Worker>> =
        workers.observeWorkers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Workers with a change the server has not had yet, tagged "Not sent yet" (row 6). */
    val unsent: StateFlow<Set<String>> =
        workers.observeUnsentIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** When the last sync failed, if it did, for the line above the list (row 6). */
    val syncStatus: StateFlow<SyncStatus> = sync?.status ?: MutableStateFlow(SyncStatus())

    fun add(details: WorkerDetails) = write { workers.add(details) }

    /** "Try again" in the red box: sends what waits and pulls, as an edit does. */
    fun retry() = write {}

    /** The name, number and kind together, as the form edits them. */
    fun edit(workerId: String, details: WorkerDetails) = write { workers.edit(workerId, details) }

    fun archive(workerId: String) = write { workers.archive(workerId) }

    /** Whether a name can be saved: non-blank and within the server's bound. */
    fun accepts(name: String): Boolean = WorkerNames.fits(name.trim()) && name.isNotBlank()

    private fun write(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            // Sent now when there is signal; otherwise the next open of the pieces list
            // sends it. Failure is silent here: the row is marked unsent either way.
            sync?.let { runCatching { it.run() } }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorkersViewModel(container.workerRepository, container.assessmentSync, container.sessionRepository.observeSummaries())
            }
        }
    }
}

package com.qualityverifier.fundi.ui.workers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerNames
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
) : ViewModel() {

    val roster: StateFlow<List<Worker>> =
        workers.observeWorkers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String) = write { workers.add(name) }

    fun rename(workerId: String, name: String) = write { workers.rename(workerId, name) }

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
            initializer { WorkersViewModel(container.workerRepository, container.assessmentSync) }
        }
    }
}

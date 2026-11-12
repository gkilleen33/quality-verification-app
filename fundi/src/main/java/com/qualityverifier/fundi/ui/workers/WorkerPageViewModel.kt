package com.qualityverifier.fundi.ui.workers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One worker's page: the person, and every piece they were named on with what each
 * assessment of it found. Read from the phone's own tables, so it opens with no signal.
 *
 * The page is a list the manager reads, not a measure: see [WorkerPages]. Edits to the
 * worker (name, number, kind, archive) go through the roster as "My workers" sends them.
 */
class WorkerPageViewModel(
    private val workerId: String,
    private val workers: WorkerRepository,
    summaries: Flow<List<SessionSummary>>,
    private val messagesOf: suspend (sessionId: String) -> List<ChatMessage>,
    /** The stored intake code of an assessment, decoded here; null when none. */
    private val intakeCodeOf: suspend (sessionId: String) -> String?,
    /** Null in tests, where nothing is sent. */
    private val sync: AssessmentSync?,
) : ViewModel() {

    /** The worker, archived or not: an archived worker's page still opens. Null while loading, or if gone. */
    val worker: StateFlow<Worker?> =
        workers.observeWorkers().map { roster -> roster.firstOrNull { it.id == workerId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val all: Flow<List<WorkerPages.Piece>> = summaries.map { list ->
        WorkerPages.build(list, workers.namedOn(workerId)) { summary ->
            WorkerPages.Assessment(
                summary = summary,
                purpose = intakeCodeOf(summary.id)?.let(FundiIntake::decode)?.second,
                problems = WorkerPages.problemsIn(messagesOf(summary.id)),
            )
        }
    }

    /** Every piece, unfiltered: what the piece filter offers to choose from. */
    val pieces: StateFlow<List<WorkerPages.Piece>> =
        all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(WorkerPages.Filter())
    val filter: StateFlow<WorkerPages.Filter> = _filter.asStateFlow()

    /** What the page lists: [pieces] narrowed to [filter]. */
    val shown: StateFlow<List<WorkerPages.Piece>> =
        combine(all, _filter) { pieces, filter -> WorkerPages.filter(pieces, filter) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: WorkerPages.Filter) {
        _filter.value = filter
    }

    fun edit(details: WorkerDetails) = write { workers.edit(workerId, details) }

    fun archive() = write { workers.archive(workerId) }

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
        fun factory(container: AppContainer, workerId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WorkerPageViewModel(
                    workerId = workerId,
                    workers = container.workerRepository,
                    summaries = container.sessionRepository.observeSummaries(),
                    messagesOf = container.sessionRepository::messagesOnce,
                    intakeCodeOf = { id -> container.sessionRepository.startOf(id)?.intakeCode },
                    sync = container.assessmentSync,
                )
            }
        }
    }
}

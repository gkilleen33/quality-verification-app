package com.qualityverifier.fundi.ui.workers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.reports.WorkerPiecesOutcome
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.domain.WorkerPiece
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
    /**
     * The worker's own pieces labelled for this studio, from the server (v0.6.0, rows 4
     * and 11). Null in tests that never ask. Never written to the phone's tables: what the
     * worker's assessments found must not reach this account's own assessments of the
     * piece, which read their earlier findings from those tables.
     */
    private val workerPieces: (suspend (workerId: String) -> WorkerPiecesOutcome)? = null,
) : ViewModel() {

    /** What the server says of the worker's pieces, while the link holds. */
    sealed interface TheirPieces {
        data object NotAsked : TheirPieces
        data object Loading : TheirPieces
        data object Offline : TheirPieces
        data class Loaded(val pieces: List<WorkerPiece>) : TheirPieces
    }

    private val _theirPieces = MutableStateFlow<TheirPieces>(TheirPieces.NotAsked)
    val theirPieces: StateFlow<TheirPieces> = _theirPieces.asStateFlow()

    /**
     * Asks the server for the worker's own pieces labelled for this studio. Called when
     * the page opens on a linked worker, and again after an assessment. Only the worker's
     * pieces are kept: the manager's own pieces naming them are already on this phone.
     */
    fun loadTheirPieces() {
        val fetch = workerPieces ?: return
        if (_theirPieces.value !is TheirPieces.Loaded) _theirPieces.value = TheirPieces.Loading
        viewModelScope.launch {
            _theirPieces.value = when (val outcome = runCatching { fetch(workerId) }.getOrNull()) {
                is WorkerPiecesOutcome.Loaded -> TheirPieces.Loaded(outcome.pieces.pieces.filter { it.theirs })
                // 403: the link ended since the last sync. Nothing of theirs is to be shown.
                is WorkerPiecesOutcome.Refused -> TheirPieces.Loaded(emptyList())
                else -> TheirPieces.Offline
            }
        }
    }

    /** The worker, archived or not: an archived worker's page still opens. Null while loading, or if gone. */
    val worker: StateFlow<Worker?> =
        workers.observeWorkers().map { roster -> roster.firstOrNull { it.id == workerId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every piece, unfiltered: what the piece filter offers to choose from. Null until the
     * first read, so the page does not say "not named on any piece" while it loads.
     *
     * Built once per change to the summaries or to the worker's rows, and shared: the
     * build reads every assessment's replies, and [shown] derives from this rather than
     * reading them again.
     */
    val pieces: StateFlow<List<WorkerPages.Piece>?> =
        combine(summaries, workers.observeNamedOn(workerId)) { list, namings ->
            WorkerPages.build(list, namings) { summary ->
                WorkerPages.Assessment(
                    summary = summary,
                    purpose = intakeCodeOf(summary.id)?.let(FundiIntake::decode)?.second,
                    problems = WorkerPages.problemsIn(messagesOf(summary.id)),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _filter = MutableStateFlow(WorkerPages.Filter())
    val filter: StateFlow<WorkerPages.Filter> = _filter.asStateFlow()

    /** What the page lists: [pieces] narrowed to [filter]. Null while [pieces] loads. */
    val shown: StateFlow<List<WorkerPages.Piece>?> =
        combine(pieces, _filter) { pieces, filter -> pieces?.let { WorkerPages.filter(it, filter) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                    workerPieces = container.reports::workerPieces,
                )
            }
        }
    }
}

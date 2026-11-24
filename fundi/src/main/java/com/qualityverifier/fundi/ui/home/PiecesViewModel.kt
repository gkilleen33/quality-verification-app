package com.qualityverifier.fundi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.PieceApi
import com.qualityverifier.data.sync.RemoteSentReport
import com.qualityverifier.domain.PieceStage
import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.workers.StudioRepository
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.workers.WorkerPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything this maker has put through the app, one row per physical piece, newest first:
 * what Home, "All my pieces" and a piece's page list (v0.7.1, rows 45, 55, 56).
 *
 * Not a nice-to-have. Fundi Bora's loop is assess, repair, **re-assess**, and with no way back
 * to a piece the second half of that loop is unreachable.
 *
 * Read from the local database, so it works with no signal: the assessments are on the
 * handset and the server copy is the backup, not the source.
 */
class PiecesViewModel(
    private val sessions: SessionRepository,
    private val sync: AssessmentSync?,
    private val workers: WorkerRepository,
    /** "Studios I work for", for the studio line and its change (v0.6.0, rows 1 and 2). */
    studioList: StudioRepository? = null,
    summaries: Flow<List<SessionSummary>> = sessions.observeSummaries(),
    private val messagesOf: suspend (String) -> List<ChatMessage> = sessions::messagesOnce,
    private val intakeCodeOf: suspend (String) -> String? = { id -> sessions.startOf(id)?.intakeCode },
    /** A piece's stage and sent reports on the server (rows 55, 56); null in tests that never ask. */
    private val pieceApi: PieceApi? = null,
) : ViewModel() {

    /** Every entry of "Studios I work for", archived ones too, so an old label still has a name. */
    val studios: StateFlow<List<Studio>> =
        (studioList?.observeStudios() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The roster, for "Who worked on this?" and the "My workers" row. */
    val roster: StateFlow<List<Worker>> =
        workers.observeWorkers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each assessment read so far, by id and version; see [pieces]. */
    private val read = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, PieceAssessment>()

    /**
     * One row per piece, newest first, with what each assessment found: the findings in prompt
     * E's improvement list, or the problems an older reply lists, read by the same rules as a
     * worker's page ([WorkerPages.problemsIn]). Null until the first read, so Home does not
     * offer "Assess your first piece" to a maker whose pieces are still loading.
     */
    val pieces: StateFlow<List<PieceSummary>?> =
        summaries.map { list ->
            PieceLines.piecesOf(list) { summary ->
                // Each assessment's replies are read and parsed once per version of it: a sync
                // or a rename re-emits every summary, and most have not changed.
                val key = summary.id to summary.updatedAt
                read[key] ?: PieceAssessment(
                    summary = summary,
                    improvements = WorkerPages.problemsIn(messagesOf(summary.id))?.size,
                    purpose = intakeCodeOf(summary.id)?.let(FundiIntake::decode)?.second,
                ).also { read[key] = it }
            }.map { piece ->
                // The summary itself is always the newest: a rename or a stage changes it
                // without touching the assessment's replies.
                piece.copy(assessments = piece.assessments.map { a -> a.copy(summary = list.first { it.id == a.summary.id }) })
            }
        }
            // Off the main thread: reading and parsing every reply is the slow part of Home.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pieces with a name or studio change still to send, tagged "Not sent yet" (row 6). */
    val unsent: StateFlow<Set<String>> =
        sessions.observeUnsentPieceIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** How the last sync went, for the red box (row 56). */
    val syncStatus: StateFlow<SyncStatus> = sync?.status ?: MutableStateFlow(SyncStatus())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        refresh()
    }

    /**
     * Pushes what the phone owes and pulls what it has not seen, as Kagua's reports list does
     * on open. "Try again" in the red box calls it too.
     */
    fun refresh() {
        val run = sync ?: return
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                runCatching { run.run() }
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * Labels one of this maker's pieces with a studio, or "My own job" with null: the first
     * answer for a piece never asked, or its one correction. Local first; sent at once when
     * there is signal, and otherwise on the next sync.
     */
    fun setStudio(pieceId: String, studioId: String?) {
        viewModelScope.launch {
            sessions.setPieceStudio(pieceId, studioId)
            refresh()
        }
    }

    /** Who is recorded on an assessment, for the edit's starting answer. */
    suspend fun workersOf(sessionId: String): List<WorkerSlot> = workers.workersOf(sessionId)

    /** Replaces who worked on an assessment. Local first; the server hears on the next sync. */
    fun setWorkers(sessionId: String, workedOn: List<WorkerSlot>) {
        viewModelScope.launch {
            workers.setWorkersOf(sessionId, workedOn)
            refresh()
        }
    }

    fun addWorker(details: WorkerDetails, onAdded: (Worker) -> Unit) {
        viewModelScope.launch { onAdded(workers.add(details)) }
    }

    /**
     * Stores the fundi's answer to "Is this piece finished?" on the server (row 55) and, once
     * it is there, on the phone. [onDone] says whether it was stored: online only, since the
     * answer is the server's record.
     */
    fun setStage(pieceId: String, stage: PieceStage, onDone: (Boolean) -> Unit) {
        val api = pieceApi ?: return onDone(false)
        viewModelScope.launch {
            val stored = runCatching { api.setPieceStage(pieceId, stage.id) }.getOrNull()
            val at = stored?.setAt
            if (stored != null && at != null) sessions.applyPieceStage(pieceId, stored.stage, at)
            onDone(stored != null && at != null)
        }
    }

    /** "Reports sent" on a piece's page (row 56), newest first; null when they could not be fetched. */
    suspend fun sentReports(pieceId: String): List<RemoteSentReport>? =
        pieceApi?.let { api -> runCatching { api.sentReports(pieceId) }.getOrNull() }

    /** Renames a piece, or clears the name with null. Written locally; the server hears on the next sync. */
    fun rename(pieceId: String, label: String?) {
        viewModelScope.launch {
            sessions.setPieceLabel(pieceId, label)
            refresh()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PiecesViewModel(
                    container.sessionRepository,
                    container.assessmentSync,
                    container.workerRepository,
                    container.studioRepository,
                    pieceApi = container.pieces,
                )
            }
        }
    }
}

package com.qualityverifier.fundi.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.reports.PreviewOutcome
import com.qualityverifier.data.reports.ReportApi
import com.qualityverifier.data.reports.ReportOutcome
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.WorkerParts
import com.qualityverifier.domain.WorkerSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Send report" on a piece (plan v7 §5.4 to §5.7 and PR 4): who it is for, the steps that
 * choose what the report covers, the preview, the download with progress, then the share
 * sheet.
 *
 * Every report is previewed before it is sent, and the preview is what the server will
 * print: the same blocks, from the same request. "Send" asks for the report with that
 * request, so the file covers what the maker saw even if a newer assessment syncs between.
 *
 * The worker and Me reports read the piece's assessments and the parts named on them from
 * the phone (Room), and send any unsent worker edits before asking for the preview, so the
 * names the preview shows are the names the report will print.
 */
class SendReportViewModel(
    private val pieceId: String,
    private val reports: ReportApi,
    /** Where the next report goes: the cleared reports folder. Null in tests, which share nothing. */
    private val fileFor: (() -> File)?,
    /** Every assessment on the phone; the piece's are picked out here. */
    private val summaries: Flow<List<SessionSummary>> = emptyFlow(),
    /** Who is recorded on an assessment, as the phone holds it. */
    private val workersOf: suspend (sessionId: String) -> List<WorkerSlot> = { emptyList() },
    /**
     * Sends the unsent worker and "Who worked on this?" edits, and says whether all of
     * them reached the server. False keeps the preview closed: the server would print
     * names the maker has since changed.
     */
    private val pushWorkers: suspend () -> Boolean = { true },
) : ViewModel() {

    /** One of the piece's assessments, as "Which assessment?" lists it. */
    data class Option(val sessionId: String, val summary: SessionSummary)

    sealed interface Step {
        /** The first step: who the report is for. */
        data object WhoFor : Step

        /** For a worker: which parts. The parts named on the piece come first, selected. */
        data class ChooseParts(
            val offered: List<WorkPart>,
            val named: Set<WorkPart>,
            val chosen: Set<WorkPart>,
        ) : Step

        /** For a worker, on a piece with two or more assessments with verdicts: issues or fixes. */
        data class IssuesOrFixes(val parts: List<WorkPart>) : Step

        /** Which assessment the report covers, newest first, the newest selected. */
        data class WhichAssessment(
            val kind: ReportKind,
            val parts: List<WorkPart>,
            val options: List<Option>,
            val selected: String,
        ) : Step

        /** The preview is being fetched for [kind]. */
        data class LoadingPreview(val kind: ReportKind) : Step

        data class Preview(val request: ReportRequest, val preview: ReportPreview) : Step

        /** The server is rendering: no bytes yet. */
        data class Preparing(val request: ReportRequest) : Step

        /** Bytes are arriving. [total] is null when the server did not say. */
        data class Downloading(val request: ReportRequest, val received: Long, val total: Long?) : Step

        /**
         * The preview or the download failed. [request] is what to retry with; null when the
         * failure was the preview's, which goes back to the start. [busy] is a server
         * rendering another report; [refused] is a 4xx, which no retry fixes;
         * [workersUnsent] is the one failure with its own words: the edits did not reach
         * the server, and the retry sends them again.
         */
        data class Failed(
            val request: ReportRequest?,
            val busy: Boolean = false,
            val refused: Boolean = false,
            val workersUnsent: Boolean = false,
        ) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.WhoFor)
    val step: StateFlow<Step> = _step.asStateFlow()

    /**
     * The file to hand to the share sheet, once a download is whole. Held until the screen
     * takes it with [shared], so a file that finished while the screen was being recreated
     * still opens the share sheet rather than sitting on disk unshared.
     */
    private val _share = MutableStateFlow<File?>(null)
    val share: StateFlow<File?> = _share.asStateFlow()

    /** The screen has opened the share sheet for [file]. */
    fun shared(file: File) {
        _share.compareAndSet(file, null)
    }

    /**
     * The kinds the first step offers, in the order shown. [ReportKind.PART_ISSUES] stands
     * for "A worker"; issues or fixes is chosen later.
     */
    val offered: List<ReportKind> = listOf(ReportKind.CUSTOMER, ReportKind.PART_ISSUES, ReportKind.FULL)

    /** Counts the times the flow was started over, so an answer to an earlier start is dropped. */
    private var generation = 0

    fun choose(kind: ReportKind) {
        when (kind) {
            ReportKind.CUSTOMER -> loadPreview(ReportRequest(kind = kind.id))
            ReportKind.PART_ISSUES, ReportKind.PART_FIXES -> viewModelScope.launch { openParts() }
            ReportKind.FULL -> viewModelScope.launch { openAssessments(ReportKind.FULL, emptyList()) }
            else -> Unit
        }
    }

    fun togglePart(part: WorkPart) {
        val current = _step.value as? Step.ChooseParts ?: return
        _step.value = current.copy(chosen = if (part in current.chosen) current.chosen - part else current.chosen + part)
    }

    /** "Next" on the parts: issues or fixes when the piece can have a fixes report, else straight on. */
    fun partsChosen() {
        val current = _step.value as? Step.ChooseParts ?: return
        if (current.chosen.isEmpty()) return
        val parts = WorkPart.entries.filter { it in current.chosen }
        viewModelScope.launch {
            // "Fixes" is offered only when the piece has two or more assessments with verdicts.
            if (listed().count { it.verdictLevel != null } >= 2) _step.value = Step.IssuesOrFixes(parts)
            else openAssessments(ReportKind.PART_ISSUES, parts)
        }
    }

    fun chooseIssuesOrFixes(kind: ReportKind) {
        val current = _step.value as? Step.IssuesOrFixes ?: return
        if (!kind.takesParts) return
        viewModelScope.launch { openAssessments(kind, current.parts) }
    }

    fun selectAssessment(sessionId: String) {
        val current = _step.value as? Step.WhichAssessment ?: return
        if (current.options.any { it.sessionId == sessionId }) _step.value = current.copy(selected = sessionId)
    }

    /** "Next" on the assessments: the request is complete; push the worker edits and preview. */
    fun assessmentChosen() {
        val current = _step.value as? Step.WhichAssessment ?: return
        loadPreview(request(current.kind, current.parts, current.selected))
    }

    /** The preview "Send" was tapped on, shown again after the share sheet so it can be sent twice. */
    private var lastPreview: Step.Preview? = null

    /** "Send" on the preview: download, then share. */
    fun send() {
        val current = _step.value as? Step.Preview ?: return
        lastPreview = current
        download(current.request)
    }

    fun retry() {
        when (val current = _step.value) {
            is Step.Failed -> when {
                current.workersUnsent && current.request != null -> loadPreview(current.request)
                current.request != null -> download(current.request)
                else -> _step.value = Step.WhoFor
            }
            else -> Unit
        }
    }

    fun back() {
        generation++
        _step.value = Step.WhoFor
    }

    /** The piece's assessments the home list shows, oldest first. */
    private suspend fun listed(): List<SessionSummary> =
        summaries.first().filter { it.piece == pieceId }.sortedBy { it.createdAt }

    private suspend fun openParts() {
        val pieces = listed()
        val type = pieces.lastOrNull()?.itemType ?: ItemType.OTHER
        // The parts named on the piece's assessments first, selected; then the rest of the
        // type's list, so a part nobody was named on can still be chosen.
        val named = pieces.flatMap { workersOf(it.id) }.mapNotNull { it.part }.toSet()
        val offered = WorkerParts.offered(type, named).sortedBy { if (it in named) 0 else 1 }
        _step.value = Step.ChooseParts(offered = offered, named = named, chosen = named)
    }

    /**
     * "Which assessment?": newest first, the newest selected. For fixes the piece's first
     * assessment with a verdict is left out, since a fixes report needs one before it. A
     * single option skips the step.
     */
    private suspend fun openAssessments(kind: ReportKind, parts: List<WorkPart>) {
        var candidates = listed()
        if (kind == ReportKind.PART_FIXES) {
            val first = candidates.firstOrNull { it.verdictLevel != null }
            candidates = candidates.filter { it.id != first?.id }
        }
        val options = candidates.sortedByDescending { it.createdAt }.map { Option(it.id, it) }
        when (options.size) {
            0 -> _step.value = Step.Failed(request = null, refused = true)
            1 -> loadPreview(request(kind, parts, options.single().sessionId))
            else -> _step.value = Step.WhichAssessment(kind, parts, options, selected = options.first().sessionId)
        }
    }

    private fun request(kind: ReportKind, parts: List<WorkPart>, sessionId: String?) = ReportRequest(
        kind = kind.id,
        parts = parts.map { it.id }.takeIf { kind.takesParts },
        sessionId = sessionId,
    )

    private fun loadPreview(request: ReportRequest) {
        val kind = ReportKind.fromId(request.kind) ?: return
        val mine = ++generation
        _step.value = Step.LoadingPreview(kind)
        viewModelScope.launch {
            // The names on the preview come from the server, so the server must hold the
            // maker's latest edits first; the customer report prints no name and needs none.
            if (kind.isInternal && !pushWorkers()) {
                if (mine == generation) _step.value = Step.Failed(request, workersUnsent = true)
                return@launch
            }
            val outcome = reports.preview(pieceId, request)
            // "Back" while the preview loaded: the maker has left this step.
            if (mine != generation) return@launch
            _step.value = when (outcome) {
                is PreviewOutcome.Loaded -> Step.Preview(request, outcome.preview)
                is PreviewOutcome.Refused -> Step.Failed(request = null, refused = true)
                else -> Step.Failed(request = null)
            }
        }
    }

    private fun download(request: ReportRequest) {
        val into = fileFor?.invoke()
        _step.value = Step.Preparing(request)
        viewModelScope.launch {
            val outcome = if (into == null) ReportOutcome.Unavailable else reports.download(pieceId, request, into) { received, total ->
                // The first byte ends "Preparing": until then the server was rendering.
                _step.value = Step.Downloading(request, received, total)
            }
            when (outcome) {
                is ReportOutcome.Done -> {
                    _step.value = lastPreview ?: Step.WhoFor
                    _share.value = outcome.file
                }
                ReportOutcome.Busy -> _step.value = Step.Failed(request, busy = true)
                is ReportOutcome.Refused -> _step.value = Step.Failed(request, refused = true)
                else -> _step.value = Step.Failed(request)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, pieceId: String, fileFor: () -> File): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val workers = container.workerRepository
                    SendReportViewModel(
                        pieceId = pieceId,
                        reports = container.reports,
                        fileFor = fileFor,
                        summaries = container.sessionRepository.observeSummaries(),
                        workersOf = workers::workersOf,
                        pushWorkers = {
                            // The sync's push sends roster writes, then session worker sets;
                            // anything still pending afterwards did not reach the server.
                            runCatching { container.assessmentSync.push() }
                            workers.unsynced().isEmpty() && workers.pendingSessions().isEmpty()
                        },
                    )
                }
            }
    }
}

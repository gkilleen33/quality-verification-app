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
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Send report" on a piece (plan v7 §5.4 to §5.7): who it is for, the preview, the
 * download with progress, then the share sheet.
 *
 * Every report is previewed before it is sent, and the preview is what the server will
 * print: the same blocks, from the same request. "Send" asks for the report with that
 * request, so the file covers what the maker saw even if a newer assessment syncs between.
 */
class SendReportViewModel(
    private val pieceId: String,
    private val reports: ReportApi,
    /** Where the next report goes: the cleared reports folder. Null in tests, which share nothing. */
    private val fileFor: (() -> File)?,
) : ViewModel() {

    sealed interface Step {
        /** The first step: who the report is for. */
        data object WhoFor : Step

        /** The preview is being fetched for [kind]. */
        data class LoadingPreview(val kind: ReportKind) : Step

        data class Preview(val request: ReportRequest, val preview: ReportPreview) : Step

        /** The server is rendering: no bytes yet. */
        data class Preparing(val request: ReportRequest) : Step

        /** Bytes are arriving. [total] is null when the server did not say. */
        data class Downloading(val request: ReportRequest, val received: Long, val total: Long?) : Step

        /**
         * The preview or the download failed. [request] is what to retry with; null when the
         * failure was the preview's, which goes back to the start.
         */
        data class Failed(val request: ReportRequest?, val busy: Boolean = false) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.WhoFor)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** The file to hand to the share sheet, once a download is whole. */
    private val _share = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val share: SharedFlow<File> = _share.asSharedFlow()

    /** The kinds the first step offers. Only the customer report in this build. */
    val offered: List<ReportKind> = listOf(ReportKind.CUSTOMER)

    fun choose(kind: ReportKind) {
        val request = ReportRequest(kind = kind.id)
        _step.value = Step.LoadingPreview(kind)
        viewModelScope.launch {
            _step.value = when (val outcome = reports.preview(pieceId, request)) {
                is PreviewOutcome.Loaded -> Step.Preview(request, outcome.preview)
                else -> Step.Failed(request = null)
            }
        }
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
            is Step.Failed -> if (current.request != null) download(current.request) else _step.value = Step.WhoFor
            else -> Unit
        }
    }

    fun back() {
        _step.value = Step.WhoFor
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
                    _share.tryEmit(outcome.file)
                }
                ReportOutcome.Busy -> _step.value = Step.Failed(request, busy = true)
                else -> _step.value = Step.Failed(request)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, pieceId: String, fileFor: () -> File): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SendReportViewModel(pieceId, container.reports, fileFor) }
            }
    }
}

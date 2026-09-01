package com.qualityverifier.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReportsViewModel(
    private val repository: SessionRepository,
    private val sync: AssessmentSync,
) : ViewModel() {

    val sessions: StateFlow<List<SessionSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        // Reports is always reached before or after a chat, so this is a reliable place
        // to clean up photos from conversations the user abandoned before sending.
        viewModelScope.launch { repository.pruneOrphanImages() }
        refresh()
    }

    /**
     * Pulls anything this phone has not seen, and flushes deletions the server has not
     * been told about.
     *
     * Runs on open rather than on a pull gesture: the case it exists for is a reinstall or
     * a new handset, where the customer has no reason to suspect there is anything to pull
     * and an empty list looks like lost data.
     */
    fun refresh() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                sync.run()
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * Removes the assessment from this phone and tells the server it was deleted.
     *
     * Local first, and the local delete is not conditional on the server answering: a
     * customer who taps delete must see it gone whether or not they have signal. The
     * server is told through a pending record that survives being offline, so the
     * retention window we promise starts even when the delete happened on a bus.
     */
    fun delete(sessionId: String) {
        viewModelScope.launch {
            repository.recordPendingRemoteDelete(sessionId)
            repository.deleteSession(sessionId)
            sync.run()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel(container.sessionRepository, container.assessmentSync) }
        }
    }
}

package com.qualityverifier.fundi.ui.home

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

/**
 * Everything this maker has put through the app, newest first.
 *
 * Not a nice-to-have. Fundi Bora's loop is assess, repair, **re-assess** — the mockup's
 * before-and-after of the same stool is the product — and with no way back to a piece the
 * second half of that loop is unreachable. The first version of this app had no list at
 * all, which also meant an assessment interrupted by a phone call was simply gone.
 *
 * Read from the local database, so it works with no signal: the assessments are on the
 * handset and the server copy is the backup, not the source.
 */
class PiecesViewModel(
    private val sessions: SessionRepository,
    private val sync: AssessmentSync,
) : ViewModel() {

    val pieces: StateFlow<List<SessionSummary>> =
        sessions.observeSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        refresh()
    }

    /**
     * Pushes what the phone owes (names, and later roster edits) and pulls what it has
     * not seen, as Kagua's reports list does on open. Until this, Fundi Bora never ran
     * the sync: a reinstalled phone showed no pieces, and a rename made offline would
     * have waited for ever.
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
     * Renames a piece from its card, or clears the name with null.
     *
     * Written locally and shown at once; the server hears on the next sync. The list
     * above re-emits from the database, so nothing here has to update it.
     */
    fun rename(pieceId: String, label: String?) {
        viewModelScope.launch {
            sessions.setPieceLabel(pieceId, label)
            // Sent now when there is signal; otherwise the next open sends it.
            refresh()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { PiecesViewModel(container.sessionRepository, container.assessmentSync) }
        }
    }
}

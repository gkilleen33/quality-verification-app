package com.qualityverifier.fundi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

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
class PiecesViewModel(sessions: SessionRepository) : ViewModel() {

    val pieces: StateFlow<List<SessionSummary>> =
        sessions.observeSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { PiecesViewModel(container.sessionRepository) }
        }
    }
}

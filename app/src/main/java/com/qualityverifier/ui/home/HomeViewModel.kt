package com.qualityverifier.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.sync.SyncStep
import com.qualityverifier.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    repository: SessionRepository,
    private val sync: AssessmentSync,
    /**
     * A shop's tablet (row 12) never syncs from Home: a sync would pull the shop account's
     * earlier customers' assessments onto a tablet that wipes each one when it ends.
     */
    private val shopMode: Boolean,
) : ViewModel() {

    private val syncing = MutableStateFlow(false)

    /**
     * "Kagua can't reach the server" (v0.7.1, row 57): the last sync could not read from the
     * server, and no retry is running. Never on a shop's tablet, which does not sync here.
     */
    val offline: StateFlow<Boolean> = combine(sync.status, syncing) { status, running -> HomeOffline.shows(status, running) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Home is the start destination, so this is the one place guaranteed to run
        // once per launch — the right place to clean up photos from conversations the
        // user abandoned before sending anything.
        viewModelScope.launch { repository.pruneOrphanImages() }
        refresh()
    }

    /** Reads the server once, on opening and on "Try again". Nothing on a shop's tablet. */
    fun refresh() {
        if (shopMode || syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            try {
                // Home is the start screen: an unexpected failure is the red box, never a crash.
                runCatching { sync.run() }
            } finally {
                syncing.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, shopMode: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container.sessionRepository, container.assessmentSync, shopMode) }
        }
    }
}

/**
 * What Home has on each kind of phone (v0.7.1, row 57; row 12 for a shop's tablet), apart from
 * the screen so a test can hold it. A personal account: the two tabs and the gear. A shop's
 * tablet, as in v0.7.0: no tabs, no gear, and the owner's controls behind the password.
 */
data class HomeChrome(val tabs: Boolean, val gear: Boolean, val ownerControls: Boolean) {
    companion object {
        fun of(shopMode: Boolean) = HomeChrome(tabs = !shopMode, gear = !shopMode, ownerControls = shopMode)
    }
}

/** When Home's red box shows (row 57), apart from the screen so a test can hold it. */
object HomeOffline {
    /**
     * The last sync did not reach the server to read from it, and no retry is running. A step
     * the server refused is not "can't reach the server", and is not shown.
     */
    fun shows(status: SyncStatus, syncing: Boolean): Boolean =
        !syncing && status.failedAt != null && SyncStep.PULL in status.failed
}

package com.qualityverifier.fundi.ui.workers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.workers.StudioRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.StudioDetails
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Studios I work at": the other side of the roster (V31). Read from the local database,
 * so the list and every edit work with no signal; the server hears on the next sync, and
 * says on the pull which entries the two-sided match holds.
 */
class StudiosViewModel(
    private val studios: StudioRepository,
    /** Null in tests, where nothing is sent. */
    private val sync: AssessmentSync?,
) : ViewModel() {

    val list: StateFlow<List<Studio>> =
        studios.observeStudios()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(details: StudioDetails) = write { studios.add(details) }

    fun edit(studioId: String, details: StudioDetails) = write { studios.edit(studioId, details) }

    fun archive(studioId: String) = write { studios.archive(studioId) }

    private fun write(change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            // Sent now when there is signal, and the pull that follows brings back
            // whether the entry is linked; otherwise the next open of the pieces list.
            sync?.let { runCatching { it.run() } }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StudiosViewModel(container.studioRepository, container.assessmentSync) }
        }
    }
}

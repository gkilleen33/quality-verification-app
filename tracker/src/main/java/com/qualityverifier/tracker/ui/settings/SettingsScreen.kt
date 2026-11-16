package com.qualityverifier.tracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.tracker.data.DeleteOutcome
import com.qualityverifier.tracker.data.TrackerRepository
import com.qualityverifier.tracker.data.TrackerSwitches
import com.qualityverifier.tracker.listen.ListenerAccess
import com.qualityverifier.tracker.trackerContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings: the two switches, notification access, deleting the server's copy, and sign-out
 * (terms section 8). Turning a switch off works at once on this phone, online or not; turning
 * one on asks its question again, since the agreement must be on record first.
 */
class SettingsViewModel(private val repository: TrackerRepository) : ViewModel() {

    val switches: StateFlow<TrackerSwitches> = repository.switches

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun stopReading() {
        viewModelScope.launch { repository.stopReading() }
    }

    fun stopSending() {
        viewModelScope.launch { repository.stopSending() }
    }

    fun deleteFromServer(labels: TrackerLabels) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                _message.value = when (val outcome = repository.deleteFromServer()) {
                    is DeleteOutcome.Deleted ->
                        labels.deletedSummary(outcome.transactions, outcome.unreadDays, outcome.sendingWithdrawn)
                    DeleteOutcome.Offline -> labels.deleteOffline
                    DeleteOutcome.Failed -> labels.deleteFailed
                }
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(repository: TrackerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(repository) }
        }
    }
}

@Composable
fun SettingsScreen(
    labels: TrackerLabels,
    onTurnOnReading: () -> Unit,
    onTurnOnSending: () -> Unit,
    onOpenAccess: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val container = trackerContainer()
    val context = LocalContext.current
    val model: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container.repository))
    val switches by model.switches.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    var accessOn by remember { mutableStateOf(ListenerAccess.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        accessOn = ListenerAccess.isEnabled(context)
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(labels.settingsTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(labels.cancel) }
        }

        SwitchRow(labels.readSwitch, labels.readSwitchHelp, switches.reading) { on ->
            if (on) onTurnOnReading() else model.stopReading()
        }
        SwitchRow(
            labels.sendSwitch,
            if (switches.reading) labels.sendSwitchHelp else labels.sendNeedsRead,
            switches.sending,
            enabled = switches.reading,
        ) { on -> if (on) onTurnOnSending() else model.stopSending() }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(labels.accessRow, style = MaterialTheme.typography.titleMedium)
            Text(if (accessOn) labels.accessGranted else labels.accessMissing, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenAccess) { Text(labels.openAccessSettings) }
        }

        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(labels.deleteHeading, style = MaterialTheme.typography.titleMedium)
            Text(labels.deleteBlurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(
                onClick = { confirming = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.deleteButton)
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(labels.signOut) }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(labels.deleteConfirmTitle) },
            text = { Text(labels.deleteConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    model.deleteFromServer(labels)
                }) { Text(labels.deleteConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(labels.deleteKeep) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

package com.qualityverifier.fundi.ui.terms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.terms.WithdrawOutcome
import com.qualityverifier.di.AppContainer
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.TermsLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Withdrawing the agreement to send data to the United States (terms, section 14).
 *
 * Shown only while the server says the agreement is current, so an account that never
 * agreed, or already withdrew, is not offered a control that does nothing. After the server
 * confirms, the app opens the terms screen: Fundi Bora cannot work without the agreement,
 * and agreeing again is done there, under the same rules as the first time.
 */
class AgreementViewModel(private val api: TermsApi) : ViewModel() {

    /** Null until the server answers, and while it cannot: the section stays hidden. */
    private val _agreed = MutableStateFlow<Boolean?>(null)
    val agreed: StateFlow<Boolean?> = _agreed.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _withdrawn = MutableStateFlow(false)
    val withdrawn: StateFlow<Boolean> = _withdrawn.asStateFlow()

    private var labels: TermsLabels = TermsLabels.ENGLISH

    fun start(labels: TermsLabels) {
        this.labels = labels
        viewModelScope.launch { _agreed.value = api.status()?.transferAgreed }
    }

    fun withdraw() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = null
            try {
                when (api.withdraw()) {
                    WithdrawOutcome.Withdrawn -> {
                        _agreed.value = false
                        _withdrawn.value = true
                    }
                    // Nothing changed: the agreement stands, and the message says so.
                    WithdrawOutcome.Offline -> _message.value = labels.withdrawOffline
                    WithdrawOutcome.Failed -> _message.value = labels.withdrawFailed
                }
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AgreementViewModel(container.terms) }
        }
    }
}

@Composable
fun AgreementSection(onWithdrawn: () -> Unit, labels: TermsLabels = TermsLabels.ENGLISH) {
    val container = fundiContainer()
    val viewModel: AgreementViewModel = viewModel(factory = AgreementViewModel.factory(container))

    val agreed by viewModel.agreed.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val withdrawn by viewModel.withdrawn.collectAsState()
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start(labels) }
    LaunchedEffect(withdrawn) { if (withdrawn) onWithdrawn() }

    if (agreed != true) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(labels.withdrawHeading, style = MaterialTheme.typography.titleMedium)
        Text(
            labels.withdrawBlurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = { confirming = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.withdrawButton)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(labels.withdrawConfirmTitle) },
            text = { Text(labels.withdrawConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.withdraw()
                }) { Text(labels.withdrawConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(labels.withdrawKeep) }
            },
        )
    }
}

package com.qualityverifier.fundi.ui.mpesa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.MpesaLabels
import kotlinx.coroutines.launch

/**
 * The two M-Pesa answers as switches (Nathan, 11 November 2026: the fundi can change either
 * at any time in Settings).
 *
 * Turning one off acts at once, on the phone, signal or not: reading off disables the
 * receiver and stops the scan, and sending off stops uploads; the server is told when it can
 * be (MpesaPreference). Turning one on opens its question, since a yes has to be read and
 * recorded before anything happens.
 */
@Composable
fun MpesaSettingsSection(
    /** The question for the switch turned on. */
    onAsk: (MpesaQuestion) -> Unit,
    labels: MpesaLabels = MpesaLabels.ENGLISH,
) {
    val container = fundiContainer()
    val switches by container.mpesa.switches.collectAsState()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(labels.settingsHeading, style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            title = labels.readSwitch,
            help = labels.readSwitchHelp,
            checked = switches.reading,
            enabled = true,
            onChange = { on ->
                if (on) onAsk(MpesaQuestion.READ) else scope.launch { container.mpesa.stopReading() }
            },
        )
        SwitchRow(
            title = labels.sendSwitch,
            help = if (switches.reading) labels.sendSwitchHelp else labels.sendNeedsRead,
            checked = switches.sending,
            enabled = switches.reading,
            onChange = { on ->
                if (on) onAsk(MpesaQuestion.SEND) else scope.launch { container.mpesa.stopSending() }
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // One toggleable row, so TalkBack reads the title, the help and the state together.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

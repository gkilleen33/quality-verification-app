package com.qualityverifier.tracker.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.tracker.listen.ListenerAccess

/**
 * The guided step to Android's notification access setting, after the two questions.
 *
 * Android does not ask for this in a dialog: the person turns PesaLog on in a settings list.
 * The screen opens that list, re-checks each time the person comes back, and moves on once
 * access is on. On Android 13 and later it also shows the extra step for an app installed
 * from outside an app store, whose setting Android blocks until "Allow restricted settings"
 * is chosen on its App info page.
 */
@Composable
fun AccessScreen(labels: TrackerLabels, onDone: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ListenerAccess.isEnabled(context)) }
    var unopened by remember { mutableStateOf(false) }

    // Re-checked whenever the person returns from Android's settings.
    LifecycleResumeEffect(Unit) {
        enabled = ListenerAccess.isEnabled(context)
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(labels.accessTitle, style = MaterialTheme.typography.headlineSmall)
        Text(labels.accessBody, style = MaterialTheme.typography.bodyLarge)

        if (enabled) {
            Text(labels.accessOn, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(labels.continueLabel) }
            return@Column
        }

        Text(labels.accessSteps, style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = {
                unopened = !ListenerAccess.open(
                    context,
                    ListenerAccess.accessSettings(context),
                    ListenerAccess.listSettings(),
                    ListenerAccess.appDetails(context),
                )
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(labels.openAccessSettings) }
        if (unopened) {
            Text(labels.noSettingsPage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        if (ListenerAccess.restrictedSettingsPossible) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(labels.restrictedTitle, style = MaterialTheme.typography.titleMedium)
                    Text(labels.restrictedSteps, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { ListenerAccess.open(context, ListenerAccess.appDetails(context)) }) {
                        Text(labels.openAppInfo)
                    }
                }
            }
        }

        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(labels.later) }
    }
}

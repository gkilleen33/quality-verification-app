package com.qualityverifier.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.BuildConfig
import com.qualityverifier.R
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.reports.ReportsViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

/**
 * Everything about this phone's copy of the app.
 *
 * There is no account and nothing here leaves the device — Phase 1 has no server to
 * hold a profile on, and collecting a name or a location that nothing consumes would be
 * asking for personal data with no purpose. What lives here is the API key, the prompt
 * cache, and a count of what has been assessed.
 */
@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    onReplaceKey: () -> Unit,
) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reports: ReportsViewModel = viewModel(factory = ReportsViewModel.factory(container))
    val sessions by reports.sessions.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = when (sessions.size) {
                    0 -> "No assessments yet."
                    1 -> "1 assessment on this phone."
                    else -> "${sessions.size} assessments on this phone."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("API key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (container.apiKeyStore.hasKey()) {
                    "A key is saved on this phone, encrypted. Rotate it if it may have " +
                        "been seen by somebody else."
                } else {
                    "No key saved. You will not be able to send messages until you add one."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onReplaceKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(if (container.apiKeyStore.hasKey()) "Rotate API key" else "Add API key") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text("Inspection protocols", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "The photo plans and tests refresh automatically every 24 hours. " +
                    "Refresh now if they were just updated.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        container.promptRepository.clearCache()
                        snackbar.showSnackbar("Protocols will reload on your next message.")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("Refresh protocols") }

            Spacer(Modifier.height(32.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(contentPadding),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

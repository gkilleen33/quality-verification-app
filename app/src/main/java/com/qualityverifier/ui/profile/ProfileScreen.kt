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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Since Phase 2 there is an account, so this is where it is shown and where somebody
 * signs out. The API key section is gone with the key itself, and so is the protocol
 * refresh: the server assembles the system prompt now, so a button here claiming to
 * refresh protocols would refresh a cache the app no longer keeps.
 */
@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    onSignOut: () -> Unit,
) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val reports: ReportsViewModel = viewModel(factory = ReportsViewModel.factory(container))
    val sessions by reports.sessions.collectAsState()
    var signOutRequested by remember { mutableStateOf(false) }

    if (signOutRequested) {
        AlertDialog(
            onDismissRequest = { signOutRequested = false },
            title = { Text("Sign out?") },
            // Confirmed rather than immediate: getting back in needs the password, and on
            // a borrowed phone somebody may not have it to hand.
            text = {
                Text(
                    "You will need your phone number and password to sign in again. " +
                        "Assessments already on this phone stay on it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    signOutRequested = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { signOutRequested = false }) { Text("Stay signed in") }
            },
        )
    }

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

            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Signed in. Your assessments are kept on this phone and on our server, " +
                    "where researchers working on Kagua can open one — including its " +
                    "photos — to check how accurate our advice was.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { signOutRequested = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("Sign out") }

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

package com.qualityverifier.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.rememberAuthLabels

/**
 * Account settings: change a password, or delete the account.
 *
 * Both live behind Profile rather than on it, because both are rare and one is
 * irreversible — a destructive control on a screen somebody opens to check how many
 * assessments they have done is a control they will eventually hit by accident.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAccountDeleted: () -> Unit) {
    val container = appContainer()
    val labels = rememberAuthLabels()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))

    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val changed by viewModel.passwordChanged.collectAsState()
    val deleted by viewModel.accountDeleted.collectAsState()

    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onAccountDeleted() }
    LaunchedEffect(changed) {
        if (changed) {
            current = ""; next = ""; repeat = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(labels.accountSettings) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(labels.changePassword, style = MaterialTheme.typography.titleMedium)
            Text(
                labels.changePasswordBlurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            listOf(
                Triple(labels.currentPassword, current, { v: String -> current = v }),
                Triple(labels.newPassword, next, { v: String -> next = v }),
                Triple(labels.newPasswordAgain, repeat, { v: String -> repeat = v }),
            ).forEach { (label, value, onChange) ->
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(label) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (changed) {
                Text(
                    labels.passwordChanged,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { viewModel.changePassword(current, next, repeat) },
                enabled = !busy && current.isNotBlank() && next.length >= 8 && repeat.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp))
                else Text(labels.changePassword)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            Text(labels.deleteAccount, style = MaterialTheme.typography.titleMedium)
            Text(
                labels.deleteAccountBlurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { confirmingDelete = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(labels.deleteAccount) }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(labels.deleteAccountConfirmTitle) },
            // The 30 days are stated in the confirmation as well as above it. Somebody
            // reaching for an irreversible button is not reading the paragraph.
            text = { Text(labels.deleteAccountConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deleteAccount()
                }) { Text(labels.deleteAccount) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(labels.keepAccount) }
            },
        )
    }
}

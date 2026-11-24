package com.qualityverifier.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.di.AppContainer
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.SettingsMenu
import com.qualityverifier.ui.parts.SettingsMenuItem
import androidx.compose.ui.window.DialogProperties

/**
 * The words of the Settings menu that differ between the apps. Fundi Bora's are English;
 * Kagua's follow the phone's language (rows 46 and 57).
 */
data class AccountMenuWords(
    val title: String,
    val changePassword: String,
    /** "Record my location" in Fundi Bora, "Record a GPS point" in Kagua. */
    val location: String,
    val locationLine: String,
    val on: String,
    val off: String,
    val signOut: String,
    val deleteAccount: String,
    /** What TalkBack reads for the scrim that closes the menu. */
    val close: String,
)

/**
 * The Settings menu under the gear, with the account actions the settings screen used to
 * hold (row 46): change the password (its own screen, [onChangePassword]), the location
 * switch, sign out, and delete the account. [extraItems] go between the location switch and
 * sign-out (Fundi Bora's agreement); [foot] under everything (Kagua's research note and
 * version).
 *
 * The deletion keeps the settings screen's rules: it is confirmed first, the request cannot
 * be left half-way (the busy dialog cannot be dismissed), and a failure says the account was
 * not deleted rather than signing out. Sign-out is confirmed as it was. The location switch
 * writes straight through to [com.qualityverifier.data.location.LocationPreference], and turning it on asks
 * Android for the permission when the app does not hold it, since a switch that is on while
 * the permission is refused would read as recording when nothing is.
 */
@Composable
fun AccountMenu(
    container: AppContainer,
    labels: AuthLabels,
    words: AccountMenuWords,
    onDismiss: () -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit,
    onAccountDeleted: () -> Unit,
    extraItems: List<SettingsMenuItem> = emptyList(),
    foot: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val deleted by viewModel.accountDeleted.collectAsState()
    LaunchedEffect(deleted) { if (deleted) onAccountDeleted() }

    val preference = container.locationPreference
    // What is recorded, not the stored switch alone, as LocationOptIn draws it.
    var recording by remember { mutableStateOf(preference.records) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }

    val dialogOpen = confirmingDelete || confirmingSignOut || busy || message != null
    if (!dialogOpen) {
        SettingsMenu(
            title = words.title,
            closeDescription = words.close,
            onDismiss = onDismiss,
            foot = foot,
            items = listOf(
                SettingsMenuItem(words.changePassword) {
                    onDismiss()
                    onChangePassword()
                },
                SettingsMenuItem(
                    label = words.location,
                    line = words.locationLine,
                    state = Chip.state(recording, words.on, words.off),
                ) {
                    val next = !recording
                    recording = next
                    // Moving the switch is an answer, so Fundi Bora stops asking.
                    preference.answer(next)
                    if (next && !hasLocationPermission(context)) {
                        request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
            ) + extraItems + listOf(
                SettingsMenuItem(words.signOut) { confirmingSignOut = true },
                SettingsMenuItem(words.deleteAccount, danger = true) { confirmingDelete = true },
            ),
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(labels.deleteAccountConfirmTitle) },
            // The 30 days stated in the confirmation: somebody reaching for an irreversible
            // button is not reading a paragraph elsewhere.
            text = { Text(labels.deleteAccountConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deleteAccount()
                }) { Text(labels.deleteAccount) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(labels.keepAccount) } },
        )
    }

    // No way out while the request is in flight: leaving would cancel the coroutine but not
    // the HTTP call, so the server would delete the account while the phone stayed signed in.
    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(labels.deleteAccount) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            },
            confirmButton = {},
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(labels.signOut + "?") },
            text = { Text(labels.signOutConfirmBody) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOut = false
                    onDismiss()
                    onSignOut()
                }) { Text(labels.signOut) }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text(labels.staySignedIn) } },
        )
    }
}

private fun hasLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

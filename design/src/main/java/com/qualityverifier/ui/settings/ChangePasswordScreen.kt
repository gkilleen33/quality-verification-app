package com.qualityverifier.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.qualityverifier.di.AppContainer
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.ButtonState
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.StatusBox
import com.qualityverifier.ui.parts.StatusKind
import com.qualityverifier.ui.parts.lineStyle

/**
 * "Change your password", from the Settings menu (row 46). The form the settings screen held,
 * on its own screen, as the mockups draw it: three fields, a note, and one button.
 *
 * @param backLabel where Back goes, named (P27): "Home".
 */
@Composable
fun ChangePasswordScreen(
    container: AppContainer,
    labels: AuthLabels,
    backLabel: String,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val changed by viewModel.passwordChanged.collectAsState()

    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    LaunchedEffect(changed) {
        if (changed) {
            current = ""; next = ""; repeat = ""
        }
    }
    // Not while the request is in flight, as the settings screen held it.
    BackHandler(enabled = busy) {}

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack = { if (!busy) onBack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle(labels.changePassword)
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
            Text(labels.changePasswordBlurb, style = lineStyle, color = Parts.Muted)
            if (changed) {
                StatusBox(StatusKind.CONFIRMATION, labels.passwordChanged, labels.changePasswordBlurb, "OK", onBack)
            }
            message?.let {
                StatusBox(StatusKind.FAILURE, labels.changePassword, it, "OK", viewModel::dismissMessage)
            }
            PrimaryButton(
                labels.changePassword,
                onClick = { viewModel.changePassword(current, next, repeat) },
                state = if (!busy && current.isNotBlank() && next.length >= 8 && repeat.isNotBlank()) ButtonState.Ready
                else ButtonState(labels.passwordHint),
            )
        }
    }
}

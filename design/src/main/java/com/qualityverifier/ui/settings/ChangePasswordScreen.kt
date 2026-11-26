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
 * What the password screen's greyed button says is missing (P19), one reason at a time, and
 * the red box's title. English, as the settings screen's messages were (SettingsViewModel).
 */
object PasswordWords {
    const val NOT_CHANGED = "Your password was not changed"
    const val SENDING = "Changing your password…"
    const val TYPE_CURRENT = "Type your current password."
    const val TYPE_AGAIN = "Type the new password again."

    /** Null when the button can be pressed. [tooShort] is the screen's own hint for the new password. */
    fun missing(busy: Boolean, current: String, next: String, repeat: String, tooShort: String): String? = when {
        busy -> SENDING
        current.isBlank() -> TYPE_CURRENT
        next.length < 8 -> tooShort
        repeat.isBlank() -> TYPE_AGAIN
        else -> null
    }
}

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
            if (changed) {
                // The note moves into the green box, which says what changed and what follows.
                StatusBox(StatusKind.CONFIRMATION, labels.passwordChanged, labels.changePasswordBlurb, "OK", onBack)
            } else {
                Text(labels.changePasswordBlurb, style = lineStyle, color = Parts.Muted)
            }
            message?.let {
                StatusBox(StatusKind.FAILURE, PasswordWords.NOT_CHANGED, it, "OK", viewModel::dismissMessage)
            }
            PrimaryButton(
                labels.changePassword,
                onClick = { viewModel.changePassword(current, next, repeat) },
                state = ButtonState(PasswordWords.missing(busy, current, next, repeat, labels.passwordHint)),
            )
        }
    }
}

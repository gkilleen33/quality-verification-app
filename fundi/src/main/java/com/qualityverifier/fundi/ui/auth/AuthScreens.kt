package com.qualityverifier.fundi.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.ui.phone.KenyanPhoneField

/** The shortest password the server will take. Refused there too; this only saves a round trip. */
private const val MIN_PASSWORD = 8

@Composable
fun FundiSignInScreen(onSignedIn: () -> Unit, onRegister: () -> Unit) {
    val container = fundiContainer()
    val viewModel: FundiAuthViewModel =
        viewModel(factory = FundiAuthViewModel.factory(container))

    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    // The nine digits after a fixed +254. A leading 0 cannot be typed, so the number on
    // screen is the number the server stores; KenyanPhone.toE164 makes the one sent.
    var phone by remember { mutableStateOf("") }
    val e164 = KenyanPhone.toE164(phone)
    var password by remember { mutableStateOf("") }

    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }

    AuthColumn {
        Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))

        PhoneField(phone) { phone = it }
        PasswordField(password, ImeAction.Done) { password = it }
        ErrorText(error)

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            label = "Sign in",
            busy = busy,
            enabled = !busy && e164 != null && password.isNotBlank(),
            onClick = { e164?.let { viewModel.signIn(it, password) } },
        )
        TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
            Text("I have an invite code")
        }
    }
}

/**
 * The first sign-up screen: invite code, name and number, then Proceed.
 *
 * The code decides which app the account is for — see `V16` — so a Kagua code typed in
 * here produces a Kagua account, and the server will then refuse it at every Fundi Bora
 * endpoint. Nothing on this screen can prevent that, because the client is deliberately
 * not told what the code grants; the refusal is the honest place for it to surface.
 *
 * Proceed asks the server nothing: it checks the number's format and that the name and code
 * are filled in, then opens the password screen. A server call here would tell anyone who
 * asked whether a number has an account. When phone verification exists, Proceed is where
 * the code is sent.
 */
@Composable
fun FundiPhoneScreen(viewModel: FundiAuthViewModel, onProceed: () -> Unit, onSignIn: () -> Unit) {
    val held by viewModel.details.collectAsState()

    // Kept across a trip to the password screen and back, so "Change details" edits them.
    var inviteCode by remember { mutableStateOf(held?.code.orEmpty()) }
    var name by remember { mutableStateOf(held?.name.orEmpty()) }
    var phone by remember { mutableStateOf(held?.phone?.removePrefix(KenyanPhone.COUNTRY_CODE).orEmpty()) }
    val e164 = KenyanPhone.toE164(phone)

    AuthColumn {
        Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
        Text(
            "You need an invite code from us to create an account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = inviteCode,
            // Uppercased as they type: the codes are printed uppercase and the server
            // compares exactly, so a lowercase paste would be refused as unusable — the
            // one refusal message that deliberately says nothing about why.
            onValueChange = { inviteCode = it.uppercase().trim() },
            label = { Text("Invite code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PhoneField(phone) { phone = it }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            label = "Proceed",
            busy = false,
            enabled = inviteCode.isNotBlank() && name.isNotBlank() && e164 != null,
            onClick = {
                e164?.let {
                    viewModel.holdDetails(SignUpDetails(code = inviteCode, name = name.trim(), phone = it))
                    onProceed()
                }
            },
        )
        TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text("I already have an account")
        }
    }
}

/**
 * The second sign-up screen: the password, then Create account. The terms screen follows.
 *
 * A refusal (an unusable code, a number that already has an account) shows here with
 * "Change details", which returns to the phone screen with what was typed.
 */
@Composable
fun FundiPasswordScreen(viewModel: FundiAuthViewModel, onRegistered: () -> Unit, onChangeDetails: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()
    val held by viewModel.details.collectAsState()

    var password by remember { mutableStateOf("") }

    LaunchedEffect(signedIn) { if (signedIn) onRegistered() }

    AuthColumn {
        Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))

        if (held == null) {
            // Android closed the app in the background and the phone screen's answers went
            // with it; they are never saved to disk beside a password.
            Text(
                "Please enter your details again.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton(label = "Change details", busy = false, enabled = true, onClick = onChangeDetails)
        } else {
            PasswordField(password, ImeAction.Done) { password = it }
            Text(
                "At least $MIN_PASSWORD characters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorText(error)

            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                label = "Create account",
                busy = busy,
                enabled = !busy && password.length >= MIN_PASSWORD,
                onClick = { viewModel.registerHeld(password) },
            )
            TextButton(onClick = onChangeDetails, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Change details")
            }
        }
    }
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            // Edge to edge from MainActivity, so the wordmark needs the inset or it
            // hides under the notification bar.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * The phone field both apps share, with its English wording because the rest of these
 * screens is in English. One copy of the rule and its messages serves both apps.
 */
@Composable
private fun PhoneField(digits: String, onChange: (String) -> Unit) {
    KenyanPhoneField(
        labels = AuthLabels.ENGLISH,
        digits = digits,
        onDigitsChange = onChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, ime: ImeAction, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ime,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(message: String?) {
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(label)
    }
}

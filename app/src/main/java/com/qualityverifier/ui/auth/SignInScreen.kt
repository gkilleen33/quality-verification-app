package com.qualityverifier.ui.auth

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
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.phone.KenyanPhoneField
import com.qualityverifier.ui.rememberAuthLabels

/**
 * Signing in.
 *
 * The start destination for anybody who has an account, which is why it exists at all:
 * before it, an invite code was the only way in, so sixty days of not opening the app
 * locked somebody out with no route back.
 */
@Composable
fun SignInScreen(onSignedIn: () -> Unit, onRegister: () -> Unit) {
    val container = appContainer()
    val labels = rememberAuthLabels()
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container))

    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    // The nine digits after +254; KenyanPhone.toE164 makes the number that is sent.
    var phone by remember { mutableStateOf("") }
    val e164 = KenyanPhone.toE164(phone)
    var password by remember { mutableStateOf("") }

    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("KAGUA", style = MaterialTheme.typography.headlineLarge)
        Text(
            labels.tagline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // A fixed +254 and the nine digits after it. A leading 0 cannot be typed, so the
        // number on screen is the number the server stores.
        KenyanPhoneField(
            labels = labels,
            digits = phone,
            onDigitsChange = { phone = it },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(labels.passwordLabel) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { e164?.let { viewModel.signIn(it, password) } },
            enabled = !busy && e164 != null && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.signIn)
        }

        TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
            Text(labels.goToRegister)
        }
    }
}

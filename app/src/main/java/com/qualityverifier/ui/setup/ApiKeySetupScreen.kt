package com.qualityverifier.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qualityverifier.data.keys.looksLikeAnthropicKey
import com.qualityverifier.ui.appContainer

/**
 * First-launch screen. Phase 1 only — Phase 2 replaces it with login/register.
 */
@Composable
fun ApiKeySetupScreen(
    onSaved: () -> Unit,
    title: String = "Welcome",
    saveLabel: String = "Save and continue",
) {
    val container = appContainer()
    var key by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Enter your Anthropic API key to start checking furniture quality. " +
                "It is stored encrypted on this phone and never shared.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = key,
            onValueChange = {
                // Pasted keys often carry a trailing newline; strip it silently.
                key = it.replace("\n", "").replace("\r", "")
                error = null
            },
            label = { Text("API key") },
            placeholder = { Text("sk-ant-...") },
            singleLine = true,
            isError = error != null,
            visualTransformation =
                if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        // Advisory only. Key formats change, so this never blocks saving.
        if (error == null && key.isNotBlank() && !looksLikeAnthropicKey(key)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This doesn't look like an Anthropic key — they usually start with \"sk-ant-\". " +
                    "You can still save it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val trimmed = key.trim()
                if (trimmed.isEmpty()) {
                    error = "Please enter your API key."
                } else {
                    container.apiKeyStore.set(trimmed)
                    onSaved()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(saveLabel)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Your key is saved using Android's encrypted storage, protected by this " +
                "device's hardware keystore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

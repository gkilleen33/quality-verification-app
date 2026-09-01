package com.qualityverifier.ui.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.ui.appContainer

/**
 * Creating an account, which needs an invite code.
 *
 * The location step is optional and deliberately worded as such: it is only useful if
 * they happen to be standing in the business as they register, and a fix taken anywhere
 * else would put a workshop on the wrong part of the map. The accuracy comes with it,
 * because a reading good to two kilometres and one good to five metres are
 * indistinguishable once stored as a point.
 */
@Composable
fun RegisterScreen(onRegistered: () -> Unit, onSignIn: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container))

    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    var invite by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("+256") }
    var password by remember { mutableStateOf("") }
    var isBusiness by remember { mutableStateOf(false) }
    var businessName by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<CapturedLocation?>(null) }
    var locationNotice by remember { mutableStateOf<String?>(null) }

    fun capture() {
        // Each outcome says something different, because "it didn't work" tells somebody
        // standing in a workshop nothing about what to do next.
        when (val fix = lastKnownFix(context)) {
            is FixResult.Ok -> {
                location = fix.location
                locationNotice = null
            }
            FixResult.None ->
                locationNotice = "No location yet. Step outside for a moment and try again."
            FixResult.Stale ->
                locationNotice = "That reading is out of date and might be from somewhere " +
                    "else. Wait a moment and try again."
            FixResult.TooCoarse ->
                locationNotice = "The location is only accurate to a few kilometres, which " +
                    "is too rough to save. Try again outdoors."
        }
    }

    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) capture()
        else locationNotice = "Location is off, so this step is skipped. You can register without it."
    }

    LaunchedEffect(signedIn) { if (signedIn) onRegistered() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Create your account", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your name is how we address you in reports you share. If you tell us your " +
                "business, we can group your assessments together.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = invite,
            onValueChange = { invite = it.trim() },
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
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } },
            label = { Text("Phone number") },
            supportingText = { Text("With your country code, e.g. +256700123456") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Choose a password") },
            supportingText = { Text("At least 8 characters. Length matters more than symbols.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text("Are you buying for yourself or for a business?")
        listOf(false to "For myself", true to "For a business").forEach { (business, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = isBusiness == business) { isBusiness = business }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isBusiness == business, onClick = { isBusiness = business })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (isBusiness) {
            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text("Business name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "If you are at the business right now, you can save its location. This is " +
                    "optional. We use it only to place your business on a map of workshops; " +
                    "we do not track where you are afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) capture()
                    else requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    location?.let { "Location saved (within ${it.accuracyMetres.toInt()}m)" }
                        ?: "Save this location — only if you are here now",
                )
            }
            locationNotice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                viewModel.register(
                    inviteCode = invite,
                    phone = phone,
                    password = password,
                    name = name,
                    isBusiness = isBusiness,
                    businessName = businessName,
                    location = location,
                )
            },
            enabled = !busy && invite.isNotBlank() && name.isNotBlank() &&
                phone.length > 6 && password.length >= 8 &&
                (!isBusiness || businessName.isNotBlank()),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Create account")
        }

        TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text("I already have an account — sign in")
        }
    }
}

/**
 * The last fix the system already has, rather than asking for a new one.
 *
 * Requesting a fresh fix means holding the GPS on and waiting — sometimes a minute,
 * indoors often forever. The cached position is instant and good enough to place a
 * workshop.
 *
 * **But it has to be recent.** A cached fix carries no hint of its age, and on a device
 * that has not moved the provider will happily hand back one from yesterday, from
 * wherever the phone was then. The first device test of this screen stored Mountain View
 * for a business in Kampala, and the button said "Location saved (within 5m)" — truthful
 * about precision and silent about the point being fifteen thousand kilometres wrong.
 * Accuracy and freshness are different properties and a stale fix is the more dangerous
 * one, because the accuracy figure makes it look trustworthy.
 *
 * So a fix older than [MAX_FIX_AGE_MILLIS] is refused, and the customer is told to try
 * again rather than being given a confident wrong answer.
 */
@SuppressLint("MissingPermission")
private fun lastKnownFix(context: android.content.Context): FixResult {
    val manager = context.getSystemService(LocationManager::class.java)
        ?: return FixResult.None
    val best: Location? = runCatching {
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull()
    val fix = best ?: return FixResult.None

    val age = System.currentTimeMillis() - fix.time
    if (age > MAX_FIX_AGE_MILLIS) return FixResult.Stale

    val accuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else return FixResult.None
    // Coarser than the server will store, so say so here rather than at submission.
    if (accuracy > 5000) return FixResult.TooCoarse
    return FixResult.Ok(CapturedLocation(fix.latitude, fix.longitude, accuracy))
}

private sealed interface FixResult {
    data class Ok(val location: CapturedLocation) : FixResult
    /** Nothing cached, or a fix with no accuracy attached. */
    data object None : FixResult
    data object Stale : FixResult
    data object TooCoarse : FixResult
}

/**
 * Two minutes. Long enough that opening the screen and reading it does not invalidate a
 * fix taken on arrival, short enough that the phone cannot have travelled meaningfully.
 */
private const val MAX_FIX_AGE_MILLIS = 2 * 60 * 1000L

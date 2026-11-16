package com.qualityverifier.tracker.ui.auth

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.auth.AuthResult
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.tracker.TrackerContainer
import com.qualityverifier.tracker.data.AccountCheck
import com.qualityverifier.tracker.trackerContainer
import com.qualityverifier.ui.phone.KenyanPhoneField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The shortest password the server will take. */
private const val MIN_PASSWORD = 8

/** The phone screen's answers, held for the password screen that follows it. Never saved to disk. */
data class SignUpDetails(val code: String, val name: String, val phone: String)

/**
 * Signing in and registering, through :core's AuthClient, as Fundi Bora does
 * (FundiAuthViewModel). A PesaLog account is a Fundi Bora account: sign-in takes the same
 * number and password, and an invite code registers whichever account the code is for.
 *
 * After the server accepts the account, PesaLog asks whether it can use it: a Kagua account
 * (a Kagua invite code, or a buyer's number) is refused by every PesaLog route, so it is
 * signed out again here with a message, rather than left stuck on a screen that cannot load.
 */
class AuthViewModel(
    private val container: TrackerContainer,
    private val labels: TrackerLabels,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _details = MutableStateFlow<SignUpDetails?>(null)
    val details: StateFlow<SignUpDetails?> = _details.asStateFlow()

    fun holdDetails(details: SignUpDetails) {
        _error.value = null
        _details.value = details
    }

    /** After a sign-out: the next sign-in starts from nothing, and sign-in does not move on by itself. */
    fun reset() {
        _signedIn.value = false
        _error.value = null
        _details.value = null
    }

    fun signIn(phone: String, password: String) = attempt { container.authClient.signIn(phone, password) }

    fun registerHeld(password: String) {
        val held = _details.value ?: return
        attempt {
            container.authClient.register(
                inviteCode = held.code,
                phone = held.phone,
                password = password,
                name = held.name,
                accountType = "individual",
                businessName = null,
                latitude = null,
                longitude = null,
                accuracyMetres = null,
            )
        }
    }

    private fun attempt(block: suspend () -> AuthResult) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                when (val result = block()) {
                    AuthResult.Success -> when (container.repository.accountCheck()) {
                        AccountCheck.NOT_A_FUNDI -> {
                            container.signOut()
                            _error.value = labels.notAFundiAccount
                        }
                        // Not knowing is not a reason to refuse: the next request says otherwise if it must.
                        AccountCheck.USABLE, AccountCheck.UNKNOWN -> {
                            // An account that agreed on another phone keeps its answers here.
                            runCatching { container.repository.adoptServerAnswers() }
                            _signedIn.value = true
                        }
                    }
                    is AuthResult.Failure -> _error.value = result.message
                }
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(container: TrackerContainer, labels: TrackerLabels): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(container, labels) }
        }
    }
}

@Composable
fun SignInScreen(viewModel: AuthViewModel, labels: TrackerLabels, onSignedIn: () -> Unit, onRegister: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    var phone by remember { mutableStateOf("") }
    val e164 = KenyanPhone.toE164(phone)
    var password by remember { mutableStateOf("") }

    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }

    AuthColumn {
        Text(labels.appName, style = MaterialTheme.typography.displaySmall)
        Text(labels.signInBlurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        PhoneField(phone) { phone = it }
        PasswordField(labels.password, password) { password = it }
        ErrorText(error)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(labels.signIn, busy, enabled = !busy && e164 != null && password.isNotBlank()) {
            e164?.let { viewModel.signIn(it, password) }
        }
        TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) { Text(labels.haveInvite) }
    }
}

@Composable
fun PhoneScreen(viewModel: AuthViewModel, labels: TrackerLabels, onProceed: () -> Unit, onSignIn: () -> Unit) {
    val held by viewModel.details.collectAsState()
    var inviteCode by remember { mutableStateOf(held?.code.orEmpty()) }
    var name by remember { mutableStateOf(held?.name.orEmpty()) }
    var phone by remember { mutableStateOf(held?.phone?.removePrefix(KenyanPhone.COUNTRY_CODE).orEmpty()) }
    val e164 = KenyanPhone.toE164(phone)

    AuthColumn {
        Text(labels.appName, style = MaterialTheme.typography.displaySmall)
        Text(labels.inviteBlurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = inviteCode,
            // Upper-cased as typed: the codes are printed so, and the server compares exactly.
            onValueChange = { inviteCode = it.uppercase().trim() },
            label = { Text(labels.inviteCode) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(labels.yourName) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PhoneField(phone) { phone = it }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(labels.proceed, busy = false, enabled = inviteCode.isNotBlank() && name.isNotBlank() && e164 != null) {
            e164?.let {
                viewModel.holdDetails(SignUpDetails(code = inviteCode, name = name.trim(), phone = it))
                onProceed()
            }
        }
        TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text(labels.haveAccount) }
    }
}

@Composable
fun PasswordScreen(viewModel: AuthViewModel, labels: TrackerLabels, onRegistered: () -> Unit, onChangeDetails: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()
    val held by viewModel.details.collectAsState()
    var password by remember { mutableStateOf("") }

    LaunchedEffect(signedIn) { if (signedIn) onRegistered() }

    AuthColumn {
        Text(labels.appName, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        if (held == null) {
            Text(labels.enterAgain, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(labels.changeDetails, busy = false, enabled = true, onClick = onChangeDetails)
        } else {
            PasswordField(labels.password, password) { password = it }
            Text(labels.passwordHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ErrorText(error)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(labels.createAccount, busy, enabled = !busy && password.length >= MIN_PASSWORD) {
                viewModel.registerHeld(password)
            }
            TextButton(onClick = onChangeDetails, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(labels.changeDetails)
            }
        }
    }
}

@Composable
fun rememberAuthViewModel(labels: TrackerLabels): AuthViewModel {
    val container = trackerContainer()
    return viewModel(factory = AuthViewModel.factory(container, labels))
}

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun PhoneField(digits: String, onChange: (String) -> Unit) {
    KenyanPhoneField(labels = AuthLabels.ENGLISH, digits = digits, onDigitsChange = onChange, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(message: String?) {
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(label)
    }
}

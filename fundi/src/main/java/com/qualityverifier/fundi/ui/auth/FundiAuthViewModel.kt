package com.qualityverifier.fundi.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.auth.AuthClient
import com.qualityverifier.data.auth.AuthResult
import com.qualityverifier.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Signing in and registering, for a maker.
 *
 * Its own rather than Kagua's, because registration genuinely differs: there is no
 * business-or-personal question here. That field is about a shop lending its handset to
 * walk-in customers, which is a Kagua behaviour, and offering it to a fundi would be
 * asking a question whose answer nothing reads.
 *
 * What is *not* duplicated is anything that matters: [AuthClient] and the token store are
 * `:core`'s, so rotation, single-flight refresh and the theft rule have one
 * implementation. What is duplicated is a busy flag and an error string.
 */
class FundiAuthViewModel(private val auth: AuthClient) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * What the phone screen collected, held for the password screen that follows it.
     *
     * In the view model, which both sign-up screens share, and never in saved state: the
     * password screen adds the password to these and sends them together. After process
     * death they are gone and the password screen sends the maker back to the phone screen.
     */
    private val _details = MutableStateFlow<SignUpDetails?>(null)
    val details: StateFlow<SignUpDetails?> = _details.asStateFlow()

    fun holdDetails(details: SignUpDetails) {
        _error.value = null
        _details.value = details
    }

    /** Creates the account from the held details and [password]. Does nothing without them. */
    fun registerHeld(password: String) {
        val held = _details.value ?: return
        register(held.code, held.phone, password, held.name)
    }

    fun signIn(phone: String, password: String) = attempt {
        auth.signIn(phone, password)
    }

    fun register(inviteCode: String, phone: String, password: String, name: String) = attempt {
        auth.register(
            inviteCode = inviteCode,
            phone = phone,
            password = password,
            name = name,
            // Always individual. account_type records whether a business lets customers
            // use its phone; a workshop does not, and the workshop's own details go to
            // fundi_workshops in the setup flow rather than here.
            accountType = "individual",
            businessName = null,
            // No location at registration. Kagua asks a business where its premises are;
            // a fundi's location arrives per assessment, if they left that switched on.
            latitude = null,
            longitude = null,
            accuracyMetres = null,
        )
    }

    private fun attempt(block: suspend () -> AuthResult) {
        if (_busy.value) return
        // Set before launching: checked outside the coroutine and set inside it, the
        // guard leans on viewModelScope dispatching immediately, and two taps in one
        // frame would both register or both sign in.
        _busy.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                when (val result = block()) {
                    AuthResult.Success -> _signedIn.value = true
                    is AuthResult.Failure -> _error.value = result.message
                }
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FundiAuthViewModel(container.authClient) }
        }
    }
}

/** The phone screen's answers: the invite code, the maker's name, and the number in E.164. */
data class SignUpDetails(val code: String, val name: String, val phone: String)

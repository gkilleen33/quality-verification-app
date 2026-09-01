package com.qualityverifier.ui.auth

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

/** Where the customer is, so a business account can be put on a map of workshops later. */
data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
)

class AuthViewModel(private val auth: AuthClient) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    fun signIn(phone: String, password: String) = attempt {
        auth.signIn(phone, password)
    }

    fun register(
        inviteCode: String,
        phone: String,
        password: String,
        name: String,
        isBusiness: Boolean,
        businessName: String,
        location: CapturedLocation?,
    ) = attempt {
        auth.register(
            inviteCode = inviteCode,
            phone = phone,
            password = password,
            name = name,
            accountType = if (isBusiness) "business" else "individual",
            businessName = businessName.takeIf { isBusiness },
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMetres = location?.accuracyMetres,
        )
    }

    private fun attempt(block: suspend () -> AuthResult) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
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
            initializer { AuthViewModel(container.authClient) }
        }
    }
}

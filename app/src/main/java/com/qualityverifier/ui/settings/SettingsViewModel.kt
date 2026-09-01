package com.qualityverifier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.sync.AccountActions
import com.qualityverifier.data.sync.PasswordOutcome
import com.qualityverifier.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val account: AccountActions) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when the password changed, so the screen can say every other device signed out. */
    private val _passwordChanged = MutableStateFlow(false)
    val passwordChanged: StateFlow<Boolean> = _passwordChanged.asStateFlow()

    /** Set when the account is gone, so the caller signs out and returns to sign-in. */
    private val _accountDeleted = MutableStateFlow(false)
    val accountDeleted: StateFlow<Boolean> = _accountDeleted.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    fun changePassword(current: String, new: String, repeat: String) {
        if (_busy.value) return
        // Checked here rather than only on the server: a typo in the confirmation should
        // not cost a round trip, and should certainly not change the password.
        if (new != repeat) {
            _message.value = "The two new passwords don't match."
            return
        }
        if (new.length < 8) {
            _message.value = "The new password must be at least 8 characters."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                _message.value = when (account.changePassword(current, new)) {
                    PasswordOutcome.Changed -> {
                        _passwordChanged.value = true
                        null
                    }
                    PasswordOutcome.WrongPassword -> "That current password isn't right."
                    PasswordOutcome.TooShort -> "The new password must be at least 8 characters."
                    PasswordOutcome.Unavailable ->
                        "Could not reach our server. Please try again shortly."
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteAccount() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                if (account.deleteAccount()) {
                    _accountDeleted.value = true
                } else {
                    // Not signed out locally on failure: doing so would leave somebody
                    // believing their account was deleted when it was not.
                    _message.value = "Could not reach our server. Your account was not deleted."
                }
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container.account) }
        }
    }
}

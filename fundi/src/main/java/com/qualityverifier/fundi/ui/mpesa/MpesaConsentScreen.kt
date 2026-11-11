package com.qualityverifier.fundi.ui.mpesa

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.mpesa.ConsentOutcome
import com.qualityverifier.data.mpesa.MpesaAnswers
import com.qualityverifier.di.AppContainer
import com.qualityverifier.fundi.FundiBoraApp
import com.qualityverifier.fundi.mpesa.MpesaReader
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.MpesaConsentText
import com.qualityverifier.text.MpesaLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of the two questions is on screen. */
enum class MpesaQuestion(val id: String) {
    READ("read"),
    SEND("send");

    companion object {
        fun fromId(id: String?): MpesaQuestion = entries.firstOrNull { it.id == id } ?: READ
    }
}

/**
 * Grady's two questions, one after the other (Nathan, 11 November 2026): may the app read
 * M-Pesa messages and keep them on the phone; then, only after a yes, may it send them to
 * the server. Each has "Not now". Asked at sign-up between the terms and setup, from the
 * Finances tab, and from the switches in Settings.
 *
 * A yes is recorded on the server before anything is read or sent, with the hash of the
 * words on screen ([MpesaConsentText]). Reading also needs the SMS permission, asked only
 * after the yes; refused, the agreement is not recorded and reading stays off.
 */
class MpesaConsentViewModel(
    private val answers: MpesaAnswers,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _question = MutableStateFlow(MpesaQuestion.READ)
    val question: StateFlow<MpesaQuestion> = _question.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True once the fundi has answered the last question this screen asks. */
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var labels = MpesaLabels.ENGLISH
    private var started = false

    /**
     * Opens on [first]. Sending is never asked of a fundi who is not reading: the server
     * would refuse it, and the question would promise something that cannot happen.
     */
    fun start(first: MpesaQuestion, labels: MpesaLabels) {
        if (started) return
        started = true
        this.labels = labels
        _question.value = if (first == MpesaQuestion.SEND && !answers.switches.value.reading) MpesaQuestion.READ else first
    }

    /** "Not now": nothing is recorded, and the screen is finished. */
    fun notNow() {
        _done.value = true
    }

    /** The SMS permission was refused after a yes to reading: reading stays off. */
    fun permissionRefused() {
        _message.value = labels.permissionRefused
    }

    /** A yes to reading, with the permission granted. */
    fun agreeToReading() = answer {
        // The moment of the yes: messages received after it are read, and none before.
        when (answers.agreeToReading(now())) {
            ConsentOutcome.Agreed -> _question.value = MpesaQuestion.SEND
            ConsentOutcome.Offline -> _message.value = labels.offline
            ConsentOutcome.Failed -> _message.value = labels.failed
        }
    }

    fun agreeToSending() = answer {
        when (answers.agreeToSending()) {
            ConsentOutcome.Agreed -> _done.value = true
            ConsentOutcome.Offline -> _message.value = labels.offline
            ConsentOutcome.Failed -> _message.value = labels.failed
        }
    }

    private fun answer(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MpesaConsentViewModel(container.mpesa) }
        }
    }
}

@Composable
fun MpesaConsentScreen(
    first: MpesaQuestion,
    onDone: () -> Unit,
    labels: MpesaLabels = MpesaLabels.ENGLISH,
) {
    val container = fundiContainer()
    val context = LocalContext.current
    val model: MpesaConsentViewModel = viewModel(factory = MpesaConsentViewModel.factory(container))
    val question by model.question.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val done by model.done.collectAsState()

    LaunchedEffect(Unit) { model.start(first, labels) }
    LaunchedEffect(done) {
        if (done) {
            // If sending was just agreed, send now rather than at the next sync; in the
            // background, so the next screen does not wait on the network.
            (context.applicationContext as FundiBoraApp).background.launch { runCatching { container.mpesa.push() } }
            onDone()
        }
    }

    // Asked only after the yes. Both or nothing (MpesaReader.PERMISSIONS).
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (MpesaReader.PERMISSIONS.all { granted[it] == true }) model.agreeToReading() else model.permissionRefused()
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (title, text) = when (question) {
            MpesaQuestion.READ -> labels.readTitle to MpesaConsentText.READ
            MpesaQuestion.SEND -> labels.sendTitle to MpesaConsentText.SEND
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        // The agreed words exactly as MpesaConsentText holds them: the hash sent with a yes is
        // of this text, so nothing may be added to or dropped from it on the way to the screen.
        Text(text, style = MaterialTheme.typography.bodyLarge)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                when (question) {
                    MpesaQuestion.READ ->
                        if (MpesaReader.hasPermission(context)) model.agreeToReading()
                        else permissions.launch(MpesaReader.PERMISSIONS)
                    MpesaQuestion.SEND -> model.agreeToSending()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.yes)
        }
        OutlinedButton(
            onClick = model::notNow,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(labels.notNow) }
    }
}

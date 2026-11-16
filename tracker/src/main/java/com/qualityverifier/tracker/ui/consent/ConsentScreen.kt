package com.qualityverifier.tracker.ui.consent

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
import com.qualityverifier.text.TrackerConsentText
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.tracker.TrackerApp
import com.qualityverifier.tracker.data.ConsentOutcome
import com.qualityverifier.tracker.data.TrackerRepository
import com.qualityverifier.tracker.trackerContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of the two questions is on screen. */
enum class Question(val id: String) {
    READ("read"),
    SEND("send");

    companion object {
        fun fromId(id: String?): Question = entries.firstOrNull { it.id == id } ?: READ
    }
}

/**
 * PesaLog's two questions, one after the other, as Fundi Bora asks its M-Pesa questions:
 * may it read mobile money notifications and keep the payments on this phone; then, only
 * after a yes, may it send them to the research team. Each has "Not now".
 *
 * A yes is recorded on the server, with the hash of the words on screen
 * ([TrackerConsentText]), before anything is read or sent. No Android permission is asked
 * here: notification access is its own step, after these questions, because Android keeps it
 * in its settings rather than in a dialog.
 */
class ConsentViewModel(
    private val repository: TrackerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _question = MutableStateFlow(Question.READ)
    val question: StateFlow<Question> = _question.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var labels: TrackerLabels? = null
    private var started = false

    /** Opens on [first]. Sending is never asked of a person who is not reading. */
    fun start(first: Question, labels: TrackerLabels) {
        if (started) return
        started = true
        this.labels = labels
        val reading = repository.switches.value.reading
        _question.value = when {
            first == Question.SEND && !reading -> Question.READ
            first == Question.READ && reading -> Question.SEND
            else -> first
        }
    }

    fun notNow() {
        _done.value = true
    }

    fun agreeToReading() = answer {
        // The moment of the yes: messages received after it are read, and none before.
        when (repository.agreeToReading(now())) {
            ConsentOutcome.Agreed -> _question.value = Question.SEND
            ConsentOutcome.Offline -> _message.value = labels?.offline
            ConsentOutcome.Failed -> _message.value = labels?.failed
        }
    }

    fun agreeToSending() = answer {
        when (repository.agreeToSending()) {
            ConsentOutcome.Agreed -> _done.value = true
            ConsentOutcome.Offline -> _message.value = labels?.offline
            ConsentOutcome.Failed -> _message.value = labels?.failed
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
        fun factory(repository: TrackerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConsentViewModel(repository) }
        }
    }
}

@Composable
fun ConsentScreen(first: Question, labels: TrackerLabels, onDone: () -> Unit) {
    val container = trackerContainer()
    val context = LocalContext.current
    val model: ConsentViewModel = viewModel(factory = ConsentViewModel.factory(container.repository))
    val question by model.question.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val done by model.done.collectAsState()

    LaunchedEffect(Unit) { model.start(first, labels) }
    LaunchedEffect(done) {
        if (done) {
            // If sending was just agreed, send what is waiting now, in the background.
            (context.applicationContext as TrackerApp).background.launch { runCatching { container.repository.push() } }
            onDone()
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val (title, text) = when (question) {
            Question.READ -> labels.readTitle to TrackerConsentText.READ
            Question.SEND -> labels.sendTitle to TrackerConsentText.SEND
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        // The agreed words exactly as TrackerConsentText holds them: the hash sent with a yes
        // is of this text, so nothing may be added to or dropped from it on the way here.
        Text(text, style = MaterialTheme.typography.bodyLarge)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                when (question) {
                    Question.READ -> model.agreeToReading()
                    Question.SEND -> model.agreeToSending()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.yes)
        }
        OutlinedButton(onClick = model::notNow, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(labels.notNow)
        }
    }
}

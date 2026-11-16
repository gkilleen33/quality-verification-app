package com.qualityverifier.tracker.ui.transaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.text.MpesaLabels
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.tracker.data.TrackerEntry
import com.qualityverifier.tracker.data.TrackerRepository
import com.qualityverifier.tracker.trackerContainer
import com.qualityverifier.tracker.ui.home.asTime
import com.qualityverifier.tracker.ui.home.counterpartyOf
import com.qualityverifier.tracker.ui.home.signedAmount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One payment: what the message said, and what the person says it was for, a category and a
 * comment, as Fundi Bora's transaction screen without the piece, which is Fundi Bora's. Saved
 * on the phone at once; sent with the next push when sending is on.
 */
class TransactionViewModel(
    private val repository: TrackerRepository,
    network: String,
    code: String,
) : ViewModel() {

    val entry: StateFlow<TrackerEntry?> =
        repository.observeEntry(network, code).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun save(network: String, code: String, category: MpesaCategory?, comment: String?, then: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            repository.annotate(network, code, category, comment)
            then()
            runCatching { repository.push() }
        }
    }

    companion object {
        fun factory(repository: TrackerRepository, network: String, code: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { TransactionViewModel(repository, network, code) } }
    }
}

@Composable
fun TransactionScreen(network: String, code: String, labels: TrackerLabels, onDone: () -> Unit) {
    val container = trackerContainer()
    val model: TransactionViewModel = viewModel(
        key = "$network/$code",
        factory = TransactionViewModel.factory(container.repository, network, code),
    )
    val entry by model.entry.collectAsState()
    val saving by model.saving.collectAsState()
    val money = MpesaLabels.ENGLISH

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(labels.transactionTitle, style = MaterialTheme.typography.headlineSmall)
        val current = entry
        if (current == null) {
            Text(labels.notFound, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDone) { Text(labels.cancel) }
            return@Column
        }
        val record = current.entry.record
        // Saveable, so a rotation keeps the answers; keyed on the payment so another never inherits them.
        var category by rememberSaveable(network, code) { mutableStateOf(current.entry.category?.id) }
        var comment by rememberSaveable(network, code) { mutableStateOf(current.entry.comment.orEmpty()) }

        Text(money.type(record.type), style = MaterialTheme.typography.titleMedium)
        Text(signedAmount(record.direction, record.amountKes, money), style = MaterialTheme.typography.headlineMedium)
        counterpartyOf(current)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(record.occurredAt.asTime(), style = MaterialTheme.typography.bodyMedium)
        Text("${labels.networkLabel}: ${current.network.displayName}", style = MaterialTheme.typography.bodySmall)
        Text("${labels.codeLabel}: ${record.code}", style = MaterialTheme.typography.bodySmall)
        record.feeKes?.let { Text("${labels.feeLabel}: ${money.kes(it)}", style = MaterialTheme.typography.bodySmall) }
        record.balanceAfterKes?.let { Text("${labels.balanceLabel}: ${money.kes(it)}", style = MaterialTheme.typography.bodySmall) }

        Text(labels.whatFor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.selectableGroup()) {
            MpesaCategory.choicesFor(record.direction).forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).selectable(
                        selected = category == choice.id,
                        // A second tap clears it: "no category" has no button of its own.
                        onClick = { category = if (category == choice.id) null else choice.id },
                        role = Role.RadioButton,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = category == choice.id, onClick = null)
                    Text(money.category(choice), Modifier.padding(start = 12.dp))
                }
            }
        }

        Text(labels.details, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = comment,
            // The server's bound (V33); the field stops there rather than the server refusing.
            onValueChange = { if (it.codePointCount(0, it.length) <= MAX_COMMENT) comment = it },
            placeholder = { Text(labels.detailsHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDone, enabled = !saving, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(labels.cancel)
            }
            Button(
                onClick = { model.save(network, code, MpesaCategory.fromId(category), comment, then = onDone) },
                enabled = !saving,
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(labels.save) }
        }
    }
}

private const val MAX_COMMENT = 500

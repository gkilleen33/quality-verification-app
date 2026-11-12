package com.qualityverifier.fundi.ui.mpesa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.qualityverifier.data.mpesa.MpesaRepository
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.mpesa.MpesaEntry
import com.qualityverifier.text.MpesaLabels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A piece the transaction can be assigned to, as the home list names it. */
data class PieceChoice(val id: String, val label: String)

/**
 * One transaction: what M-Pesa said, and what the fundi says it was for (Grady's
 * description): a category, a piece, and a comment. Saved on the phone at once; sent with
 * the next push when sending is on.
 */
class MpesaTransactionViewModel(
    private val mpesa: MpesaRepository,
    sessions: SessionRepository,
    code: String,
) : ViewModel() {

    /** Null while loading, and if the row is gone (a different account signed in meanwhile). */
    val entry: StateFlow<MpesaEntry?> =
        mpesa.observeEntry(code).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One per physical piece, newest first, named as on the home list. */
    val pieces: StateFlow<List<PieceChoice>> = sessions.observeSummaries()
        .map { summaries ->
            summaries.groupBy { it.piece }
                .map { (piece, assessments) ->
                    val latest = assessments.maxBy { it.updatedAt }
                    latest.updatedAt to PieceChoice(piece, latest.pieceLabel ?: latest.itemType.homeLabel)
                }
                .sortedByDescending { it.first }
                .map { it.second }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)

    /** True from the first tap on Save: a second tap would pop a second screen. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun save(code: String, category: MpesaCategory?, pieceId: String?, comment: String?, then: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            mpesa.annotate(code, category, pieceId, comment)
            then()
            // Sent now when there is signal and sending is on; otherwise at the next sync.
            runCatching { mpesa.push() }
        }
    }

    companion object {
        fun factory(container: AppContainer, code: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { MpesaTransactionViewModel(container.mpesa, container.sessionRepository, code) }
        }
    }
}

@Composable
fun MpesaTransactionScreen(
    code: String,
    onDone: () -> Unit,
    labels: MpesaLabels = MpesaLabels.ENGLISH,
) {
    val container = fundiContainer()
    val model: MpesaTransactionViewModel = viewModel(factory = MpesaTransactionViewModel.factory(container, code))
    val entry by model.entry.collectAsState()
    val pieces by model.pieces.collectAsState()
    val saving by model.saving.collectAsState()

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
        TransactionForm(current, pieces, labels, saving, onSave = { category, piece, comment ->
            model.save(code, category, piece, comment, then = onDone)
        }, onCancel = onDone)
    }
}

@Composable
private fun TransactionForm(
    entry: MpesaEntry,
    pieces: List<PieceChoice>,
    labels: MpesaLabels,
    saving: Boolean,
    onSave: (MpesaCategory?, String?, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val record = entry.record
    // Saveable, so a rotation halfway through keeps the answers; keyed on the code so a
    // different transaction never inherits them.
    var category by rememberSaveable(record.code) { mutableStateOf(entry.category?.id) }
    var pieceId by rememberSaveable(record.code) { mutableStateOf(entry.pieceId) }
    var comment by rememberSaveable(record.code) { mutableStateOf(entry.comment.orEmpty()) }
    var choosingPiece by remember { mutableStateOf(false) }
    LaunchedEffect(record.code) { choosingPiece = false }

    Text(labels.type(record.type), style = MaterialTheme.typography.titleMedium)
    Text(signedAmount(record.direction, record.amountKes, labels), style = MaterialTheme.typography.headlineMedium)
    counterpartyOf(entry)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    Text(record.occurredAt.asTime(), style = MaterialTheme.typography.bodyMedium)
    Text("${labels.codeLabel}: ${record.code}", style = MaterialTheme.typography.bodySmall)
    record.feeKes?.let { Text("${labels.feeLabel}: ${labels.kes(it)}", style = MaterialTheme.typography.bodySmall) }
    record.balanceAfterKes?.let {
        Text("${labels.balanceLabel}: ${labels.kes(it)}", style = MaterialTheme.typography.bodySmall)
    }

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
                Text(labels.category(choice), Modifier.padding(start = 12.dp))
            }
        }
    }

    Text(labels.whichPiece, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Box {
        OutlinedButton(onClick = { choosingPiece = true }, modifier = Modifier.fillMaxWidth()) {
            Text(pieces.firstOrNull { it.id == pieceId }?.label ?: labels.noPiece)
        }
        DropdownMenu(expanded = choosingPiece, onDismissRequest = { choosingPiece = false }) {
            DropdownMenuItem(text = { Text(labels.noPiece) }, onClick = {
                pieceId = null
                choosingPiece = false
            })
            pieces.forEach { piece ->
                DropdownMenuItem(text = { Text(piece.label) }, onClick = {
                    pieceId = piece.id
                    choosingPiece = false
                })
            }
        }
    }

    Text(labels.details, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    OutlinedTextField(
        value = comment,
        // The server's bound (V29); the field stops there rather than the server refusing.
        onValueChange = { if (it.codePointCount(0, it.length) <= MAX_COMMENT) comment = it },
        placeholder = { Text(labels.detailsHint) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f).height(56.dp)) {
            Text(labels.cancel)
        }
        Button(
            onClick = { onSave(MpesaCategory.fromId(category), pieceId, comment) },
            enabled = !saving,
            modifier = Modifier.weight(1f).height(56.dp),
        ) { Text(labels.save) }
    }
}

private const val MAX_COMMENT = 500

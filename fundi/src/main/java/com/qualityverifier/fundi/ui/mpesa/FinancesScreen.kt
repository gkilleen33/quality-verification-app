package com.qualityverifier.fundi.ui.mpesa

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.data.mpesa.MpesaRepository
import com.qualityverifier.data.mpesa.MpesaSwitches
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.fundi.mpesa.MpesaReader
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.mpesa.MpesaEntry
import com.qualityverifier.mpesa.MpesaSums
import com.qualityverifier.mpesa.MpesaTotals
import com.qualityverifier.text.MpesaLabels
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The two periods the tab totals (Nathan's build prompt: this month and all time). */
data class FinancesTotals(val thisMonth: MpesaTotals, val allTime: MpesaTotals)

/**
 * The fundi's M-Pesa transactions, newest first, and their totals. Read from the phone, so
 * it works with no signal; the server copy, when sending is on, is for the research record.
 */
class FinancesViewModel(
    private val mpesa: MpesaRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val switches: StateFlow<MpesaSwitches> = mpesa.switches

    val entries: StateFlow<List<MpesaEntry>> =
        mpesa.observeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totals: StateFlow<FinancesTotals> = mpesa.observeEntries()
        .map { all -> FinancesTotals(MpesaSums.totals(all, since = MpesaSums.monthStart(now())), MpesaSums.totals(all)) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FinancesTotals(MpesaSums.totals(emptyList()), MpesaSums.totals(emptyList())),
        )

    /** Sends what the phone owes, as the pieces list does on open. Nothing, unless sending is on. */
    fun refresh() {
        viewModelScope.launch { runCatching { mpesa.push() } }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FinancesViewModel(container.mpesa) }
        }
    }
}

@Composable
fun FinancesScreen(
    /** A transaction's screen: what it was for, which piece, a comment. */
    onOpen: (code: String) -> Unit,
    /** The M-Pesa questions, from the first. */
    onTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
    labels: MpesaLabels = MpesaLabels.ENGLISH,
) {
    val container = fundiContainer()
    val context = LocalContext.current
    val model: FinancesViewModel = viewModel(factory = FinancesViewModel.factory(container))
    val switches by model.switches.collectAsState()
    val entries by model.entries.collectAsState()
    val totals by model.totals.collectAsState()

    LaunchedEffect(Unit) { model.refresh() }

    // Checked on each composition: the fundi can revoke it in Android's settings at any time.
    var permitted by remember { mutableStateOf(MpesaReader.hasPermission(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = MpesaReader.hasPermission(context)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(labels.financesTitle, style = MaterialTheme.typography.displaySmall)
        }

        if (!switches.reading) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(labels.offHeading, style = MaterialTheme.typography.titleMedium)
                    Text(labels.offBody, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(labels.turnOn) }
                }
            }
        } else if (!permitted) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(labels.permissionMissing, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { askPermission.launch(MpesaReader.PERMISSIONS) }) { Text(labels.allowPermission) }
                }
            }
        }

        if (switches.reading && !switches.sending) {
            item {
                Text(
                    labels.notSending,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Shown whenever there is anything stored, reading on or off: what was read before
        // a fundi turned reading off is still theirs to look at.
        if (switches.reading || entries.isNotEmpty()) {
            item { TotalsCard(labels.thisMonth, totals.thisMonth, labels) }
            item { TotalsCard(labels.allTime, totals.allTime, labels) }
        }
        if (switches.reading && entries.isEmpty()) {
            item {
                Text(labels.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(entries, key = { it.record.code }) { entry ->
            TransactionRow(entry, labels, onClick = { onOpen(entry.record.code) })
        }
    }
}

@Composable
private fun TotalsCard(period: String, totals: MpesaTotals, labels: MpesaLabels) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(period, style = MaterialTheme.typography.titleMedium)
            Text("${labels.moneyIn}: ${labels.countAndAmount(totals.moneyIn.count, totals.moneyIn.amountKes)}")
            Text("${labels.moneyOut}: ${labels.countAndAmount(totals.moneyOut.count, totals.moneyOut.amountKes)}")
        }
    }
}

@Composable
private fun TransactionRow(entry: MpesaEntry, labels: MpesaLabels, onClick: () -> Unit) {
    val record = entry.record
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.type(record.type), style = MaterialTheme.typography.titleSmall)
                Text(signedAmount(record.direction, record.amountKes, labels), style = MaterialTheme.typography.titleSmall)
            }
            counterpartyOf(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                listOf(record.occurredAt.asTime(), entry.category?.let(labels::category) ?: labels.noCategory)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "+KSh 1,000" for money in, "−KSh 500" for money out, the bare amount otherwise. */
internal fun signedAmount(direction: MpesaDirection, amountKes: Double, labels: MpesaLabels): String = when (direction) {
    MpesaDirection.IN -> "+" + labels.kes(amountKes)
    MpesaDirection.OUT -> "−" + labels.kes(amountKes)
    MpesaDirection.OTHER -> labels.kes(amountKes)
}

/** The name as M-Pesa printed it, or the number when there is no name. */
internal fun counterpartyOf(entry: MpesaEntry): String? =
    entry.record.counterpartyName ?: entry.record.counterpartyPhone?.let { "+$it" }

/** "12 Nov, 10:15", Nairobi time, like every other date in this project. */
internal fun Long.asTime(): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.UK)
        .apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }
        .format(Date(this))

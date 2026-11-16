package com.qualityverifier.tracker.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.mpesa.MpesaSums
import com.qualityverifier.mpesa.MpesaTotals
import com.qualityverifier.text.MpesaLabels
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.tracker.data.ListenerStatus
import com.qualityverifier.tracker.data.TrackerEntry
import com.qualityverifier.tracker.data.TrackerRepository
import com.qualityverifier.tracker.data.TrackerSwitches
import com.qualityverifier.tracker.listen.ListenerAccess
import com.qualityverifier.tracker.trackerContainer
import com.qualityverifier.ui.update.UpdateBanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** This month's and all time's money in and out. */
data class HomeTotals(val thisMonth: MpesaTotals, val allTime: MpesaTotals)

/**
 * The log and its totals, read from the phone so it works with no signal, and the listener's
 * state, so a person or a field officer can see at a glance that PesaLog has stopped reading.
 */
class HomeViewModel(
    private val repository: TrackerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val switches: StateFlow<TrackerSwitches> = repository.switches
    val listener: StateFlow<ListenerStatus> = repository.listener

    val entries: StateFlow<List<TrackerEntry>> =
        repository.observeEntries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totals: StateFlow<HomeTotals> = repository.observeEntries()
        .map { all ->
            val entries = all.map { it.entry }
            HomeTotals(MpesaSums.totals(entries, since = MpesaSums.monthStart(now())), MpesaSums.totals(entries))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeTotals(MpesaSums.totals(emptyList()), MpesaSums.totals(emptyList())),
        )

    /** Sends what the phone owes. Nothing, unless sending is on. */
    fun refresh() {
        viewModelScope.launch { runCatching { repository.push() } }
    }

    companion object {
        fun factory(repository: TrackerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}

@Composable
fun HomeScreen(
    labels: TrackerLabels,
    onOpen: (network: String, code: String) -> Unit,
    onTurnOnReading: () -> Unit,
    onTurnOnAccess: () -> Unit,
    onSettings: () -> Unit,
) {
    val container = trackerContainer()
    val context = LocalContext.current
    val model: HomeViewModel = viewModel(factory = HomeViewModel.factory(container.repository))
    val switches by model.switches.collectAsState()
    val listener by model.listener.collectAsState()
    val entries by model.entries.collectAsState()
    val totals by model.totals.collectAsState()
    val update by container.appVersionCheck.state.collectAsState()
    val money = MpesaLabels.ENGLISH

    // Android's setting can change while PesaLog is in the background, so it is read again on return.
    var accessOn by remember { mutableStateOf(ListenerAccess.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        accessOn = ListenerAccess.isEnabled(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { model.refresh() }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(labels.appName, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = labels.settings) }
            }
        }
        if (update.prompt == UpdatePrompt.AVAILABLE) {
            item {
                UpdateBanner(UpdateLabels.ENGLISH, update.downloadUrl, onDismiss = container.appVersionCheck::dismissBanner)
            }
        }
        item {
            StatusCard(labels, switches, listener, accessOn, onTurnOnReading, onTurnOnAccess)
        }
        if (switches.reading || entries.isNotEmpty()) {
            item { TotalsCard(labels.thisMonth, totals.thisMonth, labels, money) }
            item { TotalsCard(labels.allTime, totals.allTime, labels, money) }
        }
        if (switches.reading && entries.isEmpty()) {
            item {
                Text(labels.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(entries, key = { "${it.network.id}/${it.entry.record.code}" }) { entry ->
            EntryRow(entry, labels, money) { onOpen(entry.network.id, entry.entry.record.code) }
        }
    }
}

@Composable
private fun StatusCard(
    labels: TrackerLabels,
    switches: TrackerSwitches,
    listener: ListenerStatus,
    accessOn: Boolean,
    onTurnOnReading: () -> Unit,
    onTurnOnAccess: () -> Unit,
) {
    // Stopped: reading is on, but the listener is off or Android has unbound it.
    val stopped = switches.reading && (!accessOn || !listener.connected)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (stopped) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(labels.statusTitle, style = MaterialTheme.typography.titleMedium)
            Text(if (switches.reading) labels.readingOn else labels.readingOff)
            Text(if (accessOn) labels.accessGranted else labels.accessMissing)
            if (switches.reading && accessOn && !listener.connected) Text(labels.listenerStopped)
            Text(
                listener.lastMessageAt?.let { "${labels.lastMessage}: ${it.asTime()}" } ?: labels.noMessageYet,
            )
            if (switches.reading) Text(if (switches.sending) labels.sendingOn else labels.sendingOff)
            if (!switches.reading) {
                OutlinedButton(onClick = onTurnOnReading) { Text(labels.turnOnReading) }
            } else if (!accessOn) {
                OutlinedButton(onClick = onTurnOnAccess) { Text(labels.turnOnAccess) }
            }
        }
    }
}

@Composable
private fun TotalsCard(period: String, totals: MpesaTotals, labels: TrackerLabels, money: MpesaLabels) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(period, style = MaterialTheme.typography.titleMedium)
            Text("${labels.moneyIn}: ${money.countAndAmount(totals.moneyIn.count, totals.moneyIn.amountKes)}")
            Text("${labels.moneyOut}: ${money.countAndAmount(totals.moneyOut.count, totals.moneyOut.amountKes)}")
        }
    }
}

@Composable
private fun EntryRow(entry: TrackerEntry, labels: TrackerLabels, money: MpesaLabels, onClick: () -> Unit) {
    val record = entry.entry.record
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(money.type(record.type), style = MaterialTheme.typography.titleSmall)
                Text(signedAmount(record.direction, record.amountKes, money), style = MaterialTheme.typography.titleSmall)
            }
            counterpartyOf(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                listOf(record.occurredAt.asTime(), entry.network.displayName, entry.entry.category?.let(money::category) ?: labels.noCategory)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "+KSh 1,000" for money in, "−KSh 500" for money out, the bare amount otherwise. */
internal fun signedAmount(direction: MpesaDirection, amountKes: Double, money: MpesaLabels): String = when (direction) {
    MpesaDirection.IN -> "+" + money.kes(amountKes)
    MpesaDirection.OUT -> "−" + money.kes(amountKes)
    MpesaDirection.OTHER -> money.kes(amountKes)
}

/** The name as the message printed it, or the number when there is no name. */
internal fun counterpartyOf(entry: TrackerEntry): String? =
    entry.entry.record.counterpartyName ?: entry.entry.record.counterpartyPhone?.let { "+$it" }

/** "12 Nov, 10:15", Nairobi time, like every other date in this project. */
internal fun Long.asTime(): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.UK)
        .apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }
        .format(Date(this))

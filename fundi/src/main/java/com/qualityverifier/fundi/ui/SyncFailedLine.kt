package com.qualityverifier.fundi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.text.FundiLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "The last sync at 14:32 did not finish" (v0.6.0, row 6), above the workers and the pieces
 * when the most recent sync failed; nothing once one has finished. A maker offline in a
 * workshop sees that their work is kept, and which rows are still to go ("Not sent yet").
 */
@Composable
fun SyncFailedLine(status: SyncStatus, labels: FundiLabels, modifier: Modifier = Modifier) {
    val at = status.failedAt ?: return
    // The phone's own clock and zone: the time the maker saw on their phone.
    val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(at))
    Text(
        labels.lastSyncFailed(time),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

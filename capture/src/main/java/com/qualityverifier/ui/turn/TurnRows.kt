package com.qualityverifier.ui.turn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.RetryLabels

/**
 * A turn that has just failed, with a way to send it again.
 *
 * Kagua's error row, moved here in v0.4.0 so Fundi Bora offers the same Retry. [onRetry]
 * is null for a failure that sending again cannot fix; [alternative] then takes its
 * place, as Kagua's "Open Settings" does for a sign-in failure.
 */
@Composable
fun FailedTurnRow(
    message: String,
    labels: RetryLabels,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    alternative: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row {
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text(labels.retry) }
                } else {
                    alternative?.invoke()
                }
                TextButton(onClick = onDismiss) { Text(labels.dismiss) }
            }
        }
    }
}

/**
 * Offered when the last turn never got a reply.
 *
 * Deliberately not styled as an error. By the time somebody sees this the failure is old
 * news, the photos and answers are safe on the phone, and the only thing missing is the
 * reply — so this reads as unfinished work to pick up rather than something broken.
 *
 * No Dismiss. The state is the conversation itself, not a message about it, so dismissing
 * would either lie until the next reopen or need somewhere to record that the person
 * gave up on a turn. Leaving the assessment is the way out, and it is one tap away.
 */
@Composable
fun UnansweredTurnRow(labels: RetryLabels, onSend: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                labels.unanswered,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onSend) { Text(labels.sendAgain) }
        }
    }
}

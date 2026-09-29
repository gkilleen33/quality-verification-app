package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.fundi.ui.fundiContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The maker's pieces, and the way to start another.
 *
 * The list comes first because the loop comes back to it: a piece assessed yesterday and
 * repaired this morning is re-assessed from here, and that second pass is what fills the
 * skill file and the two rates. A maker with nothing yet sees the item types and nothing
 * else, which is the same screen with the list empty rather than a different one.
 */
@Composable
fun HomeScreen(onOpen: (SessionSummary) -> Unit, onStart: (ItemType) -> Unit) {
    val container = fundiContainer()
    val model: PiecesViewModel = viewModel(factory = PiecesViewModel.factory(container))
    val pieces by model.pieces.collectAsState()

    var choosing by remember { mutableStateOf(pieces.isEmpty()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
        }

        if (choosing || pieces.isEmpty()) {
            item {
                Text(
                    "What have you just finished?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ItemType.entries) { type ->
                Button(
                    onClick = { onStart(type) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(type.homeLabel) }
            }
            if (pieces.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { choosing = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Back to my pieces") }
                }
            }
        } else {
            item {
                Button(
                    onClick = { choosing = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Assess a new piece") }
            }
            item {
                Text(
                    "My pieces",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(pieces, key = { it.id }) { piece -> PieceRow(piece) { onOpen(piece) } }
        }
    }
}

@Composable
private fun PieceRow(piece: SessionSummary, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(piece.itemType.homeLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    piece.updatedAt.asDay(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // An assessment with one turn is one somebody started and did not finish —
            // a phone call mid-plan, usually. Saying so is the difference between a list
            // they trust and a list of things they cannot account for.
            if (piece.messageCount <= 1) {
                Text(
                    "Not finished",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (piece.preview.isNotBlank()) {
                Text(
                    piece.preview.take(120),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Nairobi, like every other date in this project. */
private fun Long.asDay(): String =
    SimpleDateFormat("d MMM", Locale.UK)
        .apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }
        .format(Date(this))

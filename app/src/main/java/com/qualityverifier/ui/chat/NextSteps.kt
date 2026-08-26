package com.qualityverifier.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.ReportLabels

/**
 * What to do once the verdict is on screen.
 *
 * The case this is built for is somebody in a shop with four stools in front of them.
 * Before this, the only way on from a verdict was the back button and the grid, and then
 * five intake questions whose answers had not changed since the last stool. Now the next
 * piece inherits them and asks for its price alone.
 *
 * The comparison is offered only when there is a second finished assessment of the same
 * kind of piece to compare against — see [ChatViewModel.previousVerdict]. When there is
 * not, the button is absent rather than disabled: a button that cannot be pressed invites
 * the question of why, and there is no answer worth a customer's time.
 */
@Composable
fun NextStepsCard(
    itemName: String,
    canCompare: Boolean,
    labels: ReportLabels,
    onAssessAnother: () -> Unit,
    onCompare: () -> Unit,
    onAssessDifferent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                labels.nextStepsHeading,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onAssessAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) { Text(labels.assessAnother(itemName)) }
            if (canCompare) {
                OutlinedButton(
                    onClick = onCompare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) { Text(labels.compareWith(itemName)) }
            }
            TextButton(onClick = onAssessDifferent, modifier = Modifier.fillMaxWidth()) {
                Text(labels.assessDifferent)
            }
        }
    }
}

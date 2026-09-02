package com.qualityverifier.ui.tester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.TesterLabels
import kotlin.math.roundToInt

/**
 * The five questions an evaluator answers after finishing an assessment.
 *
 * Nothing here is required except the three that produce a measurement. The two free-text
 * boxes are optional on purpose: an evaluator who cannot put their finger on what was wrong
 * should still be able to record that something was, and forcing prose is how a scale ends
 * up answered at random to get past the form.
 *
 * There is no partial save. Either a review is submitted or it is not, because half a
 * questionnaire is not a data point and storing one would put a row in the research table
 * that looks like an answer.
 */
@Composable
fun TesterReviewScreen(
    labels: TesterLabels,
    onSubmit: (
        mistakes: String,
        mistakesDetail: String?,
        adviceStars: Int,
        itemQuality: Int,
        extraFeedback: String?,
    ) -> Unit,
    onLater: () -> Unit,
) {
    // No default on the mistakes question. A pre-selected "No" is an answer nobody gave,
    // and it is the one that would flatter us.
    var mistakes by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("") }
    var stars by remember { mutableIntStateOf(0) }
    // The midpoint is a starting position, not an answer: the slider has to be touched
    // because there is no "unset" position on a scale of one to ten.
    var quality by remember { mutableIntStateOf(0) }
    var extra by remember { mutableStateOf("") }

    val complete = mistakes != null && stars > 0 && quality > 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(labels.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            labels.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // ---- 1. mistakes
        Text(labels.mistakesQuestion, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        listOf(
            "yes" to labels.mistakesYes,
            "no" to labels.mistakesNo,
            "unsure" to labels.mistakesUnsure,
        ).forEach { (value, text) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = mistakes == value, onClick = { mistakes = value })
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mistakes == value, onClick = { mistakes = value })
                Spacer(Modifier.size(8.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // ---- 2. what went wrong, only once it is relevant
        if (mistakes == "yes") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = detail,
                onValueChange = { detail = it },
                label = { Text(labels.mistakesDetailLabel) },
                placeholder = { Text(labels.mistakesDetailHint) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- 3. advice, 1-5 stars
        Text(labels.adviceQuestion, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { value ->
                IconButton(
                    onClick = { stars = value },
                    modifier = Modifier.semantics {
                        // Stars are unreadable to a screen reader without this, and the
                        // count is the whole content of the control.
                        contentDescription = "$value"
                    },
                ) {
                    Icon(
                        if (value <= stars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                labels.adviceLow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                labels.adviceHigh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- 4. the furniture, 1-10
        Text(labels.itemQuestion, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (quality == 0) "—" else "$quality",
            style = MaterialTheme.typography.headlineMedium,
        )
        Slider(
            value = if (quality == 0) 1f else quality.toFloat(),
            onValueChange = { quality = it.roundToInt().coerceIn(1, 10) },
            valueRange = 1f..10f,
            // Nine steps between ten positions, so it cannot land between two numbers.
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                labels.itemLow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                labels.itemHigh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- 5. anything else
        OutlinedTextField(
            value = extra,
            onValueChange = { extra = it },
            label = { Text(labels.extraLabel) },
            placeholder = { Text(labels.extraHint) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onSubmit(
                    mistakes ?: return@Button,
                    detail.trim().takeIf { it.isNotEmpty() },
                    stars,
                    quality,
                    extra.trim().takeIf { it.isNotEmpty() },
                )
            },
            enabled = complete,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(labels.submit) }

        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(labels.later) }
        Spacer(Modifier.height(24.dp))
    }
}

/** The card offered under a finished assessment, which opens the questionnaire. */
@Composable
fun TesterReviewPrompt(labels: TesterLabels, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(labels.prompt, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen) { Text(labels.promptAction) }
        }
    }
}

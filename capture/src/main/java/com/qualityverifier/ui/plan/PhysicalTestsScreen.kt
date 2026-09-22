package com.qualityverifier.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.PlannedTest
import com.qualityverifier.text.ReportLabels

/**
 * One hands-on test at a time, with its outcomes as buttons.
 *
 * The customer's hands are the instrument, so the screen is one instruction and a short
 * list of what they might have felt. Typing "rocks clearly at the joints" one-handed
 * while holding a phone over an upturned stool is the wrong input; a diagram and three
 * buttons is the right one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalTestsScreen(
    test: PlannedTest,
    index: Int,
    total: Int,
    labels: ReportLabels,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(labels.testsHeading)
                        Text(
                            labels.testOf(index + 1, total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(test.title, style = MaterialTheme.typography.titleLarge)
                    if (test.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            test.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Only three tests have a drawing, and an unrecognised name draws
                    // nothing rather than a broken box — the prompts can name diagrams
                    // this build has never heard of.
                    test.diagramKind?.let { kind ->
                        Spacer(Modifier.height(12.dp))
                        TestDiagramImage(
                            kind = kind,
                            objectColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            motionColor = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (test.instruction.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(test.instruction, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                test.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onAnswer(option.label) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(vertical = 10.dp),
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Start,
                            )
                            if (option.detail.isNotBlank()) {
                                Text(
                                    option.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // Two ways out, and they mean different things. "Not sure" is an answer -- they
            // tried and learned nothing definite. "Can't do this one" is the absence of an
            // attempt, often because the piece is too heavy to tip alone.
            //
            // Added by the app rather than left to the plan, so they are on every test
            // whatever the model emitted. Neither is a failure: the prompt is told to put
            // both in the verdict's unverified list, because a wobble test nobody could
            // perform is not a wobbly stool.
            TextButton(
                onClick = { onAnswer(labels.notSure) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(labels.notSure) }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(labels.cannotDoThis)
            }
        }
    }
}

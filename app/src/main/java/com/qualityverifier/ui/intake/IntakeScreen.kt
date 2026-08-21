package com.qualityverifier.ui.intake

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels

/**
 * The questions the app can answer for itself, asked before anything is sent.
 *
 * Nothing here touches the network. The assessment used to fire a request the moment the
 * screen appeared — so the first thing a customer saw was a spinner — and then spend four
 * more round trips on a language, an ownership, a usage and a depth question that no model
 * was needed for. Now the chat opens instantly and the first request carries all of it,
 * which means the assistant's first reply can be the photo plan itself.
 *
 * Language comes first and is asked in both languages at once, because until it is
 * answered there is no right one to ask in. Everything after it is in the chosen language.
 *
 * Every question after the language offers a way out. A stepped form with no escape is
 * exactly where somebody who cannot find their answer among the buttons gets stuck, and
 * the assistant is better at an awkward question than a fixed list is. Taking the way out
 * ends the local questioning for good and hands over whatever was already chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntakeScreen(
    itemType: ItemType,
    onComplete: (AssessmentContext) -> Unit,
    onBack: () -> Unit,
) {
    var language by remember { mutableStateOf<AssessmentLanguage?>(null) }
    var ownership by remember { mutableStateOf<Ownership?>(null) }
    var price by remember { mutableStateOf("") }
    var priceAnswered by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<Usage?>(null) }
    var depth by remember { mutableStateOf<AssessmentDepth?>(null) }
    var handedOver by remember { mutableStateOf(false) }

    val labels = language?.let { ReportLabels.forLanguage(it.code) } ?: ReportLabels.ENGLISH
    val needsPrice = ownership == Ownership.BUYING
    val totalSteps = if (needsPrice) 5 else 4

    val step = when {
        language == null -> 1
        ownership == null -> 2
        needsPrice && !priceAnswered -> 3
        usage == null -> totalSteps - 1
        else -> totalSteps
    }
    // Whether there is a price step depends on an answer not yet given at step one, so the
    // total is unknowable until then. Showing "1 / 4" and then "3 / 5" reads as a glitch;
    // showing nothing for one screen does not.
    val showCounter = ownership != null

    fun back() {
        when {
            depth != null -> depth = null
            usage != null -> usage = null
            needsPrice && priceAnswered -> priceAnswered = false
            ownership != null -> ownership = null
            language != null -> language = null
            else -> onBack()
        }
    }

    BackHandler(onBack = ::back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(labels.itemName(itemType))
                        if (showCounter) {
                            Text(
                                "$step / $totalSteps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::back) {
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
                .imePadding()
                .padding(20.dp),
        ) {
            when {
                language == null -> Question("Language · Lugha") {
                    AssessmentLanguage.entries.forEach { option ->
                        Choice(option.ownName) { language = option }
                    }
                }

                ownership == null -> Question(labels.intakeOwnershipQuestion) {
                    Choice(labels.intakeBuying) { ownership = Ownership.BUYING }
                    Choice(labels.intakeAlreadyOwn) {
                        ownership = Ownership.ALREADY_OWN
                        // No price to ask about, so that step never applies.
                        priceAnswered = true
                    }
                }

                needsPrice && !priceAnswered -> Question(labels.intakePriceQuestion) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text(labels.intakePriceHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { priceAnswered = true },
                            enabled = price.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakeNext) }
                        OutlinedButton(
                            onClick = {
                                price = ""
                                priceAnswered = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakePriceSkip) }
                    }
                }

                usage == null -> Question(labels.intakeUsageQuestion) {
                    Choice(labels.intakeUsageDaily) { usage = Usage.DAILY }
                    Choice(labels.intakeUsageOccasional) { usage = Usage.OCCASIONAL }
                    Choice(labels.intakeUsageBusiness) { usage = Usage.BUSINESS }
                }

                else -> Question(labels.intakeDepthQuestion) {
                    Choice(labels.intakeDepthFull, labels.intakeDepthFullDetail) {
                        depth = AssessmentDepth.FULL
                    }
                    Choice(labels.intakeDepthRapid, labels.intakeDepthRapidDetail) {
                        depth = AssessmentDepth.RAPID
                    }
                }
            }

            // Offered from the second question onward. The language step is two words in
            // their own scripts with nothing to deviate to, and a handover there would
            // have to be written in a language nobody has chosen yet.
            if (language != null) {
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = { handedOver = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(labels.intakeSomethingElse) }
            }
        }
    }

    // In a LaunchedEffect, not in the composition: calling onComplete inline would fire
    // again on every recomposition, and each firing sends a request.
    LaunchedEffect(language, ownership, price, usage, depth, handedOver) {
        val finished = depth != null
        if (!finished && !handedOver) return@LaunchedEffect
        onComplete(
            AssessmentContext(
                language = language,
                ownership = ownership,
                quotedPrice = price,
                usage = usage,
                depth = depth,
            ),
        )
    }
}

@Composable
private fun Question(text: String, content: @Composable () -> Unit) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Choice(label: String, detail: String? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

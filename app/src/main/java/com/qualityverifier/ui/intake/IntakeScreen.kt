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
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels

/**
 * The questions the app can answer for itself, asked before anything is sent.
 *
 * Nothing here touches the network. The assessment used to fire a request the moment the
 * screen appeared — so the first thing a customer saw was a spinner — and then spend three
 * more round trips asking a language, an ownership and a usage question that no model was
 * needed for. Now the chat opens instantly and the first request carries all of it.
 *
 * Language comes first and is asked in both languages at once, because until it is
 * answered there is no right one to ask in. Everything after it is in the chosen language.
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

    val labels = language?.let { ReportLabels.forLanguage(it.code) } ?: ReportLabels.ENGLISH
    val needsPrice = ownership == Ownership.BUYING
    val totalSteps = if (needsPrice) 4 else 3

    val step = when {
        language == null -> 1
        ownership == null -> 2
        needsPrice && !priceAnswered -> 3
        else -> totalSteps
    }
    // Whether there is a price step depends on an answer not yet given at step one, so
    // the total is unknowable until then. Showing "1 / 3" and then "3 / 4" reads as a
    // glitch; showing nothing for one screen does not.
    val showCounter = ownership != null

    fun back() {
        when {
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
                language == null -> LanguageStep { language = it }

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
                    Spacer(Modifier.height(12.dp))
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

                else -> Question(labels.intakeUsageQuestion) {
                    Choice(labels.intakeUsageDaily) { usage = Usage.DAILY }
                    Choice(labels.intakeUsageOccasional) { usage = Usage.OCCASIONAL }
                    Choice(labels.intakeUsageBusiness) { usage = Usage.BUSINESS }
                }
            }
        }
    }

    // The last answer completes the intake, so there is no separate confirm step to tap
    // through — the customer is already three taps deep and wants to start.
    //
    // In a LaunchedEffect, not in the composition: calling onComplete inline would fire
    // again on every recomposition, and each firing sends a request.
    val chosenLanguage = language
    val chosenOwnership = ownership
    val chosenUsage = usage
    LaunchedEffect(chosenLanguage, chosenOwnership, chosenUsage, price) {
        if (chosenLanguage != null && chosenOwnership != null && chosenUsage != null) {
            onComplete(
                AssessmentContext(
                    language = chosenLanguage,
                    ownership = chosenOwnership,
                    quotedPrice = price,
                    usage = chosenUsage,
                ),
            )
        }
    }
}

/**
 * Asked in both languages side by side. Until this is answered there is no chosen
 * language to ask it in, and defaulting to English would quietly make English the norm.
 */
@Composable
private fun LanguageStep(onPick: (AssessmentLanguage) -> Unit) {
    Question("Language · Lugha") {
        AssessmentLanguage.entries.forEach { option ->
            Choice(option.ownName) { onPick(option) }
        }
    }
}

@Composable
private fun Question(text: String, content: @Composable () -> Unit) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Choice(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

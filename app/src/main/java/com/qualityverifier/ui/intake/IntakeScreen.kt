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
@Composable
fun IntakeScreen(
    itemType: ItemType,
    /**
     * Answers carried from the assessment before this one. When they are whole, the only
     * thing left to ask is the price: it is the one answer that belongs to this piece
     * rather than to the afternoon.
     */
    prefill: AssessmentContext? = null,
    /**
     * True when the item type was inherited from an assessment that has already settled
     * it, so the upholstery question has nothing left to decide. The button that starts
     * this assessment names the protocol it is starting — "check another padded chair" —
     * and asking again immediately afterwards reads as not having listened.
     */
    protocolSettled: Boolean = false,
    /**
     * An answer to "buying or already own it" decided before the intake. A shop's tablet
     * (row 12) passes [Ownership.BUYING]: a walk-in customer is checking a finished piece
     * before buying it, so the question is skipped and buying is recorded. Null asks it, as
     * every personal assessment does.
     */
    fixedOwnership: Ownership? = null,
    onComplete: (AssessmentContext, ItemType) -> Unit,
    onBack: () -> Unit,
) {
    var startOver by remember { mutableStateOf(false) }
    val carried = prefill?.takeIf { it.isComplete }
    if (carried != null && !startOver && fixedOwnership == null) {
        CarriedIntake(
            itemType = itemType,
            carried = carried,
            onComplete = onComplete,
            onBack = onBack,
            onStartOver = { startOver = true },
        )
        return
    }
    FullIntake(
        itemType = itemType,
        protocolSettled = protocolSettled,
        fixedOwnership = fixedOwnership,
        onComplete = onComplete,
        onBack = onBack,
    )
}

/** The questions of the full intake, in the order asked. */
internal enum class IntakeQuestion { LANGUAGE, UPHOLSTERY, OWNERSHIP, PRICE, USAGE, DEPTH }

/**
 * Which question the full intake is on, how many there are, and where Back goes, from the
 * answers so far. Apart from the screen so the order can be tested without Compose.
 *
 * [ownershipFixed] is the shop tablet's case (row 12): the ownership answer came with the
 * intake, so the question is neither asked, counted, nor gone back to.
 */
internal data class IntakeProgress(
    val needsUpholstery: Boolean,
    val ownershipFixed: Boolean = false,
    val language: AssessmentLanguage? = null,
    val upholstered: Boolean? = null,
    val ownership: Ownership? = null,
    val priceAnswered: Boolean = false,
    val usage: Usage? = null,
    val depth: AssessmentDepth? = null,
) {
    val needsPrice: Boolean get() = ownership == Ownership.BUYING

    val question: IntakeQuestion get() = when {
        language == null -> IntakeQuestion.LANGUAGE
        needsUpholstery && upholstered == null -> IntakeQuestion.UPHOLSTERY
        ownership == null -> IntakeQuestion.OWNERSHIP
        needsPrice && !priceAnswered -> IntakeQuestion.PRICE
        usage == null -> IntakeQuestion.USAGE
        else -> IntakeQuestion.DEPTH
    }

    /** The questions this intake asks, so far as the answers given can tell. */
    private val asked: List<IntakeQuestion> get() = listOfNotNull(
        IntakeQuestion.LANGUAGE,
        IntakeQuestion.UPHOLSTERY.takeIf { needsUpholstery },
        IntakeQuestion.OWNERSHIP.takeUnless { ownershipFixed },
        IntakeQuestion.PRICE.takeIf { needsPrice },
        IntakeQuestion.USAGE,
        IntakeQuestion.DEPTH,
    )

    val totalSteps: Int get() = asked.size
    val step: Int get() = asked.indexOf(question) + 1

    /**
     * Whether the total is known yet. Whether there is a price step depends on the ownership
     * answer, so the counter waits for it; with ownership fixed it is known from the start.
     */
    val showCounter: Boolean get() = ownership != null

    /** The answers with the last one taken back, or null when Back leaves the intake. */
    fun back(): IntakeProgress? = when {
        depth != null -> copy(depth = null)
        usage != null -> copy(usage = null)
        needsPrice && priceAnswered -> copy(priceAnswered = false)
        // As v0.6.1 does, priceAnswered is left as it is.
        ownership != null && !ownershipFixed -> copy(ownership = null)
        needsUpholstery && upholstered != null -> copy(upholstered = null)
        language != null -> copy(language = null)
        else -> null
    }

    companion object {
        fun start(needsUpholstery: Boolean, fixedOwnership: Ownership?) = IntakeProgress(
            needsUpholstery = needsUpholstery,
            ownershipFixed = fixedOwnership != null,
            ownership = fixedOwnership,
            // Owning means no price to ask about, as answering "already own" does.
            priceAnswered = fixedOwnership == Ownership.ALREADY_OWN,
        )
    }
}

/**
 * One question instead of five, for a second piece in the same shop.
 *
 * The carried answers are shown rather than assumed silently, and there is a way back to
 * the whole intake, because the second stool may genuinely be for something else. Saving
 * four taps is not worth putting a wrong answer in front of the assistant without saying
 * so.
 *
 * When they already own the piece there is no price to ask about, so this asks nothing at
 * all and goes straight on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarriedIntake(
    itemType: ItemType,
    carried: AssessmentContext,
    onComplete: (AssessmentContext, ItemType) -> Unit,
    onBack: () -> Unit,
    onStartOver: () -> Unit,
) {
    val labels = ReportLabels.forLanguage(carried.language?.code)
    var price by remember { mutableStateOf("") }
    var answered by remember { mutableStateOf(carried.ownership != Ownership.BUYING) }

    BackHandler(onBack = onBack)

    if (!answered) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(labels.itemName(itemType)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
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
                Question(labels.intakePriceQuestion) {
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
                            onClick = { answered = true },
                            enabled = price.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakeNext) }
                        OutlinedButton(
                            onClick = {
                                price = ""
                                answered = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakePriceSkip) }
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    labels.intakeCarriedHeading,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    carriedRecap(carried, labels),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
                    Text(labels.intakeStartOver)
                }
            }
        }
    }

    // As in the full intake: fired from an effect, because calling it inline would fire
    // again on every recomposition and each firing sends a request.
    LaunchedEffect(answered) {
        if (answered) onComplete(carried.copy(quotedPrice = price), itemType)
    }
}

/** The carried answers in the customer's own words, so they can see what is being reused. */
private fun carriedRecap(context: AssessmentContext, labels: ReportLabels): String =
    listOfNotNull(
        when (context.ownership) {
            Ownership.BUYING -> labels.intakeBuying
            Ownership.ALREADY_OWN -> labels.intakeAlreadyOwn
            null -> null
        },
        when (context.usage) {
            Usage.DAILY -> labels.intakeUsageDaily
            Usage.OCCASIONAL -> labels.intakeUsageOccasional
            Usage.BUSINESS -> labels.intakeUsageBusiness
            null -> null
        },
        when (context.depth) {
            AssessmentDepth.FULL -> labels.intakeDepthFull
            AssessmentDepth.RAPID -> labels.intakeDepthRapid
            null -> null
        },
    ).joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullIntake(
    itemType: ItemType,
    protocolSettled: Boolean,
    fixedOwnership: Ownership?,
    onComplete: (AssessmentContext, ItemType) -> Unit,
    onBack: () -> Unit,
) {
    // One grid entry covers both chair protocols, so which one applies is asked here.
    val needsUpholstery = itemType.needsUpholsteryQuestion && !protocolSettled
    var progress by remember { mutableStateOf(IntakeProgress.start(needsUpholstery, fixedOwnership)) }
    var price by remember { mutableStateOf("") }
    var handedOver by remember { mutableStateOf(false) }

    val language = progress.language
    val upholstered = progress.upholstered
    val ownership = progress.ownership
    val usage = progress.usage
    val depth = progress.depth

    val labels = language?.let { ReportLabels.forLanguage(it.code) } ?: ReportLabels.ENGLISH
    val resolvedItem = upholstered?.let { itemType.withUpholstery(it) } ?: itemType
    val totalSteps = progress.totalSteps
    val step = progress.step
    // Whether there is a price step depends on an answer not yet given at step one, so the
    // total is unknowable until then. Showing "1 / 4" and then "3 / 5" reads as a glitch;
    // showing nothing for one screen does not.
    val showCounter = progress.showCounter

    fun back() {
        progress.back()?.let { progress = it } ?: onBack()
    }

    BackHandler(onBack = ::back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (needsUpholstery && upholstered == null) {
                                labels.neutralItemName(itemType)
                            } else {
                                labels.itemName(resolvedItem)
                            },
                        )
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
            when (progress.question) {
                IntakeQuestion.LANGUAGE -> Question("Language · Lugha") {
                    AssessmentLanguage.entries.forEach { option ->
                        Choice(option.ownName) { progress = progress.copy(language = option) }
                    }
                }

                IntakeQuestion.UPHOLSTERY ->
                    Question(labels.intakeUpholsteryQuestion) {
                        Choice(labels.intakeUpholsteryYes) { progress = progress.copy(upholstered = true) }
                        Choice(labels.intakeUpholsteryNo) { progress = progress.copy(upholstered = false) }
                    }

                IntakeQuestion.OWNERSHIP -> Question(labels.intakeOwnershipQuestion) {
                    Choice(labels.intakeBuying) { progress = progress.copy(ownership = Ownership.BUYING) }
                    Choice(labels.intakeAlreadyOwn) {
                        // No price to ask about, so that step never applies.
                        progress = progress.copy(ownership = Ownership.ALREADY_OWN, priceAnswered = true)
                    }
                }

                IntakeQuestion.PRICE -> Question(labels.intakePriceQuestion) {
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
                            onClick = { progress = progress.copy(priceAnswered = true) },
                            enabled = price.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakeNext) }
                        OutlinedButton(
                            onClick = {
                                price = ""
                                progress = progress.copy(priceAnswered = true)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) { Text(labels.intakePriceSkip) }
                    }
                }

                IntakeQuestion.USAGE -> Question(labels.intakeUsageQuestion) {
                    Choice(labels.intakeUsageDaily) { progress = progress.copy(usage = Usage.DAILY) }
                    Choice(labels.intakeUsageOccasional) { progress = progress.copy(usage = Usage.OCCASIONAL) }
                    Choice(labels.intakeUsageBusiness) { progress = progress.copy(usage = Usage.BUSINESS) }
                }

                IntakeQuestion.DEPTH -> Question(labels.intakeDepthQuestion) {
                    Choice(labels.intakeDepthFull, labels.intakeDepthFullDetail) {
                        progress = progress.copy(depth = AssessmentDepth.FULL)
                    }
                    Choice(labels.intakeDepthRapid, labels.intakeDepthRapidDetail) {
                        progress = progress.copy(depth = AssessmentDepth.RAPID)
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
    LaunchedEffect(language, upholstered, ownership, price, usage, depth, handedOver) {
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
            // Unanswered when the intake was handed over early, in which case the grid's
            // default protocol stands and the assistant can ask.
            resolvedItem,
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

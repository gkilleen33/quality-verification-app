package com.qualityverifier.ui.intake

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.IntakeTopic
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.FurnitureKicker
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.ButtonState
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.rememberKaguaLabels
import com.qualityverifier.ui.shopMode

/**
 * The questions the app can answer for itself, asked before anything is sent.
 *
 * Nothing here touches the network: the first request carries every answer, so the
 * assistant's first reply can be the photo plan itself.
 *
 * Language comes first, on its own screen and in both languages at once, because it decides
 * the language of every question after it. Then, since v0.7.1 (release scope row 58; Nathan,
 * 24 November 2026), every other question is on one page, "A few questions first", one card
 * each, answered in any order. Each card has its choices and "Something else: let me
 * explain", which opens a box for the customer's own words. "Start the assessment" is greyed,
 * with how many questions are left, until each card has an answer. The cards' answers are
 * written into the first turn in fixed sentences ([com.qualityverifier.text.buildIntakeMessage]).
 */
@Composable
fun IntakeScreen(
    itemType: ItemType,
    /**
     * Answers carried from the assessment before this one ("Check another"). When they are
     * whole, the only thing left to ask is the price: it is the one answer that belongs to
     * this piece rather than to the afternoon.
     */
    prefill: AssessmentContext? = null,
    /**
     * True when the item type was inherited from an assessment that has already settled
     * it, so the cushioning question has nothing left to decide.
     */
    protocolSettled: Boolean = false,
    /**
     * An answer to "buying or already own it" decided before the intake. A shop's tablet
     * (row 12) passes [Ownership.BUYING]: the card is not shown and buying is recorded.
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
        needsUpholstery = itemType.needsUpholsteryQuestion && !protocolSettled,
        fixedOwnership = fixedOwnership,
        onComplete = onComplete,
        onBack = onBack,
    )
}

/**
 * "Check another": the price alone, for a second piece in the same shop, as a card (row 58).
 * The carried answers are shown under it, with a way back to every question, because the
 * second stool may be for something else. An owner has no price to give, so nothing is asked.
 */
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
        IntakeFrame(itemName = labels.itemName(itemType), backLabel = rememberKaguaLabels(carried.language?.code).back, onBack = onBack) {
            ScreenTitle(labels.intakePriceQuestion)
            RowCard {
                PriceField(price, labels) { price = it }
                CardRow(labels.intakePriceSkip, onClick = { price = ""; answered = true }, divider = true, chevron = false)
            }
            PrimaryButton(
                labels.intakeNext,
                onClick = { answered = true },
                state = if (price.isBlank()) ButtonState(missing = "") else ButtonState.Ready,
            )
            RowCard(heading = labels.intakeCarriedHeading) {
                CardRow(carriedRecap(carried, labels), chevron = false)
                CardRow(labels.intakeStartOver, onClick = onStartOver, divider = true, titleColor = Parts.Brand)
            }
        }
    }

    // Fired from an effect: calling it inline would fire again on every recomposition, and
    // each firing sends a request.
    LaunchedEffect(answered) {
        if (answered) onComplete(carried.copy(quotedPrice = price.trim()), itemType)
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

@Composable
private fun FullIntake(
    itemType: ItemType,
    needsUpholstery: Boolean,
    fixedOwnership: Ownership?,
    onComplete: (AssessmentContext, ItemType) -> Unit,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf<IntakePage?>(null) }
    var started by remember { mutableStateOf(false) }
    // Typing on the on-screen keyboard is not a touch MainActivity sees, so the boxes restart
    // a shop tablet's idle minutes themselves (row 12). Nothing for a personal account.
    val idle = shopMode().idle

    val current = page
    if (current == null) {
        // The language stays its own first screen: it decides the language of every question.
        BackHandler(onBack = onBack)
        val english = ReportLabels.ENGLISH
        IntakeFrame(itemName = english.neutralItemName(itemType), backLabel = rememberKaguaLabels().back, onBack = onBack) {
            ScreenTitle("Language · Lugha")
            RowCard {
                AssessmentLanguage.entries.forEachIndexed { i, language ->
                    CardRow(
                        language.ownName,
                        onClick = { page = IntakePage.start(language, needsUpholstery, fixedOwnership) },
                        divider = i > 0,
                    )
                }
            }
        }
        return
    }

    val labels = ReportLabels.forLanguage(current.language.code)
    val resolvedItem = current.upholstered?.let { itemType.withUpholstery(it) } ?: itemType
    // Back from the page returns to the language, which keeps nothing chosen on it.
    BackHandler { page = null }
    IntakeFrame(
        itemName = if (needsUpholstery && current.upholstered == null) labels.neutralItemName(itemType) else labels.itemName(resolvedItem),
        backLabel = rememberKaguaLabels(current.language.code).back,
        onBack = { page = null },
    ) {
        ScreenTitle(labels.intakePageTitle, line = labels.intakePageLine)
        current.cards.forEach { topic ->
            QuestionCard(topic, current, labels, onChange = { page = it; idle.touched() })
        }
        PrimaryButton(
            labels.intakeStart,
            onClick = { started = true },
            state = if (current.canStart) ButtonState.Ready else ButtonState(missing = labels.intakeLeft(current.left)),
        )
    }

    // In a LaunchedEffect, not in the composition: calling onComplete inline would fire again
    // on every recomposition, and each firing sends a request.
    LaunchedEffect(started) {
        val answered = page ?: return@LaunchedEffect
        if (!started || !answered.canStart) return@LaunchedEffect
        // A cushioning question answered in words leaves the grid's own protocol standing.
        onComplete(answered.toContext(), answered.upholstered?.let { itemType.withUpholstery(it) } ?: itemType)
    }
}

/** One question's card: its choices as radio rows, then "Something else: let me explain". */
@Composable
private fun QuestionCard(topic: IntakeTopic, page: IntakePage, labels: ReportLabels, onChange: (IntakePage) -> Unit) {
    val explaining = topic in page.open
    RowCard(heading = questionOf(topic, labels)) {
        when (topic) {
            IntakeTopic.UPHOLSTERY -> {
                Radio(labels.intakeUpholsteryYes, page.upholstered == true, first = true) { onChange(page.chooseUpholstery(true)) }
                Radio(labels.intakeUpholsteryNo, page.upholstered == false) { onChange(page.chooseUpholstery(false)) }
            }
            IntakeTopic.OWNERSHIP -> {
                Radio(labels.intakeBuying, page.ownership == Ownership.BUYING, first = true) { onChange(page.chooseOwnership(Ownership.BUYING)) }
                Radio(labels.intakeAlreadyOwn, page.ownership == Ownership.ALREADY_OWN) { onChange(page.chooseOwnership(Ownership.ALREADY_OWN)) }
            }
            IntakeTopic.PRICE -> {
                PriceField(page.price, labels) { onChange(page.typePrice(it)) }
                Radio(labels.intakePriceSkip, page.priceUnknown) { onChange(page.priceNotKnown()) }
            }
            IntakeTopic.USAGE -> {
                Radio(labels.intakeUsageDaily, page.usage == Usage.DAILY, first = true) { onChange(page.chooseUsage(Usage.DAILY)) }
                Radio(labels.intakeUsageOccasional, page.usage == Usage.OCCASIONAL) { onChange(page.chooseUsage(Usage.OCCASIONAL)) }
                Radio(labels.intakeUsageBusiness, page.usage == Usage.BUSINESS) { onChange(page.chooseUsage(Usage.BUSINESS)) }
            }
            IntakeTopic.DEPTH -> {
                Radio(labels.intakeDepthFull, page.depth == AssessmentDepth.FULL, line = labels.intakeDepthFullDetail, first = true) {
                    onChange(page.chooseDepth(AssessmentDepth.FULL))
                }
                Radio(labels.intakeDepthRapid, page.depth == AssessmentDepth.RAPID, line = labels.intakeDepthRapidDetail) {
                    onChange(page.chooseDepth(AssessmentDepth.RAPID))
                }
            }
        }
        if (!explaining) {
            CardRow(labels.intakeSomethingElse, onClick = { onChange(page.openExplain(topic)) }, divider = true, titleColor = Parts.Brand, chevron = false)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = page.explained[topic].orEmpty(),
                    onValueChange = { onChange(page.explain(topic, it)) },
                    label = { Text(labels.intakeOwnWords) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun questionOf(topic: IntakeTopic, labels: ReportLabels) = when (topic) {
    IntakeTopic.UPHOLSTERY -> labels.intakeUpholsteryQuestion
    IntakeTopic.OWNERSHIP -> labels.intakeOwnershipQuestion
    IntakeTopic.PRICE -> labels.intakePriceQuestion
    IntakeTopic.USAGE -> labels.intakeUsageQuestion
    IntakeTopic.DEPTH -> labels.intakeDepthQuestion
}

@Composable
private fun Radio(title: String, selected: Boolean, line: String? = null, first: Boolean = false, onClick: () -> Unit) {
    CardRow(title, line = line, radio = selected, onClick = onClick, divider = !first)
}

@Composable
private fun PriceField(value: String, labels: ReportLabels, onValue: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(labels.intakePriceHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Every intake screen: a Back that names where it goes, the furniture type (row 57), then the content. */
@Composable
private fun IntakeFrame(itemName: String, backLabel: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding().imePadding()) {
        BackBar(backLabel, onBack)
        FurnitureKicker(itemName)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

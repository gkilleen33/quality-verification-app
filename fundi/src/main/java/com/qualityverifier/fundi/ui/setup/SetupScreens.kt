package com.qualityverifier.fundi.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.text.FundiLabels

/**
 * The three setup screens, in the order the mockup asks them: workshop, tools, goals.
 *
 * They collect the block that goes into the opening turn of every assessment — see
 * `buildFundiContextMessage`. The tools screen is the one that matters most: the coaching
 * is explicitly "with your tools", and a fix built around four sash cramps for somebody
 * who owns none is the failure `prompts/fundi-master.txt` names outright.
 */

@Composable
fun WorkshopScreen(
    labels: FundiLabels,
    workshop: Workshop,
    onChange: ((Workshop) -> Workshop) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) = SetupScaffold(
    title = labels.setupWorkshopTitle,
    blurb = labels.setupWorkshopBlurb,
    primary = labels.setupNext,
    // Every field optional, so Next is never disabled. A maker who will not say how many
    // pieces a month they finish should still reach the coaching.
    primaryEnabled = true,
    onPrimary = onNext,
    secondary = labels.setupSkip,
    onSecondary = onSkip,
) {
    OutlinedTextField(
        value = workshop.worksAt.orEmpty(),
        onValueChange = { v -> onChange { it.copy(worksAt = v) } },
        label = { Text(labels.setupWorksAt) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = workshop.makes.orEmpty(),
        onValueChange = { v -> onChange { it.copy(makes = v) } },
        label = { Text(labels.setupMakes) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = workshop.usualTimber.orEmpty(),
        onValueChange = { v -> onChange { it.copy(usualTimber = v) } },
        label = { Text(labels.setupTimber) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    CountField(labels.setupYears, workshop.yearsInTrade) { v -> onChange { it.copy(yearsInTrade = v) } }
    CountField(labels.setupWorkers, workshop.workers) { v -> onChange { it.copy(workers = v) } }
    CountField(labels.setupPiecesPerMonth, workshop.piecesPerMonth) { v ->
        onChange { it.copy(piecesPerMonth = v) }
    }
}

@Composable
fun ToolsScreen(
    labels: FundiLabels,
    tools: Map<ToolKind, ToolOwnership>,
    onSet: (ToolKind, ToolOwnership) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) = SetupScaffold(
    title = labels.setupToolsTitle,
    blurb = labels.setupToolsBlurb,
    primary = labels.setupNext,
    primaryEnabled = true,
    onPrimary = onNext,
    secondary = labels.setupBack,
    onSecondary = onBack,
) {
    // Declaration order, the same order the context message uses, so the list a maker
    // answered reads back to them in the order they answered it.
    ToolKind.entries.filter { it != ToolKind.OTHER }.forEach { kind ->
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(labels.nameOf(kind), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolOwnership.entries.forEach { ownership ->
                    FilterChip(
                        // Nothing is selected until they choose. An unanswered tool must
                        // not default to "do not have": absent means nobody asked, and
                        // only a real answer licenses the coaching to work around it.
                        selected = tools[kind] == ownership,
                        onClick = { onSet(kind, ownership) },
                        label = { Text(labels.ownershipNames.getValue(ownership)) },
                    )
                }
            }
        }
    }
}

@Composable
fun GoalsScreen(
    labels: FundiLabels,
    goals: Set<FundiGoal>,
    onToggle: (FundiGoal) -> Unit,
    busy: Boolean,
    failed: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) = SetupScaffold(
    title = labels.setupGoalsTitle,
    blurb = labels.setupGoalsBlurb,
    primary = if (busy) labels.setupSaving else labels.setupFinish,
    primaryEnabled = !busy,
    onPrimary = onFinish,
    secondary = labels.setupBack,
    onSecondary = onBack,
    busy = busy,
) {
    FundiGoal.entries.forEach { goal ->
        FilterChip(
            selected = goal in goals,
            onClick = { onToggle(goal) },
            label = { Text(labels.nameOf(goal)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (failed) {
        Text(
            labels.setupFailed,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ------------------------------------------------------------------ shared pieces

/** A whole number, or nothing. Blank clears it rather than reading as zero. */
@Composable
private fun CountField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(4)
            onChange(digits.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SetupScaffold(
    title: String,
    blurb: String,
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    busy: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(primary)
        }
        TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
    }
}

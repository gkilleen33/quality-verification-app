package com.qualityverifier.fundi.ui.terms

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.MdBlock
import com.qualityverifier.text.MdInline
import com.qualityverifier.text.MdStyle
import com.qualityverifier.text.TermsLabels

/** How the maker arrived at the terms screen. The screens differ only in the heading. */
enum class TermsMode(val id: String) {
    /** Straight after creating an account. */
    NEW("new"),

    /** Signed in on an account that has not agreed. */
    RETURNING("returning"),

    /** The server refused a request because the terms changed. Sits on top of the refused screen. */
    CHANGED("changed"),

    /** Just withdrew the transfer agreement in Settings. */
    WITHDRAWN("withdrawn");

    companion object {
        fun fromId(id: String?): TermsMode = entries.firstOrNull { it.id == id } ?: RETURNING
    }
}

/**
 * The terms: the full text, scrollable, then two unticked boxes and Continue.
 *
 * Both boxes are disabled until the end of the text has been on screen (see [reachedEnd]),
 * and Continue works only with both ticked. There is no Decline and no way past: not
 * ticking is not agreeing, and the way out is Sign out or Account settings, where the
 * account can also be deleted. The text, the end marker, the hint, the boxes and Continue
 * are one list, so at the largest font the text keeps the whole screen and the boxes are
 * reached by scrolling.
 */
@Composable
fun TermsScreen(
    mode: TermsMode,
    onDone: (AfterTerms) -> Unit,
    onSignOut: () -> Unit,
    onAccountSettings: () -> Unit,
    labels: TermsLabels = TermsLabels.ENGLISH,
) {
    val container = fundiContainer()
    val assets = LocalContext.current.applicationContext.assets
    val viewModel: TermsViewModel = viewModel(factory = TermsViewModel.factory(container, assets))

    val load by viewModel.load.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val done by viewModel.done.collectAsState()

    LaunchedEffect(Unit) { viewModel.start(labels) }
    LaunchedEffect(done) { done?.let(onDone) }

    // On top of a screen the server refused, back would return to that refusal. It sends
    // the app to the background instead; elsewhere this screen is the root, and back leaves.
    val activity = LocalActivity.current
    BackHandler(enabled = mode == TermsMode.CHANGED) { activity?.moveTaskToBack(true) }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when (val state = load) {
            TermsLoad.Loading -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(labels.loading)
            }

            TermsLoad.Failed -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(labels.loadFailed, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { viewModel.reload() }) { Text(labels.retry) }
                WayOut(labels, onSignOut, onAccountSettings)
            }

            is TermsLoad.Ready -> TermsText(
                state = state,
                labels = labels.forDocument(state.document),
                heading = when (mode) {
                    TermsMode.NEW -> labels.headingNew
                    TermsMode.RETURNING -> labels.headingReturning
                    TermsMode.CHANGED -> labels.headingChanged
                    TermsMode.WITHDRAWN -> labels.headingWithdrawn
                },
                busy = busy,
                message = message,
                onLanguage = viewModel::chooseLanguage,
                onContinue = { viewModel.agree(state) },
                onSignOut = onSignOut,
                onAccountSettings = onAccountSettings,
            )
        }
    }
}

@Composable
private fun TermsText(
    state: TermsLoad.Ready,
    labels: TermsLabels,
    heading: String,
    busy: Boolean,
    message: String?,
    onLanguage: (String) -> Unit,
    onContinue: () -> Unit,
    onSignOut: () -> Unit,
    onAccountSettings: () -> Unit,
) {
    val blocks = remember(state.key) { state.document.blocks }
    val listState = rememberLazyListState()
    // Keyed on the text, so another language or a changed text starts unticked again.
    var checks by rememberSaveable(state.key, stateSaver = TermsChecks.Saver) { mutableStateOf(TermsChecks()) }

    val showLanguages = state.languages.size > 1
    // Items before the marker: the heading, the language switch when shown, then the text.
    val markerIndex = 1 + (if (showLanguages) 1 else 0) + blocks.size

    LaunchedEffect(state.key) {
        snapshotFlow {
            val info = listState.layoutInfo
            reachedEnd(
                visible = info.visibleItemsInfo.map { LaidOutItem(it.index, it.offset, it.size) },
                markerIndex = markerIndex,
                viewportStart = info.viewportStartOffset,
                viewportEnd = info.viewportEndOffset,
            )
        }.collect { if (it) checks = checks.reached() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(labels.title, style = MaterialTheme.typography.headlineSmall)
                Text(heading, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (showLanguages) {
            item(key = "languages") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.languages.forEach { language ->
                        FilterChip(
                            selected = language == state.language,
                            onClick = { if (language != state.language) onLanguage(language) },
                            label = { Text(languageName(language)) },
                        )
                    }
                }
            }
        }
        items(blocks) { block -> TermsBlock(block) }

        // The end of the text. When it is on screen the boxes enable. One pixel high so the
        // list always lays it out as an item of its own.
        item(key = "end") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }

        item(key = "hint") {
            Text(
                if (checks.boxesEnabled) labels.boxesEnabled else labels.scrollHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Polite, so TalkBack announces the change when the boxes enable.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        item(key = "terms-box") {
            CheckRow(labels.termsLabel, checks.terms, checks.boxesEnabled) { checks = checks.tickTerms(it) }
        }
        item(key = "transfer-box") {
            CheckRow(labels.transferLabel, checks.transfer, checks.boxesEnabled) { checks = checks.tickTransfer(it) }
        }
        item(key = "continue") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onContinue,
                    enabled = checks.canContinue(busy),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text(labels.continueLabel)
                }
                WayOut(labels, onSignOut, onAccountSettings)
            }
        }
    }
}

/**
 * One box and its label as a single toggleable element with the checkbox role, so TalkBack
 * reads one item: the label, "checkbox, not checked", and "disabled" until the end of the
 * text. The checkbox itself takes no taps of its own.
 */
@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WayOut(labels: TermsLabels, onSignOut: () -> Unit, onAccountSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onAccountSettings) { Text(labels.accountSettings) }
        TextButton(onClick = onSignOut) { Text(labels.signOut) }
    }
}

@Composable
private fun TermsBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Text(
            block.content.annotated(),
            style = if (block.level <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        is MdBlock.Paragraph -> Text(block.content.annotated(), style = MaterialTheme.typography.bodyMedium)
        is MdBlock.Bullet -> Row(Modifier.padding(start = (12 * (block.indent + 1)).dp)) {
            Text("•  ", style = MaterialTheme.typography.bodyMedium)
            Text(block.content.annotated(), style = MaterialTheme.typography.bodyMedium)
        }
        is MdBlock.Numbered -> Row(Modifier.padding(start = (12 * (block.indent + 1)).dp)) {
            Text("${block.number}.  ", style = MaterialTheme.typography.bodyMedium)
            Text(block.content.annotated(), style = MaterialTheme.typography.bodyMedium)
        }
        MdBlock.Rule -> HorizontalDivider()
    }
}

private fun MdInline.annotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        val style = when (span.style) {
            MdStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MdStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            MdStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
            MdStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
        }
        addStyle(style, start, end)
    }
}

private fun languageName(code: String): String = when (code) {
    "en" -> "English"
    "sw" -> "Kiswahili"
    else -> code
}

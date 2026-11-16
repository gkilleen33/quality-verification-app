package com.qualityverifier.tracker.ui.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.qualityverifier.text.MdBlock
import com.qualityverifier.text.MdInline
import com.qualityverifier.text.MdStyle
import com.qualityverifier.text.TermsLabels
import com.qualityverifier.tracker.APP_NAME
import com.qualityverifier.tracker.trackerContainer

/**
 * PesaLog's own terms and privacy notice, separate from Fundi Bora's, agreed to before the two
 * questions (Nathan, 16 November 2026). The box labels are the build prompt's, word for
 * word; they are part of what is agreed to.
 */
val PESALOG_TERMS_LABELS: TermsLabels = TermsLabels.ENGLISH.copy(
    title = "$APP_NAME: terms and privacy",
    termsLabel = "I agree to the terms and conditions.",
    transferLabel = "I agree that my mobile money records may be sent to the United States as described in section 10.",
    headingNew = "Before you start, please read these terms.",
    headingReturning = "Please read and agree to these terms to use $APP_NAME.",
    headingChanged = "There are new terms. Please read and agree to continue.",
    headingWithdrawn = "Please read and agree to these terms to use $APP_NAME.",
)

/**
 * The terms: the full text, scrollable, then two unticked boxes that can be ticked only after
 * the end of the text has been on screen, and Continue, as Fundi Bora's terms screen. There is
 * no way past without both: the way out is Sign out.
 */
@Composable
fun TermsScreen(
    onDone: () -> Unit,
    onSignOut: () -> Unit,
    labels: TermsLabels = PESALOG_TERMS_LABELS,
) {
    val container = trackerContainer()
    val assets = LocalContext.current.applicationContext.assets
    val viewModel: TermsViewModel = viewModel(factory = TermsViewModel.factory(container, assets))

    val load by viewModel.load.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val done by viewModel.done.collectAsState()

    LaunchedEffect(Unit) { viewModel.start(labels) }
    LaunchedEffect(done) { if (done) onDone() }

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
                TextButton(onClick = onSignOut) { Text(labels.signOut) }
            }

            is TermsLoad.Ready -> TermsText(
                state = state,
                labels = labels.forDocument(state.document),
                busy = busy,
                message = message,
                onContinue = { viewModel.agree(state) },
                onSignOut = onSignOut,
            )
        }
    }
}

@Composable
private fun TermsText(
    state: TermsLoad.Ready,
    labels: TermsLabels,
    busy: Boolean,
    message: String?,
    onContinue: () -> Unit,
    onSignOut: () -> Unit,
) {
    val blocks = remember(state.key) { state.document.blocks }
    val listState = rememberLazyListState()
    var checks by rememberSaveable(state.key, stateSaver = TermsChecks.Saver) { mutableStateOf(TermsChecks()) }
    // Items before the marker: the heading, then the text.
    val markerIndex = 1 + blocks.size

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
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(labels.title, style = MaterialTheme.typography.headlineSmall)
                Text(labels.headingReturning, style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(blocks) { block -> TermsBlock(block) }

        // The end of the text. When it is on screen the boxes enable.
        item(key = "end") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }

        item(key = "hint") {
            Text(
                if (checks.boxesEnabled) labels.boxesEnabled else labels.scrollHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSignOut) { Text(labels.signOut) }
                }
            }
        }
    }
}

/** One box and its label as a single toggleable element with the checkbox role. */
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

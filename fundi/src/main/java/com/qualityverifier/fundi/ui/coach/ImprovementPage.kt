package com.qualityverifier.fundi.ui.coach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qualityverifier.domain.DefectMark
import com.qualityverifier.domain.Finding
import com.qualityverifier.text.DetailAnswers
import com.qualityverifier.text.DetailKey
import com.qualityverifier.text.DetailKind
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ImprovementPaging
import com.qualityverifier.text.ImprovementRow
import com.qualityverifier.text.PageExchange
import com.qualityverifier.text.ReportLabels
import java.io.File

/**
 * One improvement's page (v0.7.0, release scope row 43; mockup v2, "Daybed v2: customer value
 * first", signed off 20 to 23 November 2026), drawn over the conversation as the photo viewer
 * is, so going back finds the list where it was.
 *
 * Top to bottom: "All improvements" and "n of N"; the severity and the title; "What the
 * customer thinks" (customer_line), highlighted; "How to fix it" (fix_line), the largest text
 * on the page because it is what the maker acts on (P7); the tool line; "See it on the photo";
 * the four detail buttons as one list that opens each answer in place (rows 37 and 49); "Ask
 * about this improvement", a chat about this improvement only; then "Previous improvement" and
 * "Next improvement". Everything above the chat is drawn from the stored reply, so the page
 * reads with no signal; a detail not yet written needs the network, as on the list before.
 */
@Composable
fun ImprovementPage(
    rows: List<ImprovementRow>,
    /** The open row's place in [rows], from 0. */
    position: Int,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    /** The photo the improvement names, by path on this phone, with its mark; null for none. */
    photoOf: (Finding) -> Pair<String, DefectMark>?,
    onOpenMark: (String, DefectMark) -> Unit,
    details: DetailAnswers,
    /** Asks Claude for one detail; null where nothing can be sent, such as an older reply. */
    onRequestDetail: ((DetailKind, String) -> Unit)?,
    /** This improvement's questions and answers, oldest first. */
    exchanges: List<PageExchange>,
    /** Sends a question about this improvement; null where nothing can be sent. */
    onAsk: ((String) -> Unit)?,
    /** A turn is on its way, so the newest question without an answer is being answered. */
    sending: Boolean,
    /** Opens the page at this position. */
    onGoTo: (Int) -> Unit,
    onClose: () -> Unit,
    /** The failed-turn and unanswered-turn rows, as the conversation draws them. */
    statusRows: @Composable () -> Unit,
) {
    BackHandler(onBack = onClose)
    val row = rows.getOrNull(position) ?: return
    val finding = row.finding
    // One scroll position per page, so Next opens the next page at its top, and a rotation
    // keeps the place on this one.
    val scroll = rememberSaveable(position, saver = ScrollState.Saver) { ScrollState(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(labels.allImprovements)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    labels.improvementOf(position + 1, rows.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeverityBadge(finding.severity, reportLabels)
                    Text(finding.title, style = MaterialTheme.typography.titleMedium)
                }

                if (finding.customerLine.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Heading(labels.customerThinks)
                            Text(finding.customerLine, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                if (finding.fixLine.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Heading(labels.howToFix)
                        Text(finding.fixLine, style = MaterialTheme.typography.titleLarge)
                    }
                }

                if (finding.toolLine.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(labels.toolLineLabel)
                        Text(finding.toolLine, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                photoOf(finding)?.let { (path, mark) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Heading(labels.seeOnPhoto)
                        AsyncImage(
                            model = File(path),
                            contentDescription = labels.seeOnPhoto,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 160.dp, height = 120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = labels.seeOnPhoto) { onOpenMark(path, mark) },
                        )
                    }
                }

                DetailList(finding, labels, details, onRequestDetail)

                PageChat(row, labels, exchanges, onAsk, sending)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val previous = ImprovementPaging.previous(position)
                    val next = ImprovementPaging.next(position, rows.size)
                    OutlinedButton(
                        onClick = { previous?.let(onGoTo) },
                        enabled = previous != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(labels.previousImprovement, textAlign = TextAlign.Center) }
                    OutlinedButton(
                        onClick = { next?.let(onGoTo) },
                        enabled = next != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(labels.nextImprovement, textAlign = TextAlign.Center) }
                }
                Spacer(Modifier.height(8.dp))
            }

            statusRows()
        }
    }
}

/**
 * The four detail buttons as one list (rows 37 and 49). A tap opens the answer in place when
 * the reply, a prefetch or an earlier tap already holds it, and otherwise asks Claude for it; a
 * second tap closes it.
 */
@Composable
private fun DetailList(
    finding: Finding,
    labels: FundiLabels,
    details: DetailAnswers,
    onRequestDetail: ((DetailKind, String) -> Unit)?,
) {
    // Saved across rotation, per improvement and button.
    var open by rememberSaveable(finding.title) { mutableStateOf(emptySet<String>()) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        DetailKind.entries.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val key = DetailKey(kind, finding.title.trim())
            val text = kind.storedIn(finding).ifBlank { details.answers[key].orEmpty() }
            val writing = key in details.pending
            val isOpen = kind.name in open
            val enabled = text.isNotBlank() || writing || onRequestDetail != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) {
                        open = if (isOpen) open - kind.name else open + kind.name
                        if (!isOpen && text.isBlank() && !writing) onRequestDetail?.invoke(kind, finding.title.trim())
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    labels.detailLabel(kind),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Decorative: the row's text says what it opens, and the answer below shows it opened.
                Icon(
                    if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isOpen) {
                when {
                    text.isNotBlank() -> Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                    writing -> Text(
                        labels.detailWriting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * "Ask about this improvement": the page's own chat. A question sent here carries the
 * improvement (ImprovementQuestion), is answered about it only, and shows on this page and
 * nowhere else.
 */
@Composable
private fun PageChat(
    row: ImprovementRow,
    labels: FundiLabels,
    exchanges: List<PageExchange>,
    onAsk: ((String) -> Unit)?,
    sending: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading(labels.askThisImprovement)
        exchanges.forEachIndexed { index, exchange ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    Text(
                        exchange.question,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            val answer = exchange.answer
            when {
                answer != null -> Text(answer, style = MaterialTheme.typography.bodyLarge)
                // Only the newest question can be the turn in flight.
                sending && index == exchanges.lastIndex -> Text(
                    labels.detailWriting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onAsk != null) {
            var text by rememberSaveable(row.key.number, row.key.title) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(labels.replyHint) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onAsk(text)
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(labels.send) }
            }
        }
    }
}

/** A section heading on the page: sentence case, a step above body text. */
@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

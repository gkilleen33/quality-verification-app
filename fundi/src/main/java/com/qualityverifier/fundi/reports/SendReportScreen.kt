package com.qualityverifier.fundi.reports

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.NamesBlock
import com.qualityverifier.domain.NoteBlock
import com.qualityverifier.domain.PhotosBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels

/**
 * "Send report" on a piece: "Who is this for?", the preview, the download with its
 * progress, and the share sheet (plan v7 §5.4 to §5.7).
 *
 * The preview lists what the PDF will say, block for block, as the server composed it; the
 * phone adds nothing and takes nothing away, so the maker checks the report itself. Once the
 * file is whole the share sheet opens over this screen, and the preview is still here
 * behind it, so the report can be sent to a second person.
 */
@Composable
fun SendReportScreen(
    pieceId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = fundiContainer()
    // The application context, not the activity: the view model keeps the lambda and
    // outlives the activity across a rotation.
    val appContext = context.applicationContext
    val model: SendReportViewModel = viewModel(
        key = "report-$pieceId",
        factory = SendReportViewModel.factory(container, pieceId) { ReportFiles.newFile(appContext, pieceId) },
    )
    val step by model.step.collectAsState()
    val toShare by model.share.collectAsState()
    val labels = FundiLabels.ENGLISH

    // Taken from the view model once opened, so a recreated screen does not open it twice
    // and a file that finished while the screen was away still opens it once.
    LaunchedEffect(toShare) {
        toShare?.let { file ->
            ReportFiles.share(context, file, labels.reportShareWith)
            model.shared(file)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        Text(labels.reportTitle, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        when (val current = step) {
            SendReportViewModel.Step.WhoFor -> WhoFor(labels, model.offered, onChoose = model::choose, onBack = onBack)
            is SendReportViewModel.Step.LoadingPreview -> Waiting(labels.reportPreparing)
            is SendReportViewModel.Step.Preview -> Preview(
                labels = labels,
                // The problem headings follow the report's own language, as the PDF's do.
                reportLabels = ReportLabels.forLanguage(current.preview.language),
                blocks = current.preview.blocks,
                photoCount = current.preview.photoCount,
                onSend = model::send,
                onBack = model::back,
            )
            is SendReportViewModel.Step.Preparing -> Waiting(labels.reportPreparing)
            is SendReportViewModel.Step.Downloading -> Downloading(labels, current.received, current.total)
            is SendReportViewModel.Step.Failed -> Failed(
                labels = labels,
                message = when {
                    current.refused -> labels.reportRefused
                    current.busy -> labels.reportBusy
                    else -> labels.reportUnavailable
                },
                onRetry = model::retry,
                onBack = if (current.request == null) onBack else model::back,
            )
        }
    }
}

@Composable
private fun WhoFor(
    labels: FundiLabels,
    offered: List<ReportKind>,
    onChoose: (ReportKind) -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(labels.reportWhoFor, style = MaterialTheme.typography.titleMedium)
        offered.forEach { kind ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(labels.reportChoice(kind), style = MaterialTheme.typography.titleMedium)
                    Text(
                        labels.reportChoiceHint(kind),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onChoose(kind) }, modifier = Modifier.fillMaxWidth()) {
                        Text(labels.reportChoice(kind))
                    }
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(labels.reportBack) }
    }
}

/** The report, block for block. Photos are counted rather than shown: the PDF carries them. */
@Composable
private fun Preview(
    labels: FundiLabels,
    reportLabels: ReportLabels,
    blocks: List<ReportBlock>,
    photoCount: Int,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(labels.reportPreviewTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            labels.reportPreviewHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (photoCount > 0) {
            Text(
                labels.reportPhotos(photoCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(blocks) { block -> Block(block, reportLabels) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(labels.reportBack) }
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text(labels.reportSend) }
        }
    }
}

@Composable
private fun Block(block: ReportBlock, labels: ReportLabels) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (block) {
        is HeadingBlock -> Text(
            block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.padding(top = if (block.level <= 1) 0.dp else 8.dp),
        )
        is TextBlock -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
        is NoteBlock -> Text(block.text, style = MaterialTheme.typography.bodySmall, color = muted)
        is LineBlock -> Row {
            Text("${block.label}: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(block.value, style = MaterialTheme.typography.bodyMedium)
        }
        is NamesBlock -> Text(
            "${block.label} ${block.names.joinToString(", ")}",
            style = MaterialTheme.typography.bodyMedium,
        )
        is ProblemBlock -> Problem(block, labels)
        // Counted above the list; nothing to draw here.
        is PhotosBlock -> Unit
    }
}

@Composable
private fun Problem(block: ProblemBlock, labels: ReportLabels) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val tags = listOfNotNull(block.severity.takeIf { it.isNotBlank() }, block.part)
            Text(
                block.title + if (tags.isNotEmpty()) " (${tags.joinToString(", ")})" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            listOf(
                labels.whatISeeHeading to block.whatISee,
                labels.whatItMeansHeading to block.whatItMeans,
                labels.whatToDoHeading to block.whatToDo.orEmpty(),
            ).forEach { (heading, text) ->
                if (text.isNotBlank()) {
                    Text(heading, style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Waiting(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Downloading the report, 120 of 480 KB", with a bar once the total is known. */
@Composable
private fun Downloading(labels: FundiLabels, received: Long, total: Long?) {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (received.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(labels.reportDownloading(received / 1024, total?.let { it / 1024 }), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Failed(labels: FundiLabels, message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(labels.reportRetry) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(labels.reportBack) }
    }
}

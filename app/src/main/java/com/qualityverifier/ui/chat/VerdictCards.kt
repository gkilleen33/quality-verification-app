package com.qualityverifier.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.rememberReportLabels
import com.qualityverifier.ui.theme.verdictColors

/**
 * The verdict, as the screen a buyer acts on while standing in the shop.
 *
 * Each defect answers the same three questions in the same order — what I see, what it
 * means for you, what to do — because a fixed shape is what makes a report skimmable by
 * somebody who is being watched by the person who built the furniture.
 */
@Composable
fun VerdictCards(
    verdict: Verdict,
    onAskQuestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Headings follow the assessment's language, not the phone's — a Swahili finding
    // under an English heading reads as an unfinished app.
    val labels = rememberReportLabels(verdict.language)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HeadlineCard(verdict, labels)
        verdict.defects.forEach { DefectCard(it, labels) }
        if (verdict.unverified.isNotEmpty()) UnverifiedCard(verdict.unverified, labels)
        if (verdict.questions.isNotEmpty()) {
            QuestionChips(verdict.questions, labels, onAskQuestion)
        }
    }
}

@Composable
private fun HeadlineCard(verdict: Verdict, labels: ReportLabels) {
    val colors = verdictColors(verdict.level)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.container,
            contentColor = colors.onContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                labels.verdictHeading,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            if (verdict.headline.isNotBlank()) {
                Text(verdict.headline, style = MaterialTheme.typography.titleLarge)
            }
            if (verdict.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(verdict.summary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DefectCard(defect: Defect, labels: ReportLabels) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityChip(defect.severity, defect.area, labels)
            }
            if (defect.title.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(defect.title, style = MaterialTheme.typography.titleMedium)
            }
            Field(labels.whatISeeHeading, defect.whatISee)
            Field(labels.whatItMeansHeading, defect.whatItMeans)
            Field(labels.whatToDoHeading, defect.whatToDo)
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Spacer(Modifier.height(10.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(2.dp))
    Text(value, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SeverityChip(severity: Severity, area: String, labels: ReportLabels) {
    val label = listOfNotNull(
        area.takeIf { it.isNotBlank() }?.let(labels::area),
        labels.severity(severity).takeIf { it.isNotBlank() }?.uppercase(),
    ).joinToString(" · ")
    if (label.isEmpty()) return

    // Severity borrows the verdict palette so that a serious defect on a fair overall
    // verdict still reads as serious.
    val colors = when (severity) {
        Severity.SERIOUS -> verdictColors(com.qualityverifier.domain.VerdictLevel.SERIOUS)
        Severity.MODERATE -> verdictColors(com.qualityverifier.domain.VerdictLevel.FAIR)
        else -> verdictColors(com.qualityverifier.domain.VerdictLevel.UNKNOWN)
    }
    Surface(
        color = colors.container,
        contentColor = colors.onContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun UnverifiedCard(items: List<String>, labels: ReportLabels) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                labels.couldNotVerifyHeading,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            items.forEach { line ->
                Spacer(Modifier.height(8.dp))
                Text("• $line", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuestionChips(
    questions: List<String>,
    labels: ReportLabels,
    onAsk: (String) -> Unit,
) {
    Column {
        Text(
            labels.askAboutThis,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            questions.forEach { question ->
                AssistChip(onClick = { onAsk(question) }, label = { Text(question) })
            }
        }
    }
}

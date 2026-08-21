package com.qualityverifier.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.PlanRun
import java.io.File
import java.util.Locale

/**
 * What the app shows while an inspection is in flight.
 *
 * The mockup ticks off content areas — frame geometry, joints, surface and finish — as
 * though it could watch the assessment happen. It cannot: this is one request with no
 * streaming, so the app knows only that it sent something and is waiting. Animating
 * those ticks would be theatre, and inventing certainty is the one thing this product
 * cannot afford to do anywhere, including in a spinner.
 *
 * So the ticks here are the steps the app really completed — the photos it prepared, the
 * answers it recorded — and what is genuinely in progress gets a spinner. The list of
 * what is being examined is the plan's own items, stated rather than pretended to track.
 */
@Composable
fun InspectingScreen(
    run: PlanRun,
    labels: ReportLabels,
    modifier: Modifier = Modifier,
) {
    val paths = run.takenPaths
    val bytes = remember(paths) {
        paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
    }
    val answered = run.plan.tests.indices.count { run.answers[it] != null }

    Column(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        if (paths.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(paths) { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Text("${labels.inspecting}…", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = summaryLine(paths.size, answered, bytes, labels),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        // Done, and genuinely so: the photos were normalised as they were taken and the
        // answers are already recorded.
        StageRow(labels.stagePreparing, done = true)
        StageRow(labels.stageSending, done = true)
        StageRow(labels.stageExamining, done = false)

        if (run.plan.photos.isNotEmpty() || run.plan.tests.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                labels.inThisInspection.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            (run.plan.photos.map { it.title } + run.plan.tests.map { it.title })
                .filter { it.isNotBlank() }
                .forEach { title ->
                    Text(
                        "· $title",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun StageRow(label: String, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(3.dp),
                )
            }
        } else {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** "7 photos · 2 test results · 1.9 MB sent" — every number here is real. */
private fun summaryLine(
    photos: Int,
    tests: Int,
    bytes: Long,
    labels: ReportLabels,
): String {
    val parts = mutableListOf<String>()
    if (photos > 0) parts += labels.photoCount(photos)
    if (tests > 0) parts += labels.testResultCount(tests)
    if (bytes > 0) {
        parts += labels.sent(String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0))
    }
    return parts.joinToString(" · ")
}

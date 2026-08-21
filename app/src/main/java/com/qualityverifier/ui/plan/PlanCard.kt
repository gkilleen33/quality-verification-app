package com.qualityverifier.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/**
 * The plan, shown in the conversation where the assistant issued it.
 *
 * It is also the review screen: each shot gains a thumbnail as it is taken and can be
 * retaken from here, and each test shows the answer given. The mockup goes straight from
 * the last test into the inspection, but a batch is expensive to waste — if shot three
 * came out badly, finding out after the verdict is the wrong time.
 */
@Composable
fun PlanCard(
    run: PlanRun,
    labels: ReportLabels,
    onRetakeShot: (Int) -> Unit,
    onChangeAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (run.plan.summary.isNotBlank()) {
                Text(run.plan.summary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
            }

            if (run.plan.photos.isNotEmpty()) {
                SectionHeading(
                    "${labels.photosHeading} · ${run.photosTaken}/${run.plan.photos.size}",
                )
                run.plan.photos.forEachIndexed { index, shot ->
                    ShotRow(
                        number = index + 1,
                        title = shot.title,
                        note = shot.note,
                        path = run.shots[index],
                        decided = index in run.shots,
                        notDoneLabel = labels.notDone,
                        retakeLabel = labels.retake,
                        onRetake = { onRetakeShot(index) },
                    )
                }
            }

            if (run.plan.tests.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionHeading(labels.testsHeading)
                run.plan.tests.forEachIndexed { index, test ->
                    TestRow(
                        number = index + 1,
                        title = test.title,
                        answer = if (index in run.answers) {
                            run.answers[index] ?: labels.notDone
                        } else {
                            null
                        },
                        changeLabel = labels.retake,
                        onChange = { onChangeAnswer(index) },
                    )
                }
            }

        }
    }
}

/**
 * The one thing to do next, pinned above the composer rather than sitting at the foot of
 * the card.
 *
 * A seven-shot plan with four tests is taller than the screen, so in the card the button
 * that starts the camera was below the fold — on the screen whose entire purpose is to
 * start the camera. Pinned, the next action is always one tap away, including on the way
 * back through for a retake.
 */
@Composable
fun PlanActionBar(
    run: PlanRun,
    labels: ReportLabels,
    onStartCamera: () -> Unit,
    onStartTests: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, action) = when {
        !run.photosDone -> labels.startCamera to onStartCamera
        run.nextTest != null -> labels.continueToTests to onStartTests
        else -> labels.sendForInspection to onSubmit
    }
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Button(
            onClick = action,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(58.dp),
        ) { Text(label) }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ShotRow(
    number: Int,
    title: String,
    note: String,
    path: String?,
    decided: Boolean,
    notDoneLabel: String,
    retakeLabel: String,
    onRetake: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (path != null) {
                AsyncImage(
                    model = File(path),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$number",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "$number" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val second = if (decided && path == null) notDoneLabel else note
            if (second.isNotBlank()) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (decided) {
            TextButton(onClick = onRetake) { Text(retakeLabel) }
        }
    }
}

@Composable
private fun TestRow(
    number: Int,
    title: String,
    answer: String?,
    changeLabel: String,
    onChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "$number" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (answer != null) {
                Text(
                    answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (answer != null) {
            TextButton(onClick = onChange) { Text(changeLabel) }
        }
    }
}

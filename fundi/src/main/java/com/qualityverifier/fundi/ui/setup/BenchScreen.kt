package com.qualityverifier.fundi.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.ui.capture.CaptureScreen
import java.io.File

/**
 * The bench photo at setup (v0.6.0, release scope row 5), between the workshop and the
 * typed tool list: one photo of the bench, Claude's draft of the tools on it, and each
 * drafted tool put to the fundi, "Is this on your bench?" and then "Who owns it?". Skippable
 * at every step: the typed list on the next screen works as before, prefilled with whatever
 * the fundi confirmed here.
 */
@Composable
fun BenchScreen(
    labels: FundiLabels,
    step: SetupViewModel.Bench,
    newFile: () -> File?,
    onPhoto: (File) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onOwner: (ToolOwnership) -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    /** Every drafted tool answered or skipped: on to the typed list. */
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    // Saveable, so a rotation with the camera open does not drop the fundi back on the offer.
    var camera by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step) { if (step is SetupViewModel.Bench.Done) onDone() }

    if (camera) {
        CaptureScreen(
            instruction = labels.benchCameraInstruction,
            reviewPhotoPath = null,
            warning = null,
            processing = false,
            createFile = newFile,
            onCaptured = { file ->
                camera = false
                onPhoto(file)
            },
            onKeep = {},
            onRetake = {},
            onClose = { camera = false },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(labels.benchTitle, style = MaterialTheme.typography.headlineMedium)
        when (step) {
            SetupViewModel.Bench.Intro -> {
                Text(labels.benchBlurb, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { camera = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(labels.benchTakePhoto)
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(labels.benchSkip) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(labels.setupBack) }
            }

            SetupViewModel.Bench.Sending -> {
                Text(labels.benchSending, style = MaterialTheme.typography.bodyLarge)
                CircularProgressIndicator()
            }

            is SetupViewModel.Bench.Confirm -> {
                val tool = step.draft[step.index]
                // Capitalised where the name stands on its own after the colon, lower case
                // inside "Who owns the hand saw?".
                val name = ToolKind.fromId(tool.kind)?.let(labels::nameOf) ?: tool.kind
                val shown = ToolKind.fromId(tool.kind)?.let(labels::displayNameOf) ?: tool.kind
                if (!step.askingOwner) {
                    Text(labels.benchIsThere(shown), style = MaterialTheme.typography.titleLarge)
                    // Where Claude saw it, so the fundi can check the photo against the bench.
                    if (tool.seen.isNotBlank()) {
                        Text(
                            tool.seen,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onConfirm(true) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(labels.benchYes)
                    }
                    OutlinedButton(onClick = { onConfirm(false) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(labels.benchNo)
                    }
                } else {
                    Text(labels.benchWhoOwns(name), style = MaterialTheme.typography.titleLarge)
                    ToolOwnership.entries.filter { it.toHand }.forEach { owner ->
                        OutlinedButton(onClick = { onOwner(owner) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(labels.ownershipNames.getValue(owner))
                        }
                    }
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(labels.benchSkipRest) }
            }

            SetupViewModel.Bench.Done -> CircularProgressIndicator()

            is SetupViewModel.Bench.Failed -> {
                Text(
                    when (step.reason) {
                        SetupViewModel.Bench.Reason.LIMIT -> labels.benchLimit
                        SetupViewModel.Bench.Reason.UNAVAILABLE -> labels.benchUnavailable
                        SetupViewModel.Bench.Reason.UNREADABLE -> labels.benchUnreadable
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (step.reason) {
                    SetupViewModel.Bench.Reason.UNAVAILABLE ->
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(labels.benchRetry) }
                    SetupViewModel.Bench.Reason.UNREADABLE ->
                        Button(onClick = { camera = true }, modifier = Modifier.fillMaxWidth()) { Text(labels.benchTakePhoto) }
                    SetupViewModel.Bench.Reason.LIMIT -> Unit
                }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(labels.benchSkip) }
            }
        }
    }
}

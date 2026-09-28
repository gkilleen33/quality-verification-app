package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.fundi.ui.coach.DiagnosisCard
import com.qualityverifier.fundi.ui.coach.FixPlanCard
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.ui.capture.CaptureScreen
import com.qualityverifier.ui.capture.captureInstruction
import com.qualityverifier.ui.plan.InspectingScreen
import com.qualityverifier.ui.plan.PhysicalTestsScreen
import com.qualityverifier.ui.plan.PlanActionBar
import com.qualityverifier.ui.plan.PlanCard

/** Which part of the loop the maker is in. The conversation decides; this only names it. */
private enum class Stage { OPENING_PHOTO, TALKING, SHOOTING, TESTING, INSPECTING }

/**
 * One assessment, end to end.
 *
 * The same camera and plan runner Kagua uses — `:capture`, unchanged — with the coaching
 * cards in place of the verdict. Mockup scene 6 is that shot runner verbatim, down to
 * "Same eyes as the buyer's app", and this is where that stops being an aspiration.
 */
@Composable
fun AssessScreen(sessionId: String, itemType: ItemType, onDone: () -> Unit) {
    val container = fundiContainer()
    val viewModel: AssessViewModel = viewModel(
        factory = AssessViewModel.factory(container, sessionId, itemType),
    )

    val messages by viewModel.messages.collectAsState()
    val run by viewModel.run.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val error by viewModel.error.collectAsState()

    val labels = FundiLabels.ENGLISH
    // The capture pipeline speaks ReportLabels — the shot counter, the test wording, the
    // "send for inspection" button. Shared with Kagua on purpose: the camera does not
    // change because the person holding it makes furniture rather than buys it.
    val reportLabels = ReportLabels.ENGLISH

    var opened by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(Stage.OPENING_PHOTO) }

    // A plan is picked up from the conversation rather than from the reply, so it
    // survives the screen being rebuilt.
    LaunchedEffect(messages) {
        messages.lastOrNull { it.role == Role.ASSISTANT }?.let(viewModel::offerPlanFrom)
    }
    LaunchedEffect(run) { if (run != null && stage == Stage.TALKING) stage = Stage.TALKING }

    when {
        !opened || stage == Stage.OPENING_PHOTO -> {
            CaptureScreen(
                instruction = "Take one photo of the whole piece.",
                reviewPhotoPath = null,
                warning = null,
                createFile = viewModel::newCaptureFile,
                onCaptured = { file ->
                    opened = true
                    stage = Stage.TALKING
                    viewModel.start(file.absolutePath)
                },
                onKeep = {},
                onRetake = {},
                onClose = onDone,
            )
        }

        stage == Stage.SHOOTING && run != null -> {
            val current = run!!
            val index = current.nextShot
            if (index == null) {
                stage = Stage.TALKING
            } else {
                val shot = current.plan.photos[index]
                CaptureScreen(
                    instruction = shot.instruction.ifBlank { shot.title },
                    reviewPhotoPath = null,
                    warning = null,
                    createFile = viewModel::newCaptureFile,
                    onCaptured = { file -> viewModel.attachShot(index, file.absolutePath) },
                    onKeep = {},
                    onRetake = {},
                    onClose = { stage = Stage.TALKING },
                    counter = reportLabels.shotOf(index + 1, current.plan.photos.size),
                    skipLabel = reportLabels.cannotDoThis,
                    onSkip = { viewModel.skipShot(index) },
                    takenPaths = current.takenPaths,
                )
            }
        }

        stage == Stage.TESTING && run != null -> {
            val current = run!!
            val index = current.nextTest
            if (index == null) {
                stage = Stage.TALKING
            } else {
                PhysicalTestsScreen(
                    test = current.plan.tests[index],
                    index = index,
                    total = current.plan.tests.size,
                    labels = reportLabels,
                    onAnswer = { viewModel.answerTest(index, it) },
                    onSkip = { viewModel.skipTest(index) },
                    onBack = { stage = Stage.TALKING },
                )
            }
        }

        stage == Stage.INSPECTING && run != null -> InspectingScreen(run!!, reportLabels)

        else -> Conversation(
            messages = messages,
            run = run,
            sending = sending,
            error = error?.message,
            labels = labels,
            reportLabels = reportLabels,
            onStartCamera = { stage = Stage.SHOOTING },
            onStartTests = { stage = Stage.TESTING },
            onSubmit = {
                stage = Stage.TALKING
                viewModel.submitRun()
            },
            onRetakeShot = { viewModel.retakeShot(it); stage = Stage.SHOOTING },
            onChangeAnswer = { viewModel.answerTest(it, ""); stage = Stage.TESTING },
        )
    }
}

@Composable
private fun Conversation(
    messages: List<com.qualityverifier.domain.ChatMessage>,
    run: com.qualityverifier.domain.PlanRun?,
    sending: Boolean,
    error: String?,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    onStartCamera: () -> Unit,
    onStartTests: () -> Unit,
    onSubmit: () -> Unit,
    onRetakeShot: (Int) -> Unit,
    onChangeAnswer: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                if (message.role == Role.ASSISTANT) {
                    val content = parseAssistantContent(message.text)
                    // Prose first, then whatever cards parsed. displayProse keeps the
                    // opening paragraph beside a plan or a fix plan and drops the repeat
                    // the model writes underneath, so a card is never the same words
                    // twice.
                    if (content.displayProse.isNotBlank()) {
                        Text(
                            markdownToPlainText(content.displayProse),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    content.diagnosis?.let {
                        DiagnosisCard(it, labels, reportLabels::severity)
                    }
                    content.fixPlan?.let { FixPlanCard(it, labels) }
                } else if (message.text.isNotBlank()) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            run?.let {
                item {
                    PlanCard(
                        run = it,
                        labels = reportLabels,
                        onRetakeShot = onRetakeShot,
                        onChangeAnswer = onChangeAnswer,
                    )
                }
            }

            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (sending) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                // The same honesty Kagua learned the hard way: a verdict turn takes long
                // enough that silence reads as a hang.
                Text(
                    reportLabels.stageExamining,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            run?.let {
                PlanActionBar(
                    run = it,
                    labels = reportLabels,
                    onStartCamera = onStartCamera,
                    onStartTests = onStartTests,
                    onSubmit = onSubmit,
                )
            }
        }
    }
}

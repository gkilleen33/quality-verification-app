package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.coach.DiagnosisCard
import com.qualityverifier.fundi.ui.coach.FixPlanCard
import com.qualityverifier.fundi.ui.coach.VerdictCard
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.RetryLabels
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.ui.capture.CaptureScreen
import com.qualityverifier.ui.plan.InspectingScreen
import com.qualityverifier.ui.plan.WorkingText
import com.qualityverifier.ui.plan.PhysicalTestsScreen
import com.qualityverifier.ui.plan.PlanActionBar
import com.qualityverifier.ui.plan.PlanCard
import com.qualityverifier.ui.turn.FailedTurnRow
import com.qualityverifier.ui.turn.UnansweredTurnRow
import java.io.File

/** Which part of the loop the maker is in. */
private enum class Stage { LOADING, INTAKE, OPENING_PHOTO, TALKING, SHOOTING, TESTING, INSPECTING }

/**
 * One assessment, end to end: intake, opening photograph, conversation.
 *
 * The same camera and plan runner Kagua uses — `:capture`, unchanged — with the coaching
 * cards in place of the verdict.
 */
@Composable
fun AssessScreen(
    sessionId: String,
    itemType: ItemType,
    pieceId: String,
    returning: Boolean,
    onDone: () -> Unit,
) {
    val container = fundiContainer()
    val viewModel: AssessViewModel = viewModel(
        factory = AssessViewModel.factory(container, sessionId, itemType, pieceId, returning),
    )

    val existing by viewModel.existing.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val run by viewModel.run.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val error by viewModel.error.collectAsState()
    val language by viewModel.language.collectAsState()
    val purpose by viewModel.purpose.collectAsState()
    val priorIssues by viewModel.priorIssues.collectAsState()
    val pieceName by viewModel.pieceLabel.collectAsState()
    val roster by viewModel.roster.collectAsState()
    val previousWorkers by viewModel.previousWorkers.collectAsState()
    val review by viewModel.review.collectAsState()
    val openingSent by viewModel.openingSent.collectAsState()
    val processing by viewModel.processing.collectAsState()
    val unanswered by viewModel.unansweredTurn.collectAsState()

    val labels = FundiLabels.ENGLISH
    // The capture pipeline and the verdict speak ReportLabels, and follow the language
    // chosen at intake — the rule Kagua keeps, so a Kiswahili conversation does not get
    // English shot counters. Fundi Bora's own card headings are English until issue #20.
    val reportLabels = ReportLabels.forLanguage(language.code)

    var stage by remember { mutableStateOf(Stage.LOADING) }
    var intake by remember { mutableStateOf<FundiIntake?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var workedOn by remember { mutableStateOf<List<WorkerSlot>>(emptyList()) }

    // A finished assessment opens on its conversation; a new one on its intake. Decided
    // once it is known which this is, never from the message list, which arrives late.
    // An intake already sent counts as finished: `existing` is read once when the view
    // model starts, and a screen rebuilt after the intake went out (a rotation resets
    // `stage`) would otherwise walk the maker through the intake again.
    LaunchedEffect(existing, openingSent) {
        if (stage != Stage.LOADING) return@LaunchedEffect
        when {
            existing == true || openingSent -> stage = Stage.TALKING
            existing == false -> stage = Stage.INTAKE
        }
    }

    // The opening photograph is kept only after it is processed, and after the maker has
    // answered a quality warning if there was one; the intake is sent then, and the
    // conversation opens. Keyed on the stage too, not only the flag: a maker who reaches
    // the opening camera with the intake already out must not be left on it.
    LaunchedEffect(openingSent, stage) {
        if (openingSent && stage == Stage.OPENING_PHOTO) stage = Stage.TALKING
    }

    // A photograph belongs to the stage it was taken in. Every change of stage leaves the
    // camera, by a button or not: the last shot kept, the opening sent, or the screen
    // rebuilt at LOADING. Finished on another stage, a dark opening photograph would come
    // up for review on a plan shot's camera and send the intake a second time.
    LaunchedEffect(stage) { viewModel.leaveCamera() }

    // A plan is picked up from the conversation rather than from the reply, so it
    // survives the screen being rebuilt.
    LaunchedEffect(messages) { viewModel.offerPlanFromLatest(messages) }

    when (stage) {
        Stage.LOADING -> Box(Modifier.fillMaxSize())

        Stage.INTAKE -> IntakeFlow(
            labels = labels,
            returning = returning,
            priorIssues = priorIssues,
            initialName = pieceName,
            roster = roster,
            initialWorkers = previousWorkers,
            onAddWorker = viewModel::addWorker,
            onComplete = { answered, pieceLabel, people ->
                intake = answered
                name = pieceLabel
                workedOn = people
                stage = Stage.OPENING_PHOTO
            },
            onCancel = onDone,
        )

        Stage.OPENING_PHOTO -> {
            val answered = intake ?: return
            val optional = answered.purpose.photoOptional
            CaptureScreen(
                instruction = labels.photoInstructions[answered.purpose],
                reviewPhotoPath = review?.path,
                warning = review?.warning,
                processing = processing,
                createFile = viewModel::newCaptureFile,
                // Processed and checked first, as in Kagua. The screen leaves the camera
                // when the photograph is kept and the intake goes with it (openingSent).
                onCaptured = { file ->
                    viewModel.onPhotoCaptured(
                        file,
                        PhotoTarget.Opening(answered, name, workedOn),
                    )
                },
                onKeep = viewModel::keepReviewedPhoto,
                onRetake = viewModel::discardReviewedPhoto,
                onClose = {
                    // A photograph still processing or under review was never kept, and
                    // must not send the intake after the maker has gone back to it.
                    viewModel.leaveCamera()
                    stage = Stage.INTAKE
                },
                skipLabel = labels.skipPhoto.takeIf { optional },
                onSkip = if (optional) {
                    {
                        viewModel.leaveCamera()
                        stage = Stage.TALKING
                        viewModel.start(answered, null, name, workedOn)
                    }
                } else {
                    null
                },
            )
        }

        Stage.SHOOTING -> {
            val current = run
            val index = current?.nextShot
            if (current == null || index == null) {
                stage = Stage.TALKING
            } else {
                val shot = current.plan.photos[index]
                CaptureScreen(
                    instruction = shot.instruction.ifBlank { shot.title },
                    reviewPhotoPath = review?.path,
                    warning = review?.warning,
                    processing = processing,
                    createFile = viewModel::newCaptureFile,
                    onCaptured = { file ->
                        viewModel.onPhotoCaptured(file, PhotoTarget.PlanShot(index))
                    },
                    onKeep = viewModel::keepReviewedPhoto,
                    onRetake = viewModel::discardReviewedPhoto,
                    onClose = {
                        viewModel.leaveCamera()
                        stage = Stage.TALKING
                    },
                    counter = reportLabels.shotOf(index + 1, current.plan.photos.size),
                    skipLabel = reportLabels.cannotDoThis,
                    onSkip = { viewModel.skipShot(index) },
                    takenPaths = current.takenPaths,
                )
            }
        }

        Stage.TESTING -> {
            val current = run
            val index = current?.nextTest
            if (current == null || index == null) {
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

        Stage.INSPECTING -> run?.let { InspectingScreen(it, reportLabels) }

        Stage.TALKING -> Conversation(
            messages = messages,
            run = run,
            sending = sending,
            error = error,
            unanswered = unanswered,
            purpose = purpose,
            labels = labels,
            reportLabels = reportLabels,
            onSend = viewModel::send,
            onRetry = viewModel::retry,
            onDismissError = viewModel::dismissError,
            onStartCamera = { stage = Stage.SHOOTING },
            onStartTests = { stage = Stage.TESTING },
            onSubmit = viewModel::submitRun,
            onRetakeShot = { viewModel.retakeShot(it); stage = Stage.SHOOTING },
            onChangeAnswer = { viewModel.answerTest(it, ""); stage = Stage.TESTING },
        )
    }
}

@Composable
private fun Conversation(
    messages: List<ChatMessage>,
    run: PlanRun?,
    sending: Boolean,
    error: AssessError?,
    /** The maker's last turn has no reply and nothing is in flight. */
    unanswered: Boolean,
    purpose: FundiPurpose?,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onStartCamera: () -> Unit,
    onStartTests: () -> Unit,
    onSubmit: () -> Unit,
    onRetakeShot: (Int) -> Unit,
    onChangeAnswer: (Int) -> Unit,
) {
    val firstUserId = messages.firstOrNull { it.role == Role.USER }?.id
    val lastAssistant = messages.lastOrNull { it.role == Role.ASSISTANT }
    val awaitingFirstReply = messages.none { it.role == Role.ASSISTANT }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { _, message ->
                when {
                    message.role == Role.ASSISTANT -> AssistantTurn(message, labels, reportLabels)
                    // Written by the app on the maker's behalf. The model reads every
                    // word; the maker sees what they did — which purpose, which photos —
                    // rather than the app printing its own prompt back at them.
                    message.composed -> ComposedTurn(
                        message = message,
                        caption = when {
                            message.id == firstUserId && purpose != null -> labels.nameOf(purpose)
                            message.attachments.isNotEmpty() -> labels.sentPhotos(message.attachments.size)
                            else -> labels.sentAnswers
                        },
                    )
                    else -> TypedTurn(message)
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
        }

        // Below the conversation and above whatever answers it, as in Kagua. Retry only for
        // a turn that failed on its way to the model; a photograph that would not process,
        // or a sign-in failure, is not fixed by sending again.
        error?.let {
            FailedTurnRow(
                message = it.message,
                labels = RetryLabels.ENGLISH,
                onRetry = if (it.retryable) onRetry else null,
                onDismiss = onDismissError,
            )
        }
        // Only when there is no live error: a turn that just failed already has a row with
        // a Retry on it, and two prompts to do the same thing is worse than one.
        if (error == null && unanswered) {
            UnansweredTurnRow(RetryLabels.ENGLISH, onSend = onRetry)
        }

        when {
            sending -> Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                // A line that keeps moving, so a long wait does not read as a hang. Every
                // phrase is true for the whole wait — see WorkingText.
                WorkingText(
                    if (awaitingFirstReply) reportLabels.workingPlan else reportLabels.workingReply,
                    style = MaterialTheme.typography.bodyLarge,
                )
                // The first reply builds a plan from nothing and is the long wait, so it
                // still says so underneath.
                if (awaitingFirstReply) {
                    Text(
                        labels.processing,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            run != null -> PlanActionBar(
                run = run,
                labels = reportLabels,
                onStartCamera = onStartCamera,
                onStartTests = onStartTests,
                onSubmit = onSubmit,
            )

            // No new turn while the last one has no reply. Only the newest turn goes on
            // the wire, so a reply typed over an unanswered opening turn would start the
            // assessment with the opening turn's words and photograph never sent.
            unanswered -> Unit

            else -> ReplyBar(
                options = lastAssistant?.let { parseAssistantContent(it.text).options }.orEmpty(),
                labels = labels,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun AssistantTurn(message: ChatMessage, labels: FundiLabels, reportLabels: ReportLabels) {
    val content = parseAssistantContent(message.text)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (content.displayProse.isNotBlank()) {
            Text(markdownToPlainText(content.displayProse), style = MaterialTheme.typography.bodyLarge)
        }
        // A verdict means a full assessment; drawn first because it is the answer the
        // maker came for.
        content.verdict?.let { VerdictCard(it, reportLabels) }
        content.diagnosis?.let { DiagnosisCard(it, labels, reportLabels::severity) }
        content.fixPlan?.let { FixPlanCard(it, labels) }
    }
}

@Composable
private fun ComposedTurn(message: ChatMessage, caption: String) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumbnails(message)
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypedTurn(message: ChatMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Thumbnails(message)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Thumbnails(message: ChatMessage) {
    if (message.attachments.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(message.attachments, key = { it.id }) { attachment ->
            AsyncImage(
                model = File(attachment.path),
                contentDescription = "Photo of the piece",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

/**
 * Where the maker answers.
 *
 * The first build had no way to reply at all, so the diagnosis's one clarifying question
 * could be asked and never answered — and the tailored flows, which are nothing but
 * questions, could not work. The chips are the model's own suggested replies from
 * `qv-options`; the field is for anything else.
 */
@Composable
private fun ReplyBar(options: List<String>, labels: FundiLabels, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (options.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options) { option ->
                    AssistChip(onClick = { onSend(option) }, label = { Text(option) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(labels.replyHint) },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Text(labels.send) }
        }
    }
}

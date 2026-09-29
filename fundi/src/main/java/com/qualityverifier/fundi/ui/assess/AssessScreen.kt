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
import com.qualityverifier.fundi.ui.coach.DiagnosisCard
import com.qualityverifier.fundi.ui.coach.FixPlanCard
import com.qualityverifier.fundi.ui.coach.VerdictCard
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.ui.capture.CaptureScreen
import com.qualityverifier.ui.plan.InspectingScreen
import com.qualityverifier.ui.plan.PhysicalTestsScreen
import com.qualityverifier.ui.plan.PlanActionBar
import com.qualityverifier.ui.plan.PlanCard
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

    val labels = FundiLabels.ENGLISH
    // The capture pipeline and the verdict speak ReportLabels, and follow the language
    // chosen at intake — the rule Kagua keeps, so a Kiswahili conversation does not get
    // English shot counters. Fundi Bora's own card headings are English until issue #20.
    val reportLabels = ReportLabels.forLanguage(language.code)

    var stage by remember { mutableStateOf(Stage.LOADING) }
    var intake by remember { mutableStateOf<FundiIntake?>(null) }

    // A finished assessment opens on its conversation; a new one on its intake. Decided
    // once it is known which this is, never from the message list, which arrives late.
    LaunchedEffect(existing) {
        when (existing) {
            true -> if (stage == Stage.LOADING) stage = Stage.TALKING
            false -> if (stage == Stage.LOADING) stage = Stage.INTAKE
            null -> Unit
        }
    }

    // A plan is picked up from the conversation rather than from the reply, so it
    // survives the screen being rebuilt.
    LaunchedEffect(messages) {
        messages.lastOrNull { it.role == Role.ASSISTANT }?.let(viewModel::offerPlanFrom)
    }

    when (stage) {
        Stage.LOADING -> Box(Modifier.fillMaxSize())

        Stage.INTAKE -> IntakeFlow(
            labels = labels,
            returning = returning,
            priorIssues = priorIssues,
            onComplete = { answered ->
                intake = answered
                stage = Stage.OPENING_PHOTO
            },
            onCancel = onDone,
        )

        Stage.OPENING_PHOTO -> {
            val answered = intake ?: return
            val optional = answered.purpose.photoOptional
            CaptureScreen(
                instruction = labels.photoInstructions[answered.purpose],
                reviewPhotoPath = null,
                warning = null,
                createFile = viewModel::newCaptureFile,
                onCaptured = { file ->
                    stage = Stage.TALKING
                    viewModel.start(answered, file.absolutePath)
                },
                onKeep = {},
                onRetake = {},
                onClose = { stage = Stage.INTAKE },
                skipLabel = labels.skipPhoto.takeIf { optional },
                onSkip = if (optional) {
                    {
                        stage = Stage.TALKING
                        viewModel.start(answered, null)
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
            error = error?.message,
            purpose = purpose,
            labels = labels,
            reportLabels = reportLabels,
            onSend = viewModel::send,
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
    error: String?,
    purpose: FundiPurpose?,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    onSend: (String) -> Unit,
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
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }

        when {
            sending -> Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    // The first reply builds a plan from nothing and is the long wait,
                    // so it says so. Later turns are shorter and say less.
                    if (awaitingFirstReply) labels.processing else reportLabels.stageExamining,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            run != null -> PlanActionBar(
                run = run,
                labels = reportLabels,
                onStartCamera = onStartCamera,
                onStartTests = onStartTests,
                onSubmit = onSubmit,
            )

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

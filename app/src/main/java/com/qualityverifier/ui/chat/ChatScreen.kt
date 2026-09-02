package com.qualityverifier.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.text.AssistantContent
import com.qualityverifier.ui.capture.CaptureScreen
import com.qualityverifier.ui.capture.captureInstruction
import com.qualityverifier.ui.plan.InspectingScreen
import com.qualityverifier.ui.plan.PhysicalTestsScreen
import com.qualityverifier.ui.intake.IntakeScreen
import com.qualityverifier.ui.plan.PlanActionBar
import com.qualityverifier.ui.plan.PlanCard
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.rememberReportLabels
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    itemType: ItemType?,
    /** Intake answers carried in from the assessment this one was started from. */
    intakePrefill: AssessmentContext? = null,
    /** The assessment this one was started from, which it can be compared against. */
    previousSessionId: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Starts another assessment of the same kind, carrying this one's answers. */
    onAssessAnother: (ItemType, AssessmentContext?) -> Unit = { _, _ -> },
    /** Back to the grid: a different kind of piece needs its own intake. */
    onAssessDifferent: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = appContainer()
    val viewModel: ChatViewModel = viewModel(
        key = sessionId,
        factory = ChatViewModel.factory(
            container = container,
            sessionId = sessionId,
            itemType = itemType,
            intakePrefill = intakePrefill,
            previousSessionId = previousSessionId,
        ),
    )

    val messages by viewModel.messages.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val error by viewModel.error.collectAsState()
    val unansweredTurn by viewModel.unansweredTurn.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val resolvedItemType by viewModel.itemType.collectAsState()
    val review by viewModel.review.collectAsState()
    val run by viewModel.run.collectAsState()
    val submitting by viewModel.submitting.collectAsState()
    val submittedRun by viewModel.submittedRun.collectAsState()
    val needsIntake by viewModel.needsIntake.collectAsState()
    val awaitingOpeningPhoto by viewModel.awaitingOpeningPhoto.collectAsState()
    val carryForward by viewModel.carryForward.collectAsState()
    val previousVerdict by viewModel.previousVerdict.collectAsState()
    val comparisonRequested by viewModel.comparisonRequested.collectAsState()

    var draft by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    // Which part of a collection run is on screen. Kept here rather than in the view
    // model because it is navigation, not state worth surviving a process death.
    var runMode by remember { mutableStateOf(RunMode.NONE) }
    val listState = rememberLazyListState()

    // Parsing is keyed on the message list so it happens once per new turn rather than
    // on every recomposition — a long assessment is a lot of text to re-scan while the
    // user is typing.
    val parsed = remember(messages) {
        messages.associate { it.id to parseAssistantContent(it.text) }
    }
    val lastAssistant = messages.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }
    val replyOptions = if (sending) emptyList() else {
        parsed[lastAssistant?.id]?.options.orEmpty()
    }
    // The most recent verdict is the one worth sharing: a later one supersedes an
    // earlier one in the same conversation.
    val shareable = messages.asReversed().firstNotNullOfOrNull { message ->
        parsed[message.id]?.verdict?.let { message to it }
    }
    // Resolved here rather than in the click handler, so it reads the device language
    // in composable scope.
    val shareLabels = rememberReportLabels(shareable?.second?.language)
    val runLabels = rememberReportLabels(run?.plan?.language)
    // Drawn as the last item in the list rather than under the verdict, so follow-up
    // questions do not push it out of reach.
    val showNextSteps = shareable != null && run == null && !sending

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (viewModel.run.value != null) runMode = RunMode.CAPTURE else capturing = true
        } else {
            // Without this the camera button just does nothing, which reads as a broken
            // app. Also covers the permanently-denied case, where the system dialog
            // never appears at all.
            viewModel.showNotice(
                "Camera permission is off, so photos can't be taken. " +
                    "You can turn it on in your phone's settings, or choose a photo instead.",
            )
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK)
    ) { uris -> viewModel.onGalleryPicked(uris) }

    // One shot, then back to the conversation: the protocol asks for the next photo
    // only after looking at this one, so staying in the camera would be misleading.
    LaunchedEffect(pending.size) {
        if (capturing && review == null && pending.isNotEmpty()) capturing = false
    }

    // Keep the newest turn in view as the conversation grows — and the card after it,
    // when there is one. Scrolling only as far as the last message left "check another"
    // just below the fold, which is how the duplicated plan buried its own camera
    // button before it.
    LaunchedEffect(messages.size, sending, showNextSteps) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex + if (showNextSteps) 1 else 0)
        }
    }

    // Inspecting comes first: once submitted there is nothing else worth showing, and
    // the run has already been cleared from the view model.
    if (needsIntake) {
        IntakeScreen(
            itemType = resolvedItemType ?: ItemType.OTHER,
            prefill = viewModel.intakePrefill,
            // Inherited from the piece before it, which already settled which protocol
            // applies — so there is nothing for the upholstery question to decide.
            protocolSettled = previousSessionId != null,
            onComplete = { context, resolvedItemType ->
                viewModel.submitIntake(
                    context = context,
                    labels = ReportLabels.forLanguage(context.language?.code),
                    resolvedItemType = resolvedItemType,
                )
            },
            onBack = onBack,
        )
        return
    }

    // Straight from the last intake question into one photo of the whole piece, which is
    // sent with the context so the assistant can check its protocol against the actual
    // piece before planning seven shots of it.
    awaitingOpeningPhoto?.let { context ->
        val labels = rememberReportLabels(context.language?.code)
        CaptureScreen(
            instruction = labels.openingShotInstruction,
            reviewPhotoPath = review?.path,
            warning = review?.warning,
            createFile = viewModel::newCaptureFile,
            onCaptured = { file -> viewModel.onPhotoCaptured(file, CaptureTarget.Opening) },
            onKeep = viewModel::keepReviewedPhoto,
            onRetake = viewModel::discardReviewedPhoto,
            // Backing out starts the conversation without the photo rather than trapping
            // them on a camera screen.
            onClose = viewModel::skipOpeningPhoto,
        )
        return
    }

    submittedRun?.takeIf { submitting }?.let { inFlight ->
        InspectingScreen(inFlight, rememberReportLabels(inFlight.plan.language))
        return
    }

    val active = run
    if (active != null && runMode == RunMode.TESTS) {
        val testIndex = active.nextTest
        if (testIndex == null) {
            runMode = RunMode.NONE
        } else {
            PhysicalTestsScreen(
                test = active.plan.tests[testIndex],
                index = testIndex,
                total = active.plan.tests.size,
                labels = runLabels,
                onAnswer = { answer -> viewModel.answerTest(testIndex, answer) },
                onSkip = { viewModel.skipTest(testIndex) },
                onBack = { runMode = RunMode.NONE },
            )
            return
        }
    }

    if (active != null && runMode == RunMode.CAPTURE) {
        val shotIndex = active.nextShot
        if (shotIndex == null) {
            // Straight into the tests when the shots are done, as the mockup does. The
            // plan card is the review step afterwards.
            runMode = if (active.nextTest != null) RunMode.TESTS else RunMode.NONE
        } else {
            val shot = active.plan.photos[shotIndex]
            CaptureScreen(
                instruction = shot.instruction.ifBlank { shot.note }.ifBlank { shot.title },
                reviewPhotoPath = review?.path,
                warning = review?.warning,
                createFile = viewModel::newCaptureFile,
                onCaptured = { file ->
                    viewModel.onPhotoCaptured(file, CaptureTarget.PlanShot(shotIndex))
                },
                onKeep = viewModel::keepReviewedPhoto,
                onRetake = viewModel::discardReviewedPhoto,
                onClose = {
                    viewModel.discardReviewedPhoto()
                    runMode = RunMode.NONE
                },
                counter = runLabels.shotOf(shotIndex + 1, active.plan.photos.size),
                skipLabel = runLabels.cannotDoThis,
                onSkip = { viewModel.skipShot(shotIndex) },
                takenPaths = active.takenPaths,
            )
            return
        }
    }

    if (capturing) {
        CaptureScreen(
            // The assistant asks for one photo at a time, so its last message is the
            // shot instruction. Nothing to keep in sync.
            instruction = captureInstruction(parsed[lastAssistant?.id]?.displayProse),
            reviewPhotoPath = review?.path,
            warning = review?.warning,
            createFile = viewModel::newCaptureFile,
            onCaptured = viewModel::onPhotoCaptured,
            onKeep = {
                viewModel.keepReviewedPhoto()
                capturing = false
            },
            onRetake = viewModel::discardReviewedPhoto,
            onClose = {
                // Leaving with a photo still under review discards it: it was never
                // attached, and keeping it would mean an unexplained thumbnail appearing
                // in the composer.
                viewModel.discardReviewedPhoto()
                capturing = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(resolvedItemType?.displayName ?: "Assessment") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only offered once there is a verdict: sharing a half-finished
                    // walkthrough would send somebody a report that says nothing.
                    if (shareable != null) {
                        IconButton(
                            onClick = {
                                shareReport(
                                    context = context,
                                    itemType = resolvedItemType ?: ItemType.OTHER,
                                    verdict = shareable.second,
                                    at = shareable.first.createdAt,
                                    labels = shareLabels,
                                )
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share this report")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty() && !sending) {
                    Text(
                        "Starting the assessment…",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                content = parsed[message.id],
                                onAskQuestion = { question -> viewModel.send(question) },
                            )
                            if (active != null && message.id == active.sourceMessageId) {
                                Spacer(Modifier.height(10.dp))
                                PlanCard(
                                    run = active,
                                    labels = runLabels,
                                    onRetakeShot = viewModel::retakeShot,
                                    onChangeAnswer = viewModel::changeTestAnswer,
                                )
                            }
                        }
                        // Pinned to the end of the conversation rather than under the
                        // verdict itself, so that follow-up questions do not push it
                        // out of reach.
                        if (showNextSteps) {
                            item(key = "next-steps") {
                                Spacer(Modifier.height(6.dp))
                                NextStepsCard(
                                    itemName = shareLabels.itemName(
                                        resolvedItemType ?: ItemType.OTHER,
                                    ),
                                    canCompare = previousVerdict != null && !comparisonRequested,
                                    labels = shareLabels,
                                    onAssessAnother = {
                                        onAssessAnother(
                                            resolvedItemType ?: ItemType.OTHER,
                                            carryForward,
                                        )
                                    },
                                    onCompare = { viewModel.compareWithPrevious(shareLabels) },
                                    onAssessDifferent = onAssessDifferent,
                                )
                            }
                        }
                    }
                }
            }

            if (sending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (messages.isEmpty()) "Starting the check…" else "Looking at the furniture…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            error?.let { chatError -> ErrorRow(chatError, viewModel, onOpenSettings) }

            // Only when there is no live error: a turn that just failed already has a row
            // with a Retry on it, and two prompts to do the same thing is worse than one.
            if (error == null && unansweredTurn) {
                UnansweredTurnRow(onSend = { viewModel.retry() })
            }

            notice?.let { message -> NoticeRow(message, onDismiss = viewModel::dismissNotice) }

            active?.let { plan ->
                PlanActionBar(
                    run = plan,
                    labels = runLabels,
                    onStartCamera = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            runMode = RunMode.CAPTURE
                        } else {
                            requestCameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onStartTests = { runMode = RunMode.TESTS },
                    onSubmit = { viewModel.submitRun(runLabels) },
                )
            }

            if (replyOptions.isNotEmpty()) {
                // The question is in the message too, so these are a shortcut rather
                // than the only way to answer — typing something else still works.
                //
                // Wrapping rather than scrolling horizontally: on the emulator a row of
                // three Swahili options ran off the screen edge with no hint that
                // anything was there, so a third of the answers were invisible.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    replyOptions.forEach { option ->
                        AssistChip(
                            onClick = { viewModel.send(option) },
                            label = { Text(option) },
                        )
                    }
                }
            }

            if (pending.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pending, key = { it.id }) { image ->
                        Box {
                            AsyncImage(
                                model = File(image.path),
                                contentDescription = "Attached photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            IconButton(
                                onClick = { viewModel.removePending(image.id) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) capturing = true else {
                                requestCameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
                    }
                    IconButton(
                        onClick = {
                            pickImages.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose photo")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Ask about this furniture") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            viewModel.send(draft)
                            draft = ""
                        },
                        enabled = !sending && (draft.isNotBlank() || pending.isNotEmpty()),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(
    chatError: ChatError,
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                chatError.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row {
                if (chatError.kind == ChatErrorKind.AUTH) {
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                } else {
                    TextButton(onClick = { viewModel.retry() }) { Text("Retry") }
                }
                TextButton(onClick = { viewModel.dismissError() }) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Offered when the customer's last turn never got a reply.
 *
 * Deliberately not styled as an error. By the time somebody sees this the failure is old
 * news, the photos and answers are safe on the phone, and the only thing missing is the
 * reply — so this reads as unfinished work to pick up rather than something broken.
 *
 * No Dismiss. The state is the conversation itself, not a message about it, so dismissing
 * would either lie until the next reopen or need somewhere to record that the customer
 * gave up on a turn. Leaving the assessment is the way out, and it is one tap away.
 */
@Composable
private fun UnansweredTurnRow(onSend: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "This didn't get an answer. Your photos and answers are still here — " +
                    "send it again when you have signal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onSend) { Text("Send again") }
        }
    }
}

/** Advisory banner with no retry — the action failed for a reason retrying won't fix. */
@Composable
private fun NoticeRow(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    content: AssistantContent?,
    onAskQuestion: (String) -> Unit,
) {
    val fromUser = message.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        if (message.attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(message.attachments, key = { it.id }) { attachment ->
                    AsyncImage(
                        model = File(attachment.path),
                        contentDescription = "Photo of the furniture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        // An assistant turn is shown as the verdict cards when it carries one, and as a
        // bubble otherwise. The prose duplicate the prompt also produced is only used
        // when the structured block failed to parse — see AssistantContent.
        val verdict = if (fromUser) null else content?.verdict
        if (verdict != null) {
            VerdictCards(
                verdict = verdict,
                onAskQuestion = onAskQuestion,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        val body = if (fromUser) message.text else content?.displayProse ?: message.text
        if (body.isNotBlank()) {
            Surface(
                color = if (fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(16.dp),
                tonalElevation = if (fromUser) 0.dp else 2.dp,
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                if (fromUser) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    MarkdownText(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_IMAGES_PER_PICK = 5

/** Which screen of a collection run is showing. */
private enum class RunMode { NONE, CAPTURE, TESTS }

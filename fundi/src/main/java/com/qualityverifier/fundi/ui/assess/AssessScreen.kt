package com.qualityverifier.fundi.ui.assess

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.qualityverifier.data.session.PieceStudioChoice
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.DefectMark
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PlanRun
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.TestNavigation
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.fundi.ui.coach.DiagnosisCard
import com.qualityverifier.fundi.ui.coach.FixPlanCard
import com.qualityverifier.fundi.ui.coach.ImprovementList
import com.qualityverifier.fundi.ui.coach.ImprovementPage
import com.qualityverifier.fundi.ui.coach.VerdictCard
import com.qualityverifier.fundi.ui.coach.verdictCardProblems
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.DetailAnswers
import com.qualityverifier.text.DetailKind
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.PageQuestions
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.RetryLabels
import com.qualityverifier.text.detailAnswersOf
import com.qualityverifier.text.improvementRows
import com.qualityverifier.text.isImprovementList
import com.qualityverifier.text.pageQuestionsOf
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.ReplyChip
import com.qualityverifier.text.replyChips
import com.qualityverifier.text.translatedConversation
import com.qualityverifier.text.translatedRun
import com.qualityverifier.ui.capture.CaptureScreen
import com.qualityverifier.ui.photo.PhotoMark
import com.qualityverifier.ui.photo.PhotoViewer
import com.qualityverifier.ui.plan.InspectingScreen
import com.qualityverifier.ui.plan.WorkingText
import com.qualityverifier.ui.plan.PhysicalTestsScreen
import com.qualityverifier.ui.plan.PlanActionBar
import com.qualityverifier.ui.plan.PlanCard
import com.qualityverifier.ui.turn.FailedTurnRow
import com.qualityverifier.ui.turn.UnansweredTurnRow
import java.io.File

/** Which part of the loop the maker is in. */
private enum class Stage { LOADING, TODAY, INTAKE, OPENING_PHOTO, TALKING, SHOOTING, TESTING, INSPECTING }

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
    /** The final assessment of the finished piece, before a customer report (v0.6.0, row 27). */
    isFinal: Boolean = false,
    /** False for a linked worker's piece, assessed from the worker's page (row 11). */
    pieceMine: Boolean = true,
    /** The worker's name for their piece, when [pieceMine] is false. */
    givenLabel: String? = null,
) {
    val container = fundiContainer()
    val viewModel: AssessViewModel = viewModel(
        factory = AssessViewModel.factory(container, sessionId, itemType, pieceId, returning, isFinal, pieceMine, givenLabel),
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
    val studios by viewModel.studios.collectAsState()
    val askToday by viewModel.askToday.collectAsState()
    val reviewHelpful by viewModel.reviewHelpful.collectAsState()
    val photoNumbers by viewModel.photoNumbers.collectAsState()
    val askFinished by viewModel.askFinished.collectAsState()
    val shownLanguage by viewModel.shownLanguage.collectAsState()
    val shownTranslations by viewModel.shownTranslations.collectAsState()
    val translationState by viewModel.translationState.collectAsState()

    val labels = FundiLabels.ENGLISH
    // The language chosen at intake: the language every turn the app writes is in, the
    // submission of a plan's run included — the rule Kagua keeps.
    val reportLabels = ReportLabels.forLanguage(language.code)
    // The conversation's headings and buttons follow the English and Kiswahili switch (row 29),
    // as its replies do, and so do the camera's and the tests' screens, so a Kiswahili
    // conversation does not get English shot counters. The intake, before the switch exists,
    // stays English.
    val shownLabels = FundiLabels.forLanguage(shownLanguage.code)
    val shownReportLabels = ReportLabels.forLanguage(shownLanguage.code)
    // The plan's run as drawn (row 29): its instructions, questions and answer buttons in the
    // language shown, from the translation of the reply that issued it. Computed here rather
    // than collected, so it is never a frame behind the run. Drawn only: every tap goes back by
    // place, and the run the app submits is `run`, in Claude's words.
    val shownRun = remember(run, shownTranslations, reportLabels, shownReportLabels) {
        run?.let { translatedRun(it, shownTranslations[it.sourceMessageId], reportLabels.notSure, shownReportLabels.notSure) }
    }
    val shownMessages = remember(messages, shownTranslations) {
        translatedConversation(messages, shownTranslations).messages
    }

    var stage by remember { mutableStateOf(Stage.LOADING) }
    // The earlier physical test the maker went Back to, or null for the first one still
    // unanswered (row 40). Apart from the run's own progress so going back deletes nothing.
    var revisitingTest by rememberSaveable { mutableStateOf<Int?>(null) }
    var intake by remember { mutableStateOf<FundiIntake?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var workedOn by remember { mutableStateOf<List<WorkerSlot>>(emptyList()) }
    var studio by remember { mutableStateOf<PieceStudioChoice>(PieceStudioChoice.NotAsked) }

    // A finished assessment opens on its conversation; a new one on its intake, after
    // "Which studio are you at today?" on the day's first. Decided once it is known which
    // this is, never from the message list, which arrives late. An intake already sent
    // counts as finished: `existing` is read once when the view model starts, and a screen
    // rebuilt after the intake went out (a rotation resets `stage`) would otherwise walk
    // the maker through the intake again.
    LaunchedEffect(existing, openingSent, askToday) {
        if (stage != Stage.LOADING) return@LaunchedEffect
        when {
            existing == true || openingSent -> stage = Stage.TALKING
            existing == false && askToday == true -> stage = Stage.TODAY
            existing == false && askToday == false -> stage = Stage.INTAKE
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

    // The photograph open in the viewer, by path, or null for none. Saved across a
    // rotation, which rebuilds the screen through LOADING: the viewer should still be
    // open afterwards, on the same photograph. The path rather than a position in the
    // list, because the list grows as shots are taken.
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    // The defect's point on [viewing] when it was opened from a finding (row 19): x then y,
    // fractions of the photo. Null when it was opened from a thumbnail.
    var marked by rememberSaveable { mutableStateOf<FloatArray?>(null) }
    val photos = remember(shownMessages, shownRun, purpose, shownLabels, shownReportLabels, reportLabels, viewing, marked) {
        val mark = marked?.let { PhotoMark(it[0], it[1]) }
        // Captioned with the shot titles as shown; a submission turn is read in the intake's words.
        assessmentPhotos(shownMessages, shownRun, purpose, shownLabels, shownReportLabels, writtenIn = reportLabels)
            .map { if (mark != null && it.path == viewing) it.copy(mark = mark) else it }
    }
    // The improvement page open over the conversation (row 43): the reply whose list it came
    // from, by message id, and the row's place in that list. Saved across a rotation, as the
    // viewer is.
    var pageReply by rememberSaveable { mutableStateOf<String?>(null) }
    var pagePosition by rememberSaveable { mutableIntStateOf(0) }
    val viewingIndex = photos.indexOfFirst { it.path == viewing }
    // A photograph deleted while open — a shot retaken from the plan card cannot be,
    // but a list that no longer holds the path must close rather than show nothing.
    LaunchedEffect(viewing, photos) {
        if (viewing != null && viewingIndex < 0) viewing = null
    }

    // The viewer is drawn over the stage rather than navigated to, so closing it finds
    // the conversation scrolled exactly where it was.
    Box(Modifier.fillMaxSize()) {
        when (stage) {
            Stage.LOADING -> Box(Modifier.fillMaxSize())

            Stage.TODAY -> StudioTodayQuestion(
                labels = labels,
                studios = studios,
                onAnswer = { answer ->
                    viewModel.answerToday(answer)
                    stage = Stage.INTAKE
                },
                onCancel = onDone,
            )

            Stage.INTAKE -> IntakeFlow(
                labels = labels,
                returning = returning,
                priorIssues = priorIssues,
                initialName = pieceName,
                itemType = itemType,
                roster = roster,
                initialWorkers = previousWorkers,
                onAddWorker = viewModel::addWorker,
                onComplete = { answered, pieceLabel, people, forStudio ->
                    intake = answered
                    name = pieceLabel
                    workedOn = people
                    studio = forStudio
                    stage = Stage.OPENING_PHOTO
                },
                onCancel = onDone,
                studios = studios,
                askName = pieceMine,
                fixedPurpose = if (isFinal) FundiPurpose.REEVALUATE else null,
                askFinished = askFinished,
                onFinished = viewModel::answerFinished,
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
                            PhotoTarget.Opening(answered, name, workedOn, studio),
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
                            viewModel.start(answered, null, name, workedOn, studio)
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
                    // The shot as shown (row 29); the photograph is kept by its place.
                    val shot = (shownRun ?: current).plan.photos[index]
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
                        counter = shownReportLabels.shotOf(index + 1, current.plan.photos.size),
                        skipLabel = shownReportLabels.cannotDoThis,
                        onSkip = { viewModel.skipShot(index) },
                        takenPaths = current.takenPaths,
                    )
                }
            }

            Stage.TESTING -> {
                val current = run
                val tests = TestNavigation(revisitingTest)
                val index = current?.let(tests::shown)
                if (current == null || index == null) {
                    revisitingTest = null
                    stage = Stage.TALKING
                } else {
                    val shown = shownRun ?: current
                    PhysicalTestsScreen(
                        // The question and its buttons as shown (row 29). A tap is recorded by
                        // the option's place, as the option Claude wrote; "Not sure" in the
                        // intake's language; typed words as typed.
                        test = shown.plan.tests[index],
                        index = index,
                        total = current.plan.tests.size,
                        labels = shownReportLabels,
                        // Replaces any earlier answer, then on to the first test with none.
                        onAnswer = { viewModel.answerTest(index, it); revisitingTest = tests.afterAnswer().revisiting },
                        onOption = { viewModel.answerTestOption(index, it); revisitingTest = tests.afterAnswer().revisiting },
                        onNotSure = { viewModel.answerTestNotSure(index); revisitingTest = tests.afterAnswer().revisiting },
                        onSkip = { viewModel.skipTest(index); revisitingTest = tests.afterAnswer().revisiting },
                        // Back opens the test before, its answer kept and selected (row 40);
                        // on the first test it leaves the tests as before.
                        onBack = {
                            val before = tests.back(current)
                            revisitingTest = before?.revisiting
                            if (before == null) stage = Stage.TALKING
                        },
                        selected = shown.answerShown(index),
                    )
                }
            }

            Stage.INSPECTING -> (shownRun ?: run)?.let { InspectingScreen(it, shownReportLabels) }

            Stage.TALKING -> Conversation(
                messages = messages,
                translations = shownTranslations,
                shownLanguage = shownLanguage,
                translationState = translationState,
                onShowLanguage = viewModel::showLanguage,
                onRetryTranslation = viewModel::retryTranslation,
                run = shownRun,
                sending = sending,
                error = error,
                unanswered = unanswered,
                purpose = purpose,
                labels = shownLabels,
                reportLabels = shownReportLabels,
                onSend = viewModel::send,
                onRequestDetail = viewModel::requestDetail,
                onAskAboutImprovement = viewModel::askAboutImprovement,
                openPage = pageReply?.let { it to pagePosition },
                onOpenPage = { reply, position -> pageReply = reply; pagePosition = position },
                onClosePage = { pageReply = null },
                onRetry = viewModel::retry,
                onDismissError = viewModel::dismissError,
                onStartCamera = { stage = Stage.SHOOTING },
                onStartTests = { revisitingTest = null; stage = Stage.TESTING },
                onSubmit = viewModel::submitRun,
                onRetakeShot = { viewModel.retakeShot(it); stage = Stage.SHOOTING },
                // Opens that test with its answer selected, as Back does (row 40). Storing ""
                // here, as before, left the run with no unanswered test, so the tests screen
                // closed at once and the answer stayed blank.
                onChangeAnswer = { revisitingTest = it; stage = Stage.TESTING },
                onOpenPhoto = { viewing = it; marked = null },
                onOpenMark = { path, mark ->
                    viewing = path
                    marked = floatArrayOf(mark.x.toFloat(), mark.y.toFloat())
                },
                photoNumbers = photoNumbers,
                isTester = viewModel.isTester,
                reviewHelpful = reviewHelpful,
                onAnswerHelpful = viewModel::answerReviewHelpful,
            )
        }

        if (viewingIndex >= 0) {
            PhotoViewer(
                photos = photos,
                initialIndex = viewingIndex,
                labels = shownReportLabels,
                onClose = { viewing = null; marked = null },
            )
        }
    }
}

@Composable
private fun Conversation(
    /** The conversation as stored: what every turn the app writes is written from. */
    messages: List<ChatMessage>,
    /** Each reply's translation into [shownLanguage], by message id; empty when shown as written (row 29). */
    translations: Map<String, String>,
    shownLanguage: AssessmentLanguage,
    translationState: TranslationState,
    onShowLanguage: (AssessmentLanguage) -> Unit,
    onRetryTranslation: () -> Unit,
    /** The plan's run as drawn, in the language shown (row 29); every tap on it goes back by place. */
    run: PlanRun?,
    sending: Boolean,
    error: AssessError?,
    /** The maker's last turn has no reply and nothing is in flight. */
    unanswered: Boolean,
    purpose: FundiPurpose?,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    onSend: (String) -> Unit,
    /** A detail button under a finding (row 37): which detail, of the finding with this title. */
    onRequestDetail: (DetailKind, String) -> Unit,
    /** A question on an improvement's page (row 43): its number, its title, the maker's words. */
    onAskAboutImprovement: (Int, String, String) -> Unit,
    /** The improvement page open: the reply's message id and the row's place in its list. */
    openPage: Pair<String, Int>?,
    onOpenPage: (String, Int) -> Unit,
    onClosePage: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onStartCamera: () -> Unit,
    onStartTests: () -> Unit,
    onSubmit: () -> Unit,
    onRetakeShot: (Int) -> Unit,
    onChangeAnswer: (Int) -> Unit,
    /** A tap on any photograph, by its path: opens it full screen. */
    onOpenPhoto: (String) -> Unit,
    /** A tap on a defect with a mark: opens its photo, by path, zoomed on the mark (row 19). */
    onOpenMark: (String, DefectMark) -> Unit,
    /** The server's number for each synced photo, by path (row 19). */
    photoNumbers: Map<String, Int>,
    /** Testers are asked "Was this review helpful?" after a verdict (row 7). */
    isTester: Boolean,
    reviewHelpful: Boolean?,
    onAnswerHelpful: (Boolean) -> Unit,
) {
    // The conversation as drawn (row 29): each translated reply in its original's place, and
    // the app's turns naming each improvement by its shown title, so everything below pairs
    // answers with improvements as it does in the original language. Never sent anywhere.
    val view = remember(messages, translations) { translatedConversation(messages, translations) }
    val shown = view.messages
    // A detail button's request and its answer show under the finding, not as turns (row 37),
    // and a page's question and its answer on that page (row 43).
    val details = remember(shown) { detailAnswersOf(shown) }
    val pages = remember(shown) { pageQuestionsOf(shown) }
    val turns = remember(shown, details, pages) {
        shown.filterNot { it.id in details.hidden || it.id in pages.hidden }
    }
    // Only while nothing else is in flight or waiting: one turn at a time goes on the wire.
    val canSend = !sending && !unanswered && run == null
    // A tap names the improvement by the title Claude wrote, whichever language is shown, so
    // the request, the stored answer it finds and the prompt's rule read as before. Found by
    // the improvement's place in its reply (from 1), never by the shown title, which two
    // improvements could share once translated.
    val requestDetail: ((DetailKind, String, Int) -> Unit)? =
        if (canSend) {
            { kind, replyId, number -> view.originalTitle(replyId, number)?.let { onRequestDetail(kind, it) } }
        } else {
            null
        }
    val firstUserId = messages.firstOrNull { it.role == Role.USER }?.id
    // The chips come from the last turn the maker can see, so an answer under a finding
    // does not take away the replies the diagnosis offered.
    val lastAssistant = turns.lastOrNull { it.role == Role.ASSISTANT }
    // Only the newest diagnosis asks for detail. An older card a correction replaced keeps
    // the answers it already has, but a tap there would ask about a finding that is gone.
    val latestDiagnosisId = remember(turns) {
        turns.lastOrNull { it.role == Role.ASSISTANT && parseAssistantContent(it.text).sentDiagnosis }?.id
    }
    val awaitingFirstReply = messages.none { it.role == Role.ASSISTANT }
    // A finding's button opens the photo Claude named, if this phone holds it.
    val showOnPhoto: (Finding) -> (() -> Unit)? = { finding ->
        finding.mark?.let { mark ->
            photoNumbered(messages, mark.photo, photoNumbers)?.let { path -> { onOpenMark(path, mark) } }
        }
    }
    val hasVerdict = remember(messages) {
        messages.any { it.role == Role.ASSISTANT && parseAssistantContent(it.text).verdict != null }
    }
    // The reply chips show in the language shown, and a tap sends the chip in the same place
    // as Claude wrote it (row 29): a translation never enters the conversation.
    val chips = remember(messages, lastAssistant) {
        replyChips(messages.firstOrNull { it.id == lastAssistant?.id }?.text, lastAssistant?.text)
    }
    // Once the newest diagnosis is a list, the box under the conversation is for the whole
    // piece, and says so (row 43): each improvement has its own on its page.
    val listShown = remember(turns, latestDiagnosisId) {
        turns.firstOrNull { it.id == latestDiagnosisId }
            ?.let { parseAssistantContent(it.text).diagnosis?.isImprovementList } == true
    }
    // The page's reply and its list, read from the stored conversation, so it opens offline.
    val pageRows = remember(shown, pages, openPage?.first) {
        openPage?.first?.let { id -> shown.firstOrNull { it.id == id } }
            ?.let { parseAssistantContent(it.text).diagnosis }
            ?.let { improvementRows(it, pages) }
    }
    // A page whose reply or row is gone (a list that changed under it) closes rather than
    // showing nothing.
    LaunchedEffect(openPage, pageRows) {
        val position = openPage?.second ?: return@LaunchedEffect
        if (pageRows == null || position !in pageRows.indices) onClosePage()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        LanguageSwitch(shownLanguage, labels, translationState, onShowLanguage, onRetryTranslation)
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(turns, key = { _, m -> m.id }) { _, message ->
                when {
                    message.role == Role.ASSISTANT ->
                        AssistantTurn(
                            message, labels, reportLabels, showOnPhoto, details,
                            requestDetail.takeIf { message.id == latestDiagnosisId }
                                ?.let { request -> { kind: DetailKind, number: Int -> request(kind, message.id, number) } },
                            pages = pages,
                            onOpenImprovement = { position -> onOpenPage(message.id, position) },
                        )
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
                        onOpenPhoto = onOpenPhoto,
                    )
                    else -> TypedTurn(message, onOpenPhoto)
                }
            }
            // One answer per assessment, below the conversation once there is a verdict, so it
            // follows whichever verdict came last. Never sent to Claude.
            if (isTester && hasVerdict) {
                item(key = "review-helpful") {
                    ReviewHelpfulCard(labels, reviewHelpful, onAnswerHelpful)
                }
            }
            run?.let {
                item {
                    PlanCard(
                        run = it,
                        labels = reportLabels,
                        onRetakeShot = onRetakeShot,
                        onChangeAnswer = onChangeAnswer,
                        onOpenShot = { index -> it.shots[index]?.let(onOpenPhoto) },
                    )
                }
            }
        }

        TurnStatusRows(error, unanswered, onRetry, onDismissError)

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
                chips = chips,
                labels = labels,
                onSend = onSend,
                hint = if (listShown) labels.askWholePiece else labels.replyHint,
            )
        }
    }

    // Drawn after the column, into the Box AssessScreen draws the stage in, so the page covers
    // the conversation as the photo viewer does, and closing it finds the list where it was.
    val rows = pageRows
    val page = openPage
    if (page != null && rows != null && page.second in rows.indices) {
        val (replyId, position) = page
        val row = rows[position]
        // Only the newest diagnosis asks anything, as on the list: an older one's improvements
        // may be gone.
        val current = replyId == latestDiagnosisId
        ImprovementPage(
            rows = rows,
            position = position,
            labels = labels,
            reportLabels = reportLabels,
            photoOf = { finding ->
                finding.mark?.let { mark -> photoNumbered(messages, mark.photo, photoNumbers)?.let { it to mark } }
            },
            onOpenMark = onOpenMark,
            details = details,
            // The page shows one improvement, so its buttons ask about that one, by its place.
            onRequestDetail = requestDetail.takeIf { current }
                ?.let { request -> { kind: DetailKind, _: String -> request(kind, replyId, row.number) } },
            exchanges = pages.byImprovement[row.key].orEmpty(),
            onAsk = if (current && canSend) {
                view.originalTitle(replyId, row.number)?.let { title ->
                    { question: String -> onAskAboutImprovement(row.number, title, question) }
                }
            } else {
                null
            },
            sending = sending,
            onGoTo = { onOpenPage(replyId, it) },
            onClose = onClosePage,
            statusRows = { TurnStatusRows(error, unanswered, onRetry, onDismissError) },
        )
    }
}

/**
 * English and Kiswahili, at the top of the assessment (v0.7.0, release scope row 29). The
 * assessment opens in the language chosen at its intake; the other asks the server for the
 * replies' translations, written on the first ask and kept, and the headings and buttons
 * follow. While the server translates, a line says so; when a reply comes back untranslated,
 * a line says those replies are shown as written, with "Try again". Each choice is the
 * language's own name, the same in both, so a maker finds theirs whichever is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSwitch(
    shown: AssessmentLanguage,
    labels: FundiLabels,
    state: TranslationState,
    onShow: (AssessmentLanguage) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().semantics { contentDescription = labels.languageSwitch },
        ) {
            val languages = AssessmentLanguage.entries
            languages.forEachIndexed { index, language ->
                SegmentedButton(
                    selected = language == shown,
                    onClick = { onShow(language) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = languages.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(language.ownName) }
            }
        }
        when (state) {
            TranslationState.WORKING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(labels.translating, style = MaterialTheme.typography.bodyMedium)
            }
            TranslationState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labels.translationFailed,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(labels.translationRetry) }
            }
            TranslationState.IDLE -> Unit
        }
    }
}

/**
 * Below the conversation and above whatever answers it, as in Kagua, and at the foot of an
 * improvement's page. Retry only for a turn that failed on its way to the model; a photograph
 * that would not process, or a sign-in failure, is not fixed by sending again.
 */
@Composable
private fun TurnStatusRows(error: AssessError?, unanswered: Boolean, onRetry: () -> Unit, onDismissError: () -> Unit) {
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
}

@Composable
private fun AssistantTurn(
    message: ChatMessage,
    labels: FundiLabels,
    reportLabels: ReportLabels,
    showOnPhoto: (Finding) -> (() -> Unit)?,
    details: DetailAnswers,
    /** Asks for one detail of the improvement at this place in the reply (from 1). */
    onRequestDetail: ((DetailKind, Int) -> Unit)?,
    /** The improvement pages' questions, for each row's count (row 43). */
    pages: PageQuestions,
    /** Opens the page of the row at this position in the reply's list (row 43). */
    onOpenImprovement: (Int) -> Unit,
) {
    val content = parseAssistantContent(message.text)
    val list = content.diagnosis?.takeIf { it.isImprovementList }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The list (row 43, mockup v2): the app's heading, then the opening Claude wrote, as
        // the overview. Shown beside a verdict too: in this layout the opening is the overview
        // the mockup puts on top, where displayProse leaves it out of every verdict reply.
        val prose = if (list != null) content.prose.substringBefore("\n\n").trim() else content.displayProse
        if (list != null) {
            Text(labels.replyHeading, style = MaterialTheme.typography.titleLarge)
        }
        if (prose.isNotBlank()) {
            Text(markdownToPlainText(prose), style = MaterialTheme.typography.bodyLarge)
        }
        // A verdict means a full assessment; drawn first because it is the answer the
        // maker came for. Above the list, as the mockup has it.
        content.verdict?.let { VerdictCard(it, verdictCardProblems(content), reportLabels) }
        if (list != null) {
            ImprovementList(list, improvementRows(list, pages), labels, reportLabels, onOpen = onOpenImprovement)
        } else {
            // A reply from before arm 1, with no fix_line, keeps the card it was drawn with.
            content.diagnosis?.let {
                DiagnosisCard(
                    it, labels, reportLabels,
                    showOnPhoto = showOnPhoto,
                    details = details,
                    onRequestDetail = onRequestDetail,
                )
            }
        }
        content.fixPlan?.let { FixPlanCard(it, labels) }
    }
}

/** "Was this review helpful?" for a tester (row 7): Yes or No, once; then a thank-you. */
@Composable
private fun ReviewHelpfulCard(labels: FundiLabels, answer: Boolean?, onAnswer: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (answer == null) {
                Text(labels.reviewHelpfulQuestion, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onAnswer(true) }) { Text(labels.reviewHelpfulYes) }
                    OutlinedButton(onClick = { onAnswer(false) }) { Text(labels.reviewHelpfulNo) }
                }
            } else {
                Text(labels.reviewHelpfulThanks, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ComposedTurn(message: ChatMessage, caption: String, onOpenPhoto: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumbnails(message, onOpenPhoto)
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypedTurn(message: ChatMessage, onOpenPhoto: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Thumbnails(message, onOpenPhoto)
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

/** A turn's photographs, small. A tap on one opens it full screen, where it can be read. */
@Composable
private fun Thumbnails(message: ChatMessage, onOpenPhoto: (String) -> Unit) {
    if (message.attachments.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(message.attachments, key = { it.id }) { attachment ->
            AsyncImage(
                model = File(attachment.path),
                contentDescription = "Photo of the piece",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenPhoto(attachment.path) },
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
private fun ReplyBar(
    /** Each shown in the language shown; a tap sends the words Claude wrote (row 29). */
    chips: List<ReplyChip>,
    labels: FundiLabels,
    onSend: (String) -> Unit,
    /** "Ask about the whole piece" once the reply is a list of improvements (row 43). */
    hint: String = labels.replyHint,
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chips.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { chip ->
                    AssistChip(onClick = { onSend(chip.sends) }, label = { Text(chip.shown) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
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

package com.qualityverifier.fundi.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.data.reports.ReportTarget
import com.qualityverifier.fundi.reports.SendReportScreen
import com.qualityverifier.fundi.reports.SendReportViewModel
import com.qualityverifier.fundi.ui.assess.AssessScreen
import com.qualityverifier.fundi.ui.home.AgreementScreen
import com.qualityverifier.fundi.ui.home.AllPiecesScreen
import com.qualityverifier.fundi.ui.home.FundiWelcomeScreen
import com.qualityverifier.fundi.ui.home.HomeScreen
import com.qualityverifier.fundi.ui.home.NewPieceScreen
import com.qualityverifier.fundi.ui.home.PieceRef
import com.qualityverifier.fundi.ui.home.PieceScreen
import com.qualityverifier.fundi.ui.home.PiecesViewModel
import com.qualityverifier.fundi.ui.home.pieceName
import com.qualityverifier.data.session.PieceStudioChoice
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.settings.ChangePasswordScreen
import com.qualityverifier.fundi.ui.auth.FundiAuthViewModel
import com.qualityverifier.fundi.ui.auth.FundiPasswordScreen
import com.qualityverifier.fundi.ui.auth.FundiPhoneScreen
import com.qualityverifier.fundi.ui.auth.FundiSignInScreen
import com.qualityverifier.fundi.ui.terms.AgreementSection
import com.qualityverifier.fundi.ui.terms.TermsMode
import com.qualityverifier.fundi.ui.terms.TermsRules
import com.qualityverifier.fundi.ui.terms.TermsScreen
import com.qualityverifier.fundi.ui.terms.TermsStep
import com.qualityverifier.fundi.ui.setup.BenchScreen
import com.qualityverifier.fundi.ui.setup.GoalsScreen
import com.qualityverifier.fundi.ui.setup.SetupViewModel
import com.qualityverifier.fundi.ui.setup.ToolsScreen
import com.qualityverifier.fundi.ui.setup.WorkshopScreen
import com.qualityverifier.fundi.ui.workers.StudiosScreen
import com.qualityverifier.fundi.ui.workers.WorkerPageScreen
import com.qualityverifier.fundi.ui.workers.WorkersScreen
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.update.UpdateRequiredScreen
import java.util.UUID

private object Routes {
    /** The first launch (v0.7.1, row 56): what the app does, before sign-in. */
    const val WELCOME = "welcome"
    const val SIGN_IN = "sign-in"
    /** New piece (row 46): what it is and who it is for, then the camera. */
    const val NEW_PIECE = "pieces/new"
    /** A piece's page (row 56); [worker] set for a linked worker's own piece, from their page. */
    // "piece/", not "pieces/": "pieces/new" and "pieces/all" would fit this pattern too.
    const val PIECE = "piece/{pieceId}?worker={worker}"
    /** "All my pieces" (row 56), in progress and finished. */
    const val ALL_PIECES = "pieces/all"
    /** "Change your password", from the Settings menu (row 46). */
    const val CHANGE_PASSWORD = "settings/password"
    /** "Your agreement" from the Settings menu: the transfer to the United States. */
    const val AGREEMENT = "settings/agreement"

    fun piece(pieceId: String, workerId: String? = null) = "piece/$pieceId?worker=${workerId.orEmpty()}"
    const val REGISTER_PHONE = "register/phone"
    const val REGISTER_PASSWORD = "register/password"
    const val TERMS = "terms/{mode}"
    const val SETUP_WORKSHOP = "setup/workshop"
    /** The bench photo (v0.6.0, row 5), before the typed tool list. */
    const val SETUP_BENCH = "setup/bench"
    const val SETUP_TOOLS = "setup/tools"
    const val SETUP_GOALS = "setup/goals"
    const val HOME = "home"
    const val WORKERS = "workers"
    /** One worker's page (plan v7 §4.6), from a card on "My workers". */
    const val WORKER_PAGE = "workers/{workerId}"
    /** "Studios I work for" (V31), the other side of the roster, from Home. */
    const val STUDIOS = "studios"
    const val SETTINGS = "settings"
    /**
     * "Send report" on a piece (plan v7 §5.4): who for, the preview, the download, the share
     * sheet. From the worker's page, [itemType], [theirs] and [label] describe a linked
     * worker's piece this phone may hold no assessment of (row 11).
     */
    const val SEND_REPORT = "pieces/{pieceId}/report?itemType={itemType}&theirs={theirs}&label={label}"
    /** "Send report" on a linked worker's page: the report of their work across pieces. */
    const val WORKER_REPORT = "workers/{workerId}/report"

    fun workerPage(workerId: String) = "workers/$workerId"
    fun sendReport(pieceId: String) = "pieces/$pieceId/report"

    /** A linked worker's piece, from the worker's page. */
    fun sendReportOnTheirs(pieceId: String, itemTypeId: String, label: String?) =
        "pieces/$pieceId/report?itemType=$itemTypeId&theirs=true&label=${Uri.encode(label.orEmpty())}"
    fun workerReport(workerId: String) = "workers/$workerId/report"
    /**
     * [final] marks the final assessment before a customer report (row 27); [theirs] a
     * linked worker's piece assessed from the worker's page (row 11), with [label], the
     * worker's name for it.
     */
    const val ASSESS = "assess/{sessionId}/{itemTypeId}?piece={piece}&returning={returning}" +
        "&final={final}&theirs={theirs}&label={label}&studio={studio}"

    /**
     * New piece's answer to "Who is it for?" (row 46), as the ASSESS route carries it: empty
     * for none (the intake asks, or the account has no studio), [OWN_JOB] or [NOT_ASKED], or
     * a studio entry's id.
     */
    const val OWN_JOB = "own-job"
    const val NOT_ASKED = "not-asked"

    fun studioArg(choice: PieceStudioChoice?): String = when (choice) {
        null -> ""
        PieceStudioChoice.NotAsked -> NOT_ASKED
        PieceStudioChoice.OwnJob -> OWN_JOB
        is PieceStudioChoice.Studio -> choice.studioId
    }

    fun studioOf(arg: String?): PieceStudioChoice? = when (arg) {
        null, "" -> null
        NOT_ASKED -> PieceStudioChoice.NotAsked
        OWN_JOB -> PieceStudioChoice.OwnJob
        else -> PieceStudioChoice.Studio(arg)
    }

    /**
     * [piece] defaults to the session itself, which is how a first assessment names its
     * piece. A re-assessment passes the piece it is returning to.
     */
    fun assess(
        sessionId: String,
        itemTypeId: String,
        piece: String = sessionId,
        returning: Boolean = false,
        final: Boolean = false,
        theirs: Boolean = false,
        label: String? = null,
        studio: PieceStudioChoice? = null,
    ) = "assess/$sessionId/$itemTypeId?piece=$piece&returning=$returning&final=$final&theirs=$theirs" +
        "&label=${Uri.encode(label.orEmpty())}&studio=${Uri.encode(studioArg(studio))}"

    fun terms(mode: TermsMode) = "terms/${mode.id}"

    /**
     * The terms screen as the start destination, for an account that has not agreed. Its
     * own route without an argument, because a start destination has to name a route
     * exactly; it shows the same screen as [TERMS] in its returning mode.
     */
    const val TERMS_START = "terms-start"
}

@Composable
fun FundiNav() {
    val container = fundiContainer()
    val navController = rememberNavController()
    // Evaluated once per process. Signing in navigates onward itself, and signing out
    // replaces the whole stack with sign-in, so this never needs re-reading. The terms
    // screen opens first unless the server last said this account has agreed; offline, the
    // last answer stands, and with none the screen asks the server.
    val start = remember {
        when (TermsRules.start(container.isSignedIn, container.tokenStore.termsRequired())) {
            TermsStep.HOME -> Routes.HOME
            TermsStep.TERMS -> Routes.TERMS_START
            // A signed-out phone opens on the welcome screen (row 56, P17), which leads to
            // today's sign-in and invite-code screens.
            else -> Routes.WELCOME
        }
    }

    // English only, and that is a gap rather than a decision — see FundiLabels. Kagua's
    // Swahili is unreviewed placeholder copy already (issue #20), and a producer-facing
    // app in Kenya needs that pass more than the buyer's app does, not less.
    val labels = FundiLabels.ENGLISH

    // Below the minimum, nothing behind this would work: the server answers 426 to every
    // route but sign-in and the config. So the update screen replaces the app outright.
    // English, like the rest of this app for now.
    val update by container.appVersionCheck.state.collectAsState()
    if (update.prompt == UpdatePrompt.REQUIRED) {
        UpdateRequiredScreen(UpdateLabels.ENGLISH, update.downloadUrl)
        return
    }

    // Signing out and deleting the account end in the same place, as in Kagua: a deleted
    // account whose tokens were left on the handset would look signed in until the next
    // request was refused. They differ only in the push: a sign-out sends the maker's
    // unsent names, roster and worker sets first, in the background; a deleted account
    // sends nothing (see AppContainer.signOut).
    val leave = { pushPendingWork: Boolean ->
        container.signOut(pushPendingWork)
        navController.navigate(Routes.SIGN_IN) {
            // The whole back stack goes, so back from sign-in cannot reopen a signed-out
            // maker's screens. That is all this does. The pieces and roster stay on the
            // phone; they are cleared when a different account signs in (AppContainer's
            // LocalAccountGuard), and kept when the same maker signs back in.
            popUpTo(0) { inclusive = true }
        }
    }
    val signOut = { leave(true) }
    val accountDeleted = { leave(false) }

    // Any request the server refuses with 403 terms_required opens the terms screen on top
    // of the screen that made it, where the turn waits for Retry. Single top, so several
    // refusals at once open one screen.
    LaunchedEffect(Unit) {
        container.termsSignal.required.collect {
            val here = navController.currentDestination?.route
            if (here != Routes.TERMS && here != Routes.TERMS_START) {
                navController.navigate(Routes.terms(TermsMode.CHANGED)) { launchSingleTop = true }
            }
        }
    }
    // Opening on home with a stored "agreed": ask the server again in the background, and
    // open the terms screen if the terms changed or were withdrawn on another phone.
    LaunchedEffect(Unit) {
        if (start == Routes.HOME && container.terms.status()?.required == true) {
            navController.navigate(Routes.terms(TermsMode.CHANGED)) { launchSingleTop = true }
        }
    }

    // One terms screen behind both of its routes; the mode changes only the heading.
    val termsScreen: @Composable (TermsMode) -> Unit = { mode ->
        TermsScreen(
            mode = mode,
            onDone = { next ->
                when (TermsRules.afterTerms(mode, next)) {
                    TermsStep.BACK -> if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    }
                    // Setup, not home, for an account with no workshop profile: a new
                    // account, or a maker who quit at the terms screen before setup.
                    TermsStep.SETUP -> navController.navigate(Routes.SETUP_WORKSHOP) {
                        popUpTo(0) { inclusive = true }
                    }
                    else -> navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                }
            },
            onSignOut = signOut,
            onAccountSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
        )
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SIGN_IN) {
            FundiSignInScreen(
                // Home, not setup: a maker signing in on a new handset has already answered
                // all this, and their profile is on the server. The terms screen first if
                // the account has not agreed; it goes to setup only if there is no profile.
                onSignedIn = {
                    val next = when (TermsRules.afterSignIn(container.tokenStore.termsRequired())) {
                        TermsStep.HOME -> Routes.HOME
                        else -> Routes.terms(TermsMode.RETURNING)
                    }
                    // The whole stack, the welcome screen included: Back from Home leaves the app.
                    navController.navigate(next) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onRegister = { navController.navigate(Routes.REGISTER_PHONE) },
            )
        }

        composable(Routes.WELCOME) {
            FundiWelcomeScreen(
                onSignIn = { navController.navigate(Routes.SIGN_IN) { launchSingleTop = true } },
                onInvite = { navController.navigate(Routes.REGISTER_PHONE) { launchSingleTop = true } },
            )
        }

        // Sign-up is two screens, then the terms, then setup.
        composable(Routes.REGISTER_PHONE) { entry ->
            FundiPhoneScreen(
                viewModel = sharedSignUpViewModel(navController, entry),
                onProceed = { navController.navigate(Routes.REGISTER_PASSWORD) { launchSingleTop = true } },
                // To sign-in whether this was opened from sign-in or from the welcome screen.
                onSignIn = {
                    if (navController.previousBackStackEntry?.destination?.route == Routes.SIGN_IN) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.SIGN_IN) {
                            popUpTo(Routes.REGISTER_PHONE) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.REGISTER_PASSWORD) { entry ->
            FundiPasswordScreen(
                viewModel = sharedSignUpViewModel(navController, entry),
                // The terms screen becomes the root: back from it leaves the app rather
                // than returning to a sign-up form for an account that now exists.
                onRegistered = {
                    navController.navigate(Routes.terms(TermsMode.NEW)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onChangeDetails = { navController.popBackStack(Routes.REGISTER_PHONE, inclusive = false) },
            )
        }

        composable(
            Routes.TERMS,
            arguments = listOf(
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = TermsMode.RETURNING.id
                },
            ),
        ) { entry ->
            termsScreen(TermsMode.fromId(entry.arguments?.getString("mode")))
        }

        composable(Routes.TERMS_START) { termsScreen(TermsMode.RETURNING) }

        // One view model across all three setup screens, scoped to the graph rather than
        // to a screen: the profile is sent whole, so the answers have to survive Next.
        composable(Routes.SETUP_WORKSHOP) { entry ->
            val model = sharedSetupViewModel(navController, entry)
            // Pull whatever the server already holds, so re-entering setup edits rather
            // than replaces. Once per entry into the flow.
            LaunchedEffect(Unit) { model.loadExisting() }
            val workshop by model.workshop.collectAsState()
            val shop by model.shop.collectAsState()
            WorkshopScreen(
                labels = labels,
                workshop = workshop,
                onChange = { model.updateWorkshop(it) },
                shop = shop,
                onAtShop = model::answerAtShop,
                onConsent = model::answerConsent,
                onLocate = model::locateShop,
                onPermission = model::onPermission,
                // Written straight to the preference rather than held with the profile:
                // it is a setting on this phone, not part of what the server is sent, and
                // a maker who answers and then skips setup has still answered.
                assessLocation = container.locationPreference,
                onNext = { navController.navigate(Routes.SETUP_BENCH) },
                // Skipping lands on home with no profile at all. The coaching then runs
                // tool-blind, which is worse advice but not broken advice, and setup can
                // be finished later.
                onSkip = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP_WORKSHOP) { inclusive = true }
                    }
                },
            )
        }

        // The bench photo, skippable: the typed list after it works as before, prefilled with
        // whatever the fundi confirmed here.
        composable(Routes.SETUP_BENCH) { entry ->
            val model = sharedSetupViewModel(navController, entry)
            val step by model.benchStep.collectAsState()
            BenchScreen(
                labels = labels,
                step = step,
                newFile = model::newBenchFile,
                onPhoto = model::onBenchPhoto,
                onConfirm = model::confirmTool,
                onOwner = model::chooseOwner,
                onRetry = model::retryBench,
                onSkip = model::skipBench,
                onDone = {
                    navController.navigate(Routes.SETUP_TOOLS) {
                        // Back from the tools comes to the workshop, not to a finished draft.
                        popUpTo(Routes.SETUP_BENCH) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETUP_TOOLS) { entry ->
            val model = sharedSetupViewModel(navController, entry)
            val tools by model.tools.collectAsState()
            ToolsScreen(
                labels = labels,
                tools = tools,
                onSet = model::setTool,
                onNext = { navController.navigate(Routes.SETUP_GOALS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETUP_GOALS) { entry ->
            val model = sharedSetupViewModel(navController, entry)
            val goals by model.goals.collectAsState()
            val busy by model.busy.collectAsState()
            val failed by model.failed.collectAsState()
            val saved by model.saved.collectAsState()

            LaunchedEffect(saved) {
                if (saved) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP_WORKSHOP) { inclusive = true }
                    }
                }
            }

            GoalsScreen(
                labels = labels,
                goals = goals,
                onToggle = model::toggleGoal,
                busy = busy,
                failed = failed,
                onFinish = model::save,
                onBack = { navController.popBackStack() },
            )
        }

        // Reading an assessment again: the same session, so its conversation and photographs
        // come back exactly as they were.
        val openAssessment = { latest: SessionSummary ->
            navController.navigate(Routes.assess(latest.id, latest.itemType.id, piece = latest.piece))
        }
        // A re-assessment is a new session on the same piece — not a continuation — so its
        // verdict is recorded beside the first rather than overwriting it, and the earlier
        // conversation is not resent every turn.
        val reassess = { latest: SessionSummary ->
            navController.navigate(
                Routes.assess(
                    sessionId = UUID.randomUUID().toString(),
                    itemTypeId = latest.itemType.id,
                    piece = latest.piece,
                    returning = true,
                    // A linked worker's piece this account assessed as the studio's manager
                    // stays the worker's (row 11).
                    theirs = !latest.pieceMine,
                    label = latest.pieceLabel.takeIf { !latest.pieceMine },
                ),
            )
        }
        val back: () -> Unit = { navController.popBackStack() }

        // One screen: the Finances tab went with the M-Pesa reading (v0.7.1, row 17).
        composable(Routes.HOME) { entry ->
            HomeScreen(
                model = piecesViewModel(navController, entry),
                onNewPiece = { navController.navigate(Routes.NEW_PIECE) },
                onOpenPiece = { piece -> navController.navigate(Routes.piece(piece.pieceId)) },
                onAllPieces = { navController.navigate(Routes.ALL_PIECES) },
                onWorkers = { navController.navigate(Routes.WORKERS) },
                onStudios = { navController.navigate(Routes.STUDIOS) },
                onChangePassword = { navController.navigate(Routes.CHANGE_PASSWORD) { launchSingleTop = true } },
                onAgreement = { navController.navigate(Routes.AGREEMENT) { launchSingleTop = true } },
                onSignOut = signOut,
                // The view model reports success only after DELETE /v1/account succeeds, so the
                // server has already deleted the account by the time this runs.
                onAccountDeleted = accountDeleted,
            )
        }

        composable(Routes.NEW_PIECE) { entry ->
            NewPieceScreen(
                backLabel = backLabel(navController, entry),
                onBack = back,
                // As the mockup: the new piece's page under its assessment, so Back from the
                // reply reads "‹ <piece>" and lands on the page, and Back again on Home, where
                // the piece is at the top of "My pieces" (P17). A first assessment's piece is
                // its own session. New piece goes from the stack.
                onStart = { itemType, studio ->
                    val sessionId = UUID.randomUUID().toString()
                    navController.navigate(Routes.piece(sessionId)) {
                        popUpTo(Routes.NEW_PIECE) { inclusive = true }
                    }
                    navController.navigate(Routes.assess(sessionId, itemType.id, studio = studio))
                },
            )
        }

        composable(
            Routes.PIECE,
            arguments = listOf(
                navArgument("pieceId") { type = NavType.StringType },
                navArgument("worker") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val ref = PieceRef(
                pieceId = entry.arguments?.getString("pieceId").orEmpty(),
                workerId = entry.arguments?.getString("worker")?.takeIf { it.isNotBlank() },
            )
            // The kind of a report Send report just handed to the share sheet (row 56).
            val sentKind by entry.savedStateHandle.getStateFlow<String?>(SENT_KIND, null).collectAsState()
            PieceScreen(
                ref = ref,
                model = piecesViewModel(navController, entry),
                sentKind = sentKind,
                onSentSeen = { entry.savedStateHandle[SENT_KIND] = null },
                backLabel = backLabel(navController, entry),
                onBack = back,
                onOpenAssessment = openAssessment,
                onReassess = reassess,
                // "Assess piece" on one of the worker's own pieces (row 11): the manager's own
                // new assessment of that piece, judged afresh.
                onAssessTheirs = { piece ->
                    navController.navigate(
                        Routes.assess(
                            sessionId = UUID.randomUUID().toString(),
                            itemTypeId = piece.itemTypeId,
                            piece = piece.id,
                            returning = true,
                            theirs = true,
                            label = piece.label,
                        ),
                    )
                },
                onSendReport = { piece -> navController.navigate(Routes.sendReport(piece.pieceId)) },
                // The worker's piece: its kind and name go with it, since this phone may hold
                // no assessment of it yet.
                onSendReportOnTheirs = { piece ->
                    navController.navigate(Routes.sendReportOnTheirs(piece.id, piece.itemTypeId, piece.label))
                },
            )
        }

        composable(Routes.ALL_PIECES) { entry ->
            AllPiecesScreen(
                model = piecesViewModel(navController, entry),
                backLabel = backLabel(navController, entry),
                onBack = back,
                onOpenPiece = { piece -> navController.navigate(Routes.piece(piece.pieceId)) },
            )
        }

        composable(Routes.CHANGE_PASSWORD) { entry ->
            ChangePasswordScreen(
                container = container,
                labels = remember(labels) { labels.onSettings(AuthLabels.ENGLISH) },
                backLabel = backLabel(navController, entry),
                onBack = back,
            )
        }

        // Withdrawing the transfer agreement. Once the server confirms, the terms screen is
        // the only screen left: nothing else works without the agreement.
        composable(Routes.AGREEMENT) { entry ->
            AgreementScreen(
                backLabel = backLabel(navController, entry),
                onBack = back,
                onWithdrawn = {
                    navController.navigate(Routes.terms(TermsMode.WITHDRAWN)) { popUpTo(0) { inclusive = true } }
                },
            )
        }

        composable(Routes.WORKERS) { entry ->
            WorkersScreen(
                backLabel = backLabel(navController, entry),
                onBack = back,
                onOpen = { worker -> navController.navigate(Routes.workerPage(worker.id)) },
            )
        }

        composable(Routes.STUDIOS) { entry ->
            StudiosScreen(backLabel = backLabel(navController, entry), onBack = back)
        }

        composable(
            Routes.SEND_REPORT,
            arguments = listOf(
                navArgument("pieceId") { type = NavType.StringType },
                navArgument("itemType") { type = NavType.StringType; nullable = true },
                navArgument("theirs") { type = NavType.BoolType; defaultValue = false },
                navArgument("label") { type = NavType.StringType; nullable = true },
            ),
        ) { entry ->
            val args = entry.arguments
            SendReportScreen(
                target = ReportTarget.Piece(args?.getString("pieceId").orEmpty()),
                hint = args?.getString("itemType")?.let(ItemType::fromId)?.let { itemType ->
                    SendReportViewModel.PieceHint(
                        itemType = itemType,
                        mine = !(args.getBoolean("theirs")),
                        label = args.getString("label")?.takeIf { it.isNotBlank() },
                    )
                },
                onBack = { navController.popBackStack() },
                // After the share sheet closes on a new report, the piece's page under this
                // screen opens with "Report sent to …" (row 56, P17).
                onShared = { kind ->
                    val under = navController.previousBackStackEntry
                    if (under?.destination?.route == Routes.PIECE) {
                        under.savedStateHandle[SENT_KIND] = kind
                        navController.popBackStack()
                    }
                },
                // The final assessment a customer report needs (row 27): a new session on the
                // same piece, as any re-assessment is. Done, it comes back here.
                onAssessFinal = { pieceId, itemType, mine, label ->
                    navController.navigate(
                        Routes.assess(
                            sessionId = UUID.randomUUID().toString(),
                            itemTypeId = itemType.id,
                            piece = pieceId,
                            returning = true,
                            final = true,
                            theirs = !mine,
                            label = label,
                        ),
                    )
                },
            )
        }

        composable(
            Routes.WORKER_REPORT,
            arguments = listOf(navArgument("workerId") { type = NavType.StringType }),
        ) { entry ->
            SendReportScreen(
                target = ReportTarget.Worker(entry.arguments?.getString("workerId").orEmpty()),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Routes.WORKER_PAGE,
            arguments = listOf(navArgument("workerId") { type = NavType.StringType }),
        ) { entry ->
            WorkerPageScreen(
                workerId = entry.arguments?.getString("workerId").orEmpty(),
                backLabel = backLabel(navController, entry),
                onBack = back,
                onSendReport = { workerId -> navController.navigate(Routes.workerReport(workerId)) },
                // The piece's page; a linked worker's own piece carries the worker, so its
                // page can ask the server for it (rows 4 and 11).
                onOpenPiece = { pieceId, theirsOf -> navController.navigate(Routes.piece(pieceId, theirsOf)) },
            )
        }

        // The settings screen, kept only for the terms screen's "account settings" link, so
        // an account that has not agreed can still delete itself. Every other way in is the
        // Settings menu on Home (row 46).
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                labels = remember(labels) { labels.onSettings(AuthLabels.ENGLISH) },
                onBack = { navController.popBackStack() },
                // The view model reports success only after DELETE /v1/account succeeds,
                // so the server has already deleted the account by the time this runs.
                onAccountDeleted = accountDeleted,
                onSignOut = signOut,
                // Withdrawing the transfer agreement. Once the server confirms, the terms
                // screen is the only screen left: nothing else works without the agreement.
                agreementSection = {
                    AgreementSection(
                        onWithdrawn = {
                            navController.navigate(Routes.terms(TermsMode.WITHDRAWN)) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                },
            )
        }

        composable(
            Routes.ASSESS,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("itemTypeId") { type = NavType.StringType },
                navArgument("piece") { type = NavType.StringType; nullable = true },
                navArgument("returning") { type = NavType.BoolType; defaultValue = false },
                navArgument("final") { type = NavType.BoolType; defaultValue = false },
                navArgument("theirs") { type = NavType.BoolType; defaultValue = false },
                navArgument("label") { type = NavType.StringType; nullable = true },
                navArgument("studio") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            // The id is generated on the phone, so a retry of the same assessment is the
            // same session server-side rather than a second one billed twice.
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val itemType = ItemType.fromId(entry.arguments?.getString("itemTypeId").orEmpty())
                ?: ItemType.OTHER
            val pieceId = entry.arguments?.getString("piece")?.takeIf { it.isNotBlank() } ?: sessionId
            val theirs = entry.arguments?.getBoolean("theirs") ?: false
            val givenLabel = entry.arguments?.getString("label")?.takeIf { it.isNotBlank() }
            AssessScreen(
                sessionId = sessionId,
                itemType = itemType,
                pieceId = pieceId,
                returning = entry.arguments?.getBoolean("returning") ?: false,
                onDone = { navController.popBackStack() },
                isFinal = entry.arguments?.getBoolean("final") ?: false,
                pieceMine = !theirs,
                givenLabel = givenLabel,
                presetStudio = Routes.studioOf(entry.arguments?.getString("studio")),
                // "Send report" on the closing screen (row 56). The assessment leaves the stack
                // first, so that after sharing the piece's page under Send report shows its
                // green box. A final assessment opened from Send report goes back to it.
                onSendReport = {
                    val cameFromReport = navController.previousBackStackEntry?.destination?.route == Routes.SEND_REPORT
                    navController.popBackStack()
                    if (!cameFromReport) {
                        navController.navigate(
                            if (theirs) {
                                Routes.sendReportOnTheirs(pieceId, itemType.id, givenLabel)
                            } else {
                                Routes.sendReport(pieceId)
                            },
                        )
                    }
                },
            )
        }
    }
}

/** The saved-state key a piece's page reads for its green "Report sent to …" box (row 56). */
private const val SENT_KIND = "sentKind"

/**
 * The pieces, shared by Home, a piece's page and "All my pieces": scoped to Home's entry, so
 * the three read one list and the sync runs once when Home opens, not again on every page.
 * Scoped to the page's own entry when Home is not under it.
 */
@Composable
private fun piecesViewModel(
    navController: androidx.navigation.NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
): PiecesViewModel {
    val owner = remember(entry) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrDefault(entry) }
    val container = fundiContainer()
    return viewModel(viewModelStoreOwner = owner, factory = PiecesViewModel.factory(container))
}

/**
 * What Back says on [entry]'s screen: the name of the screen under it (P27), "Home", "My
 * workers", the piece's or the worker's own name.
 */
@Composable
private fun backLabel(
    navController: androidx.navigation.NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
): String {
    val words = FundiScreenLabels.ENGLISH
    val previous = remember(entry) { navController.previousBackStackEntry }
    val container = fundiContainer()
    return when (previous?.destination?.route) {
        Routes.HOME -> words.home
        Routes.WORKERS -> words.myWorkers
        Routes.STUDIOS -> words.studiosIWorkFor
        Routes.ALL_PIECES -> words.allPieces
        Routes.NEW_PIECE -> words.newPieceTitle
        Routes.PIECE -> {
            val pieceId = previous.arguments?.getString("pieceId")
            val pieces by piecesViewModel(navController, entry).pieces.collectAsState()
            pieces?.firstOrNull { it.pieceId == pieceId }?.let(::pieceName) ?: words.home
        }
        Routes.WORKER_PAGE -> {
            val workerId = previous.arguments?.getString("workerId")
            val roster by container.workerRepository.observeWorkers().collectAsState(initial = emptyList())
            roster.firstOrNull { it.id == workerId }?.name ?: words.myWorkers
        }
        else -> words.home
    }
}

/**
 * The sign-up view model, shared by the phone and password screens, so the password screen
 * sends what the phone screen collected. Scoped to the phone screen's entry, as the setup
 * screens share theirs.
 */
@Composable
private fun sharedSignUpViewModel(
    navController: androidx.navigation.NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
): FundiAuthViewModel {
    val owner = remember(entry) { navController.getBackStackEntry(Routes.REGISTER_PHONE) }
    val container = fundiContainer()
    return viewModel(viewModelStoreOwner = owner, factory = FundiAuthViewModel.factory(container))
}

/**
 * The setup view model, shared by all three screens.
 *
 * Scoped to the first setup entry rather than to each screen, so Next does not discard
 * the answers. The profile is sent whole — the server derives what changed by comparing
 * against what it holds — so three per-screen saves would write three partial profiles
 * and record a tool history of somebody acquiring their own tools one screen at a time.
 */
@Composable
private fun sharedSetupViewModel(
    navController: androidx.navigation.NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
): SetupViewModel {
    // Keyed on the current entry rather than on the controller: the back stack entry it
    // resolves is a store owner, and caching one across a recreated entry would hand a
    // later screen a view model whose store has already been cleared.
    val owner = remember(entry) { navController.getBackStackEntry(Routes.SETUP_WORKSHOP) }
    val container = fundiContainer()
    return viewModel(viewModelStoreOwner = owner, factory = SetupViewModel.factory(container))
}

package com.qualityverifier.fundi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.fundi.ui.assess.AssessScreen
import com.qualityverifier.fundi.ui.home.HomeScreen
import com.qualityverifier.fundi.ui.mpesa.FinancesScreen
import com.qualityverifier.fundi.ui.mpesa.MpesaConsentScreen
import com.qualityverifier.fundi.ui.mpesa.MpesaQuestion
import com.qualityverifier.fundi.ui.mpesa.MpesaSettingsSection
import com.qualityverifier.fundi.ui.mpesa.MpesaTransactionScreen
import com.qualityverifier.fundi.ui.auth.FundiAuthViewModel
import com.qualityverifier.fundi.ui.auth.FundiPasswordScreen
import com.qualityverifier.fundi.ui.auth.FundiPhoneScreen
import com.qualityverifier.fundi.ui.auth.FundiSignInScreen
import com.qualityverifier.fundi.ui.terms.AgreementSection
import com.qualityverifier.fundi.ui.terms.TermsMode
import com.qualityverifier.fundi.ui.terms.TermsRules
import com.qualityverifier.fundi.ui.terms.TermsScreen
import com.qualityverifier.fundi.ui.terms.TermsStep
import com.qualityverifier.fundi.ui.setup.GoalsScreen
import com.qualityverifier.fundi.ui.setup.SetupViewModel
import com.qualityverifier.fundi.ui.setup.ToolsScreen
import com.qualityverifier.fundi.ui.setup.WorkshopScreen
import com.qualityverifier.fundi.ui.workers.StudiosScreen
import com.qualityverifier.fundi.ui.workers.WorkerPageScreen
import com.qualityverifier.fundi.ui.workers.WorkersScreen
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.MpesaLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.update.UpdateRequiredScreen
import java.util.UUID

private object Routes {
    const val SIGN_IN = "sign-in"
    const val REGISTER_PHONE = "register/phone"
    const val REGISTER_PASSWORD = "register/password"
    const val TERMS = "terms/{mode}"
    const val SETUP_WORKSHOP = "setup/workshop"
    const val SETUP_TOOLS = "setup/tools"
    const val SETUP_GOALS = "setup/goals"
    const val HOME = "home"
    const val WORKERS = "workers"
    /** One worker's page (plan v7 §4.6), from a card on "My workers". */
    const val WORKER_PAGE = "workers/{workerId}"
    /** "Studios I work at" (V31), the other side of the roster, from Home. */
    const val STUDIOS = "studios"
    const val SETTINGS = "settings"

    fun workerPage(workerId: String) = "workers/$workerId"
    const val ASSESS = "assess/{sessionId}/{itemTypeId}?piece={piece}&returning={returning}"

    /**
     * The M-Pesa questions, from [MpesaQuestion]. [signUp] true between the terms and setup,
     * where finishing goes on to setup; otherwise finishing goes back.
     */
    const val MPESA_CONSENT = "mpesa/consent/{question}?signUp={signUp}"
    const val MPESA_TRANSACTION = "mpesa/transaction/{code}"

    fun mpesaConsent(question: MpesaQuestion, signUp: Boolean = false) =
        "mpesa/consent/${question.id}?signUp=$signUp"

    fun mpesaTransaction(code: String) = "mpesa/transaction/$code"

    /**
     * [piece] defaults to the session itself, which is how a first assessment names its
     * piece. A re-assessment passes the piece it is returning to.
     */
    fun assess(
        sessionId: String,
        itemTypeId: String,
        piece: String = sessionId,
        returning: Boolean = false,
    ) = "assess/$sessionId/$itemTypeId?piece=$piece&returning=$returning"

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
            else -> Routes.SIGN_IN
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
                    // account, or a maker who quit at the terms screen before setup. The
                    // M-Pesa questions come first, between the terms and setup (Nathan,
                    // 11 November 2026).
                    TermsStep.SETUP -> navController.navigate(Routes.mpesaConsent(MpesaQuestion.READ, signUp = true)) {
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
                    navController.navigate(next) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
                onRegister = { navController.navigate(Routes.REGISTER_PHONE) },
            )
        }

        // Sign-up is two screens, then the terms, then the two M-Pesa questions, then setup.
        composable(Routes.REGISTER_PHONE) { entry ->
            FundiPhoneScreen(
                viewModel = sharedSignUpViewModel(navController, entry),
                onProceed = { navController.navigate(Routes.REGISTER_PASSWORD) { launchSingleTop = true } },
                onSignIn = { navController.popBackStack() },
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
                onNext = { navController.navigate(Routes.SETUP_TOOLS) },
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

        composable(
            Routes.MPESA_CONSENT,
            arguments = listOf(
                navArgument("question") { type = NavType.StringType },
                navArgument("signUp") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val signUp = entry.arguments?.getBoolean("signUp") ?: false
            MpesaConsentScreen(
                first = MpesaQuestion.fromId(entry.arguments?.getString("question")),
                onDone = {
                    if (signUp) {
                        // On to the first setup step, as the terms screen went before.
                        navController.navigate(Routes.SETUP_WORKSHOP) { popUpTo(0) { inclusive = true } }
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(
            Routes.MPESA_TRANSACTION,
            arguments = listOf(navArgument("code") { type = NavType.StringType }),
        ) { entry ->
            MpesaTransactionScreen(
                code = entry.arguments?.getString("code").orEmpty(),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.HOME) {
            // Two tabs: the pieces, and the Finances tab (Grady's description, Slack,
            // 11 November 2026). Saveable, so returning from a transaction lands on Finances.
            var tab by rememberSaveable { mutableStateOf(HomeTab.PIECES) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        HomeTab.entries.forEach { each ->
                            NavigationBarItem(
                                selected = tab == each,
                                onClick = { tab = each },
                                icon = { Icon(each.icon, contentDescription = null) },
                                label = { Text(each.label(MpesaLabels.ENGLISH)) },
                            )
                        }
                    }
                },
            ) { padding ->
                when (tab) {
                    HomeTab.PIECES -> Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                        HomeScreen(
                            // Reading an assessment again: the same session, so its conversation
                            // and photographs come back exactly as they were.
                            onOpen = { latest ->
                                navController.navigate(
                                    Routes.assess(latest.id, latest.itemType.id, piece = latest.piece),
                                )
                            },
                            // A re-assessment is a new session on the same piece — not a
                            // continuation — so its verdict is recorded beside the first rather than
                            // overwriting it, and the earlier conversation is not resent every turn.
                            onReassess = { latest ->
                                navController.navigate(
                                    Routes.assess(
                                        sessionId = UUID.randomUUID().toString(),
                                        itemTypeId = latest.itemType.id,
                                        piece = latest.piece,
                                        returning = true,
                                    ),
                                )
                            },
                            onStart = { itemType ->
                                navController.navigate(
                                    Routes.assess(UUID.randomUUID().toString(), itemType.id),
                                )
                            },
                            onWorkers = { navController.navigate(Routes.WORKERS) },
                            onStudios = { navController.navigate(Routes.STUDIOS) },
                            onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                        )
                    }
                    HomeTab.FINANCES -> FinancesScreen(
                        onOpen = { code -> navController.navigate(Routes.mpesaTransaction(code)) },
                        onTurnOn = { navController.navigate(Routes.mpesaConsent(MpesaQuestion.READ)) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }

        composable(Routes.WORKERS) {
            WorkersScreen(
                onBack = { navController.popBackStack() },
                onOpen = { worker -> navController.navigate(Routes.workerPage(worker.id)) },
            )
        }

        composable(Routes.STUDIOS) {
            StudiosScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Routes.WORKER_PAGE,
            arguments = listOf(navArgument("workerId") { type = NavType.StringType }),
        ) { entry ->
            WorkerPageScreen(
                workerId = entry.arguments?.getString("workerId").orEmpty(),
                onBack = { navController.popBackStack() },
                // The piece's newest assessment, as Home's "Open" opens it.
                onOpenPiece = { latest ->
                    navController.navigate(
                        Routes.assess(latest.id, latest.itemType.id, piece = latest.piece),
                    )
                },
            )
        }

        // Kagua's screen, with Fundi Bora's wording where Kagua's would be wrong, and the
        // sign-out button Kagua keeps on Profile. Fundi Bora has no Profile.
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
                    Column {
                        // The two M-Pesa switches above the transfer withdrawal: they are the
                        // ones somebody comes here to change.
                        MpesaSettingsSection(
                            onAsk = { question -> navController.navigate(Routes.mpesaConsent(question)) },
                        )
                        Spacer(Modifier.height(28.dp))
                        AgreementSection(
                            onWithdrawn = {
                                navController.navigate(Routes.terms(TermsMode.WITHDRAWN)) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                        )
                    }
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
            ),
        ) { entry ->
            // The id is generated on the phone, so a retry of the same assessment is the
            // same session server-side rather than a second one billed twice.
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val itemType = ItemType.fromId(entry.arguments?.getString("itemTypeId").orEmpty())
                ?: ItemType.OTHER
            AssessScreen(
                sessionId = sessionId,
                itemType = itemType,
                pieceId = entry.arguments?.getString("piece")?.takeIf { it.isNotBlank() }
                    ?: sessionId,
                returning = entry.arguments?.getBoolean("returning") ?: false,
                onDone = { navController.popBackStack() },
            )
        }
    }
}

/** Home's two tabs. */
private enum class HomeTab(val icon: ImageVector) {
    PIECES(Icons.Filled.Chair),
    FINANCES(Icons.Filled.Payments);

    fun label(labels: MpesaLabels): String = when (this) {
        PIECES -> labels.piecesTab
        FINANCES -> labels.financesTab
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

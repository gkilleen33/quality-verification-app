package com.qualityverifier.ui.nav

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.text.decodeIntake
import com.qualityverifier.text.encodeIntake
import com.qualityverifier.domain.TabletEnd
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.shopMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import com.qualityverifier.ui.rememberAuthLabels
import com.qualityverifier.ui.rememberUpdateLabels
import com.qualityverifier.ui.update.UpdateRequiredScreen
import com.qualityverifier.ui.chat.ChatScreen
import com.qualityverifier.ui.home.HomeChrome
import com.qualityverifier.ui.home.HomeScreen
import com.qualityverifier.ui.main.MainScaffold
import com.qualityverifier.ui.main.MainTab
import com.qualityverifier.ui.profile.ProfileScreen
import com.qualityverifier.ui.reports.ReportsScreen
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.auth.RegisterScreen
import com.qualityverifier.ui.auth.SignInScreen
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.WelcomeContent
import com.qualityverifier.ui.parts.WelcomeStep
import com.qualityverifier.ui.rememberKaguaLabels
import com.qualityverifier.ui.settings.ChangePasswordScreen
import com.qualityverifier.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.util.UUID

private object Routes {
    /** The first screen of a signed-out phone (v0.7.1, row 57): what Kagua does, then sign in. */
    const val WELCOME = "welcome"
    const val SIGN_IN = "sign-in"
    const val REGISTER = "register"
    const val SETTINGS = "settings"
    /** "Change your password" from the Settings menu under Home's gear (row 57). */
    const val CHANGE_PASSWORD = "settings/password"
    const val HOME = "home"
    const val REPORTS = "reports"
    /** The shop owner's controls on a shop's tablet (row 12); a personal account has no Profile (row 57). */
    const val PROFILE = "profile"
    const val CHAT =
        "chat/{sessionId}?itemTypeId={itemTypeId}&carry={carry}&from={from}"

    /**
     * [carry] holds the previous assessment's intake answers and [from] its id, both set
     * only when this assessment was started from the end of another one. Absent is the
     * normal case and means the full intake, so an old back-stack entry degrades into
     * asking properly rather than into a crash.
     */
    fun chat(
        sessionId: String,
        itemTypeId: String? = null,
        carry: String? = null,
        from: String? = null,
    ): String {
        val query = listOfNotNull(
            itemTypeId?.let { "itemTypeId=$it" },
            carry?.let { "carry=$it" },
            from?.let { "from=$it" },
        )
        return "chat/$sessionId" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    fun of(tab: MainTab): String = when (tab) {
        MainTab.HOME -> HOME
        MainTab.REPORTS -> REPORTS
    }
}

@Composable
fun AppNav() {
    val container = appContainer()
    val navController = rememberNavController()
    // Evaluated once per process. Signing in navigates onward itself, and signing out
    // pops back to the start, so this never needs re-reading.
    val start = remember { if (container.isSignedIn) Routes.HOME else Routes.WELCOME }

    // A review answered with no signal, flushed the next time the app is opened.
    //
    // The questionnaire pushes its own answers, so this only matters when that attempt
    // failed — an evaluator in a workshop, which is where the walkthroughs happen. Without
    // it the remaining flush points are the Reports screen and a deletion, neither of which
    // an evaluator has any reason to visit, and the answers sit on the handset unnoticed.
    //
    // Deliberately not a WorkManager job: the cost of one is not the battery, it is a
    // background path that constructs the whole container in a process started without an
    // Activity. This covers the same case for anyone who opens the app again.
    LaunchedEffect(Unit) {
        if (container.isSignedIn) runCatching { container.assessmentSync.pushReviews() }
    }

    // Below the minimum, nothing behind this would work: the server answers 426 to every
    // route but sign-in and the config. So the update screen replaces the app outright.
    val update by container.appVersionCheck.state.collectAsState()
    if (update.prompt == UpdatePrompt.REQUIRED) {
        UpdateRequiredScreen(rememberUpdateLabels(), update.downloadUrl)
        return
    }

    // Signing out and deleting the account end in the same place, so they share one path:
    // a deleted account whose tokens were left on the handset would look signed in until
    // the next request was refused. They differ only in the push: a sign-out sends the
    // account's unsent work first, in the background; a deleted account sends nothing
    // (see AppContainer.signOut).
    val leave = { pushPendingWork: Boolean ->
        container.signOut(pushPendingWork)
        navController.navigate(Routes.WELCOME) {
            // The whole back stack goes, so back from sign-in cannot reopen a signed-out
            // account's screens. That is all this does. The assessments stay on the phone;
            // they are cleared when a different account signs in (AppContainer's
            // LocalAccountGuard), and kept when the same account signs back in.
            popUpTo(0) { inclusive = true }
        }
    }
    val signOut = { leave(true) }
    val accountDeleted = { leave(false) }

    // A shop's tablet (row 12). Every end of a customer's assessment brings back an empty
    // start screen with nothing behind it, which also closes the owner's controls. The first
    // value is skipped: it is the count as this composition found it, not an end. When the
    // server has refused the sign-in meanwhile (an expired or revoked refresh token), the
    // tablet is still a shop's and has still wiped; it goes to sign-in with nothing behind,
    // unless the owner is already there or registering, which no customer's screen is.
    val shop = shopMode()
    LaunchedEffect(shop) {
        shop.resets.drop(1).collect {
            val here = navController.currentDestination?.route
            when {
                container.isSignedIn ->
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                here != Routes.SIGN_IN && here != Routes.REGISTER && here != Routes.WELCOME ->
                    navController.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true } }
            }
        }
    }
    // Five minutes with no touch ends the assessment, checked every few seconds while the
    // app is open. A personal account's watch never fires: end() does nothing for it.
    LaunchedEffect(shop) {
        while (true) {
            delay(IDLE_CHECK_MILLIS)
            if (shop.active && shop.idle.isIdle()) {
                shop.idle.touched()
                shop.end(TabletEnd.IDLE)
            }
        }
    }

    NavHost(navController = navController, startDestination = start) {

        // The first screen of a signed-out phone (v0.7.1, row 57, P17): what Kagua does,
        // before anyone is asked to sign in.
        composable(Routes.WELCOME) {
            val words = rememberKaguaLabels()
            Box(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
                WelcomeContent(
                    wordmark = stringResource(R.string.app_name).uppercase(),
                    tagline = stringResource(R.string.app_tagline),
                    title = words.welcomeTitle,
                    howItWorks = words.howItWorks,
                    steps = listOf(
                        WelcomeStep(words.stepChoose, words.stepChooseLine),
                        WelcomeStep(words.stepPhotos, words.stepPhotosLine),
                        WelcomeStep(words.stepRead, words.stepReadLine),
                    ),
                    primary = words.signIn,
                    onPrimary = { navController.navigate(Routes.SIGN_IN) },
                    secondary = words.createAccount,
                    onSecondary = { navController.navigate(Routes.REGISTER) },
                )
            }
        }

        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        // Cleared, not stacked: back from Home must not return to a
                        // sign-in or welcome screen for an account already signed in.
                        popUpTo(0) { inclusive = true }
                    }
                },
                onRegister = {
                    navController.navigate(Routes.REGISTER) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
                },
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegistered = {
                    navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                },
                onSignIn = {
                    navController.navigate(Routes.SIGN_IN) { popUpTo(Routes.REGISTER) { inclusive = true } }
                },
            )
        }

        composable(Routes.HOME) {
            // Read here, when the screen is built: signing in is what makes an account a shop's.
            val shopActive = shop.active
            SecureWindow(shopActive)
            MainScaffold(MainTab.HOME, navController.tabSelector(), showTabs = HomeChrome.of(shopActive).tabs) { padding ->
                HomeScreen(
                    contentPadding = padding,
                    onItemChosen = { itemType ->
                        val sessionId = UUID.randomUUID().toString()
                        navController.navigate(Routes.chat(sessionId, itemType.id))
                    },
                    shopMode = shopActive,
                    // The password opened it; the account screen is on top of the start
                    // screen, so Back, and every end, closes it again.
                    onOwnerUnlocked = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                    onChangePassword = { navController.navigate(Routes.CHANGE_PASSWORD) { launchSingleTop = true } },
                    onSignOut = signOut,
                    // The server has already deleted the account by the time this runs.
                    onAccountDeleted = accountDeleted,
                )
            }
        }

        composable(Routes.REPORTS) {
            // A shop's tablet has no reports to show (row 12): nothing leads here, and if
            // anything did, it would find the start screen instead.
            if (shop.active) {
                LaunchedEffect(Unit) { navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            MainScaffold(MainTab.REPORTS, navController.tabSelector()) { padding ->
                ReportsScreen(
                    contentPadding = padding,
                    onOpenSession = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                    onCheckFirst = { navController.switchTo(MainTab.HOME) },
                )
            }
        }

        composable(Routes.CHANGE_PASSWORD) {
            ChangePasswordScreen(
                container = container,
                labels = rememberAuthLabels(),
                backLabel = rememberKaguaLabels().tabHome,
                onBack = { navController.popBackStack() },
            )
        }

        // A shop's tablet only (row 12): the owner's controls, opened with the password. A
        // personal account has no Profile; its settings are under Home's gear (row 57).
        composable(Routes.PROFILE) {
            val shopActive = shop.active
            if (!shopActive) {
                LaunchedEffect(Unit) { navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } }
                return@composable
            }
            MainScaffold(MainTab.HOME, navController.tabSelector(), showTabs = false) { padding ->
                ProfileScreen(
                    contentPadding = padding,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                    onSignOut = signOut,
                    shopMode = true,
                    onBackToStart = { navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } },
                )
            }
        }

        // Outside the tab scaffold, like the chat: Settings is somewhere you go from
        // Profile and come back from, not a tab of its own, so it has a back arrow and no
        // bottom bar.
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                labels = rememberAuthLabels(),
                onBack = { navController.popBackStack() },
                // The server has already deleted the account by the time this runs; the
                // view model only reports success after DELETE /v1/account succeeds.
                onAccountDeleted = accountDeleted,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("itemTypeId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("carry") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("from") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val itemType = entry.arguments?.getString("itemTypeId")?.let(ItemType::fromId)
            val shopActive = shop.active
            val words = rememberKaguaLabels()
            ChatScreen(
                sessionId = sessionId,
                itemType = itemType,
                intakePrefill = decodeIntake(entry.arguments?.getString("carry")),
                previousSessionId = entry.arguments?.getString("from"),
                // The screen Back returns to, by name (P27).
                backLabel = when (navController.previousBackStackEntry?.destination?.route) {
                    Routes.REPORTS -> words.tabReports
                    Routes.HOME -> words.tabHome
                    else -> null
                },
                // On a shop's tablet, Back ends the customer's assessment as Done does (row 12).
                onBack = { if (shopActive) shop.end(TabletEnd.DONE) else navController.popBackStack() },
                shopMode = shopActive,
                onDone = { shop.end(TabletEnd.DONE) },
                // A sign-in failure on a personal phone: the settings screen, with sign-out.
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                // "Delete this report" on the report's page (row 57): it is gone, so is its page.
                onDeleted = { navController.popBackStack() },
                onAssessAnother = { nextItemType, carried ->
                    // A new conversation rather than a continuation of this one: each
                    // piece gets its own report, and the earlier one stays as it was.
                    // Not popped off the stack either, so back walks the chain.
                    navController.navigate(
                        Routes.chat(
                            sessionId = UUID.randomUUID().toString(),
                            itemTypeId = nextItemType.id,
                            carry = carried?.let(::encodeIntake),
                            from = sessionId,
                        )
                    )
                },
            )
        }

    }
}

private fun NavHostController.tabSelector(): (MainTab) -> Unit = { tab -> switchTo(tab) }

/**
 * On a shop's tablet (row 12) the window is never captured: no screenshot, and no picture of
 * a customer's result in the recent-apps list. Set from the start screen, which every
 * sign-in reaches before any assessment, as well as on each resume (MainActivity).
 */
@Composable
private fun SecureWindow(active: Boolean) {
    val window = LocalActivity.current?.window ?: return
    SideEffect {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** How often the shop tablet's idle watch is read (row 12). A few seconds late is fine. */
private const val IDLE_CHECK_MILLIS = 10_000L

/**
 * Tab switching keeps Home at the root of the stack, so the system back button leaves
 * Reports or Profile for Home rather than closing the app, and each tab keeps its own
 * scroll position when you come back to it.
 */
private fun NavHostController.switchTo(tab: MainTab) {
    val route = Routes.of(tab)
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = route != Routes.HOME }
        launchSingleTop = true
        restoreState = true
    }
}

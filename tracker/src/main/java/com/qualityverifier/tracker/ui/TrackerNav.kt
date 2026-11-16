package com.qualityverifier.tracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.text.TrackerLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.tracker.APP_NAME
import com.qualityverifier.tracker.listen.ListenerAccess
import com.qualityverifier.tracker.trackerContainer
import com.qualityverifier.tracker.ui.access.AccessScreen
import com.qualityverifier.tracker.ui.auth.PasswordScreen
import com.qualityverifier.tracker.ui.auth.PhoneScreen
import com.qualityverifier.tracker.ui.auth.SignInScreen
import com.qualityverifier.tracker.ui.auth.rememberAuthViewModel
import com.qualityverifier.tracker.ui.consent.ConsentScreen
import com.qualityverifier.tracker.ui.consent.Question
import com.qualityverifier.tracker.ui.home.HomeScreen
import com.qualityverifier.tracker.ui.settings.SettingsScreen
import com.qualityverifier.tracker.ui.terms.TermsScreen
import com.qualityverifier.tracker.ui.transaction.TransactionScreen
import com.qualityverifier.ui.update.UpdateRequiredScreen

private object Routes {
    const val SIGN_IN = "sign-in"
    const val REGISTER_PHONE = "register-phone"
    const val REGISTER_PASSWORD = "register-password"
    const val TERMS = "terms"

    /** The two questions; [Question.id] first. `back` true returns to the screen that asked. */
    const val CONSENT = "consent/{question}?back={back}"
    fun consent(question: Question, back: Boolean = false) = "consent/${question.id}?back=$back"

    const val ACCESS = "access?back={back}"
    fun access(back: Boolean = false) = "access?back=$back"

    const val HOME = "home"
    const val TRANSACTION = "transaction/{network}/{code}"
    fun transaction(network: String, code: String) = "transaction/$network/$code"
    const val SETTINGS = "settings"
}

/**
 * PesaLog's screens: sign-in (or sign-up with an invite code), PesaLog's terms, the two
 * questions, the guided step to notification access, then the log. Each step is skipped when
 * it is already done: an account that agreed on another phone, or access already on.
 */
@Composable
fun TrackerNav() {
    val container = trackerContainer()
    val context = LocalContext.current
    val navController = rememberNavController()
    val labels = remember { TrackerLabels.english(APP_NAME) }
    val auth = rememberAuthViewModel(labels)

    val scope = rememberCoroutineScope()

    // Read once per process. Signed in with PesaLog's own terms last known agreed: home, with
    // a check in the background; otherwise the terms screen, which asks the server. PesaLog's
    // own flag (TrackerPreference), not the token store's, which holds Fundi Bora's terms.
    val start = remember {
        when {
            !container.isSignedIn -> Routes.SIGN_IN
            container.termsAgreed == true -> Routes.HOME
            else -> Routes.TERMS
        }
    }

    // Below the minimum, the server answers 426 to everything but sign-in and the config.
    val update by container.appVersionCheck.state.collectAsState()
    if (update.prompt == UpdatePrompt.REQUIRED) {
        UpdateRequiredScreen(UpdateLabels.ENGLISH, update.downloadUrl)
        return
    }

    val signOut: () -> Unit = {
        scope.launch {
            container.signOut()
            auth.reset()
            navController.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true } }
        }
    }

    // Opening on home with a stored "agreed": ask again, and open the terms screen if PesaLog's
    // terms changed since.
    LaunchedEffect(Unit) {
        if (start == Routes.HOME && container.terms.status()?.required == true) {
            navController.navigate(Routes.TERMS) { popUpTo(0) { inclusive = true } }
        }
    }

    /**
     * After PesaLog's terms: the answers this account already gave on another phone are taken
     * from the server (only now, so nothing is read here before the terms are agreed here);
     * then the first question if reading is off, access if it is off, else home. A new terms
     * version leaves sending as it was: the upload refused meanwhile was a "later", not a no.
     */
    fun afterTerms() {
        scope.launch {
            runCatching { container.repository.adoptServerAnswers() }
            val next = when {
                !container.repository.switches.value.reading -> Routes.consent(Question.READ)
                !ListenerAccess.isEnabled(context) -> Routes.access()
                else -> Routes.HOME
            }
            navController.navigate(next) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SIGN_IN) {
            SignInScreen(
                viewModel = auth,
                labels = labels,
                onSignedIn = { navController.navigate(Routes.TERMS) { popUpTo(0) { inclusive = true } } },
                onRegister = { navController.navigate(Routes.REGISTER_PHONE) },
            )
        }

        composable(Routes.REGISTER_PHONE) {
            PhoneScreen(
                viewModel = auth,
                labels = labels,
                onProceed = { navController.navigate(Routes.REGISTER_PASSWORD) { launchSingleTop = true } },
                onSignIn = { navController.popBackStack() },
            )
        }

        composable(Routes.REGISTER_PASSWORD) {
            PasswordScreen(
                viewModel = auth,
                labels = labels,
                onRegistered = { navController.navigate(Routes.TERMS) { popUpTo(0) { inclusive = true } } },
                onChangeDetails = { navController.popBackStack(Routes.REGISTER_PHONE, inclusive = false) },
            )
        }

        composable(Routes.TERMS) {
            TermsScreen(onDone = ::afterTerms, onSignOut = signOut)
        }

        composable(
            Routes.CONSENT,
            arguments = listOf(
                navArgument("question") { type = NavType.StringType },
                navArgument("back") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val back = entry.arguments?.getBoolean("back") == true
            ConsentScreen(
                first = Question.fromId(entry.arguments?.getString("question")),
                labels = labels,
                onDone = {
                    when {
                        back -> navController.popBackStack()
                        container.repository.switches.value.reading && !ListenerAccess.isEnabled(context) ->
                            navController.navigate(Routes.access()) { popUpTo(0) { inclusive = true } }
                        else -> navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }

        composable(
            Routes.ACCESS,
            arguments = listOf(navArgument("back") {
                type = NavType.BoolType
                defaultValue = false
            }),
        ) { entry ->
            val back = entry.arguments?.getBoolean("back") == true
            AccessScreen(labels = labels, onDone = {
                if (back) navController.popBackStack() else navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
            })
        }

        composable(Routes.HOME) {
            HomeScreen(
                labels = labels,
                onOpen = { network, code -> navController.navigate(Routes.transaction(network, code)) },
                onTurnOnReading = { navController.navigate(Routes.consent(Question.READ, back = true)) },
                onTurnOnAccess = { navController.navigate(Routes.access(back = true)) },
                onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }

        composable(Routes.TRANSACTION) { entry ->
            TransactionScreen(
                network = entry.arguments?.getString("network").orEmpty(),
                code = entry.arguments?.getString("code").orEmpty(),
                labels = labels,
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                labels = labels,
                onTurnOnReading = { navController.navigate(Routes.consent(Question.READ, back = true)) },
                onTurnOnSending = { navController.navigate(Routes.consent(Question.SEND, back = true)) },
                onOpenAccess = { navController.navigate(Routes.access(back = true)) },
                onSignOut = signOut,
                onBack = { navController.popBack(Routes.HOME) },
            )
        }
    }
}

/** Back, or to [fallback] when there is nothing to go back to. */
private fun NavHostController.popBack(fallback: String) {
    if (!popBackStack()) navigate(fallback) { popUpTo(0) { inclusive = true } }
}

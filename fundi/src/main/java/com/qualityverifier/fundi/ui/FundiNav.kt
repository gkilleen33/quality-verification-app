package com.qualityverifier.fundi.ui

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
import com.qualityverifier.fundi.ui.assess.AssessScreen
import com.qualityverifier.fundi.ui.home.HomeScreen
import com.qualityverifier.fundi.ui.auth.FundiRegisterScreen
import com.qualityverifier.fundi.ui.auth.FundiSignInScreen
import com.qualityverifier.fundi.ui.setup.GoalsScreen
import com.qualityverifier.fundi.ui.setup.SetupViewModel
import com.qualityverifier.fundi.ui.setup.ToolsScreen
import com.qualityverifier.fundi.ui.setup.WorkshopScreen
import com.qualityverifier.fundi.ui.workers.WorkersScreen
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.update.UpdateRequiredScreen
import java.util.UUID

private object Routes {
    const val SIGN_IN = "sign-in"
    const val REGISTER = "register"
    const val SETUP_WORKSHOP = "setup/workshop"
    const val SETUP_TOOLS = "setup/tools"
    const val SETUP_GOALS = "setup/goals"
    const val HOME = "home"
    const val WORKERS = "workers"
    const val SETTINGS = "settings"
    const val ASSESS = "assess/{sessionId}/{itemTypeId}?piece={piece}&returning={returning}"

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
}

@Composable
fun FundiNav() {
    val container = fundiContainer()
    val navController = rememberNavController()
    // Evaluated once per process. Signing in navigates onward itself, and signing out
    // replaces the whole stack with sign-in, so this never needs re-reading.
    val start = remember { if (container.isSignedIn) Routes.HOME else Routes.SIGN_IN }

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
    // request was refused.
    val signOut = {
        container.signOut()
        navController.navigate(Routes.SIGN_IN) {
            // Everything goes: the next person to open the app must not find another
            // maker's pieces behind the back button.
            popUpTo(0) { inclusive = true }
        }
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SIGN_IN) {
            FundiSignInScreen(
                // Straight to home, not to setup. A maker signing in on a new handset has
                // already answered all this, and their profile is on the server.
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
                onRegister = { navController.navigate(Routes.REGISTER) },
            )
        }

        composable(Routes.REGISTER) {
            FundiRegisterScreen(
                // Registration is the one path that leads into setup: a brand new account
                // has no workshop row, and the tool list is what the coaching is built on.
                onRegistered = {
                    navController.navigate(Routes.SETUP_WORKSHOP) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
                onSignIn = { navController.popBackStack() },
            )
        }

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

        composable(Routes.HOME) {
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
                onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }

        composable(Routes.WORKERS) {
            WorkersScreen(onBack = { navController.popBackStack() })
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
                onAccountDeleted = signOut,
                onSignOut = signOut,
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

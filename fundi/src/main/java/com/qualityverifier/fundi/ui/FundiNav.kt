package com.qualityverifier.fundi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qualityverifier.fundi.ui.auth.FundiRegisterScreen
import com.qualityverifier.fundi.ui.auth.FundiSignInScreen
import com.qualityverifier.fundi.ui.setup.GoalsScreen
import com.qualityverifier.fundi.ui.setup.SetupViewModel
import com.qualityverifier.fundi.ui.setup.ToolsScreen
import com.qualityverifier.fundi.ui.setup.WorkshopScreen
import com.qualityverifier.text.FundiLabels

private object Routes {
    const val SIGN_IN = "sign-in"
    const val REGISTER = "register"
    const val SETUP_WORKSHOP = "setup/workshop"
    const val SETUP_TOOLS = "setup/tools"
    const val SETUP_GOALS = "setup/goals"
    const val HOME = "home"
}

@Composable
fun FundiNav() {
    val container = fundiContainer()
    val navController = rememberNavController()
    // Evaluated once per process: signing in navigates onward itself, and nothing here
    // signs out yet.
    val start = remember { if (container.isSignedIn) Routes.HOME else Routes.SIGN_IN }

    // English only, and that is a gap rather than a decision — see FundiLabels. Kagua's
    // Swahili is unreviewed placeholder copy already (issue #20), and a producer-facing
    // app in Kenya needs that pass more than the buyer's app does, not less.
    val labels = FundiLabels.ENGLISH

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
            WorkshopScreen(
                labels = labels,
                workshop = workshop,
                onChange = { model.updateWorkshop(it) },
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

        composable(Routes.HOME) { FundiHome() }
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

/**
 * Where an assessment will start.
 *
 * Still a placeholder: the loop is the next slice. What is real behind it is everything
 * an assessment needs — an account, a workshop profile with a tool list, and a server
 * that hands this account the coaching prompt.
 */
@Composable
private fun FundiHome() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
        Text(
            "Setup is saved. Assessments arrive in the next build.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

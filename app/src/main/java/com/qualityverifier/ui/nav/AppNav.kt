package com.qualityverifier.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.ItemType
import com.qualityverifier.text.decodeIntake
import com.qualityverifier.text.encodeIntake
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.chat.ChatScreen
import com.qualityverifier.ui.home.HomeScreen
import com.qualityverifier.ui.main.MainScaffold
import com.qualityverifier.ui.main.MainTab
import com.qualityverifier.ui.profile.ProfileScreen
import com.qualityverifier.ui.reports.ReportsScreen
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.auth.RegisterScreen
import com.qualityverifier.ui.auth.SignInScreen
import java.util.UUID

private object Routes {
    const val SIGN_IN = "sign-in"
    const val REGISTER = "register"
    const val SETTINGS = "settings"
    const val HOME = "home"
    const val REPORTS = "reports"
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
        MainTab.HOME, MainTab.ASSESS -> HOME
        MainTab.REPORTS -> REPORTS
        MainTab.PROFILE -> PROFILE
    }
}

@Composable
fun AppNav() {
    val container = appContainer()
    val navController = rememberNavController()
    // Evaluated once per process. Signing in navigates onward itself, and signing out
    // pops back to the start, so this never needs re-reading.
    val start = remember { if (container.isSignedIn) Routes.HOME else Routes.SIGN_IN }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        // Cleared, not stacked: back from Home must not return to a
                        // sign-in screen for an account already signed in.
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
                onRegister = { navController.navigate(Routes.REGISTER) },
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegistered = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
                onSignIn = { navController.popBackStack() },
            )
        }

        composable(Routes.HOME) {
            MainScaffold(MainTab.HOME, navController.tabSelector()) { padding ->
                HomeScreen(
                    contentPadding = padding,
                    onItemChosen = { itemType ->
                        val sessionId = UUID.randomUUID().toString()
                        navController.navigate(Routes.chat(sessionId, itemType.id))
                    },
                    onOpenReports = { navController.switchTo(MainTab.REPORTS) },
                )
            }
        }

        composable(Routes.REPORTS) {
            MainScaffold(MainTab.REPORTS, navController.tabSelector()) { padding ->
                ReportsScreen(
                    contentPadding = padding,
                    onOpenSession = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                )
            }
        }

        composable(Routes.PROFILE) {
            MainScaffold(MainTab.PROFILE, navController.tabSelector()) { padding ->
                ProfileScreen(
                    contentPadding = padding,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onSignOut = {
                        container.signOut()
                        navController.navigate(Routes.SIGN_IN) {
                            // Everything goes: the next person to open the app must not
                            // find another account's assessments behind the back button.
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
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
            ChatScreen(
                sessionId = sessionId,
                itemType = itemType,
                intakePrefill = decodeIntake(entry.arguments?.getString("carry")),
                previousSessionId = entry.arguments?.getString("from"),
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.PROFILE) },
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
                // A different kind of piece needs its own protocol and its own intake,
                // so it starts where every first assessment starts: the grid.
                onAssessDifferent = { navController.switchTo(MainTab.HOME) },
            )
        }

    }
}

private fun NavHostController.tabSelector(): (MainTab) -> Unit = { tab -> switchTo(tab) }

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

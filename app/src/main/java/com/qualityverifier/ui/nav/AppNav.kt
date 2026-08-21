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
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.chat.ChatScreen
import com.qualityverifier.ui.home.HomeScreen
import com.qualityverifier.ui.main.MainScaffold
import com.qualityverifier.ui.main.MainTab
import com.qualityverifier.ui.profile.ProfileScreen
import com.qualityverifier.ui.reports.ReportsScreen
import com.qualityverifier.ui.setup.ApiKeySetupScreen
import java.util.UUID

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val REPORTS = "reports"
    const val PROFILE = "profile"
    const val REPLACE_KEY = "replace-key"
    const val CHAT = "chat/{sessionId}?itemTypeId={itemTypeId}"

    fun chat(sessionId: String, itemTypeId: String? = null): String =
        "chat/$sessionId" + if (itemTypeId != null) "?itemTypeId=$itemTypeId" else ""

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
    // Evaluated once per process: the only way to gain a key is via the setup screen,
    // which navigates onward itself.
    val start = remember { if (container.apiKeyStore.hasKey()) Routes.HOME else Routes.SETUP }

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SETUP) {
            ApiKeySetupScreen(
                onSaved = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
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
                    onReplaceKey = { navController.navigate(Routes.REPLACE_KEY) },
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
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val itemType = entry.arguments?.getString("itemTypeId")?.let(ItemType::fromId)
            ChatScreen(
                sessionId = sessionId,
                itemType = itemType,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.PROFILE) },
            )
        }

        composable(Routes.REPLACE_KEY) {
            ApiKeySetupScreen(
                onSaved = { navController.popBackStack() },
                title = "Rotate API key",
                saveLabel = "Save key",
                body = "Enter the new Anthropic API key to use on this phone. " +
                    "It replaces the key currently saved and is stored encrypted.",
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

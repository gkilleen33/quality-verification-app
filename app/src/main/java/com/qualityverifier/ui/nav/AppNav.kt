package com.qualityverifier.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qualityverifier.domain.ItemType
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.chat.ChatScreen
import com.qualityverifier.ui.home.HomeScreen
import com.qualityverifier.ui.items.ItemSelectionScreen
import com.qualityverifier.ui.settings.SettingsScreen
import com.qualityverifier.ui.setup.ApiKeySetupScreen
import java.util.UUID

private object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val ITEMS = "items"
    const val SETTINGS = "settings"
    const val REPLACE_KEY = "replace-key"
    const val CHAT = "chat/{sessionId}?itemTypeId={itemTypeId}"

    fun chat(sessionId: String, itemTypeId: String? = null): String =
        "chat/$sessionId" + if (itemTypeId != null) "?itemTypeId=$itemTypeId" else ""
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
            HomeScreen(
                onNewEvaluation = { navController.navigate(Routes.ITEMS) },
                onOpenSession = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.ITEMS) {
            ItemSelectionScreen(
                onItemChosen = { itemType ->
                    val sessionId = UUID.randomUUID().toString()
                    navController.navigate(Routes.chat(sessionId, itemType.id)) {
                        // The picker is a transient step: coming back from chat should
                        // land on Home, not on the grid again.
                        popUpTo(Routes.ITEMS) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
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
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val itemType = entry.arguments?.getString("itemTypeId")?.let(ItemType::fromId)
            ChatScreen(
                sessionId = sessionId,
                itemType = itemType,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onReplaceKey = { navController.navigate(Routes.REPLACE_KEY) },
            )
        }

        composable(Routes.REPLACE_KEY) {
            ApiKeySetupScreen(
                onSaved = { navController.popBackStack() },
                title = "Replace API key",
                saveLabel = "Save key",
                body = "Enter the new Anthropic API key to use on this phone. " +
                    "It replaces the key currently saved and is stored encrypted.",
            )
        }
    }
}

package com.qualityverifier.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The four sections of the app.
 *
 * [ASSESS] is a shortcut rather than a destination of its own: starting an assessment
 * means picking a category, and that grid is what [HOME] already is. Giving it a slot in
 * the bar keeps the primary action one tap away from Reports and Profile, which is the
 * whole point of putting it there.
 */
enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    REPORTS("Reports", Icons.AutoMirrored.Filled.List),
    ASSESS("Assess", Icons.Filled.AddCircle),
    PROFILE("Profile", Icons.Filled.Person),
}

@Composable
fun MainScaffold(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { onSelect(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                // The primary action reads as bigger, as it does in the
                                // mockup, without needing a floating button that would
                                // cover the category grid it leads to.
                                modifier = if (tab == MainTab.ASSESS) {
                                    Modifier.size(32.dp)
                                } else {
                                    Modifier
                                },
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        content = content,
    )
}

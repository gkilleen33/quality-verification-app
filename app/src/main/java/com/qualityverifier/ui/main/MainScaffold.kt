package com.qualityverifier.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import com.qualityverifier.text.KaguaScreenLabels
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.rememberKaguaLabels

/**
 * The two tabs of a personal account (v0.7.1, row 57; Kagua v2 mockup): Home and My reports,
 * side by side in the middle of the bar. There is no Profile tab and no Assess tab: Home is
 * where an assessment starts, and the account's settings are under Home's gear.
 */
enum class MainTab(val icon: ImageVector) {
    HOME(Icons.Filled.Home),
    REPORTS(Icons.AutoMirrored.Filled.List);

    fun label(words: KaguaScreenLabels): String = when (this) {
        HOME -> words.tabHome
        REPORTS -> words.tabReports
    }
}

@Composable
fun MainScaffold(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    /**
     * False on a shop's tablet (row 12), which has no My reports for a customer to open: the
     * start screen is the whole app, and the owner reaches the account through the password.
     */
    showTabs: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val words = rememberKaguaLabels()
    Scaffold(
        containerColor = Parts.Page,
        bottomBar = {
            if (showTabs) NavigationBar(containerColor = Parts.Page) {
                // Centred as a pair rather than spread across the bar.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = { onSelect(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label(words)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Parts.StripBackground),
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                }
            }
        },
        content = content,
    )
}

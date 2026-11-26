package com.qualityverifier.ui.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * One item of the Settings menu: a label, an optional line under it, an optional On/Off chip,
 * and red when the action cannot be undone. [onClick] decides whether the menu closes: a
 * switch stays open so the new state can be seen, an item that opens a screen closes it.
 * [checked] is set on a switch, so TalkBack reads it as one.
 */
data class SettingsMenuItem(
    val label: String,
    val line: String? = null,
    val state: Chip? = null,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** The mockups' scrim, #1C1611 at 28 %. */
private const val SCRIM_ALPHA = 0.28f

/**
 * Raj Hansini's UI review (row 46): the gear opens one dropdown panel over the screen, with a
 * scrim, in place of a settings screen. Its title is a tinted strip, as every card's is (P16).
 *
 * A full-window dialog rather than Material's DropdownMenu: the mockup's panel is wider than
 * DropdownMenu allows, and its scrim dims the whole screen, the system bars included, so the
 * panel reads as the one thing to deal with. Tapping the scrim or Back closes it. [foot] is
 * anything under the items (Kagua's research note and version).
 */
@Composable
fun SettingsMenu(
    title: String,
    items: List<SettingsMenuItem>,
    onDismiss: () -> Unit,
    closeDescription: String,
    foot: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dialog window's own dim, set to the mockups' scrim rather than drawn a second time.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(SCRIM_ALPHA) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scrimTap = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = scrimTap, indication = null, role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = closeDescription },
            )
            // 300 dp as drawn, and never wider than the screen less its margins (P24: 320 dp).
            val width = minOf(300.dp, maxWidth - 28.dp)
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    // Below the gear, which ends 66 dp down, as the mockup places the panel.
                    .padding(top = 70.dp, end = 14.dp)
                    .width(width)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Parts.Border, RoundedCornerShape(16.dp))
                    .heightIn(max = maxHeight - 90.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CardHeading(title)
                items.forEachIndexed { i, item -> MenuRow(item, divider = i > 0) }
                foot?.let {
                    RowDivider()
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { it() }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: SettingsMenuItem, divider: Boolean) {
    Column {
        if (divider) RowDivider()
        val touch = item.checked?.let { Modifier.toggleable(value = it, role = Role.Switch, onValueChange = { item.onClick() }) }
            ?: Modifier.clickable(role = Role.Button, onClick = item.onClick)
        Row(
            Modifier
                .fillMaxWidth()
                .then(touch)
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.label,
                    color = if (item.danger) Parts.Danger else Parts.Ink,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                item.line?.let { Text(it, color = Parts.Muted, fontSize = 14.sp, lineHeight = 19.sp) }
            }
            item.state?.let { ChipView(it, bold = true) }
        }
    }
}

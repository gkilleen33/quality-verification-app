package com.qualityverifier.ui.parts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The screen parts both apps compose their v0.7.1 screens from (rows 45, 46, 56 and 57),
 * drawn as the approved mockups draw them (`App_FundiBora_v2` and `App_Kagua_v2`, 24 November
 * 2026), so each screen does not draw its own card, row, box or button.
 *
 * The colours are the mockups' own. The theme already has most of them under Material role
 * names (Timber is primary, Sawdust the background); the ones it lacks, the strip tint and the
 * status boxes, are named here. Light only, as the mockups are: both apps' screens set their
 * own backgrounds from these, so a phone in dark mode sees the same warm page.
 */
object Parts {
    val Page = Color(0xFFF6EFE6)
    val Ink = Color(0xFF1C1611)
    val Brand = Color(0xFF6B4A2F)
    val Muted = Color(0xFF4E4237)
    val Border = Color(0xFFE4D7C7)
    val ButtonBorder = Color(0xFFB9A994)

    /** P16: a section title is a tinted strip inside its card. */
    val StripBackground = Color(0xFFF1E6D8)
    val StripText = Color(0xFF4E3420)

    val OffBackground = Color(0xFFE4D7C7)
    val OffText = Color(0xFF6E6258)
    val Danger = Color(0xFFB3261E)
    val RadioOff = Color(0xFF847666)

    /** The callout's amber, which also marks a prompt (P19). */
    val Amber = Color(0xFFFBEBCF)
    val AmberBorder = Color(0xFFE8C98F)
    val AmberText = Color(0xFF7A4A00)
    val AmberStrong = Color(0xFF3D2600)
}

/** A small rounded label: a row's chip ("Edit", "Linked", "New"), a severity, a menu's On/Off. */
data class Chip(val label: String, val container: Color, val content: Color, val icon: ImageVector? = null) {
    companion object {
        /** "Edit", "New" before it is styled, and any neutral tag (the mockups' default tag). */
        fun neutral(label: String) = Chip(label, Color(0xFFE9D8C6), Color(0xFF2A1A0C))
        fun linked(label: String) = Chip(label, Color(0xFFD6E8CE), Color(0xFF1F3D14))
        fun notLinked(label: String) = Chip(label, Color(0xFFECE8E3), Color(0xFF4E4237))
        fun new(label: String) = Chip(label, Color(0xFFD6E8CE), Color(0xFF1F3D14))

        /**
         * A setting's state in the Settings menu: green "On", grey "Off". The words come from
         * the caller, so Kagua can pass its Kiswahili.
         */
        fun state(on: Boolean, onLabel: String, offLabel: String) =
            if (on) Chip(onLabel, Color(0xFFD6E8CE), Color(0xFF1F3D14)) else Chip(offLabel, Color(0xFFECE8E3), Color(0xFF4E4237))
    }
}

@Composable
fun ChipView(chip: Chip, modifier: Modifier = Modifier, fontSize: Int = 14, bold: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(chip.container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chip.icon?.let { Icon(it, contentDescription = null, tint = chip.content, modifier = Modifier.size(13.dp)) }
        Text(
            chip.label,
            color = chip.content,
            fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            lineHeight = 20.sp,
        )
    }
}

/**
 * P16: a white card with a 1 dp border and 16 dp corners, its title a tinted strip inside it,
 * then an optional line of text, then [rows], which sit one under another. Give every row but
 * the first `divider = true`, or use [rowsOf].
 */
@Composable
fun RowCard(
    modifier: Modifier = Modifier,
    heading: String? = null,
    hint: String? = null,
    hintItalic: Boolean = false,
    rows: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, Parts.Border), RoundedCornerShape(16.dp)),
    ) {
        if (heading != null) CardHeading(heading)
        if (hint != null) {
            Text(
                hint,
                style = lineStyle.copy(fontStyle = if (hintItalic) FontStyle.Italic else FontStyle.Normal),
                color = Parts.Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        rows()
    }
}

/** The tinted strip at the top of a card (P16), read by TalkBack as a heading. */
@Composable
fun CardHeading(text: String) {
    Column {
        Text(
            text,
            color = Parts.StripText,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .background(Parts.StripBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { heading() },
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Parts.Border))
    }
}

/** The inset line between two rows of a card. */
@Composable
fun RowDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Parts.Border))
}

/** P22: supporting text at a line height of about 1.5. */
val lineStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)

/**
 * One row of a card: the whole row is the touch target, at least 60 dp tall.
 *
 * @param line the line under the title (15 sp, line height about 1.5, P22).
 * @param topChip a chip above the title: a severity, a verdict.
 * @param chip a chip at the end: "Edit", "Linked", "New".
 * @param value a value at the end, such as a price.
 * @param radio a radio circle at the start; the row is then a radio button.
 * @param chevron whether the row opens a page. Defaults to true when it can be tapped and is
 *   not a radio.
 * @param extra anything more under the line, such as a buyer's meaning beside its tag icon.
 */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    line: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
    topChip: Chip? = null,
    chip: Chip? = null,
    value: String? = null,
    radio: Boolean? = null,
    chevron: Boolean = onClick != null && radio == null,
    titleColor: Color = Parts.Ink,
    enabled: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        if (divider) RowDivider()
        val touch = when {
            onClick == null -> Modifier
            radio != null -> Modifier.selectable(selected = radio, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        }
        Row(
            touch
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (radio != null) RadioCircle(radio)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                topChip?.let { ChipView(it) }
                Text(title, color = titleColor, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                line?.let { Text(it, style = lineStyle, color = Parts.Muted) }
                extra?.invoke()
            }
            chip?.let { ChipView(it) }
            value?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Parts.Ink) }
            if (chevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Parts.Brand,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** The round radio button every single choice uses (P25). Drawn, not Material's, to match the mockups. */
@Composable
fun RadioCircle(selected: Boolean, modifier: Modifier = Modifier) {
    val ring = if (selected) Parts.Brand else Parts.RadioOff
    Box(
        modifier
            .size(22.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Parts.Brand))
    }
}

/** The three kinds of status box (P19). */
enum class StatusKind(val container: Color, val border: Color, val content: Color, val icon: ImageVector) {
    /** Red: something failed. Stays until it is fixed or its button is pressed. */
    FAILURE(Color(0xFFF6D6D2), Color(0xFFE0A49C), Color(0xFF5B1410), Icons.Filled.Warning),

    /** Green: something worked, shown where the result is ("Report sent to …"). */
    CONFIRMATION(Color(0xFFDCEBD3), Color(0xFFB5D1A6), Color(0xFF1F3D14), Icons.Filled.Check),

    /** Amber: something the person is asked to do. */
    PROMPT(Parts.Amber, Parts.AmberBorder, Parts.AmberText, Icons.Outlined.Info),
}

/** A status box: an icon, a title, a line, and one button. */
@Composable
fun StatusBox(
    kind: StatusKind,
    title: String,
    line: String,
    button: String,
    onButton: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(kind.container)
            .border(1.dp, kind.border, RoundedCornerShape(16.dp))
            // Announced when it appears after a tap, as the mockup's role="status" is.
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(kind.icon, contentDescription = null, tint = kind.content, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = kind.content, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                Text(line, color = kind.content, style = lineStyle)
            }
        }
        OutlinedButton(
            onClick = onButton,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, kind.content),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = kind.content),
            contentPadding = PaddingValues(horizontal = 18.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(button, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * Whether a button can be pressed, and if not, what is missing (P19). A greyed button keeps its
 * place on the screen and says why under it, so a slow reader sees what to do rather than a
 * button that does nothing.
 */
data class ButtonState(val missing: String? = null) {
    val enabled: Boolean get() = missing == null

    /** The line under a greyed button; null when the button can be pressed. */
    val reason: String? get() = missing

    companion object {
        val Ready = ButtonState()
    }
}

/** The filled 56 dp button: one per screen, the main step (P18). */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    state: ButtonState = ButtonState.Ready,
) {
    AppButton(label, onClick, modifier, icon, state, primary = true)
}

/** The outlined 56 dp button for a second step beside the main one. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    state: ButtonState = ButtonState.Ready,
) {
    AppButton(label, onClick, modifier, icon, state, primary = false)
}

@Composable
private fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    state: ButtonState,
    primary: Boolean,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shape = RoundedCornerShape(14.dp)
        val content: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(24.dp)) }
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val size = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        if (primary) {
            Button(
                onClick = onClick,
                enabled = state.enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Parts.Brand,
                    contentColor = Color.White,
                    disabledContainerColor = Parts.OffBackground,
                    disabledContentColor = Parts.OffText,
                ),
                modifier = size,
            ) { content() }
        } else {
            OutlinedButton(
                onClick = onClick,
                enabled = state.enabled,
                shape = shape,
                border = BorderStroke(1.dp, if (state.enabled) Parts.ButtonBorder else Parts.OffBackground),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Parts.Brand,
                    disabledContainerColor = Parts.OffBackground,
                    disabledContentColor = Parts.OffText,
                ),
                modifier = size,
            ) { content() }
        }
        state.reason?.let {
            Text(
                it,
                style = lineStyle,
                color = Parts.Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The bar at the top of a screen below Home: Back names the screen it returns to ("‹ Home",
 * "‹ Daybed", "‹ My workers"; P27), with optional text on the right.
 */
@Composable
fun BackBar(backLabel: String, onBack: () -> Unit, modifier: Modifier = Modifier, right: String? = null) {
    Column(modifier.fillMaxWidth().background(Parts.Page)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Parts.Brand, modifier = Modifier.size(22.dp))
                // A long piece name is cut with an ellipsis rather than pushing the right text out.
                Text(
                    backLabel,
                    color = Parts.Brand,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            right?.let {
                Text(
                    it,
                    color = Parts.Muted,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                )
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Parts.Border))
    }
}

/** A screen's title and the line under it. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, line: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = Parts.Ink,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        line?.let { Text(it, color = Parts.Muted, fontSize = 17.sp, lineHeight = 26.sp) }
    }
}

/** The amber callout: a small label and a large line ("What it means for you"). */
@Composable
fun Callout(label: String, text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Parts.Amber)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Parts.AmberText, modifier = Modifier.size(26.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Parts.AmberText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = Parts.AmberStrong, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The app's wordmark, "Fundi Bora" or "KAGUA", and its tagline when it has one. */
@Composable
fun Wordmark(text: String, modifier: Modifier = Modifier, tagline: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = Parts.Ink, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        tagline?.let { Text(it, style = lineStyle, color = Parts.Muted) }
    }
}

/** One step of "How it works" on a welcome screen. */
data class WelcomeStep(val title: String, val line: String)

/**
 * The first launch (P17): the app's wordmark, a title, a card "How it works" of three rows,
 * and two buttons. Each app supplies its own words (rows 56 and 57).
 */
@Composable
fun WelcomeContent(
    wordmark: String,
    title: String,
    howItWorks: String,
    steps: List<WelcomeStep>,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    tagline: String? = null,
) {
    // Scrolls: on a small phone with large text the two buttons would otherwise fall off the screen.
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Wordmark(wordmark, tagline = tagline)
        ScreenTitle(title)
        RowCard(heading = howItWorks) {
            steps.forEachIndexed { i, step -> CardRow(step.title, line = step.line, divider = i > 0) }
        }
        PrimaryButton(primary, onPrimary)
        SecondaryButton(secondary, onSecondary)
    }
}

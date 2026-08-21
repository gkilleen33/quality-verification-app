package com.qualityverifier.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.qualityverifier.domain.VerdictLevel

private val Timber = Color(0xFF6B4A2F)
private val TimberLight = Color(0xFF8A6444)
private val Sawdust = Color(0xFFF6EFE6)
private val Ink = Color(0xFF1C1611)
private val Warning = Color(0xFFB3261E)

// Every surface role has to be named explicitly. Anything left unset keeps Material's
// baseline purple tint, which reads as a lavender card on this warm background —
// cards, dialogs and outlined buttons all pull from these roles.
private val LightColors = lightColorScheme(
    primary = Timber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9D8C6),
    onPrimaryContainer = Color(0xFF2A1A0C),
    secondary = TimberLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4D5C3),
    onSecondaryContainer = Color(0xFF2A1A0C),
    tertiary = Color(0xFF5B6247),
    onTertiary = Color.White,
    background = Sawdust,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE3D7),
    onSurfaceVariant = Color(0xFF4E4237),
    surfaceTint = Timber,
    surfaceBright = Color(0xFFFFFBF6),
    surfaceDim = Color(0xFFDFD3C5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF3EA),
    surfaceContainer = Color(0xFFF4EBE0),
    surfaceContainerHigh = Color(0xFFEEE4D8),
    surfaceContainerHighest = Color(0xFFE8DCCE),
    inverseSurface = Color(0xFF33291F),
    inverseOnSurface = Color(0xFFF7EFE6),
    outline = Color(0xFF847666),
    outlineVariant = Color(0xFFD3C5B4),
    error = Warning,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0BE9C),
    onPrimary = Color(0xFF3B2612),
    primaryContainer = Color(0xFF54381F),
    onPrimaryContainer = Color(0xFFF7E2CC),
    secondary = Color(0xFFD5BCA1),
    onSecondary = Color(0xFF39291A),
    secondaryContainer = Color(0xFF4C3B2A),
    onSecondaryContainer = Color(0xFFF2E1CF),
    tertiary = Color(0xFFBFC8A8),
    onTertiary = Color(0xFF2A3119),
    background = Color(0xFF17120E),
    onBackground = Color(0xFFEDE3D7),
    surface = Color(0xFF201A15),
    onSurface = Color(0xFFEDE3D7),
    surfaceVariant = Color(0xFF4E4237),
    onSurfaceVariant = Color(0xFFD3C5B4),
    surfaceTint = Color(0xFFE0BE9C),
    surfaceBright = Color(0xFF3D342C),
    surfaceDim = Color(0xFF17120E),
    surfaceContainerLowest = Color(0xFF120E0A),
    surfaceContainerLow = Color(0xFF201A15),
    surfaceContainer = Color(0xFF251E18),
    surfaceContainerHigh = Color(0xFF302822),
    surfaceContainerHighest = Color(0xFF3B322B),
    inverseSurface = Color(0xFFEDE3D7),
    inverseOnSurface = Color(0xFF33291F),
    outline = Color(0xFF9C8E7D),
    outlineVariant = Color(0xFF4E4237),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Verdict badge colours, one pair per level.
 *
 * Deliberately outside the Material colour scheme: these do not mean "primary" or
 * "error", they mean sound, fair and serious concerns, and a reader has to be able to
 * tell them apart at a glance in daylight without reading the label. Only three, and
 * never used for anything else, so the association stays learnable.
 */
data class VerdictColors(val container: Color, val onContainer: Color)

object VerdictPalette {
    val sound = VerdictColors(Color(0xFFD6E8CE), Color(0xFF1F3D14))
    val fair = VerdictColors(Color(0xFFF7E3B8), Color(0xFF4A3305))
    val serious = VerdictColors(Color(0xFFF6D6D2), Color(0xFF5B1410))
    val unknown = VerdictColors(Color(0xFFE4DACD), Color(0xFF4E4237))

    val soundDark = VerdictColors(Color(0xFF2E4222), Color(0xFFD6E8CE))
    val fairDark = VerdictColors(Color(0xFF4B3A14), Color(0xFFF7E3B8))
    val seriousDark = VerdictColors(Color(0xFF5A2320), Color(0xFFF6D6D2))
    val unknownDark = VerdictColors(Color(0xFF3B322B), Color(0xFFD3C5B4))
}

@Composable
fun verdictColors(level: VerdictLevel): VerdictColors {
    val dark = isSystemInDarkTheme()
    return when (level) {
        VerdictLevel.SOUND -> if (dark) VerdictPalette.soundDark else VerdictPalette.sound
        VerdictLevel.FAIR -> if (dark) VerdictPalette.fairDark else VerdictPalette.fair
        VerdictLevel.SERIOUS -> if (dark) VerdictPalette.seriousDark else VerdictPalette.serious
        VerdictLevel.UNKNOWN -> if (dark) VerdictPalette.unknownDark else VerdictPalette.unknown
    }
}

/**
 * Type is a step larger than Material defaults throughout. The app is used outdoors,
 * on small phones, by people with varying literacy — legibility beats density.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        // The wordmark. Weight and letterspacing rather than a bundled typeface: the
        // mockup's Bricolage and IBM Plex pairing would cost either a font download at
        // first launch or a few hundred KB of APK, and buys nothing usable in a shed.
        displaySmall = base.displaySmall.copy(
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
        ),
    )
}

@Composable
fun QualityVerifierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

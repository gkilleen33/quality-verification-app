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

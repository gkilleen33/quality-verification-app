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

private val LightColors = lightColorScheme(
    primary = Timber,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9D8C6),
    onPrimaryContainer = Color(0xFF2A1A0C),
    secondary = TimberLight,
    onSecondary = Color.White,
    background = Sawdust,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE3D7),
    onSurfaceVariant = Color(0xFF4E4237),
    error = Warning,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0BE9C),
    onPrimary = Color(0xFF3B2612),
    background = Color(0xFF17120E),
    onBackground = Color(0xFFEDE3D7),
    surface = Color(0xFF201A15),
    onSurface = Color(0xFFEDE3D7),
    error = Color(0xFFF2B8B5),
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

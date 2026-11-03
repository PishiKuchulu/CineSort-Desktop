package com.cinesort.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Atelier token set, identical values to the Android app's
 * designsystem (Direction 2: one product identity across phone and
 * desktop). Warm paper light theme, deep ink dark theme, amber
 * primary, blue series accent.
 */

val CineSuccess = Color(0xFF47C98F)
val CineNeedsReview = Color(0xFFFFC53D)
val CineError = Color(0xFFFF6B6B)
val CineSeriesAccentDark = Color(0xFF6C9BFF)
val CineSeriesAccentLight = Color(0xFF2F6FED)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF2A1503),
    primaryContainer = Color(0xFF4A2A12),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFB3ADA2),
    onSecondary = Color(0xFF1A1816),
    secondaryContainer = Color(0xFF24211E),
    onSecondaryContainer = Color(0xFFF5F1E8),
    background = Color(0xFF0F0E0D),
    onBackground = Color(0xFFF5F1E8),
    surface = Color(0xFF1A1816),
    onSurface = Color(0xFFF5F1E8),
    surfaceVariant = Color(0xFF24211E),
    onSurfaceVariant = Color(0xFFB3ADA2),
    surfaceContainer = Color(0xFF24211E),
    outline = Color(0xFF34302B),
    outlineVariant = Color(0xFF2A2723),
    error = CineError,
    onError = Color(0xFF3D0A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFAC4500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3D3),
    onPrimaryContainer = Color(0xFF4A1E02),
    secondary = Color(0xFF5C5D66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECE8DF),
    onSecondaryContainer = Color(0xFF17181C),
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF17181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17181C),
    surfaceVariant = Color(0xFFECE8DF),
    onSurfaceVariant = Color(0xFF5C5D66),
    surfaceContainer = Color(0xFFECE8DF),
    outline = Color(0xFF8A8171),
    outlineVariant = Color(0xFFE0DACB),
    error = Color(0xFFBA1A1A),
)

@Composable
fun CineSortTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

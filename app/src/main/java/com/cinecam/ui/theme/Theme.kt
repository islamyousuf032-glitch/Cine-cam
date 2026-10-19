package com.cinecam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// CineCam Professional Color Palette - Dark cinema grading
private val CineBlack = Color(0xFF0A0A0A)
private val CineSurface = Color(0xFF1C1C1E)
private val CineRed = Color(0xFFE50914)
private val CineAccent = Color(0xFF00E5FF)
private val CineOnSurface = Color(0xFFF2F2F2)
private val CineOnSurfaceVariant = Color(0xFF9A9A9E)

private val DarkColorScheme = darkColorScheme(
    primary = CineRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF37000B),
    secondary = CineAccent,
    onSecondary = CineBlack,
    background = CineBlack,
    onBackground = CineOnSurface,
    surface = CineSurface,
    onSurface = CineOnSurface,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = CineOnSurfaceVariant,
    error = CineRed,
    outline = Color(0xFF3A3A3C)
)

@Composable
fun CineCamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}

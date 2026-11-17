package com.emofah.agencies.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = EmofahRoyal,
    onPrimary = Color.White,
    primaryContainer = EmofahCyan.copy(alpha = 0.18f),
    onPrimaryContainer = EmofahNavy,
    secondary = EmofahAzure,
    onSecondary = Color.White,
    background = EmofahLightBg,
    onBackground = EmofahLightText,
    surface = EmofahLightSurface,
    onSurface = EmofahLightText,
    surfaceVariant = Color(0xFFEBF2F7),
    onSurfaceVariant = EmofahLightSubtle,
)

private val DarkColors = darkColorScheme(
    primary = EmofahCyan,
    onPrimary = EmofahDarkBg,
    primaryContainer = EmofahRoyal.copy(alpha = 0.35f),
    onPrimaryContainer = EmofahDarkText,
    secondary = EmofahSteel,
    onSecondary = EmofahDarkBg,
    background = EmofahDarkBg,
    onBackground = EmofahDarkText,
    surface = EmofahDarkSurface,
    onSurface = EmofahDarkText,
    surfaceVariant = Color(0xFF132945),
    onSurfaceVariant = EmofahDarkSubtle,
)

@Composable
fun EmofahTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

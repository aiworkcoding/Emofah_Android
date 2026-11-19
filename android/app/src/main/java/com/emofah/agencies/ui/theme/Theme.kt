package com.emofah.agencies.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class EmofahExtendedColors(
    val isDark: Boolean,
    val canvas: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val surfaceMuted: Color,
    val glassSurface: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
)

private val LightExtendedColors = EmofahExtendedColors(
    isDark = false,
    canvas = EmofahLightBg,
    surfaceCard = EmofahLightSurface,
    surfaceElevated = EmofahLightSurfaceElevated,
    surfaceMuted = EmofahLightSurfaceMuted,
    glassSurface = EmofahLightGlass,
    borderSubtle = EmofahLightBorder,
    borderStrong = EmofahLightBorderStrong,
    textPrimary = EmofahLightText,
    textMuted = EmofahLightSubtle,
    success = EmofahSuccessLight,
    successContainer = EmofahSuccessContainerLight,
    warning = EmofahWarningLight,
    warningContainer = EmofahWarningContainerLight,
    danger = EmofahDangerLight,
    dangerContainer = EmofahDangerContainerLight,
    info = EmofahInfoLight,
    infoContainer = EmofahInfoContainerLight,
)

private val DarkExtendedColors = EmofahExtendedColors(
    isDark = true,
    canvas = EmofahDarkBg,
    surfaceCard = EmofahDarkSurface,
    surfaceElevated = EmofahDarkSurfaceElevated,
    surfaceMuted = EmofahDarkSurfaceMuted,
    glassSurface = EmofahDarkGlass,
    borderSubtle = EmofahDarkBorder,
    borderStrong = EmofahDarkBorderStrong,
    textPrimary = EmofahDarkText,
    textMuted = EmofahDarkSubtle,
    success = EmofahSuccessDark,
    successContainer = EmofahSuccessContainerDark,
    warning = EmofahWarningDark,
    warningContainer = EmofahWarningContainerDark,
    danger = EmofahDangerDark,
    dangerContainer = EmofahDangerContainerDark,
    info = EmofahInfoDark,
    infoContainer = EmofahInfoContainerDark,
)

val LocalEmofahColors = staticCompositionLocalOf { LightExtendedColors }

val MaterialTheme.emofahColors: EmofahExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEmofahColors.current

private val LightColors = lightColorScheme(
    primary = EmofahRoyal,
    onPrimary = Color.White,
    primaryContainer = EmofahInfoContainerLight,
    onPrimaryContainer = EmofahNavy,
    secondary = EmofahNavy,
    onSecondary = Color.White,
    secondaryContainer = EmofahLightSurfaceMuted,
    onSecondaryContainer = EmofahLightText,
    tertiary = EmofahSuccessLight,
    onTertiary = Color.White,
    tertiaryContainer = EmofahWarningContainerLight,
    onTertiaryContainer = EmofahWarningLight,
    background = EmofahLightBg,
    onBackground = EmofahLightText,
    surface = EmofahLightSurface,
    onSurface = EmofahLightText,
    surfaceVariant = EmofahLightSurfaceMuted,
    onSurfaceVariant = EmofahLightSubtle,
    outline = EmofahLightBorderStrong,
    outlineVariant = EmofahLightBorder,
    error = EmofahDangerLight,
    onError = Color.White,
    errorContainer = EmofahDangerContainerLight,
    onErrorContainer = EmofahDangerLight,
)

private val DarkColors = darkColorScheme(
    primary = EmofahRoyalDark,
    onPrimary = EmofahDarkBg,
    primaryContainer = EmofahInfoContainerDark,
    onPrimaryContainer = EmofahDarkText,
    secondary = EmofahCyan,
    onSecondary = EmofahDarkBg,
    secondaryContainer = EmofahDarkSurfaceElevated,
    onSecondaryContainer = EmofahDarkText,
    tertiary = EmofahSuccessDark,
    onTertiary = EmofahDarkBg,
    tertiaryContainer = EmofahWarningContainerDark,
    onTertiaryContainer = EmofahWarningDark,
    background = EmofahDarkBg,
    onBackground = EmofahDarkText,
    surface = EmofahDarkSurface,
    onSurface = EmofahDarkText,
    surfaceVariant = EmofahDarkSurfaceMuted,
    onSurfaceVariant = EmofahDarkSubtle,
    outline = EmofahDarkBorderStrong,
    outlineVariant = EmofahDarkBorder,
    error = EmofahDangerDark,
    onError = EmofahDarkBg,
    errorContainer = EmofahDangerContainerDark,
    onErrorContainer = EmofahDangerDark,
)

@Composable
fun EmofahTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalEmofahColors provides extendedColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = EmofahTypography,
            shapes = EmofahShapes,
            content = content,
        )
    }
}

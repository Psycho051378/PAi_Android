package com.pai.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.pai.android.data.model.ThemeMode

/**
 * Modern light color scheme with elegant blue/gray palette.
 */
private val ModernLightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    secondary = SecondaryLight,
    tertiary = TertiaryLight,
    surface = SurfaceLight,
    background = BackgroundLight,
    onPrimary = OnPrimaryLight,
    onSurface = OnSurfaceLight,
    error = ErrorLight
)

/**
 * Modern dark color scheme with sophisticated dark blue palette.
 */
private val ModernDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    tertiary = TertiaryDark,
    surface = SurfaceDark,
    background = BackgroundDark,
    onPrimary = OnPrimaryDark,
    onSurface = OnSurfaceDark,
    error = ErrorDark
)

/**
 * Legacy color schemes for compatibility.
 */
private val LegacyLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

private val LegacyDarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

/**
 * Determines if dark theme should be used based on ThemeMode.
 */
@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean {
    return when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/**
 * The main theme composable for the Pai Android application.
 * Supports three theme modes: SYSTEM, LIGHT, and DARK.
 *
 * @param themeMode The current theme mode (defaults to SYSTEM).
 * @param useModernColors Whether to use modern color scheme (defaults to true).
 * @param content The content to display with the applied theme.
 */
@Composable
fun PaiAndroidTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useModernColors: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = shouldUseDarkTheme(themeMode)
    
    val colorScheme = if (useModernColors) {
        if (darkTheme) ModernDarkColorScheme else ModernLightColorScheme
    } else {
        if (darkTheme) LegacyDarkColorScheme else LegacyLightColorScheme
    }
    
    // Create remembered theme settings for performance
    val themeSettings = remember {
        mapOf(
            "darkTheme" to darkTheme,
            "themeMode" to themeMode,
            "useModernColors" to useModernColors
        )
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/**
 * Extension to get current theme colors easily.
 */
val MaterialTheme.colorPalette
    @Composable get() = colorScheme
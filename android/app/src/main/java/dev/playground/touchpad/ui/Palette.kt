package dev.playground.touchpad.ui

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
internal data class Colors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfacePressed: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    /** Text and icons drawn on [accent]. */
    val onAccent: Color,
    val warning: Color,
    val danger: Color,
)

internal val DarkColors = Colors(
    isDark = true,
    background = Color(0xFF0E1116),
    surface = Color(0xFF1A1F27),
    surfacePressed = Color(0xFF2C3441),
    textPrimary = Color(0xFFE6E9EE),
    textMuted = Color(0xFF8A93A1),
    accent = Color(0xFF3DD68C),
    onAccent = Color(0xFF0E1116),
    warning = Color(0xFFF2B84B),
    danger = Color(0xFFF2685B),
)

internal val LightColors = Colors(
    isDark = false,
    background = Color(0xFFEEF1F5),
    surface = Color(0xFFFFFFFF),
    surfacePressed = Color(0xFFD9DFE7),
    textPrimary = Color(0xFF12161C),
    textMuted = Color(0xFF5B6472),
    accent = Color(0xFF117A4A),
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFC98A0B),
    danger = Color(0xFFD23C30),
)

private val LocalColors = staticCompositionLocalOf { DarkColors }

/** The current theme's colors; read inside composables. */
internal object Palette {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalColors.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalColors.current.surface
    val SurfacePressed: Color @Composable @ReadOnlyComposable get() = LocalColors.current.surfacePressed
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalColors.current.textPrimary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalColors.current.textMuted
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalColors.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalColors.current.onAccent
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalColors.current.warning
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalColors.current.danger
}

/** Applies [colors] to this app's screens and to Material components (dialogs, sliders, switches). */
@Composable
internal fun TouchpadTheme(colors: Colors, content: @Composable () -> Unit) {
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.surface,
            surfaceContainerHigh = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textMuted,
            surfaceVariant = colors.surfacePressed,
            outline = colors.textMuted,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.surface,
            surfaceContainerHigh = colors.surface,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textMuted,
            surfaceVariant = colors.surfacePressed,
            outline = colors.textMuted,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

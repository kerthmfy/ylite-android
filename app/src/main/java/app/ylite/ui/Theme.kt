package app.ylite.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A small, deliberate palette. Two neutrals, one hairline, one accent.
 * Everything on screen is one of these — that is what keeps it from looking
 * like a pile of default components.
 */
data class YColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val warn: Color,
)

val DarkYLiteColors = YColors(
    background = Color(0xFF0B0D0F),
    surface = Color(0xFF121619),
    surfaceRaised = Color(0xFF1A1F24),
    line = Color(0xFF252B32),
    text = Color(0xFFEDF0F2),
    muted = Color(0xFF8B959E),
    accent = Color(0xFFFF5B3D),
    onAccent = Color(0xFF1A0703),
    good = Color(0xFF4CC38A),
    warn = Color(0xFFE0B15C),
)

val LightYLiteColors = YColors(
    background = Color(0xFFF5F6F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF0F2F4),
    line = Color(0xFFDDE2E6),
    text = Color(0xFF0F1317),
    muted = Color(0xFF5D6770),
    accent = Color(0xFFC63A1C),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF1E8E5A),
    warn = Color(0xFF9A6B12),
)

val LocalYColors = staticCompositionLocalOf { DarkYLiteColors }

/** 10sp, spaced caps. Used for every label: units, section names, buttons. */
val MicroLabel = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.4.sp,
)

/** Monospace body, so changing values do not make the layout twitch. */
val MonoValue = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

val MonoCode = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 16.sp,
)

/** The one big number on the ride screen. */
val DisplayNumber = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Light,
    fontSize = 68.sp,
    lineHeight = 70.sp,
    letterSpacing = (-3).sp,
)

@Composable
fun YLiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkYLiteColors else LightYLiteColors
    CompositionLocalProvider(LocalYColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(colors) else lightScheme(colors),
            content = content,
        )
    }
}

private fun darkScheme(c: YColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    background = c.background,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceRaised,
    onSurfaceVariant = c.muted,
    outline = c.line,
    outlineVariant = c.line,
    error = Color(0xFFFF6B6B),
)

private fun lightScheme(c: YColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    background = c.background,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceRaised,
    onSurfaceVariant = c.muted,
    outline = c.line,
    outlineVariant = c.line,
    error = Color(0xFFB3261E),
)

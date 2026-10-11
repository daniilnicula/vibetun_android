package com.goresense.gorebox.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Palette copied from GoreBox Windows' Dark.xaml / Light.xaml. */
data class GorePalette(
    val window: Color,
    val panel: Color,
    val card: Color,
    val cardAlt: Color,
    val input: Color,
    val border: Color,
    val divider: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
)

private val DarkGorePalette = GorePalette(
    window = Color(0xFF191B1D),
    panel = Color(0xFF1F2226),
    card = Color(0xFF242830),
    cardAlt = Color(0xFF2A2F38),
    input = Color(0xFF20242A),
    border = Color(0xFF2C3138),
    divider = Color(0xFF272C33),
    text = Color(0xFFE9EBEE),
    textSecondary = Color(0xFFA7AEB8),
    textMuted = Color(0xFF6C7480),
    accent = Color(0xFF5C96FF),
    accentSoft = Color(0xFF2A3A58),
    success = Color(0xFF3BC08A),
    successSoft = Color(0xFF1E3A30),
    warning = Color(0xFFE5B24B),
    warningSoft = Color(0xFF3B3320),
    danger = Color(0xFFE5646B),
    dangerSoft = Color(0xFF3E2429),
)

private val LightGorePalette = GorePalette(
    window = Color(0xFFF4F5F7),
    panel = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFF7F8FA),
    input = Color(0xFFFFFFFF),
    border = Color(0xFFE4E7EC),
    divider = Color(0xFFECEEF1),
    text = Color(0xFF1D2024),
    textSecondary = Color(0xFF5C6570),
    textMuted = Color(0xFF8B929C),
    accent = Color(0xFF2E6EE0),
    accentSoft = Color(0xFFE3ECFB),
    success = Color(0xFF1F9D6B),
    successSoft = Color(0xFFE1F5EC),
    warning = Color(0xFFB57C14),
    warningSoft = Color(0xFFFBF0D8),
    danger = Color(0xFFD14343),
    dangerSoft = Color(0xFFFBE7E7),
)

val LocalGorePalette = staticCompositionLocalOf { DarkGorePalette }

@Composable
fun GoreBoxTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkGorePalette else LightGorePalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.window,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardAlt,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.window,
            surface = palette.card,
            onSurface = palette.text,
            surfaceVariant = palette.cardAlt,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalGorePalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

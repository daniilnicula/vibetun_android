package com.vibetun.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Palette copied from VibeTun Windows' Dark.xaml / Light.xaml. */
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

    /** Цвет текста/иконок поверх акцента: у светлого акцента — тёмный, у тёмного — белый. */
    val onAccent: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
)

private val DarkGorePalette = GorePalette(
    window = Color(0xFF141518),
    panel = Color(0xFF191B1E),
    card = Color(0xFF1E2024),
    cardAlt = Color(0xFF25282E),
    input = Color(0xFF1B1D21),
    border = Color(0xFF2A2E35),
    divider = Color(0xFF24272D),
    text = Color(0xFFE9EBEE),
    textSecondary = Color(0xFFA7AEB8),
    textMuted = Color(0xFF6C7480),
    // Акцент по умолчанию — розовый #FF758AAC.
    accent = Color(0xFFFF758AAC),
    accentSoft = Color(0x2AFF758AAC),
    onAccent = Color(0xFFFFFFFF),
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
    cardAlt = Color(0xFFF1F2F5),
    input = Color(0xFFFFFFFF),
    border = Color(0xFFE0E3E8),
    divider = Color(0xFFECEEF1),
    text = Color(0xFF1D2024),
    textSecondary = Color(0xFF5C6570),
    textMuted = Color(0xFF8B929C),
    accent = Color(0xFFFF758AAC),
    accentSoft = Color(0x33FF758AAC),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1F9D6B),
    successSoft = Color(0xFFE1F5EC),
    warning = Color(0xFFB57C14),
    warningSoft = Color(0xFFFBF0D8),
    danger = Color(0xFFD14343),
    dangerSoft = Color(0xFFFBE7E7),
)

val LocalGorePalette = staticCompositionLocalOf { DarkGorePalette }

@Composable
fun VibeTunTheme(darkTheme: Boolean, accentColorArgb: Int = 0, content: @Composable () -> Unit) {
    val base = if (darkTheme) DarkGorePalette else LightGorePalette
    // Пользовательский акцент (если задан) заменяет акцент темы и его мягкую подложку;
    // хранится в настройках и переживает перезапуск приложения.
    val palette = if (accentColorArgb != 0) {
        val accent = Color(accentColorArgb)
        base.copy(
            accent = accent,
            accentSoft = accent.copy(alpha = 0.16f),
            onAccent = onAccentFor(accent),
        )
    } else {
        base
    }
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
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
            onPrimary = palette.onAccent,
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

/**
 * Цвет текста поверх акцента: на светлом акценте (песок, золото) — тёмный,
 * на тёмном (розовый, синий, фиолетовый) — белый.
 */
private fun onAccentFor(accent: Color): Color =
    if (accent.luminance() > 0.45f) Color(0xFF1A1712) else Color.White

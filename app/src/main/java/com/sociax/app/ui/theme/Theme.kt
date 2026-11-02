package com.sociax.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =================================================================================================================
// Sociax design system v2 (Step 59): clean, light-first, purple accent, white cards with a thin edge, round avatars.
// EVERY screen reads its colours from the tokens below, so one switch (applyPalette) turns the whole app light or dark.
// The tokens are state values: a screen that reads one redraws by itself when the person changes the theme or the accent.
// =================================================================================================================

/** Solid dark colours kept for full-screen media (Story viewer, Short Video backdrop). */
val Navy = Color(0xFF0B1220)
val Surface = Color(0xFF121C2E)

/** Accent colour (Settings > Appearance). The first choice is the Sociax purple of the new design. */
var Accent by mutableStateOf(Color(0xFF6C4DF0))

/** The accent colours offered in Settings. The first one is the Sociax purple. */
val AccentChoices: List<Long> = listOf(0xFF6C4DF0, 0xFF2F80ED, 0xFF12B886, 0xFFEC4899, 0xFFF59E0B)

/** Text / icons that sit ON the accent colour (filled buttons, unread badges, the send button). */
val OnAccent = Color.White

/** A darker and a lighter shade of the accent, for the soft gradient of filled buttons and rings. */
val AccentDeep: Color get() = lerp(Accent, Color.Black, 0.22f)
val AccentLight: Color get() = lerp(Accent, Color.White, 0.35f)

/** The soft gradient of every filled button (Share Profile, Send, Follow ...). */
val AccentBrush: Brush get() = Brush.linearGradient(listOf(Accent, AccentDeep))

val LikeRed = Color(0xFFFF4D6A)
val OnlineGreen = Color(0xFF22C55E)

// ---- Light / dark palette ------------------------------------------------------------------------------------
private var darkPalette by mutableStateOf(false)

/** Main text and icon colour. Greys are made with Ink.copy(alpha): .7 for secondary text, .5 for hints. */
var Ink by mutableStateOf(Color(0xFF15142B))
/** The page behind everything. */
var PageBg by mutableStateOf(Color.White)
/** White cards, the bottom bar, dialogs. */
var CardFill by mutableStateOf(Color.White)
/** Search boxes, text fields, round icon buttons, message bubbles of other people. */
var FieldFill by mutableStateOf(Color(0xFFF3F2FA))
/** The thin edge of cards, fields and dividers. */
var Hairline by mutableStateOf(Color(0xFFE8E6F2))

/** Pale tint of the accent (selected chip, my message bubble in AI chat, "Follow" chip). */
val SoftAccent: Color get() = Accent.copy(alpha = if (darkPalette) 0.24f else 0.10f)

/** Error and notice texts that stay readable on a white page and on a dark one. */
val ErrorInk: Color get() = if (darkPalette) Color(0xFFFF8A8A) else Color(0xFFD92D20)
val NoticeInk: Color get() = if (darkPalette) Color(0xFFFFE08A) else Color(0xFFB45309)

/** Switches every token to the light or the dark version. Safe to call again and again (an unchanged value changes nothing). */
fun applyPalette(dark: Boolean) {
    darkPalette = dark
    if (dark) {
        Ink = Color(0xFFF3F2FB); PageBg = Color(0xFF0F0E1A); CardFill = Color(0xFF191828)
        FieldFill = Color(0xFF232137); Hairline = Color(0xFF2F2D47)
    } else {
        Ink = Color(0xFF15142B); PageBg = Color.White; CardFill = Color.White
        FieldFill = Color(0xFFF3F2FA); Hairline = Color(0xFFE8E6F2)
    }
}

// ---- Older names, kept so every old call site now gets the new look ---------------------------------------------
val GlassFill: Color get() = CardFill
val GlassBorder: Color get() = Hairline
val ControlFill: Color get() = FieldFill
val ControlBorder: Color get() = Hairline
val InsetFill: Color get() = FieldFill
val InsetBorder: Color get() = Hairline
val BarFill: Color get() = CardFill

/** Round buttons and chips that sit ON a photo or a video: a dark glass with white content, readable on anything. */
val MediaGlass = Color(0x66000000)
val MediaGlassBorder = Color(0x33FFFFFF)

/** Full-screen media backdrops (Short Video while loading, ad pages, text stories). Always dark, white text on top. */
val MediaBackdrop = Brush.verticalGradient(listOf(Color(0xFF1B1636), Color(0xFF07060F)))
val StoryBackground = Brush.verticalGradient(listOf(Color(0xFF7B5CF5), Color(0xFF2B1B7A)))

/** Height of the docked bottom tab bar (without the phone's own navigation-bar inset). */
val BottomBarHeight = 64.dp

/** Space a scrolling page must leave at the bottom so its last item is not hidden by the docked tab bar. */
@Composable
fun bottomBarClearance(): Dp = BottomBarHeight + 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private fun darkColors() = darkColorScheme(
    primary = Accent, onPrimary = Color.White,
    secondary = Accent, onSecondary = Color.White,
    background = PageBg, onBackground = Ink,
    surface = CardFill, onSurface = Ink,
    surfaceVariant = FieldFill, onSurfaceVariant = Ink.copy(alpha = 0.7f),
    outline = Hairline
)

private fun lightColors() = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    secondary = Accent, onSecondary = Color.White,
    background = PageBg, onBackground = Ink,
    surface = CardFill, onSurface = Ink,
    surfaceVariant = FieldFill, onSurfaceVariant = Ink.copy(alpha = 0.7f),
    outline = Hairline
)

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** True when the app should be drawn in dark mode for this setting (SYSTEM follows the phone). */
@Composable
fun isDarkMode(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** The page colour behind a whole screen (the old cinematic gradient is gone: the new design is a clean flat page). */
@Composable
fun SociaxBackground(dark: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(PageBg), content = content)
}

enum class TextSize(val factor: Float) { SMALL(0.9f), NORMAL(1f), LARGE(1.15f) }

/** True when the user chose Reduce Motion (Settings > Appearance). */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun SociaxTheme(
    mode: ThemeMode = ThemeMode.LIGHT,
    textScale: Float = 1f,
    lang: Lang = Lang.EN,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * textScale),
        LocalLang provides lang,
        LocalReduceMotion provides reduceMotion,
        LocalContentColor provides Ink
    ) {
        MaterialTheme(colorScheme = if (isDarkMode(mode)) darkColors() else lightColors(), content = content)
    }
}

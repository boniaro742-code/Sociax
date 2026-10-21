package com.sociax.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Design tokens from the Master Plan
val Navy = Color(0xFF0B1220)
val Surface = Color(0xFF121C2E)
/** Accent colour (Settings > Appearance). A state value, so every screen using it redraws when the user picks another one. */
var Teal by mutableStateOf(Color(0xFF2FE0B2))

/** The accent colours offered in Settings. The first one is the original Sociax teal. */
val AccentChoices: List<Long> = listOf(0xFF2FE0B2, 0xFF5AC8FF, 0xFFB79CFF, 0xFFFF7AB6, 0xFFFFB03A)
val BlueDeep = Color(0xFF1B3A8A)
val BlueMid = Color(0xFF3B6FD8)
val BlueGlow = Color(0xFF7FB2FF)
/** Soft glass for big panels and list rows (settings, chat rows, cards). */
val GlassFill = Color(0x33FFFFFF)
val GlassBorder = Color(0x44FFFFFF)
val LikeRed = Color(0xFFFF5A36)

// ---- Measured from the reference image (Sociax_reference.jpg) --------------------------------------------------
/**
 * The frosted blue-grey glass of every round button, reaction chip and the bottom bar in the reference
 * (about 45% of a pale blue-grey): it reads the same over the blue background and over dark photos.
 */
val ControlFill = Color(0x73BEC4D4)
/** Border of the frosted controls: a thin, light edge. */
val ControlBorder = Color(0x40FFFFFF)
/** Darker "inset" glass of the For you / Following switch, the search pill and the message bars. */
val InsetFill = Color(0xBF050A19)
val InsetBorder = Color(0x26FFFFFF)
/** Soft white of the selected switch / button (not pure paper-white, like the reference). */
val PaperWhite = Color(0xFFF4F6FA)

/** Kept for older call sites: chips and buttons that sit on photos / videos use the same frosted glass. */
val MediaGlassFill = ControlFill
/** Glass of the bottom tab bar. */
val BarFill = ControlFill
/** Inactive tab circle inside the bottom bar (a little lighter than the bar itself). */
val BarItemFill = Color(0x33FFFFFF)

// Dark mode (default, matches the reference): bright cobalt at the top, a long calm blue middle, then a quick fall into deep navy.
val CinematicBackground = Brush.verticalGradient(
    0.00f to Color(0xFF2B56B6),
    0.25f to Color(0xFF25469A),
    0.55f to Color(0xFF213F85),
    0.78f to Color(0xFF162A62),
    0.92f to Color(0xFF0C1735),
    1.00f to Navy
)

// Light mode: the same cinematic blue but lighter and brighter. Text stays white, so it is kept deep enough to read.
val CinematicBackgroundLight = Brush.verticalGradient(
    listOf(Color(0xFF4A82EA), Color(0xFF3A68CF), Color(0xFF2D52AC), Color(0xFF223F8A))
)

/** Space a scrolling page must leave at the bottom so its last item is not hidden by the floating tab bar. */
@Composable
fun bottomBarClearance(): Dp = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private fun darkColors() = darkColorScheme(
    primary = Teal, onPrimary = Navy,
    secondary = BlueGlow, background = Navy, onBackground = Color.White,
    surface = Surface, onSurface = Color.White
)

private fun lightColors() = lightColorScheme(
    primary = BlueMid, onPrimary = Color.White,
    secondary = Teal, background = Color(0xFFEAF1FF), onBackground = Navy,
    surface = Color.White, onSurface = Navy
)

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** True when the app should be drawn in dark mode for this setting (SYSTEM follows the phone). */
@Composable
fun isDarkMode(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Full-screen cinematic gradient + a soft light glow in the top-left corner, as in the reference screens. */
@Composable
fun SociaxBackground(dark: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .background(if (dark) CinematicBackground else CinematicBackgroundLight)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(if (dark) 0x3D7FB2FF else 0x40FFFFFF), Color.Transparent),
                        center = Offset(size.width * 0.12f, 0f),
                        radius = size.maxDimension * 0.75f
                    )
                )
            },
        content = content
    )
}

enum class TextSize(val factor: Float) { SMALL(0.9f), NORMAL(1f), LARGE(1.15f) }

/** True when the user chose Reduce Motion (Settings > Appearance). */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun SociaxTheme(
    mode: ThemeMode = ThemeMode.DARK,
    textScale: Float = 1f,
    lang: Lang = Lang.EN,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * textScale),
        LocalLang provides lang,
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(colorScheme = if (isDarkMode(mode)) darkColors() else lightColors(), content = content)
    }
}

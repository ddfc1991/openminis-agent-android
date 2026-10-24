package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFF528AD2)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFB2DFDB)
private val TealOnPrimaryContainer = Color(0xFF00332F)
private val TealSecondary = Color(0xFF4A6360)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFCCE8E4)
private val TealOnSecondaryContainer = Color(0xFF05201D)
private val TealTertiary = Color(0xFF46617A)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFCDE5FF)
private val TealOnTertiaryContainer = Color(0xFF001D32)
private val TealBackground = Color(0xFFF5FAFA)
private val TealOnBackground = Color(0xFF171D1C)
private val TealSurface = Color(0xFFF5FAFA)
private val TealOnSurface = Color(0xFF171D1C)
private val TealSurfaceVariant = Color(0xFFDAE5E2)
private val TealOnSurfaceVariant = Color(0xFF3F4947)
private val TealOutline = Color(0xFF6F7977)

private val TealDarkPrimary = Color(0xFF6A94CE)
private val TealDarkOnPrimary = Color(0xFF003737)
private val TealDarkPrimaryContainer = Color(0xFF1A6B6B)
private val TealDarkOnPrimaryContainer = Color(0xFFB2DFDB)
private val TealDarkSecondary = Color(0xFFB1CCC8)
private val TealDarkOnSecondary = Color(0xFF1C3532)
private val TealDarkSecondaryContainer = Color(0xFF334B48)
private val TealDarkOnSecondaryContainer = Color(0xFFCCE8E4)
private val TealDarkBackground = Color(0xFF0E1514)
private val TealDarkOnBackground = Color(0xFFDEE4E2)
private val TealDarkSurface = Color(0xFF0E1514)
private val TealDarkOnSurface = Color(0xFFDEE4E2)
private val TealDarkSurfaceVariant = Color(0xFF3F4947)
private val TealDarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val TealDarkOutline = Color(0xFF899390)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFF2F2F7)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFF7F7FA)
private val NeutralOutline = Color(0xFFD1D1D6)

private val NeutralDarkGroupedBg = Color(0xFF000000)
private val NeutralDarkGroupedCard = Color(0xFF1C1C1E)
private val NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)
private val NeutralDarkOutline = Color(0xFF38383A)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(14.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar  [Mines-38.1] radii 20 / 14
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    /** [Mines-38.7] 0 = day … 1 = night while the sunrise/sunset mode animates; null = plain light / dark */
    nightFraction: Float? = null,
    content: @Composable () -> Unit,
) {
    // [Mines-design-34] 「暖瓷 · 毛玻璃」 palette; the accent follows the desk-pet character.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    com.openminis.app.plus.PlusPrefs.init(ctx)
    val plusSt = com.openminis.app.plus.PlusPrefs.state.collectAsState().value
    // [Mines-38.1] single accent: terracotta #B5532F everywhere, whatever character is chosen (user 16:03)
    @Suppress("UNUSED_VARIABLE") val style = plusSt.petStyle
    // [Mines-38.9] 外观调节: loaded once; reading MinesLook.look here recomposes the theme when a slider moves
    androidx.compose.runtime.remember { com.openminis.app.ui.plus.MinesLook.init(ctx); 0 }
    @Suppress("UNUSED_VARIABLE") val lookNow = com.openminis.app.ui.plus.MinesLook.look
    val mc = if (nightFraction != null && nightFraction > 0f && nightFraction < 1f)
        com.openminis.app.ui.plus.lerpMines(com.openminis.app.ui.plus.minesColors("claude", false), com.openminis.app.ui.plus.minesColors("claude", true), nightFraction)
    else com.openminis.app.ui.plus.minesColors("claude", if (nightFraction != null) nightFraction >= 0.5f else darkTheme)
    val colorScheme = porcelainScheme(if (darkTheme) DarkColorScheme else LightColorScheme, mc)
    // [Mines-38.9.5 i] user 2026-10-17 「不要大字」 (38.9.4 on the phone: text too big, loose letter-spacing): the in-app
    // scale never goes above 1.0 and the system 「大字体」 setting is ignored for layout (fontScale clamped to ≤ 1.0).
    val typography = scaledTypography(fontScale.coerceAtMost(1f))
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette
    // [Mines-38.8 v3] the window behind Compose is the Mines backdrop colour, not the theme's plain white/black: a frame
    // the window shows before Compose has drawn (first WebView draw on a slow GPU) is never a white flash.
    val winBg = mc.bg.toArgb()
    androidx.compose.runtime.DisposableEffect(winBg) {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(winBg))
        onDispose { }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        val dens = androidx.compose.ui.platform.LocalDensity.current
        // [Mines-38.9.7] user 21:15 「不要大字」 (realme 480 dpi): the design is drawn at ~427 dp across (405 dpi); a phone at
        // 480 dpi made every row ~18 % bigger. Keep ≥ 400 dp across the short edge (HomeFixCore.density); never enlarge.
        val conf = androidx.compose.ui.platform.LocalConfiguration.current
        val shortPx = (minOf(conf.screenWidthDp, conf.screenHeightDp) * dens.density).toInt()
        val d2 = com.openminis.app.plus.HomeFixCore.density(dens.density, shortPx)
        MinesDensity.applied = d2; MinesDensity.system = dens.density
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d2, dens.fontScale.coerceAtMost(1f)),
            LocalChatPalette provides chatPalette,
            com.openminis.app.ui.plus.LocalMinesColors provides mc,
            content = content,
        )
    }
}

/** [Mines-design-34] Material scheme from the porcelain tokens + character accent. */
private fun porcelainScheme(base: androidx.compose.material3.ColorScheme, m: com.openminis.app.ui.plus.MinesColors) = base.copy(
    // [Mines-warm-36] Material text buttons/links in dark mode keep the light accent (≥4.5:1 on dark);
    // Mines' own primary pills use mines().primary (#B5532F + white) in both modes.
    primary = if (m.dark) m.primaryText else m.primary,
    onPrimary = if (m.dark) Color(0xFF2A1206) else m.onPrimary,
    primaryContainer = m.container,
    onPrimaryContainer = m.onContainer,
    secondary = m.primaryText,
    onSecondary = m.onPrimary,
    secondaryContainer = m.container,
    onSecondaryContainer = m.onContainer,
    tertiary = m.primaryText,
    // [Mines-38.1] pages are transparent: AppNavigation draws the porcelain backdrop (blurred + masked
    // wallpaper) once behind every screen, so pages never restyled one by one still sit on 暖瓷.
    background = Color.Transparent,
    onBackground = m.ink,
    surface = Color.Transparent,
    onSurface = m.ink,
    surfaceVariant = m.surface2,
    onSurfaceVariant = m.ink2,
    surfaceContainerLowest = m.bg,
    // [Mines-38.9.5 i] user 22:24 「一个全局玻璃」: every Material card / sheet / menu / dialog surface is Mines glass (warm tint,
    // translucent) instead of a solid porcelain or dark panel. Floating surfaces over text (menus, toolbars, dialogs) use the
    // strong glass raised to ≥ 88 % so what is under them never fights the text on them.
    surfaceContainerLow = m.glass,
    surfaceContainer = m.glass,
    surfaceBright = m.glass,
    surfaceDim = m.bg,
    inverseSurface = if (m.dark) Color(0xFFF5F0EA) else Color(0xFF2A2420),
    inverseOnSurface = if (m.dark) Color(0xFF2A2420) else Color(0xFFF5F0EA),
    inversePrimary = Color(0xFFF0A285),
    surfaceTint = Color.Transparent,
    scrim = Color(0x6B14100C),
    surfaceContainerHigh = m.glassStrong.copy(alpha = maxOf(m.glassStrong.alpha, 0.72f)),   // [Mines-38.9.6] menus/sheets/dialogs now go through the glass wrappers
    surfaceContainerHighest = m.glassStrong.copy(alpha = maxOf(m.glassStrong.alpha, 0.72f)),
    outline = if (m.dark) Color(0xFF6A615A) else Color(0xFF8F857A),
    outlineVariant = if (m.dark) Color(0xFF3A342F) else Color(0xFFE2DAD0),
)

// [Mines-38.9.5 i] no extra tracking: M3's 0.1–0.5 sp letter-spacing spreads CJK glyphs apart (user: 字间距松)
private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) copy(letterSpacing = 0.sp) else copy(fontSize = fontSize * factor, letterSpacing = 0.sp)

private fun scaledTypography(factor: Float): Typography {
    // [Mines-38.1] one size smaller, per the approved board (user 16:03 「字体是不是太大了」): page title 20,
    // card title 15–16 SemiBold, body 14, secondary 13, meta 12. Readability comes from ≥ 4.5:1 contrast and
    // weight, not size; the system font scale still multiplies everything.
    val m3 = Typography()
    val base = m3.copy(
        headlineSmall = m3.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleLarge = m3.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        bodyLarge = m3.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = m3.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = m3.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        titleMedium = m3.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleSmall = m3.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = m3.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = m3.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = m3.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}

/** [Mines-38.9.7] what MinisTheme applied (自检). */
object MinesDensity { @Volatile var applied = 0f; @Volatile var system = 0f }

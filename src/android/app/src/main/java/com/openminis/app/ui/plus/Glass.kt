package com.openminis.app.ui.plus

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.PlusPrefs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * [Mines-glass-26] iOS "Today"-style frosted cards.
 *
 * Backdrop: the home wallpaper is drawn again inside the card, aligned to where
 * the card sits on screen, and blurred (Modifier.blur = RenderEffect on
 * Android 12+; older phones get no blur and rely on the translucent layer).
 * On top: a soft gradient scrim (theme colour → dark, or → light in light
 * theme), a 1 dp highlight edge and a light shadow.
 *
 * Readability first (the user is partially sighted): the scrim opacity is
 * raised as far as needed so that, against the BRIGHTEST (dark theme) or
 * DARKEST (light theme) part of the wallpaper, the text still has ≥ 4.5:1.
 * The worst-case backdrop colour is published as [LocalCardSurface], which
 * cardTextColor()/readableSecondary() use, so text is checked against what can
 * really be behind it, not the theme surface.
 */
val LocalCardSurface = compositionLocalOf<Color?> { null }

/** Linear-light luminance → an sRGB grey with that luminance. */
private fun greyOfLuminance(l: Float): Color {
    val c = l.coerceIn(0f, 1f).toDouble()
    val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    return Color(s.toFloat(), s.toFloat(), s.toFloat())
}

data class WallLum(val min: Float, val max: Float)

/** Darkest / brightest luminance of the wallpaper (on a 12×24 thumbnail, i.e. after heavy averaging). */
@Composable
private fun rememberWallLum(bmp: ImageBitmap?, preset: List<Color>?): WallLum? = remember(bmp, preset) {
    when {
        bmp != null -> runCatching {
            val small = Bitmap.createScaledBitmap(bmp.asAndroidBitmap(), 12, 24, true)
            var lo = 1f; var hi = 0f
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val l = Color(small.getPixel(x, y)).luminance(); lo = minOf(lo, l); hi = maxOf(hi, l)
            }
            WallLum(lo, hi)
        }.getOrNull()
        preset != null -> WallLum(preset.minOf { it.luminance() }, preset.maxOf { it.luminance() })
        else -> null
    }
}

/**
 * A frosted card. [onClick] adds a restrained press-scale (0.97). Content gets
 * LocalContentColor = a readable foreground and LocalCardSurface = the worst-case
 * backdrop for contrast checks.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    elevation: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val dark = mines().dark
    val wallOn = st.wallpaper.isNotEmpty() && st.wallpaperOnHome
    val preset = if (wallOn && st.wallpaper.startsWith("preset:")) wallpaperPresets.firstOrNull { it.id == st.wallpaper.removePrefix("preset:") }?.colors else null
    val bmp = if (wallOn && st.wallpaper.startsWith("file:")) rememberWallpaperBitmap(st.wallpaper) else null
    val lum = rememberWallLum(bmp, preset)
    val glass = st.glassOn

    // Base colour of the scrim and the opacity needed for 4.5:1 against the worst backdrop.
    // [Mines-design-34] porcelain glass (#FFFDFA / #221D1A) instead of the cool grey
    val base = if (dark) GlassTint.Dark else Color(0xFFFBF9F5)   // [Mines-38.9.6] 暖瓷
    val userA = if (glass) st.glassOpacity.coerceIn(0.3f, 0.95f).coerceAtLeast(if (wallOn) 0.3f else 0.72f) else 0.94f
    val worstWall = when {
        !wallOn || lum == null -> mines().bg.luminance()
        dark -> lum.max
        else -> lum.min
    }
    val baseL = base.luminance()
    val needA = if (dark) {
        // white text: backdrop luminance must stay ≤ 0.183
        if (worstWall <= 0.183f) 0f else ((worstWall - 0.183f) / (worstWall - baseL)).coerceIn(0f, 1f)
    } else {
        // black text: backdrop luminance must stay ≥ 0.175
        if (worstWall >= 0.175f) 0f else ((0.175f - worstWall) / (baseL - worstWall)).coerceIn(0f, 1f)
    }
    val a = maxOf(userA, needA + 0.04f).coerceAtMost(1f)
    val worstBg = greyOfLuminance((1 - a) * worstWall + a * baseL)

    var pos by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.97f else 1f, tween(120), label = "press")
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val screenW = with(density) { conf.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { conf.screenHeightDp.dp.roundToPx() }

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .onGloballyPositioned { pos = it.positionInRoot() }
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
    ) {
        // 1) backdrop: the wallpaper slice behind this card, blurred
        if (glass && bmp != null) {
            val soft = rememberSoftBitmap(bmp)  // [Mines-38.6] pre-blurred small copy: no dither grid under the GPU blur
            Image(
                soft ?: bmp, null, contentScale = ContentScale.Crop, alpha = st.wallpaperAlpha.coerceIn(0f, 1f),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier
                    .matchParentSize()
                    .layout { m, c ->
                        // [Mines-launch-28] build-26/27 launch crash: the Today chips sit in a
                        // Row(height(IntrinsicSize.Min)); intrinsic measurement runs this block
                        // with an unbounded maxHeight, and layout(w, Constraints.Infinity) throws
                        // "Size(… x 2147483647) is out of range" — every launch once a photo
                        // wallpaper is shown on home with 毛玻璃 on. The backdrop must not
                        // take part in sizing: unbounded → 0.
                        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(screenW.coerceIn(1, 16_000), screenH.coerceIn(1, 16_000)))
                        val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
                        val h = if (c.hasBoundedHeight) c.maxHeight else c.minHeight
                        layout(w, h) { p.place(IntOffset(-pos.x.roundToInt(), -pos.y.roundToInt())) }
                    }
                    .blur((st.glassBlur.coerceIn(0, 40) / 2).coerceAtMost(8).dp),   // [Mines-38.9.5 i11] cap: the bitmap is pre-blurred already
            )
        } else if (glass && preset != null) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(preset)))
        }
        // 2) gradient scrim (keeps text readable) — theme colour fading into the base
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(
                    listOf(
                        cs.primaryContainer.copy(alpha = 0.18f).compositeOverSafe(base.copy(alpha = a)),
                        base.copy(alpha = a),
                    ),
                ),
            ),
        )
        // 3) hairline highlight
        Box(
            Modifier.matchParentSize().border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.22f else 0.6f), Color.White.copy(alpha = 0.04f))),
                shape,
            ),
        )
        val fg = if (dark) MinesDesign.InkDark else MinesDesign.Ink
        CompositionLocalProvider(LocalCardSurface provides worstBg, LocalContentColor provides fg) {
            content()
        }
    }
}

private fun Color.compositeOverSafe(under: Color): Color {
    val a = alpha + under.alpha * (1 - alpha)
    if (a <= 0f) return Color.Transparent
    fun ch(c1: Float, c2: Float) = (c1 * alpha + c2 * under.alpha * (1 - alpha)) / a
    return Color(ch(red, under.red), ch(green, under.green), ch(blue, under.blue), a)
}

/**
 * Fill for conversation rows / folders in glass mode: a soft translucent
 * gradient with the same worst-case readability rule (no per-row blur, to keep
 * scrolling smooth).
 */
@Composable
fun glassRowBrush(): Brush? {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    if (!st.glassOn || st.wallpaper.isEmpty() || !st.wallpaperOnHome) return null
    val dark = mines().dark
    val base = if (dark) GlassTint.Dark else Color(0xFFFBF9F5)   // [Mines-38.9.6] 暖瓷
    val a = maxOf(st.glassOpacity, 0.72f).coerceAtMost(0.95f)
    return Brush.verticalGradient(listOf(base.copy(alpha = a - 0.06f), base.copy(alpha = a)))
}

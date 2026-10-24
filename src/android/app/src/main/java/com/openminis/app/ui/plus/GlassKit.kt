package com.openminis.app.ui.plus

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [Mines-38.9.6 i12] ONE glass for every floating surface (menus, sheets, dialogs, the progress card) and the tint every
 * card uses. User 2026-10-18 (phone shots 1000129911 / 1000129912): the ☰ menu had a square, near-opaque white block inside
 * its rounded glass, the progress sheet was a solid off-white card, and the glass looked pink-red.
 *
 * Root cause of the inner block: an ELEVATION shadow under a translucent surface. HWUI draws the whole shadow (umbra
 * included) under the shape; through a 30–50 % tint the umbra shows as a square-cornered grey/white rectangle offset
 * downward (spot light from above). Glass therefore never uses elevation: [glassOuterShadow] paints the soft halo only
 * OUTSIDE the outline (clip difference), with a cached path and paint, nothing allocated per frame.
 *
 * 暖瓷 tint (user: calmer warm ivory porcelain, low saturation, slight warm grey; fixed, never from the wallpaper):
 * light #F3EFE9, dark warm charcoal #252220.
 */
object GlassTint {
    val Light = Color(0xFFF3EFE9)
    val Dark = Color(0xFF252220)
    fun of(dark: Boolean): Color = if (dark) Dark else Light
    /** what was there before 38.9.6 (for the before/after sheet only) */
    val OldLight = Color(0xFFFFF5EF)
    val OldDark = Color(0xFF221B1E)
}

/**
 * Tint alpha for every floating glass surface. 自动: the GlassAuto cap (light 45 %, dark 50 %) over the blurred page copy;
 * 手动: the 外观调节 slider at full strength — the same slider and the same factor as the cards (one global value).
 */
@Composable
fun glassPopupFill(): Color {
    val m = mines()
    val a = popupGlassAlpha(m.dark, MinesLook.glassScale(m.dark)).coerceAtLeast(GlassFloor.popup(m.dark))
    // graceful fallback: until (or unless) the page behind is blurred (RenderEffect on API 31+, the cached copy below),
    // sharp text would sit right under the tint — stay dense (88 %) and thin out with the blur fade-in (same 160 ms)
    // [Mines-38.9.6 i14] user 「不要穿透」: no blur (yet / at all) or a second overlay over a sheet (that sheet is not under the
    // root blur) → ≥ 92 % so the text behind can never be read; with the blur the 暖瓷 tint stays translucent (frosted)
    val blurred = GlassBackdrop.ready.collectAsState().value
    val stacked = GlassBackdrop.depth.collectAsState().value > 1
    val shown by animateFloatAsState(if (blurred && !stacked) a else maxOf(a, GlassFloor.NO_BLUR), tween(160), label = "glassFill")
    return GlassTint.of(m.dark).copy(alpha = shown)
}

/** Soft halo painted only outside [shape] (so a translucent surface never shows a shadow core). API < 28: no halo. */
fun Modifier.glassOuterShadow(shape: Shape, radius: Dp = 18.dp, dy: Dp = 6.dp, dark: Boolean = false): Modifier =
    if (Build.VERSION.SDK_INT < 28) this else this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutlineTo(outline) }
        val r = radius.toPx(); val off = dy.toPx()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF000000.toInt() else 0xFF3C2E24.toInt()
            setShadowLayer(r, 0f, off, (if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF3C2E24).copy(alpha = 0.16f)).toArgb())
        }
        val nativePath = path.asAndroidPath()
        onDrawBehind {
            clipPath(path, ClipOp.Difference) {
                drawIntoCanvas { it.nativeCanvas.drawPath(nativePath, paint) }
            }
        }
    }

/** 1 px top highlight that fades out over the first ~40 dp: the light edge that makes a flat tint read as glass. */
fun Modifier.glassSheen(shape: Shape, dark: Boolean): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (dark) 0.07f else 0.26f),
        1f to Color.Transparent,
        startY = 0f, endY = 40.dp.toPx().coerceAtMost(size.height),
    )
    onDrawBehind { drawOutline(outline, brush) }
}

/**
 * The shared glass container: outside-only halo, clip, 暖瓷 tint at the global glass alpha, top sheen, hairline rim.
 * What is behind it is blurred by [GlassBackdrop] (cached, software, one capture per open) — call [GlassWindowEffect]
 * once inside the window that hosts it.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    fill: Color = glassPopupFill(),
    halo: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = mines()
    Column(
        modifier
            .then(if (halo) Modifier.glassOuterShadow(shape, dark = m.dark) else Modifier)
            .clip(shape)
            .background(fill)
            .glassSheen(shape, m.dark)
            .border(1.dp, m.glassBorder, shape),
        content = content,
    )
}

private fun Path.addOutlineTo(o: Outline) {
    when (o) {
        is Outline.Rectangle -> addRect(o.rect)
        is Outline.Rounded -> addRoundRect(o.roundRect)
        is Outline.Generic -> addPath(o.path)
    }
}


/**
 * [Mines-38.9.6 i12] minimum tint per surface (user: 「直接透穿」 — nothing behind may be legible, even at the lowest
 * 外观调节 setting). Popups sit on the blurred page, so their floor is lower; input bars and the chat composer sit over
 * sharp, scrolling text with NO blur, so theirs is a dense tint (the fallback 「higher solid tint」).
 */
object GlassFloor {
    fun popup(dark: Boolean) = if (dark) 0.50f else 0.45f
    fun card(dark: Boolean) = if (dark) 0.38f else 0.32f
    /** home 开个新对话 bar (over the blurred fog, rows scroll away under the dock fade) */
    fun bar(dark: Boolean) = if (dark) 0.80f else 0.80f
    /** chat composer + text fields over sharp chat text */
    fun field(dark: Boolean) = if (dark) 0.90f else 0.90f
    /** [Mines-38.9.6 i14] any overlay when the page behind is not blurred (old phone w/o copy, battery saver, stacked) */
    const val NO_BLUR = 0.97f   // [i15] 0.92 left a faint ghost of white home text under the dark dialog before the blur arrived (smoke 39100911779)
    /** toasts / snackbars / banners: never modal, nothing behind them is blurred → dense frosted tint */
    fun toast(dark: Boolean) = if (dark) 0.96f else 0.96f
    /** lowest manual value the 外观调节 slider can set */
    const val SLIDER_MIN = 0.35f
}

/**
 * Visible soft rim for input bars and text fields (user: the input box 「看不清」): warm grey with ≥ 3:1 against the
 * glass tint (light #8C8278 on #F3EFE9 ≈ 3.4:1, dark #8E857D on #252220 ≈ 4.5:1; GlassWrapperScanTest checks it).
 */
object GlassRim {
    val Light = Color(0xFF8C8278)
    val Dark = Color(0xFF8E857D)
    fun of(dark: Boolean): Color = if (dark) Dark else Light
}

/** Input bar / composer tint: the global glass alpha, never below [GlassFloor.bar] / [GlassFloor.field]. */
@Composable
fun glassInputFill(field: Boolean = false): Color {
    val m = mines()
    val a = popupGlassAlpha(m.dark, MinesLook.glassScale(m.dark))
    return GlassTint.of(m.dark).copy(alpha = a.coerceAtLeast(if (field) GlassFloor.field(m.dark) else GlassFloor.bar(m.dark)).coerceAtMost(0.97f))
}

/** One input surface: halo outside only, glass fill, soft visible rim, top sheen. */
fun Modifier.glassInput(shape: Shape, fill: Color, dark: Boolean): Modifier =
    this.glassOuterShadow(shape, radius = 10.dp, dy = 2.dp, dark = dark).clip(shape).background(fill).glassSheen(shape, dark)
        .border(1.dp, GlassRim.of(dark).copy(alpha = 0.85f), shape)

/**
 * The page behind an open popup / menu / sheet / dialog, blurred ONCE in the shared host (user 2026-10-18: blur the whole
 * screen behind every popup). API 31+: a RenderEffect blur on the app's content root, animated in (180 ms) and out, the
 * fog held still meanwhile so the blurred layer stays cached (no per-frame work while open). Below 31: the cached
 * software-blurred copy ([GlassBackdropLayer]) + a stronger scrim.
 */
fun Modifier.glassRootBlur(): Modifier = this.composed {
    if (Build.VERSION.SDK_INT < 31) return@composed this
    val on = GlassBackdrop.active.collectAsState().value
    val off = GlassBackdrop.rootBlurOff.collectAsState().value   // [i17] not proven to draw → software copy instead
    val r by animateFloatAsState(if (on && !off) 20f else 0f, tween(180), label = "rootBlur")
    this.graphicsLayer {
        val px = r.dp.toPx()
        renderEffect = if (px > 0.5f) androidx.compose.ui.graphics.BlurEffect(px, px, androidx.compose.ui.graphics.TileMode.Clamp) else null
    }
}

/** Filled glass text field (search fields in sheets): input tint + soft rim, accent rim while focused. */
@Composable
fun glassFieldColors(): androidx.compose.material3.TextFieldColors {
    val m = mines()
    val fill = glassInputFill(field = true)
    return androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill,
        focusedBorderColor = m.primary, unfocusedBorderColor = GlassRim.of(m.dark),
        focusedTextColor = m.ink, unfocusedTextColor = m.ink,
        focusedPlaceholderColor = m.ink2, unfocusedPlaceholderColor = m.ink2,
        focusedLeadingIconColor = m.ink2, unfocusedLeadingIconColor = m.ink2,
    )
}

/**
 * [Mines-38.9.6 i14] toasts / banners / snackbars (non-modal, nothing behind is blurred): the shared glass with a dense
 * 暖瓷 tint ([GlassFloor.toast]) so text behind never reads through; same halo, sheen and rim as every other overlay.
 */
@Composable
fun GlassToast(modifier: Modifier = Modifier, shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
               /** [Mines-38.9.8] solid (error card with a log: nothing behind may bleed through, user 01:22) */
               solid: Boolean = false,
               content: @Composable ColumnScope.() -> Unit) {
    val m = mines()
    GlassSurface(modifier, shape = shape, fill = GlassTint.of(m.dark).copy(alpha = if (solid) 1f else GlassFloor.toast(m.dark)), content = content)
}

/** Material SnackbarHost's item, drawn as a [GlassToast] (ink text, accent action). */
@Composable
fun GlassSnackbar(data: androidx.compose.material3.SnackbarData) {
    val m = mines()
    GlassToast(androidx.compose.ui.Modifier.padding(12.dp).fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 40.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Text(data.visuals.message, fontSize = MT.Body, color = m.ink, modifier = androidx.compose.ui.Modifier.weight(1f))
            data.visuals.actionLabel?.let { a ->
                androidx.compose.material3.TextButton(onClick = { data.performAction() }) {
                    androidx.compose.material3.Text(a, fontSize = MT.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = m.primaryText)
                }
            }
        }
    }
}

/**
 * [Mines-38.9.6 i14] View-system AlertDialogs (permission gates, crash loop, share target, web page alert/confirm/prompt):
 * same rule as the Compose overlays — the app page behind is blurred (root RenderEffect via [GlassBackdrop]) while it is
 * open, the window is a rounded 暖瓷 glass card (dense when the window behind is not ours to blur), Mines ink colours,
 * no white Material card. Use [GlassViewDialog.builder] instead of AlertDialog.Builder (GlassSurfaceScanTest enforces it).
 */
object GlassViewDialog {
    fun builder(ctx: android.content.Context): android.app.AlertDialog.Builder {
        val dark = GlassBackdrop.darkNow
        val themed = android.view.ContextThemeWrapper(ctx,
            if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        return object : android.app.AlertDialog.Builder(themed) {
            override fun create(): android.app.AlertDialog = super.create().also { attach(it, ctx, dark) }
        }
    }

    fun attach(d: android.app.Dialog, host: android.content.Context, dark: Boolean = GlassBackdrop.darkNow) {
        val tint = GlassTint.of(dark)
        val a = GlassFloor.NO_BLUR   // the window under a View dialog may be another window (sheet): dense, never see-through
        val fill = android.graphics.Color.argb((255 * a).toInt(), (tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt())
        d.setOnShowListener { di ->
            val w = (di as android.app.Dialog).window ?: return@setOnShowListener
            val dp = host.resources.displayMetrics.density
            w.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26f * dp; setColor(fill)
                setStroke((1f * dp).toInt().coerceAtLeast(1), if (dark) 0x33FFFFFF else 0x29FFFFFF)
            }, (20 * dp).toInt()))
            runCatching { w.setDimAmount(0.18f) }
            GlassBackdrop.show(host)
            w.decorView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {}
                override fun onViewDetachedFromWindow(v: android.view.View) { v.removeOnAttachStateChangeListener(this); GlassBackdrop.hide() }
            })
        }
    }
}

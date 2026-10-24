package com.openminis.app.ui.plus

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.glass.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.brand.MinesIcon

/**
 * [Mines-38.5] 「毛玻璃弹窗」 (user 2026-10-15 「不会做一个毛玻璃弹窗？」). Every Mines dialog / bottom
 * sheet / long-press menu blurs what is behind it (Android 12+: FLAG_BLUR_BEHIND + blurBehindRadius
 * on the dialog window, 64 px) and lays a ~72 % warm porcelain veil + 1 dp highlight over it. Text sits
 * on the veil, never on readable content (radius 64 px turns the page into colour only). When the
 * device has cross-window blur off (battery saver, low-end, Android ≤ 11) the veil is solid (98 %).
 */
object GlassSupport {
    fun blurAvailable(ctx: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return false
        return runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
    }

    /** The dialog / sheet window hosting [view] (walks up to the DialogWindowProvider), or null. */
    fun windowOf(view: View): Window? {
        var v: Any? = view
        var hops = 0
        while (v != null && hops < 8) {
            if (v is androidx.compose.ui.window.DialogWindowProvider) return v.window
            v = (v as? View)?.parent
            hops++
        }
        return null
    }

    fun apply(w: Window, ctx: android.content.Context) {
        if (Build.VERSION.SDK_INT < 31 || !blurAvailable(ctx)) return
        runCatching {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = 64 }
            w.setDimAmount(0.18f)
        }
    }
}

/**
 * [Mines-38.7] Frosted glass without the compositor blur. 38.5/38.6 used FLAG_BLUR_BEHIND (radius 64): on the emulator's
 * GPU the compositor's down-sampled blur left a fine grid / dither over the page behind every sheet (smoke screenshots,
 * zoomed), and how it looks depends on each phone's GPU and battery saver. Now, when a glass dialog / sheet opens, the
 * activity's own pixels are copied once (PixelCopy), shrunk to ~1/12 with filtering, box-blurred in software (3 passes)
 * and drawn full screen over the app by [GlassBackdropLayer], under the dialog window. Same look on every phone,
 * nothing per-frame, no grid.
 */
object GlassBackdrop {
    val bitmap = kotlinx.coroutines.flow.MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    @Volatile private var count = 0
    /** smoke: what the last capture produced ("ok WxH" / the failure). */
    @Volatile var lastResult: String = ""

    private fun activityOf(ctx: android.content.Context?): android.app.Activity? {
        var c = ctx
        repeat(8) { if (c is android.app.Activity) return c as android.app.Activity; c = (c as? android.content.ContextWrapper)?.baseContext }
        return null
    }

    /** [Mines-38.9.6 i12] a glass popup / sheet / dialog is open (drives the root blur + scrim) */
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** the page behind is blurred now (RenderEffect on 31+, or the copy has arrived) */
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** RenderEffect path (API 31+); the smoke / old phones use the copied, software-blurred page */
    val live: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** [Mines-38.9.6 i14] how many glass overlays are open (a dialog over a sheet: the sheet window is NOT under the root
     *  blur, so the top one goes dense — see [glassPopupFill]) */
    val depth = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** [Mines-38.9.6 i14] the Mines theme is dark (set by [GlassBackdropLayer]; for View-based dialogs) */
    @Volatile var darkNow: Boolean = false
    /** smoke: overlays opened so far and whether the page behind was blurred for the last one */
    @Volatile var opened: Long = 0
    @Volatile var lastBlurred: Boolean = false

    fun show(ctx: android.content.Context) {
        count++; depth.value = count; opened++
        if (count != 1) return
        active.value = true
        FogClock.holdUntil = Long.MAX_VALUE   // the page stays still while blurred → the blurred layer is cached
        // [Mines-38.9.6 i17] API 31+: the root RenderEffect blur is NOT assumed to have drawn (smoke O: light theme, glass 0 +
        // photo wallpaper → ☰ over a sharp page, list text read through the thin tint). [ready] (= thin frosted fill) is set
        // only after a post-draw copy of the window proves the page behind really went soft; otherwise the fill stays dense
        // (GlassFloor.NO_BLUR) and the software-blurred copy of the pre-blur frame is laid over the page.
        if (live) { verifyLive(ctx); return }
        lastBlurred = false
        // [Mines-38.9.6 i14] battery saver below API 31: no software copy → the fill stays dense (≥ 92 %)
        val saver = runCatching { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode }.getOrDefault(false)
        if (saver) { lastResult = "saver: dense fill"; return }
        val a = activityOf(ctx) ?: run { lastResult = "no activity"; return }
        val w = a.window; val v = w.decorView
        if (v.width <= 0 || v.height <= 0) { lastResult = "no size"; return }
        runCatching {
            val full = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(w, full, { res ->
                if (res == android.view.PixelCopy.SUCCESS && count > 0) {
                    val small = blurSmall(full)
                    bitmap.value = small.asImageBitmap()
                    ready.value = true; lastBlurred = true
                    lastResult = "ok ${small.width}x${small.height}"
                } else lastResult = "pixelcopy $res"
                full.recycle()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { lastResult = "error ${it.javaClass.simpleName}" }
    }

    fun hide() {
        count = (count - 1).coerceAtLeast(0); depth.value = count
        if (count == 0) { gen++; bitmap.value = null; ready.value = false; active.value = false; FogClock.holdUntil = 0L }
    }

    /** [Mines-38.9.6 i17] one open session; a copy callback from an older session is dropped */
    @Volatile private var gen = 0
    /** smoke: sharpness ratio (after / before) the last check measured, -1 = not measured */
    @Volatile var lastRatio: Float = -1f
    /** [Mines-38.9.6 i17] the RenderEffect was not proven this session → the root drops it and draws the software copy instead */
    val rootBlurOff = kotlinx.coroutines.flow.MutableStateFlow(false)

    private class Frame(val luma: FloatArray, val w: Int, val h: Int, val blurred: android.graphics.Bitmap)

    /** PixelCopy of the activity window (popups / dialogs are their own windows, so not in it) → small luma + blurred copy. */
    private fun copyFrame(w: android.view.Window, v: android.view.View, done: (Frame?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching {
            val full = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(w, full, { res ->
                if (res != android.view.PixelCopy.SUCCESS) { full.recycle(); done(null); return@request }
                val f = runCatching {
                    val tw = 180; val th = (full.height * tw / full.width.toFloat()).toInt().coerceAtLeast(1)
                    var cur = full
                    while (cur.width / 2 >= tw * 2) {
                        val nx = android.graphics.Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
                        if (cur !== full) cur.recycle(); cur = nx
                    }
                    val small = android.graphics.Bitmap.createScaledBitmap(cur, tw, th, true)
                    if (cur !== full) cur.recycle()
                    val px = IntArray(tw * th); small.getPixels(px, 0, tw, 0, 0, tw, th); small.recycle()
                    val l = FloatArray(px.size) { i -> val c = px[i]; 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255) }
                    Frame(l, tw, th, blurSmall(full))
                }.getOrNull()
                full.recycle(); done(f)
            }, main)
        }.onFailure { done(null) }
    }

    private fun verifyLive(ctx: android.content.Context) {
        val my = ++gen
        ready.value = false; lastBlurred = false; lastRatio = -1f; rootBlurOff.value = false; lastResult = "renderEffect: checking"
        val a = activityOf(ctx) ?: run { lastResult = "renderEffect: no activity → dense"; return }
        val w = a.window; val v = w.decorView
        if (v.width <= 0 || v.height <= 0) { lastResult = "renderEffect: no size → dense"; return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // the frame on screen now is the page before the blur animates in (180 ms)
        copyFrame(w, v) { before ->
            if (my != gen || count == 0) return@copyFrame
            if (before == null) { lastResult = "renderEffect: copy failed → dense"; return@copyFrame }
            fun check(attempt: Int) {
                main.postDelayed({
                    if (my != gen || count == 0) return@postDelayed
                    copyFrame(w, v) { after ->
                        if (my != gen || count == 0) return@copyFrame
                        val r = if (after == null || after.w != before.w || after.h != before.h) -1f else GlassBlurCheck.sharpRatio(before.luma, after.luma, before.w, before.h)
                        after?.blurred?.recycle()
                        lastRatio = r
                        if (GlassBlurCheck.blurred(r)) {
                            before.blurred.recycle()
                            ready.value = true; lastBlurred = true; lastResult = "renderEffect drawn r=" + String.format(java.util.Locale.US, "%.2f", r)
                        } else if (attempt == 0) check(1)
                        else {
                            // never proven: blurred copy of the pre-blur frame over the page, fill stays dense
                            rootBlurOff.value = true
                            bitmap.value = before.blurred.asImageBitmap()
                            lastBlurred = false
                            lastResult = "renderEffect not drawn r=" + String.format(java.util.Locale.US, "%.2f", r) + " → dense fill + copy"
                        }
                    }
                }, if (attempt == 0) 320L else 600L)
            }
            check(0)
        }
    }

    /** ~90 px wide copy, three box-blur passes each way (≈ gaussian), ready to be drawn back up with bilinear filtering. */
    fun blurSmall(src: android.graphics.Bitmap): android.graphics.Bitmap {
        // [Mines-38.9.4] 180 px (was 90) + radius 8 × 3 passes: stronger, smoother blur without the blocky upscale bands
        val tw = 180; val th = (src.height * tw / src.width.toFloat()).toInt().coerceAtLeast(1)
        // two filtered halvings before the final size: a plain one-step shrink aliases fine text into moiré
        var cur = src
        while (cur.width / 2 >= tw * 2) {
            val nx = android.graphics.Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle(); cur = nx
        }
        val small = android.graphics.Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src) cur.recycle()
        val px = IntArray(tw * th); small.getPixels(px, 0, tw, 0, 0, tw, th)
        val tmp = IntArray(px.size)
        fun pass(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
            val n = if (horiz) w else h; val lines = if (horiz) h else w
            for (l in 0 until lines) {
                var ra = 0; var rr = 0; var rg = 0; var rb = 0
                fun at(i: Int): Int { val k = i.coerceIn(0, n - 1); return if (horiz) inp[l * w + k] else inp[k * w + l] }
                for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
                val d = 2 * r + 1
                for (i in 0 until n) {
                    val o = ((ra / d) shl 24) or ((rr / d) shl 16) or ((rg / d) shl 8) or (rb / d)
                    if (horiz) out[l * w + i] = o else out[i * w + l] = o
                    val a0 = at(i - r); val a1 = at(i + r + 1)
                    ra += (a1 ushr 24) - (a0 ushr 24); rr += ((a1 shr 16) and 255) - ((a0 shr 16) and 255)
                    rg += ((a1 shr 8) and 255) - ((a0 shr 8) and 255); rb += (a1 and 255) - (a0 and 255)
                }
            }
        }
        repeat(3) { pass(px, tmp, tw, th, 8, true); pass(tmp, px, tw, th, 8, false) }
        small.setPixels(px, 0, tw, 0, 0, tw, th)
        return small
    }
}

/** Drawn once at the activity root: the scrim over the blurred page (and, below API 31, the blurred copy itself). */
@Composable
fun GlassBackdropLayer() {
    val on by GlassBackdrop.active.collectAsState()
    val b by GlassBackdrop.bitmap.collectAsState()
    val fade by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(180), label = "glass")
    var last by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // [Mines-38.9.6 i17] the copy is also the API 31+ fallback (RenderEffect not proven); a new session forgets the old copy
    if (b != null) last = b else if (on) last = null
    if (fade <= 0.01f && !on) return
    last?.let { img ->
        androidx.compose.foundation.Image(img, null, androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = fade },
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    }
    // [Mines-38.9.6] light neutral warm-grey scrim over the blurred page (was a pink veil); stronger without RenderEffect
    val dk = mines().dark
    GlassBackdrop.darkNow = dk
    val scrim = when {
        GlassBackdrop.live -> if (dk) Color(0x3A0E0C0B) else Color(0x29E2DCD3)
        else -> if (dk) Color(0x570E0C0B) else Color(0x40DCD5CB)
    }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(scrim))
}

/** Call inside any Dialog / ModalBottomSheet content: turns its window into frosted glass (no-op elsewhere). */
@Composable
fun GlassWindowEffect() {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(view) {
        GlassSupport.windowOf(view)?.let { runCatching { it.setDimAmount(0.18f) } }
        GlassBackdrop.show(ctx)
        onDispose { GlassBackdrop.hide() }
    }
}

/** Veil colour for glass surfaces: ~72 % porcelain over a 64 px blur, solid when blur is unavailable. */
@Composable
fun glassVeil(): Color {
    val m = mines()
    val ctx = LocalContext.current
    // [Mines-38.7] the backdrop is our own blurred copy on every phone (Android 8+), so the veil no longer depends on the
    // system's cross-window blur switch
    // [Mines-38.8 v5d] light sheets / dialogs: frosted peach glass at 65 % (was 80 % near-white); dark unchanged
    val base = GlassTint.of(m.dark)
    // [Mines-38.9.6 i12] one global glass value: sheets / dialogs follow the 外观调节 slider at full strength like the cards
    val k = MinesLook.glassScale(m.dark)
    // [Mines-38.9.4] user 「弹窗雾化背景可以再做好点」: dialogs / sheets are frosted glass (light 66 %, dark 70 %) over a smoother blur
    return base.copy(alpha = popupGlassAlpha(m.dark, k))
}

/** [Mines-38.9.5 i] user 22:02 (☰ menu 「不是毛玻璃」, glass auto 「太白」): menus, dropdowns, sheets and dialogs use the
 *  38.9.3-like thin glass — 自动: the GlassAuto caps (light 45 %, dark 50 %) over the blurred, lightly dimmed page copy;
 *  手动: the slider (half strength, as before). */
fun popupGlassAlpha(dark: Boolean, k: Float = 1f): Float =
    if (MinesLook.glassAuto(dark)) com.openminis.app.plus.GlassAuto.cap(dark)
    else ((if (dark) 0.56f else 0.52f) * k).coerceIn(GlassFloor.popup(dark), 0.97f)   // [Mines-38.9.6] card alpha + a little (one slider), floor

/**
 * The one Mines dialog: title, scrollable content (never a big empty block), primary = solid
 * terracotta pill, cancel = text button. Frosted glass window.
 */
@Composable
fun MinesDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismiss: String? = "取消",
    onDismissButton: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = mines()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = { Text(title, fontSize = MT.PageTitle, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MS.S), content = content)
        },
        confirmButton = {
            if (confirm != null && onConfirm != null) GlassPill(confirm, enabled = confirmEnabled, danger = danger, onClick = onConfirm)
        },
        dismissButton = {
            if (dismiss != null) GlassTextButton(dismiss) { (onDismissButton ?: onDismiss)() }
        },
    )
}

/** Solid primary pill used in dialogs and sheets (48 dp, 15 sp). */
@Composable
fun GlassPill(text: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = mines()
    val bg = if (danger) MinesDesign.DangerFill else m.primary
    // [Mines-38.9.5 i11] disabled = a quiet neutral pill with full-contrast secondary ink + hairline (was the accent at 40 %:
    // white on light salmon in light mode, dark brown in dark — unreadable); it still reads as 「not yet」, not as an action
    val offBg = if (m.dark) Color(0x24FFFFFF) else Color(0x141C1915)
    val offRim = if (m.dark) Color(0x33FFFFFF) else Color(0x291C1915)
    Box(modifier.heightIn(min = MS.ButtonH).clip(RoundedCornerShape(50)).background(if (enabled) bg else offBg)
        .then(if (enabled) Modifier else Modifier.border(1.dp, offRim, RoundedCornerShape(50)))
        .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button; if (!enabled) disabled() }.padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else m.ink2, maxLines = 1, softWrap = false)
    }
}

/** Text button (secondary actions, 取消): 48 dp tall, accent ink. */
@Composable
fun GlassTextButton(text: String, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val m = mines()
    Box(modifier.heightIn(min = MS.ButtonH).clip(RoundedCornerShape(50)).clickable(onClick = onClick).semantics { role = Role.Button }
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Button, fontWeight = FontWeight.Medium, color = color ?: m.primaryText, maxLines = 1, softWrap = false)
    }
}

/** Secondary pill (quiet fill + ink), same height as the primary one. */
@Composable
fun GlassSecondaryPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = mines()
    Box(modifier.heightIn(min = MS.ButtonH).clip(RoundedCornerShape(50)).background(if (m.dark) Color(0x1FFFFFFF) else Color(0x121C1915))
        .clickable(onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, softWrap = false)
    }
}

/** Frosted bottom sheet: content scrolls, bottom gets navigation-bar padding (nothing hides under the gesture bar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinesSheet(onDismiss: () -> Unit, secure: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val m = mines()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        // [Mines-38.9.6 i13] credential sheets: FLAG_SECURE on the sheet's own window (no screenshots / recents preview)
        properties = androidx.compose.material3.ModalBottomSheetProperties(
            securePolicy = if (secure) androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = MS.L), content = content)
    }
}

/** One row of a glass menu: 20 dp line icon in the accent, 16 sp label, optional danger tint. */
@Composable
fun GlassMenuRow(text: String, icon: ImageVector?, danger: Boolean = false, onClick: () -> Unit) {
    val m = mines()
    val fg = if (danger) (if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) else m.ink
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).semantics { role = Role.Button }
        .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MinesIcon(icon, null, tint = if (danger) fg else m.icon, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(16.dp)) }
        Text(text, fontSize = MT.Body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

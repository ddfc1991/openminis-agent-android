package com.openminis.app.ui.plus

import com.openminis.app.plus.SkyFreezeCore

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.SkyModeCore
import com.openminis.app.plus.SkyPerfCore
import androidx.compose.ui.graphics.graphicsLayer
import com.openminis.app.plus.WeatherSkyCore
import com.openminis.app.plus.WindDriftCore
import kotlinx.coroutines.launch
import com.openminis.app.plus.WeatherSkyCore.Scene

/**
 * [Mines-38.9.10 sky v7] living sky — the user-approved prototype (mock-mood/video2 sky.py + yu2.py, 2026-10-19 19:09)
 * ported to AGSL: one weather held, the sky keeps living. Pass 1 (assets/skybg/sky.agsl, one SKY segment per scene) runs at
 * quarter resolution (= the prototype's 270-wide internal sky) into a RenderNode, softened by a 1.5 px blur and drawn ×4;
 * it reads four tileable fractal-noise textures (assets/skybg/tex0..3.webp, the prototype's FFT noise) for the cloud
 * masses on 2–4 parallax layers, a slowly wandering camera, light gaps, rays, mist, aurora. Pass 2: 夜 stars and 雪 flakes at
 * full resolution; 雨 = wet glass at half resolution that refracts the sky pass (RenderEffect runtime shader, input `sky`).
 * The shader applies the theme grade and the text guard itself ([WeatherSkyCore.guard]: ink ≥ 4.5:1 on home, secondary
 * ink ≥ 4.5:1 on the chat page). ≤ 20 fps while RESUMED and nothing covers the page (MotionGate); battery saver = 4 fps;
 * reduce motion = one still frame of the shader. No shader (API < 33 / compile error) → pre-rendered stills (skybg/still_*).
 */
object WeatherSky {
    /** smoke: force a scene (null = from the weather) and / or the still frame */
    @Volatile var forced: Scene? = null
    @Volatile var forceStatic: Boolean = false
    /** smoke: shift the sky clock (ms) — live frames minutes apart, or the forced still frame ([WeatherSkyCore.STILL_T] + this) */
    @Volatile var clockOffsetMs: Long = 0L
    /** smoke / 自检: what is drawn now */
    @Volatile var lastScene: Scene = Scene.CLOUDY
    @Volatile var lastMode: String = ""
    @Volatile var lastShot: String = ""
    /** [38.9.11.3] scrolling / a page opening: the sky loop runs at SkyModeCore.BUSY_FPS until this time (keeps drifting) */
    @Volatile var busyUntil: Long = 0L
    fun busy(ms: Long = 600L) { val u = System.currentTimeMillis() + ms; if (u > busyUntil) busyUntil = u }
    /** [38.9.11.3] smoke / 自检: the drift shift of the last animated key composite */
    @Volatile var lastDrift: String = ""
    @Volatile var shaderError: String? = null
    @Volatile var frames: Long = 0
    val tick = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun bump() { tick.value++ }

    // [38.9.11.1] 设置 › 外观调节 › 天空动画: 流畅 (default) / 精美 / 关闭 (still)
    /** [38.9.11.3 g2] 远程诊断 remote config (in-memory only; null / 0 = the user's own setting) */
    @Volatile var modeOverride: SkyModeCore.Mode? = null
    @Volatile var fpsCap: Int = 0
    fun mode(c: android.content.Context): SkyModeCore.Mode = modeOverride ?: SkyModeCore.parse(c.getSharedPreferences("mines_sky", 0).getString("mode", null))
    fun setMode(c: android.content.Context, m: SkyModeCore.Mode) { c.getSharedPreferences("mines_sky", 0).edit().putString("mode", m.key).apply(); bump() }
    /** [38.9.11.1] one live sky at a time (main thread) */
    val registry = SkyModeCore.Registry()
    val liveToken = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** smoke / 自检: shader renders (key frames + live frames) since launch */
    @Volatile var keyRenders: Long = 0
    /** [38.9.11.3 f] key nodes found without a recording (HWUI dropped them) and recorded again */
    @Volatile var keyLost: Long = 0
    fun enabled(c: android.content.Context): Boolean = c.getSharedPreferences("mines_sky", 0).getBoolean("on", true)
    fun setEnabled(c: android.content.Context, on: Boolean) { c.getSharedPreferences("mines_sky", 0).edit().putBoolean("on", on).apply(); bump() }

    /** the current scene: forced (smoke) › HomeGreeting override (smoke) › cached weather + is_day › time-only default */
    fun scene(c: android.content.Context): Scene {
        forced?.let { return it }
        val o = com.openminis.app.plus.HomeGreeting.override
        val hour = o?.optInt("hour", -1)?.takeIf { it >= 0 } ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val w = runCatching { com.openminis.app.plus.HomeGreeting.weatherNow(c) }.getOrNull()
        val day = if (o != null && o.has("hour")) null else runCatching { com.openminis.app.plus.HomeGreeting.isDayNow(c) }.getOrNull()
        return WeatherSkyCore.pick(w?.code, hour, day)
    }

    fun now(): Long = System.currentTimeMillis() + clockOffsetMs

    // [Mines-38.9.11 i32] cloud / fog drift follows the real wind (WindDriftCore): one shared, eased, integrated clock
    private val cloud = WindDriftCore.Clock()
    @Volatile private var windAt = 0L
    @Volatile var windMs: Double? = null
    @Volatile var windTarget: Float = WindDriftCore.UNKNOWN
    /** smoke / 自检: force the wind (m/s; NaN = unknown) */
    @Volatile var forcedWind: Double? = null
    fun windRateLabel(): String = "风 ${windMs?.let { "%.1f m/s".format(it) } ?: "未知"} → 云速 ×${"%.2f".format(cloud.rate)}（目标 ×${"%.2f".format(windTarget)}）"
    /**
     * [38.9.11.2] smoke before / after for the 晴 「闪光」 fix: true = 38.9.11.1 behaviour (key-frame resolution follows the frame
     * governor level → both keys re-rendered at another size; earlier key = later key's clouds; still frames use the raw
     * sky time = a different sky; the sunny glow follows 4 point samples). Never set by the app itself.
     */
    @Volatile var legacySky: Boolean = false
        set(v) { field = v; synchronized(cloud) { cloud.legacy = v } }
    /** [38.9.11.2] cloud time for [t] without moving the clock (still frames, earlier key frames) */
    fun cloudPeek(t: Float): Float = synchronized(cloud) { cloud.at(t) }
    /** cloud clock for the page's sky time [t] (s); still frames use the sky time itself (deterministic) */
    fun cloudClock(c: android.content.Context, t: Float, rateScale: Float = 1f): Float = synchronized(cloud) {
        val now = System.currentTimeMillis()
        if (now - windAt > 60_000L || forcedWind != null) {
            windAt = now
            windMs = forcedWind?.takeIf { !it.isNaN() } ?: if (forcedWind != null) null
                else runCatching { com.openminis.app.plus.HomeGreeting.windNow(c)?.first }.getOrNull()
            windTarget = WindDriftCore.targetRate(windMs)
        }
        // [38.9.11.3] never slower than WindDriftCore.VISIBLE_MIN (the smoke's before-recording replays the old rate)
        cloud.advance(t, if (legacyDrift) windTarget * rateScale else WindDriftCore.effective(windTarget, rateScale))
    }
    /** [38.9.11.3] smoke before-recording only: 38.9.11.2 drift (old slow rate, keys only cross-faded, no shift) */
    @Volatile var legacyDrift: Boolean = false
    /** sky clock (s) for a wall-clock instant */
    fun skyTime(ms: Long): Float = WeatherSkyCore.skyTime(ms, java.util.TimeZone.getDefault().getOffset(ms).toLong())

    private var src: String? = null
    fun source(c: android.content.Context): String = src ?: c.assets.open("skybg/sky.agsl").bufferedReader().use { it.readText() }.also { src = it }

    // fallback stills (no shader): one per scene × theme, ~4 KB each, decoded on demand, at most 4 kept
    private val stills = object : LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 6
    }
    // [38.9.11.3 g] Perfetto (page-switch trace): BitmapFactory.decodeStream ran inside Record View#draw on the main thread
    // (decodeBitmap 6–29 ms, 8× per run) whenever a page switch drew a sky whose still had been evicted. Never decode on
    // the caller's thread now: a miss returns null (that layer skips one frame) and decodes on a low-priority thread → bump().
    private val stillExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sky-still").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 } }
    private val stillLoading = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun stillAsset(s: Scene, dark: Boolean) = "skybg/still_${s.key}_${if (dark) "d" else "l"}.webp"
    fun still(c: android.content.Context, s: Scene, dark: Boolean): android.graphics.Bitmap? {
        val asset = stillAsset(s, dark)
        return synchronized(stills) { stills[asset] } ?: run { prefetchStill(c, s, dark); null }
    }
    /** decode a still off the main thread (no-op when cached or already loading) */
    fun prefetchStill(c: android.content.Context, s: Scene, dark: Boolean) {
        val asset = stillAsset(s, dark)
        if (synchronized(stills) { stills.containsKey(asset) } || !stillLoading.add(asset)) return
        val app = c.applicationContext
        stillExec.execute {
            try {
                runCatching { app.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                    ?.let { synchronized(stills) { stills[asset] = it }; bump() }
            } finally { stillLoading.remove(asset) }
        }
    }

    /** Compiled shaders (per scene, lazily) + the four noise textures. A compile error is kept (→ stills) and reported. */
    @androidx.annotation.RequiresApi(33)
    class Gpu(c: android.content.Context) {
        private val seg = RainSky.segments(c, WeatherSkyCore.segments(source(c)))   // [3911c] 雨 v5 (skybg/rain.agsl) over the bundled rain
        private val tex: List<android.graphics.BitmapShader> = (0..3).map { i ->
            val o = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }
            val b = c.assets.open("skybg/tex$i.webp").use { android.graphics.BitmapFactory.decodeStream(it, null, o) } ?: error("tex$i")
            android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                .apply { filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR }
        }
        private val sky = HashMap<Scene, android.graphics.RuntimeShader>()
        private val p2 = HashMap<Scene, android.graphics.RuntimeShader>()
        private fun bind(sh: android.graphics.RuntimeShader) = tex.forEachIndexed { i, t -> runCatching { sh.setInputShader("tex$i", t) } }
        fun sky(s: Scene): android.graphics.RuntimeShader = sky.getOrPut(s) { android.graphics.RuntimeShader(WeatherSkyCore.skySource(seg, s)).also { bind(it) } }
        fun pass2(s: Scene): android.graphics.RuntimeShader? = if (s.pass2 == null) null
            else p2[s] ?: WeatherSkyCore.pass2Source(seg, s)?.let { android.graphics.RuntimeShader(it).also { sh -> bind(sh); p2[s] = sh } }
        /** compiles every scene once (so an error shows up at once, not mid-crossfade) */
        fun warm() { Scene.values().forEach { sky(it); pass2(it) } }

        private fun f(sh: android.graphics.RuntimeShader, n: String, vararg v: Float) = runCatching { sh.setFloatUniform(n, v) }
        /** reused for every shader record (a recording copies the paint) */
        val paint = android.graphics.Paint()
        private val blurs = java.util.WeakHashMap<android.graphics.RenderNode, Float>()
        fun blurOf(n: android.graphics.RenderNode): Float = blurs[n] ?: -1f
        fun setBlur(n: android.graphics.RenderNode, px: Float) { blurs[n] = px }
        fun uniforms(sh: android.graphics.RuntimeShader, s: Scene, dark: Boolean, chat: Boolean, w: Float, h: Float, t: Float, ct: Float = t,
                     calm: Float = 0f, hq: Float = 1f) {
            val g = WeatherSkyCore.grade(s, dark); val (lo, hi) = WeatherSkyCore.guard(chat, dark)
            f(sh, "iResolution", w, h); f(sh, "iTime", t); f(sh, "cloudT", ct); f(sh, "dark", if (dark) 1f else 0f)
            f(sh, "liftTint", ((g.tint shr 16) and 255) / 255f, ((g.tint shr 8) and 255) / 255f, (g.tint and 255) / 255f)
            f(sh, "lift", g.lift); f(sh, "mulc", *g.mul); f(sh, "desat", g.desat); f(sh, "gLo", lo); f(sh, "gHi", hi)
            f(sh, "starK", g.k2); f(sh, "gk", if (dark) 1f else 0f); f(sh, "calm", calm); f(sh, "hq", hq); f(sh, "sunFix", if (legacySky) 0f else 1f)
        }
    }
    @Volatile private var gpu: Any? = null
    // [Mines-38.9.10 i30] perf pass (user 「优化下」): degrade level (SkyPerfCore), software-GPU check, off-main preparation
    @Volatile var level: Int = 0
    @Volatile var software: Boolean? = null
    @Volatile var renderer: String = ""
    @Volatile private var preparing = false
    @Volatile var lastFrameMs: Float = 0f
    @Volatile var slowFrames: Long = 0
    @Volatile var measuredFrames: Long = 0
    fun perfLabel(): String = SkyPerfCore.label(level, software == true)
    /** [sky-e] true while a backdrop shows the shader sky that would animate at L0 (the only frames the governor reads) */
    val watchers = java.util.concurrent.atomic.AtomicInteger(0)
    val watching: Boolean get() = watchers.get() > 0
    /** [sky-e] the frame governor moved the level (down: slow render / hangs; up: recovered) */
    fun setLevel(to: Int, why: String) {
        val n = to.coerceIn(0, SkyPerfCore.MAX_AUTO)
        if (n == level) return
        android.util.Log.w("MinesSky", "${if (n > level) "degrade" else "recover"} → $n (${SkyPerfCore.label(n, false)}): $why")
        level = n; bump()
    }

    /** [38.9.11.1] smoke only: run the shader on the emulator's software GPU too (off = back to its stills) */
    fun forceGpu(c: android.content.Context, on: Boolean) {
        if (on) { software = false; shaderError = null; level = 0; prepare(c.applicationContext) } else software = true
        bump()
    }
    /** Never builds anything on the caller's thread: returns the prepared GPU object, or null (→ stills) and starts [prepare]. */
    fun gpuOrNull(c: android.content.Context): Any? {
        if (Build.VERSION.SDK_INT < 33 || shaderError != null || software == true || level >= SkyPerfCore.STILLS) return null
        gpu?.let { return it }
        if (!preparing) prepare(c.applicationContext)
        return null
    }

    /** off the main thread: GL_RENDERER check (software renderer → stills for good), textures, every scene's shader parsed */
    fun prepare(c: android.content.Context) {
        if (preparing || Build.VERSION.SDK_INT < 33) return
        preparing = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                if (software == null) {
                    val r = glRenderer(c); renderer = r ?: ""
                    software = SkyPerfCore.isSoftwareRenderer(r)
                    // [sky-e] a known phone GPU (Mali / Immortalis / Adreno …) is never auto-degraded past L1
                    val cap = SkyPerfCore.maxAuto(r)
                    SkyFrameWatch.handler.post { SkyFrameWatch.degrader.maxAuto = cap; if (level > cap) setLevel(cap, "hardware GPU '$r'") }
                    if (software == true) { android.util.Log.w("MinesSky", "software GL renderer '$r' → stills"); bump(); return@launch }
                }
                val g = runCatching { Gpu(c).also { it.warm() } }
                    .onFailure { shaderError = (it.message ?: it.javaClass.simpleName).take(300) }.getOrNull()
                if (g != null) gpu = g
                bump()
            } finally { preparing = false }
        }
    }

    /** GLES20.GL_RENDERER from a 1×1 pbuffer context (cached per system build) */
    private fun glRenderer(c: android.content.Context): String? {
        val sp = c.getSharedPreferences("mines_sky", 0); val fp = Build.FINGERPRINT
        if (sp.getString("gl_fp", null) == fp) sp.getString("gl_renderer", null)?.let { return it }
        val r = runCatching {
            val d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)
            val cfg = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
            android.opengl.EGL14.eglChooseConfig(d, intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            val ctx = android.opengl.EGL14.eglCreateContext(d, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT,
                intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
            val surf = android.opengl.EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1,
                android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
            android.opengl.EGL14.eglMakeCurrent(d, surf, surf, ctx)
            val s = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
            android.opengl.EGL14.eglDestroySurface(d, surf); android.opengl.EGL14.eglDestroyContext(d, ctx)
            s
        }.getOrNull()
        if (r != null) sp.edit().putString("gl_fp", fp).putString("gl_renderer", r).apply()
        return r
    }
}

/**
 * [i30 → sky-e] FrameMetrics of the window while the shader sky is shown → [SkyPerfCore.Degrader]. Build d read
 * TOTAL_DURATION (input / animation / layout / unknown delay of the whole window) and froze the sky on a real 120 Hz phone;
 * now only the render cost (draw + sync + command issue + swap, + GPU on API 31+) of non-first frames, deduplicated by
 * vsync (home + chat backdrops can both be registered), skipped while a glass overlay is open or the page is held.
 */
internal object SkyFrameWatch {
    private val thread by lazy { android.os.HandlerThread("mines-sky-frames").also { it.start() } }
    val handler by lazy { android.os.Handler(thread.looper) }
    val degrader = SkyPerfCore.Degrader()
    /** frames ignored after (re)registering: launch / page-open frames are slow everywhere */
    @Volatile var skip = 0
    private var lastVsync = -1L
    fun listener(displayVsyncMs: Float) = android.view.Window.OnFrameMetricsAvailableListener { _, fm, _ ->
        if (skip > 0) { skip--; return@OnFrameMetricsAvailableListener }
        if (fm.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val vs = fm.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        if (vs == lastVsync) return@OnFrameMetricsAvailableListener
        lastVsync = vs
        if (!WeatherSky.watching || GlassBackdrop.active.value || System.currentTimeMillis() < FogClock.holdUntil) return@OnFrameMetricsAvailableListener
        val gpuNs = if (Build.VERSION.SDK_INT >= 31) fm.getMetric(android.view.FrameMetrics.GPU_DURATION) else -1L
        val ms = SkyPerfCore.renderCostMs(fm.getMetric(android.view.FrameMetrics.DRAW_DURATION), fm.getMetric(android.view.FrameMetrics.SYNC_DURATION),
            fm.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION), fm.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION), gpuNs)
        val deadline = if (Build.VERSION.SDK_INT >= 31) fm.getMetric(android.view.FrameMetrics.DEADLINE) / 1_000_000f else 0f
        val vsync = if (deadline in 4f..50f) deadline else displayVsyncMs
        WeatherSky.lastFrameMs = ms; WeatherSky.measuredFrames++
        if (ms > SkyPerfCore.slowMs(vsync)) WeatherSky.slowFrames++
        val before = degrader.level
        val after = degrader.feed(ms, vsync, android.os.SystemClock.elapsedRealtime())
        if (after != before) WeatherSky.setLevel(after, degrader.lastWhy)
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/**
 * [Mines-38.9.11.1 hotfix] user on 38.9.11 (realme Neo7 Turbo): 「雾好卡」「比之前卡太多了」 → slow, gentle, light.
 * What was heavy: the procedural shader was re-run for EVERY sky frame (12–15 fps, home and chat each), the 夜/雪 pass-2
 * node had no cached layer (re-run on every window frame while scrolling), the sky Canvas shared its Compose layer with
 * the page (each sky frame re-recorded the whole chat page on the UI thread), the frame loop woke on every vsync (120 Hz),
 * and per-frame Paint / BitmapShader / RectF / RenderEffect allocations.
 * Now ([SkyModeCore]): key frames of the shader in cached GPU layers, cross-faded at ≤ 30 fps (流畅) / 40 fps (精美);
 * 雨 / 雪 keep the live path; only the newest sky animates (chat over home); the sky has its own graphics layer; the loop
 * sleeps between frames; 关闭 / battery saver / reduce motion = one still key frame.
 */
private class SkyKeys {
    var scene: Scene? = null; var dark = false; var chat = false; var w = 0f; var h = 0f; var div = 0f; var blur = 0f
    var calm = -1f; var hq = -1f
    // [38.9.11.2] three key slots: the B-spline blend uses keys k-1, k, k+1 (the 38.9.11.1 linear blend used two)
    val sky = arrayOfNulls<Any>(3); val p2 = arrayOfNulls<Any>(3)
    val t = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
    /** [38.9.11.3] cloud time each key was rendered at (drift shift) + the side margin (px of the key node) */
    val ct = FloatArray(3); var mq = 0; var margin = -1f
    /** [38.9.11.3] the last animated composite (slots, alphas, shifts): a still frame re-draws exactly this (no jump) */
    val lastSlot = IntArray(3) { -1 }; val lastAl = FloatArray(3); val lastDx = FloatArray(3); val lastKt = LongArray(3); var lastValid = false
    /** live path (雨 / 雪) nodes: [sky, sky-prev, p2, p2-prev] */
    val live = arrayOfNulls<Any>(4)
    fun reset() { t[0] = Long.MIN_VALUE; t[1] = Long.MIN_VALUE; t[2] = Long.MIN_VALUE; lastValid = false }
}

@Composable
fun WeatherSkyBackdrop(modifier: Modifier = Modifier, /** [Mines-38.9.9] chat page: stronger text guard + veil */ chat: Boolean = false) {
    val ctx = LocalContext.current
    val m = mines()
    val gTick by com.openminis.app.plus.HomeGreeting.tick.collectAsState()
    val sTick by WeatherSky.tick.collectAsState()
    // re-picked every minute (weather / time of day)
    val minuteTick by androidx.compose.runtime.produceState(0) { while (true) { kotlinx.coroutines.delay(60_000L); value++ } }
    val scene = remember(gTick, sTick, minuteTick) { WeatherSky.scene(ctx) }
    // crossfade from the previous scene over 1.5 s
    var prev by remember { mutableStateOf<Scene?>(null) }
    var cur by remember { mutableStateOf(scene) }
    val fadeStart = remember { mutableLongStateOf(0L) }
    if (scene != cur) { prev = cur; cur = scene; fadeStart.longValue = System.currentTimeMillis() }
    // [i27] battery saver follows the system live (broadcast), reduce-motion re-read every minute
    val powerSave by MotionGate.powerSave(ctx)
    val animOff = remember(sTick, minuteTick) { AnimeWeather.still(ctx) }
    val gpu = remember(sTick) { WeatherSky.gpuOrNull(ctx) }
    // [38.9.11.3 g] stills path: both themes of the current scene decoded ahead, off the main thread
    if (gpu == null) androidx.compose.runtime.SideEffect { WeatherSky.prefetchStill(ctx, cur, false); WeatherSky.prefetchStill(ctx, cur, true) }
    val level = remember(sTick) { WeatherSky.level }
    // [38.9.11.1] 天空动画 流畅 / 精美 / 关闭 (+ saver / reduce motion / forced still → 关闭)
    val chosen = remember(sTick) { WeatherSky.mode(ctx) }
    val mode = SkyModeCore.effective(chosen, powerSave, animOff, WeatherSky.forceStatic)
    val mp = SkyModeCore.params(mode)
    // [38.9.11.1] one live sky: the newest shown backdrop animates, older ones (home under the chat) hold a still key frame
    var token by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val tk = WeatherSky.registry.enter(); token = tk; WeatherSky.liveToken.value = WeatherSky.registry.live()
        onDispose { WeatherSky.registry.leave(tk); WeatherSky.liveToken.value = WeatherSky.registry.live() }
    }
    val liveTok by WeatherSky.liveToken.collectAsState()
    val isLive = token != 0L && liveTok == token
    val liveScene = SkyModeCore.liveScene(cur.key)
    // [38.9.11.3] the sky never stops on its own: the frame governor's level 2 only stills the expensive live path (雨 / 雪);
    // key-frame scenes (≈ one small shader frame per 1.2 s) keep drifting. 关闭 / battery saver / reduce motion still stop it.
    // [38.9.11.3 h] 「所有壁纸都不会动」: no shader (stills) / governor-calmed 雨雪 / battery saver / animator scale 0 keep a low
    // frame rate instead of 0 — only 关闭 (or an older sky under the newest) is still
    val fps = SkyModeCore.loopFps(mode, liveScene, isLive)
        .let { f -> if (f > 0 && (gpu == null || (level >= 2 && liveScene))) minOf(f, SkyModeCore.FALLBACK_FPS) else f }
        .let { f -> SkyModeCore.lowPowerFps(f, powerSave, animOff) }
        .let { f -> WeatherSky.fpsCap.let { cap -> if (cap > 0 && f > 0) minOf(f, cap) else f } }
    // [sky-e] the governor watches whenever this sky animates (also at L1, so it can recover)
    val watch = gpu != null && isLive && SkyModeCore.loopFps(mode, liveScene, true) > 0
    WeatherSky.lastScene = cur
    WeatherSky.lastMode = WeatherSkyCore.mode(Build.VERSION.SDK_INT, WeatherSky.shaderError.takeIf { gpu == null } ?: (if (gpu == null) "unavailable" else null),
        powerSave, animOff, WeatherSky.forceStatic) + " · " + WeatherSky.perfLabel() + " · 天空动画 " + chosen.label +
        (if (mode != chosen) "→" + mode.label else "") + (if (isLive) " · live" else " · held") + " · ${fps}fps"
    // virtual clock: the wall clock (every page shows the same sky) minus the time the page was held still
    val heldMs = remember { mutableLongStateOf(0L) }
    val frameNow = remember { mutableLongStateOf(WeatherSky.now()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val fading = prev != null
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, watch) {
        val win = if (watch) view.context.findActivity()?.window else null
        val hz = runCatching { view.display?.refreshRate ?: 60f }.getOrDefault(60f).let { if (it in 20f..240f) it else 60f }
        val l = if (win != null) SkyFrameWatch.listener(1000f / hz) else null
        if (win != null && l != null) runCatching {
            WeatherSky.watchers.incrementAndGet()
            SkyFrameWatch.handler.post { SkyFrameWatch.degrader.resetWindow() }
            SkyFrameWatch.skip = 60; win.addOnFrameMetricsAvailableListener(l, SkyFrameWatch.handler)
        }
        onDispose { if (win != null && l != null) runCatching { win.removeOnFrameMetricsAvailableListener(l); WeatherSky.watchers.decrementAndGet() } }
    }
    // [38.9.11.1] the loop SLEEPS between frames (was: woke on every vsync, 120 Hz) and parks while an overlay is open or
    // the app is not RESUMED; the parked time is added to the held clock so the sky resumes where it stopped.
    if (fps > 0 || fading) LaunchedEffect(owner, fps, fading) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var lastWall = 0L
            val step = 1000L / (if (fps > 0) fps else WeatherSkyCore.FPS)
            while (true) {
                val parked = MotionGate.awaitRun()
                if (parked > 0L) { heldMs.longValue += parked; lastWall = 0L }
                val busyStep = if (System.currentTimeMillis() < WeatherSky.busyUntil && fps > 0) 1000L / SkyModeCore.busyFps(fps) else step
                kotlinx.coroutines.delay((busyStep - 4L).coerceAtLeast(1L))
                val more = androidx.compose.runtime.withFrameMillis { _ ->
                    val wall = System.currentTimeMillis()
                    val dt = if (lastWall == 0L) 0L else (wall - lastWall).coerceAtMost(500L)
                    lastWall = wall
                    // still while a page is being scrolled
                    // [38.9.11.2] also still while the screen is read / captured, another window is on top, or 1 s after input
                    // [38.9.11.3] user 2026-10-21: the sky must NOT stop — no hold on scroll / touch / a11y / capture / split screen /
                    // overlays (38.9.11.2 froze it while any accessibility service was on → Q仔 kept it frozen for good).
                    // Only the smoke's before-recording replays the 38.9.11.2 rule (SkyFreeze.legacyFreeze).
                    if (SkyFreeze.frozen(wall)) heldMs.longValue += dt
                    if (prev != null && wall - fadeStart.longValue > WeatherSkyCore.FADE_MS) prev = null
                    frameNow.longValue = WeatherSky.now() - heldMs.longValue
                    fps > 0 || prev != null
                }
                if (!more) break
            }
        }
    }
    val dark = m.dark
    val grainShader = remember { android.graphics.BitmapShader(skyGrain(), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG) }
    val grainPaint = remember { android.graphics.Paint().apply { shader = grainShader; alpha = 8 } }
    val rect = remember { android.graphics.RectF() }
    val keys = remember { arrayOf(SkyKeys(), SkyKeys()) }
    // [38.9.11.1] own graphics layer: a sky frame no longer re-records the page drawn over it
    Box(modifier.graphicsLayer {}) {
        Canvas(Modifier.fillMaxSize()) {
            // sky clock (ms): forced still (smoke) › still (关闭 / held page) › the page's virtual clock
            val nowMs: Long = when {
                WeatherSky.forceStatic -> ((WeatherSkyCore.STILL_T + WeatherSky.clockOffsetMs / 1000f) * 1000f).toLong()
                fps == 0 && !fading -> (WeatherSky.skyTime(WeatherSky.now() - heldMs.longValue) * 1000f).toLong()
                else -> (WeatherSky.skyTime(frameNow.longValue) * 1000f).toLong()
            }
            WeatherSky.frames++
            val w = size.width; val h = size.height
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                val fadeK = prev?.let { ((System.currentTimeMillis() - fadeStart.longValue).toFloat() / WeatherSkyCore.FADE_MS).coerceIn(0f, 1f) } ?: 1f
                fun layer(s: Scene, alpha: Float, slot: Int) {
                    val g = gpu
                    if (g != null && Build.VERSION.SDK_INT >= 33 && nc.isHardwareAccelerated) {
                        val ok = runCatching { drawSkyMode(ctx, nc, g as WeatherSky.Gpu, s, dark, chat, w, h, nowMs, alpha, keys[slot], slot, level, mode, mp, fps > 0, isLive) }
                        ok.exceptionOrNull()?.let { WeatherSky.shaderError = (it.message ?: it.javaClass.simpleName).take(300); WeatherSky.bump() }
                        return
                    }
                    val b = WeatherSky.still(ctx, s, dark) ?: return
                    paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                    // [38.9.11.3 h] stills path drifts too: a visible pan of the still (±6 % of the width over ~40 s), 7 % overscan
                    val ph = nowMs / 1000.0 * 2.0 * Math.PI / 40.0
                    val dx = (kotlin.math.sin(ph) * 0.06 * w).toFloat(); val dy = (kotlin.math.cos(ph * 0.7) * 0.03 * h).toFloat()
                    rect.set(-0.07f * w + dx, -0.07f * h + dy, 1.07f * w + dx, 1.07f * h + dy)
                    nc.drawBitmap(b, null, rect, paint)
                    paint.alpha = 255
                    WeatherSky.lastShot = "still_${s.key}_${if (dark) "d" else "l"}"
                }
                val p = prev
                if (p != null && fadeK < 1f) { layer(p, 1f, 1); layer(cur, fadeK, 0) } else layer(cur, 1f, 0)
                // chat page: a veil of the page colour (light over the guarded shader sky, thicker over a still)
                if (chat) {
                    val (v, rgb) = if (gpu != null) WeatherSkyCore.chatVeilColor(cur, dark) else WeatherSkyCore.stillChatVeil(cur, dark)
                    if (v > 0f) nc.drawColor(android.graphics.Color.argb((255 * v).toInt(), (rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255))
                }
                // static film grain at ~3 %
                nc.drawRect(0f, 0f, w, h, grainPaint)
            }
        }
    }
}

/**
 * [38.9.11.1] one scene in the chosen 天空动画 mode. Key-frame scenes: two cached key frames around [nowMs] (rendered only when
 * the grid moves), cross-faded. 雨 / 雪: the live path ([drawSky]) every loop frame. A held / still page draws key a only.
 */
@androidx.annotation.RequiresApi(33)
private fun drawSkyMode(ctx: android.content.Context, nc: android.graphics.Canvas, g: WeatherSky.Gpu, s: Scene, dark: Boolean, chat: Boolean,
                        w: Float, h: Float, nowMs: Long, alpha: Float, k: SkyKeys, slot: Int, level: Int, mode: SkyModeCore.Mode,
                        mp: SkyModeCore.Params, animating: Boolean, isLive: Boolean) {
    val t = nowMs / 1000f
    if (SkyModeCore.liveScene(s.key)) {
        val ct = if (animating) WeatherSky.cloudClock(ctx, t, mp.cloudRate) else t
        drawSky(nc, g, s, dark, chat, w, h, t, alpha, k.live, slot, level, ct, SkyModeCore.skyDiv(mode, level), mp.blurPx, mp.calm, mp.hq)
        WeatherSky.lastShot = "v7.1 live ${s.key} t=${"%.1f".format(t)} ct=${"%.1f".format(ct)} ${mode.key}"
        return
    }
    // [38.9.11.2] key frames keep one resolution per mode: a governor level change no longer re-renders both keys at another
    // size (a visible pop / blur flash); the key path costs ≈ 1 shader frame per key anyway. 雨 / 雪 (live path) still degrade.
    val div = if (WeatherSky.legacySky) SkyModeCore.skyDiv(mode, level) else SkyModeCore.skyDiv(mode, 0)
    val margin = if (WeatherSky.legacySky || WeatherSky.legacyDrift) 0f else SkyModeCore.marginUnits(s.key, mode, chat)
    if (k.scene != s || k.dark != dark || k.chat != chat || k.w != w || k.h != h || k.div != div || k.blur != mp.blurPx || k.calm != mp.calm || k.hq != mp.hq || k.margin != margin) {
        k.scene = s; k.dark = dark; k.chat = chat; k.w = w; k.h = h; k.div = div; k.blur = mp.blurPx; k.calm = mp.calm; k.hq = mp.hq; k.margin = margin; k.reset()
    }
    // [38.9.11.3 f] HWUI's destroyHardwareResources (window hidden / trim memory / surface change) deletes the display lists of
    // these cached key nodes too; they then draw NOTHING until replaced — smoke saw the dark fog vanish to the page colour for
    // 1.5 s and fade back over 3 keys. A key whose node lost its recording counts as not rendered and is recorded again.
    for (i in 0..2) if (k.t[i] != Long.MIN_VALUE) {
        val n = k.sky[i] as? android.graphics.RenderNode
        val n3 = k.p2[i] as? android.graphics.RenderNode
        if (n == null || !n.hasDisplayList() || (g.pass2(s) != null && (n3 == null || !n3.hasDisplayList()))) {
            k.t[i] = Long.MIN_VALUE; k.lastValid = false; WeatherSky.keyLost++
        }
    }
    val qw = kotlin.math.ceil(w / div).toInt().coerceAtLeast(1); val qh = kotlin.math.ceil(h / div).toInt().coerceAtLeast(1)
    // [38.9.11.3] side margin in key pixels (the shader's 270 units span qw)
    val mq = kotlin.math.ceil(margin * qw / 270f).toInt(); k.mq = mq
    val still = !animating
    // [38.9.11.3] a still frame (关闭 / battery saver / governor on 雨雪 / held page) right after the sky moved re-draws the exact
    // last composite — 38.9.11.2 switched to the single nearest key, a 4.5–6.2 % brightness jump
    if (still && !WeatherSky.forceStatic && !WeatherSky.legacySky && k.lastValid && (0..2).all { j -> k.lastSlot[j] in 0..2 && k.t[k.lastSlot[j]] == k.lastKt[j] }) {
        for (j in 0..2) {
            val n = k.sky[k.lastSlot[j]] as? android.graphics.RenderNode ?: continue
            val a = if (j == 0) alpha else alpha * k.lastAl[j]
            if (a <= 0.003f) continue
            n.alpha = a
            nc.save(); nc.translate(k.lastDx[j], 0f); nc.scale(w / qw, h / qh); nc.translate(-mq.toFloat(), 0f); nc.drawRenderNode(n); nc.restore()
            val n3 = k.p2[k.lastSlot[j]] as? android.graphics.RenderNode
            if (n3 != null && g.pass2(s) != null) { n3.alpha = a; nc.save(); nc.scale(w / n3.width, h / n3.height); nc.drawRenderNode(n3); nc.restore() }
        }
        WeatherSky.lastShot = "v7.3 held-exact ${s.key} ${mode.key}"
        return
    }
    val step = SkyModeCore.keyMs(mode, chat)
    // a still page: the smoke's forced instant › a live 关闭 page = the minute's key › a held page (home under the chat)
    // keeps the newest key it already has (no re-render)
    val kk = if (still) {
        val have = k.t.any { it != Long.MIN_VALUE }
        val target = when {
            WeatherSky.forceStatic -> nowMs
            // [38.9.11.2] animated → still (governor, freeze): keep the key nearest to now instead of jumping to the minute's key
            !WeatherSky.legacySky && have && k.t.filter { it != Long.MIN_VALUE }.minOf { kotlin.math.abs(it - nowMs) } <= 3 * SkyModeCore.keyMs(mode, chat) ->
                k.t.filter { it != Long.MIN_VALUE }.minByOrNull { kotlin.math.abs(it - nowMs) }!!
            isLive || !have -> Math.floorDiv(nowMs, 60_000L) * 60_000L
            else -> k.t.maxOrNull() ?: nowMs
        }
        SkyModeCore.Keys(target, target, 0f)
    } else SkyModeCore.keys(nowMs, step)
    fun find(tk: Long) = k.t.indices.firstOrNull { k.t[it] == tk } ?: -1
    fun render(i: Int, tk: Long) {
        val tt = tk / 1000f
        // [38.9.11.2] a still frame shows the clouds the animated sky had (38.9.11.1: raw sky time = a different sky, so going
        // back to home / a governor still flashed the whole sky); the smoke's forced instant stays deterministic
        val ct = when {
            !still -> WeatherSky.cloudClock(ctx, tt, mp.cloudRate)
            WeatherSky.legacySky || WeatherSky.forceStatic -> tt
            else -> WeatherSky.cloudPeek(tt)
        }
        val n = (k.sky[i] as? android.graphics.RenderNode) ?: android.graphics.RenderNode("mines-skykey-$slot-$i").also { k.sky[i] = it }
        n.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(mp.blurPx, mp.blurPx, android.graphics.Shader.TileMode.CLAMP))
        n.setUseCompositingLayer(true, null)
        n.setPosition(0, 0, qw + 2 * mq, qh)
        val sh = g.sky(s)
        g.uniforms(sh, s, dark, chat, qw.toFloat(), qh.toFloat(), tt, ct, mp.calm, mp.hq)
        // [38.9.11.3] the key is rendered mq px wider on both sides (the shader's coordinates continue past the screen edge)
        val rc = n.beginRecording(qw + 2 * mq, qh)
        try { rc.translate(mq.toFloat(), 0f); g.paint.shader = sh; rc.drawPaint(g.paint) } finally { g.paint.shader = null; n.endRecording() }
        k.ct[i] = ct
        val p2 = g.pass2(s)
        if (p2 != null) {
            val d2 = SkyPerfCore.pass2Div(level)
            val hw = kotlin.math.ceil(w / d2).toInt().coerceAtLeast(1); val hh = kotlin.math.ceil(h / d2).toInt().coerceAtLeast(1)
            val n3 = (k.p2[i] as? android.graphics.RenderNode) ?: android.graphics.RenderNode("mines-skykey-p2-$slot-$i").also { k.p2[i] = it }
            n3.setUseCompositingLayer(true, null)
            n3.setPosition(0, 0, hw, hh)
            g.uniforms(p2, s, dark, chat, hw.toFloat(), hh.toFloat(), tt, ct, mp.calm, mp.hq)
            val r3 = n3.beginRecording(hw, hh)
            try { g.paint.shader = p2; r3.drawPaint(g.paint) } finally { g.paint.shader = null; n3.endRecording() }
        }
        k.t[i] = tk
        WeatherSky.keyRenders++
    }
    fun put(i: Int, a: Float, dx: Float = 0f) {
        if (a <= 0.003f) return
        val n = k.sky[i] as? android.graphics.RenderNode ?: return
        n.alpha = a
        nc.save(); nc.translate(dx, 0f); nc.scale(w / qw, h / qh); nc.translate(-mq.toFloat(), 0f); nc.drawRenderNode(n); nc.restore()
        val n3 = k.p2[i] as? android.graphics.RenderNode
        if (n3 != null && g.pass2(s) != null) {
            n3.alpha = a
            nc.save(); nc.scale(w / n3.width, h / n3.height); nc.drawRenderNode(n3); nc.restore()
        }
    }
    // [38.9.11.2] animated, not legacy: quadratic B-spline over keys (k-1, k, k+1) — C1-continuous brightness, no pulse at
    // the key rate (the linear blend's slope jumped every key). Composited as k-1 opaque, k at a1, k+1 at a2.
    if (!still && !WeatherSky.legacySky) {
        val a = Math.floorDiv(nowMs, step) * step
        val u = ((nowMs - a).toFloat() / step).coerceIn(0f, 1f)
        val need = longArrayOf(a - step, a, a + step)
        val al = SkyFreezeCore.layerAlphas(u)
        val slot = IntArray(3) { find(need[it]) }
        for (j in 0..2) if (slot[j] < 0) {
            val free = k.t.indices.first { i -> i !in slot.toList() && k.t[i] !in need }
            render(free, need[j]); slot[j] = free
        }
        // [38.9.11.3] each key slides by the drift since its cloud time → the mist floats instead of dissolving in place
        val nowCt = if (margin > 0f) WeatherSky.cloudPeek(t) else 0f
        val dx = FloatArray(3) { j -> if (margin > 0f) SkyModeCore.shiftPx(s.key, nowCt, k.ct[slot[j]], w, margin) else 0f }
        put(slot[0], alpha, dx[0]); put(slot[1], alpha * al[1], dx[1]); put(slot[2], alpha * al[2], dx[2])
        for (j in 0..2) { k.lastSlot[j] = slot[j]; k.lastKt[j] = k.t[slot[j]]; k.lastDx[j] = dx[j] }
        k.lastAl[0] = 1f; k.lastAl[1] = al[1]; k.lastAl[2] = al[2]; k.lastValid = true
        WeatherSky.lastDrift = "dx=${"%.1f".format(dx[0])}/${"%.1f".format(dx[1])}/${"%.1f".format(dx[2])} ct=${"%.2f".format(nowCt)}"
        WeatherSky.lastShot = "v7.2 key3 ${s.key} t=${"%.1f".format(t)} keys=${need[0] / 1000f}..${need[2] / 1000f} u=${"%.2f".format(u)} ${mode.key}${s.pass2?.let { " +$it" } ?: ""}"
        return
    }
    if (!still && k.t[2] != Long.MIN_VALUE) k.reset()   // legacy (smoke before/after) uses slots 0 / 1 only
    var ia = find(kk.a); var ib = if (kk.b == kk.a) ia else find(kk.b)
    if (ia < 0 && ib < 0) { render(0, kk.a); ia = 0; if (kk.b != kk.a) { render(1, kk.b); ib = 1 } else ib = 0 }
    else if (ia < 0) { ia = 1 - ib; render(ia, kk.a) }
    else if (ib < 0) { ib = 1 - ia; render(ib, kk.b) }
    put(ia, alpha)
    if (ib != ia) put(ib, alpha * kk.k)
    WeatherSky.lastShot = "v7.1 key ${s.key} t=${"%.1f".format(t)} keys=${kk.a / 1000f}/${kk.b / 1000f} k=${"%.2f".format(kk.k)} ${mode.key}${s.pass2?.let { " +$it" } ?: ""}"
}

/** one scene: pass 1 (1/div res, blurred) + pass 2 (stars / snow, or 雨 glass at half res over the sky) — the live path */
@androidx.annotation.RequiresApi(33)
private fun drawSky(nc: android.graphics.Canvas, g: WeatherSky.Gpu, s: Scene, dark: Boolean, chat: Boolean, w: Float, h: Float, t: Float,
                    alpha: Float, nodes: Array<Any?>, slot: Int, level: Int = 0, ct: Float = t, dv: Float = SkyPerfCore.skyDiv(level),
                    blurPx: Float = 1.5f, calm: Float = 0f, hq: Float = 1f) {
    val d2 = SkyPerfCore.pass2Div(level)
    val qw = kotlin.math.ceil(w / dv).toInt().coerceAtLeast(1); val qh = kotlin.math.ceil(h / dv).toInt().coerceAtLeast(1)
    val n1 = (nodes[slot] as? android.graphics.RenderNode) ?: android.graphics.RenderNode("mines-sky-$slot").also {
        it.setUseCompositingLayer(true, null); nodes[slot] = it
    }
    if (g.blurOf(n1) != blurPx) { n1.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)); g.setBlur(n1, blurPx) }
    n1.setPosition(0, 0, qw, qh)
    val sh = g.sky(s)
    g.uniforms(sh, s, dark, chat, qw.toFloat(), qh.toFloat(), t, ct, calm, hq)
    val rc = n1.beginRecording(qw, qh)
    try { g.paint.shader = sh; rc.drawPaint(g.paint) } finally { g.paint.shader = null; n1.endRecording() }
    WeatherSky.keyRenders++
    val p2 = g.pass2(s)
    if (s == Scene.RAIN && p2 != null) {
        val hw = kotlin.math.ceil(w / d2).toInt().coerceAtLeast(1); val hh = kotlin.math.ceil(h / d2).toInt().coerceAtLeast(1)
        val n2 = (nodes[2 + slot] as? android.graphics.RenderNode) ?: android.graphics.RenderNode("mines-glass-$slot").also { it.setUseCompositingLayer(true, null); nodes[2 + slot] = it }
        n2.setPosition(0, 0, hw, hh)
        g.uniforms(p2, s, dark, chat, hw.toFloat(), hh.toFloat(), t, ct, calm, hq)
        // the effect captures the shader's uniforms when it is made → a new one per (live) frame
        n2.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(p2, "sky"))
        n1.alpha = 1f
        val r2 = n2.beginRecording(hw, hh)
        try { r2.save(); r2.scale(hw.toFloat() / qw, hh.toFloat() / qh); r2.drawRenderNode(n1); r2.restore() } finally { n2.endRecording() }
        n2.alpha = alpha
        nc.save(); nc.scale(w / hw, h / hh); nc.drawRenderNode(n2); nc.restore()
        return
    }
    n1.alpha = alpha
    nc.save(); nc.scale(w / qw, h / qh); nc.drawRenderNode(n1); nc.restore()
    if (p2 != null) {
        val hw = kotlin.math.ceil(w / d2).toInt().coerceAtLeast(1); val hh = kotlin.math.ceil(h / d2).toInt().coerceAtLeast(1)
        val n3 = (nodes[2 + slot] as? android.graphics.RenderNode) ?: android.graphics.RenderNode("mines-p2-$slot").also { it.setUseCompositingLayer(true, null); nodes[2 + slot] = it }
        n3.setPosition(0, 0, hw, hh)
        g.uniforms(p2, s, dark, chat, hw.toFloat(), hh.toFloat(), t, ct, calm, hq)
        val r3 = n3.beginRecording(hw, hh)
        try { g.paint.shader = p2; r3.drawPaint(g.paint) } finally { g.paint.shader = null; n3.endRecording() }
        n3.alpha = alpha
        nc.save(); nc.scale(w / hw, h / hh); nc.drawRenderNode(n3); nc.restore()
    }
}

/** 128 px monochrome noise tile (grain). */
private fun skyGrain(): android.graphics.Bitmap {
    val n = 128; val px = IntArray(n * n); val rnd = java.util.Random(11)
    for (i in px.indices) { val v = 96 + rnd.nextInt(64); px[i] = android.graphics.Color.argb(255, v, v, v) }
    return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
}

/** [Mines-38.9.9] user: the same weather sky on every page — the chat page (no chat wallpaper) gets it with a thicker veil. */
@Composable
fun chatSkyOn(): Boolean {
    val ctx = LocalContext.current
    val t by WeatherSky.tick.collectAsState()
    return remember(t) { WeatherSky.enabled(ctx) } && !wallpaperOn()
}

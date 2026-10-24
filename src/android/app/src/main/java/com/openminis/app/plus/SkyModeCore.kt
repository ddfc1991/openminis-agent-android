package com.openminis.app.plus

/**
 * [Mines-38.9.11.1 hotfix] 设置 › 外观调节 › 天空动画 (user 2026-10-20 on 38.9.11, realme Neo7 Turbo: 「雾好卡」「比之前卡太多了」,
 * then 「像早期那样慢、柔和：光影 + 云慢慢飘，不要偏激的效果」). Pure, unit-tested (SkyModeCoreTest).
 *
 * How the sky is drawn now (instead of re-running the procedural shader on every frame):
 *  - the shader renders a small KEY FRAME (1/[skyDiv] resolution, blurred) into a cached GPU layer every [keyMs];
 *  - every display frame (≤ [blendFps]) only cross-fades the two cached key frames around "now" → smooth, slow motion at
 *    the cost of two textured quads; the shader itself runs ≈ 1000 / [keyMs] times a second;
 *  - 雨 / 雪 (moving drops / flakes would ghost in a cross-fade) keep the live path at [liveFps];
 *  - only ONE sky animates at a time (the newest page: chat over home); the chat page updates [chatKeyFactor]× slower;
 *  - 关闭 / battery saver / reduce motion → one still key frame, refreshed once a minute.
 * [calm] tones the scene down (subtler fog, gentler contrast, slower drift: [cloudRate] × the wind rate); [hq] = full fog
 * layers + light shafts (0 = two fog layers, cheap glow instead of shafts).
 */
object SkyModeCore {
    enum class Mode(val key: String, val label: String) { SMOOTH("smooth", "流畅"), RICH("rich", "精美"), OFF("off", "关闭") }

    data class Params(
        val keyMs: Long,
        val blendFps: Int,
        val liveFps: Int,
        val skyDiv: Float,
        val blurPx: Float,
        val calm: Float,
        val hq: Float,
        val cloudRate: Float,
        val chatKeyFactor: Float,
    )

    val DEFAULT = Mode.SMOOTH

    fun parse(key: String?): Mode = Mode.values().firstOrNull { it.key == key } ?: DEFAULT

    fun params(m: Mode): Params = when (m) {
        Mode.SMOOTH -> Params(keyMs = 1200L, blendFps = 30, liveFps = 12, skyDiv = 5f, blurPx = 2.2f, calm = 1f, hq = 0f, cloudRate = 0.6f, chatKeyFactor = 2f)
        Mode.RICH -> Params(keyMs = 300L, blendFps = 30,   // [38.9.11.3] capped at 30 fps (was 40)
            liveFps = 15, skyDiv = 4f, blurPx = 1.5f, calm = 0.5f, hq = 1f, cloudRate = 0.8f, chatKeyFactor = 1.5f)
        Mode.OFF -> Params(keyMs = 60_000L, blendFps = 0, liveFps = 0, skyDiv = 4f, blurPx = 2.2f, calm = 1f, hq = 0f, cloudRate = 0.6f, chatKeyFactor = 1f)
    }

    /** the effective mode: only a forced still (smoke) behaves like 关闭. [38.9.11.3 h] user 2026-10-21 「所有壁纸都不会动」
     *  (battery saver off): battery saver / animator scale 0 (realme sets it) no longer stop the sky — see [lowPowerFps] */
    fun effective(chosen: Mode, powerSave: Boolean, reduceMotion: Boolean, forcedStatic: Boolean): Mode =
        if (forcedStatic) Mode.OFF else chosen

    /** [38.9.11.3 h] battery saver / animator scale 0: keep drifting at ≤ this frame rate (never 0 unless 关闭) */
    const val LOW_POWER_FPS = 10
    fun lowPowerFps(fps: Int, powerSave: Boolean, reduceMotion: Boolean): Int =
        if ((powerSave || reduceMotion) && fps > 0) minOf(fps, LOW_POWER_FPS) else fps
    /** [38.9.11.3 h] frame rate when the shader is unavailable (stills path: the still pans slowly) or the governor
     *  calmed 雨 / 雪 (level ≥ 2): low, never 0 */
    const val FALLBACK_FPS = 8

    /** key-frame spacing for a page (chat updates slower: it sits behind text) */
    fun keyMs(m: Mode, chat: Boolean): Long { val p = params(m); return if (chat) (p.keyMs * p.chatKeyFactor).toLong() else p.keyMs }

    /** scenes whose motion cannot be cross-faded (drops / flakes) → live path */
    fun liveScene(sceneKey: String): Boolean = sceneKey == "rain" || sceneKey == "snow"

    /** display-loop fps for a page: 0 = no loop (still) */
    fun loopFps(m: Mode, live: Boolean, isLiveOwner: Boolean): Int {
        if (m == Mode.OFF || !isLiveOwner) return 0
        val p = params(m)
        return if (live) p.liveFps else p.blendFps
    }

    /** [38.9.11.3] while the user scrolls / a page opens: the sky keeps drifting at this lower frame rate (never stops) */
    const val BUSY_FPS = 20
    fun busyFps(fps: Int): Int = if (fps <= 0) 0 else minOf(fps, BUSY_FPS)

    /** sky divisor at a degrade level (the frame governor still exists; a degraded sky renders smaller) */
    fun skyDiv(m: Mode, level: Int): Float = params(m).skyDiv * (if (level >= 1) 1.5f else 1f)

    /**
     * Key frames: given the current sky time [now] (ms on the page's virtual clock) and the key spacing, the bracketing key
     * times (a ≤ now < b) and the blend weight of b. Keys sit on a fixed grid (multiples of [keyMs]) so every redraw of
     * the same instant picks the same keys and a re-entered page continues seamlessly.
     */
    data class Keys(val a: Long, val b: Long, val k: Float)
    fun keys(now: Long, keyMs: Long): Keys {
        val step = keyMs.coerceAtLeast(1L)
        val a = Math.floorDiv(now, step) * step
        val b = a + step
        val k = ((now - a).toFloat() / step).coerceIn(0f, 1f)
        return Keys(a, b, smooth(k))
    }
    /** smoothstep-free linear blend keeps a constant drift speed; this eases only the very ends a little (no visible pulse) */
    fun smooth(k: Float): Float = k

    /**
     * [38.9.11.3] 「云雾基本不动」: the key frames (1.2 s apart) were only CROSS-FADED, so drifting mist dissolved in place
     * instead of floating. Now each key layer is shifted by the drift between its cloud time and now, so the mist really
     * slides. Speed (screen units of a 270-wide sky per second of cloud time) = the dominant mid layer's wind / texel scale
     * in sky.agsl, negative = towards the left (cnoise samples at uv + wind·t). 0 = no shift (晴 / 日落 / 夜 keep 38.9.11.2).
     */
    fun driftUps(sceneKey: String): Float = when (sceneKey) {
        "fog" -> -8.5f / 0.36f        // 雾 layer 2 (wind 8.5, scale 0.36)
        "cloudy" -> -10.0f / 0.30f    // 多云 mid layer
        "overcast" -> -5.5f / 0.32f   // 阴 mid layer
        // [38.9.11.3 h] 「所有壁纸都不会动」: the cross-faded keys of 晴 / 日落 / 夜 dissolved in place too → they slide as well
        "sunny" -> -6.0f / 0.30f      // 晴 far cloud mass (k 0)
        "sunset" -> -5.0f / 0.30f     // 日落 far layer
        "night" -> -5.0f / 0.26f      // 夜 near dark cloud drift (k 1)
        else -> 0f
    }

    /** extra sky width rendered on each side of a key (units of 270) so a shifted key never shows an edge */
    fun marginUnits(sceneKey: String, m: Mode, chat: Boolean): Float {
        val v = kotlin.math.abs(driftUps(sceneKey)); if (v == 0f) return 0f
        // worst shift: key k-1 at u→1 is 2 key steps old; cloud rate ≤ MAX_RATE
        return (v * WindDriftCore.MAX_RATE * 2f * keyMs(m, chat) / 1000f + 4f).coerceAtMost(90f)
    }

    /** horizontal shift (px) of a key rendered at cloud time [keyCt] when the sky is at [nowCt]; clamped to the margin */
    fun shiftPx(sceneKey: String, nowCt: Float, keyCt: Float, widthPx: Float, marginUnits: Float): Float {
        val units = (driftUps(sceneKey) * (nowCt - keyCt)).coerceIn(-marginUnits, marginUnits)
        return units * widthPx / 270f
    }

    /**
     * The one-live-sky registry: every shown sky registers a token; only the newest one animates, the rest hold a still
     * key frame. Not thread-safe by itself (called on the main thread).
     */
    class Registry {
        private val stack = ArrayList<Long>()
        private var next = 1L
        fun enter(): Long { val t = next++; stack.add(t); return t }
        fun leave(t: Long) { stack.remove(t) }
        fun live(): Long = stack.lastOrNull() ?: 0L
        fun isLive(t: Long): Boolean = live() == t
        fun size(): Int = stack.size
    }
}

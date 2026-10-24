package com.openminis.app.plus

/**
 * [Mines-38.9.10 sky v7] living sky — AGSL port of the user-approved prototype (mock-mood/video2 sky.py + yu2.py, approved
 * 2026-10-19 19:09): one weather held, the sky keeps living (huge soft cloud masses on 2–4 parallax layers drifting and
 * morphing, slow viewpoint drift, light gaps opening and closing, warm light vs cool shadow). No sun / moon / trees / icons.
 * Pure part (no Android imports, unit-tested): scene choice, per-scene theme grade, the text guard that keeps ink ≥ 4.5:1,
 * shader segment assembly, the sky clock and the frame rate.
 * [Mines-38.9.9 sky v6] (photo stills + Ken Burns) and [Mines-38.9.6 i14] (v5) are replaced.
 */
object WeatherSkyCore {
    enum class Scene(val id: Int, val key: String, val zh: String) {
        SUNNY(0, "sunny", "晴"), CLOUDY(1, "cloudy", "多云"), RAIN(2, "rain", "雨"), SNOW(3, "snow", "雪"),
        FOG(4, "fog", "雾"), NIGHT(5, "night", "夜"), SUNSET(6, "sunset", "日落"),
        /** [Mines-38.9.9] 阴 (WMO 3): grey stratus deck; 多云 (WMO 1-2) is blue sky + clouds */
        OVERCAST(7, "overcast", "阴");
        val shaderId: Int get() = id
        /** the scene draws a second full-res / half-res pass (stars / snow / wet glass) */
        val pass2: String? get() = when (this) { NIGHT -> "STARS"; SNOW -> "SNOW"; RAIN -> "GLASS"; else -> null }
        companion object { fun of(k: String?): Scene? = values().firstOrNull { it.key == k || it.zh == k } }
    }

    /** frame cap while live; battery saver drops to [SLOW_FPS]; reduce motion = one still frame */
    const val FPS = 12   // [i30] was 20 (sky perf pass)
    const val SLOW_FPS = 4
    /** crossfade when the weather / time changes */
    const val FADE_MS = 1500L
    /** the still frame used by smoke 'sky <scene> static' (seconds on the sky clock) — matches the offline sheets */
    const val STILL_T = 10f
    /** the sky clock wraps once a day at 04:00 local (the one moment its noise jumps) */
    const val DAY_S = 86_400L

    fun isRain(code: Int) = code in 51..67 || code in 80..82 || code in 95..99
    fun isSnow(code: Int) = code in 71..77 || code == 85 || code == 86
    fun isFog(code: Int) = code == 45 || code == 48

    /**
     * Scene from the current WMO weather code (open-meteo, null = no fresh weather) and the time of day. Weather that shows at
     * any hour (rain / snow / fog) wins; a clear or cloudy sky becomes 夜 at night and 日落 in the hour or so before dark.
     * 0 = 晴, 1-2 = 多云, 3 = 阴. Without weather: a time-of-day clear sky (晴 by day, 日落 at dusk, 夜 at night), never grey.
     */
    fun pick(code: Int?, hour: Int, isDay: Boolean? = null): Scene {
        val night = if (isDay != null) !isDay else (hour >= 19 || hour < 6)
        val dusk = !night && hour in 17..18
        if (code != null) {
            if (isRain(code)) return Scene.RAIN
            if (isSnow(code)) return Scene.SNOW
            if (isFog(code)) return Scene.FOG
        }
        if (night) return Scene.NIGHT
        if (dusk && (code == null || code in 0..2)) return Scene.SUNSET
        return when (code) { null, 0 -> Scene.SUNNY; 1, 2 -> Scene.CLOUDY; else -> Scene.OVERCAST }
    }

    /** true only for the grey skies (阴 / 雾) */
    fun isGrey(s: Scene): Boolean = s == Scene.OVERCAST || s == Scene.FOG

    /**
     * Theme grade of one scene (mirrors r3910/sky/params.json, used by the offline renderer — keep in sync).
     * light: mix toward [tint] by [lift]; dark: desaturate by [desat], multiply by [mul] (rgb). [k2] = pass-2 strength.
     */
    class Grade(val lift: Float, val tint: Int, val mul: FloatArray, val desat: Float, val k2: Float = 1f)
    fun grade(s: Scene, dark: Boolean): Grade = if (!dark) when (s) {
        Scene.SUNSET -> Grade(0.05f, 0xF4EAE4, ONE, 0f)
        Scene.NIGHT -> Grade(0.45f, 0xC9D3E0, ONE, 0f, 0.5f)
        Scene.OVERCAST -> Grade(0f, 0xEEF0F2, ONE, 0f)
        Scene.SNOW -> Grade(0f, 0xEEF0F2, ONE, 0f, 1f)
        Scene.RAIN -> Grade(0f, 0xE2E6EA, ONE, 0f)
        else -> Grade(0f, 0xF4EAE4, ONE, 0f)
    } else when (s) {
        // [i29] dark day: lift = contrast exponent 1 + 2·lift before the multiply (was a flat band under the dark guard)
        Scene.SUNNY -> Grade(0.8f, 0xF4EAE4, floatArrayOf(0.50f, 0.58f, 0.72f), 0.04f)   // [3911c] was .75/.85/1: clouds sat above the guard knee → flat
        Scene.CLOUDY -> Grade(0.8f, 0xF4EAE4, floatArrayOf(0.70f, 0.76f, 0.88f), 0.04f)
        Scene.OVERCAST -> Grade(0.6f, 0xEEF0F2, floatArrayOf(0.60f, 0.63f, 0.70f), 0.04f)
        Scene.FOG -> Grade(0f, 0xF4EAE4, floatArrayOf(0.36f, 0.37f, 0.40f), 0.3f)
        Scene.SNOW -> Grade(0.6f, 0xEEF0F2, floatArrayOf(0.62f, 0.66f, 0.76f), 0.04f, 0.6f)
        Scene.SUNSET -> Grade(0.5f, 0xF4EAE4, floatArrayOf(0.66f, 0.56f, 0.58f), 0.04f)
        Scene.NIGHT -> Grade(0f, 0xDCE2EA, ONE, 0f, 1f)
        Scene.RAIN -> Grade(0f, 0xE2E6EA, ONE, 0f)   // 雨 has its own dark palette in the shader
    }
    private val ONE = floatArrayOf(1f, 1f, 1f)

    /**
     * Text guard (WCAG relative luminance of the sky, linear light), applied in the shader after the grade:
     * light theme → no sky pixel darker than [first] (home: ink #2A1E1A ≥ 4.5:1 needs L ≥ 0.247; chat: secondary ink #6B5A50
     * (L 0.110) ≥ 4.5:1 needs L ≥ 0.67); dark theme → no sky pixel brighter than [second] (home: ink #F6F0EB (L 0.87) ≥ 4.5:1
     * needs L ≤ 0.154; chat: secondary ink #CDC0B8 (L 0.53) needs L ≤ 0.079). (lo, hi): lo 0 = off, hi 1 = off.
     */
    // [Mines-38.9.11 i32] user 2026-10-20 08:54 (real phone, light chat) 「对话框底层不是天气皮肤」: the chat guard lifted every
    // dark part to ≥ 0.70 luminance (+ a 15 % page veil) → the sky read as flat beige. The chat page now shows the SAME sky
    // as home (same guard); legibility comes from the reply cards / bubbles / tool pills, which carry their own glass.
    fun guard(@Suppress("UNUSED_PARAMETER") chat: Boolean, dark: Boolean): Pair<Float, Float> = if (dark) 0f to 0.15f else 0.26f to 1f
    /** WCAG contrast of two relative luminances */
    fun contrast(l1: Float, l2: Float): Float = (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    fun lum(rgb: Int): Float {
        fun lin(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
        return 0.2126f * lin((rgb shr 16) and 255) + 0.7152f * lin((rgb shr 8) and 255) + 0.0722f * lin(rgb and 255)
    }

    /** chat page: a light veil of the page colour over the (already guarded) living sky */
    fun chatVeilColor(@Suppress("UNUSED_PARAMETER") scene: Scene, dark: Boolean): Pair<Float, Int> = if (dark) 0f to 0x161114 else 0f to 0xF4EAE4
    /** fallback (no shader: API < 33 / compile error): still frame + the thicker v6 veil (the stills carry the home guard only) */
    fun stillChatVeil(@Suppress("UNUSED_PARAMETER") scene: Scene, dark: Boolean): Pair<Float, Int> = if (dark) 0f to 0x161114 else 0f to 0xF4EAE4

    /** sky clock (seconds) for the shader: continuous, wraps once a day at 04:00 local time */
    fun skyTime(nowMs: Long, tzOffsetMs: Long): Float {
        val s = Math.floorMod(nowMs + tzOffsetMs - 4 * 3_600_000L, DAY_S * 1000L)
        return (s / 1000.0).toFloat()   // ≤ 86 400 s: float step ≈ 8 ms
    }

    /** [i27] the sky frame loop keeps running only while something moves: live sky or a scene cross-fade */
    fun keepLooping(fps: Int, fading: Boolean): Boolean = fps > 0 || fading

    /** frames per second for the current power / motion state (0 = draw one still frame) */
    fun fps(powerSave: Boolean, animOff: Boolean, forcedStatic: Boolean): Int = when {
        animOff || forcedStatic -> 0
        powerSave -> SLOW_FPS
        else -> FPS
    }

    /** Why the sky is drawn from the pre-rendered still instead of the shader (null = shader). */
    fun stillReason(api: Int, shaderError: String?): String? = when {
        api < 33 -> "api<33"
        shaderError != null -> "shader error"
        else -> null
    }
    /** what the smoke / 自检 reads: "shader live" / "shader slow" / "shader still (reduce motion)" / "still: api<33" */
    fun mode(api: Int, shaderError: String?, powerSave: Boolean, animOff: Boolean, forcedStatic: Boolean): String {
        stillReason(api, shaderError)?.let { return "static: $it" }
        return when {
            forcedStatic -> "shader still (forced)"
            animOff -> "shader still (reduce motion)"
            powerSave -> "shader slow (battery saver)"
            else -> "shader live"
        }
    }

    /** Segments of the bundled sky.agsl, keyed by marker (SHARED, "SKY sunny" …, MAIN, STARS, SNOW, GLASS). */
    fun segments(src: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val rx = Regex("(?m)^//#(.+)$")
        val ms = rx.findAll(src).toList()
        for ((i, m) in ms.withIndex()) {
            val end = if (i + 1 < ms.size) ms[i + 1].range.first else src.length
            out[m.groupValues[1].trim()] = src.substring(m.range.first, end)
        }
        return out
    }
    /** pass-1 source of a scene: SHARED + its SKY segment + MAIN */
    fun skySource(seg: Map<String, String>, s: Scene): String {
        val sh = seg["SHARED"] ?: error("sky.agsl: SHARED missing")
        val sky = seg["SKY ${s.key}"] ?: error("sky.agsl: SKY ${s.key} missing")
        return sh + sky + (seg["MAIN"] ?: error("sky.agsl: MAIN missing"))
    }
    /** pass-2 source (STARS / SNOW / GLASS) or null */
    fun pass2Source(seg: Map<String, String>, s: Scene): String? = s.pass2?.let { k -> (seg["SHARED"] ?: return null) + (seg[k] ?: return null) }

    /** 4 colours per scene (top → bottom bands of the light still, night = dark still; r3910/sky/stills.py), for the page fog that follows the sky */
    val mist: Map<String, IntArray> = mapOf(
        "sunny" to intArrayOf(0xCBCECF, 0xB8C6D1, 0xBEC8D1, 0xC3C9CF),
        "cloudy" to intArrayOf(0xA8B2BB, 0xADB7BF, 0xB4BCC1, 0xABB1B7),
        "overcast" to intArrayOf(0xB0B5BA, 0xAEB3B8, 0xA7ACB1, 0xA3A8AE),
        "rain" to intArrayOf(0xD2D7DC, 0xCCD2D8, 0xC5CBD2, 0xCFD3D8),   // [3911c] 雨 v5 water surface
        "snow" to intArrayOf(0xD4D8DA, 0xD5D9DB, 0xD6D9DB, 0xD8DBDC),
        "fog" to intArrayOf(0xCBCCC9, 0xD7D3CA, 0xDBDAD5, 0xDCDDDA),
        "night" to intArrayOf(0x161D2C, 0x151C2A, 0x192132, 0x2A3855),
        "sunset" to intArrayOf(0xDEC8BE, 0xDEC7BC, 0xE0C4B6, 0xD5BAB0),
    )
}

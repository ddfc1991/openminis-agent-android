package com.openminis.app.plus

/**
 * [Mines-38.9.11 c] 雨 v5 — AGSL port of the user-approved rain prototypes (2026-10-19 22:34): light = mock-mood/video2 yu5
 * 「水面光影」 (silver / blue-grey wet water surface, flowing light and soft reflections, ripples and pink petals only as faint
 * blurred hints, no green); dark = yu4 (fogged window with beading / sliding drops, quiet drizzle in the lamplight, a warm lamp
 * slowly swaying and now and then faltering, a few far dim lights).
 *
 * The rain lives in its own asset (assets/skybg/rain.agsl) so it never edits the shared sky.agsl: [merge] lays its
 * `//#SKY rain` and `//#GLASS` segments over the bundled ones and prepends `//#RAIN_COMMON` to both. Pure (unit-tested).
 * Frame caps are unchanged ([SkyPerfCore.RAIN_FPS] = 15, L1 halves it, stills on a software GPU).
 */
object RainSkyCore {
    const val ASSET = "skybg/rain.agsl"
    const val VERSION = "雨 v5"

    /** base segments (from sky.agsl) with the rain segments of [rainSrc] laid over them; base unchanged when rainSrc is unusable */
    fun merge(base: Map<String, String>, rainSrc: String?): Map<String, String> {
        if (rainSrc.isNullOrBlank()) return base
        val r = WeatherSkyCore.segments(rainSrc)
        val common = r["RAIN_COMMON"]?.let { body(it) } ?: return base
        val sky = r["SKY rain"]?.let { body(it) } ?: return base
        val glass = r["GLASS"]?.let { body(it) } ?: return base
        if (!sky.contains("half3 skyColor(") || !glass.contains("half4 main(")) return base
        val out = LinkedHashMap(base)
        out["SKY rain"] = "//#SKY rain\n" + common + sky
        out["GLASS"] = "//#GLASS\n" + common + glass
        return out
    }
    private fun body(seg: String): String = seg.substringAfter('\n', "")

    /**
     * Mirror of the shader's text guard ceiling for the dark rain glass: on home (gHi ≥ 0.1) the ceiling rises to [OPEN_HI]
     * inside the open stage between the cards and the input bar (where the approved prototype keeps the lamp bright and no
     * text sits); a stricter ceiling (< 0.1, a chat page that wants it) is never relaxed.
     */
    const val OPEN_HI = 0.80f
    fun openStage(y: Float): Float = ss(0.55f, 0.67f, y) * (1f - ss(0.82f, 0.88f, y))
    fun darkCeiling(gHi: Float, y: Float): Float = if (gHi >= 0.1f && gHi < 1f) gHi + (OPEN_HI - gHi) * openStage(y) else gHi
    private fun ss(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
}

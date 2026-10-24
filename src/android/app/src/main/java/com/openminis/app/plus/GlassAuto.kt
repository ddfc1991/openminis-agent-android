package com.openminis.app.plus

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [Mines-38.9.4] user 2026-10-17 16:59 「那个磨砂玻璃可以调节一下，不用户自己调节」: the frosted glass picks its own tint.
 * [Mines-38.9.5 i] user 21:22 on the 38.9.4 sheets: auto was too white (light 86 %, dark 73 %); the 38.9.3 look (slider
 * ~30 %) is the one they like. So the glass now stays THIN — light 30–40 %, dark 35–45 %, hard cap light 45 % / dark 50 %
 * (strong variant included) — and readability comes from the text instead of from whitening the whole card:
 *  1. secondary text (ink2) is pulled toward the primary ink just as far as the worst band needs ([Plan.ink2]);
 *  2. where even that is not enough, text rows on those bands get a soft halo / scrim in the glass tint behind the glyphs
 *     ([Plan.halo] per band, 0…[HALO_MAX]), not a denser card.
 *
 * What is behind the glass is measured (the fog as it is drawn — base colour + the 4 drifting blobs at the current
 * 雾气浓淡 / weather density, sampled on a 12 × 24 grid at 4 moments of its 24 s loop — or the photo wallpaper on a
 * 12 × 24 thumbnail). Per vertical band of the screen it keeps the WORST luminance for the text colour (darkest
 * spot for dark ink on light glass, brightest for light ink on dark glass), pushed a little further by how busy the
 * background is (std-dev).
 *
 * Pure (no Android types) so it is unit-tested: GlassAutoTest.
 */
object GlassAuto {
    const val BANDS = 6
    const val LIGHT_MIN = 0.30f; const val LIGHT_MAX = 0.40f; const val LIGHT_CAP = 0.45f
    const val DARK_MIN = 0.35f; const val DARK_MAX = 0.45f; const val DARK_CAP = 0.50f
    const val TARGET = 4.5f
    /** strongest halo behind text rows (tint alpha at the glyph centre; it fades out over ~6 dp) */
    const val HALO_MAX = 0.70f
    fun min(dark: Boolean) = if (dark) DARK_MIN else LIGHT_MIN
    fun max(dark: Boolean) = if (dark) DARK_MAX else LIGHT_MAX
    fun cap(dark: Boolean) = if (dark) DARK_CAP else LIGHT_CAP

    /** Per band (top → bottom): darkest / brightest luminance (after the busy-ness margin), mean, std-dev. */
    data class Band(val lo: Float, val hi: Float, val mean: Float, val sd: Float)
    data class Stats(val bands: List<Band>) {
        val worstLo: Float get() = bands.minOf { it.lo }
        val worstHi: Float get() = bands.maxOf { it.hi }
    }

    // ---------------- colour maths (sRGB, WCAG) ----------------

    private fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun srgb(l: Float): Float { val c = l.coerceIn(0f, 1f); return if (c <= 0.0031308f) 12.92f * c else (1.055 * c.toDouble().pow(1 / 2.4) - 0.055).toFloat() }
    fun lum(argb: Int): Float = 0.2126f * lin(((argb shr 16) and 0xFF) / 255f) + 0.7152f * lin(((argb shr 8) and 0xFF) / 255f) + 0.0722f * lin((argb and 0xFF) / 255f)
    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /** Luminance of a glass of luminance [tint] at [alpha] over a backdrop of luminance [bg] (blended in sRGB, like the GPU). */
    fun over(tint: Float, alpha: Float, bg: Float): Float = lin(alpha * srgb(tint) + (1 - alpha) * srgb(bg))

    // ---------------- the solver ----------------

    private fun worst(dark: Boolean, band: Band) = if (dark) band.hi else band.lo

    /** Lowest alpha (0.01 steps) in [min]…[max] keeping [ink] ≥ [TARGET]:1 over the worst spot of [band]; [max] when none does
     *  (the glass never goes denser than that — the text gets help instead, see [plan]). */
    fun solveBand(dark: Boolean, tint: Float, ink: Float, band: Band): Float {
        var a = min(dark)
        while (a <= max(dark) + 1e-4f) {
            if (contrast(ink, over(tint, a, worst(dark, band))) >= TARGET) return a
            a += 0.01f
        }
        return max(dark)
    }

    /** Alpha per band and the screen-wide one (the max: a card can scroll over any band). */
    fun solve(dark: Boolean, tint: Float, ink: Float, s: Stats): Pair<List<Float>, Float> {
        val per = s.bands.map { solveBand(dark, tint, ink, it) }
        return per to (per.maxOrNull() ?: min(dark))
    }

    /** Glass alpha per band + screen-wide, the secondary-text colour to use, and the halo behind text rows per band. */
    data class Plan(val per: List<Float>, val all: Float, val ink2: Int, val inkMix: Float, val halo: List<Float>)

    /** [ink2] pulled toward [ink] by [t] (0 = ink2, 1 = ink), in sRGB like the GPU. */
    fun mixInk(ink2: Int, ink: Int, t: Float): Int = blend(ink, ink2, t.coerceIn(0f, 1f))

    /** Contrast of text [inkLum] over the glass at [a] with a halo of [halo] (tint coverage behind the glyphs) over [bg]. */
    fun textContrast(tint: Float, a: Float, halo: Float, bg: Float, inkLum: Float): Float =
        contrast(inkLum, over(tint, a + (1 - a) * halo.coerceIn(0f, 1f), bg))

    /**
     * Thin glass first, then the text: per band the lowest alpha in min…max that already gives ink2 ≥ 4.5:1 (else max);
     * then ONE ink2 for the screen = the least pull toward [ink] that makes every band pass at its own alpha; where even the
     * primary ink is not enough, the lowest halo (0.05 steps, ≤ [HALO_MAX]) that does.
     */
    fun plan(dark: Boolean, tint: Int, ink2: Int, ink: Int, s: Stats): Plan {
        val tl = lum(tint)
        val (per, all) = solve(dark, tl, lum(ink2), s)
        var t = 0f
        while (t < 1f && s.bands.indices.any { i -> textContrast(tl, per[i], 0f, worst(dark, s.bands[i]), lum(mixInk(ink2, ink, t))) < TARGET }) t += 0.05f
        t = t.coerceAtMost(1f)
        val ink2n = mixInk(ink2, ink, t); val il = lum(ink2n)
        val halo = s.bands.indices.map { i ->
            var h = 0f
            while (h < HALO_MAX && textContrast(tl, per[i], h, worst(dark, s.bands[i]), il) < TARGET) h += 0.05f
            (Math.round(h.coerceAtMost(HALO_MAX) * 20f) / 20f)
        }
        return Plan(per, all, ink2n, t, halo)
    }

    /** Worst spot = 10th / 90th percentile; busy backgrounds read worse through frost, so it is widened by ¼ std-dev (≤ 0.04). */
    private fun band(ls: List<Float>): Band {
        if (ls.isEmpty()) return Band(0.5f, 0.5f, 0.5f, 0f)
        val mean = ls.average().toFloat()
        val sd = sqrt(ls.map { (it - mean) * (it - mean) }.average()).toFloat()
        val m = (sd * 0.25f).coerceAtMost(0.04f)
        val sorted = ls.sorted()
        val p05 = sorted[(sorted.size * 0.10f).toInt().coerceIn(0, sorted.lastIndex)]
        val p95 = sorted[(sorted.size * 0.90f).toInt().coerceIn(0, sorted.lastIndex)]
        return Band((p05 - m).coerceIn(0f, 1f), (p95 + m).coerceIn(0f, 1f), mean, sd)
    }

    /** [px] row-major ARGB of a w × h thumbnail of what is behind the glass. */
    fun statsOf(px: IntArray, w: Int, h: Int): Stats {
        val rows = (0 until BANDS).map { b ->
            val y0 = h * b / BANDS; val y1 = maxOf(y0 + 1, h * (b + 1) / BANDS)
            val ls = ArrayList<Float>()
            for (y in y0 until minOf(y1, h)) for (x in 0 until w) ls += lum(px[y * w + x])
            band(ls)
        }
        return Stats(rows)
    }

    /** Photo thumbnail drawn at [photoAlpha] over the page colour [page]. */
    fun photoStats(px: IntArray, w: Int, h: Int, photoAlpha: Float, page: Int): Stats {
        val a = photoAlpha.coerceIn(0f, 1f)
        val out = IntArray(px.size) { i -> blend(px[i], page, a) }
        return statsOf(out, w, h)
    }

    // ---------------- the fog, as PorcelainBackdrop draws it ----------------

    private val SPOTS = arrayOf(   // (cx, cy, ax, ay, kx, ky, phase, radius × width) — keep in step with PorcelainBackdrop
        floatArrayOf(0.24f, 0.17f, 230f, 210f, 1f, 1f, 0.0f, 0.50f),
        floatArrayOf(0.80f, 0.40f, 210f, 250f, 1f, 2f, 1.9f, 0.48f),
        floatArrayOf(0.24f, 0.66f, 230f, 190f, 2f, 1f, 3.6f, 0.46f),
        floatArrayOf(0.78f, 0.88f, 190f, 230f, 1f, 1f, 4.8f, 0.48f),
    )
    private val LIGHT_BASE = 0xFFF4EAE4.toInt(); private val DARK_BASE = 0xFF161114.toInt()
    private val LIGHT_COLS = intArrayOf(0xFFF0844E.toInt(), 0xFFA07CF0.toInt(), 0xFFFF9F45.toInt(), 0xFFEC72A4.toInt())
    private val DARK_COLS = intArrayOf(0xFF963A42.toInt(), 0xFF563296.toInt(), 0xFFA85C1C.toInt(), 0xFF8C2E60.toInt())
    private val LIGHT_A = floatArrayOf(1.0f, 1.0f, 0.95f, 0.95f); private val DARK_A = floatArrayOf(0.90f, 0.85f, 0.75f, 0.78f)

    fun blend(top: Int, under: Int, a: Float): Int {
        fun ch(s: Int) = ((((top shr s) and 0xFF) * a + ((under shr s) and 0xFF) * (1 - a)).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Fog stats: [fogK] = 外观调节 › 雾气浓淡, [dens] = weather density; 12 × 24 grid × 4 moments of the 24 s loop. */
    fun fogStats(dark: Boolean, fogK: Float, dens: Float, aspect: Float = 2.19f): Stats {
        val w = 12; val h = 24
        val base = if (dark) DARK_BASE else LIGHT_BASE
        val cols = if (dark) DARK_COLS else LIGHT_COLS
        val als = (if (dark) DARK_A else LIGHT_A).map { (it * fogK).coerceIn(0f, 1f) }
        val W = 1080f; val H = W * aspect
        val perBand = List(BANDS) { ArrayList<Float>() }
        for (t in floatArrayOf(0f, 6f, 12f, 18f)) {
            val om = (2.0 * Math.PI * t / 24.0).toFloat()
            val centres = SPOTS.mapIndexed { i, sp ->
                val cx = sp[0] * W + 1.7f * sp[2] * kotlin.math.cos(sp[4] * om + sp[6])
                val cy = sp[1] * H + 1.7f * sp[3] * kotlin.math.sin(sp[5] * om + sp[6])
                val r = sp[7] * W * 1.6f * 1.12f
                val a = (als[i] * dens).coerceIn(0f, 1f)   // breathing peak (0.74 + 0.26) = 1
                floatArrayOf(cx, cy, r, a)
            }
            for (gy in 0 until h) for (gx in 0 until w) {
                val x = (gx + 0.5f) * W / w; val y = (gy + 0.5f) * H / h
                var c = base
                centres.forEachIndexed { i, s ->
                    val dx = x - s[0]; val dy = (y - s[1]) / 1.15f
                    val d = sqrt(dx * dx + dy * dy) / s[2]       // 0 … 1 of the gradient radius
                    val fall = if (d >= 1f) 0f else kotlin.math.exp(-2.0 * (d * 1.6) * (d * 1.6)).toFloat()
                    val a = s[3] * fall
                    if (a > 0.002f) c = blend(cols[i], c, a)
                }
                perBand[(gy * BANDS / h).coerceIn(0, BANDS - 1)] += lum(c)
            }
        }
        return Stats(perBand.map { band(it) })
    }
}

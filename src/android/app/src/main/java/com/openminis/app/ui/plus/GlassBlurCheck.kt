package com.openminis.app.ui.plus

/**
 * [Mines-38.9.6 i17] Did the root RenderEffect blur really draw? Compares two small luma copies of the activity window
 * (before the blur animates in / after it should have): median over textured tiles of (gradient energy after / before).
 * A blurred page drops far below [LIMIT]; the same page only under the glass scrim (16–23 %) stays ≈ 0.8.
 * No textured tile = nothing behind that could read through → 0 (counts as blurred). Pure, JVM-testable.
 */
object GlassBlurCheck {
    const val LIMIT = 0.5f

    fun blurred(ratio: Float): Boolean = ratio in 0f..LIMIT

    fun sharpRatio(a: FloatArray, b: FloatArray, w: Int, h: Int): Float {
        if (a.size != b.size || a.size != w * h || w < 8 || h < 16) return 1f
        val gx = 4; val gy = 8; val rs = ArrayList<Float>()
        for (ty in 0 until gy) for (tx in 0 until gx) {
            val x0 = tx * w / gx; val x1 = (tx + 1) * w / gx - 1; val y0 = ty * h / gy; val y1 = (ty + 1) * h / gy - 1
            var ea = 0f; var eb = 0f; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = y * w + x
                ea += kotlin.math.abs(a[i + 1] - a[i]) + kotlin.math.abs(a[i + w] - a[i])
                eb += kotlin.math.abs(b[i + 1] - b[i]) + kotlin.math.abs(b[i + w] - b[i]); n++
            }
            if (n > 0 && ea / n > 3f) rs.add(eb / ea)
        }
        if (rs.isEmpty()) return 0f
        rs.sort(); return rs[rs.size / 2]
    }
}

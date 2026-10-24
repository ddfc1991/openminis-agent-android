package com.openminis.app.ui.components

/** Pure rules for [SwipeRowActions] (unit-tested). */
object SwipeRevealCore {
    /** Actions exist only once the row is pulled left by more than 1 px. */
    fun showActions(offsetPx: Float): Boolean = !offsetPx.isNaN() && offsetPx < -1f
    /**
     * [Mines-38.9.9 i26] where a released drag settles (true = open). Open past 35 % of the reveal, or on a left fling
     * faster than [flingPx] px/s; a right fling closes. (The old AnchoredDraggable defaults — 50 % / 125 dp/s — left the
     * 模型组 row closed in smoke 39190432130 after a ~340 px swipe; the measured reason is logged in [probe].)
     */
    fun settleOpen(offsetPx: Float, velocityPx: Float, revealPx: Float, flingPx: Float): Boolean = when {
        revealPx <= 0f -> false
        velocityPx <= -flingPx -> true
        velocityPx >= flingPx -> false
        else -> -offsetPx >= revealPx * 0.35f
    }
    /** smoke: the last gesture seen by any swipe row ("start", "end off=… v=… open=…") */
    @Volatile var probe: String = ""
    /** Fade the actions in over the first half of the reveal. */
    fun actionAlpha(offsetPx: Float, revealPx: Float): Float {
        if (revealPx <= 0f || !showActions(offsetPx)) return 0f
        return ((-offsetPx) / (revealPx * 0.5f)).coerceIn(0f, 1f)
    }

    /** [i27] which gesture a pointer that has moved ([dx],[dy]) for [elapsedMs] belongs to. */
    enum class Claim { UNDECIDED, HORIZONTAL, VERTICAL, LONG_PRESS }
    fun claim(dx: Float, dy: Float, slop: Float, elapsedMs: Long, longPressMs: Long): Claim {
        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
        return when {
            ax > slop && ax > ay -> Claim.HORIZONTAL
            ay > slop && ay >= ax -> Claim.VERTICAL
            elapsedMs >= longPressMs -> Claim.LONG_PRESS
            else -> Claim.UNDECIDED
        }
    }
    /** [i27] a down at [x] lands on the revealed buttons (the strip the slid row no longer covers). */
    fun hitsActions(x: Float, widthPx: Float, offsetPx: Float): Boolean =
        showActions(offsetPx) && widthPx > 0f && x >= widthPx + offsetPx
    /** [i27] opaque colour of the slid row: [row] over the theme's reading paper; light mode never darker than L 0.80. */
    fun solidRowColor(row: androidx.compose.ui.graphics.Color, paper: androidx.compose.ui.graphics.Color, dark: Boolean): androidx.compose.ui.graphics.Color {
        val c = if (row.alpha >= 0.999f) row else androidx.compose.ui.graphics.Color(
            red = row.red * row.alpha + paper.red * (1 - row.alpha),
            green = row.green * row.alpha + paper.green * (1 - row.alpha),
            blue = row.blue * row.alpha + paper.blue * (1 - row.alpha), alpha = 1f)
        if (dark) return c
        var out = c; var i = 0
        while (out.luminance() < 0.80f && i < 10) { out = androidx.compose.ui.graphics.lerp(out, paper, 0.25f); i++ }
        return out
    }
    private fun androidx.compose.ui.graphics.Color.luminance(): Float {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue)
    }
    /** [Mines-38.9.10 i28] width of the slid row's foreground: full width minus the revealed actions (never < 0) */
    fun narrowWidth(fullPx: Int, offsetPx: Float): Int = (fullPx + kotlin.math.round(offsetPx).toInt()).coerceIn(0, fullPx)
}

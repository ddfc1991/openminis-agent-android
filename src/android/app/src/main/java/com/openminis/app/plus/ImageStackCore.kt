package com.openminis.app.plus

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * [Mines-38.9.11 b] Pure geometry for the Hark-style fanned picture stack (ref Hark chat, user 2026-10-20 08:55).
 *
 * Card 0 = front, straight, centred, drawn on top. Card 1 sits behind on the LEFT tilted -[TILT]°, card 2 behind on the
 * RIGHT tilted +[TILT]° (with exactly two images the second goes right, like the reference). Back cards are a bit
 * smaller ([BACK_SCALE]) and lower. All values are in dp. x / y / w / h are the card's UNROTATED box inside the stack;
 * the UI rotates each card around its own centre, and the stack size already contains the rotated corners, so nothing
 * gets clipped. No Android types here so it is unit-testable on a plain JVM.
 */
object ImageStackCore {
    const val MAX_SHOWN = 3
    /** width / height when the image has not loaded yet: a phone screenshot shape. */
    const val DEFAULT_ASPECT = 0.56f
    const val MIN_ASPECT = 0.45f
    const val MAX_ASPECT = 1.6f
    const val TILT = 9f
    const val BACK_SCALE = 0.88f
    /** horizontal shift of a back card's centre, as a fraction of the front card width. */
    const val SPREAD = 0.62f
    /** downward shift of a back card's centre, as a fraction of the front card height. */
    const val DROP = 0.07f

    data class Card(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float, val z: Int)
    data class Layout(val width: Float, val height: Float, val cards: List<Card>)

    fun clampAspect(a: Float): Float =
        if (a.isNaN() || a <= 0f || a.isInfinite()) DEFAULT_ASPECT else a.coerceIn(MIN_ASPECT, MAX_ASPECT)

    /**
     * @param n number of images (only the first [MAX_SHOWN] are laid out; 0 → empty layout)
     * @param cardWidth width of the front card in dp
     * @param aspects width / height per image (missing / bad → [DEFAULT_ASPECT])
     */
    fun layout(n: Int, cardWidth: Float, aspects: List<Float>): Layout {
        val shown = min(max(n, 0), MAX_SHOWN)
        if (shown == 0 || cardWidth <= 0f) return Layout(0f, 0f, emptyList())
        fun aspect(i: Int) = clampAspect(aspects.getOrNull(i) ?: DEFAULT_ASPECT)
        val frontW = cardWidth
        val frontH = frontW / aspect(0)
        // centre positions relative to the front card's centre, before normalising
        data class Raw(val i: Int, val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float, val z: Int)
        val raws = ArrayList<Raw>(shown)
        raws += Raw(0, 0f, 0f, frontW, frontH, 0f, shown)
        for (i in 1 until shown) {
            val side = if (shown == 2) 1f else if (i == 1) -1f else 1f
            val w = frontW * BACK_SCALE
            val h = w / aspect(i)
            raws += Raw(i, side * frontW * SPREAD, frontH * DROP, w, h, side * TILT, shown - i)
        }
        // rotated bounding box of every card → overall extents
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (r in raws) {
            val rad = Math.toRadians(r.rot.toDouble())
            val c = abs(cos(rad)).toFloat(); val s = abs(sin(rad)).toFloat()
            val hw = (r.w * c + r.h * s) / 2f; val hh = (r.w * s + r.h * c) / 2f
            minX = min(minX, r.cx - hw); maxX = max(maxX, r.cx + hw)
            minY = min(minY, r.cy - hh); maxY = max(maxY, r.cy + hh)
        }
        val cards = raws.map { r ->
            Card(r.i, r.cx - r.w / 2f - minX, r.cy - r.h / 2f - minY, r.w, r.h, r.rot, r.z)
        }
        return Layout(maxX - minX, maxY - minY, cards)
    }
}

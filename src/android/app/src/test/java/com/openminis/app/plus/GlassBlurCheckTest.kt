package com.openminis.app.plus

import com.openminis.app.ui.plus.GlassBlurCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.6 i17] the RenderEffect proof: a blurred page passes, the same page only under a scrim does not. */
class GlassBlurCheckTest {
    private val w = 180; private val h = 392

    /** text-like page: dark 2-px strokes on light rows */
    private fun page(): FloatArray = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        if ((y / 6) % 3 == 0 && (x / 3) % 2 == 0) 60f else 235f
    }

    private fun boxBlur(a: FloatArray, r: Int): FloatArray {
        var cur = a
        repeat(3) {
            val t = FloatArray(cur.size); val o = FloatArray(cur.size)
            for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (k in -r..r) s += cur[y * w + (x + k).coerceIn(0, w - 1)]; t[y * w + x] = s / (2 * r + 1) }
            for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (k in -r..r) s += t[(y + k).coerceIn(0, h - 1) * w + x]; o[y * w + x] = s / (2 * r + 1) }
            cur = o
        }
        return cur
    }

    @Test fun blurredPagePasses() {
        val a = page(); val b = boxBlur(a, 6)
        val r = GlassBlurCheck.sharpRatio(a, b, w, h)
        assertTrue("ratio $r", GlassBlurCheck.blurred(r))
    }

    @Test fun scrimOnlyFails() {
        val a = page(); val b = FloatArray(a.size) { a[it] * 0.84f + 0.16f * 226f }   // light scrim 0x29 ≈ 16 %
        val r = GlassBlurCheck.sharpRatio(a, b, w, h)
        assertFalse("ratio $r", GlassBlurCheck.blurred(r))
    }

    @Test fun unchangedFails() {
        val a = page()
        assertFalse(GlassBlurCheck.blurred(GlassBlurCheck.sharpRatio(a, a.copyOf(), w, h)))
    }

    @Test fun flatPageCountsAsBlurred() {
        val a = FloatArray(w * h) { 200f }
        assertEquals(0f, GlassBlurCheck.sharpRatio(a, a.copyOf(), w, h), 0f)
        assertTrue(GlassBlurCheck.blurred(0f))
    }

    @Test fun notMeasuredIsNotBlurred() {
        assertFalse(GlassBlurCheck.blurred(-1f))
        assertEquals(1f, GlassBlurCheck.sharpRatio(FloatArray(10), FloatArray(12), w, h), 0f)
    }
}

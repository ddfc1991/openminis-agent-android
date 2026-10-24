package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ImageStackCoreTest {
    private val C = ImageStackCore
    private fun near(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) < eps

    @Test fun emptyAndSingle() {
        assertEquals(0, C.layout(0, 100f, emptyList()).cards.size)
        assertEquals(0, C.layout(2, 0f, emptyList()).cards.size)
        val one = C.layout(1, 100f, listOf(0.5f))
        assertEquals(1, one.cards.size)
        val c = one.cards[0]
        assertEquals(0f, c.rotation); assertTrue(near(c.x, 0f) && near(c.y, 0f))
        assertTrue(near(one.width, 100f) && near(one.height, 200f))
    }

    @Test fun threeFanFrontCentredOnTop() {
        val l = C.layout(5, 100f, listOf(0.5f, 0.5f, 0.5f))
        assertEquals(C.MAX_SHOWN, l.cards.size)
        val front = l.cards.first { it.index == 0 }
        assertEquals(0f, front.rotation)
        assertTrue(l.cards.all { it.z <= front.z }); assertEquals(front.z, l.cards.maxOf { it.z })
        val left = l.cards.first { it.index == 1 }; val right = l.cards.first { it.index == 2 }
        assertEquals(-C.TILT, left.rotation); assertEquals(C.TILT, right.rotation)
        assertTrue(left.x < front.x && right.x > front.x)
        assertTrue(left.w < front.w)
        // front is horizontally centred, the fan is symmetric
        assertTrue(near(front.x + front.w / 2, l.width / 2))
    }

    @Test fun twoImagesSecondGoesRight() {
        val l = C.layout(2, 100f, emptyList())
        val second = l.cards.first { it.index == 1 }
        assertEquals(C.TILT, second.rotation)
        assertTrue(second.x > l.cards.first { it.index == 0 }.x)
    }

    @Test fun rotatedCornersInsideBounds() {
        for (n in 1..3) for (a in listOf(0.3f, 0.56f, 1.0f, 3f)) {
            val l = C.layout(n, 80f, List(n) { a })
            for (c in l.cards) {
                val rad = Math.toRadians(c.rotation.toDouble())
                val cx = c.x + c.w / 2; val cy = c.y + c.h / 2
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val px = dx * c.w / 2; val py = dy * c.h / 2
                    val x = cx + (px * cos(rad) - py * sin(rad)).toFloat()
                    val y = cy + (px * sin(rad) + py * cos(rad)).toFloat()
                    assertTrue("n=$n a=$a corner x=$x of ${l.width}", x >= -0.01f && x <= l.width + 0.01f)
                    assertTrue("n=$n a=$a corner y=$y of ${l.height}", y >= -0.01f && y <= l.height + 0.01f)
                }
            }
        }
    }

    @Test fun clampAspect() {
        assertEquals(C.DEFAULT_ASPECT, C.clampAspect(Float.NaN)); assertEquals(C.DEFAULT_ASPECT, C.clampAspect(0f))
        assertEquals(C.DEFAULT_ASPECT, C.clampAspect(Float.POSITIVE_INFINITY))
        assertEquals(C.MIN_ASPECT, C.clampAspect(0.05f)); assertEquals(C.MAX_ASPECT, C.clampAspect(9f))
        assertEquals(0.75f, C.clampAspect(0.75f))
        // a tall screenshot is clamped, so the card never grows past width / MIN_ASPECT
        assertTrue(C.layout(1, 100f, listOf(0.1f)).height <= 100f / C.MIN_ASPECT + 0.01f)
    }
}

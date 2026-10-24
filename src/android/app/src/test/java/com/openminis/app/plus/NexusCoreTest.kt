package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NexusCoreTest {
    @Test fun sphereIsUnitAndSized() {
        val s = NexusCore.sphere()
        assertEquals(900 * 3, s.size)
        for (i in 0 until 900) {
            val r = sqrt(s[i * 3] * s[i * 3] + s[i * 3 + 1] * s[i * 3 + 1] + s[i * 3 + 2] * s[i * 3 + 2])
            assertTrue("r=$r", abs(r - 1f) < 1e-4f)
        }
        // spread over both hemispheres
        assertTrue((0 until 900).count { s[it * 3 + 1] > 0 } in 440..460)
    }

    @Test fun ringsAndOrbit() {
        val r = NexusCore.rings(96)
        assertEquals(11, r.size)
        r.forEach { assertEquals(96 * 3, it.size) }
        assertEquals(140 * 3, NexusCore.orbit(140).size)
    }

    @Test fun nodeDirsCapAt8() {
        assertEquals(24, NexusCore.nodeDirs(12).size)
        assertEquals(9, NexusCore.nodeDirs(3).size)
        assertEquals(0, NexusCore.nodeDirs(0).size)
    }

    @Test fun subsDeterministicAndCapped() {
        val d = NexusCore.nodeDirs(3)
        val (a, oa) = NexusCore.subs(d, intArrayOf(39, 2, 0))
        val (b, _) = NexusCore.subs(d, intArrayOf(39, 2, 0))
        assertEquals(12, oa.size)               // 10 + 2 + 0
        assertTrue(a.contentEquals(b))
        assertEquals(0, oa[0]); assertEquals(1, oa[11])
    }

    @Test fun rotateKeepsLength() {
        val s = NexusCore.sphere(50); val d = FloatArray(s.size)
        NexusCore.rotate(s, d, 1.1f, -0.32f)
        for (i in 0 until 50) {
            val r = sqrt(d[i * 3] * d[i * 3] + d[i * 3 + 1] * d[i * 3 + 1] + d[i * 3 + 2] * d[i * 3 + 2])
            assertTrue(abs(r - 1f) < 1e-4f)
        }
        // zero rotation = identity
        NexusCore.rotate(s, d, 0f, 0f); assertTrue(s.contentEquals(d))
    }

    @Test fun projectCentreAndHit() {
        val p = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)
        val out = FloatArray(8)
        NexusCore.project(p, out, 100f, 200f, 50f, 0f, 0f)
        assertEquals(100f, out[0], 1e-4f); assertEquals(200f, out[1], 1e-4f); assertEquals(1f, out[3], 1e-4f)
        assertEquals(150f, out[4], 1e-4f)
        assertEquals(1, NexusCore.hit(out, 2, 148f, 202f, 20f))
        assertEquals(-1, NexusCore.hit(out, 2, 400f, 400f, 20f))
    }

    @Test fun easeAndReadouts() {
        assertEquals(0f, NexusCore.ease(-1f), 0f); assertEquals(1f, NexusCore.ease(2f), 0f); assertEquals(0.5f, NexusCore.ease(0.5f), 1e-6f)
        assertEquals("097", NexusCore.pad3(97)); assertEquals("08", NexusCore.pad2(8))
        assertEquals("—", NexusCore.indexed(0, 0)); assertEquals("50.0%", NexusCore.indexed(4, 2))
        assertEquals(66L, NexusCore.frameGapMs(true)); assertEquals(16L, NexusCore.frameGapMs(false))
    }

    @Test fun arcEnds() {
        val (x0, y0) = NexusCore.arc(0f, 0f, 10f, 0f, 5f, 0f); assertEquals(0f, x0, 0f); assertEquals(0f, y0, 0f)
        val (x1, y1) = NexusCore.arc(0f, 0f, 10f, 0f, 5f, 1f); assertEquals(10f, x1, 1e-5f); assertEquals(0f, y1, 1e-5f)
        val (_, ym) = NexusCore.arc(0f, 0f, 10f, 0f, 5f, 0.5f); assertTrue(ym < 0f)
    }

    @Test fun layout39121() {
        // phone 1080 wide, search ends at 560 px, panel starts at 1980 px
        val (cy, scale, floor) = NexusCore.layout(1080f, 560f, 1980f)
        assertTrue(cy > 1150f && cy < 1270f)
        assertTrue(scale > 240f && scale <= 1080f * 0.235f + 0.1f)
        assertTrue(floor > cy + scale && floor < 1980f)
        assertEquals(24, NexusCore.orbitDirs(12).size)
        val d = NexusCore.orbitDirs(8)
        for (k in 0 until 8) assertTrue(kotlin.math.abs(d[k * 3 + 1]) < 0.2f)
    }
}

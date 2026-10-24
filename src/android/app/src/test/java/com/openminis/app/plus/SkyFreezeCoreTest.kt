package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11.2] sky still while the screen is read / captured / touched; C1 key blend (no pulse at the key rate). */
class SkyFreezeCoreTest {
    private val S = SkyFreezeCore.Signals()

    @Test fun freezeReasons() {
        val now = 100_000L
        assertEquals("", SkyFreezeCore.reason(S, now))
        assertEquals("读屏", SkyFreezeCore.reason(S.copy(touchExploration = true), now))
        assertEquals("无障碍服务", SkyFreezeCore.reason(S.copy(otherA11yServices = 1), now))
        assertEquals("录屏", SkyFreezeCore.reason(S.copy(screenRecording = true), now))
        assertEquals("截屏", SkyFreezeCore.reason(S.copy(screenshotAtMs = now - 1000), now))
        assertEquals("", SkyFreezeCore.reason(S.copy(screenshotAtMs = now - 5000), now))
        assertEquals("分屏", SkyFreezeCore.reason(S.copy(multiWindow = true), now))
        assertEquals("悬浮窗在上面", SkyFreezeCore.reason(S.copy(windowFocused = false), now))
        assertEquals("操作中", SkyFreezeCore.reason(S.copy(lastInputMs = now - 999), now))
        assertEquals("", SkyFreezeCore.reason(S.copy(lastInputMs = now - 1000), now))
        assertEquals(1, SkyFreezeCore.otherServices(listOf("com.openminis.mines.plus", "com.realme.qzai", ""), "com.openminis.mines.plus"))
        assertEquals(0, SkyFreezeCore.otherServices(listOf("com.openminis.mines.plus"), "com.openminis.mines.plus"))
    }

    /** [38.9.11.3] user: the sky never stops — with Q仔 (an a11y service) on, while recording, touching, split screen … */
    @Test fun neverHolds() {
        val now = 100_000L
        for (sig in listOf(S.copy(otherA11yServices = 1), S.copy(touchExploration = true), S.copy(screenRecording = true),
            S.copy(screenshotAtMs = now - 500), S.copy(multiWindow = true), S.copy(windowFocused = false), S.copy(lastInputMs = now - 10))) {
            val r = SkyFreezeCore.reason(sig, now)
            assertTrue("diagnostic reason for $sig", r.isNotEmpty())
            assertEquals("sky must keep moving: $r", false, SkyFreezeCore.holds(r, legacyReplay = false))
            assertEquals(true, SkyFreezeCore.holds(r, legacyReplay = true))   // smoke before-recording only
        }
        assertEquals(false, SkyFreezeCore.holds("", legacyReplay = true))
    }

    @Test fun bsplineIsC1AcrossKeys() {
        for (i in 0..100) { val w = SkyFreezeCore.bspline(i / 100f); assertEquals(1f, w.sum(), 1e-5f); assertTrue(w.all { it >= -1e-6f }) }
        // a signal that steps by 1 at every key: blended value and its slope are continuous at u = 1 → u = 0 of the next key
        fun v(n: Int, u: Float): Float { val w = SkyFreezeCore.bspline(u); return w[0] * (n - 1) + w[1] * n + w[2] * (n + 1) }
        assertEquals(v(5, 1f), v(6, 0f), 1e-5f)
        val e = 1e-3f
        val slopeEnd = (v(5, 1f) - v(5, 1f - e)) / e; val slopeStart = (v(6, e) - v(6, 0f)) / e
        assertEquals(slopeEnd, slopeStart, 1e-2f)
        // per-frame change at 30 fps over a 1.2 s key: constant and small (a step of 1 between keys → ≤ 1/36 per frame)
        var maxD = 0f; var prev = v(0, 0f)
        for (f in 1..360) { val tt = f / 36f; val cur = v(tt.toInt(), tt - tt.toInt()); maxD = maxOf(maxD, kotlin.math.abs(cur - prev)); prev = cur }
        assertTrue("max per-frame change $maxD", maxD <= 1f / 36f + 1e-4f)
    }

    @Test fun layerAlphasCompositeToTheWeights() {
        for (i in 0..20) {
            val u = i / 20f; val w = SkyFreezeCore.bspline(u); val a = SkyFreezeCore.layerAlphas(u)
            // composite: x0 opaque → over with a1 → over with a2
            val c0 = 1f * (1 - a[1]) * (1 - a[2]); val c1 = a[1] * (1 - a[2]); val c2 = a[2]
            assertEquals(w[0], c0, 1e-5f); assertEquals(w[1], c1, 1e-5f); assertEquals(w[2], c2, 1e-5f)
        }
    }
}

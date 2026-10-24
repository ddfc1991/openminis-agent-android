package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindDriftCoreTest {
    @Test fun unknownWindIsHalfOfOldSpeed() {
        assertEquals(0.5f, WindDriftCore.targetRate(null), 1e-6f)
        assertEquals(0.5f, WindDriftCore.targetRate(Double.NaN), 1e-6f)
        assertEquals(0.5f, WindDriftCore.targetRate(-1.0), 1e-6f)
    }

    @Test fun calmBarelyMoves() {
        assertTrue(WindDriftCore.targetRate(0.0) <= 0.1f)
        assertTrue(WindDriftCore.targetRate(0.9) < 0.16f)
        assertTrue(WindDriftCore.targetRate(0.0) >= WindDriftCore.MIN_RATE)
    }

    @Test fun moderateWindIsAboutHalf() {
        for (w in listOf(3.0, 4.0, 5.0)) {
            val r = WindDriftCore.targetRate(w)
            assertTrue("$w → $r", r in 0.44f..0.56f)
        }
    }

    @Test fun strongWindNeverFasterThanToday() {
        assertEquals(1.0f, WindDriftCore.targetRate(12.0), 1e-6f)
        assertEquals(1.0f, WindDriftCore.targetRate(40.0), 1e-6f)
        assertTrue(WindDriftCore.targetRate(9.0) in 0.75f..1.0f)
    }

    @Test fun monotonic() {
        var last = 0f
        var w = 0.0
        while (w <= 20.0) { val r = WindDriftCore.targetRate(w); assertTrue("$w", r >= last); last = r; w += 0.25 }
    }

    @Test fun easingHasNoJump() {
        // a new reading (calm → storm) at 12 fps: the per-frame rate change stays tiny, and it gets there in ~a minute
        var r = WindDriftCore.targetRate(0.5)
        val target = WindDriftCore.targetRate(15.0)
        var maxStep = 0f
        repeat(12 * 60) { val n = WindDriftCore.ease(r, target, 1f / 12f); maxStep = maxOf(maxStep, kotlin.math.abs(n - r)); r = n }
        assertTrue("max per-frame step $maxStep", maxStep < 0.005f)
        assertTrue("after 60 s $r", r > 0.9f)
    }

    @Test fun clockIsContinuousAcrossRateChange() {
        val c = WindDriftCore.Clock()
        var t = 1000f
        var prev = c.advance(t, 0.5f)
        assertEquals(500f, prev, 1e-3f)
        var maxDelta = 0f
        for (i in 0 until 12 * 120) {
            t += 1f / 12f
            val target = if (i < 600) 0.1f else 1.0f
            val now = c.advance(t, target)
            val d = now - prev
            assertTrue("never goes backwards", d >= 0f)
            maxDelta = maxOf(maxDelta, d)
            prev = now
        }
        // at 12 fps and rate ≤ 1 a frame moves the clock ≤ 1/12 s
        assertTrue(maxDelta <= 1f / 12f + 1e-4f)
    }

    @Test fun clockIgnoresJumpsAndHolds() {
        val c = WindDriftCore.Clock()
        c.advance(100f, 0.5f)
        val a = c.advance(101f, 0.5f)
        val b = c.advance(500f, 0.5f)       // resume after minutes: no sudden drift
        assertEquals(a, b, 1e-4f)
        val h = c.advance(500f, 0.5f)       // held page (same sky time)
        assertEquals(b, h, 1e-4f)
        val d = c.advance(400f, 0.5f)       // clock went back (day wrap / smoke shift)
        assertEquals(h, d, 1e-4f)
        assertTrue(c.advance(401f, 0.5f) > d)
    }
    // [38.9.11.2] 晴 「闪光」: key a asked after key b (layer reset) must get its own, earlier cloud time
    @Test fun earlierKeyGetsItsOwnCloudTime() {
        val c = WindDriftCore.Clock()
        c.advance(100f, 0.5f); c.advance(101.2f, 0.5f)
        val b = c.advance(102.4f, 0.5f)
        val a = c.advance(101.2f, 0.5f)            // out of order: earlier
        assertTrue("earlier key is earlier", a < b - 0.1f)
        assertEquals(b, c.cloud, 1e-4f)             // the clock did not move
        assertTrue(c.advance(103.6f, 0.5f) > b)
    }

    @Test fun peekDoesNotMoveTheClock() {
        val c = WindDriftCore.Clock()
        assertEquals(50f, c.at(100f), 1e-4f)        // no clock yet: the cold-start rule
        c.advance(100f, 0.5f); val b = c.advance(101f, 0.5f)
        val p = c.at(101.5f)
        assertTrue(p > b && p < b + 1f)
        assertEquals(b, c.cloud, 1e-4f)
        assertEquals(b, c.at(400f), 1e-4f)          // far away: the current clouds (a still page shows what it showed)
    }

    @Test fun legacyKeepsTheOldFreeze() {
        val c = WindDriftCore.Clock(); c.legacy = true
        c.advance(100f, 0.5f); val b = c.advance(101.2f, 0.5f)
        assertEquals(b, c.advance(100f, 0.5f), 1e-4f)
    }

    /** [38.9.11.3] 「云雾基本不动」: the effective rate never drops below the visible floor, never above the max */
    @Test fun visibleFloor() {
        assertEquals(WindDriftCore.VISIBLE_MIN, WindDriftCore.effective(WindDriftCore.targetRate(0.0), 0.6f), 1e-6f)
        assertEquals(WindDriftCore.VISIBLE_MIN, WindDriftCore.effective(WindDriftCore.targetRate(null), 0.6f), 1e-6f)
        assertEquals(maxOf(0.6f, WindDriftCore.VISIBLE_MIN), WindDriftCore.effective(WindDriftCore.targetRate(15.0), 0.6f), 1e-6f)
        assertTrue(WindDriftCore.effective(5f, 5f) <= WindDriftCore.MAX_RATE)
    }
}

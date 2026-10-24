package com.openminis.app.plus

import com.openminis.app.plus.SkyModeCore.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyModeCoreTest {
    @Test fun defaultIsSmooth() {
        assertEquals(Mode.SMOOTH, SkyModeCore.parse(null))
        assertEquals(Mode.SMOOTH, SkyModeCore.parse("bogus"))
        assertEquals(Mode.RICH, SkyModeCore.parse("rich"))
        assertEquals(Mode.OFF, SkyModeCore.parse("off"))
    }

    @Test fun labels() {
        assertEquals(listOf("流畅", "精美", "关闭"), Mode.values().map { it.label })
    }

    @Test fun smoothIsCheaperAndCalmerThanRich() {
        val s = SkyModeCore.params(Mode.SMOOTH); val r = SkyModeCore.params(Mode.RICH)
        assertTrue(s.keyMs > r.keyMs)          // fewer shader runs
        assertTrue(s.skyDiv >= r.skyDiv)       // smaller buffer
        assertTrue(s.calm >= r.calm)
        assertTrue(s.cloudRate <= r.cloudRate) // slower drift
        assertTrue(s.blendFps in 24..60)
        // shader runs per second in 流畅 stay ≤ 1
        assertTrue(1000f / s.keyMs <= 1f)
    }

    @Test fun saverAndReduceMotionKeepDriftingOnlyForcedStillIsOff() {
        assertEquals(Mode.RICH, SkyModeCore.effective(Mode.RICH, powerSave = true, reduceMotion = false, forcedStatic = false))
        assertEquals(Mode.SMOOTH, SkyModeCore.effective(Mode.SMOOTH, false, true, false))
        assertEquals(Mode.OFF, SkyModeCore.effective(Mode.SMOOTH, false, false, true))
        assertEquals(Mode.RICH, SkyModeCore.effective(Mode.RICH, false, false, false))
        assertEquals(SkyModeCore.LOW_POWER_FPS, SkyModeCore.lowPowerFps(30, powerSave = true, reduceMotion = false))
        assertEquals(SkyModeCore.LOW_POWER_FPS, SkyModeCore.lowPowerFps(30, false, true))
        assertEquals(30, SkyModeCore.lowPowerFps(30, false, false))
        assertEquals(0, SkyModeCore.lowPowerFps(0, true, true))   // 关闭 stays still
        assertTrue(SkyModeCore.FALLBACK_FPS > 0)
    }

    @Test fun loopOnlyForTheLiveOwnerAndNotWhenOff() {
        assertEquals(0, SkyModeCore.loopFps(Mode.OFF, false, true))
        assertEquals(0, SkyModeCore.loopFps(Mode.SMOOTH, false, false))
        assertEquals(30, SkyModeCore.loopFps(Mode.SMOOTH, false, true))
        assertEquals(12, SkyModeCore.loopFps(Mode.SMOOTH, true, true))
    }

    @Test fun chatKeysAreSlower() {
        assertTrue(SkyModeCore.keyMs(Mode.SMOOTH, true) > SkyModeCore.keyMs(Mode.SMOOTH, false))
    }

    @Test fun liveScenes() {
        assertTrue(SkyModeCore.liveScene("rain")); assertTrue(SkyModeCore.liveScene("snow"))
        assertFalse(SkyModeCore.liveScene("fog")); assertFalse(SkyModeCore.liveScene("sunny"))
    }

    @Test fun keysBracketNowOnAFixedGrid() {
        val k = SkyModeCore.keys(10_500L, 1200L)
        assertEquals(9600L, k.a); assertEquals(10_800L, k.b)
        assertEquals(0.75f, k.k, 1e-4f)
        val k0 = SkyModeCore.keys(9600L, 1200L)
        assertEquals(9600L, k0.a); assertEquals(0f, k0.k, 1e-6f)
        // negative clocks (smoke offsets) still bracket
        val kn = SkyModeCore.keys(-100L, 1200L)
        assertTrue(kn.a <= -100L && kn.b > -100L)
    }

    @Test fun degradedLevelRendersSmaller() {
        assertTrue(SkyModeCore.skyDiv(Mode.SMOOTH, 1) > SkyModeCore.skyDiv(Mode.SMOOTH, 0))
    }

    @Test fun registryOnlyNewestIsLive() {
        val r = SkyModeCore.Registry()
        val home = r.enter(); assertTrue(r.isLive(home))
        val chat = r.enter(); assertTrue(r.isLive(chat)); assertFalse(r.isLive(home))
        r.leave(chat); assertTrue(r.isLive(home))
        r.leave(home); assertEquals(0, r.size()); assertEquals(0L, r.live())
    }

    /** [38.9.11.3] 云雾 really floats: keys slide by the drift, margins cover the worst shift, 晴 / 夜 untouched */
    @Test fun fogDriftShift() {
        assertTrue(SkyModeCore.driftUps("fog") < 0f && SkyModeCore.driftUps("cloudy") < 0f && SkyModeCore.driftUps("overcast") < 0f)
        assertTrue(SkyModeCore.driftUps("sunny") < 0f && SkyModeCore.driftUps("night") < 0f && SkyModeCore.driftUps("sunset") < 0f)
        assertEquals(0f, SkyModeCore.driftUps("rain"), 0f)   // live path: re-rendered every frame, no key shift
        assertEquals(0f, SkyModeCore.marginUnits("rain", Mode.SMOOTH, false), 0f)
        for (m in listOf(Mode.SMOOTH, Mode.RICH)) for (chat in listOf(false, true)) {
            val mg = SkyModeCore.marginUnits("fog", m, chat)
            // worst case: oldest key (2 steps) at the fastest effective rate stays inside the margin (no edge shows)
            val worst = kotlin.math.abs(SkyModeCore.driftUps("fog")) * WindDriftCore.MAX_RATE * 2f * SkyModeCore.keyMs(m, chat) / 1000f
            assertTrue("$m chat=$chat margin $mg vs $worst", mg >= worst || mg == 90f)
        }
        // an older key moves left-to-right back into place: shift is positive... direction: older key (smaller ct) → negative dx
        val w = 1080f
        val older = SkyModeCore.shiftPx("fog", nowCt = 100f, keyCt = 99f, widthPx = w, marginUnits = 60f)
        val newer = SkyModeCore.shiftPx("fog", nowCt = 100f, keyCt = 100.5f, widthPx = w, marginUnits = 60f)
        assertTrue(older < 0f && newer > 0f)
        assertEquals(SkyModeCore.driftUps("fog") * w / 270f, older, 1e-3f)
        assertEquals(60f * w / 270f, SkyModeCore.shiftPx("fog", 0f, 1000f, w, 60f), 1e-3f)   // clamped to the margin
        // clearly drifting (user 20:39): at the floor rate the mid fog layer crosses the screen in 10–30 s
        val crossS = 270f / (kotlin.math.abs(SkyModeCore.driftUps("fog")) * WindDriftCore.VISIBLE_MIN)
        assertTrue("fog crosses in $crossS s", crossS in 10f..30f)
    }
}

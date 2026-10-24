package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.10 i30] sky performance rules + a source scan that the sky never builds shaders / decodes on the main path. */
class SkyPerfCoreTest {
    @Test fun software() {
        assertTrue(SkyPerfCore.isSoftwareRenderer("Android Emulator OpenGL ES Translator (Google SwiftShader)"))
        assertTrue(SkyPerfCore.isSoftwareRenderer("llvmpipe (LLVM 15.0.7, 256 bits)"))
        assertTrue(SkyPerfCore.isSoftwareRenderer("Gallium 0.4 on softpipe"))
        assertFalse(SkyPerfCore.isSoftwareRenderer("Mali-G720-Immortalis MC12"))
        assertFalse(SkyPerfCore.isSoftwareRenderer("Adreno (TM) 750"))
        assertFalse(SkyPerfCore.isSoftwareRenderer(null))
    }

    @Test fun fps() {
        assertEquals(12, SkyPerfCore.fps(false, 0, false, false, false))
        assertEquals(15, SkyPerfCore.fps(true, 0, false, false, false))
        assertTrue(SkyPerfCore.FPS <= 12 && SkyPerfCore.RAIN_FPS <= 15)
        assertEquals(6, SkyPerfCore.fps(false, 1, false, false, false))
        assertEquals(7, SkyPerfCore.fps(true, 1, false, false, false))
        assertEquals(0, SkyPerfCore.fps(false, 2, false, false, false))
        assertEquals(SkyPerfCore.pass2Div(0), SkyPerfCore.pass2Div(1))         // stars keep size + twinkle at L1
        assertEquals(4, SkyPerfCore.fps(false, 0, true, false, false))
        assertEquals(0, SkyPerfCore.fps(false, 0, false, true, false))
        assertEquals(0, SkyPerfCore.fps(true, 0, false, false, true))
        assertTrue(SkyPerfCore.skyDiv(0) >= 4f && SkyPerfCore.skyDiv(1) > SkyPerfCore.skyDiv(0))
        assertTrue(SkyPerfCore.pass2Div(0) >= 2f)
        assertEquals(WeatherSkyCore.FPS, SkyPerfCore.FPS)
    }

    /** feeds [n] frames of cost [ms] at [fps] starting at [t0] (ms clock); returns the clock after the last frame */
    private fun SkyPerfCore.Degrader.run(n: Int, t0: Long, fps: Int = 12, vsync: Float = 8.33f, ms: (Int) -> Float): Long {
        var t = t0; val dt = 1000L / fps
        for (k in 0 until n) { feed(ms(k), vsync, t); t += dt }
        return t
    }

    @Test fun thresholds() {
        assertEquals(33f, SkyPerfCore.slowMs(8.33f), 0.01f)     // 120 Hz → floor 33 ms
        assertEquals(33.34f, SkyPerfCore.slowMs(16.67f), 0.01f) // 60 Hz → 2 × vsync
        assertEquals(66f, SkyPerfCore.slowMs(33f), 0.01f)       // 30 Hz
        // render cost = draw + sync + issue + swap (+ GPU on 31+), in ns → ms
        assertEquals(10f, SkyPerfCore.renderCostMs(4_000_000, 1_000_000, 2_000_000, 3_000_000), 0.001f)
        assertEquals(15f, SkyPerfCore.renderCostMs(4_000_000, 1_000_000, 2_000_000, 3_000_000, 5_000_000), 0.001f)
        assertEquals(10f, SkyPerfCore.renderCostMs(4_000_000, 1_000_000, 2_000_000, 3_000_000, -1), 0.001f)
    }

    @Test fun hardwareGpu() {
        assertTrue(SkyPerfCore.isHardwareGpu("Mali-G720-Immortalis MC12"))
        assertTrue(SkyPerfCore.isHardwareGpu("Adreno (TM) 750"))
        assertTrue(SkyPerfCore.isHardwareGpu("PowerVR Rogue GE8320"))
        assertTrue(SkyPerfCore.isHardwareGpu("Samsung Xclipse 940"))
        assertFalse(SkyPerfCore.isHardwareGpu("Android Emulator OpenGL ES Translator (Google SwiftShader)"))
        assertFalse(SkyPerfCore.isHardwareGpu(null))
        assertEquals(1, SkyPerfCore.maxAuto("Mali-G720-Immortalis MC12"))
        assertEquals(SkyPerfCore.MAX_AUTO, SkyPerfCore.maxAuto("some unknown GPU"))
        assertTrue("auto never reaches stills", SkyPerfCore.MAX_AUTO < SkyPerfCore.STILLS)
    }

    /** build d regression: a real 120 Hz phone with UI jank (chat streaming, scrolling, 40 ms frames) must stay at L0 */
    @Test fun realPhoneDoesNotDegrade() {
        val d = SkyPerfCore.Degrader(); d.maxAuto = SkyPerfCore.maxAuto("Mali-G720-Immortalis MC12")
        var t = d.run(600, 0L) { k -> if (k % 5 == 0) 40f else 4f + (k % 6) }   // 20 % 40 ms frames, rest 4–9 ms
        assertEquals(0, d.level)
        t = d.run(30, t) { 45f }                                                // one window of a scroll burst
        t = d.run(30, t) { k -> if (k % 3 == 0) 40f else 7f }                   // then 33 % slow
        t = d.run(30, t) { 50f }                                                // another burst, not consecutive
        assertEquals(0, d.level)
        // even the old rule's trigger (12/30 > 20 ms) for minutes is fine now
        d.run(2000, t) { k -> if (k % 30 < 14) 28f else 8f }
        assertEquals(0, d.level)
    }

    @Test fun slowRenderGoesToL1OnlyAndNotFasterThanGap() {
        val d = SkyPerfCore.Degrader()
        var t = d.run(30, 0L) { 60f }
        assertEquals("one slow window is not enough", 0, d.level)
        t = d.run(30, t) { 60f }
        assertEquals(1, d.level)
        t = d.run(600, t) { 60f }                                               // slow but no hangs: stays L1
        assertEquals(1, d.level)
    }

    @Test fun hangsReachL2OnlyWithoutHardwareGpu() {
        val e = SkyPerfCore.Degrader()                                          // unknown GPU, emulator-class stalls
        var t = e.run(60, 0L) { 3000f }
        assertEquals(1, e.level)
        t = e.run(60, t, fps = 1000) { 3000f }                                 // 2 windows inside 10 s → no second step yet
        assertEquals(1, e.level)
        t = e.run(60, t + 10_000L) { 3000f }
        assertEquals(2, e.level)
        e.run(600, t) { 3000f }
        assertEquals("auto never reaches stills", 2, e.level)
        val h = SkyPerfCore.Degrader(); h.maxAuto = SkyPerfCore.maxAuto("Adreno (TM) 750")
        h.run(2000, 0L) { 3000f }
        assertEquals(1, h.level)
    }

    @Test fun recovers() {
        val d = SkyPerfCore.Degrader()
        var t = d.run(60, 0L) { 60f }
        assertEquals(1, d.level)
        t = d.run(12 * 20, t) { 6f }                                            // 20 s good: not yet
        assertEquals(1, d.level)
        t = d.run(12 * 15, t) { 6f }                                            // > 30 s good → back to L0
        assertEquals(0, d.level)
        // a second trip waits longer before stepping up (no flapping), but still recovers
        t = d.run(60, t + 10_000L) { 60f }
        assertEquals(1, d.level)
        t = d.run(12 * 40, t) { 6f }
        assertEquals(1, d.level)
        d.run(12 * 30, t) { 6f }
        assertEquals(0, d.level)
        // L2 recovers step by step
        val e = SkyPerfCore.Degrader()
        var u = e.run(60, 0L) { 3000f }; u = e.run(60, u + 10_000L) { 3000f }
        assertEquals(2, e.level)
        u = e.run(12 * 35, u) { 6f }
        assertEquals(1, e.level)
    }

    @Test fun labels() {
        assertEquals("stills: software GPU", SkyPerfCore.label(0, true))
        assertEquals("full", SkyPerfCore.label(0, false))
        assertEquals("stills: too slow", SkyPerfCore.label(3, false))
    }

    /** the composable must not construct the GPU object (texture decode + shader parse) during composition */
    @Test fun noMainThreadBuild() {
        val f = listOf("src/main/java/com/openminis/app/ui/plus/WeatherSky.kt", "app/src/main/java/com/openminis/app/ui/plus/WeatherSky.kt")
            .map { File(it) }.firstOrNull { it.exists() } ?: return
        val src = f.readText()
        assertTrue("prepare runs off the main thread", src.contains("Dispatchers.Default") && src.contains("fun prepare("))
        assertFalse("no Gpu(c) inside remember{}", Regex("remember\\([^)]*\\)\\s*\\{[^}]*Gpu\\(").containsMatchIn(src))
        assertTrue("frame metrics drive the degrade", src.contains("addOnFrameMetricsAvailableListener"))
        assertFalse("render cost, not the whole window's TOTAL_DURATION", src.contains("FrameMetrics.TOTAL_DURATION"))
        assertTrue("first-draw frames ignored", src.contains("FIRST_DRAW_FRAME"))
        assertTrue("software renderer check", src.contains("GL_RENDERER") || src.contains("GLES20.GL_RENDERER"))
    }
}

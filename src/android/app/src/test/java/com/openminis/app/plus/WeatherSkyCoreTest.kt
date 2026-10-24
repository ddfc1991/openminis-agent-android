package com.openminis.app.plus

import com.openminis.app.plus.WeatherSkyCore.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.10 sky v7] living sky: scene choice, theme grades, text guard (≥ 4.5:1), shader segments + AGSL rules, assets, clock. */
class WeatherSkyCoreTest {
    @Test fun scenes() {
        assertEquals(Scene.SUNNY, WeatherSkyCore.pick(0, 11))
        assertEquals(Scene.CLOUDY, WeatherSkyCore.pick(1, 11))
        assertEquals(Scene.CLOUDY, WeatherSkyCore.pick(2, 11))
        assertEquals(Scene.OVERCAST, WeatherSkyCore.pick(3, 11))
        assertEquals(Scene.RAIN, WeatherSkyCore.pick(61, 23))
        assertEquals(Scene.RAIN, WeatherSkyCore.pick(95, 12))
        assertEquals(Scene.SNOW, WeatherSkyCore.pick(73, 9))
        assertEquals(Scene.FOG, WeatherSkyCore.pick(45, 2))
        assertEquals(Scene.NIGHT, WeatherSkyCore.pick(0, 23))
        assertEquals(Scene.NIGHT, WeatherSkyCore.pick(1, 12, isDay = false))
        assertEquals(Scene.SUNSET, WeatherSkyCore.pick(1, 18))
        assertEquals(Scene.SUNNY, WeatherSkyCore.pick(null, 10))
        for (h in 0..23) assertTrue(!WeatherSkyCore.isGrey(WeatherSkyCore.pick(null, h)))
        assertTrue(WeatherSkyCore.isGrey(Scene.OVERCAST) && WeatherSkyCore.isGrey(Scene.FOG) && !WeatherSkyCore.isGrey(Scene.CLOUDY))
        assertEquals(Scene.NIGHT, WeatherSkyCore.pick(null, 2))
        assertEquals(Scene.SUNSET, WeatherSkyCore.pick(null, 17))
        assertEquals("STARS", Scene.NIGHT.pass2); assertEquals("GLASS", Scene.RAIN.pass2); assertEquals("SNOW", Scene.SNOW.pass2); assertNull(Scene.FOG.pass2)
    }

    @Test fun motion() {
        assertEquals(WeatherSkyCore.FPS, WeatherSkyCore.fps(false, false, false))
        assertTrue(WeatherSkyCore.FPS <= 20)
        assertEquals(WeatherSkyCore.SLOW_FPS, WeatherSkyCore.fps(true, false, false))
        assertEquals(0, WeatherSkyCore.fps(false, true, false)); assertEquals(0, WeatherSkyCore.fps(false, false, true))
        assertTrue(WeatherSkyCore.keepLooping(20, false)); assertTrue(WeatherSkyCore.keepLooping(0, true)); assertTrue(!WeatherSkyCore.keepLooping(0, false))
        assertEquals("static: api<33", WeatherSkyCore.mode(31, null, false, false, false))
        assertEquals("static: shader error", WeatherSkyCore.mode(35, "x", false, false, false))
        assertEquals("shader live", WeatherSkyCore.mode(35, null, false, false, false))
        assertTrue(WeatherSkyCore.mode(35, null, true, false, false).startsWith("shader slow"))
        assertTrue(WeatherSkyCore.mode(35, null, false, true, false).startsWith("shader still"))
    }

    @Test fun skyClockIsContinuousAndWrapsAtFourAm() {
        val tz = 8 * 3_600_000L
        val a = WeatherSkyCore.skyTime(1_760_000_000_000L, tz); val b = WeatherSkyCore.skyTime(1_760_000_000_050L, tz)
        assertEquals(0.05f, b - a, 0.01f)
        // 04:00 local = 20:00 UTC of the day before
        val fourAm = java.time.ZonedDateTime.of(2026, 10, 20, 4, 0, 0, 0, java.time.ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        assertEquals(0f, WeatherSkyCore.skyTime(fourAm, tz), 0.01f)
        assertEquals(86_399f, WeatherSkyCore.skyTime(fourAm - 1000L, tz), 0.01f)
        assertTrue(WeatherSkyCore.skyTime(fourAm + 3_600_000L, tz) in 3599f..3601f)
    }

    @Test fun guardKeepsInkReadable() {
        val ink = WeatherSkyCore.lum(0x2A1E1A); val ink2 = WeatherSkyCore.lum(0x6B5A50)
        val inkD = WeatherSkyCore.lum(0xF6F0EB); val ink2D = WeatherSkyCore.lum(0xCDC0B8)
        // the floor (light) / ceiling (dark) each guard sets gives ≥ 4.5:1 for the ink it protects
        assertTrue(WeatherSkyCore.contrast(WeatherSkyCore.guard(false, false).first, ink) >= 4.5f)
        // [38.9.11 i32] the chat page shows the same sky as home (user 08:54: chat was flat beige); bubbles carry the glass
        assertEquals(WeatherSkyCore.guard(false, false), WeatherSkyCore.guard(true, false))
        assertTrue(WeatherSkyCore.contrast(WeatherSkyCore.guard(false, true).second, inkD) >= 4.5f)
        assertEquals(WeatherSkyCore.guard(false, true), WeatherSkyCore.guard(true, true))
        // a chat veil over a guarded sky only moves it further from the ink
        for (d in listOf(false, true)) for (s in Scene.values()) { assertEquals(0f, WeatherSkyCore.chatVeilColor(s, d).first, 0f); assertEquals(0f, WeatherSkyCore.stillChatVeil(s, d).first, 0f) }
    }

    @Test fun gradesPerSceneAndTheme() {
        for (s in Scene.values()) for (d in listOf(false, true)) {
            val g = WeatherSkyCore.grade(s, d)
            assertEquals(3, g.mul.size); assertTrue(g.lift in 0f..0.8f); assertTrue(g.mul.all { it in 0.2f..1f })
            // [i29] dark day = contrast c^(1+2·lift) then multiply: a pale sky value (0.8) still lands well below 0.7
            if (d && s != Scene.NIGHT && s != Scene.RAIN) assertTrue("$s dark is dimmed", g.mul.all { it * Math.pow(0.8, 1.0 + 2.0 * g.lift).toFloat() < 0.7f })
        }
        assertTrue(WeatherSkyCore.grade(Scene.NIGHT, false).lift > 0.3f)        // light 夜 = lifted blue-grey
        assertTrue((WeatherSkyCore.grade(Scene.NIGHT, false).tint and 255) > ((WeatherSkyCore.grade(Scene.NIGHT, false).tint shr 16) and 255))
    }

    private fun assets(): File = File(listOf("src/main/assets", "app/src/main/assets").first { File(it).isDirectory })
    private fun agsl(): String = File(assets(), "skybg/sky.agsl").readText()

    @Test fun everySceneHasShaderAndStill() {
        val seg = WeatherSkyCore.segments(agsl())
        for (s in Scene.values()) {
            val src = WeatherSkyCore.skySource(seg, s)
            assertTrue(src.contains("half3 skyColor(") && src.contains("half4 main("))
            assertEquals("one skyColor per pass-1 shader", 1, Regex("""half3 skyColor\(""").findAll(src).count())
            assertEquals(1, Regex("""half4 main\(""").findAll(src).count())
            s.pass2?.let { assertNotNull(WeatherSkyCore.pass2Source(seg, s)); assertEquals(1, Regex("""half4 main\(""").findAll(WeatherSkyCore.pass2Source(seg, s)!!).count()) }
            for (d in listOf("l", "d")) assertTrue("still ${s.key} $d", File(assets(), "skybg/still_${s.key}_$d.webp").length() in 500L..60_000L)
            assertEquals(4, WeatherSkyCore.mist.getValue(s.key).size)
        }
        assertTrue(WeatherSkyCore.pass2Source(seg, Scene.RAIN)!!.contains("uniform shader sky"))
        for (i in 0..3) assertTrue(File(assets(), "skybg/tex$i.webp").length() in 5_000L..120_000L)
        val total = File(assets(), "skybg").listFiles()!!.sumOf { it.length() }
        assertTrue("sky assets ≤ 600 KB ($total)", total <= 600_000L)
        assertTrue("old photo stills gone", File(assets(), "skybg").listFiles()!!.none { it.name.startsWith("sky_") })
    }

    @Test fun overcastIsNotFog() {
        // 38.9.9: 阴 and 雾 looked identical (two stills graded into the same beige). Now two different procedural skies.
        val seg = WeatherSkyCore.segments(agsl())
        val a = seg.getValue("SKY overcast"); val b = seg.getValue("SKY fog")
        assertTrue(a != b)
        assertTrue("阴 is a grey deck with structure (bellies)", a.contains("belly"))
        assertTrue("雾 is milky mist with hidden warm light", b.contains("warm") && b.contains("milk"))
    }

    @Test fun agslFollowsSkSLLoopRules() {
        val code = agsl().lines().map { it.substringBefore("//") }.joinToString("\n")
        assertTrue("no while / do loops", !Regex("""\b(while|do)\b""").containsMatchIn(code))
        val loops = Regex("""for\s*\(([^)]*)\)""").findAll(code).map { it.groupValues[1] }.toList()
        assertTrue(loops.isNotEmpty())
        // ES2 (GLSL ES 1.0 appendix A) loop form: int index, constant init / bound, ++
        loops.forEach { assertTrue("loop form: $it", Regex("""^\s*int (\w+) = -?\d+; \1 (<|<=) \d+; \1\+\+\s*$""").matches(it)) }
        assertTrue("no #define / #include", !code.contains("#define") && !code.contains("#include"))
        assertTrue("no unresolved colour macro", !code.contains("H(#"))
        assertTrue("no sun / moon disc helpers", !Regex("""\b(sunDisc|moonDisc|drawSun|drawMoon)\b""").containsMatchIn(code))
    }
}

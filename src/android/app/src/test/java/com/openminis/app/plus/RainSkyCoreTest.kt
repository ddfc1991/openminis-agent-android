package com.openminis.app.plus

import com.openminis.app.plus.WeatherSkyCore.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11 c] 雨 v5: rain.agsl merges over sky.agsl, keeps SkSL rules, both looks present, guard relaxed only on home's open stage. */
class RainSkyCoreTest {
    private fun assets(): File = File(listOf("src/main/assets", "app/src/main/assets").first { File(it).isDirectory })
    private fun base() = WeatherSkyCore.segments(File(assets(), "skybg/sky.agsl").readText())
    private fun rain() = File(assets(), RainSkyCore.ASSET).readText()

    @Test fun mergeReplacesOnlyRain() {
        val b = base(); val m = RainSkyCore.merge(b, rain())
        assertEquals(b.keys, m.keys)
        for (k in b.keys) if (k != "SKY rain" && k != "GLASS") assertSame(b[k], m[k])
        val sky = WeatherSkyCore.skySource(m, Scene.RAIN)
        assertTrue("light = water surface", sky.contains("rainLightSky") && sky.contains("caustic"))
        assertTrue("dark = night behind the window", sky.contains("rainDarkSky"))
        assertEquals(1, Regex("""half3 skyColor\(""").findAll(sky).count())
        assertEquals(1, Regex("""half4 main\(""").findAll(sky).count())
        val g = WeatherSkyCore.pass2Source(m, Scene.RAIN)!!
        assertTrue(g.contains("uniform shader sky") && g.contains("rainDarkGlass") && g.contains("rainLightGlass"))
        assertTrue("lamp + drizzle + petals + ripples", g.contains("lampLight") && g.contains("drizzle") && g.contains("petal") && g.contains("ripple"))
        assertEquals(1, Regex("""half4 main\(""").findAll(g).count())
        assertTrue("no nested segment marker", !Regex("(?m)^//#RAIN_COMMON").containsMatchIn(sky + g))
        // other scenes untouched
        assertEquals(WeatherSkyCore.skySource(b, Scene.SUNNY), WeatherSkyCore.skySource(m, Scene.SUNNY))
        assertEquals(WeatherSkyCore.pass2Source(b, Scene.NIGHT), WeatherSkyCore.pass2Source(m, Scene.NIGHT))
    }

    @Test fun unusableSourceKeepsBase() {
        val b = base()
        assertSame(b, RainSkyCore.merge(b, null)); assertSame(b, RainSkyCore.merge(b, "")); assertSame(b, RainSkyCore.merge(b, "//#SKY rain\nx"))
    }

    @Test fun rainFollowsSkSLRules() {
        val code = rain().lines().map { it.substringBefore("//") }.joinToString("\n")
        assertTrue(!Regex("""\b(while|do)\b""").containsMatchIn(code))
        val loops = Regex("""for\s*\(([^)]*)\)""").findAll(code).map { it.groupValues[1] }.toList()
        assertTrue(loops.size >= 6)
        loops.forEach { assertTrue("loop form: $it", Regex("""^\s*int (\w+) = -?\d+; \1 (<|<=) \d+; \1\+\+\s*$""").matches(it)) }
        assertTrue(!code.contains("#define") && !code.contains("H(#"))
        // pow(x, 2.0) is undefined for x < 0 on GPUs (it broke the lamp flicker in the offline render) → sq()
        assertTrue("no pow(…, 2.0)", !Regex("""pow\([^;]*,\s*2\.0\)""").containsMatchIn(code))
        // only sky.agsl's SHARED uniforms + the glass input
        val uni = Regex("""uniform\s+\w+\s+(\w+)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("sky", "gk"), uni)
        assertTrue("no green in the light palette", !code.contains("#7FA87F") && !code.contains("green"))
    }

    @Test fun stillsAndBudget() {
        for (d in listOf("l", "d")) assertTrue(File(assets(), "skybg/still_rain_$d.webp").length() in 500L..60_000L)
        assertTrue(File(assets(), RainSkyCore.ASSET).length() in 5_000L..60_000L)
        assertTrue(File(assets(), "skybg").listFiles()!!.sumOf { it.length() } <= 600_000L)
    }

    @Test fun guardRelaxedOnlyInOpenStageOnHome() {
        assertEquals(0.15f, RainSkyCore.darkCeiling(0.15f, 0.20f), 1e-4f)     // behind the greeting / cards
        assertEquals(0.15f, RainSkyCore.darkCeiling(0.15f, 0.95f), 1e-4f)     // behind the bottom bar
        assertTrue(RainSkyCore.darkCeiling(0.15f, 0.70f) > 0.7f)              // lamp in the open stage stays bright
        assertEquals(0.075f, RainSkyCore.darkCeiling(0.075f, 0.70f), 1e-4f)   // a strict (chat) ceiling is never relaxed
        assertEquals(1f, RainSkyCore.darkCeiling(1f, 0.70f), 1e-4f)
        // mirror of the shader constants
        assertTrue(rain().contains("mix(gHi, 0.80, rzOpen(yo))") && rain().contains("ss(0.55, 0.67, y) * (1.0 - ss(0.82, 0.88, y))"))
    }

    @Test fun capsUnchanged() {
        assertEquals(15, SkyPerfCore.fps(true, 0, false, false, false))
        assertEquals(7, SkyPerfCore.fps(true, 1, false, false, false))
        assertEquals(0, SkyPerfCore.fps(true, 2, false, false, false))
    }
}

package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] thin glass (light 30–40 % / dark 35–45 %, cap 45 / 50 %), readability from ink2 + halo. */
class GlassAutoTest {
    private val lightTintC = 0xFFFFF5EF.toInt(); private val darkTintC = 0xFF221B1E.toInt()
    private val lightInk2C = 0xFF6B5A50.toInt(); private val darkInk2C = 0xFFCDC0B8.toInt()
    private val lightInkC = 0xFF2A1E1A.toInt(); private val darkInkC = 0xFFF6F0EB.toInt()
    private val lightTint = GlassAuto.lum(lightTintC)
    private val darkTint = GlassAuto.lum(darkTintC)
    private val lightInk2 = GlassAuto.lum(lightInk2C)
    private val darkInk2 = GlassAuto.lum(darkInk2C)

    private fun plan(dark: Boolean, s: GlassAuto.Stats) =
        if (dark) GlassAuto.plan(true, darkTintC, darkInk2C, darkInkC, s) else GlassAuto.plan(false, lightTintC, lightInk2C, lightInkC, s)

    /** every band: the planned ink2 with its halo reaches 4.5:1, or the halo is at its maximum */
    private fun readable(dark: Boolean, s: GlassAuto.Stats, p: GlassAuto.Plan) {
        val tint = if (dark) darkTint else lightTint
        s.bands.forEachIndexed { i, b ->
            val worst = if (dark) b.hi else b.lo
            val c = GlassAuto.textContrast(tint, p.per[i], p.halo[i], worst, GlassAuto.lum(p.ink2))
            assertTrue("band $i contrast $c a=${p.per[i]} halo=${p.halo[i]}", c >= 4.5f || p.halo[i] >= GlassAuto.HALO_MAX - 1e-4f)
        }
    }

    private fun thin(dark: Boolean, p: GlassAuto.Plan) {
        p.per.forEach { assertTrue("a=$it", it >= GlassAuto.min(dark) - 1e-4f && it <= GlassAuto.max(dark) + 1e-4f) }
        assertTrue(p.all <= GlassAuto.cap(dark))
    }

    @Test fun fogLightIsThinAndReadable() {
        val s = GlassAuto.fogStats(false, 1.25f, 1.05f)
        val p = plan(false, s)
        println("fog light a=${p.all} per=${p.per} mix=${p.inkMix} halo=${p.halo}")
        thin(false, p)
        assertTrue("a=${p.all}", p.all <= 0.40f + 1e-4f)   // the 38.9.3 look, not 86 %
        readable(false, s, p)
    }

    @Test fun fogDarkIsThinAndReadable() {
        val s = GlassAuto.fogStats(true, 1.2f, 1.05f)
        val p = plan(true, s)
        println("fog dark a=${p.all} per=${p.per} mix=${p.inkMix} halo=${p.halo}")
        thin(true, p)
        assertTrue("a=${p.all}", p.all <= 0.45f + 1e-4f)
        readable(true, s, p)
    }

    @Test fun thinFogIsMoreTransparentThanDenseFog() {
        val thin = GlassAuto.solve(false, lightTint, lightInk2, GlassAuto.fogStats(false, 0.6f, 0.85f)).second
        val dense = GlassAuto.solve(false, lightTint, lightInk2, GlassAuto.fogStats(false, 1.6f, 1.3f)).second
        println("thin=$thin dense=$dense")
        assertTrue(thin <= dense)
    }

    @Test fun lightPlainBackgroundUsesTheMostTransparentGlassAndTheUsualInk() {
        val px = IntArray(12 * 24) { 0xFFF4EAE4.toInt() }
        val s = GlassAuto.statsOf(px, 12, 24)
        val p = plan(false, s)
        assertEquals(GlassAuto.LIGHT_MIN, p.all, 0.001f)
        assertEquals(0f, p.inkMix, 0.001f)
        assertEquals(lightInk2C, p.ink2)
        p.halo.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test fun darkPhotoUnderLightGlassStaysUnderTheCapAndHelpsTheText() {
        // the case that made 38.9.4 go to 86 %: a dark photo under light glass
        val px = IntArray(12 * 24) { i -> if (i % 3 == 0) 0xFF101010.toInt() else 0xFF404040.toInt() }
        val s = GlassAuto.photoStats(px, 12, 24, 1f, 0xFFF4EAE4.toInt())
        val p = plan(false, s)
        println("dark photo light a=${p.all} mix=${p.inkMix} halo=${p.halo}")
        assertEquals(GlassAuto.BANDS, p.per.size)
        assertTrue("a=${p.all}", p.all <= GlassAuto.LIGHT_CAP)
        assertTrue("ink2 pulled toward ink", p.inkMix > 0f)
        assertTrue("halo on the hard bands", p.halo.any { it > 0f })
        readable(false, s, p)
    }

    @Test fun brightPhotoUnderDarkGlassStaysUnderTheCap() {
        val px = IntArray(12 * 24) { 0xFFF8F8F8.toInt() }
        val s = GlassAuto.statsOf(px, 12, 24)
        val p = plan(true, s)
        println("bright photo dark a=${p.all} mix=${p.inkMix} halo=${p.halo}")
        assertTrue("a=${p.all}", p.all <= GlassAuto.DARK_CAP)
        assertTrue(p.inkMix > 0f || p.halo.any { it > 0f })
        readable(true, s, p)
    }

    @Test fun haloOnlyWhereNeeded() {
        // top half dark (hard for dark ink), bottom half page colour (easy)
        val px = IntArray(12 * 24) { i -> if (i / 12 < 12) 0xFF202020.toInt() else 0xFFF4EAE4.toInt() }
        val s = GlassAuto.photoStats(px, 12, 24, 1f, 0xFFF4EAE4.toInt())
        val p = plan(false, s)
        println("split a=${p.per} halo=${p.halo}")
        assertTrue(p.halo.first() >= p.halo.last())
        assertEquals(0f, p.halo.last(), 0.001f)
        readable(false, s, p)
    }

    @Test fun mixInkEndsAtTheInks() {
        assertEquals(lightInk2C, GlassAuto.mixInk(lightInk2C, lightInkC, 0f))
        assertEquals(lightInkC, GlassAuto.mixInk(lightInk2C, lightInkC, 1f))
        assertTrue(GlassAuto.lum(GlassAuto.mixInk(lightInk2C, lightInkC, 0.5f)) < lightInk2)
        assertTrue(GlassAuto.lum(GlassAuto.mixInk(darkInk2C, darkInkC, 0.5f)) > darkInk2)
    }

    @Test fun busyBandNeedsMoreThanCalmBand() {
        val calm = GlassAuto.Band(0.45f, 0.45f, 0.45f, 0f)
        val busy = GlassAuto.statsOf(IntArray(12 * 24) { i -> if (i % 2 == 0) 0xFF505050.toInt() else 0xFFE0E0E0.toInt() }, 12, 24).bands[0]
        assertTrue(GlassAuto.solveBand(false, lightTint, lightInk2, busy) >= GlassAuto.solveBand(false, lightTint, lightInk2, calm))
    }
}

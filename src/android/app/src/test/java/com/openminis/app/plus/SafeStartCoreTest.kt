package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.12.1] OOM hotfix rules: small screenshots, safe start, heap gate for new sessions, image release. */
class SafeStartCoreTest {
    private val MB = 1048576L

    @Test fun shotScale() {
        // the phone's agent viewport: ~3.5k px long edge → 1280
        val s = SafeStartCore.shotScale(3600, 2496)
        val (w, h) = SafeStartCore.scaled(3600, 2496, s)
        assertEquals(1280, w); assertTrue(h in 885..889)
        assertEquals(1.0, SafeStartCore.shotScale(1000, 600), 0.0)
        assertEquals(1.0, SafeStartCore.shotScale(0, 600), 0.0)
        // full page 1080 × 32768: width fits, pixel budget decides
        val f = SafeStartCore.shotScale(1080, 32768, fullPage = true)
        val (fw, fh) = SafeStartCore.scaled(1080, 32768, f)
        assertTrue(fw.toLong() * fh <= SafeStartCore.SHOT_MAX_PIXELS_FULL + 50_000)
        assertTrue(fw <= 1280)
        // a 36 MB ARGB buffer (8.99 Mpx) never happens again
        assertTrue(w.toLong() * h * 4 < 5 * MB)
    }

    @Test fun safeStart() {
        val now = 10_000_000L
        assertTrue(SafeStartCore.safeStart(oom = true, crashesInWindow = 1))
        assertFalse(SafeStartCore.safeStart(oom = false, crashesInWindow = 1))
        val two = SafeStartCore.noteCrash(listOf(now - 300_000L), now)
        assertEquals(2, two.size); assertTrue(SafeStartCore.safeStart(false, two.size))
        val old = SafeStartCore.noteCrash(listOf(now - 900_000L), now)
        assertEquals(1, old.size); assertFalse(SafeStartCore.safeStart(false, old.size))
        assertEquals(8, SafeStartCore.noteCrash(List(20) { now - it }, now).size)
        assertTrue(SafeStartCore.markerLive(now - 60_000L, now))
        assertFalse(SafeStartCore.markerLive(now - 7 * 3_600_000L, now))
        assertFalse(SafeStartCore.markerLive(0, now))
    }

    @Test fun card() {
        assertEquals("上次内存不足退出，已暂停 5 个任务", SafeStartCore.cardTitle(5, true))
        assertEquals("上次内存不足退出，已为你回到首页", SafeStartCore.cardTitle(0, true))
        assertTrue(SafeStartCore.cardTitle(2, false).startsWith("上次连续出错退出"))
        assertTrue(SafeStartCore.cardBody(3).contains("继续"))
    }

    @Test fun slotGate() {
        assertEquals(5, SafeStartCore.slotLimit(200 * MB, 512 * MB, 5, 3))
        assertEquals(3, SafeStartCore.slotLimit(400 * MB, 512 * MB, 5, 3))   // 78 %: nothing new starts
        assertEquals(1, SafeStartCore.slotLimit(500 * MB, 512 * MB, 5, 0))   // never deadlocks
        assertEquals(5, SafeStartCore.slotLimit(500 * MB, 512 * MB, 5, 9))
        assertEquals(5, SafeStartCore.slotLimit(1, 0, 5, 0))
    }

    @Test fun images() {
        assertEquals(0, SafeStartCore.imagesToRelease(4))
        assertEquals(0, SafeStartCore.imagesToRelease(10))
        assertEquals(5, SafeStartCore.imagesToRelease(11))
        assertEquals(14, SafeStartCore.imagesToRelease(20))
        assertTrue(SafeStartCore.releasedImageNote("/var/minis/browser/a.jpg").contains("read_image /var/minis/browser/a.jpg"))
        assertFalse(SafeStartCore.releasedImageNote(null).contains("read_image"))
    }
}

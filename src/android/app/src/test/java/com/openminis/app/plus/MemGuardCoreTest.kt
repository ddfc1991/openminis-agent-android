package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.10 i28] memory guard rules: pressure, trim pacing, OOM survival, summary, bounded text. */
class MemGuardCoreTest {
    private val MB = 1048576L
    @Test fun levels() {
        assertEquals(MemGuardCore.Level.OK, MemGuardCore.level(100 * MB, 512 * MB))
        assertEquals(MemGuardCore.Level.HIGH, MemGuardCore.level(380 * MB, 512 * MB))
        assertEquals(MemGuardCore.Level.CRITICAL, MemGuardCore.level(460 * MB, 512 * MB))
        assertEquals(MemGuardCore.Level.OK, MemGuardCore.level(1, 0))
    }
    @Test fun trimPacing() {
        assertFalse(MemGuardCore.shouldTrim(MemGuardCore.Level.OK, 1_000_000, 0))
        assertTrue(MemGuardCore.shouldTrim(MemGuardCore.Level.HIGH, 1_000_000, 0))
        assertFalse(MemGuardCore.shouldTrim(MemGuardCore.Level.HIGH, 1_000_000, 950_000))
        assertTrue(MemGuardCore.shouldTrim(MemGuardCore.Level.CRITICAL, 1_000_000, 985_000))
    }
    @Test fun oom() {
        assertTrue(MemGuardCore.isOom(OutOfMemoryError("x")))
        assertTrue(MemGuardCore.isOom(RuntimeException(IllegalStateException(OutOfMemoryError()))))
        assertFalse(MemGuardCore.isOom(RuntimeException("no")))
        assertTrue(MemGuardCore.surviveOom(false, 10_000_000, 0))
        assertFalse("main thread never", MemGuardCore.surviveOom(true, 10_000_000, 0))
        assertFalse("second within 10 min", MemGuardCore.surviveOom(false, 10_000_000, 9_700_000))
        assertTrue(MemGuardCore.surviveOom(false, 10_000_000, 9_000_000))
    }
    @Test fun summaryAndClip() {
        val s = MemGuardCore.summary(400 * MB, 450 * MB, 512 * MB, 80 * MB, 600_000, 120, listOf("聊天缓存" to "普通 4 个"))
        assertTrue(s.contains("400.0 MB") && s.contains("512.0 MB") && s.contains("78%") && s.contains("线程 120") && s.contains("聊天缓存：普通 4 个"))
        val long = "a".repeat(100_000) + "TAIL"
        val c = MemGuardCore.clip(long, 64_000)
        assertTrue(c.length < 64_200); assertTrue(c.endsWith("TAIL")); assertTrue(c.contains("中间省略"))
        assertEquals("short", MemGuardCore.clip("short", 10))
    }
}

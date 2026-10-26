package com.openminis.app.plus

import com.openminis.app.ui.chat.minesStallWatchdog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [Mines-38.9.14.1 stall_watch] stream idle watchdog (first chunk + mid-stream) and per-tool limits. */
class StallWatchCoreTest {
    private val C = StallWatchCore

    @Test fun toolLimits() {
        assertEquals(90_000L, C.toolLimitMs("file_read", "{}"))
        assertEquals(90_000L, C.toolLimitMs("memory_get", "not json"))
        assertEquals(180_000L, C.toolLimitMs("browser_use", """{"action":"click"}"""))
        assertEquals(180_000L, C.toolLimitMs("browser_use", """{"action":"wait_for_otp"}"""))
        assertNull(C.toolLimitMs("browser_use", """{"action":"wait_for_human"}"""))
        assertEquals(180_000L, C.toolLimitMs("app_agent", "{}"))
        assertEquals(900_000L + C.SHELL_MARGIN_MS, C.toolLimitMs("shell_execute", """{"command":"ls"}"""))
        assertEquals(60_000L + C.SHELL_MARGIN_MS, C.toolLimitMs("shell_execute", """{"command":"ls","timeout":60}"""))
        assertTrue(C.toolTimeoutText("x", 90_000L).contains("90 秒"))
    }

    @Test fun stallErrorIsRecognised() {
        val e = StallWatch.stallError(40_000L, midStream = false)
        assertTrue(StallWatch.isStall(e))
        assertTrue(e.detail.contains("40 秒没有任何输出"))
        assertTrue(StallWatch.stallError(40_000L, true).detail.contains("没有新输出"))
        assertFalse(StallWatch.isStall(com.openminis.app.data.model.LLMError.TransientError("Stream idle")))
        assertFalse(StallWatch.isStall(null))
        assertEquals("模型没响应，已换 B", C.switchedLine("B"))
    }

    @Test fun healthyStreamPasses() = runBlocking {
        val out = flow { repeat(5) { emit(it); delay(20) } }.minesStallWatchdog(300).toList()
        assertEquals(listOf(0, 1, 2, 3, 4), out)
    }

    @Test fun silentFirstChunkStalls() = runBlocking {
        try { flow<Int> { delay(5_000) }.minesStallWatchdog(150).toList(); fail("no stall") }
        catch (e: com.openminis.app.data.model.LLMError.TransientError) { assertTrue(StallWatch.isStall(e)); assertTrue(e.detail.contains("没有任何输出")) }
    }

    @Test fun midStreamSilenceStalls() = runBlocking {
        val got = mutableListOf<Int>()
        try { flow { emit(1); emit(2); delay(5_000); emit(3) }.minesStallWatchdog(150).collect { got.add(it) }; fail("no stall") }
        catch (e: com.openminis.app.data.model.LLMError.TransientError) { assertTrue(e.detail.contains("没有新输出")) }
        assertEquals(listOf(1, 2), got)
    }

    /** [38.9.14.1] smoke 40172410482: a producer stuck in a blocking read (ignores cancellation) must not hold the stall error */
    @Test fun blockedProducerDoesNotDelayTheStall() = runBlocking {
        val t0 = System.currentTimeMillis()
        try {
            flow { emit(1); Thread.sleep(4_000); emit(2) }.flowOn(kotlinx.coroutines.Dispatchers.IO).minesStallWatchdog(200).toList(); fail("no stall")
        } catch (e: com.openminis.app.data.model.LLMError.TransientError) { assertTrue(e.detail.contains("没有新输出")) }
        val dt = System.currentTimeMillis() - t0
        assertTrue("stall took $dt ms", dt < 2_000)
    }

    @Test fun upstreamErrorPassesThrough() = runBlocking {
        try { flow<Int> { throw IllegalStateException("boom") }.minesStallWatchdog(1000).toList(); fail("no error") }
        catch (e: IllegalStateException) { assertEquals("boom", e.message) }
    }
}

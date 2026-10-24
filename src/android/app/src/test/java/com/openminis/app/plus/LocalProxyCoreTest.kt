package com.openminis.app.plus

import com.openminis.app.plus.LocalProxyCore.Probe
import com.openminis.app.plus.LocalProxyCore.Result
import com.openminis.app.plus.LocalProxyCore.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyCoreTest {
    @Test fun userPhoneCase7899DeadClash7890Works() {
        val order = LocalProxyCore.candidates(7898, builtinUp = false)
        val probes = listOf(Probe(7890, Result.OK, 120), Probe(7899, Result.FAIL), Probe(7897, Result.DOWN))
        assertEquals(7890, LocalProxyCore.select(probes, order))
        assertFalse(LocalProxyCore.usable(probes, 7899, probedOnce = true))
        assertTrue(LocalProxyCore.usable(probes, 7890, probedOnce = true))
    }

    @Test fun noneWorksExportsNothing() {
        val order = LocalProxyCore.candidates(7898, true)
        val probes = order.map { Probe(it, if (it == 7899) Result.FAIL else Result.DOWN) }
        assertNull(LocalProxyCore.select(probes, order))
        assertTrue(LocalProxyCore.summary(probes, null).contains("直连"))
    }

    @Test fun never7899Hardcoded_onlyIfItPassed() {
        val order = LocalProxyCore.candidates(null, false)
        assertEquals(7899, LocalProxyCore.select(listOf(Probe(7899, Result.OK)), order))
        assertNull(LocalProxyCore.select(listOf(Probe(7899, Result.FAIL)), order))
    }

    @Test fun builtinPortOnlyWhenListenerUp() {
        assertFalse(LocalProxyCore.candidates(7898, builtinUp = false).contains(7898))
        assertTrue(LocalProxyCore.candidates(7898, builtinUp = true).contains(7898))
        assertEquals(listOf(7890, 7897, 7899, 7891, 1080, 10808), LocalProxyCore.candidates(7890, true))   // no duplicate
        val order = LocalProxyCore.candidates(7898, true)
        assertEquals(7898, LocalProxyCore.select(listOf(Probe(7898, Result.OK), Probe(7899, Result.FAIL)), order))
    }

    @Test fun preferenceOrderWhenSeveralPass() {
        val order = LocalProxyCore.candidates(7898, true)
        assertEquals(7890, LocalProxyCore.select(listOf(Probe(7899, Result.OK), Probe(7890, Result.OK)), order))
        assertEquals(7897, LocalProxyCore.select(listOf(Probe(7899, Result.OK), Probe(7897, Result.OK)), order))
    }

    @Test fun unknownBeforeFirstProbeIsAllowed() {
        assertTrue(LocalProxyCore.usable(emptyList(), 7899, probedOnce = false))
        assertFalse(LocalProxyCore.usable(emptyList(), 7899, probedOnce = true))
        assertNull(LocalProxyCore.ok(emptyList(), 7899))
    }

    @Test fun reprobeRules() {
        assertTrue(LocalProxyCore.shouldProbe(Why.START, 1000, 1001))
        assertTrue(LocalProxyCore.shouldProbe(Why.NETWORK, 1000, 1001))
        assertFalse(LocalProxyCore.shouldProbe(Why.FAILURE, 100_000, 105_000))
        assertTrue(LocalProxyCore.shouldProbe(Why.FAILURE, 100_000, 116_000))
        assertTrue(LocalProxyCore.shouldProbe(Why.FAILURE, 0, 5))
        assertFalse(LocalProxyCore.shouldProbe(Why.STALE, 100_000, 200_000))
        assertTrue(LocalProxyCore.shouldProbe(Why.STALE, 100_000, 100_000 + LocalProxyCore.TTL_MS))
    }

    @Test fun summaryShowsOnlyListeningPorts() {
        val s = LocalProxyCore.summary(listOf(Probe(7890, Result.OK, 120), Probe(7899, Result.FAIL), Probe(1080, Result.DOWN)), 7890)
        assertTrue(s.startsWith("用 127.0.0.1:7890"))
        assertTrue(s.contains("7899 ✗")); assertFalse(s.contains("1080"))
        assertEquals("http://127.0.0.1:7890", LocalProxyCore.url(7890))
    }

    /** [38.9.11.3] the user's own node is kept (still listening, check failed) and dropped only when it stops listening */
    @Test fun userNodeKeptNotDropped() {
        fun P(p: Int, r: LocalProxyCore.Result) = LocalProxyCore.Probe(p, r)
        assertEquals(7890, LocalProxyCore.nodeFor(listOf(P(7890, LocalProxyCore.Result.OK)), 7890, 7890))
        assertEquals(7890, LocalProxyCore.nodeFor(listOf(P(7890, LocalProxyCore.Result.FAIL)), null, 7890))       // failed check: still the PROXY route
        assertEquals(null, LocalProxyCore.nodeFor(listOf(P(7890, LocalProxyCore.Result.DOWN)), null, 7890))       // Clash closed: not used
        assertEquals(null, LocalProxyCore.nodeFor(listOf(P(7890, LocalProxyCore.Result.FAIL)), null, null))       // never passed: nothing to keep
        assertTrue(LocalProxyCore.nodeNote(null, 7890)!!.contains("仍保留"))
        assertEquals(null, LocalProxyCore.nodeNote(7890, 7890))
    }
}

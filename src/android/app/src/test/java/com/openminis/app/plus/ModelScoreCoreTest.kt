package com.openminis.app.plus

import com.openminis.app.agent.ModelScoreCore
import com.openminis.app.agent.ModelScoreCore.Call
import com.openminis.app.agent.ModelScoreCore.Cand
import com.openminis.app.agent.ModelScoreCore.Kind
import com.openminis.app.agent.ModelScoreCore.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.9] automatic scheduling: tier prior, observed scores, escalation signals, fallback order. */
class ModelScoreCoreTest {
    private val now = 1_000_000_000L

    @Test fun tiers() {
        assertEquals(3, ModelScoreCore.tier("deepseek-reasoner"))
        assertEquals(3, ModelScoreCore.tier("claude-opus-4"))
        assertEquals(3, ModelScoreCore.tier("web:gemini"))
        assertEquals(1, ModelScoreCore.tier("gpt-4o-mini"))
        assertEquals(1, ModelScoreCore.tier("gemini-2.5-flash"))
        assertEquals(2, ModelScoreCore.tier("some-model"))
    }
    @Test fun observedBeatsName() {
        var bad: Stats? = null; repeat(6) { bad = ModelScoreCore.onFail(bad, 502, false, now - 60 * 60_000L) }
        var good: Stats? = null; repeat(6) { good = ModelScoreCore.onOk(good, 2000, now, done = true) }
        assertTrue(ModelScoreCore.score("light-mini", good, now) > ModelScoreCore.score("deepseek-reasoner", bad, now))
    }
    @Test fun recent5xxCoolsDown() {
        val s = ModelScoreCore.onFail(null, 503, false, now - 60_000)
        assertTrue(ModelScoreCore.recentBad(s, now))
        assertTrue(!ModelScoreCore.recentBad(s, now + ModelScoreCore.COOL_MS))
        assertTrue(!ModelScoreCore.recentBad(ModelScoreCore.onOk(s, 100, now), now))
    }
    @Test fun classify() {
        assertEquals(Kind.PLAN, ModelScoreCore.classify("为什么 502 报错"))
        assertEquals(Kind.TOOL, ModelScoreCore.classify("帮我安装 ripgrep"))
        assertEquals(Kind.CHAT, ModelScoreCore.classify("你好"))
    }
    @Test fun escalation() {
        assertNull(ModelScoreCore.escalation(listOf(Call("shell", false, "")), Kind.TOOL))
        assertNotNull(ModelScoreCore.escalation(listOf(Call("shell", true, "x"), Call("shell", true, "x")), Kind.TOOL))
        assertNotNull(ModelScoreCore.escalation(List(3) { Call("t$it", true, "e$it") }, Kind.CHAT))
        assertNotNull(ModelScoreCore.escalation(listOf(Call("a", true, "x")), Kind.PLAN, "我不确定原因"))
    }
    @Test fun strongestSkipsBadAndBusyWeb() {
        val cands = listOf(Cand("p|mini", "gpt-4o-mini"), Cand("p|r1", "deepseek-r1"), Cand("w|g", "web:gemini"))
        val stats = mapOf("p|r1" to ModelScoreCore.onFail(null, 500, false, now - 1000))
        val pick = ModelScoreCore.strongest(cands, "p|mini", { stats[it] }, now, webBusy = false)
        assertEquals("w|g", pick?.key)
        assertNull(ModelScoreCore.strongest(cands, "p|mini", { stats[it] }, now, webBusy = true))
    }
    @Test fun fallbackSameModelOtherAccountFirst() {
        data class R(val k: String, val m: String)
        val list = listOf(R("b|other", "glm-4.6"), R("acct2|qwen", "qwen3-max"))
        val f = ModelScoreCore.nextFallback(list, "qwen3-max", { it.m }, { it.k }, { null }, now)
        assertEquals("acct2|qwen", f?.k)
    }
    @Test fun jsonRoundTrip() {
        val m = mapOf("a|b" to Stats(ok = 2, fail = 1, done = 1, last5xxAt = 5, latencyMs = 12.5, lastOkAt = 9))
        assertEquals(m, ModelScoreCore.fromJson(ModelScoreCore.toJson(m)))
        assertTrue(ModelScoreCore.summary("修 502", listOf(Call("shell", true, "boom")), listOf("curl x")).contains("✗ shell"))
    }
}

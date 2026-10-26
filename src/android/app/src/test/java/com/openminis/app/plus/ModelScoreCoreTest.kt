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

    // ── [38.9.15.1] 「问题我看它都不会切」: a retry loop must actually switch ──
    @Test fun retryLoopTriggersEscalation() {
        // 3 consecutive failed steps (different errors) → escalate
        assertNotNull(ModelScoreCore.escalation(listOf(Call("a", false, ""), Call("shell", true, "e1"), Call("shell", true, "e2"), Call("shell", true, "e3")), Kind.TOOL))
        // every call "succeeded" but the model narrates redo after redo (screenshot 14:53)
        val narr = "命令有点乱。重发干净版。\nheredoc 被工具搞坏了。写到文件再跑。\n命令被传递时被截断/损坏。改用 file_write 在本地写脚本再传。\n没输出，先验证 minis-vps 是否还正常。"
        assertTrue(ModelScoreCore.redoLines(narr) >= 3)
        assertNotNull(ModelScoreCore.escalation(listOf(Call("shell", false, ""), Call("shell", false, "")), Kind.TOOL, narr))
        // a normal answer is not a loop
        assertNull(ModelScoreCore.escalation(listOf(Call("shell", false, "")), Kind.TOOL, "好了，已经部署完成。"))
    }
    @Test fun allFlashSwitchesFamilyNotJustAccount() {
        val cur = ModelScoreCore.Member("amd-11", "amd|deepseek-v4.1-flash", "deepseek-v4.1-flash")
        val cands = listOf(cur,
            ModelScoreCore.Member("amd-12", "amd|deepseek-v4.1-flash", "deepseek-v4.1-flash"),
            ModelScoreCore.Member("amd-10", "amd|mimo-v2.6-flash", "MiMo-V2.6-Flash"))
        val p = ModelScoreCore.pickEscalation(cands, cur, { null }, { false }, now)
        assertEquals("amd-10", p.target?.member)
        assertEquals("mimo", ModelScoreCore.family(p.target!!.modelId))
        // and back: MiMo stuck → DeepSeek
        val p2 = ModelScoreCore.pickEscalation(cands, cands[2], { null }, { false }, now)
        assertEquals("deepseek", ModelScoreCore.family(p2.target!!.modelId))
    }
    @Test fun nonFlashOnlyWhenConfigured() {
        val cur = ModelScoreCore.Member("amd-11", "amd|ds-flash", "deepseek-v4.1-flash")
        val strong = ModelScoreCore.Member("ds", "ds|reasoner", "deepseek-reasoner")
        val mimo = ModelScoreCore.Member("amd-10", "amd|mimo", "mimo-v2.6-flash")
        assertEquals("ds", ModelScoreCore.pickEscalation(listOf(cur, mimo, strong), cur, { null }, { false }, now).target?.member)
        assertEquals("amd-10", ModelScoreCore.pickEscalation(listOf(cur, mimo), cur, { null }, { false }, now).target?.member)
    }
    @Test fun loneModelFallsBackToOtherAccountThenEffortOnly() {
        val cur = ModelScoreCore.Member("amd-11", "amd|ds", "deepseek-v4.1-flash")
        val acct = ModelScoreCore.Member("amd-12", "amd|ds", "deepseek-v4.1-flash")
        assertEquals("amd-12", ModelScoreCore.pickEscalation(listOf(cur, acct), cur, { null }, { false }, now).target?.member)
        assertNull(ModelScoreCore.pickEscalation(listOf(cur, acct), cur, { null }, { it.member == "amd-12" }, now).target)
    }
    @Test fun boostLevelOneStep() {
        assertEquals(3, ModelScoreCore.boostLevel(2, listOf(1, 2, 3, 4)))
        assertNull(ModelScoreCore.boostLevel(4, listOf(1, 2, 3, 4)))
    }
    @Test fun stallRotatesToNextAccountSameModel() {
        val cool = ModelScoreCore.AccountCool()
        data class R(val member: String, val m: String)
        val list = listOf(R("amd-11", "ds-flash"), R("amd-12", "ds-flash"), R("amd-10", "mimo-flash"))
        cool.mark("amd-11", now)
        assertTrue(ModelScoreCore.stallRotateNow(list, "amd-11", "ds-flash", { it.member }, { it.m }) { cool.cooling(it.member, now) })
        val next = ModelScoreCore.nextFallback(list.filter { it.member != "amd-11" }, "ds-flash", { it.m }, { "amd|" + it.m }, { null }, now) { cool.cooling(it.member, now) }
        assertEquals("amd-12", next?.member)
    }

    /**
     * [38.9.15.1] loop simulation: the agent loop's per-iteration check as ChatViewModel runs it (calls since the last switch,
     * text since the last switch, ≤ 3 switches a run). A Flash model that keeps failing the same shell step must switch to
     * another family within a few iterations, and the next switch is judged only on what happened after the first.
     */
    @Test fun loopSimulationSwitches() {
        val members = listOf(
            ModelScoreCore.Member("amd-11", "amd|deepseek-v4.1-flash", "deepseek-v4.1-flash"),
            ModelScoreCore.Member("amd-12", "amd|deepseek-v4.1-flash", "deepseek-v4.1-flash"),
            ModelScoreCore.Member("amd-10", "amd|mimo-v2.6-flash", "MiMo-V2.6-Flash"))
        var cur = members[0]
        val calls = ArrayList<Call>()
        var text = ""
        var escBase = 0; var escTextBase = 0; var escCount = 0
        val switches = ArrayList<Pair<Int, String>>()
        // what the stuck model does each iteration: one shell call failing the same way + a redo line
        fun step(i: Int, failing: Boolean) {
            calls += Call("shell", failing, if (failing) "heredoc: unexpected EOF" else "")
            text += if (failing) "命令被截断了，重发干净版。\n" else "好的，继续。\n"
            if (escCount >= 3) return
            val why = ModelScoreCore.escalation(calls.drop(escBase), Kind.TOOL, text.drop(escTextBase)) ?: return
            val pick = ModelScoreCore.pickEscalation(members, cur, { null }, { false }, now)
            val t = pick.target ?: return
            switches += i to (why + "：" + cur.modelId + " → " + t.modelId)
            cur = t; escCount++; escBase = calls.size; escTextBase = text.length
        }
        for (i in 1..3) step(i, failing = true)
        assertEquals("one switch after the same step failed twice", 1, switches.size)
        assertTrue(switches[0].first <= 2)
        assertEquals("mimo", ModelScoreCore.family(cur.modelId))
        // the new model works: no further switch however long the run goes
        for (i in 4..20) step(i, failing = false)
        assertEquals(1, switches.size)
        // it gets stuck again (its earlier failure + this one = the same step twice) → back to the other family
        step(21, failing = true)
        assertEquals(2, switches.size)
        assertEquals("deepseek", ModelScoreCore.family(cur.modelId))
        // a run that never recovers: at most 3 switches, then it stays put
        for (i in 22..40) step(i, failing = true)
        assertEquals(3, switches.size)
    }

}

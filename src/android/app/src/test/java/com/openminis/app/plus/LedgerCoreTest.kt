package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11.3] 台账 account choice: named > bound > 备注 > last success > 默认 > first; card only when nothing saved. */
class LedgerCoreTest {
    private val a = LedgerCore.Cand("1", "github.com", "main@x.com", note = "主号")
    private val b = LedgerCore.Cand("2", "github.com", "test@y.com", note = "测试")
    private val c = LedgerCore.Cand("3", "login.github.com", "work@z.com", note = "工作 minis")

    @Test fun nothingSaved_noPick() {
        val p = LedgerCore.pick(emptyList(), "", null)
        assertNull(p.cand); assertFalse(p.ask)
    }

    @Test fun single_isUsed() = assertEquals("1", LedgerCore.pick(listOf(a), "", null).cand?.id)

    @Test fun namedInChat_wins_overEverything() {
        val bd = b.copy(isDefault = true, lastOkAt = 99)
        val p = LedgerCore.pick(listOf(a, bd, c), "用 work@z.com 登录 GitHub", bound = "main@x.com")
        assertEquals("3", p.cand?.id); assertEquals(LedgerCore.Why.NAMED, p.why)
    }

    @Test fun namedFullAddress() = assertEquals("2", LedgerCore.pick(listOf(a, b), "换 TEST@y.com 登录", null).cand?.id)

    @Test fun localPartLongEnough() {
        val d = LedgerCore.Cand("4", "github.com", "deningyang740@gmail.com")
        assertEquals("4", LedgerCore.pick(listOf(a, d), "用 deningyang740 那个号", null).cand?.id)
    }

    @Test fun bound_beatsNoteAndHistory() {
        val p = LedgerCore.pick(listOf(a.copy(lastOkAt = 100), b), "随便看看", bound = "test@y.com")
        assertEquals("2", p.cand?.id); assertEquals(LedgerCore.Why.BOUND, p.why)
    }

    @Test fun note_matchesTask() {
        val p = LedgerCore.pick(listOf(a, b, c), "在 minis 项目里提交 PR", null)
        assertEquals("3", p.cand?.id); assertEquals(LedgerCore.Why.NOTE, p.why)
    }

    @Test fun lastOk_thenDefault_thenFirst() {
        assertEquals("2", LedgerCore.pick(listOf(a.copy(lastOkAt = 5), b.copy(lastOkAt = 9)), "hi", null).cand?.id)
        val p = LedgerCore.pick(listOf(a, b.copy(isDefault = true)), "hi", null)
        assertEquals("2", p.cand?.id); assertEquals(LedgerCore.Why.DEFAULT, p.why)
        val f = LedgerCore.pick(listOf(a, b), "hi", null)
        assertEquals("1", f.cand?.id); assertEquals(LedgerCore.Why.FIRST, f.why); assertFalse(f.ask)
    }

    @Test fun lastOk_beatsDefault() =
        assertEquals("1", LedgerCore.pick(listOf(a.copy(lastOkAt = 7), b.copy(isDefault = true)), "", null).cand?.id)

    @Test fun ordered_putsPickFirst() {
        val p = LedgerCore.pick(listOf(a, b, c), "", bound = "work@z.com")
        assertEquals(listOf("3", "1", "2"), p.ordered.map { it.id })
    }

    @Test fun indistinct_paying_asks_only_whenRequested() {
        assertTrue(LedgerCore.pick(listOf(a, b), "", null, choiceMatters = true).ask)
        assertFalse(LedgerCore.pick(listOf(a, b), "", null, choiceMatters = false).ask)
        assertTrue(LedgerCore.choiceMatters("帮我充值 100 元")); assertFalse(LedgerCore.choiceMatters("看看通知"))
    }

    @Test fun sites() {
        assertTrue(LedgerCore.sameSite("github.com", "login.github.com"))
        assertTrue(LedgerCore.sameSite("www.taobao.com.cn", "a.taobao.com.cn"))
        assertFalse(LedgerCore.sameSite("a.com.cn", "b.com.cn"))
        assertFalse(LedgerCore.sameSite("github.com", "gitlab.com"))
        assertEquals(3, LedgerCore.forSite(listOf(a, b, c, LedgerCore.Cand("9", "gitlab.com", "q")), "github.com").size)
        assertEquals(1, LedgerCore.forSite(listOf(a, a.copy(id = "dup", host = "GITHUB.com", account = "MAIN@x.com")), "github.com").size)
    }

    @Test fun agentLine_neverHasPassword_andChip() {
        val l = LedgerCore.line(a.copy(isDefault = true, lastOkAt = 1_000L), here = true, now = 1_000L + 7_200_000L)
        assertTrue(l.contains("main@x.com")); assertTrue(l.contains("默认")); assertTrue(l.contains("本对话在用")); assertTrue(l.contains("2 小时前"))
        assertEquals("已用 台账：main@x.com", LedgerCore.chip(a))
        assertEquals(LedgerCore.idOf("github.com", "Main@x.com"), LedgerCore.idOf("login.github.com", "main@x.com"))
        assertFalse(LedgerCore.idOf("github.com", "main@x.com").contains("main"))
    }
}

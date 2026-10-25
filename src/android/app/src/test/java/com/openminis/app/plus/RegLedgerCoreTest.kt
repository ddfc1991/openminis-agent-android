package com.openminis.app.plus

import com.openminis.app.plus.RegLedgerCore.Entry
import com.openminis.app.plus.RegLedgerCore.Ka
import com.openminis.app.plus.RegLedgerCore.Mail
import com.openminis.app.plus.RegLedgerCore.RSt
import com.openminis.app.plus.RegLedgerCore.Rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegLedgerCoreTest {
    private val H = 3600_000L
    private val doms = setOf("smoke-mail.test", "dcpa.net")

    @Test fun tempBoxesNeverLongTermProviders() {
        assertTrue(RegLedgerCore.isTemp("mines1@dcpa.net", doms, emptySet()))
        assertTrue(RegLedgerCore.isTemp("x@other.org", doms, setOf("x@other.org")))
        listOf("deni@gmail.com", "ydn4@163.com", "a@qq.com", "b@outlook.com", "c@126.com").forEach { assertFalse(it, RegLedgerCore.isTemp(it, doms + "gmail.com" + "163.com", setOf(it))) }
        assertFalse(RegLedgerCore.isTemp("nobody@unknown.io", doms, emptySet()))
    }

    @Test fun maskBoxKeepsTwoBoxesApart() {
        assertEquals("mine••8587@smoke-mail.test", RegLedgerCore.maskBox("minesfncch8587@smoke-mail.test"))
        assertFalse(RegLedgerCore.maskBox("minesfncch8587@x.test") == RegLedgerCore.maskBox("minesfndhuz626@x.test"))
        assertEquals("k7q2••@mail.tm", RegLedgerCore.maskBox("k7q2abcd@mail.tm"))
    }

    @Test fun recordKeepsPlatformUrlAndFirstTime() {
        var l = RegLedgerCore.upsert(emptyList(), Rec("a@dcpa.net", "zeabur.com", "Zeabur", "https://zeabur.com/sign-up", st = RSt.RUNNING, createdAt = 100))
        l = RegLedgerCore.upsert(l, Rec("a@dcpa.net", "zeabur.com", st = RSt.CAPTCHA, why = "人机", createdAt = 999))
        assertEquals(1, l.size); assertEquals("Zeabur", l[0].platform); assertEquals("https://zeabur.com/sign-up", l[0].url)
        assertEquals(100L, l[0].createdAt); assertEquals(RSt.CAPTCHA, l[0].st); assertEquals("未完成", l[0].st.pill)
        val back = RegLedgerCore.recsOf(RegLedgerCore.recsJson(l)); assertEquals(l, back)
        val cut = RegLedgerCore.afterRestart(listOf(Rec("b@x", "h", st = RSt.RUNNING)), 5)
        assertEquals(RSt.PENDING, cut[0].st); assertTrue(cut[0].why.contains("重启"))
    }

    @Test fun switchesDefaultOffAndDueTimes() {
        val k = Ka(RegLedgerCore.acctKey("Zeabur.com", "A@x"))
        assertEquals("acct|zeabur.com|a@x", k.key)
        assertFalse(RegLedgerCore.anyOn(listOf(k))); assertFalse(RegLedgerCore.probeDue(k, 0)); assertEquals("", RegLedgerCore.statusLine(k) { "t" })
        val p = k.copy(probe = true, everyH = 6, lastProbeAt = 10 * H)
        assertTrue(RegLedgerCore.anyOn(listOf(p)))
        assertFalse(RegLedgerCore.probeDue(p, 15 * H)); assertTrue(RegLedgerCore.probeDue(p, 16 * H))
        val e = k.copy(extend = true)
        assertTrue(RegLedgerCore.extendDue(e, 1))   // never ran → now
        assertFalse(RegLedgerCore.extendDue(e.copy(lastExtendAt = 1), 23 * H)); assertTrue(RegLedgerCore.extendDue(e.copy(lastExtendAt = 1), 25 * H))
        assertEquals(12, RegLedgerCore.cycleHours(6)); assertEquals(24, RegLedgerCore.cycleHours(12)); assertEquals(6, RegLedgerCore.cycleHours(24))
        val line = RegLedgerCore.statusLine(e.copy(probe = true, lastExtendAt = 1, lastProbeAt = 2, probeOk = false, probeNote = "HTTP 503")) { "T$it" }
        assertTrue(line, line.startsWith("下次续期 T")); assertTrue(line.contains("上次探活 T2 ✗ HTTP 503"))
        val ks = RegLedgerCore.kaOf(RegLedgerCore.kaJson(listOf(p, e)))
        assertEquals(listOf(p, e), ks)
    }

    @Test fun tidyFillsVerifiesAndKeepsAliveBox() {
        val box = "fill1@smoke-mail.test"
        val mails = listOf(Mail("no-reply@smokecloud.test", "Confirm your SmokeCloud email", "open http://10.0.2.2:8096/verify-link?token=t9 to confirm", 50_000))
        val p = RegLedgerCore.tidy(box, listOf(Entry("10.0.2.2", box, true, "", "10.0.2.2")), emptyList(), mails, 99_000)
        assertEquals(1, p.keep.size); assertTrue(p.trash.isEmpty())
        val r = p.keep[0]
        assertEquals("http://10.0.2.2:8096/verify-link?token=t9", p.verify.single().second)
        assertEquals("https://10.0.2.2/", r.url); assertEquals(50_000L, r.createdAt); assertTrue(r.platform.isNotBlank())
        assertEquals(1, p.filled)
    }

    @Test fun tidyVerifiedFromNoteOrMail() {
        val p = RegLedgerCore.tidy("a@dcpa.net", listOf(Entry("zeabur.com", "a@dcpa.net", true, "注册于 2026-10-20 · Mail.tm", "Zeabur")), emptyList(), emptyList(), 1)
        assertEquals(RSt.VERIFIED, p.keep[0].st); assertEquals("Zeabur", p.keep[0].platform); assertTrue(p.verify.isEmpty())
        val q = RegLedgerCore.tidy("b@dcpa.net", listOf(Entry("vercel.com", "b@dcpa.net", true, "", "")), emptyList(),
            listOf(Mail("Vercel <no@vercel.com>", "Your email is verified", "welcome", 5)), 9)
        assertEquals(RSt.VERIFIED, q.keep[0].st); assertEquals("Vercel", q.keep[0].platform)
    }

    @Test fun tidyDeletesOnlyDeadAndUnfillable() {
        // dead box + no password → 最近删除
        val d = RegLedgerCore.tidy("gone@smoke-mail.test", listOf(Entry("dead.example", "gone@smoke-mail.test", false, "")), emptyList(), null, 1)
        assertEquals(1, d.trash.size); assertTrue(d.keep.isEmpty())
        // dead box but platform + password known → kept (nothing to fill from mail, nothing lost)
        val k = RegLedgerCore.tidy("old@smoke-mail.test", listOf(Entry("github.com", "old@smoke-mail.test", true, "注册中 2026-10-01", "GitHub")), emptyList(), null, 1)
        assertTrue(k.trash.isEmpty()); assertEquals("GitHub", k.keep[0].platform)
        // alive box, password missing → kept, marked 缺账号密码
        val a = RegLedgerCore.tidy("live@smoke-mail.test", listOf(Entry("x.com", "live@smoke-mail.test", false, "")), emptyList(), emptyList(), 1)
        assertTrue(a.trash.isEmpty()); assertEquals("缺账号密码", a.keep[0].why)
    }

    @Test fun tidyDedupesRecordsPerHost() {
        val box = "d@dcpa.net"
        val recs = listOf(Rec(box, "zeabur.com", "Zeabur", st = RSt.PENDING, updatedAt = 1), Rec(box, "ZEABUR.com", "Zeabur", st = RSt.CAPTCHA, updatedAt = 5))
        val p = RegLedgerCore.tidy(box, listOf(Entry("zeabur.com", box, true, "")), recs, emptyList(), 9)
        assertEquals(1, p.keep.size); assertEquals(RSt.CAPTCHA, p.keep[0].st)
    }

    @Test fun summaryAndTrash() {
        assertEquals("整理 3 个 · 补全 1 · 删除 1 ›", RegLedgerCore.Summary(3, 1, 1).line)
        val now = 10 * 86_400_000L
        val t = listOf(RegLedgerCore.Trashed("t1", Rec("a@x", "h"), "h", "a@x", "", now - 8 * 86_400_000L), RegLedgerCore.Trashed("t2", Rec("b@x", "h"), "h", "b@x", "", now - 86_400_000L))
        val kept = RegLedgerCore.purge(t, now)
        assertEquals(listOf("t2"), kept.map { it.id }); assertEquals(6, RegLedgerCore.daysLeft(kept[0], now))
        assertEquals(kept, RegLedgerCore.trashOf(RegLedgerCore.trashJson(kept)))
    }

    @Test fun renewScriptNeverPays() {
        assertTrue(RegLedgerCore.RENEW_JS.contains("付款")); assertTrue(RegLedgerCore.RENEW_JS.contains("renew"))
    }
}

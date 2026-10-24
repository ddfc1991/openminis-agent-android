package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Inbox3912CoreTest {
    private val now = 1_800_000_000_000L
    private fun m(id: String, from: String, subj: String, text: String, ago: Long) = Inbox3912Core.MailLite(id, from, subj, text, now - ago, "temp")
    private val ex: (String) -> String? = { t -> Regex("(?<![0-9])([0-9]{6})(?![0-9])").find(t)?.value }

    @Test fun codesNewestFirstFreshFlag() {
        val c = Inbox3912Core.codes(listOf(
            m("a", "Cloudflare <noreply@cloudflare.com>", "Your Cloudflare verification code", "code 482917", 5 * 60_000L),
            m("b", "noreply@railway.app", "Sign in", "your code is 123456", 3 * 3600_000L),
            m("c", "news@x.com", "Weekly digest", "no code here", 60_000L),
            m("d", "old@x.com", "code", "999999", 30 * 3600_000L),
        ), now, ex)
        assertEquals(listOf("a", "b"), c.map { it.mailId })
        assertTrue(c[0].fresh); assertFalse(c[1].fresh)
        assertEquals("Cloudflare", c[0].site)
    }

    @Test fun siteFallbacks() {
        assertEquals("Acme Mail", Inbox3912Core.siteOf("\"Acme Mail\" <a@acme.io>", "hello"))
        assertEquals("Acme", Inbox3912Core.siteOf("a@acme.io", "hello"))
    }

    @Test fun spacedAndMask() {
        assertEquals("482 917", Inbox3912Core.spaced("482917"))
        assertEquals("AB12", Inbox3912Core.spaced("AB12"))
        assertEquals("k7q2••@mail.tm", Inbox3912Core.maskMail("k7q2abcd@mail.tm"))
    }

    @Test fun summaryFlagsActionItems() {
        val s = Inbox3912Core.summary(listOf(
            m("a", "g@glados", "套餐到期提醒", "", 60_000L), m("b", "r@railway", "Security alert: rotate token", "", 120_000L),
            m("c", "n@news", "Newsletter", "", 180_000L)), 2, 1, now - 3600_000L)
        assertEquals(3, s.today); assertEquals(2, s.needAct.size)
        assertTrue(s.line.startsWith("需要处理"))
        assertEquals("今天还没有新邮件。", Inbox3912Core.summary(emptyList(), 0, 0, now).line)
    }

    @Test fun regStatusAndGrouping() {
        assertEquals(Inbox3912Core.RegSt.DONE, Inbox3912Core.regStatus("注册于 10-23"))
        assertEquals(Inbox3912Core.RegSt.PENDING, Inbox3912Core.regStatus("注册中 10-23"))
        val g = Inbox3912Core.byMailbox(listOf(
            Inbox3912Core.RegRow("railway.com", "a@mail.tm", "", Inbox3912Core.RegSt.SAVED),
            Inbox3912Core.RegRow("cloudflare.com", "A@mail.tm", "", Inbox3912Core.RegSt.DONE),
            Inbox3912Core.RegRow("x.com", "user1", "", Inbox3912Core.RegSt.SAVED)))
        assertEquals("a@mail.tm", g[0].first); assertEquals(2, g[0].second.size); assertEquals("cloudflare.com", g[0].second[0].host)
        assertEquals("其他账号", g.last().first)
    }

    @Test fun registerTaskObeysRules() {
        val t = Inbox3912Core.registerTask("Cloudflare", "https://dash.cloudflare.com/sign-up", "k7q2@mail.tm")
        assertTrue(t.contains("register_pick")); assertTrue(t.contains("不要绕过")); assertTrue(t.contains("k7q2@mail.tm"))
    }
}

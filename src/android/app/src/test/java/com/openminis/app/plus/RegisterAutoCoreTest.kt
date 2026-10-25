package com.openminis.app.plus

import com.openminis.app.plus.RegisterAutoCore.Item
import com.openminis.app.plus.RegisterAutoCore.Page
import com.openminis.app.plus.RegisterAutoCore.Platform
import com.openminis.app.plus.RegisterAutoCore.Run
import com.openminis.app.plus.RegisterAutoCore.Scan
import com.openminis.app.plus.RegisterAutoCore.St
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterAutoCoreTest {

    @Test fun customUrlAcceptsDomainsAndLinks() {
        assertEquals("https://zeabur.com/", RegisterAutoCore.normUrl("zeabur.com"))
        assertEquals("https://zeabur.com/", RegisterAutoCore.normUrl("  Zeabur.com "))
        assertEquals("https://zeabur.com/sign-up?x=1", RegisterAutoCore.normUrl("https://Zeabur.com/sign-up?x=1"))
        assertEquals("http://10.0.2.2:8097/signup", RegisterAutoCore.normUrl("http://10.0.2.2:8097/signup"))
        assertEquals("https://10.0.2.2:8097/signup", RegisterAutoCore.normUrl("10.0.2.2:8097/signup"))
        assertEquals("https://app.netlify.com/signup", RegisterAutoCore.normUrl("「app.netlify.com/signup」"))
    }

    @Test fun customUrlRejectsNonWebInput() {
        listOf("", "zeabur", "注册 zeabur", "javascript:alert(1)", "mailto:a@b.com", "ftp://x.com/", "https://a b.com",
            "https://user@evil.com/", "http://x.com:99999/", "999.1.1.1", "https://-bad-.com").forEach {
            assertNull("should reject [$it]", RegisterAutoCore.normUrl(it))
        }
    }

    @Test fun nameFromHost() {
        assertEquals("Zeabur", RegisterAutoCore.nameFor("https://zeabur.com/"))
        assertEquals("Netlify", RegisterAutoCore.nameFor("https://app.netlify.com/signup"))
        assertEquals("Example", RegisterAutoCore.nameFor("https://www.example.com.cn/"))
        assertEquals("10.0.2.2", RegisterAutoCore.nameFor("http://10.0.2.2:8097/signup"))
    }

    @Test fun customAddsOnceAndExplainsErrors() {
        val list = listOf(Platform("GitHub", "https://github.com/signup"))
        val (p, why) = RegisterAutoCore.custom("zeabur.com", list)
        assertNotNull(p); assertNull(why); assertTrue(p!!.custom); assertEquals("zeabur.com", p.host)
        val (dup, why2) = RegisterAutoCore.custom("https://github.com/join", list)
        assertNull(dup); assertTrue(why2!!.contains("已经在列表里"))
        assertTrue(RegisterAutoCore.custom("hello", list).second!!.contains("不是网址"))
    }

    @Test fun pickedKeepsOrderOnePerHostCapped() {
        val l = (1..12).map { Platform("P$it", "https://p$it.com/") } + Platform("dup", "https://p1.com/other")
        val got = RegisterAutoCore.picked(l, l.map { it.host }.toSet())
        assertEquals(RegisterAutoCore.MAX_PICK, got.size)
        assertEquals("P1", got.first().name)
        assertEquals(1, RegisterAutoCore.picked(l, setOf("p1.com")).size)
    }

    @Test fun classifierStopsForCaptchaPhonePayment() {
        assertEquals(Page.CAPTCHA, RegisterAutoCore.classify(Scan(captcha = "Cloudflare Turnstile", email = true, pass = 1)))
        assertEquals(Page.PAYMENT, RegisterAutoCore.classify(Scan(card = true, email = true)))
        assertEquals(Page.PHONE, RegisterAutoCore.classify(Scan(phone = true, phoneRequired = true)))
        // a phone box next to an e-mail box is just optional: fill the form
        assertEquals(Page.FORM, RegisterAutoCore.classify(Scan(email = true, pass = 1, phone = true)))
    }

    @Test fun classifierFormCodeMailDone() {
        assertEquals(Page.FORM, RegisterAutoCore.classify(Scan(email = true, pass = 2, submit = true)))
        assertEquals(Page.FORM, RegisterAutoCore.classify(Scan(pass = 1)))   // second step: password only
        assertEquals(Page.CODE, RegisterAutoCore.classify(Scan(code = true, codeEmpty = true, checkMail = true)))
        assertEquals(Page.CHECK_MAIL, RegisterAutoCore.classify(Scan(checkMail = true)))
        assertEquals(Page.DONE, RegisterAutoCore.classify(Scan(success = true)))
        assertEquals(Page.UNKNOWN, RegisterAutoCore.classify(Scan()))
    }

    @Test fun parseScanReadsRunnerJson() {
        val s = RegisterAutoCore.parseScan("R:{\"email\":true,\"pass\":2,\"code\":false,\"phone\":true,\"phoneReq\":false,\"submit\":true,\"url\":\"http://x/\"}", "")
        assertTrue(s.email); assertEquals(2, s.pass); assertTrue(s.phone); assertFalse(s.phoneRequired); assertEquals("http://x/", s.url)
        assertEquals(Page.UNKNOWN, RegisterAutoCore.classify(RegisterAutoCore.parseScan("garbage")))
        assertEquals(Page.CAPTCHA, RegisterAutoCore.classify(RegisterAutoCore.parseScan(null, "hCaptcha")))
    }

    @Test fun askLineIsOneShortChineseLine() {
        val l = RegisterAutoCore.askLine("Zeabur", Page.CAPTCHA, "Cloudflare Turnstile")
        assertTrue(l.startsWith("Zeabur 要做人机验证")); assertFalse(l.contains('\n')); assertTrue(l.length < 60)
        assertTrue(RegisterAutoCore.askLine("X", Page.PAYMENT).contains("不替你付款"))
        assertTrue(RegisterAutoCore.askLine("X", Page.PHONE).contains("手机号"))
    }

    @Test fun mailTmMessagesAndLink() {
        val list = """{"hydra:member":[{"id":"m1","from":{"address":"no-reply@zeabur.com"},"subject":"Verify your email","intro":"Click","createdAt":"2026-10-25T10:00:00+00:00"},
            {"id":"m0","from":{"address":"a@b.c"},"subject":"old","intro":"","createdAt":"2026-10-25T09:00:00+00:00"}]}"""
        val ms = RegisterAutoCore.parseMessages(list)
        assertEquals("m1", ms.first().id); assertEquals(2, ms.size)
        val full = """{"subject":"Verify","text":"Hi","html":["<a href=\"https://zeabur.com/unsubscribe\">x</a><a href=\"https://track.mail.com/c?u=1\">t</a><a href=\"https://zeabur.com/auth/verify?token=abc&amp;e=1\">Verify</a>"]}"""
        val text = RegisterAutoCore.parseMessage(full)
        assertEquals("https://zeabur.com/auth/verify?token=abc&e=1", RegisterAutoCore.pickLink(text, "zeabur.com"))
        assertNull(RegisterAutoCore.pickLink("see https://zeabur.com/privacy", "zeabur.com"))
        assertEquals("http://10.0.2.2:8097/verify?token=t1", RegisterAutoCore.pickLink("open http://10.0.2.2:8097/verify?token=t1 now", "10.0.2.2"))
    }

    @Test fun foldedRowAndItemTitles() {
        val a = Item(Platform("Zeabur", "https://zeabur.com/"), St.DONE, email = "m1@x.tm")
        val b = Item(Platform("GitHub", "https://github.com/signup"), St.RUNNING, line = "填表")
        assertEquals("Zeabur 注册 · 完成", RegisterAutoCore.itemTitle(a))
        val r = Run("r1", listOf(a, b))
        assertEquals("一键注册 · 进行中 1/2", RegisterAutoCore.runTitle(r))
        assertEquals("GitHub：填表", RegisterAutoCore.runLine(r))
        assertEquals(ChatHomeCore.St.RUNNING, RegisterAutoCore.chatSt(r))
        val w = r.copy(items = listOf(a, b.copy(st = St.WAITING, line = "GitHub 要做人机验证，点这里完成后我接着注册。")))
        assertEquals("一键注册 · 等你", RegisterAutoCore.runTitle(w)); assertEquals(ChatHomeCore.St.WAITING, RegisterAutoCore.chatSt(w))
        val d = r.copy(items = listOf(a, b.copy(st = St.PENDING, line = "要手机号，跳过了")))
        assertEquals("一键注册 · 完成 1/2 · 1 个未完成", RegisterAutoCore.runTitle(d)); assertEquals(ChatHomeCore.St.DONE, RegisterAutoCore.chatSt(d))
    }

    @Test fun runSurvivesRestartAsUnfinished() {
        val r = Run("r9", listOf(Item(Platform("A", "https://a.com/"), St.DONE, "已验证", "x@y"), Item(Platform("B", "https://b.com/", true), St.RUNNING)), 5L)
        val back = RegisterAutoCore.fromJson(RegisterAutoCore.toJson(r))!!
        assertEquals(St.DONE, back.items[0].st); assertEquals(St.PENDING, back.items[1].st); assertTrue(back.items[1].p.custom)
        assertTrue(back.finished)
    }

    @Test fun ledgerNotesMapToStatus() {
        assertEquals(Inbox3912Core.RegSt.DONE, Inbox3912Core.regStatus(RegisterAutoCore.ledgerNote("2026-10-25", true)))
        assertEquals(Inbox3912Core.RegSt.PENDING, Inbox3912Core.regStatus(RegisterAutoCore.ledgerNote("2026-10-25", false, "人机验证没完成")))
        assertTrue(RegisterAutoCore.ledgerNote("2026-10-25", true).contains("Mail.tm"))
    }

    @Test fun scriptsAndUserName() {
        val js = RegisterAutoCore.fillJs("a\"b@x.tm", "p'w\\1", "abx")
        assertTrue(js.contains("\"a\\\"b@x.tm\"")); assertTrue(js.contains("R:filled"))
        assertTrue(RegisterAutoCore.SCAN_JS.contains("checkMail"))
        assertEquals("mines1abc", RegisterAutoCore.userFor("mines1abc@x.tm"))
        assertEquals("m12347", RegisterAutoCore.userFor("1234@x.tm"))
    }
}

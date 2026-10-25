package com.openminis.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.15 伪人核心] identity, per-account profiles, active hours, first-person contact memory. */
class PersonaCoreTest {

    @Test fun eachAccountHasItsOwnStableProfile() {
        val a = PersonaCore.Account(PersonaCore.accountId("www.Weibo.com", "Me"), "weibo.com", "Me")
        assertEquals("weibo.com|me", a.id)
        assertEquals(a.profile, PersonaCore.Account(a.id, "weibo.com", "me").profile)
        assertTrue(PersonaCore.isPersonaProfile(a.profile))
        assertTrue(a.profile.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(PersonaCore.profileName("x".repeat(200)).length <= 60)
    }

    @Test fun accountForMatchesSiteAndHandle() {
        val p = PersonaCore.Profile(accounts = listOf(PersonaCore.Account("weibo.com|a", "weibo.com", "a"), PersonaCore.Account("weibo.com|b", "weibo.com", "b"),
            PersonaCore.Account("163.com|me@163.com", "163.com", "me@163.com")))
        assertEquals("weibo.com|a", PersonaCore.accountFor(p, "https://m.weibo.com/home")?.id)
        assertEquals("weibo.com|b", PersonaCore.accountFor(p, "weibo.com", "B")?.id)
        assertEquals("163.com|me@163.com", PersonaCore.accountFor(p, "163.com", "me@163.com")?.id)
        assertNull(PersonaCore.accountFor(p, "https://zeabur.com/signup"))
        assertNull(PersonaCore.accountFor(p, null))
    }

    @Test fun discoveredAccountsStartSwitchedOff() {
        val p = PersonaCore.discover(PersonaCore.Profile(), "xiaohongshu.com", "me")
        assertEquals(1, p.accounts.size)
        assertFalse(p.accounts[0].allowed)
        assertTrue(p.accounts[0].confirmSend)
        assertEquals(p, PersonaCore.discover(p, "xiaohongshu.com", "me"))   // no duplicate
        assertEquals("xiaohongshu.com", PersonaCore.knownSite("https://www.xiaohongshu.com/explore"))
        assertNull(PersonaCore.knownSite("https://zeabur.com"))
    }

    @Test fun activeHours() {
        val day = PersonaCore.Profile(activeFromHour = 8, activeToHour = 23)
        assertTrue(PersonaCore.withinActiveHours(day, 8)); assertTrue(PersonaCore.withinActiveHours(day, 22))
        assertFalse(PersonaCore.withinActiveHours(day, 23)); assertFalse(PersonaCore.withinActiveHours(day, 3))
        val night = PersonaCore.Profile(activeFromHour = 20, activeToHour = 2)
        assertTrue(PersonaCore.withinActiveHours(night, 23)); assertTrue(PersonaCore.withinActiveHours(night, 1)); assertFalse(PersonaCore.withinActiveHours(night, 12))
    }

    @Test fun promptMakesMinesThePersonNotAnAssistant() {
        val s = PersonaCore.promptFor(PersonaCore.Profile(displayName = "阿杨", traits = listOf("短句")), on = true)
        assertTrue(s.contains("就是本人")); assertTrue(s.contains("不自称 AI"))
        assertTrue(s.contains("限制层只听主人的"))
        assertTrue(s.contains("wait_for_human"))
        assertEquals("", PersonaCore.promptFor(PersonaCore.Profile(), on = false))
    }

    @Test fun profileRoundTrip() {
        val p = PersonaCore.Profile("阿杨", listOf("短句", "不用感叹号"), 9, 22,
            listOf(PersonaCore.Account("weibo.com|me", "weibo.com", "me", "微博", allowed = true, confirmSend = false, dailyCap = 12)))
        assertEquals(p, PersonaCore.fromJson(PersonaCore.toJson(p)))
        assertEquals(PersonaCore.Profile(), PersonaCore.fromJson("not json"))
    }

    @Test fun contactMemoryIsFirstPersonAndKeepsRecentTopics() {
        val C = ContactMemoryCore
        var m = C.touch(emptyMap(), "王总 <Wang@Corp.com>", "报价", 1L, name = "王总", relation = "客户")
        m = C.touch(m, "wang@corp.com", "发票", 2L)
        m = C.touch(m, "wang@corp.com", "报价", 3L)
        val c = m["wang@corp.com"]!!
        assertEquals(listOf("发票", "报价"), c.topics)
        val b = C.brief(c)
        assertTrue(b, b.contains("我的客户")); assertTrue(b.contains("我们最近聊过")); assertFalse(b.contains("用户"))
        assertTrue(C.brief(null).contains("第一次"))
        assertEquals(m, C.fromJson(C.toJson(m)))
        var many = emptyMap<String, ContactMemoryCore.Contact>()
        repeat(8) { many = C.touch(many, "a@b.c", "t$it", it.toLong()) }
        assertEquals(C.MAX_TOPICS, many["a@b.c"]!!.topics.size)
    }

    @Test fun sentFolderAndOwnPart() {
        val list = listOf("* LIST (\\HasNoChildren) \"/\" \"INBOX\"", "* LIST (\\HasNoChildren \\Sent) \"/\" \"&XfJT0ZAB-\"")
        assertEquals("&XfJT0ZAB-", PersonaMail.sentFolder(list))
        assertEquals("Sent Messages", PersonaMail.sentFolder(listOf("* LIST () \"/\" \"Sent Messages\"")))
        assertEquals("好的，明天给你。", PersonaMail.ownPart("好的，明天给你。\n\n-----原始邮件-----\n发件人: x"))
    }

    @Test fun smtpMessageIsEncoded() {
        val m = PersonaMail.message("me@163.com", "you@qq.com", "你好", "正文", 0L)
        assertTrue(m.contains("Subject: =?UTF-8?B?"))
        assertTrue(m.contains("Content-Transfer-Encoding: base64"))
        assertEquals("smtp.163.com", PersonaMail.smtpHost("me@163.com"))
        assertNull(PersonaMail.smtpHost("me@example.org"))
    }
}

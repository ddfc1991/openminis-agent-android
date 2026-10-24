package com.openminis.app.plus

import com.openminis.app.plus.AccountsCore.Acct
import com.openminis.app.plus.AccountsCore.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] account pick, order of ways in, challenges, relogin, secrets never leave. */
class AccountsCoreTest {
    private val C = AccountsCore
    private val ali = Acct("aliyun.com", "阿里云", "demo_user", "OK", setOf(Way.SESSION, Way.PASSWORD))
    private val gh1 = Acct("github.com", "GitHub", "alice", "OK", setOf(Way.SESSION))
    private val gh2 = Acct("github.com", "GitHub", "bob", "UNCHECKED", setOf(Way.PASSWORD))
    @Test fun aliasesFindTheAccountSite() {
        assertEquals("aliyun.com", C.accountSite("https://bailian.console.aliyun.com/cn-beijing/"))
        assertEquals("google.com", C.accountSite("mail.google.com"))
        assertEquals("163.com", C.accountSite("mail.163.com"))
        assertTrue(C.sameSite("aliyun.com", "ecs.console.aliyun.com")); assertFalse(C.sameSite("aliyun.com", "notaliyun.com"))
    }
    @Test fun pickOneOrAskOnceThenRemember() {
        assertEquals(AccountsCore.Pick.One(ali), C.pick("bailian.console.aliyun.com", listOf(ali, gh1), null))
        val ask = C.pick("github.com", listOf(ali, gh1, gh2), null)
        assertTrue(ask is AccountsCore.Pick.Ask); assertEquals("alice", (ask as AccountsCore.Pick.Ask).choices.first().identity)
        assertEquals(AccountsCore.Pick.One(gh2, remembered = true), C.pick("github.com", listOf(gh1, gh2), gh2.key))
        assertEquals(AccountsCore.Pick.None, C.pick("example.org", listOf(ali), null))
    }
    @Test fun orderIsSessionPasswordProviderCode() {
        assertEquals(listOf(Way.SESSION, Way.PASSWORD, Way.THIRD_PARTY, Way.CODE), C.order(true, true, listOf("Google"), listOf("Gmail")))
        assertEquals(listOf(Way.THIRD_PARTY, Way.CODE), C.order(false, false, listOf("GitHub"), listOf("163")))
        assertEquals(listOf(Way.KEY), C.order(true, true, listOf("Google"), listOf("x"), isKey = true))
        assertEquals("已登录会话 → 保险箱密码 → Google / GitHub → 验证码", C.orderText(C.order(true, true, listOf("Google", "GitHub"), listOf("m")), listOf("Google", "GitHub")))
    }
    @Test fun challengesNeverBypassed() {
        assertEquals(AccountsCore.Challenge.SILENT_WAIT, C.challenge("Cloudflare Turnstile"))
        assertEquals(AccountsCore.Challenge.SILENT_WAIT, C.challenge("Cloudflare"))
        assertEquals(AccountsCore.Challenge.ASK_USER, C.challenge("网易易盾验证"))
        assertEquals(AccountsCore.Challenge.ASK_USER, C.challenge("reCAPTCHA"))
        assertEquals(AccountsCore.Challenge.NONE, C.challenge(""))
        assertEquals(15_000L, AccountsCore.TURNSTILE_WAIT_MS)
    }
    @Test fun reloginOnlyOnConfirmedSignOut() {
        assertEquals(AccountsCore.Relogin.NOTHING, C.relogin(false, setOf(Way.PASSWORD), false))
        assertEquals(AccountsCore.Relogin.AUTO, C.relogin(true, setOf(Way.PASSWORD), false))
        assertEquals(AccountsCore.Relogin.ASK_USER, C.relogin(true, emptySet(), false))
        assertEquals(AccountsCore.Relogin.NOTHING, C.relogin(true, setOf(Way.PASSWORD), true))
    }
    @Test fun reasonsAndSecrets() {
        assertEquals("密钥无效或已被删除（401）", C.failReason("", 401))
        assertEquals("页面要人机验证，需要你点一下", C.failReason("网易易盾 captcha"))
        assertEquals("Google 不让在 App 内登录，改用其他方式", C.failReason("Error 403: disallowed_useragent"))
        assertEquals("138****8000", C.maskPhone("13800138000"))
        val s = C.scrub("key sk-abcdefghijklmnop and password=hunter2 ghp_ABCDEFGHIJKLMNOP")
        assertFalse(s.contains("sk-abc")); assertFalse(s.contains("hunter2")); assertFalse(s.contains("ghp_"))
        assertTrue(C.line(ali).contains("已登录会话 → 保险箱密码"))
    }
    @Test fun stuckReloginHasAReason() {
        assertEquals(AccountsCore.Stuck.CAPTCHA, C.stuck(true, true, true, null, true))
        assertEquals(AccountsCore.Stuck.CODE, C.stuck(false, true, false, null, true))
        assertEquals(AccountsCore.Stuck.NETWORK, C.stuck(false, false, false, "net::ERR_TIMED_OUT", false))
        assertEquals(AccountsCore.Stuck.NO_PAGE, C.stuck(false, false, false, "net::ERR_NAME_NOT_RESOLVED", false))
        assertEquals(AccountsCore.Stuck.NETWORK, C.stuck(false, false, false, null, false))
        assertEquals(AccountsCore.Stuck.PASSWORD, C.stuck(false, false, true, null, true))
        assertEquals(AccountsCore.Stuck.SLOW, C.stuck(false, false, false, null, true))
        assertTrue(C.stuckLine("github.com", AccountsCore.Stuck.CAPTCHA, 60).contains("需要人机验证"))
        assertTrue(C.stuckLine("mail.163.com", AccountsCore.Stuck.NETWORK, 60).contains("重试"))
        assertTrue(AccountsCore.Stuck.CODE.needsUser); assertFalse(AccountsCore.Stuck.NETWORK.needsUser)
    }
}

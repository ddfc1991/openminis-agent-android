package com.openminis.app.plus

import com.openminis.app.plus.HandoffCore.Handoff
import com.openminis.app.plus.HandoffCore.Kind
import com.openminis.app.plus.HandoffCore.State
import com.openminis.app.plus.HandoffCore.TabInfo
import com.openminis.app.plus.HandoffCore.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.13 handoff_v2] hand a browser step to the user and back. */
class HandoffCoreTest {
    private fun h(id: String = "h1", sid: String = "s1", tab: Int = 2, at: Long = 1_000L, kind: Kind = Kind.CAPTCHA) =
        Handoff(id, sid, tab, "https://railway.com/login", "railway.com", kind, createdAt = at)

    @Test fun stateMachine() {
        val a = h()
        val active = HandoffCore.move(a, State.USER_ACTIVE, 2_000L)!!
        assertEquals(State.USER_ACTIVE, active.state); assertEquals(2_000L, active.progressAt)
        assertEquals(State.DONE, HandoffCore.move(active, State.DONE, 3_000L)!!.state)
        assertEquals(State.WAITING, HandoffCore.move(active, State.WAITING, 3_000L)!!.state)   // sign-in not confirmed → back to waiting
        assertEquals(State.CANCELLED, HandoffCore.move(a, State.CANCELLED, 3_000L)!!.state)
        val exp = HandoffCore.move(a, State.EXPIRED, 3_000L)!!
        assertFalse(exp.open)
        assertEquals(State.DONE, HandoffCore.move(exp, State.DONE, 4_000L)!!.state)            // 「继续接管」 after the pause
        assertNull(HandoffCore.move(exp, State.WAITING, 4_000L))
        val done = HandoffCore.move(a, State.DONE, 3_000L)!!
        assertNull(HandoffCore.move(done, State.CANCELLED, 4_000L))
        assertNull(HandoffCore.move(done, State.DONE, 4_000L))
        assertNull(HandoffCore.move(a, State.WAITING, 4_000L))
    }

    @Test fun perConversation() {
        val all = listOf(h("a", "s1", at = 1), h("b", "s2", at = 2), h("c", "s1", at = 3).copy(state = State.DONE))
        assertEquals(listOf("a", "c"), HandoffCore.forSession(all, "s1").map { it.id })
        assertEquals("a", HandoffCore.pick(all, "s1", null)!!.id)       // newest OPEN one wins over a newer finished one
        assertEquals("c", HandoffCore.pick(all, "s1", "c")!!.id)
        assertNull(HandoffCore.pick(all, "s1", "b"))                     // another conversation's handoff is never picked
        assertEquals("c", HandoffCore.pick(all.filter { it.id != "a" }, "s1", null)!!.id)
    }

    @Test fun watchdogStages() {
        val a = h(at = 0L)
        assertEquals(Watch.NONE, HandoffCore.watchdog(a, 29_000L))
        assertEquals(Watch.REMIND, HandoffCore.watchdog(a, 30_000L))
        val r = a.copy(stage = 1)
        assertEquals(Watch.NONE, HandoffCore.watchdog(r, 119_000L))
        assertEquals(Watch.CARD, HandoffCore.watchdog(r, 120_000L))
        assertEquals(Watch.EXPIRE, HandoffCore.watchdog(r.copy(stage = 2), 300_000L))
        assertEquals(Watch.NONE, HandoffCore.watchdog(r.copy(stage = 3), 900_000L))
        assertEquals(Watch.NONE, HandoffCore.watchdog(a.copy(progressAt = 200_000L), 220_000L))   // the user moved: clock restarts
        assertEquals(Watch.NONE, HandoffCore.watchdog(a.copy(state = State.DONE), 900_000L))
    }

    @Test fun oauthAskedOncePerTenMinutes() {
        assertTrue(HandoffCore.shouldAskOauth(null, 5L))
        assertFalse(HandoffCore.shouldAskOauth(0L, 9 * 60_000L))
        assertTrue(HandoffCore.shouldAskOauth(0L, 10 * 60_000L))
    }

    @Test fun loggedInNeedsTwoSignals() {
        val start = setOf("_ga", "cf_clearance")
        // password field gone + session cookie
        assertTrue(HandoffCore.loggedIn(HandoffCore.signals(true, true, start, false, "https://x.com/login", start + "session_id", false)))
        // URL left /login + 退出 on the page
        assertTrue(HandoffCore.loggedIn(HandoffCore.signals(false, true, start, false, "https://x.com/dashboard", start, true)))
        // only a welcome page / only a URL change: not enough
        assertFalse(HandoffCore.loggedIn(HandoffCore.signals(false, true, start, false, "https://x.com/welcome", start, false)))
        assertFalse(HandoffCore.loggedIn(HandoffCore.signals(false, false, start, false, "https://x.com/", start, true)))
        // a page that never had a password field / never was a login URL gives no credit for lacking one
        assertFalse(HandoffCore.loggedIn(HandoffCore.signals(false, false, start, false, "https://x.com/", start + "_gid", false)))
        assertTrue(HandoffCore.isAuthUrl("https://railway.com/login?next=/"))
        assertFalse(HandoffCore.isAuthUrl("https://railway.com/project/1?from=login"))
        assertEquals(setOf("a", "b"), HandoffCore.cookieNames("a=1; b=2"))
    }

    @Test fun captchaAndOtpCleared() {
        val u = "https://x.com/challenge?__cf_chl_tk=1"
        assertFalse(HandoffCore.captchaCleared(u, "$u#a", false, false, "Cloudflare"))
        assertTrue(HandoffCore.captchaCleared(u, "https://x.com/", false, false, "Cloudflare"))
        assertTrue(HandoffCore.captchaCleared(u, u, false, true, "Cloudflare"))
        assertFalse(HandoffCore.captchaCleared(u, u, true, true, "Cloudflare"))
        assertTrue(HandoffCore.captchaCleared(u, u, false, false, ""))
        assertTrue(HandoffCore.otpCleared(u, u, false)); assertFalse(HandoffCore.otpCleared(u, u, true))
    }

    @Test fun pickResumeTab() {
        val site = "railway.com"
        // the user opened tab 5 and signed in there → the agent continues on 5
        assertEquals(5, HandoffCore.pickResumeTab(2, 5, 4, site, listOf(TabInfo(2, "https://railway.com/login", false), TabInfo(5, "https://railway.com/dashboard", true))))
        // window.open popup (tab 6) signed in, user still looking at tab 2
        assertEquals(6, HandoffCore.pickResumeTab(2, 2, 4, site, listOf(TabInfo(2, "https://railway.com/login", false), TabInfo(6, "https://app.railway.com/", true))))
        // same URL in two tabs: an older unrelated tab (1) is never adopted
        assertEquals(2, HandoffCore.pickResumeTab(2, 2, 4, site, listOf(TabInfo(1, "https://railway.com/x", true), TabInfo(2, "https://railway.com/x", true))))
        // the new tab is on another site → stay on the handoff tab
        assertEquals(2, HandoffCore.pickResumeTab(2, 5, 4, site, listOf(TabInfo(2, "https://railway.com/login", false), TabInfo(5, "https://github.com/", true))))
        // handoff tab closed → the selected one; nothing at all → the original id
        assertEquals(5, HandoffCore.pickResumeTab(2, 5, 4, site, listOf(TabInfo(5, "https://github.com/", false))))
        assertEquals(2, HandoffCore.pickResumeTab(2, null, 4, site, emptyList()))
        assertEquals("railway.com", HandoffCore.site("https://app.railway.com/x")); assertEquals("example.com.cn", HandoffCore.site("a.example.com.cn"))
    }

    @Test fun agentTextsAndLock() {
        val a = h()
        assertTrue(HandoffCore.pendingText(a, "点一下人机验证").contains("wait_for_handoff"))
        assertTrue(HandoffCore.pendingText(a, "x").contains("不要重试点击"))
        val t = HandoffCore.doneText(a, 5, "https://railway.com/dashboard", true, manual = false)
        assertTrue(t.contains("use tab_id 5")); assertTrue(t.contains("✅"))
        assertTrue(HandoffCore.doneText(a, 2, "u", null, manual = true).contains("用户手动确认"))
        assertTrue(HandoffCore.stopText(a).contains("不要再调用 wait_for_handoff"))
        assertTrue(HandoffCore.blocks(a, "click")); assertFalse(HandoffCore.blocks(a, "screenshot"))
        assertFalse(HandoffCore.blocks(a.copy(state = State.DONE), "click")); assertFalse(HandoffCore.blocks(null, "click"))
    }
}

class HandoffLabelTest {
    @Test fun taskName() {
        assertEquals("Railway 注册 · 第2次", HandoffLabel.taskName("Railway 注册", 2, "帮我注册 Railway", "Railway", "https://railway.com/login"))
        assertEquals("Railway 注册 · 第2次", HandoffLabel.taskName("Railway 注册 · 第2次", 2, null, "Railway", ""))
        assertEquals("Railway 注册", HandoffLabel.taskName("Railway 注册", 1, null, "Railway", ""))
        assertEquals("帮我注册 Railway", HandoffLabel.taskName(null, 1, "帮我注册 Railway", "Railway", "https://railway.com/login"))
        assertEquals("Railway 注册", HandoffLabel.taskName(" ", 1, "新对话", "Railway", "https://railway.com/signup"))
        assertEquals("Railway 登录", HandoffLabel.taskName(null, 1, null, "Railway", "https://railway.com/login"))
        assertEquals("Railway", HandoffLabel.siteWord("https://app.railway.com/x"))
    }

    @Test fun actionWords() {
        assertEquals("输验证码", HandoffLabel.action(HandoffCore.Kind.OTP))
        assertEquals("登录", HandoffLabel.action(HandoffCore.Kind.LOGIN))
        assertEquals("点一下人机验证（Cloudflare）", HandoffLabel.action(HandoffCore.Kind.CAPTCHA, "Cloudflare"))
        assertEquals("点一下人机验证", HandoffLabel.action(HandoffCore.Kind.CAPTCHA, "人机验证"))
        assertEquals("点确认", HandoffLabel.action(HandoffCore.Kind.CONFIRM))
        assertEquals("在 Chrome 里用 Google 登录", HandoffLabel.action(HandoffCore.Kind.OAUTH_EXTERNAL, host = "accounts.google.com"))
        assertEquals("在 Chrome 里用 Apple 登录", HandoffLabel.action(HandoffCore.Kind.OAUTH_EXTERNAL, host = "appleid.apple.com"))
        assertEquals("Railway 注册 · 需要你点一下验证", HandoffLabel.title("Railway 注册", "点一下验证"))
        assertEquals("railway.com · Mines 在等你，完成后自动继续", HandoffLabel.body("app.railway.com"))
    }
}

class OAuthOnlyCoreTest {
    @Test fun emailFirst() {
        // Railway login: GitHub / Google / Email buttons
        assertEquals(OAuthOnlyCore.Kind.EMAIL, OAuthOnlyCore.detect(false, listOf("Log in with GitHub", "Log in with Google", "Log in using Email")))
        assertEquals("Log in using Email", OAuthOnlyCore.emailEntry(listOf("Log in with GitHub", "Log in with Google", "Log in using Email")))
        // Vercel sign-up
        assertEquals("Continue with Email", OAuthOnlyCore.emailEntry(listOf("Continue with GitHub", "Continue with GitLab", "Continue with Bitbucket", "Continue with Email")))
        // Supabase / Hugging Face: the form is right there
        assertEquals(OAuthOnlyCore.Kind.EMAIL, OAuthOnlyCore.detect(true, listOf("Continue with GitHub", "Sign Up")))
        // 国内: 手机号注册
        assertEquals(OAuthOnlyCore.Kind.EMAIL, OAuthOnlyCore.detect(false, listOf("微信登录", "手机号注册")))
    }

    @Test fun oauthOnly() {
        assertEquals(OAuthOnlyCore.Kind.OAUTH_ONLY, OAuthOnlyCore.detect(false, listOf("Continue with Google", "Continue with GitHub", "Terms")))
        assertEquals(OAuthOnlyCore.Kind.OAUTH_ONLY, OAuthOnlyCore.detect(false, listOf("Sign in with Apple")))
        assertNull(OAuthOnlyCore.emailEntry(listOf("Continue with Google (gmail)")))
        assertEquals(OAuthOnlyCore.Kind.UNKNOWN, OAuthOnlyCore.detect(false, listOf("Pricing", "Docs")))
    }
}

class Inbox3912EmailOnlyTest {
    @Test fun registerTaskAsksForEmail() {
        assertTrue(Inbox3912Core.registerTask("Railway", "https://railway.com/login", null, emailOnly = true).contains("不要点 Google / Apple"))
        assertFalse(Inbox3912Core.registerTask("Railway", "https://railway.com/login", null).contains("不要点 Google"))
        assertTrue(Inbox3912Core.registerTaskSelf("Vercel", "https://vercel.com/signup", "a@b.c", emailOnly = true).contains("用邮箱注册"))
    }
}

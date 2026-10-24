package com.openminis.app.plus

import com.openminis.app.plus.WebSessionCore.St
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] web sign-in state from a real authenticated request (Notion 38.9.4: 「需要登录」 after signing in). */
class WebSessionCoreTest {
    private val notion = WebSessionCore.probeFor("notion", "https://www.notion.so/login", null, null)!!

    @Test fun notionProbeIsGetSpaces() {
        assertEquals("https://www.notion.so/api/v3/getSpaces", notion.url)
        assertEquals("POST", notion.method)
    }

    @Test fun notionSignedInOnlyWithSpaces() {
        val body = """{"1f2e":{"notion_user":{"1f2e":{"value":{"id":"1f2e"}}},"space":{"ab":{}}}}"""
        assertEquals(St.SIGNED_IN, WebSessionCore.judge(notion, 200, body, null, true))
        assertEquals(St.SIGNED_OUT, WebSessionCore.judge(notion, 200, "{}", null, true))
        assertEquals(St.SIGNED_OUT, WebSessionCore.judge(notion, 401, """{"errorId":"x","name":"UnauthorizedError"}""", null, true))
        assertEquals(St.ERROR, WebSessionCore.judge(notion, 0, "", null, true))
    }

    @Test fun genericRedirectToLoginIsSignedOut() {
        val p = WebSessionCore.probeFor("x", null, "https://console.example.com/home", null)!!
        assertEquals(St.SIGNED_OUT, WebSessionCore.judge(p, 302, "", "https://passport.example.com/login?redirect=x", true))
        assertEquals(St.SIGNED_IN, WebSessionCore.judge(p, 200, "<html>", null, true))
        assertEquals(St.SIGNED_OUT, WebSessionCore.judge(p, 200, "<html>", null, false))
    }

    /** The 38.9.4 bug as a transition: page said 已连接 while no session existed → a check must flip it to 未登录. */
    @Test fun transitionAfterChromeGoogleLogin() {
        var st = St.UNCHECKED
        st = WebSessionCore.next(st, St.CHECKING); assertEquals(St.CHECKING, st)
        st = WebSessionCore.next(st, WebSessionCore.judge(notion, 200, "{}", null, false)); assertEquals(St.SIGNED_OUT, st)
        assertFalse("「我已登录好了」 must not close the page", WebSessionCore.finishAllowed(st))
        // user signs in with email + code inside Mines → the next check confirms it
        st = WebSessionCore.next(st, WebSessionCore.judge(notion, 200, """{"u":{"notion_user":{},"space":{}}}""", null, true))
        assertEquals(St.SIGNED_IN, st)
        assertTrue(WebSessionCore.finishAllowed(st))
    }

    @Test fun networkBlipNeverFlipsAConfirmedState() {
        assertEquals(St.SIGNED_IN, WebSessionCore.next(St.SIGNED_IN, St.ERROR))
        assertEquals(St.SIGNED_OUT, WebSessionCore.next(St.SIGNED_OUT, St.ERROR))
        assertEquals(St.ERROR, WebSessionCore.next(St.UNCHECKED, St.ERROR))
    }

    @Test fun heroLinesAreConsistent() {
        assertEquals("已登录 · 网页登录", WebSessionCore.heroLine(St.SIGNED_IN, "网页登录"))
        assertTrue(WebSessionCore.heroLine(St.SIGNED_OUT, "网页登录").startsWith("未登录"))
    }
}

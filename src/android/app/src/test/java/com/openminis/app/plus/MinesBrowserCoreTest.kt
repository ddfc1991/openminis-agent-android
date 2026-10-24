package com.openminis.app.plus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MinesBrowserCoreTest {
    @Test fun engineArgument() {
        assertEquals("gecko", MinesBrowserCore.engineOf("""{"action":"navigate","engine":"Gecko"}"""))
        assertEquals("webview", MinesBrowserCore.engineOf("""{"action":"navigate","engine":"webview"}"""))
        assertNull(MinesBrowserCore.engineOf("""{"action":"navigate","engine":"chrome"}"""))
        assertNull(MinesBrowserCore.engineOf("""{"action":"navigate"}"""))
        assertNull(MinesBrowserCore.engineOf("not json"))
    }

    @Test fun routingKeepsWebViewTheDefault() {
        // flag off → always the old WebView path
        assertFalse(MinesBrowserCore.useGecko(false, "gecko", true, "navigate"))
        // no engine and no gecko session → WebView
        assertFalse(MinesBrowserCore.useGecko(true, null, false, "navigate"))
        assertTrue(MinesBrowserCore.useGecko(true, "gecko", false, "navigate"))
        assertFalse(MinesBrowserCore.useGecko(true, "webview", true, "click"))
        // follow-ups of an owner that has a gecko session stay on it
        assertTrue(MinesBrowserCore.useGecko(true, null, true, "click"))
        assertTrue(MinesBrowserCore.useGecko(true, null, true, "get_text"))
        // the WebView pool keeps its tab management
        assertFalse(MinesBrowserCore.useGecko(true, null, true, "list_tabs"))
        assertFalse(MinesBrowserCore.useGecko(true, null, true, "new_tab"))
    }

    @Test fun contextIdIsSafeAndPerTask() {
        assertEquals("mines-task-abc-1", MinesBrowserCore.contextId("abc", 1))
        assertEquals("mines-task-chat-2", MinesBrowserCore.contextId(null, 2))
        assertEquals("mines-task-a_bc-3", MinesBrowserCore.contextId("a_b/c:", 3))
        assertTrue(MinesBrowserCore.contextId("x".repeat(100), 4).length < 60)
    }

    @Test fun proxyNeverDirectUnderStrict() {
        val p = MinesBrowserCore.proxyPrefs(7899, strict = false)
        assertEquals(1, p["network.proxy.type"]); assertEquals(7899, p["network.proxy.ssl_port"]); assertEquals("127.0.0.1", p["network.proxy.http"])
        val blocked = MinesBrowserCore.proxyPrefs(null, strict = true)
        assertEquals(1, blocked["network.proxy.type"]); assertEquals(1, blocked["network.proxy.http_port"])
        val direct = MinesBrowserCore.proxyPrefs(null, strict = false)
        assertEquals(0, direct["network.proxy.type"]); assertNull(direct["network.proxy.http"])
        // WebRTC off in every case (no real-IP leak)
        listOf(p, blocked, direct).forEach { assertEquals(false, it["media.peerconnection.enabled"]) }
        assertEquals(0, MinesBrowserCore.proxyPrefs(70000, strict = false)["network.proxy.type"])
        assertTrue(MinesBrowserCore.proxyLabel(null, true).contains("不直连"))
    }

    @Test fun typingIsHumanPaced() {
        val r = Random(7)
        val d = MinesBrowserCore.typingDelays("hello world, ok", r)
        assertEquals(15, d.size)
        assertTrue(d.all { it in 45L..1200L })
        // after the comma there is a thinking pause
        assertTrue(d[12] >= 350L)
        // a long text is sped up but never longer than the cap
        val long = MinesBrowserCore.typingDelays("x".repeat(500), Random(1), maxTotalMs = 12_000L)
        assertTrue(long.sum() <= 12_000L + 500)
        assertTrue(long.all { it >= 8L })
        assertTrue(MinesBrowserCore.typingDelays("").isEmpty())
    }

    @Test fun clicksAndScrollsLookHuman() {
        repeat(50) {
            val r = Random(it)
            assertTrue(MinesBrowserCore.clickPauseMs(r) in 250L..700L)
            assertTrue(MinesBrowserCore.pressMs(r) in 60L..140L)
            val j = MinesBrowserCore.jitter(30f, r); assertTrue(j in -5f..5f)
            val steps = MinesBrowserCore.scrollSteps(-500, r)
            assertTrue(steps.size in 3..6); assertEquals(-500, steps.sum())
        }
        assertEquals(0f, MinesBrowserCore.jitter(0f))
        assertEquals(listOf(0), MinesBrowserCore.scrollSteps(0))
    }

    @Test fun postingNeedsTheUsersYes() {
        listOf("post", "send", "Message", "reply", "comment", "pay").forEach { assertTrue(it, MinesBrowserCore.needsUserConfirm(it)) }
        listOf("read", "archive", "label", "draft", "navigate").forEach { assertFalse(it, MinesBrowserCore.needsUserConfirm(it)) }
    }

    @Test fun bridgeReplies() {
        val r = MinesBrowserCore.parseReply(JSONObject().put("id", 3).put("ok", true).put("value", "{\"a\":1}"))!!
        assertEquals(3, r.id); assertTrue(r.ok); assertEquals("{\"a\":1}", r.value)
        val e = MinesBrowserCore.parseReply("""{"id":4,"ok":false,"value":"boom"}""")!!
        assertFalse(e.ok); assertEquals("boom", e.value)
        assertNull(MinesBrowserCore.parseReply("""{"ok":true}"""))
        assertNull(MinesBrowserCore.parseReply(42))
        assertTrue(MinesBrowserCore.wrapUserScript("return 1").contains("async function(){ return 1 }"))
    }

    @Test fun supportedActionsAndPrompt() {
        assertTrue("navigate" in MinesBrowserCore.SUPPORTED && "type" in MinesBrowserCore.SUPPORTED && "close_tab" in MinesBrowserCore.SUPPORTED)
        assertFalse("get_cookies" in MinesBrowserCore.SUPPORTED)
        assertTrue(MinesBrowserCore.unsupported("get_cookies").contains("webview"))
        assertTrue(MinesBrowserCore.PROMPT.contains("Google and Apple block"))
    }
}

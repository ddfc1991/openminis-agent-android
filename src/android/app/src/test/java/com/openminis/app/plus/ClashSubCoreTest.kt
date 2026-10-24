package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.8] subscription through the phone's own proxy, plain errors without the link, links / deep links, CMFA API. */
class ClashSubCoreTest {
    @Test fun planDirectThenLocalProxies() {
        val p = ClashSubCore.plan(listOf(ClashSubCore.Route("Clash", "http", "127.0.0.1", 7890), ClashSubCore.Route("dup", "http", "127.0.0.1", 7890),
            ClashSubCore.Route("系统代理", "http", "10.0.0.2", 8080)))
        assertEquals("direct", p[0].route.type); assertEquals(7890, p[1].route.port); assertEquals(8080, p[2].route.port)
        assertEquals("clash.meta", p[0].ua); assertTrue(p.size <= 12); assertTrue(p.any { it.ua.startsWith("ClashMetaForAndroid") })
    }

    @Test fun errorsNeverCarryTheLink() {
        val r = ClashSubCore.errorReason("https://sub.example.com/sub/abcdef.yaml")
        assertFalse(r.contains("example.com")); assertFalse(r.contains("abcdef"))
        assertEquals("下载被中途断开（直连多半被重置）", ClashSubCore.errorReason("connection closed"))
        assertTrue(ClashSubCore.httpReason(403).contains("403"))
        val s = ClashSubCore.summary(listOf("直连" to "下载被中途断开（直连多半被重置）", "Clash 7890" to ClashSubCore.httpReason(403)))
        assertTrue(s.startsWith("订阅下载失败：直连：")); assertTrue(s.contains("Clash 7890"))
    }

    @Test fun kinds() {
        assertEquals("yaml", ClashSubCore.kind("port: 1\nproxies:\n  - name: a"))
        assertEquals("links", ClashSubCore.kind("vmess://abc\ntrojan://x@y:443"))
        val b64 = java.util.Base64.getEncoder().encodeToString("ss://YWVz@h:1#a\ntrojan://p@h:443#b\n".toByteArray())
        assertEquals("links", ClashSubCore.kind(b64)); assertEquals(2, ClashSubCore.countLinks(b64))
        assertEquals("html", ClashSubCore.kind("<html><body>403</body></html>"))
        assertEquals("", ClashSubCore.kind("hello world"))
        assertTrue(ClashSubCore.providerConfig("mixed-port: 7898\n", "./sub.txt").contains("use: [订阅]"))
    }

    @Test fun findLinks() {
        assertEquals("https://a.example/sub?token=1", ClashSubCore.findLink("clash://install-config?url=https%3A%2F%2Fa.example%2Fsub%3Ftoken%3D1&name=x"))
        assertEquals("https://b.example/api/v1/client/subscribe?token=z", ClashSubCore.findLink("我的订阅：https://b.example/api/v1/client/subscribe?token=z 。"))
        assertEquals("https://c.example/s/abc.yaml", ClashSubCore.findLink("https://c.example/s/abc.yaml"))
        assertNull(ClashSubCore.findLink("看看 https://www.example.com/news/1"))
        assertNull(ClashSubCore.findLink("https://substack.com/p/hello"))
        assertEquals("https://f.example/sub/6cad80.yaml", ClashSubCore.findLink("https://f.example/sub/6cad80.yaml"))
        assertNull(ClashSubCore.findLink(null))
    }

    @Test fun controllerJson() {
        val pv = ClashSubCore.parseProviders("""{"providers":{"default":{"name":"default","vehicleType":"Compatible","proxies":[{}]},"机场":{"name":"机场","vehicleType":"HTTP","proxies":[{},{},{}]}}}""")
        assertEquals(listOf(ClashSubCore.Provider("机场", "HTTP", 3)), pv)
        val g = ClashSubCore.parseGroups("""{"proxies":{"GLOBAL":{"type":"Selector","now":"a"},"节点选择":{"type":"Selector","now":"香港 01"},"香港 01":{"type":"Vmess"}}}""")
        assertEquals(listOf("节点选择" to "香港 01"), g)
        assertTrue(ClashSubCore.parseProviders("not json").isEmpty())
    }

    @Test fun startStateNeverStuck() {
        assertEquals("正在启动…", ClashSubCore.startState(2_000, true, false, "", ""))
        assertTrue(ClashSubCore.startState(12_000, true, false, "", "level=error msg=https://x.example/sub bad").startsWith("启动超时（12 秒没就绪）"))
        assertFalse(ClashSubCore.startState(12_000, true, false, "", "https://x.example/sub").contains("x.example"))
        assertTrue(ClashSubCore.startState(1_000, false, false, "退出码 1", "parse error").startsWith("启动失败（退出码 1）"))
        assertEquals("运行中", ClashSubCore.startState(1_000, true, true, "", ""))
    }
    @Test fun restartLineSaysWhyAfterThreeFailures() {
        assertEquals("内核退出（退出码 1），2 秒后重启", ClashSubCore.restartLine(1, "退出码 1", "", 2))
        val l = ClashSubCore.restartLine(3, "退出码 1", "bind: address already in use https://sub.example/x?token=abc", 8)
        org.junit.Assert.assertTrue(l.startsWith("自动重启失败 3 次") && l.contains("address already in use") && !l.contains("token=abc") && l.endsWith("8 秒后再试"))
    }
}

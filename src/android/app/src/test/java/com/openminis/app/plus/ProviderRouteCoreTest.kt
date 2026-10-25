package com.openminis.app.plus

import com.openminis.app.plus.ProviderRouteCore.Fail
import com.openminis.app.plus.ProviderRouteCore.Next
import com.openminis.app.plus.ProviderRouteCore.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.3] provider routes: China direct / foreign via proxy, remembered fallback, fast failover, timeouts. */
class ProviderRouteCoreTest {
    private val now = 10_000_000L

    @Test fun chinaHostsGoDirectFirst() {
        for (h in listOf("token.sensenova.cn", "developer.amd.com.cn", "api.deepseek.com", "api.moonshot.cn", "open.bigmodel.cn",
            "api.siliconflow.cn", "dashscope.aliyuncs.com", "ark.cn-beijing.volces.com", "qianfan.baidubce.com", "apihub.agnes-ai.com"))
            assertEquals(h, listOf(Route.DIRECT), ProviderRouteCore.order(h, null, now, proxyAvailable = true))   // [38.9.11.3] direct only
    }

    @Test fun foreignHostsGoThroughTheProxyFirst() {
        for (h in listOf("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com", "openrouter.ai", "api.x.ai", "api.groq.com"))
            assertEquals(h, listOf(Route.PROXY, Route.DIRECT), ProviderRouteCore.order(h, null, now, proxyAvailable = true))
        // the 中转 / unknown hosts: direct first (no proxy forced on them)
        assertEquals(listOf(Route.DIRECT, Route.PROXY), ProviderRouteCore.order("relay.example.net", null, now, true))
    }

    @Test fun noProxyOrLocalMeansDirectOnly() {
        assertEquals(listOf(Route.DIRECT), ProviderRouteCore.order("api.openai.com", null, now, proxyAvailable = false))
        for (h in listOf("127.0.0.1", "10.0.2.2", "192.168.1.5", "localhost", "100.100.1.2"))
            assertEquals(h, listOf(Route.DIRECT), ProviderRouteCore.order(h, null, now, true))
    }

    @Test fun memoryWinsAndFailedRouteGoesLast() {
        // sensenova worked through the proxy last time → proxy first for 30 min
        val m = ProviderRouteCore.recordOk(null, Route.PROXY, 120, now)
        assertEquals(listOf(Route.PROXY, Route.DIRECT), ProviderRouteCore.order("relay.example.net", m, now + 60_000, true))
        assertEquals(listOf(Route.DIRECT, Route.PROXY), ProviderRouteCore.order("relay.example.net", m, now + ProviderRouteCore.OK_TTL_MS + 1, true))
        assertEquals(listOf(Route.DIRECT), ProviderRouteCore.order("token.sensenova.cn", m, now + 60_000, true))   // China: never the proxy
        // openai: the proxy (dead Clash) failed → direct first for 5 min
        val f = ProviderRouteCore.recordFail(null, Route.PROXY, now)
        assertEquals(listOf(Route.DIRECT, Route.PROXY), ProviderRouteCore.order("api.openai.com", f, now + 1000, true))
        assertEquals(listOf(Route.PROXY, Route.DIRECT), ProviderRouteCore.order("api.openai.com", f, now + ProviderRouteCore.FAIL_TTL_MS + 1, true))
        // a remembered route that then fails is dropped
        val m2 = ProviderRouteCore.recordFail(m, Route.PROXY, now + 10)
        assertEquals(null, m2.okRoute)
    }

    @Test fun bothDownFailsFastForAMinute() {
        var m = ProviderRouteCore.recordFail(null, Route.DIRECT, now)
        assertFalse(ProviderRouteCore.bothDownRecently(m, now + 10, true))
        m = ProviderRouteCore.recordFail(m, Route.PROXY, now + 20)
        assertTrue(ProviderRouteCore.bothDownRecently(m, now + 30, true))
        assertFalse(ProviderRouteCore.bothDownRecently(m, now + 20 + ProviderRouteCore.BOTH_DOWN_MS + 1, true))
        assertTrue(ProviderRouteCore.bothDownRecently(ProviderRouteCore.recordFail(null, Route.DIRECT, now), now + 5, proxyAvailable = false))
        assertFalse(ProviderRouteCore.bothDownRecently(ProviderRouteCore.recordOk(m, Route.DIRECT, 50, now + 40), now + 50, true))
    }

    @Test fun probePicksTheRouteThatAnswered() {
        assertEquals(Route.DIRECT, ProviderRouteCore.pickByProbe(300, null))
        assertEquals(Route.PROXY, ProviderRouteCore.pickByProbe(null, 400))
        assertEquals(null, ProviderRouteCore.pickByProbe(null, null))
        assertEquals(Route.DIRECT, ProviderRouteCore.pickByProbe(500, 300))          // direct wins near-ties
        assertEquals(Route.PROXY, ProviderRouteCore.pickByProbe(2500, 300))          // proxy clearly faster
        assertEquals(Route.PROXY, ProviderRouteCore.pickByProbe(300, 500, preferProxy = true))
        assertFalse(ProviderRouteCore.needsProbe("token.sensenova.cn", null, now, true))   // [38.9.11.3] China: direct only, no probe
        assertTrue(ProviderRouteCore.needsProbe("relay.example.net", null, now, true))
        assertFalse(ProviderRouteCore.needsProbe("token.sensenova.cn", null, now, false))
        assertFalse(ProviderRouteCore.needsProbe("token.sensenova.cn", ProviderRouteCore.recordOk(null, Route.DIRECT, 80, now), now + 1000, true))
    }

    @Test fun timeoutsAreSeparateAndBounded() {
        assertEquals(10_000L, ProviderRouteCore.CONNECT_MS)
        assertTrue(ProviderRouteCore.firstTokenMs(false) in 30_000L..60_000L)
        assertTrue(ProviderRouteCore.firstTokenMs(true) > ProviderRouteCore.firstTokenMs(false))
        // a dead first route costs ≤ 10 s before the second is tried (was: up to the 60 s chat deadline)
        assertTrue(ProviderRouteCore.CONNECT_MS + ProviderRouteCore.PROBE_MS < 15_000L)
        assertEquals(2 * ProviderRouteCore.CONNECT_MS + ProviderRouteCore.PROBE_MS + ProviderRouteCore.firstTokenMs(false), ProviderRouteCore.silentBudgetMs(false))
    }

    @Test fun failoverDecisions() {
        assertEquals(Fail.CONNECT, ProviderRouteCore.classify("连不上 token.sensenova.cn：直连不通"))
        assertEquals(Fail.CONNECT, ProviderRouteCore.classify("failed to connect to /127.0.0.1:7890"))
        assertEquals(Fail.FIRST_TOKEN, ProviderRouteCore.classify("60 秒没有任何回应"))
        assertEquals(Fail.RATE, ProviderRouteCore.classify("x", 429))
        assertEquals(Fail.AUTH, ProviderRouteCore.classify("x", 401))
        assertEquals(Fail.SERVER, ProviderRouteCore.classify("x", 503))
        assertEquals(Next.OTHER_ROUTE, ProviderRouteCore.next(Fail.CONNECT, routesLeft = 1, keysLeft = 4))
        assertEquals(Next.NEXT_PROVIDER, ProviderRouteCore.next(Fail.CONNECT, routesLeft = 0, keysLeft = 4))   // keys share the host
        assertEquals(Next.NEXT_KEY, ProviderRouteCore.next(Fail.RATE, 0, 2))
        assertEquals(Next.NEXT_PROVIDER, ProviderRouteCore.next(Fail.FIRST_TOKEN, 1, 0))
    }

    @Test fun statusLine() {
        assertEquals("正在连接 token.sensenova.cn（直连）", ProviderRouteCore.statusText(ProviderRouteCore.Phase.CONNECTING, "token.sensenova.cn", Route.DIRECT, 900))
        assertEquals("正在连接 api.openai.com（代理） 3 秒", ProviderRouteCore.statusText(ProviderRouteCore.Phase.CONNECTING, "api.openai.com", Route.PROXY, 3200))
        assertEquals("正在思考 12 秒", ProviderRouteCore.statusText(ProviderRouteCore.Phase.THINKING, "x", Route.DIRECT, 12_400))
        assertEquals("", ProviderRouteCore.statusText(ProviderRouteCore.Phase.IDLE, "x", null, 0))
        val t = ProviderRouteCore.bothFailedText("api.x.cn", "10 秒没连上", "10 秒没连上", true)
        assertTrue(t, t.contains("直连") && t.contains("代理") && t.contains("换下一个"))
    }

    /** the provider clients really use the routes (source wiring) */
    @Test fun wiring() {
        fun f(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()
        val oa = f("src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt")
        assertTrue(oa.contains("ProviderRoutes.plan(rHost, rPort)") && oa.contains("ProviderRoutes.proxyFor(route)") && oa.contains("acquiredAtNanos"))
        assertTrue(oa.contains("connectTimeout(com.openminis.app.plus.ProviderRouteCore.CONNECT_MS"))
        for (p in listOf("src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt", "src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt"))
            assertTrue(p, f(p).contains("proxySelector(com.openminis.app.plus.ProviderRoutes.selector)"))
        val vm = f("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        // [38.9.14.1 stall_watch] the 40 s stream-idle watchdog replaced the silent budget on the model stream
        assertTrue(vm.contains("ProviderRouteCore.silentBudgetMs(") || vm.contains("StallWatchCore.STREAM_IDLE_MS"))
        assertTrue(f("src/main/java/com/openminis/app/ui/chat/ChatIndicators.kt").contains("ProviderRoutes.status"))
    }
}

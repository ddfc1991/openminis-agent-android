package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11.3] 测试全部密钥 verdicts / summary, key-pool skipping of invalid keys, short Chinese error lines. */
class KeyCheckCoreTest {
    @Test fun verdicts() {
        assertEquals(KeyCheckCore.Verdict.BAD, KeyCheckCore.verdict(401))
        assertEquals(KeyCheckCore.Verdict.UNKNOWN, KeyCheckCore.verdict(403))   // quota / region on relays: not a dead key
        assertEquals(KeyCheckCore.Verdict.OK, KeyCheckCore.verdict(200))
        assertEquals(KeyCheckCore.Verdict.UNKNOWN, KeyCheckCore.verdict(null))
        assertEquals(KeyCheckCore.Verdict.UNKNOWN, KeyCheckCore.verdict(500))
    }

    @Test fun urlsAndHeaders() {
        assertEquals("https://h/v1/models", KeyCheckCore.modelsUrl("https://h/v1/"))
        assertEquals("https://h/v1/models", KeyCheckCore.modelsUrl("https://h/v1/models"))
        assertEquals("Bearer k", KeyCheckCore.headers(KeyCheckCore.authOf("openAI"), "k").toMap()["Authorization"])
        assertEquals("k", KeyCheckCore.headers(KeyCheckCore.authOf("anthropic"), "k").toMap()["x-api-key"])
        assertEquals("k", KeyCheckCore.headers(KeyCheckCore.authOf("gemini"), "k").toMap()["x-goog-api-key"])
    }

    @Test fun summaryLines() {
        val r = listOf(
            KeyCheckCore.Row("商汤", 0, 3, KeyCheckCore.Verdict.OK), KeyCheckCore.Row("商汤", 1, 3, KeyCheckCore.Verdict.BAD),
            KeyCheckCore.Row("商汤", 2, 3, KeyCheckCore.Verdict.UNKNOWN), KeyCheckCore.Row("AMD", 0, 2, KeyCheckCore.Verdict.OK),
            KeyCheckCore.Row("AMD", 1, 2, KeyCheckCore.Verdict.OK), KeyCheckCore.Row("X", 0, 1, KeyCheckCore.Verdict.BAD))
        assertEquals(listOf("商汤：3 个密钥，#2 无效，1 个没测出", "AMD：2 个全部能用", "X：密钥无效"), KeyCheckCore.summary(r))
    }

    @Test fun poolSkipsBadKeys() {
        val pool = listOf("a", "b", "c"); val bad = setOf("b")
        val picks = (0L until 6L).map { ProviderMergeCore.pickGood(pool, it) { k -> k in bad } }
        assertTrue(picks.none { it == "b" })
        assertEquals("a", ProviderMergeCore.pickGood(pool, 0) { true })   // all bad → whole pool, a fixed key still gets tried
        assertEquals(1, ProviderMergeCore.othersGood(pool, "a") { it in bad })
        assertNull(ProviderMergeCore.pickGood(emptyList(), 0) { false })
    }

    @Test fun chineseErrors() {
        assertEquals("商汤 的密钥无效，已换下一个", ProviderRouteCore.zhError("Invalid API key: xxx", "商汤", switched = true))
        assertTrue(ProviderRouteCore.zhError("unexpected end of stream on https://api.lixil.top/...", "AWS中转")!!.startsWith("AWS中转 中途断开"))
        assertTrue(ProviderRouteCore.zhError("HTTP 429 Too Many Requests", "A")!!.contains("限流"))
        assertNull(ProviderRouteCore.zhError("首字超时（没有任何回应）", "A"))
        assertNull(ProviderRouteCore.zhError("some other thing", "A"))
    }
}

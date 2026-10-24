package com.openminis.app.plus

import com.openminis.app.plus.ProviderHealthCore.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthCoreTest {
    private fun noEnglishReason(r: String) = assertTrue("reason must be Chinese: $r", r.any { it.code in 0x4E00..0x9FFF })

    @Test fun classifyCommonFailures() {
        val cases = mapOf(
            "Invalid API key" to Kind.AUTH,
            "[401] HTTP 401：Incorrect API key provided" to Kind.AUTH,
            "Provider error: [402] HTTP 402：Insufficient Balance" to Kind.QUOTA,
            "Rate limited: 请求太频繁或额度用完（429）" to Kind.QUOTA,
            "java.net.SocketTimeoutException: timeout" to Kind.NETWORK,
            "Unable to resolve host \"api.x.ai\"" to Kind.NETWORK,
            "Provider error: [404] model_not_found" to Kind.MODEL,
            "Transient error: [503] HTTP 503" to Kind.SERVER,
        )
        for ((m, k) in cases) {
            val (kind, reason) = ProviderHealthCore.classify(m)
            assertEquals(m, k, kind); noEnglishReason(reason)
        }
    }

    @Test fun keepsTheProvidersOwnChineseSentence() {
        val m = "Invalid API key: 你的账号有 Copilot（免费版），但 GitHub 不肯用这次的登录换 Copilot 令牌：请点「重新登录」重新授权（HTTP 403）"
        val (kind, reason) = ProviderHealthCore.classify(m, oauth = true)
        assertEquals(Kind.LOGIN, kind)
        assertTrue(reason.startsWith("你的账号有 Copilot"))
        assertFalse(reason.contains("Invalid API key"))
    }

    @Test fun oauthAuthFailureMeansRelogin() {
        assertEquals(Kind.LOGIN, ProviderHealthCore.classify("Invalid API key", oauth = true).first)
        val plan = ProviderHealthCore.fixPlan(Kind.LOGIN, oauth = true, hasVaultKey = false, hasKeyRecipe = false)
        assertEquals(listOf(ProviderHealthCore.Step.RETEST, ProviderHealthCore.Step.REFRESH_LOGIN, ProviderHealthCore.Step.RELOGIN), plan)
    }

    @Test fun keyProviderPlan() {
        val p = ProviderHealthCore.fixPlan(Kind.AUTH, oauth = false, hasVaultKey = true, hasKeyRecipe = true)
        assertEquals(listOf(ProviderHealthCore.Step.RETEST, ProviderHealthCore.Step.VAULT_KEY, ProviderHealthCore.Step.FETCH_KEY), p)
        assertEquals(listOf(ProviderHealthCore.Step.RETEST, ProviderHealthCore.Step.REFRESH_MODELS),
            ProviderHealthCore.fixPlan(Kind.MODEL, false, true, true))
        assertEquals(listOf(ProviderHealthCore.Step.RETEST), ProviderHealthCore.fixPlan(Kind.NETWORK, false, true, true))
    }

    @Test fun staleness() {
        val now = 10_000_000_000L
        assertTrue(ProviderHealthCore.stale(null, now))
        val ok = ProviderHealthCore.Check("a", true, Kind.OK, "能用", at = now - 3600_000L)
        assertFalse(ProviderHealthCore.stale(ok, now))
        assertTrue(ProviderHealthCore.stale(ok.copy(at = now - 7 * 3600_000L), now))
        val bad = ok.copy(ok = false, kind = Kind.AUTH)
        assertTrue(ProviderHealthCore.stale(bad, now))                       // 1 h old failure → re-check
        assertFalse(ProviderHealthCore.stale(bad.copy(at = now - 60_000L), now))
        assertTrue(ProviderHealthCore.stale(ok.copy(at = now + 3600_000L), now))   // clock moved back
    }

    @Test fun jsonRoundTrip() {
        val c = ProviderHealthCore.Check("id1", false, Kind.QUOTA, "额度用完", "HTTP 402", "deepseek-chat", 812, 123)
        assertEquals(c, ProviderHealthCore.fromJson(JSONObject(ProviderHealthCore.toJson(c).toString())))
    }

    @Test fun summaryAndAgo() {
        assertEquals("能用 9 · 不能用 2", ProviderHealthCore.summary(9, 2, 0))
        assertEquals("能用 1 · 不能用 0 · 检测中 3", ProviderHealthCore.summary(1, 0, 3))
        assertEquals("刚刚检测", ProviderHealthCore.ago(1000, 2000))
        assertEquals("12 分钟前检测", ProviderHealthCore.ago(0 + 1, 12 * 60_000L + 1))
        assertEquals("还没检测", ProviderHealthCore.ago(0, 5))
    }

    @Test fun testModelPick() {
        assertEquals("deepseek-chat", ProviderHealthCore.pickTestModel(listOf("text-embedding-3", "deepseek-chat", "deepseek-reasoner")))
        assertEquals("gpt-4o-mini", ProviderHealthCore.pickTestModel(listOf("gpt-4o", "gpt-4o-mini")))
        assertEquals(null, ProviderHealthCore.pickTestModel(emptyList()))
    }
}

package com.openminis.app.auth

import com.openminis.app.provider.openai.CompatQuirks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Mines-38.9.11 i32] user 2026-10-20 「github那个自动登录调用的还是用不了」 (GitHub Copilot via OAuth). Real account facts
 * (api.github.com/copilot_internal/user, 2026-10-20): access_type_sku free_limited_copilot, copilot_plan individual,
 * endpoints.api https://api.individual.githubcopilot.com; a token the exchange will not take gets a plain-text 403.
 */
class CopilotFixTest {
    private val userJson = JSONObject("""{"login":"ddfc1991","access_type_sku":"free_limited_copilot","chat_enabled":true,
        "copilot_plan":"individual","endpoints":{"api":"https://api.individual.githubcopilot.com","proxy":"https://proxy.individual.githubcopilot.com"}}""")

    @Test fun chatHostFromExchange() {
        val j = JSONObject("""{"token":"tid=1;exp=2;proxy-ep=proxy.individual.githubcopilot.com;x=1","expires_at":2000,"refresh_in":1500,
            "endpoints":{"api":"https://api.individual.githubcopilot.com/","proxy":"https://proxy.individual.githubcopilot.com"}}""")
        assertEquals("https://api.individual.githubcopilot.com", CopilotDeviceFlow.parseApiEndpoint(j))
    }

    @Test fun chatHostFromProxyEpWhenNoEndpoints() {
        val j = JSONObject("""{"token":"tid=1;exp=2;sku=free_limited_copilot;proxy-ep=proxy.business.githubcopilot.com;8kp=1","refresh_in":1500}""")
        assertEquals("https://api.business.githubcopilot.com", CopilotDeviceFlow.parseApiEndpoint(j))
    }

    @Test fun neverSendsTheTokenToAnotherHost() {
        assertNull(CopilotDeviceFlow.parseApiEndpoint(JSONObject("""{"token":"x","endpoints":{"api":"https://evil.example.com"}}""")))
        assertNull(CopilotDeviceFlow.parseApiEndpoint(JSONObject("""{"token":"x","endpoints":{"api":"http://api.githubcopilot.com"}}""")))
        assertNull(CopilotDeviceFlow.parseApiEndpoint(JSONObject("""{"token":"x","endpoints":{"api":"https://githubcopilot.com.evil.io"}}""")))
        assertNull(CopilotDeviceFlow.parseApiEndpoint(JSONObject("""{"token":"tid=1;proxy-ep=proxy.evil.io"}""")))
        assertNull(CopilotDeviceFlow.parseApiEndpoint(JSONObject("""{"token":"tid=1"}""")))
    }

    @Test fun sessionDeadlineIsOnThePhoneClock() {
        // phone clock 2 hours behind GitHub: expires_at would look valid for 2 h after the token died
        val nowPhone = 1_000_000L
        val serverExpires = nowPhone + 7200 + 1800
        val s = CopilotDeviceFlow.parseSessionTokenLocal(JSONObject("""{"token":"t","expires_at":$serverExpires,"refresh_in":1500}"""), nowPhone)!!
        assertEquals(nowPhone + 1500, s.expiresAtEpochSeconds)
        assertTrue(s.needsRefresh(nowPhone + 1450))
        // only expires_at, phone clock ahead (deadline already "past") → short life, not "refresh forever"
        val a = CopilotDeviceFlow.parseSessionTokenLocal(JSONObject("""{"token":"t","expires_at":${nowPhone - 5000}}"""), nowPhone)!!
        assertEquals(nowPhone + 120, a.expiresAtEpochSeconds)
        // only expires_at, far in the future → capped at 30 min
        val b = CopilotDeviceFlow.parseSessionTokenLocal(JSONObject("""{"token":"t","expires_at":${nowPhone + 99999}}"""), nowPhone)!!
        assertEquals(nowPhone + 1800, b.expiresAtEpochSeconds)
        assertNull(CopilotDeviceFlow.parseSessionTokenLocal(JSONObject("""{"token":"t"}"""), nowPhone))
    }

    @Test fun responsesOnlyModelsAreNotOffered() {
        val p = CopilotDeviceFlow.parseModelsDetailed(JSONObject("""{"data":[
            {"id":"gpt-4.1","capabilities":{"type":"chat"},"supported_endpoints":["/chat/completions","/responses"]},
            {"id":"gpt-5-codex","capabilities":{"type":"chat"},"supported_endpoints":["/responses"]},
            {"id":"claude-haiku-4.5","capabilities":{"type":"chat"}}
        ]}"""))
        assertEquals(listOf("gpt-4.1", "claude-haiku-4.5"), p.models.map { it.id })
        assertTrue(p.droppedByType.any { it.startsWith("gpt-5-codex") })
    }

    @Test fun refusedTokenOnAnAccountWithCopilotIsNotNoCopilot() {
        val body = "403 Forbidden. For more on scraping GitHub and how it may affect your rights, please review our Terms of Service (https://docs.github.com/en/site-policy/github-terms/github-terms-of-service)."
        val zh = CompatQuirks.copilotExchangeError(403, body, userJson)
        assertTrue(zh, zh.contains("免费版"))
        assertTrue(zh, zh.contains("重新登录"))
        assertFalse(zh, zh.contains("没有开通"))
        assertFalse("no raw English page text", zh.contains("scraping"))
        // without the diagnosis the plain-text 403 still says "token refused → re-login", not "no Copilot"
        val z2 = CompatQuirks.copilotExchangeError(403, body, null)
        assertTrue(z2, z2.contains("重新登录")); assertFalse(z2.contains("没有开通"))
        // revoked token: copilot_internal/user also 401
        assertTrue(CompatQuirks.copilotExchangeError(404, "", JSONObject().put("_status", 401)).contains("失效"))
        // the old meaning stays for a real "no Copilot"
        assertTrue(CompatQuirks.copilotExchangeError(404, "", null).contains("没有开通 Copilot"))
    }

    @Test fun chatErrorsInChinese() {
        assertTrue(CopilotDeviceFlow.chatErrorZh(400, """{"error":{"message":"The requested model is not supported.","code":"model_not_supported"}}""").contains("免费版"))
        assertTrue(CopilotDeviceFlow.chatErrorZh(400, """{"error":{"message":"model gpt-5-codex does not support Chat Completions API","code":"unsupported_api_for_model"}}""").contains("换一个模型"))
        assertTrue(CopilotDeviceFlow.chatErrorZh(402, """{"error":{"message":"You have exceeded your monthly quota"}}""").contains("额度"))
        assertTrue(CopilotDeviceFlow.chatErrorZh(401, "unauthorized: token expired").contains("重新登录"))
        val t = CompatQuirks.errorText(400, """{"error":{"message":"The requested model is not supported.","code":"model_not_supported"}}""", "https://api.individual.githubcopilot.com")!!
        assertTrue(t, t.startsWith("你的 Copilot 套餐"))
        assertTrue(CompatQuirks.isCopilot("https://api.individual.githubcopilot.com"))
        assertFalse(CompatQuirks.isCopilot("https://models.github.ai/inference"))
    }
}

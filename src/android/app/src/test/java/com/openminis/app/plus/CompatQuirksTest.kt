package com.openminis.app.plus

import com.openminis.app.provider.openai.CompatQuirks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.9] Cloudflare / GitHub Models / AMD request + error quirks. */
class CompatQuirksTest {
    private val cf = "https://api.cloudflare.com/client/v4/accounts/abc/ai/v1"
    private val gh = CompatQuirks.GITHUB_MODELS_BASE

    @Test fun detects() {
        assertTrue(CompatQuirks.isCloudflare(cf)); assertFalse(CompatQuirks.isCloudflare("https://api.cloudflare.com/client/v4"))
        assertTrue(CompatQuirks.isGitHubModels(gh)); assertTrue(CompatQuirks.isAmd("https://developer.amd.com.cn/api/v1"))
    }
    @Test fun cloudflareSanitize() {
        val b = JSONObject().put("model", "meta/llama-3.1-8b-instruct").put("max_completion_tokens", 100)
            .put("stream_options", JSONObject()).put("parallel_tool_calls", true).put("thinking", JSONObject())
        CompatQuirks.sanitize(b, cf)
        assertEquals("@cf/meta/llama-3.1-8b-instruct", b.getString("model"))
        assertEquals(100, b.getInt("max_tokens")); assertFalse(b.has("max_completion_tokens"))
        assertFalse(b.has("stream_options")); assertFalse(b.has("parallel_tool_calls")); assertFalse(b.has("thinking"))
        assertEquals("@hf/x", CompatQuirks.cfModelId("@hf/x"))
    }
    @Test fun otherHostsUntouched() {
        val b = JSONObject().put("model", "gpt-4o").put("stream_options", JSONObject())
        assertTrue(CompatQuirks.sanitize(b, "https://api.openai.com/v1").isEmpty()); assertTrue(b.has("stream_options"))
    }
    @Test fun errorsKeepServerWords() {
        val t = CompatQuirks.errorText(502, """{"errors":[{"message":"upstream blew up"}]}""", cf)!!
        assertTrue(t.startsWith("Cloudflare Workers AI 出错")); assertTrue(t.endsWith("upstream blew up"))
        assertTrue(CompatQuirks.errorText(401, """{"error":{"message":"bad"}}""", gh)!!.contains("Models"))
        assertTrue(CompatQuirks.errorText(429, "", gh)!!.contains("额度"))
        assertTrue(CompatQuirks.errorText(404, """{"error":{"code":"unknown_model","message":"Unknown model: x"}}""", gh)!!.contains("openai/gpt-4.1"))
        assertEquals("上游网关出错（502）：<b>oops</b>".replace("<b>", "").replace("</b>", ""), CompatQuirks.errorText(502, "<b>oops</b>", null))
    }
    @Test fun emptySsePayloads() {
        assertTrue(CompatQuirks.isEmptyPayload(""))
        assertTrue(CompatQuirks.isEmptyPayload("{}"))
        assertTrue(CompatQuirks.isEmptyPayload("""{"choices":[]}"""))
        assertTrue(CompatQuirks.isEmptyPayload("""{"choices":[{"delta":{}}]}"""))
        assertFalse(CompatQuirks.isEmptyPayload("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertFalse(CompatQuirks.isEmptyPayload("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertFalse(CompatQuirks.isEmptyPayload("""{"choices":[],"usage":{"total_tokens":3}}"""))
    }
    @Test fun githubCatalog() {
        val l = CompatQuirks.parseGitHubCatalog("""[{"id":"openai/gpt-4.1","name":"OpenAI GPT-4.1","supported_output_modalities":["text"]},
            {"id":"openai/text-embedding-3-small","name":"emb","supported_output_modalities":["embeddings"]},{"id":"meta/llama"}]""")
        assertEquals(listOf("openai/gpt-4.1" to "OpenAI GPT-4.1", "meta/llama" to "meta/llama"), l)
    }
    @Test fun copilotExchange() {
        assertTrue(CompatQuirks.copilotExchangeError(404, "").contains("没有开通 Copilot"))
        assertTrue(CompatQuirks.copilotExchangeError(401, """{"message":"Bad credentials"}""").contains("Bad credentials"))
        assertTrue(CompatQuirks.copilotExchangeError(403, """{"message":"Copilot disabled by organization policy"}""").contains("组织"))
    }
}

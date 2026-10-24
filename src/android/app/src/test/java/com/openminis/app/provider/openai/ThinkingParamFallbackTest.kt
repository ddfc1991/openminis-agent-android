package com.openminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.4] issue #2: `400 "thinking" is not supported … Use "reasoning_effort"` → drop thinking, retry once, remember. */
class ThinkingParamFallbackTest {
    private val amd400 = """{"error":{"message":"\"thinking\" is not supported on /v1/chat/completions and was not applied. Use \"reasoning_effort\" (or \"reasoning.effort\") to control thinking.","type":"invalid_request_error","param":"thinking","code":"unsupported_parameter"}}"""

    @Test fun detectsTheIssueBody() {
        assertTrue(ThinkingParamFallback.isUnsupported(400, amd400))
        assertTrue(ThinkingParamFallback.isUnsupported(400, """{"error":"Unrecognized key(s) in object: 'thinking'"}"""))
        assertTrue(ThinkingParamFallback.isUnsupported(422, """{"detail":"thinking: Extra inputs are not permitted"}"""))
    }

    @Test fun ignoresOtherErrors() {
        assertFalse(ThinkingParamFallback.isUnsupported(401, amd400))
        assertFalse(ThinkingParamFallback.isUnsupported(400, """{"error":{"message":"context_length_exceeded"}}"""))
        assertFalse(ThinkingParamFallback.isUnsupported(429, "rate limited"))
    }

    @Test fun retryOnlyWhenTheFieldWasSent() {
        val withThinking = JSONObject().put("model", "MiMo-V2.6-Flash").put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "medium")
        assertTrue(ThinkingParamFallback.shouldRetry(400, amd400, withThinking))
        assertTrue(ThinkingParamFallback.strip(withThinking))
        assertFalse(withThinking.has("thinking"))
        assertEquals("medium", withThinking.getString("reasoning_effort"))   // effort kept
        assertFalse(ThinkingParamFallback.shouldRetry(400, amd400, withThinking))   // already stripped: no loop
    }

    @Test fun remembersPerEndpoint() {
        val saved = mutableListOf<Set<String>>()
        ThinkingParamFallback.store = { saved += it }
        val base = "https://developer.amd.com.cn/radeon/api/v1/"
        assertFalse(ThinkingParamFallback.known("https://other.example/v1"))
        ThinkingParamFallback.remember(base)
        assertTrue(ThinkingParamFallback.known("https://DEVELOPER.amd.com.cn/radeon/api/v1"))
        assertFalse(ThinkingParamFallback.known("https://other.example/v1"))
        assertTrue(saved.last().contains("https://developer.amd.com.cn/radeon/api/v1"))
        ThinkingParamFallback.store = null
    }
}

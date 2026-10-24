package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHintsCoreTest {
    private val C = WebHintsCore

    @Test fun chromeVersionAndBrands() {
        val ua = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals("141.0.7390.122", C.chromeFullVersion(ua))
        assertNull(C.chromeFullVersion("curl/8")); assertNull(C.chromeFullVersion(null))
        val b = C.brands("141.0.7390.122")
        assertEquals(3, b.size); assertEquals("Google Chrome", b[1].first); assertEquals("141", b[1].second)
        assertFalse(b.any { it.first.contains("WebView") })
    }

    @Test fun cloudflareChallengeIsNotAnError() {
        assertTrue(C.isChallenge(403, mapOf("cf-mitigated" to "challenge")))
        assertTrue(C.isChallenge(200, mapOf("CF-Mitigated" to "challenge")))
        assertTrue(C.isChallenge(403, mapOf("Server" to "cloudflare", "CF-RAY" to "8a-SIN")))
        assertTrue(C.isChallenge(503, mapOf("cf-ray" to "1")))
        assertTrue(C.isChallenge(0, null, "Just a moment..."))
        assertTrue(C.isChallenge(200, emptyMap(), "正在執行安全驗證"))
        assertTrue(C.isChallenge(429, mapOf("x-datadome" to "1")))
    }

    @Test fun realErrorsStayErrors() {
        assertFalse(C.isChallenge(404, mapOf("server" to "cloudflare")))
        assertFalse(C.isChallenge(403, mapOf("server" to "nginx")))
        assertFalse(C.isChallenge(500, mapOf("cf-ray" to "1")))
        assertFalse(C.isChallenge(403, null, "Forbidden"))
        assertTrue(C.CHALLENGE_NOTE.contains("不是错误"))
    }
}

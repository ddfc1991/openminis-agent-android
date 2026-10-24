package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMailCoreTest {
    @Test fun netReasonsArePlainChinese() {
        assertTrue(AiMailCore.netReason("Mail.tm", "UnknownHostException").contains("DNS"))
        assertTrue(AiMailCore.netReason("Mail.tm", "SocketTimeoutException").contains("超时"))
        assertTrue(AiMailCore.netReason("AgentMail", "ConnectException", "Failed to connect to /127.0.0.1:7899").contains("连不上"))
        assertTrue(AiMailCore.netReason("Mail.tm", "SSLHandshakeException").contains("加密"))
        assertTrue(AiMailCore.netReason("X", "WeirdException").contains("WeirdException"))
    }

    @Test fun mailTmHttp() {
        assertNull(AiMailCore.mailTmHttp(AiMailCore.Step.ACCOUNT, 201))
        assertTrue(AiMailCore.mailTmHttp(AiMailCore.Step.ACCOUNT, 429)!!.contains("频繁"))
        assertEquals("这个地址已被占用", AiMailCore.mailTmHttp(AiMailCore.Step.ACCOUNT, 422, "{\"detail\":\"address: This value is already used.\"}"))
        assertTrue(AiMailCore.mailTmHttp(AiMailCore.Step.TOKEN, 401)!!.contains("登录失败"))
        assertTrue(AiMailCore.mailTmHttp(AiMailCore.Step.DOMAINS, 503)!!.contains("服务器出错"))
    }

    @Test fun domainPick() {
        val j = """{"hydra:member":[{"domain":"priv.tm","isActive":true,"isPrivate":true},{"domain":"off.tm","isActive":false},{"domain":"web.tm","isActive":true,"isPrivate":false}]}"""
        assertEquals("web.tm", AiMailCore.mailTmDomain(j))
        assertEquals("a.tm", AiMailCore.mailTmDomain("""{"member":[{"domain":"a.tm"}]}"""))
        assertNull(AiMailCore.mailTmDomain("""{"hydra:member":[]}"""))
        assertNull(AiMailCore.mailTmDomain("not json"))
        // Accept: application/json → plain array (real api.mail.tm reply, 2026-10-20)
        assertEquals("maxxspace.com", AiMailCore.mailTmDomain("""[{"id":"6aa6","domain":"maxxspace.com","isActive":true,"isPrivate":false}]"""))
        assertEquals("b.tm", AiMailCore.mailTmDomain(""" [{"domain":"p.tm","isPrivate":true},{"domain":"b.tm","isActive":true}]"""))
        assertNull(AiMailCore.mailTmDomain("[]"))
    }

    @Test fun userName() {
        val u = AiMailCore.userName(1_760_000_000_000L, -5)
        assertTrue(u.startsWith("mines")); assertTrue(u.length in 3..64); assertTrue(u == u.lowercase())
        assertTrue(Regex("[a-z0-9]+").matches(u))
    }

    @Test fun agmailMarkedDown() {
        val p = CodeSourcesCore.PROVIDERS.first { it.id == "agmail" }
        assertTrue(p.down)
        assertTrue(CodeSourcesCore.PROVIDERS.filter { it.id != "agmail" }.none { it.down })
    }
}

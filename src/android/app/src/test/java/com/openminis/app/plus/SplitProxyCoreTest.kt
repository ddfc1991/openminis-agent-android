package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.6 i12] authenticated upstream proxy: Basic header, injection into a forwarded head, url parsing. */
class SplitProxyCoreTest {
    @Test fun basicIsRfc7617() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", SplitProxyCore.basic("Aladdin", "open sesame"))
        assertEquals("", SplitProxyCore.authLine("", "x"))
        assertTrue(SplitProxyCore.authLine("u", "p").startsWith("Proxy-Authorization: Basic ") && SplitProxyCore.authLine("u", "p").endsWith("\r\n"))
    }
    @Test fun injectsAndReplacesClientHeader() {
        val head = "GET http://a.test/ HTTP/1.1\r\nHost: a.test\r\nProxy-Authorization: Basic old\r\n\r\n"
        val out = SplitProxyCore.withAuth(head, SplitProxyCore.authLine("u", "p"))
        assertTrue(out.startsWith("GET http://a.test/ HTTP/1.1\r\nProxy-Authorization: Basic dTpw\r\n"))
        assertFalse(out.contains("Basic old")); assertTrue(out.endsWith("\r\n\r\n"))
        assertEquals(head, SplitProxyCore.withAuth(head, ""))
    }
    @Test fun parsesProxyUrls() {
        val p = SplitProxyCore.parse("http://us%40er:p%3Ass@203.0.113.7:24445")!!
        assertEquals("http", p.type); assertEquals("203.0.113.7", p.host); assertEquals(24445, p.port)
        assertEquals("us@er", p.user); assertEquals("p:ss", p.pass)
        assertFalse(p.toString().contains("p:ss"))
        assertEquals("socks5", SplitProxyCore.parse("socks5://h.test:1080")!!.type)
        assertEquals("", SplitProxyCore.parse("h.test:8080")!!.user)
        assertNull(SplitProxyCore.parse("h.test")); assertNull(SplitProxyCore.parse(""))
    }

    @Test fun http400IsReachableButProtocolMismatch() {
        val ok = SplitProxyCore.probeOutcome(204)
        assertTrue(ok.reachable); assertTrue(ok.protocolOk); assertEquals(204, ok.statusCode)
        val badProtocol = SplitProxyCore.probeOutcome(400)
        assertTrue(badProtocol.reachable); assertFalse(badProtocol.protocolOk)
        assertEquals(400, badProtocol.statusCode)
        assertTrue(badProtocol.detail!!.contains("已连通"))
        assertTrue(badProtocol.detail.contains("HTTP 400"))
        // Reachability is diagnostic only: non-204 HTTP responses must not be healthy/selectable.
        assertFalse(badProtocol.protocolOk)
        assertTrue(SplitProxyCore.probeOutcome(503).reachable)
        assertFalse(SplitProxyCore.probeOutcome(503).protocolOk)
        assertTrue(SplitProxyCore.probeReason(null, 400).contains("已连通"))
    }

    @Test fun diagnosticEndpointNeverShowsUserInfo() {
        assertEquals("http://proxy.example:8080", SplitProxyCore.endpoint("http", "alice:topsecret@proxy.example", 8080))
        assertFalse(SplitProxyCore.endpoint("socks5", "user:pass@2001:db8::1", 1080).contains("pass"))
        assertEquals("socks5://[2001:db8::1]:1080", SplitProxyCore.endpoint("socks5", "user:pass@2001:db8::1", 1080))
        val malformed = SplitProxyCore.endpoint("http", "proxy.example/path?token=not-for-logs", 8080)
        assertEquals("http://proxy.example:8080", malformed)
        assertFalse(malformed.contains("token"))
    }

    // [Mines-38.9.6 i14] short growing pause after a failure, then retried; Google never direct
    @Test fun backoffAndGoogle() {
        assertEquals(0L, SplitProxyCore.backoffMs(0)); assertEquals(5_000L, SplitProxyCore.backoffMs(1))
        assertEquals(10_000L, SplitProxyCore.backoffMs(2)); assertEquals(60_000L, SplitProxyCore.backoffMs(9))
        assertEquals(false, SplitProxyCore.usable(1, 1_000L, 3_000L)); assertEquals(true, SplitProxyCore.usable(1, 1_000L, 6_001L))
        assertEquals(true, SplitProxyCore.isGoogle("mail.google.com")); assertEquals(true, SplitProxyCore.isGoogle("accounts.google.com"))
        assertEquals(true, SplitProxyCore.isGoogle("gmail.com")); assertEquals(false, SplitProxyCore.isGoogle("notgoogle.com"))
        assertEquals(true, SplitProxyCore.downResponse().startsWith("HTTP/1.1 503"))
    }
    @Test fun realCheckParsingAndDedupe() {
        assertEquals(204, SplitProxyCore.statusCode("HTTP/1.1 204 No Content"))
        assertEquals(302, SplitProxyCore.statusCode("HTTP/2 302 Found"))
        assertEquals(null, SplitProxyCore.statusCode("SSH-2.0-OpenSSH"))
        assertEquals(SplitProxyCore.sameProxyKey("localhost", 7890), SplitProxyCore.sameProxyKey("127.0.0.1", 7890))
        assertEquals(SplitProxyCore.sameProxyKey("LOCALHOST ", 7890), SplitProxyCore.sameProxyKey("::1", 7890))
        org.junit.Assert.assertNotEquals(SplitProxyCore.sameProxyKey("127.0.0.1", 7890), SplitProxyCore.sameProxyKey("127.0.0.1", 7898))
        org.junit.Assert.assertTrue(SplitProxyCore.probeReason("SSLHandshakeException: connection closed", 0).startsWith("加密握手失败"))
        org.junit.Assert.assertTrue(SplitProxyCore.probeReason(null, 302).startsWith("被重定向"))
        org.junit.Assert.assertTrue(SplitProxyCore.probeReason("IOException: closed before a reply", 0).startsWith("连上后被断开"))
        org.junit.Assert.assertTrue(SplitProxyCore.probeReason("ConnectException: failed to connect (Connection refused)", 0).startsWith("代理没在运行"))
    }
}

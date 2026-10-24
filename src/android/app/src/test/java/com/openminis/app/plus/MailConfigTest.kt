package com.openminis.app.plus

import com.openminis.app.plus.MailConfig.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.1] 授权码 clean-up, per-provider IMAP config and server-error mapping. */
class MailConfigTest {
    @Test fun normalize() {
        assertEquals("abcdefghijklmnop", MailConfig.normalizeCode("abcd efgh ijkl mnop"))
        assertEquals("abcdefghijklmnop", MailConfig.normalizeCode(" abcd\u00A0efgh\nijkl\u200Bmnop\t"))
        assertEquals("ABC123XYZ", MailConfig.normalizeCode("「ABC123XYZ」"))
        assertEquals("box@163.com", MailConfig.normalizeAddress(" Box@163.COM\n"))
    }
    @Test fun providers() {
        assertEquals("imap.gmail.com", MailConfig.of("a@gmail.com")!!.host)
        assertTrue(MailConfig.of("a@163.com")!!.needsId); assertTrue(MailConfig.of("a@126.com")!!.needsId); assertTrue(MailConfig.of("a@yeah.net")!!.needsId)
        assertEquals("imap.qq.com", MailConfig.of("a@foxmail.com")!!.host); assertEquals(993, MailConfig.of("a@qq.com")!!.port)
        assertTrue(MailConfig.of("a@outlook.com")!!.oauthOnly); assertTrue(MailConfig.of("a@hotmail.com")!!.oauthOnly)
        assertNull(MailConfig.of("a@unknown-domain.org"))
        assertEquals("应用专用密码", MailConfig.of("a@gmail.com")!!.codeName)
    }
    @Test fun errors() {
        val g = MailConfig.of("a@gmail.com"); val w = MailConfig.of("a@163.com")
        assertEquals(Why.ADDRESS_OR_CODE, MailConfig.classify("[AUTHENTICATIONFAILED] Invalid credentials (Failure)", g))
        assertEquals(Why.APP_PASSWORD, MailConfig.classify("Application-specific password required", g))
        assertEquals(Why.UNSAFE_LOGIN, MailConfig.classify("SELECT Unsafe Login. Please contact kefu@188.com for help", w))
        assertEquals(Why.ADDRESS_OR_CODE, MailConfig.classify("NO LOGIN Login error password error", w))
        assertEquals(Why.IMAP_OFF, MailConfig.classify("NO [ALERT] IMAP access is disabled for your domain", g))
        assertEquals(Why.OAUTH_ONLY, MailConfig.classify("anything", MailConfig.of("a@outlook.com")))
        assertEquals(Why.NET, MailConfig.classify("连不上 imap.163.com（SocketTimeoutException）", w))
        assertTrue(MailConfig.explain("Invalid credentials", g, "a@gmail.com").startsWith("地址和应用专用密码对不上"))
    }
}

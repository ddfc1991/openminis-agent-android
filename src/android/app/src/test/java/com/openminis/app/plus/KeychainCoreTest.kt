package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9] 账号关联: identity grouping, masking, TOTP (RFC 6238 vectors), otpauth parsing, new-device alert detection. */
class KeychainCoreTest {
    @Test fun identities() {
        assertEquals(KeychainCore.IdKind.PHONE, KeychainCore.identity("13800138000").kind)
        assertEquals(KeychainCore.identity("+86 138 0013 8000").key, KeychainCore.identity("13800138000").key)
        assertEquals("Gmail", KeychainCore.identity("alex.demo@gmail.com").label)
        assertEquals("163 邮箱", KeychainCore.identity("yxy201711@163.com").label)
        assertEquals(KeychainCore.IdKind.OTHER, KeychainCore.identity("").kind)
        assertEquals("138****8000", KeychainCore.maskPhone("13800138000"))
        assertEquals("+86 138****8000", KeychainCore.maskPhone("+8613800138000"))
    }

    @Test fun totpRfc6238() {
        val t = KeychainCore.Totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", digits = 8)
        assertEquals("94287082", KeychainCore.totpCode(t, 59_000L))
        assertEquals("07081804", KeychainCore.totpCode(t, 1111111109_000L))
        assertEquals("14050471", KeychainCore.totpCode(t, 1111111111_000L))
        assertEquals(1, KeychainCore.secondsLeft(KeychainCore.Totp("JBSWY3DPEHPK3PXP"), 59_000L))
    }

    @Test fun otpauth() {
        val t = KeychainCore.parseTotp("otpauth://totp/GitHub:ddfc?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6")
        assertNotNull(t); assertEquals("GitHub", t!!.issuer)
        assertNotNull(KeychainCore.parseTotp("jbsw y3dp ehpk 3pxp"))
        assertNull(KeychainCore.parseTotp("hello"))
    }

    @Test fun alerts() {
        val a = KeychainCore.detectAlert("OKX", "okx.com", "a@b.com", "OKX 新设备登录提醒", "您的账户在新设备登录。\n设备：Windows · Chrome\n位置：新加坡", 0, "邮件")
        assertNotNull(a); assertEquals("Windows · Chrome", a!!.device); assertEquals("新加坡", a.place)
        val g = KeychainCore.detectAlert("Google", "google.com", "a", "Security alert", "New sign-in on Windows device. We noticed a new sign-in from Singapore.", 0, "邮件")
        assertEquals("Windows", g!!.device); assertEquals("Singapore", g.place)
        assertNull(KeychainCore.detectAlert("GitHub", "github.com", "a", "Your verification code", "验证码 123456", 0, "邮件"))
        assertNull(KeychainCore.detectAlert("X", "x.com", "a", "Weekly digest", "hello", 0, "邮件"))
        assertTrue(KeychainCore.securityUrl("github.com").contains("settings/security"))
        assertTrue(KeychainCore.guideFor("okx.com").any { it.contains("提币") })
    }
}

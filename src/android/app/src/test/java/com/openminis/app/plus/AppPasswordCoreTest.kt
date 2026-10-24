package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPasswordCoreTest {
    private val C = AppPasswordCore

    @Test fun parseAppPassword() {
        assertEquals("abcdefghijklmnop", C.parse("abcd efgh ijkl mnop"))
        assertEquals("abcdefghijklmnop", C.parse("你的应用专用密码\nABCD\u00A0EFGH ijkl\u200Bmnop\n"))
        assertEquals("abcdefghijklmnop", C.parse("abcdefghijklmnop"))
        assertNull(C.parse("abcd efgh ijkl")); assertNull(C.parse(null))
        assertNull(C.parse("xabcdefghijklmnopq"))
    }

    @Test fun stepsAreChineseAndCfFree() {
        assertEquals(5, C.STEPS.size)
        assertEquals("第 1 步 / 共 5 步", C.progress(0))
        assertTrue(C.STEPS.none { it.body.contains("API key", true) })
        assertTrue(C.STEPS.last().body.contains("不需要 Cloudflare 账号"))
        assertTrue(C.APP_PASSWORDS_URL.startsWith("https://myaccount.google.com/"))
    }

    @Test fun gmailAndTestLine() {
        assertTrue(C.isGmail(" Me@Gmail.com ")); assertTrue(C.isGmail("a@googlemail.com"))
        assertFalse(C.isGmail("a@163.com")); assertFalse(C.isGmail("a b@gmail.com"))
        assertTrue(C.testLine(null, null, null).contains("还没有新的验证码"))
        assertEquals("收件箱能读了：最近一封验证码 123456（Cloudflare，3 分钟前）", C.testLine("123456", "Cloudflare", 3))
        assertEquals("收件箱能读了：最近一封验证码 1（X）", C.testLine("1", "X", null))
        assertEquals("收件箱能读了：最近一封验证码 1", C.testLine("1", "", 2))
    }
}

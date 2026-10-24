package com.openminis.app.plus

import com.openminis.app.browser.BrowserLogMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11.2] passwords / tokens / codes never reach the browser's log lines. */
class BrowserLogMaskTest {
    private fun hidden(line: String, vararg secrets: String) {
        val m = BrowserLogMask.mask(line)
        for (s in secrets) assertFalse("leaked $s in $m", m.contains(s))
    }

    @Test fun queryAndFragmentSecrets() {
        hidden("https://x.cn/cb?code=8f3k2LQ&state=abc", "8f3k2LQ")
        hidden("https://x.cn/#access_token=eyJhbGciOi.zzz&token_type=bearer", "eyJhbGciOi.zzz")
        hidden("https://x.cn/login?user=a&password=hunter22", "hunter22")
        hidden("https://api.x.cn/v1?api_key=sk-12345678&q=1", "sk-12345678")
        hidden("https://x.cn/?sessionid=S3SS10N;sig=AbCdEf", "S3SS10N", "AbCdEf")
        val m = BrowserLogMask.mask("https://x.cn/search?q=weather&page=2")
        assertEquals("https://x.cn/search?q=weather&page=2", m)   // ordinary parameters stay readable
        assertTrue(BrowserLogMask.mask("https://x.cn/cb?code=1234").contains("code=***"))
    }

    @Test fun headersUserInfoJsonAndCodes() {
        hidden("Authorization: Bearer abcdef123456.xyz", "abcdef123456.xyz")
        hidden("Cookie: SID=deadbeef; HSID=cafe", "deadbeef", "cafe")
        hidden("https://bob:pa55w0rd@host.cn/x", "pa55w0rd")
        hidden("""{"username":"bob","password":"pa55","otp":"123456"}""", "pa55", "123456")
        hidden("您的验证码为 482913，5分钟内有效", "482913")
        hidden("OTP: 7741", "7741")
        assertEquals("", BrowserLogMask.mask(null))
        assertEquals("about:blank", BrowserLogMask.mask("about:blank"))
    }
}

package com.openminis.app.browser

/**
 * [Mines-38.9.11.2] credentials never reach logcat / debug output of the in-app browser: every URL or page text the browser
 * logs goes through [mask]. Pure, unit-tested (BrowserLogMaskTest).
 *  - query / fragment / form values of secret-looking keys (password, token, code, otp, key, secret, session, auth, sig…);
 *  - `Bearer …` / `Basic …` credentials, `Cookie:` / `Set-Cookie:` values, user:pass@ in URLs;
 *  - JSON "password": "…" style pairs;
 *  - 4–8 digit codes next to 验证码 / code / OTP / PIN words.
 */
object BrowserLogMask {
    const val MASK = "***"
    private const val KEYS = "pass(?:word|wd)?|pwd|passcode|pin|otp|code|token|access_token|refresh_token|id_token|auth|authorization|" +
        "secret|client_secret|api[_-]?key|apikey|key|session|sessionid|sid|sig|signature|ticket|credential|jwt|cookie"

    private val query = Regex("""([?&#;]|^)((?:[A-Za-z0-9_.\-]*?)(?:$KEYS))=([^&#\s"']*)""", RegexOption.IGNORE_CASE)
    private val bearer = Regex("""\b(Bearer|Basic|Token)\s+[A-Za-z0-9._~+/=\-]{6,}""", RegexOption.IGNORE_CASE)
    private val cookieHdr = Regex("""\b(Set-Cookie|Cookie)\s*:\s*[^\n]*""", RegexOption.IGNORE_CASE)
    private val userInfo = Regex("""(\b[a-z][a-z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@""", RegexOption.IGNORE_CASE)
    private val jsonPair = Regex(""""((?:[A-Za-z0-9_\-]*?)(?:$KEYS))"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE)
    private val codeWord = Regex("""(验证码|校验码|动态码|code|otp|pin)([^0-9\n]{0,6})(\d{4,8})""", RegexOption.IGNORE_CASE)

    fun mask(s: String?): String {
        if (s.isNullOrEmpty()) return s.orEmpty()
        var r: String = s
        r = userInfo.replace(r) { it.groupValues[1] + MASK + "@" }
        r = bearer.replace(r) { it.groupValues[1] + " " + MASK }
        r = cookieHdr.replace(r) { it.groupValues[1] + ": " + MASK }
        r = jsonPair.replace(r) { "\"" + it.groupValues[1] + "\": \"" + MASK + "\"" }
        r = query.replace(r) { it.groupValues[1] + it.groupValues[2] + "=" + (if (it.groupValues[3].isEmpty()) "" else MASK) }
        r = codeWord.replace(r) { it.groupValues[1] + it.groupValues[2] + MASK }
        return r
    }
}

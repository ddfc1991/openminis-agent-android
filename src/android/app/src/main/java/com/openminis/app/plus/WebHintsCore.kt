package com.openminis.app.plus

/**
 * [Mines-38.9.11 b] In-app browser vs Cloudflare (user 2026-10-20 08:32, grok.com 「正在執行安全驗證」 under Mines'
 * 「页面没打开 HTTP 403」 card that covered the checkbox). Pure part, unit-tested in WebHintsCoreTest:
 *  - a Cloudflare / Turnstile challenge answer (403 / 503 / 429 with `cf-mitigated: challenge`, or a Cloudflare server +
 *    cf-ray on a challenge status) is the page asking the user to tick a box — NOT a load error, never an error card;
 *  - Sec-CH-UA brands that agree with the Chrome UA Mines sends (WebView's own brand list says "Android WebView").
 */
object WebHintsCore {
    fun chromeFullVersion(ua: String?): String? = Regex("""Chrome/([0-9]+(?:\.[0-9]+){1,3})""").find(ua.orEmpty())?.groupValues?.get(1)

    /** (brand, major, full) like real Chrome sends them, GREASE brand first. */
    fun brands(full: String): List<Triple<String, String, String>> {
        val major = full.substringBefore('.')
        return listOf(Triple("Not)A;Brand", "99", "99.0.0.0"), Triple("Google Chrome", major, full), Triple("Chromium", major, full))
    }

    private val CHALLENGE_STATUS = setOf(403, 429, 503)

    /** True when this main-frame HTTP answer is a bot / human check the user can pass on the page itself. */
    fun isChallenge(status: Int, headers: Map<String, String>?, title: String? = null): Boolean {
        val h = headers.orEmpty().mapKeys { it.key.lowercase() }
        if (h["cf-mitigated"]?.contains("challenge", true) == true) return true
        val t = title.orEmpty().lowercase()
        if (t.contains("just a moment") || t.contains("attention required") || t.contains("请稍候") || t.contains("安全驗證") ||
            t.contains("安全验证") || t.contains("verify you are human")) return true
        if (status !in CHALLENGE_STATUS) return false
        val cloudflare = h["server"]?.contains("cloudflare", true) == true || h.containsKey("cf-ray")
        val otherWaf = h.containsKey("x-amzn-waf-action") || h["server"]?.contains("ddos-guard", true) == true ||
            h.containsKey("x-datadome") || h.containsKey("x-sucuri-id")
        return cloudflare || otherWaf
    }

    const val CHALLENGE_NOTE = "challenge: 人机验证（不是错误，在页面上点一下）"
}

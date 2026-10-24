package com.openminis.app.plus

/**
 * [Mines-38.9.7] user 2026-10-18 21:30 (phone): 应用代理 › Manus 「去登录」 → pure white screen. Every sign-in WebView now
 * records its main-frame errors, HTTP errors and console errors here (last 20, no values typed on the page), and the
 * screen shows 「页面没打开」 + 重新加载 / 用浏览器打开 when nothing visible loaded after [BLANK_AFTER_MS]. 自检 lists them.
 */
object WebLoginDiag {
    const val BLANK_AFTER_MS = 12_000L
    data class Entry(val at: Long, val where: String, val what: String)
    private val ring = ArrayDeque<Entry>()

    fun note(where: String, what: String) = synchronized(ring) {
        ring.addLast(Entry(System.currentTimeMillis(), where.take(40), what.replace(Regex("[\\r\\n]+"), " ").take(200)))
        while (ring.size > 20) ring.removeFirst()
    }

    fun recent(): List<Entry> = synchronized(ring) { ring.toList().asReversed() }

    /** One line per entry for 自检 / smoke. */
    fun lines(max: Int = 5): String = recent().take(max).joinToString("；") { "${it.where}: ${it.what}" }.ifBlank { "没有记录到错误" }

    /** [Mines-38.9.11 b] main-frame HTTP error, but a Cloudflare / WAF challenge is noted as a challenge, not an error. */
    fun noteHttp(where: String, status: Int, reason: String?, headers: Map<String, String>?) {
        if (WebHintsCore.isChallenge(status, headers)) note(where, WebHintsCore.CHALLENGE_NOTE + " HTTP $status")
        else note(where, "main: HTTP $status ${reason.orEmpty()}")
    }

    /** The page of [where] is showing a human check right now (newer than any main-frame error). */
    fun challengeActive(where: String): Boolean =
        recent().firstOrNull { it.where == where && (it.what.startsWith("main:") || it.what.startsWith("challenge:")) }?.what?.startsWith("challenge:") == true

    /** Plain reason for the 「页面没打开」 card from the last main-frame error of [where] (null = just blank, or a human
     *  check is on screen — [Mines-38.9.11 b] that is never covered by an error card). */
    fun reasonFor(where: String): String? {
        val last = recent().firstOrNull { it.where == where && (it.what.startsWith("main:") || it.what.startsWith("challenge:")) } ?: return null
        if (last.what.startsWith("challenge:")) return null
        return last.what.removePrefix("main:").trim()
    }
}

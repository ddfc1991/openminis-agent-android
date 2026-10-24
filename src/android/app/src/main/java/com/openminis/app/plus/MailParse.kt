package com.openminis.app.plus

/**
 * [Mines-sources-33] Pure parsing used by the automatic data sources (no Android
 * imports, so it is unit-tested on a plain JVM; see hark-build/selftest/MailParseTest.kt).
 *
 *  - [extractCode]: a one-time code next to 验证码 / code / OTP … in mail or notification text
 *  - [atom]: Gmail's Atom feed (https://mail.google.com/mail/u/N/feed/atom, read with the
 *    signed-in WebView session)
 *  - [mime] / [decodeWords] / [qp] / [htmlText]: enough MIME to read an IMAP message body
 *  - [githubNotifications]: github.com/notifications HTML (web session, no token)
 *  - [balance]: a balance figure from a billing page's visible text
 *  - [cookieHas]: the "signed in" cookie test shared by the scanner and the login screen
 */
object MailParse {
    data class Mail(
        val id: String,
        val source: String,      // "Gmail", "Outlook", "163 邮箱", "QQ 邮箱" …
        val from: String,
        val subject: String,
        val text: String,        // plain text (snippet or decoded body)
        val at: Long,            // epoch ms, 0 = unknown
        val url: String? = null,
    )

    // ---------------- one-time codes ----------------

    private val KEYWORDS = Regex(
        "(验证码|校验码|动态码|確認碼|驗證碼|认证码|動態密碼|动态密码|短信码|安全码|登录码|激活码|verification|verify|security code|one[- ]time|passcode|sign[- ]in code|login code|confirmation code|\\bcode\\b|\\botp\\b|\\b2fa\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val DIGITS = Regex("(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9])")
    private val SPACED = Regex("(?<![0-9])([0-9]{3})[ \\-]([0-9]{3})(?![0-9])") // "482 913" / "482-913"

    /** The code nearest a keyword, or null. Years, amounts, phone/zip-like and long ids are skipped. */
    fun extractCode(text: String): String? {
        if (text.isBlank()) return null
        val t = text.replace('\u00a0', ' ')
        val kw = KEYWORDS.findAll(t).map { it.range.first }.toList()
        if (kw.isEmpty()) return null
        data class C(val v: String, val pos: Int)
        val cands = ArrayList<C>()
        DIGITS.findAll(t).forEach { m ->
            val v = m.groupValues[1]
            val before = t.getOrNull(m.range.first - 1)
            val after = t.getOrNull(m.range.last + 1)
            val year = v.length == 4 && (v.startsWith("19") || v.startsWith("20"))
            val nearKw = kw.any { k -> kotlin.math.abs(k - m.range.first) <= 40 }
            when {
                before == '¥' || before == '$' || before == '￥' || before == '#' -> {}
                after == '年' || after == '元' || after == '%' || after == '.' && t.getOrNull(m.range.last + 2)?.isDigit() == true -> {}
                before == '.' || before == ':' && t.getOrNull(m.range.first - 2)?.isDigit() == true -> {} // 12:34, 1.2345
                year && !nearKw -> {}
                year && t.substring(maxOf(0, m.range.first - 2), m.range.first).contains("©") -> {}
                else -> cands += C(v, m.range.first)
            }
        }
        SPACED.findAll(t).forEach { m -> cands += C(m.groupValues[1] + m.groupValues[2], m.range.first) }
        if (cands.isEmpty()) return null
        val best = cands.minByOrNull { c -> kw.minOf { k -> val d = c.pos - k; if (d >= 0) d else -d * 2 } } ?: return null
        val dist = kw.minOf { k -> kotlin.math.abs(best.pos - k) }
        return if (dist <= 300) best.v else null
    }

    /** Newest code among mails received at or after [sinceMs] (unknown dates count as fresh). */
    fun codeFrom(mails: List<Mail>, sinceMs: Long): Pair<String, Mail>? =
        mails.filter { it.at == 0L || it.at >= sinceMs }.sortedByDescending { it.at }
            .firstNotNullOfOrNull { m -> extractCode(m.subject + "\n" + m.text)?.let { it to m } }

    // ---------------- Gmail Atom feed ----------------

    private fun tag(s: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>").find(s)?.groupValues?.get(1)

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")

    /** Returns null when [xml] is not a Gmail feed (e.g. a login page). */
    fun atom(xml: String, source: String = "Gmail"): List<Mail>? {
        if (!xml.contains("<feed") || !xml.contains("gmail", ignoreCase = true) && !xml.contains("<entry")) return null
        return xml.split("<entry").drop(1).mapNotNull { e ->
            val title = unescape(tag(e, "title").orEmpty()).trim()
            val summary = unescape(tag(e, "summary").orEmpty()).trim()
            val author = tag(e, "author").orEmpty()
            val name = unescape(tag(author, "name").orEmpty()).trim()
            val email = tag(author, "email").orEmpty().trim()
            val at = (tag(e, "issued") ?: tag(e, "modified"))?.trim()?.let { parseIso(it) } ?: 0L
            val link = Regex("<link[^>]*href=\"([^\"]+)\"").find(e)?.groupValues?.get(1)?.let { unescape(it) }
            val id = tag(e, "id")?.trim() ?: (title + at)
            if (title.isBlank() && summary.isBlank()) null
            else Mail("gm:" + id.hashCode(), source, name.ifBlank { email }, title, summary, at, link)
        }
    }

    fun parseIso(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s.trim().replace(" ", "T")).toInstant().toEpochMilli()
    }.recoverCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }.getOrDefault(0L)

    // ---------------- MIME (IMAP bodies) ----------------

    /** RFC 2047 encoded words: =?UTF-8?B?…?= / =?gbk?Q?…?= */
    fun decodeWords(s: String): String =
        Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=(\\s+(?==\\?))?").replace(s) { m ->
            val cs = charset(m.groupValues[1])
            val data = m.groupValues[3]
            runCatching {
                if (m.groupValues[2].equals("B", true)) String(java.util.Base64.getMimeDecoder().decode(data), cs)
                else String(qpBytes(data.replace('_', ' ')), cs)
            }.getOrDefault(m.value)
        }

    private fun charset(name: String): java.nio.charset.Charset = runCatching {
        val n = name.trim().trim('"').lowercase()
        java.nio.charset.Charset.forName(if (n == "gb2312" || n == "gbk") "GB18030" else n)
    }.getOrDefault(Charsets.UTF_8)

    fun qpBytes(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val src = s.replace(Regex("=\\r?\\n"), "")
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '=' && i + 2 < src.length) {
                val hex = src.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (hex.length == 2 && v != null) { out.write(v); i += 3; continue }
            }
            val b = c.toString().toByteArray(Charsets.UTF_8)
            out.write(b, 0, b.size)
            i++
        }
        return out.toByteArray()
    }

    fun qp(s: String, cs: String = "utf-8"): String = String(qpBytes(s), charset(cs))

    fun htmlText(html: String): String = unescape(
        html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h\\d>"), "\n")
            .replace(Regex("<[^>]+>"), " "),
    ).replace(Regex("[ \\t\\u00a0]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()

    private fun headerValue(headers: String, name: String): String? {
        val unfolded = headers.replace(Regex("\\r?\\n[ \\t]+"), " ")
        return Regex("(?im)^$name:\\s*(.*)$").find(unfolded)?.groupValues?.get(1)?.trim()
    }

    /** Subject + From from a header block. */
    fun headers(block: String): Pair<String, String> =
        decodeWords(headerValue(block, "Subject").orEmpty()) to decodeWords(headerValue(block, "From").orEmpty()).replace("\"", "")

    /**
     * Plain text of a MIME entity ([raw] = headers + blank line + body, or just a body
     * with [contentType] / [encoding] given). Picks text/plain, else text/html, recursing
     * into multiparts. Truncated input is fine.
     */
    fun mime(raw: String, contentType: String? = null, encoding: String? = null, depth: Int = 0): String {
        if (depth > 5) return ""
        val split = Regex("\\r?\\n\\r?\\n").find(raw)
        val hasHeaders = contentType == null && split != null && Regex("(?im)^content-type:").containsMatchIn(raw.substring(0, split.range.first))
        val head = if (hasHeaders) raw.substring(0, split!!.range.first) else ""
        val body = if (hasHeaders) raw.substring(split!!.range.last + 1) else raw
        val ct = contentType ?: headerValue(head, "Content-Type") ?: "text/plain"
        val enc = (encoding ?: headerValue(head, "Content-Transfer-Encoding") ?: "").lowercase().trim()
        val cs = Regex("(?i)charset=\"?([^\";\\s]+)").find(ct)?.groupValues?.get(1) ?: "utf-8"
        if (ct.lowercase().startsWith("multipart/")) {
            val boundary = Regex("(?i)boundary=\"?([^\";]+)\"?").find(ct)?.groupValues?.get(1)?.trim() ?: return htmlText(body)
            val parts = body.split("--$boundary").map { it.trim('\r', '\n') }.filter { it.isNotBlank() && it != "--" && !it.startsWith("--") }
            val decoded = parts.map { p -> p to mime(p, depth = depth + 1) }
            val plain = decoded.firstOrNull { (p, t) -> Regex("(?i)content-type:\\s*text/plain").containsMatchIn(p.take(400)) && t.isNotBlank() }
            return (plain ?: decoded.firstOrNull { it.second.isNotBlank() })?.second.orEmpty()
        }
        val text = when (enc) {
            "base64" -> runCatching { String(java.util.Base64.getMimeDecoder().decode(body.replace(Regex("[^A-Za-z0-9+/=]"), "").let { it.substring(0, it.length - it.length % 4) }), charset(cs)) }.getOrDefault(body)
            "quoted-printable" -> qp(body, cs)
            else -> body
        }
        return if (ct.lowercase().contains("html") || text.contains("<html", true) || text.contains("<div", true)) htmlText(text) else text.trim()
    }

    // ---------------- GitHub notifications page (web session) ----------------

    data class GhNote(val title: String, val repo: String, val url: String, val reason: String)

    fun githubNotifications(html: String): List<GhNote> {
        if (!html.contains("notifications-list-item")) return emptyList()
        return html.split(Regex("<li[^>]*notifications-list-item")).drop(1).mapNotNull { li ->
            val href = Regex("href=\"(/[^\"]+/(?:issues|pull|discussions|commit|releases|security|actions)[^\"]*)\"").find(li)?.groupValues?.get(1)
                ?: Regex("class=\"[^\"]*notification-list-item-link[^\"]*\"[^>]*href=\"([^\"]+)\"").find(li)?.groupValues?.get(1)
                ?: Regex("href=\"([^\"]+)\"[^>]*class=\"[^\"]*notification-list-item-link").find(li)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = Regex("<p[^>]*markdown-title[^>]*>([\\s\\S]*?)</p>").find(li)?.groupValues?.get(1)?.let { htmlText(it) }
                ?: return@mapNotNull null
            val repo = Regex("^/([^/]+/[^/]+)/").find(href)?.groupValues?.get(1) ?: ""
            val reason = Regex("(?i)\\b(review requested|mention|assigned|author|comment|subscribed|state change|security alert|ci activity|team mention)\\b").find(htmlText(li))?.value ?: ""
            GhNote(title.trim(), repo, if (href.startsWith("http")) href else "https://github.com" + unescape(href), reason.lowercase())
        }.distinctBy { it.url }
    }

    // ---------------- balances from page text ----------------

    private val BAL = Regex(
        "(可用余额|账户余额|现金余额|余额|剩余额度|可用额度|赠送余额|Credits remaining|Remaining credits|Available balance|Credit balance|Balance|Credits)" +
            "[^0-9¥$￥\\n]{0,24}\\n?[^0-9¥$￥\\n]{0,12}([¥$￥]|US\\$|CNY|USD)?\\s*(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(元|CNY|USD)?",
        RegexOption.IGNORE_CASE,
    )

    /** "¥12.34" / "$5.00" or null. */
    fun balance(text: String): String? {
        val m = BAL.find(text) ?: return null
        val cur = m.groupValues[2].ifBlank { m.groupValues[4] }
        val sym = when {
            cur.contains("$") || cur.equals("USD", true) -> "$"
            cur.isBlank() && Regex("(?i)credits|balance").containsMatchIn(m.groupValues[1]) && !text.contains("¥") && !text.contains("元") -> "$"
            else -> "¥"
        }
        return sym + m.groupValues[3]
    }

    // ---------------- cookies ----------------

    /** True when [cookieHeader] holds one of [names] ("SID" = a cookie named SID, "a=b" = that exact pair). */
    fun cookieHas(cookieHeader: String?, names: List<String>): Boolean {
        if (cookieHeader.isNullOrBlank() || names.isEmpty()) return false
        val pairs = cookieHeader.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return names.any { n ->
            val want = n.trimEnd('=')
            if (want.contains('=')) pairs.any { it == want }
            else pairs.any { p -> p.substringBefore('=') == want && p.substringAfter('=', "").isNotBlank() }
        }
    }

    /** A user name hint from well-known session cookies (GitHub dotcom_user, …). */
    fun accountFromCookies(kindId: String, cookieHeader: String?): String? {
        val pairs = cookieHeader.orEmpty().split(';').map { it.trim() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return when (kindId) {
            "github" -> pairs["dotcom_user"]
            "bilibili" -> pairs["DedeUserID"]?.let { "UID $it" }
            "x" -> pairs["twid"]?.let { java.net.URLDecoder.decode(it, "UTF-8").removePrefix("u=").let { id -> "id $id" } }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}

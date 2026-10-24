package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.12.1] user 2026-10-23 08:07: 「自建域名 / Cloudflare」 always said 未开通. Client rules for the user's own
 * inbox API (Cloudflare Email Routing catch-all → Worker → KV), pure and unit-tested (SelfMailCoreTest):
 *   GET /api/health → {ok, domain} · POST /api/mailboxes {prefix?} → {address} · GET /api/mailboxes → [{address, createdAt, count}]
 *   GET /api/messages?to=&since=&limit= → [{id, to, from, subject, text, html?, receivedAt}] · DELETE /api/messages/<id>
 * Auth: `Authorization: Bearer <token>`. The token is only ever stored in the encrypted vault, never in code.
 */
object SelfMailCore {
    const val SOURCE = "自建域名"

    data class Config(val base: String, val token: String)
    data class Box(val address: String, val createdAt: Long, val count: Int)

    /**
     * What the user pasted: `mines-mail://<host>/<path>#<token>` (→ https://<host>/<path>), or an https URL and the token
     * on separate lines (either order). Null when there is no https base or no token.
     */
    fun parseConfig(raw: String?): Config? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("mines-mail://", ignoreCase = true)) {
            val rest = s.substring("mines-mail://".length)
            val hash = rest.indexOf('#'); if (hash <= 0) return null
            val token = rest.substring(hash + 1).trim()
            val base = normBase("https://" + rest.substring(0, hash)) ?: return null
            return if (tokenOk(token)) Config(base, token) else null
        }
        val parts = s.split('\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        val url = parts.firstOrNull { it.startsWith("https://", true) } ?: return null
        val token = parts.firstOrNull { it != url && tokenOk(it) } ?: return null
        return normBase(url)?.let { Config(it, token) }
    }

    /** https only, no trailing slash, no query / fragment, no trailing "/api" (the client adds it) */
    fun normBase(u: String?): String? {
        val s = u?.trim().orEmpty().substringBefore('#').substringBefore('?').trimEnd('/')
        if (!s.startsWith("https://", true)) return null
        val host = s.substring(8).substringBefore('/')
        if (host.isBlank() || !host.contains('.') || host.any { it.isWhitespace() }) return null
        return s.removeSuffix("/api")
    }

    fun tokenOk(t: String?): Boolean = t != null && t.length in 16..512 && t.none { it.isWhitespace() || it == '#' } && !t.startsWith("http", true)

    /** {ok:true, domain:"x"} → "x" (null when not ok) */
    fun parseHealth(json: String): String? = runCatching {
        val o = JSONObject(json)
        if (!o.optBoolean("ok", false)) null else o.optString("domain").trim().lowercase().takeIf { it.contains('.') }
    }.getOrNull()

    fun parseAddress(json: String): String? = runCatching { JSONObject(json).optString("address").trim().takeIf { it.contains('@') } }.getOrNull()

    private fun arr(json: String, key: String): JSONArray? = runCatching {
        val t = json.trim(); if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray(key)
    }.getOrNull()

    fun parseMailboxes(json: String): List<Box> {
        val a = arr(json, "mailboxes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ad = o.optString("address").trim(); if (!ad.contains('@')) return@mapNotNull null
            Box(ad, time(o.opt("createdAt")), o.optInt("count", 0))
        }
    }

    /** messages → MailParse.Mail (source 自建域名); the html part is only used when there is no text */
    fun parseMessages(json: String): List<MailParse.Mail> {
        val a = arr(json, "messages") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isBlank()) return@mapNotNull null
            val text = o.optString("text").ifBlank { stripHtml(o.optString("html")) }
            MailParse.Mail("sh:$id", SOURCE, o.optString("from"), o.optString("subject"), text.take(4000), time(o.opt("receivedAt")))
        }
    }

    /** the address a message was sent to (for the 验证码 row), from the same JSON */
    fun toOf(json: String): Map<String, String> {
        val a = arr(json, "messages") ?: return emptyMap()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { "sh:" + it.optString("id") to it.optString("to") } }.toMap()
    }

    /** epoch ms number, epoch seconds, or ISO text → ms */
    fun time(v: Any?): Long = when (v) {
        is Number -> v.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }
        is String -> v.toLongOrNull()?.let { time(it) } ?: CodeSourcesCore.iso(v)
        else -> 0L
    }

    fun stripHtml(h: String): String = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()

    /** prefix for a new address: platform name → lowercase letters / digits, ≤ 16, plus a short suffix */
    fun prefixFor(platform: String, n: Int): String {
        val p = platform.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16).ifEmpty { "mines" }
        return p + "-" + n.toString(36).takeLast(4)
    }
}

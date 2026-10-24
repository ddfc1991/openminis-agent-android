package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:35 / 19:36: most users have no Cloudflare, so codes must not depend on it. Default:
 * the agent reads a verification code / sign-in link straight from the user's OWN linked mailboxes (Gmail app password
 * IMAP, 163 / QQ 授权码 IMAP, Outlook) — only recent mail from the site being signed into; the code or link is taken,
 * the body is never stored. 「AI 服务」 shows these as 「验证码来源」 with a switch each. Ready-made agent mailboxes are an
 * optional 「AI 邮箱」 group; a self-hosted domain (Cloudflare) is 高级 · 可选 and hidden by default. Pure part, unit-tested
 * in CodeSourcesCoreTest.
 */
object CodeSourcesCore {
    /** Only mail this young counts (a code is valid for minutes). */
    const val MAX_AGE_MS = 10 * 60_000L

    /** "GitHub <noreply@github.com>" / "noreply@github.com" → "github.com" (registrable part). */
    fun senderDomain(from: String): String {
        val addr = Regex("[A-Za-z0-9._%+\\-]+@([A-Za-z0-9.\\-]+)").find(from)?.groupValues?.get(1)?.lowercase().orEmpty()
        val parts = addr.trim('.').split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return parts.joinToString(".")
        val two = parts.takeLast(2).joinToString(".")
        return if (two in setOf("com.cn", "net.cn", "org.cn", "co.uk", "com.hk", "co.jp")) parts.takeLast(3).joinToString(".") else two
    }

    /** Does this mail come from the site being signed into? (aliases: 百炼 → aliyun.com, Gmail → google.com …) */
    fun fromSite(from: String, subject: String, site: String): Boolean {
        val s = AccountsCore.accountSite(site).lowercase()
        val d = senderDomain(from)
        if (d.isNotBlank() && (AccountsCore.sameSite(s, d) || AccountsCore.sameSite(site, d))) return true
        // display names like 「阿里云」 / "GitHub" when the sender is a mailing service (sendgrid, amazonses …)
        val name = s.substringBefore('.')
        return name.length >= 3 && (from.contains(name, true) || subject.contains(name, true))
    }

    data class Found(val code: String?, val link: String?, val from: String, val at: Long)

    private val LINK = Regex("https?://[^\\s\"'<>）)]+", RegexOption.IGNORE_CASE)
    private val LINK_WORDS = Regex("(?i)(verify|verification|confirm|magic|login|signin|sign-in|auth|token|activate|激活|验证|登录)")

    /** A sign-in / verify link on the site's own domain in [text] (null = none). */
    fun linkFrom(text: String, site: String): String? = LINK.findAll(text).map { it.value.trimEnd('.', ',', ';') }
        .firstOrNull { u ->
            val host = u.substringAfter("://").substringBefore('/').substringBefore('?')
            (AccountsCore.sameSite(AccountsCore.accountSite(site), host) || AccountsCore.sameSite(site, host)) && LINK_WORDS.containsMatchIn(u)
        }

    /**
     * The newest code / link from [site] among [mails] that arrived after [sinceMs] and are at most [MAX_AGE_MS] old.
     * Mail from other senders is never looked at. Returns only the code / link, never the body.
     */
    fun find(mails: List<MailParse.Mail>, site: String, sinceMs: Long, now: Long): Found? =
        mails.asSequence()
            .filter { (it.at == 0L || (it.at >= sinceMs && now - it.at <= MAX_AGE_MS)) && fromSite(it.from, it.subject, site) }
            .sortedByDescending { it.at }
            .mapNotNull { m ->
                val all = m.subject + "\n" + m.text
                val c = MailParse.extractCode(all)
                val l = linkFrom(all, site)
                if (c == null && l == null) null else Found(c, l, m.from, m.at)
            }.firstOrNull()

    /** 「验证码 482913」 → 「验证码 48****」 for the card (the last codes are shown masked). */
    fun mask(code: String): String = if (code.length <= 2) "**" else code.take(2) + "*".repeat(code.length - 2)

    // ---------------- 「AI 邮箱」 providers ----------------

    data class Provider(
        val id: String, val name: String, val url: String, val limits: String, val needsKey: Boolean,
        val note: String = "", val advanced: Boolean = false, val temporary: Boolean = false,
        /** [38.9.11.1] the service itself is down (agmail.ai: no DNS / no response, 2026-10-20) → 暂不可用, no buttons */
        val down: Boolean = false,
    )

    /** user 19:36 — the list and the free limits shown on each row. */
    val PROVIDERS = listOf(
        Provider("agentmail", "AgentMail", "https://console.agentmail.to", "免费 3 个邮箱 · 每月 3,000 封 · 每天 100 封", needsKey = true,
            note = "登录后在控制台建 API Key，Mines 存进保险箱，用 API 读码"),
        Provider("agmail", "AGmail", "https://agmail.ai", "免费邮箱 @agmail.ai", needsKey = true, note = "官网 agmail.ai 现在打不开，先用 AgentMail 或 Mail.tm", down = true),
        Provider("mailtm", "Mail.tm", "https://mail.tm", "免费临时邮箱 · 不用密钥 · 每秒 8 次", needsKey = false,
            note = "临时，只适合一次性注册", temporary = true),
        Provider("selfhost", "自建域名 / Cloudflare", "https://dash.cloudflare.com", "要有自己的域名和 Cloudflare 账号", needsKey = true,
            note = "高级 · 可选", advanced = true),
    )

    // ---------------- Mail.tm / AgentMail JSON (API clients live in AgentMailbox) ----------------

    /** api.mail.tm GET /messages → mails (from.address, subject, intro, createdAt). [38.9.11.1] AgentMailbox asks with
     *  `Accept: application/json`, which returns a plain array; hydra:member / member is the ld+json shape. */
    fun mailTmMessages(json: String): List<MailParse.Mail> = runCatching {
        val t = json.trim()
        val a = if (t.startsWith("[")) org.json.JSONArray(t) else {
            val o = JSONObject(t)
            o.optJSONArray("hydra:member") ?: o.optJSONArray("member") ?: return emptyList()
        }
        (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            val from = m.optJSONObject("from")?.let { f -> (f.optString("name") + " <" + f.optString("address") + ">").trim() }.orEmpty()
            MailParse.Mail("mt:" + m.optString("id"), "Mail.tm", from, m.optString("subject"), m.optString("intro"), iso(m.optString("createdAt")))
        }
    }.getOrDefault(emptyList())

    /** api.agentmail.to GET /v0/inboxes/{id}/messages → mails (from, subject, preview, timestamp). */
    fun agentMailMessages(json: String): List<MailParse.Mail> = runCatching {
        val a = JSONObject(json).optJSONArray("messages") ?: return emptyList()
        (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            MailParse.Mail("am:" + m.optString("message_id"), "AgentMail", m.optString("from"), m.optString("subject"), m.optString("preview"), iso(m.optString("timestamp")))
        }
    }.getOrDefault(emptyList())

    fun iso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
}

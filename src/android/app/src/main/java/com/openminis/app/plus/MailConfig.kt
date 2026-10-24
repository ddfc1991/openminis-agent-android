package com.openminis.app.plus

/**
 * [Mines-38.9.1] user 2026-10-16 「邮箱这里的授权码还是有问题，不是一个的问题」 — one table for every mailbox and one mapping
 * of the server's own words to plain Chinese (pure, unit-tested):
 *  - codes pasted with spaces / line breaks / no-break or zero-width spaces (Gmail shows 4 × 4) are cleaned;
 *  - host / port / TLS per domain; 163 / 126 / yeah / vip.163 need the IMAP ID command (RFC 2971) before LOGIN;
 *  - Outlook / Hotmail turned basic auth off: a 授权码 cannot work there, it needs 微软授权 (said up front);
 *  - errors: 地址或授权码不对 / 没开 IMAP / 要应用专用密码 / 网易拦了不安全登录 / 网络, with the server text kept short.
 */
object MailConfig {
    data class Cfg(val host: String, val port: Int = 993, val tls: Boolean = true, val needsId: Boolean = false,
                   val provider: String, val codeName: String = "授权码", val oauthOnly: Boolean = false, val imapHelp: String = "")

    fun of(address: String): Cfg? {
        val d = address.substringAfter('@', "").lowercase().trim()
        val wy = "网页版 设置 → POP3/SMTP/IMAP → 开启 IMAP/SMTP 服务 → 新增授权码"
        return when (d) {
            "gmail.com", "googlemail.com" -> Cfg("imap.gmail.com", provider = "Gmail", codeName = "应用专用密码",
                imapHelp = "Google 账号 → 安全性 → 两步验证 → 应用专用密码（16 位）")
            "163.com" -> Cfg("imap.163.com", needsId = true, provider = "163 邮箱", imapHelp = wy)
            "126.com" -> Cfg("imap.126.com", needsId = true, provider = "126 邮箱", imapHelp = wy)
            "yeah.net" -> Cfg("imap.yeah.net", needsId = true, provider = "yeah 邮箱", imapHelp = wy)
            "vip.163.com" -> Cfg("imap.vip.163.com", needsId = true, provider = "163 VIP 邮箱", imapHelp = wy)
            "qq.com", "foxmail.com", "vip.qq.com" -> Cfg("imap.qq.com", provider = "QQ 邮箱", imapHelp = "QQ 邮箱网页版 设置 → 账号 → 开启 IMAP/SMTP 服务 → 生成授权码")
            "sina.com", "sina.cn" -> Cfg("imap.sina.com", provider = "新浪邮箱", imapHelp = "新浪邮箱 设置 → 客户端 pop/imap/smtp → 开启 IMAP")
            "aliyun.com" -> Cfg("imap.aliyun.com", provider = "阿里邮箱", codeName = "密码")
            "outlook.com", "hotmail.com", "live.com", "msn.com" -> Cfg("outlook.office365.com", provider = "Outlook", oauthOnly = true)
            else -> null
        }
    }

    /** Clean a pasted 授权码 / 应用专用密码: every kind of space, line break, zero-width char and stray quotes go. */
    fun normalizeCode(s: String): String = s.replace(Regex("[\\s\\u00A0\\u2000-\\u200D\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"), "").trim('"', '\'', '“', '”', '「', '」')

    fun normalizeAddress(s: String): String = s.replace(Regex("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]"), "").lowercase()

    enum class Why { ADDRESS_OR_CODE, IMAP_OFF, APP_PASSWORD, UNSAFE_LOGIN, OAUTH_ONLY, NET, OTHER }

    /** The server's words (LOGIN / SELECT reply or a network error) → which problem it is. */
    fun classify(server: String, cfg: Cfg?): Why {
        val t = server.lowercase()
        return when {
            cfg?.oauthOnly == true || t.contains("basic authentication is disabled") || t.contains("basicauthblocked") -> Why.OAUTH_ONLY
            t.contains("unsafe login") -> Why.UNSAFE_LOGIN
            t.contains("application-specific password") || t.contains("app password") || t.contains("web login required") -> Why.APP_PASSWORD
            t.contains("imap") && (t.contains("disabled") || t.contains("not enabled") || t.contains("未开启") || t.contains("关闭")) ||
                t.contains("please using authorized code") || t.contains("请使用授权码") || t.contains("请开启") -> Why.IMAP_OFF
            t.contains("authenticationfailed") || t.contains("invalid credentials") || t.contains("password error") || t.contains("login fail") ||
                t.contains("login error") || t.contains("auth fail") || t.contains("账号或密码") || t.contains("密码错误") || t.contains("user not exist") -> Why.ADDRESS_OR_CODE
            t.contains("timeout") || t.contains("超时") || t.contains("连不上") || t.contains("unreachable") || t.contains("断了") || t.contains("reset") -> Why.NET
            else -> Why.OTHER
        }
    }

    fun explain(server: String, cfg: Cfg?, address: String): String {
        val p = cfg?.provider ?: "邮箱"; val code = cfg?.codeName ?: "授权码"
        val raw = server.take(70).let { if (it.isNotBlank()) "（服务器：$it）" else "" }
        return when (classify(server, cfg)) {
            Why.OAUTH_ONLY -> "Outlook / Hotmail 已经不支持用授权码登录邮箱，要用微软授权：到 账号连接 › Outlook 点「一键授权」"
            Why.UNSAFE_LOGIN -> "$p 拦了这次登录（Unsafe Login）：Mines 已经按要求先报了身份，仍被拦，请在${cfg?.imapHelp?.ifBlank { null } ?: "网页版"}重新生成授权码再试$raw"
            Why.APP_PASSWORD -> "Gmail 要用「应用专用密码」，不是 Google 登录密码：${cfg?.imapHelp ?: ""}$raw"
            Why.IMAP_OFF -> "$p 还没开 IMAP：${cfg?.imapHelp ?: "到网页版设置里开启 IMAP"}$raw"
            Why.ADDRESS_OR_CODE -> "地址和${code}对不上：请核对地址 $address，${code}要从${cfg?.imapHelp?.ifBlank { null } ?: "邮箱设置"}里拿（不是邮箱登录密码）$raw"
            Why.NET -> "连不上 $p 的服务器${cfg?.host?.let { "（$it）" } ?: ""}：" + (if (cfg?.provider == "Gmail") "国内网络直连 Gmail 会被拦，开 VPN 或在 设置 › 网络 › 分流代理 填上游代理后再试" else "网络问题，稍后再试") + raw
            Why.OTHER -> "$p 没接受$raw"
        }
    }
}

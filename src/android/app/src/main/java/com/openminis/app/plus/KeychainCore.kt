package com.openminis.app.plus

/**
 * [Mines-38.9] 账号关联 keychain — the pure part (no Android types, unit-tested on the JVM): identity of an account
 * (phone / e-mail), masking, status order, TOTP (RFC 6238), otpauth:// parsing, new-device / abnormal-login detection in
 * a mail or SMS, and each site's security page.
 */
object KeychainCore {
    // [Mines-38.9.3] one pill vocabulary (SessionStatus.Pill): ABNORMAL → 异常 (new-device alert), EXPIRED → 要注意 (confirmed
    // sign-out, 「要重新登录」), UNCHECKED → 未检查 (grey, never a warning), OK → 正常.
    enum class Status(val label: String, val rank: Int) { ABNORMAL("异常登录", 0), EXPIRED("要重新登录", 1), UNCHECKED("未检查", 2), OK("正常", 3) }

    enum class IdKind { PHONE, EMAIL, OTHER }
    data class Identity(val kind: IdKind, val key: String, val label: String, val masked: String)

    private val EMAIL = Regex("^[^@\\s]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$")
    private val PHONE = Regex("^\\+?[0-9][0-9 \\-]{6,16}[0-9]$")

    fun digits(s: String) = s.filter { it.isDigit() }

    /** Which identity an account name belongs to: 手机号 (by its last 11 digits) / the mailbox / 其他. */
    fun identity(account: String): Identity {
        val a = account.trim()
        if (EMAIL.matches(a)) {
            val dom = a.substringAfter('@').lowercase()
            val label = when {
                dom == "gmail.com" || dom == "googlemail.com" -> "Gmail"
                dom == "163.com" -> "163 邮箱"
                dom == "126.com" -> "126 邮箱"
                dom == "qq.com" -> "QQ 邮箱"
                dom.startsWith("outlook.") || dom.startsWith("hotmail.") || dom == "live.com" -> "Outlook"
                dom == "icloud.com" || dom == "me.com" -> "iCloud"
                else -> "邮箱"
            }
            return Identity(IdKind.EMAIL, a.lowercase(), label, a.substringBefore('@').take(10) + "@…")
        }
        if (PHONE.matches(a)) {
            val d = digits(a).takeLast(11)
            return Identity(IdKind.PHONE, "phone:$d", "手机号", maskPhone(a))
        }
        return Identity(IdKind.OTHER, "other", "其他账号", "")
    }

    /** +86 136****8658 style: country code kept, middle four hidden. */
    fun maskPhone(p: String): String {
        val d = digits(p)
        if (d.length < 7) return p
        val local = if (d.length > 11 && d.startsWith("86")) d.takeLast(11) else d.takeLast(minOf(11, d.length))
        val cc = if (p.trim().startsWith("+") || d.length > 11) "+" + d.dropLast(local.length).ifBlank { "86" } + " " else ""
        val m = if (local.length >= 11) local.take(3) + "****" + local.takeLast(4) else local.take(2) + "***" + local.takeLast(2)
        return cc + m
    }

    fun maskAccount(a: String): String = when (identity(a).kind) {
        IdKind.PHONE -> maskPhone(a).substringAfter(' ')
        else -> a
    }

    // ---------------- TOTP (RFC 6238, SHA-1, 6 digits, 30 s) ----------------

    data class Totp(val secret: String, val digits: Int = 6, val period: Int = 30, val algo: String = "HmacSHA1", val issuer: String = "", val label: String = "")

    fun base32(s: String): ByteArray? {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = s.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        if (clean.isEmpty() || clean.any { it !in alpha }) return null
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in clean) {
            buf = (buf shl 5) or alpha.indexOf(c); bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }

    /** A pasted secret (base32) or an otpauth://totp/… URI (from a QR code shown on the site). */
    fun parseTotp(input: String): Totp? {
        val t = input.trim()
        if (t.startsWith("otpauth://", true)) {
            val q = t.substringAfter('?', "")
            val params = q.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val sec = params["secret"] ?: return null
            if (base32(sec) == null) return null
            val label = java.net.URLDecoder.decode(t.substringAfter("totp/", "").substringBefore('?'), "UTF-8")
            val algo = when (params["algorithm"]?.uppercase()) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
            return Totp(sec.uppercase().replace(" ", ""), params["digits"]?.toIntOrNull()?.coerceIn(6, 8) ?: 6,
                params["period"]?.toIntOrNull()?.coerceIn(15, 120) ?: 30, algo, params["issuer"] ?: label.substringBefore(':'), label.substringAfter(':'))
        }
        val sec = t.uppercase().replace(" ", "").replace("-", "")
        return if (sec.length >= 16 && base32(sec) != null) Totp(sec) else null
    }

    fun totpCode(t: Totp, epochMs: Long): String {
        val key = base32(t.secret) ?: return ""
        val counter = epochMs / 1000 / t.period
        val msg = ByteArray(8) { i -> ((counter shr ((7 - i) * 8)) and 0xFF).toByte() }
        val mac = javax.crypto.Mac.getInstance(t.algo); mac.init(javax.crypto.spec.SecretKeySpec(key, t.algo))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1; repeat(t.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(t.digits, '0')
    }

    fun secondsLeft(t: Totp, epochMs: Long): Int = t.period - ((epochMs / 1000) % t.period).toInt()

    fun spaced(code: String) = if (code.length == 6) code.take(3) + " " + code.takeLast(3) else code

    // ---------------- new-device / abnormal login in a mail or SMS ----------------

    data class Alert(val site: String, val host: String, val account: String, val device: String, val place: String, val at: Long, val source: String, val key: String)

    private val ALERT = Regex("(?i)(新设备|新的设备|异地登录|异常登录|陌生设备|新登录|登录提醒|登录通知|安全提醒|new (sign-?in|login|device)|unusual (sign-?in|activity|login)|suspicious|security alert|signed in from|login attempt|was used to sign in)")
    private val NOT_ALERT = Regex("(?i)(验证码|verification code|one-time|otp|your code|动态码)")   // a code mail is a code, not an alert

    /** A mail (subject + body) or an SMS that says someone signed in somewhere new; null when it is not one. */
    fun detectAlert(siteHint: String, host: String, account: String, subject: String, body: String, at: Long, source: String): Alert? {
        val head = subject + " " + body.take(400)
        if (!ALERT.containsMatchIn(head)) return null
        if (NOT_ALERT.containsMatchIn(subject) && !Regex("(新设备|异地|new device|unusual)", RegexOption.IGNORE_CASE).containsMatchIn(subject)) return null
        val text = body.replace('\r', ' ')
        fun field(vararg k: String): String {
            for (w in k) {
                val m = Regex("(?i)$w\\s*[:：]\\s*([^\\n。；;|]{2,60})").find(text) ?: continue
                return m.groupValues[1].trim().trimEnd(',', '，', '.').take(40)
            }
            return ""
        }
        var device = field("设备", "登录设备", "device", "browser", "浏览器", "客户端", "operating system", "系统")
        var place = field("位置", "地点", "登录地点", "地区", "location", "near", "城市", "ip ?地址", "ip address", "ip")
        if (device.isBlank()) Regex("(?i)(windows|mac ?os|macintosh|iphone|ipad|android|linux|chrome ?os)[^\\n,，。.;]{0,24}").find(text)?.let { device = it.value.trim().removeSuffix(" device").take(40) }
        if (place.isBlank()) Regex("(?:\\b(?:from|near|in)\\s+([A-Z][a-zA-Z]+(?: [A-Z][a-zA-Z]+){0,2})|(?:来自|在)\\s*([\\u4e00-\\u9fa5]{2,8})(?:附近|登录))").find(text)?.let { place = it.groupValues[1].ifBlank { it.groupValues[2] }.trim() }
        val site = siteHint.ifBlank { host }
        return Alert(site, host, account, device, place, at, source, (host.ifBlank { site } + "|" + account + "|" + (at / 60_000)).lowercase())
    }

    /** Where the user changes the password / signs out other devices for a site (null → the site's home page). */
    fun securityUrl(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        fun has(x: String) = h == x || h.endsWith(".$x")
        return when {
            has("github.com") -> "https://github.com/settings/security"
            has("okx.com") -> "https://www.okx.com/account/security"
            has("binance.com") -> "https://www.binance.com/en/my/security"
            has("aliyun.com") -> "https://account.console.aliyun.com/v2/#/secure"
            has("cloudflare.com") -> "https://dash.cloudflare.com/profile/authentication"
            has("google.com") || has("gmail.com") -> "https://myaccount.google.com/security"
            has("163.com") || has("126.com") -> "https://mail.163.com/"
            has("qq.com") -> "https://aq.qq.com/"
            has("microsoft.com") || has("outlook.com") || has("live.com") -> "https://account.microsoft.com/security"
            has("x.com") || has("twitter.com") -> "https://x.com/settings/security_and_account_access"
            has("apple.com") || has("icloud.com") -> "https://account.apple.com/"
            has("modelscope.cn") -> "https://www.modelscope.cn/my/myaccesstoken"
            h.isBlank() -> "about:blank"
            else -> "https://$h/"
        }
    }

    /** One plain step-by-step guide shown with 「不是我，帮我处理」 (nothing is changed automatically). */
    fun guideFor(host: String): List<String> {
        val h = host.lowercase()
        val base = listOf("改一个新密码（别和别的网站一样）", "在「登录设备 / 会话」里退出不认识的设备", "打开两步验证（可以存在 Mines 里）")
        return when {
            h.contains("okx") || h.contains("binance") -> base + "查一下提币地址白名单和 API Key，删掉不认识的"
            h.contains("github") -> base + "看一下 Personal access tokens 和 SSH keys，删掉不认识的"
            h.contains("aliyun") || h.contains("cloudflare") -> base + "看一下 AccessKey / API Token，删掉不认识的"
            h.contains("google") || h.contains("gmail") || h.contains("163") || h.contains("qq") -> base + "看一下邮件转发和过滤规则有没有被加"
            else -> base
        }
    }
}

package com.openminis.app.plus

/**
 * [Mines-accounts-31] What an account row on 账号连接 says instead of "账号 1":
 * how it was connected + a masked identity, e.g.
 *   "Google 登录 · den•••@gmail.com", "GitHub · ddfc1991", "API Key · sk-•••3f2a".
 * The full identity is only shown on the account's detail page.
 */
object AccountLabel {
    fun method(kind: ConnectionKind, entry: ConnectionEntry?): String {
        val via = entry?.via.orEmpty()
        return when {
            kind.method == ConnMethod.GOOGLE || PlusVault.baseId(kind.id) in setOf("google", "gcal", "gdrive", "youtube") && via != "key" -> "Google 登录"
            kind.method == ConnMethod.GITHUB && via != "key" -> "GitHub"
            kind.method == ConnMethod.MICROSOFT -> "Microsoft 登录"
            kind.method == ConnMethod.CALENDAR -> "手机日历"
            kind.method == ConnMethod.A11Y -> "无障碍"
            via == "key" || via == "token" || kind.method == ConnMethod.KEY -> kind.keyLabel
            via == "chrome" -> "Chrome 已登录"   // [Mines-38.9.4] issue #2: chrome-session rows had no label
            via == "oauth" -> "授权登录"
            via == "app" -> "应用密码"
            via == "web" || kind.method == ConnMethod.WEB -> "网页登录"
            else -> "已连接"
        }
    }

    /**
     * [Mines-privacy-35.3] user 2026-10-14 「还有账号显示，不够隐私」: e-mail "de***40@gmail.com" (first 2 + last 2 of
     * the name, domain kept), phone "136****8658", user names / ids first + last 1–2 characters, keys last 4 only.
     * Everything is masked by default; [Privacy.revealed] (eye icon, 30 s) shows the raw value.
     */
    fun maskEmail(e: String): String {
        val at = e.indexOf('@')
        if (at <= 0) return e
        val name = e.substring(0, at)
        val head = when { name.length <= 2 -> name.take(1); name.length <= 4 -> name.take(1); else -> name.take(2) }
        val tail = if (name.length > 4) name.takeLast(2) else ""
        return head + "***" + tail + e.substring(at)
    }

    /** "••••3f2a" — only the last 4. */
    fun maskSecret(s: String): String {
        val t = s.trim()
        return "••••" + t.takeLast(4.coerceAtMost(maxOf(1, t.length - 4)))
    }

    /** "136****8658" */
    fun maskPhone(p: String): String {
        val d = p.trim().removePrefix("+86")
        return if (d.length >= 7) d.take(3) + "****" + d.takeLast(4) else d.take(1) + "****"
    }

    /** user name / id: first + last 1–2 characters */
    fun maskName(n: String): String {
        val t = n.trim()
        return when {
            t.length <= 1 -> t + "*"
            t.length <= 3 -> t.take(1) + "**"
            t.length <= 6 -> t.take(1) + "***" + t.takeLast(1)
            else -> t.take(2) + "***" + t.takeLast(2)
        }
    }

    fun maskIdentity(raw: String): String {
        val a = raw.trim()
        return when {
            a.contains('@') && !a.startsWith("@") -> maskEmail(a)
            a.matches(Regex("""\+?\d[\d \-]{6,16}""")) -> maskPhone(a.replace(" ", "").replace("-", ""))
            a.startsWith("@") -> "@" + maskName(a.drop(1))
            else -> maskName(a)
        }
    }

    private val emailRx = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val phoneRx = Regex("""(?<!\d)(\+?86)?1[3-9]\d{9}(?!\d)""")
    private val keyRx = Regex("""(?<![A-Za-z0-9])(sk-|sk_|ghp_|gho_|hf_|gsk_|xai-|pplx-|AIza|dop_v1_|napi_|lin_api_|fc-)[A-Za-z0-9_\-]{12,}""")
    /** e-mails, phone numbers and keys inside free text (patrol reasons, notifications, cards) */
    fun maskText(t: String): String = t.replace(keyRx) { maskSecret(it.value) }
        .replace(emailRx) { maskEmail(it.value) }.replace(phoneRx) { m -> maskPhone(m.value.removePrefix("+86").removePrefix("86")) }

    /** masked unless the user pressed the eye icon in the last 30 s */
    fun show(raw: String): String = if (Privacy.isRevealed()) raw else maskIdentity(raw)
    fun showText(raw: String): String = if (Privacy.isRevealed()) raw else maskText(raw)

    /** Full identity, or null when nothing is known. */
    fun identity(id: String, entry: ConnectionEntry?): String? =
        entry?.account?.trim()?.takeIf { it.isNotBlank() && it != "网页登录" }
            ?: PlusVault.extra(id, "identity")?.takeIf { it.isNotBlank() }

    /** Row title: "<method> · <masked identity or masked key>". */
    fun row(id: String, kind: ConnectionKind, entry: ConnectionEntry?): String = method(kind, entry) + " · " + short(id, kind, entry)

    /**
     * [Mines-accounts-35.3] the identity part of a one-line account row: masked identity, else the key's last 4,
     * else the way it is connected. Auto names like "商汤 2" are not identities.
     */
    fun short(id: String, kind: ConnectionKind, entry: ConnectionEntry?): String {
        val base = PlusVault.kind(PlusVault.baseId(id))?.name ?: kind.name
        val ident = identity(id, entry)?.takeUnless { v -> v == base || v.matches(Regex(Regex.escape(base.substringBefore(' ')) + ".*\\s*\\d+")) || v.startsWith(base.substringBefore(' ') + " ") }
        return ident?.let { show(it) }
            ?: PlusVault.secret(id)?.takeIf { entry?.via == "key" || entry?.via == "token" || entry?.via == "model" || kind.method == ConnMethod.KEY }?.let { maskSecret(it) }
            ?: method(kind, entry)
    }
}

/** [Mines-privacy-35.3] the eye icon: raw account identities for 30 s, then masked again. */
object Privacy {
    private val _until = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val until: kotlinx.coroutines.flow.StateFlow<Long> = _until
    fun isRevealed() = System.currentTimeMillis() < _until.value
    fun reveal(ms: Long = 30_000L) { _until.value = System.currentTimeMillis() + ms }
    fun hide() { _until.value = 0L }
    /** adb self-test only: let the smoke take screenshots of the account page */
    @Volatile var secureOffForSelfTest = false
}

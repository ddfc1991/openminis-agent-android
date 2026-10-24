package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:33 「我账号关联这些啊，调用要搞好一点」 + 19:37 priority #1: after linking, every linked
 * account signs in with one tap and stays signed in. The pure rules behind the agent's `accounts` tool, 一键登录 and
 * 一键重新登录 (unit-tested in AccountsCoreTest):
 *  - which linked account a site gets (one → used; several → ask once, then the remembered choice);
 *  - the order of ways in: kept session → saved password (vault fills it) → Google / GitHub (signed in in Mines) → a code
 *    read from the user's own linked mailboxes / SMS;
 *  - challenges: Cloudflare Turnstile / interstitial usually passes by itself in a normal WebView → wait quietly ≤ 15 s;
 *    易盾 / 滑块 / 点字 / reCAPTCHA / hCaptcha need a person → shown to the user; never solved or bypassed by Mines;
 *  - an expired login with a one-tap way in is re-logged automatically; only a confirmed sign-out is 「要重新登录」;
 *  - failure reasons in short plain Chinese;
 *  - nothing secret ever leaves: the tool lists names / addresses / status / ways, never passwords, keys, cookies.
 */
object AccountsCore {
    enum class Way(val label: String) { SESSION("已登录会话"), PASSWORD("保险箱密码"), THIRD_PARTY("第三方登录"), CODE("验证码"), KEY("API 密钥") }

    data class Acct(
        val site: String,            // registrable domain, e.g. "aliyun.com"
        val name: String,            // what the user knows it as, e.g. "阿里云"
        val identity: String,        // account shown to the user (address / user name / masked phone), "" = unknown
        val status: String,          // SessionStatus.Pill name: OK / WARN / BAD / UNCHECKED
        val ways: Set<Way> = emptySet(),
        val providers: List<String> = emptyList(),   // "Google", "GitHub" this account can use here
        val source: String = "",
    ) {
        val key: String get() = site + "|" + identity.lowercase()
    }

    /** Is [host] (or a URL's host) the same site as [site]? (sub-domains count: bailian.console.aliyun.com → aliyun.com) */
    fun sameSite(site: String, host: String): Boolean {
        val s = site.lowercase().removePrefix("www.").trim('.')
        val h = host.lowercase().substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").trim('.')
        if (s.isBlank() || h.isBlank()) return false
        return h == s || h.endsWith(".$s") || s.endsWith(".$h")
    }

    /** Sites that sign in through another one (百炼 / 通义 → 阿里云 account, Gmail → Google …). */
    private val ALIAS = mapOf(
        "aliyun.com" to listOf("bailian.console.aliyun.com", "dashscope.aliyuncs.com", "aliyuncs.com", "tongyi.aliyun.com", "qianwen.com"),
        "google.com" to listOf("gmail.com", "mail.google.com", "youtube.com", "gemini.google.com", "aistudio.google.com"),
        "163.com" to listOf("mail.163.com", "126.com", "yeah.net", "netease.com"),
        "github.com" to listOf("api.github.com", "gist.github.com"),
        "microsoft.com" to listOf("outlook.com", "live.com", "office.com", "hotmail.com"),
    )

    /** The site whose account signs [host] in. */
    fun accountSite(host: String): String {
        val h = host.lowercase().substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.")
        ALIAS.forEach { (site, alias) -> if (sameSite(site, h) || alias.any { sameSite(it, h) }) return site }
        return h
    }

    sealed class Pick {
        data class One(val acct: Acct, val remembered: Boolean = false) : Pick()
        data class Ask(val choices: List<Acct>) : Pick()
        object None : Pick()
    }

    /**
     * The account for [host]: one linked account → it; several → the one the user picked before ([rememberedKey]),
     * else ask once ([Pick.Ask]); a working one (status OK) is preferred over a broken one when there is no choice to make.
     */
    fun pick(host: String, accts: List<Acct>, rememberedKey: String?): Pick {
        val site = accountSite(host)
        val mine = accts.filter { sameSite(it.site, site) || sameSite(it.site, host) }.distinctBy { it.key }
        if (mine.isEmpty()) return Pick.None
        rememberedKey?.let { k -> mine.firstOrNull { it.key == k }?.let { return Pick.One(it, remembered = true) } }
        if (mine.size == 1) return Pick.One(mine[0])
        val named = mine.filter { it.identity.isNotBlank() }.distinctBy { it.identity.lowercase() }
        if (named.size <= 1) return Pick.One(mine.sortedBy { rank(it.status) }.first())
        return Pick.Ask(named.sortedBy { rank(it.status) })
    }

    private fun rank(pill: String) = when (pill) { "OK" -> 0; "UNCHECKED" -> 1; "WARN" -> 2; else -> 3 }

    /**
     * The ways in, in the order the user asked for (19:33): kept session → saved password → Google / GitHub → code.
     * [hasSession] = sign-in cookies are there (live or kept by SessionKeeper); [providers] = providers signed in in
     * Mines that this site offers; [codeSources] = linked mailboxes / SMS that can deliver a code.
     */
    fun order(hasSession: Boolean, hasPassword: Boolean, providers: List<String>, codeSources: List<String>, isKey: Boolean = false): List<Way> {
        if (isKey) return listOf(Way.KEY)
        val out = ArrayList<Way>()
        if (hasSession) out += Way.SESSION
        if (hasPassword) out += Way.PASSWORD
        if (providers.isNotEmpty()) out += Way.THIRD_PARTY
        if (codeSources.isNotEmpty()) out += Way.CODE
        return out
    }

    /** 「会话 → 密码 → Google → 邮箱验证码」 for the card / tool output. */
    fun orderText(ways: List<Way>, providers: List<String>): String = ways.joinToString(" → ") { w ->
        if (w == Way.THIRD_PARTY && providers.isNotEmpty()) providers.joinToString(" / ") else w.label
    }

    enum class Challenge { NONE, SILENT_WAIT, ASK_USER }

    /** Turnstile ≤ 15 s quietly (it usually passes by itself in a real WebView); anything that needs a person is shown. */
    const val TURNSTILE_WAIT_MS = 15_000L

    fun challenge(kind: String): Challenge {
        val k = kind.trim()
        if (k.isBlank()) return Challenge.NONE
        return if (k.equals("Cloudflare", true) || k.contains("Turnstile", true)) Challenge.SILENT_WAIT else Challenge.ASK_USER
    }

    enum class Relogin { NOTHING, AUTO, ASK_USER }

    /**
     * What to do with an account after a check: only a CONFIRMED sign-out acts; with any one-tap way (kept session,
     * saved password, signed-in provider, recorded identity) Mines signs in again by itself, otherwise the user is asked
     * once with the reason. Unknown / network trouble changes nothing (no false 登录失效).
     */
    fun relogin(confirmedOut: Boolean, ways: Collection<Way>, ignored: Boolean): Relogin = when {
        ignored || !confirmedOut -> Relogin.NOTHING
        ways.any { it == Way.SESSION || it == Way.PASSWORD || it == Way.THIRD_PARTY || it == Way.CODE } -> Relogin.AUTO
        else -> Relogin.ASK_USER
    }

    /** Short plain reason for a failed sign-in / key call (what the card and the agent show). */
    fun failReason(raw: String, http: Int = 0): String {
        val t = raw.lowercase()
        return when {
            http == 401 || http == 403 || t.contains("invalid api key") || t.contains("incorrect api key") || t.contains("unauthorized") -> "密钥无效或已被删除（${if (http > 0) http else 401}）"
            http == 402 || t.contains("insufficient") || t.contains("余额不足") || t.contains("quota") && t.contains("exceed") -> "额度用完了"
            http == 429 || t.contains("rate limit") -> "请求太频繁，稍后再试"
            http in 500..599 -> "对方服务器出错（$http）"
            t.contains("captcha") || t.contains("人机验证") || t.contains("易盾") || t.contains("滑块") -> "页面要人机验证，需要你点一下"
            t.contains("密码错误") || t.contains("password is incorrect") || t.contains("wrong password") || t.contains("账号或密码") -> "保存的密码不对了，需要更新"
            t.contains("disallowed_useragent") || t.contains("不支持 webview") || t.contains("may not be secure") -> "Google 不让在 App 内登录，改用其他方式"
            t.contains("未注册") || t.contains("not registered") || t.contains("no account") -> "这个账号在这个网站没注册"
            t.contains("timeout") || t.contains("超时") -> "网页太久没反应"
            t.contains("unable to resolve") || t.contains("net::err") || t.contains("连不上") -> "网络连不上这个网站"
            t.isBlank() -> "没登录上，原因不明"
            else -> raw.trim().lineSequence().first().take(40)
        }
    }

    /**
     * [Mines-38.9.5 i11] Why a hidden re-login did not finish in time (38.9.5 smoke: GitHub / 163 just hung until the caller's
     * own timeout — the user saw nothing). Decided from what the page showed when the deadline hit. Never a bypass: a captcha
     * or a code is handed to the user, with 重试.
     */
    enum class Stuck(val label: String, val needsUser: Boolean) {
        CAPTCHA("需要人机验证", true), CODE("需要验证码", true), PASSWORD("需要账号密码", true),
        NETWORK("网络超时", false), NO_PAGE("登录页打不开", false), SLOW("网页太久没反应", false)
    }
    fun stuck(captcha: Boolean, codeField: Boolean, passwordField: Boolean, netError: String?, pageLoaded: Boolean): Stuck = when {
        captcha -> Stuck.CAPTCHA
        codeField -> Stuck.CODE
        !netError.isNullOrBlank() -> if (Regex("(?i)timed?_?out|超时").containsMatchIn(netError)) Stuck.NETWORK else Stuck.NO_PAGE
        !pageLoaded -> Stuck.NETWORK
        passwordField -> Stuck.PASSWORD
        else -> Stuck.SLOW
    }
    fun stuckLine(root: String, st: Stuck, seconds: Int): String = "$root 自动重新登录没完成（${seconds} 秒）：${st.label}" + when (st) {
        Stuck.CAPTCHA -> " · 在「账号」里打开这个网站，自己点一下验证，再点「重试」"
        Stuck.CODE -> " · 验证码没自动读到，在「账号」里打开这个网站填一下，或点「重试」"
        Stuck.PASSWORD -> " · 保险箱里没有能用的密码，在「账号」里登录一次"
        Stuck.NETWORK, Stuck.NO_PAGE, Stuck.SLOW -> " · 点「重试」再试一次"
    }

    /** 13800138000 → 138****8000; e-mail and user names stay as they are (the agent needs them to type). */
    fun maskPhone(v: String): String {
        val d = v.filter { it.isDigit() }
        return if (d.length in 11..13 && v.none { it == '@' }) d.takeLast(11).let { it.take(3) + "****" + it.takeLast(4) } else v
    }

    private val SECRETISH = Regex("(?i)(sk-[A-Za-z0-9_\\-]{8,}|sk-ws-[A-Za-z0-9_\\-]{8,}|ghp_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|AIza[0-9A-Za-z_\\-]{20,}|am_[A-Za-z0-9]{16,}|eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]+|(password|passwd|pwd|token|secret|cookie)\\s*[:=]\\s*\\S+)")

    /** Last guard on anything the tool returns: key / token / password-looking strings are replaced. */
    fun scrub(s: String): String = SECRETISH.replace(s, "[已隐藏]")

    /** One line per account for the agent (never secrets). */
    fun line(a: Acct, providers: List<String> = a.providers): String {
        val st = when (a.status) { "OK" -> "已登录"; "WARN" -> "要重新登录"; "BAD" -> "异常"; else -> "未检查" }
        val ways = order(Way.SESSION in a.ways, Way.PASSWORD in a.ways, providers, if (Way.CODE in a.ways) listOf("mail") else emptyList(), Way.KEY in a.ways)
        return scrub("- ${a.name}（${a.site}）" + (if (a.identity.isNotBlank()) " 账号 ${maskPhone(a.identity)}" else "") + " · $st" +
            (if (ways.isNotEmpty()) " · 登录方式：" + orderText(ways, providers) else " · 没有可用的登录方式"))
    }
}

package com.openminis.app.plus

/**
 * [Mines-38.9.12] 账号自动调用 — what Mines does when the AI's browser lands on a sign-in page (pure, AutoLoginCoreTest).
 *
 * User 2026-10-22: 「GitHub 已经关联、自动登录了，还让我输密码」. Root cause: since 38.9.11.2 every conversation's agent
 * browser has its own WebView profile (empty cookie jar), and AgentLogin only knew the 台账 password path — an account
 * that signs in 「通过 Google」 has no password, so the secure password card popped. Order now (design ws2 ②):
 *  1. REUSE_SESSION — the site's session that Mines already holds (default jar / saved snapshot) is copied into this
 *     conversation's browser and the page reloads: no password at all;
 *  2. PROVIDER — the account's recorded login method is a third party (Google / GitHub / Apple / Microsoft …) and the page
 *     offers that button → press it (the provider's own session is copied too), pick the account on the chooser;
 *  3. LEDGER_PASSWORD — a 台账 password exists → filled without ever showing it;
 *  4. CARD — only when none of these can work (or the user turned 自动使用 off): the secure login card.
 * An OAuth account never gets the password card before its provider was tried.
 */
object AutoLoginCore {
    enum class Act(val zh: String) {
        REUSE_SESSION("复用已保存的登录会话"), PROVIDER("用第三方登录"), LEDGER_PASSWORD("台账密码自动填入"),
        CARD("弹安全登录框"), NONE("无需处理")
    }

    data class In(
        /** recorded login method for the site: google / github / apple / microsoft / x / password / code / "" */
        val method: String,
        /** Mines holds session cookies for the site that this browser does not have yet */
        val sessionAvailable: Boolean,
        /** a session copy was already tried on this site in this conversation recently */
        val sessionTried: Boolean,
        /** label of the page button for [method]'s provider (null = not on the page) */
        val providerButton: String?,
        /** the provider button was already pressed recently */
        val providerTried: Boolean,
        val ledgerPassword: Boolean,
        /** policy 「任务需要登录时自动使用台账」 */
        val autoUse: Boolean = true,
    )

    val OAUTH = setOf("google", "github", "apple", "microsoft", "x", "oauth")

    fun isOAuth(method: String) = method.lowercase() in OAUTH

    fun decide(i: In): Act {
        if (!i.autoUse) return Act.CARD
        if (i.sessionAvailable && !i.sessionTried) return Act.REUSE_SESSION
        if (isOAuth(i.method) && i.providerButton != null && !i.providerTried) return Act.PROVIDER
        if (i.ledgerPassword) return Act.LEDGER_PASSWORD
        return Act.CARD
    }

    /** the provider's login host (its own session is copied into the conversation browser before the button is pressed) */
    fun providerHosts(method: String): List<String> = when (method.lowercase()) {
        "google" -> listOf("accounts.google.com", "google.com", "www.google.com")
        "github" -> listOf("github.com")
        "apple" -> listOf("appleid.apple.com", "apple.com")
        "microsoft" -> listOf("login.microsoftonline.com", "login.live.com", "microsoft.com")
        "x" -> listOf("x.com", "twitter.com")
        else -> emptyList()
    }

    private fun rx(name: String) = Regex(
        "(?i)^(\\s*(continue|sign in|log in|login|sign up)\\s+(with|using|via)\\s+$name\\s*|\\s*使用\\s*$name\\s*(账号|帐号)?(登录|继续)?\\s*|\\s*$name\\s*(账号|帐号)?\\s*登录\\s*|\\s*通过\\s*$name\\s*(登录|继续)\\s*|\\s*用\\s*$name\\s*(登录|继续)\\s*|\\s*$name\\s*)$")

    private val NAMES = mapOf(
        "google" to "(google|谷歌)", "github" to "github", "apple" to "(apple|苹果)", "microsoft" to "(microsoft|微软|outlook)",
        "x" to "(x|twitter|推特)",
    )

    /** the visible button label that signs in with [method]'s provider, or null */
    fun matchProvider(buttons: List<String>, method: String): String? {
        val n = NAMES[method.lowercase()] ?: return null
        val r = rx(n)
        return buttons.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 36 }.firstOrNull { r.matches(it) }
    }

    /** LoginMemoryCore / IdentityCore method → the id used here */
    fun methodId(memo: String?, recProvider: String?, recMethodOauth: Boolean): String {
        val m = memo?.lowercase().orEmpty()
        if (m in setOf("google", "github")) return m
        if (recMethodOauth && !recProvider.isNullOrBlank()) return recProvider.lowercase()
        if (m == "oauth" && !recProvider.isNullOrBlank()) return recProvider.lowercase()
        if (m == "password" || m == "code") return m
        return recProvider?.lowercase()?.takeIf { recMethodOauth }.orEmpty()
    }

    /** exchange / wallet sites: the account is used read-only (policy 「交易所账号 只读调用」) */
    private val EXCHANGES = listOf("okx.com", "binance.com", "bybit.com", "gate.io", "gate.com", "huobi.com", "htx.com", "kucoin.com",
        "bitget.com", "mexc.com", "coinbase.com", "kraken.com")
    fun isExchange(host: String): Boolean { val h = host.lowercase().removePrefix("www."); return EXCHANGES.any { h == it || h.endsWith(".$it") } }

    // ── recent-use log (shown on the 账号自动调用 page) ──
    data class LogLine(val at: Long, val site: String, val how: String, val auto: Boolean, val ok: Boolean? = null)

    fun logText(l: LogLine): String = l.site + " · " + l.how + " · " + (if (l.auto) "自动" else "询问") +
        (when (l.ok) { true -> " · 成功"; false -> " · 未成功"; null -> "" })

    fun push(log: List<LogLine>, l: LogLine, cap: Int = 30): List<LogLine> {
        val last = log.firstOrNull()
        val merged = if (last != null && last.site == l.site && last.how == l.how && l.at - last.at < 60_000L) log.drop(1) else log
        return (listOf(l) + merged).take(cap)
    }

    /** method label for the UI: 「Google 登录」 / 「密码」 */
    fun howLabel(act: Act, method: String): String = when (act) {
        Act.REUSE_SESSION -> "复用会话"
        Act.PROVIDER -> when (method.lowercase()) { "google" -> "Google 登录"; "github" -> "GitHub 登录"; "apple" -> "Apple 登录"; "microsoft" -> "Microsoft 登录"; "x" -> "X 登录"; else -> "第三方登录" }
        Act.LEDGER_PASSWORD -> "密码"
        Act.CARD -> "安全登录框"
        Act.NONE -> ""
    }
}

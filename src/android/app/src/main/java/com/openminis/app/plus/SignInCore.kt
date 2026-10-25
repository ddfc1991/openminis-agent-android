package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.15] Account picker by HOW an account signs in (user 2026-10-25 12:26 / 12:35, after Mines looped
 * 「重试 CF 自动登录」 64 times on Cloudflare):
 *  - every saved account belongs to one sign-in group: 「Google 登录」/「GitHub 登录」/「邮箱+密码」/「手机号+验证码」/
 *    「API Key」/「其他」;
 *  - every site links to ONE group + account (remembered after the first success, or set by the user in 账号台账), so the
 *    auto-login tries that way first and never the same failing (account, way) twice;
 *  - at most [MAX_AUTO] automatic sign-in attempts per site per task, then 等你处理 with a handoff — no loop.
 * Pure; unit-tested in SignInCoreTest. Android side: [SignIn].
 */
object SignInCore {
    enum class Group(val id: String, val zh: String, val order: Int) {
        GOOGLE("google", "Google 登录", 0), GITHUB("github", "GitHub 登录", 1), EMAIL_PW("email_pw", "邮箱+密码", 2),
        EMAIL_CODE("email_code", "邮箱+验证码", 3), PHONE_CODE("phone_code", "手机号+验证码", 4), USER_PW("user_pw", "账号+密码", 5),
        API_KEY("api_key", "API Key", 6), OTHER("other", "其他", 7);
        companion object { fun of(id: String?): Group? = values().firstOrNull { it.id == id } }
    }

    /** the group of one saved account, from what Mines knows about how it signs in */
    fun groupOf(method: IdentityCore.Method, provider: IdentityCore.IdType?, account: String, hasPw: Boolean, isKey: Boolean = false): Group = when {
        isKey || method == IdentityCore.Method.KEY -> Group.API_KEY
        method == IdentityCore.Method.OAUTH && provider == IdentityCore.IdType.GOOGLE -> Group.GOOGLE
        method == IdentityCore.Method.OAUTH && provider == IdentityCore.IdType.GITHUB -> Group.GITHUB
        method == IdentityCore.Method.PHONE_SMS || method == IdentityCore.Method.PHONE_PW && !hasPw -> Group.PHONE_CODE
        method == IdentityCore.Method.EMAIL_PW -> Group.EMAIL_PW
        method == IdentityCore.Method.EMAIL_CODE -> Group.EMAIL_CODE
        method == IdentityCore.Method.USER_PW || method == IdentityCore.Method.PHONE_PW -> if (isEmail(account)) Group.EMAIL_PW else Group.USER_PW
        isPhone(account) -> Group.PHONE_CODE
        isEmail(account) && hasPw -> Group.EMAIL_PW
        isEmail(account) -> Group.EMAIL_CODE
        hasPw -> Group.USER_PW
        else -> Group.OTHER
    }

    private val EMAIL = Regex("^[^@\\s]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$")
    fun isEmail(s: String) = EMAIL.matches(s.trim())
    fun isPhone(s: String) = s.filter { it.isDigit() }.length in 11..13 && s.trim().all { it.isDigit() || it in "+- " }

    /** the IdentityCore methods a group means on a sign-in page */
    fun methodsOf(g: Group): Set<IdentityCore.Method> = when (g) {
        Group.GOOGLE, Group.GITHUB -> setOf(IdentityCore.Method.OAUTH)
        Group.EMAIL_PW -> setOf(IdentityCore.Method.EMAIL_PW, IdentityCore.Method.USER_PW)
        Group.EMAIL_CODE -> setOf(IdentityCore.Method.EMAIL_CODE)
        Group.PHONE_CODE -> setOf(IdentityCore.Method.PHONE_SMS)
        Group.USER_PW -> setOf(IdentityCore.Method.USER_PW, IdentityCore.Method.PHONE_PW)
        Group.API_KEY -> setOf(IdentityCore.Method.KEY)
        Group.OTHER -> emptySet()
    }

    fun providerOf(g: Group): IdentityCore.IdType? = when (g) { Group.GOOGLE -> IdentityCore.IdType.GOOGLE; Group.GITHUB -> IdentityCore.IdType.GITHUB; else -> null }

    /** a site's link: which group, which account */
    data class Link(val group: Group, val account: String, val byUser: Boolean = false)

    fun linkKey(site: String) = site.lowercase().removePrefix("www.").trim()

    fun linksToJson(m: Map<String, Link>): String = JSONObject().apply {
        m.forEach { (k, v) -> put(k, JSONObject().put("g", v.group.id).put("a", v.account).put("u", v.byUser)) }
    }.toString()

    fun linksFromJson(s: String?): Map<String, Link> = runCatching {
        val o = JSONObject(s ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { v -> Group.of(v.optString("g"))?.let { k to Link(it, v.optString("a"), v.optBoolean("u")) } } }.toMap()
    }.getOrDefault(emptyMap())

    /** one candidate way in: (account, group) */
    data class Way(val account: String, val group: Group) { val key: String get() = account.lowercase() + "|" + group.id }

    /**
     * The order to try, given the site's accounts: the linked (account, group) first, then the rest by group order;
     * a way that already failed in this task is dropped, so the same failing path is never retried.
     */
    fun order(cands: List<Way>, link: Link?, failed: Set<String>): List<Way> {
        val live = cands.distinctBy { it.key }.filter { it.key !in failed && it.group != Group.API_KEY && it.group != Group.OTHER }
        val first = link?.let { l -> live.firstOrNull { it.group == l.group && it.account.equals(l.account, true) } }
        return listOfNotNull(first) + live.filter { it != first }.sortedBy { it.group.order }
    }

    /**
     * [38.9.15] user 12:59: a site with more than one saved account, none linked and the task naming none → the USER picks
     * (card in chat). Never auto-pick among several unlinked accounts. [named] = the account the task/user named.
     */
    fun needsPick(cands: List<Way>, link: Link?, named: String?): Boolean {
        if (link != null || !named.isNullOrBlank()) return false
        return pickChoices(cands).map { it.account.lowercase() }.distinct().size > 1
    }

    /** the rows of the picker card: one per (account, group), API keys / unknown ways left out, by group order */
    fun pickChoices(cands: List<Way>): List<Way> =
        cands.distinctBy { it.key }.filter { it.group != Group.API_KEY && it.group != Group.OTHER }.sortedWith(compareBy({ it.group.order }, { it.account.lowercase() }))

    /** the ways of the account the task named (any of its groups), else all */
    fun forNamed(cands: List<Way>, named: String?): List<Way> =
        if (named.isNullOrBlank()) cands else cands.filter { it.account.equals(named.trim(), true) }.ifEmpty { cands }

    const val MAX_AUTO = 2

    enum class Next { TRY, HANDOFF }

    /** before an automatic sign-in: try again, or stop and hand the page to the user */
    fun next(attempts: Int, remaining: Int): Next = if (attempts >= MAX_AUTO || remaining == 0) Next.HANDOFF else Next.TRY

    fun handoffLine(site: String, attempts: Int, tried: List<Way>): String =
        "$site 自动登录已经试了 $attempts 次没进去（" + tried.joinToString("；") { "${it.group.zh} · ${it.account}" }.ifBlank { "没有可用的方式" } +
            "）。已转成「等你处理」：请你在页面上登录一次，Mines 会记住这个账号用的方式。不要再调用 accounts login 或反复打开登录页。"
}

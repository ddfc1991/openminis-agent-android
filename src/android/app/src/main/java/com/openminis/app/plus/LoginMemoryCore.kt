package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] user 2026-10-17 22:42 / 22:43: 「登录新的东西…第一次就搞成你们那种输入进去的」「这个账号关联有记录，下次就可以不用那么麻烦了」.
 *
 * Pure rules (LoginMemoryCoreTest):
 *  - [Memo]: what a successful sign-in leaves behind per site — account, method, provider, which mailbox the code came
 *    in, whether an encrypted session snapshot exists, and whether the user picked this account among several.
 *  - [replay]: next time → reuse the saved session silently → silent re-login with the saved method → the secure login
 *    card (only if both fail).
 *  - [cardFields]: the card asks only for what the page wants (account / password / code), the code only after ~30 s with
 *    no code from the linked mailboxes / SMS, and 「密码不对，重新输入」 when the saved password was refused.
 */
object LoginMemoryCore {
    enum class Method(val id: String, val label: String) {
        PASSWORD("password", "密码"), GOOGLE("google", "Google"), GITHUB("github", "GitHub"), CODE("code", "验证码"),
        OTHER_OAUTH("oauth", "第三方"), WEB("web", "网页");
        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: WEB }
    }

    data class Memo(
        val site: String,
        val account: String,
        val method: Method,
        /** label of the mailbox the last code arrived in ("Gmail", "163 邮箱"…), "" when SMS / none. */
        val codeVia: String = "",
        val hasSession: Boolean = false,
        /** the user chose this account when the site has several */
        val chosen: Boolean = false,
        val hasPassword: Boolean = false,
        val at: Long = 0L,
    )

    enum class Step { REUSE_SESSION, SILENT_RELOGIN, CARD }

    /** The account to use for [site] when several are remembered: the one the user picked, else the most recent. */
    fun pick(memos: List<Memo>, site: String): Memo? =
        memos.filter { it.site == site }.sortedWith(compareByDescending<Memo> { it.chosen }.thenByDescending { it.at }).firstOrNull()

    /** True when [m] can sign in again without asking the user anything. */
    fun canSilent(m: Memo): Boolean = when (m.method) {
        Method.PASSWORD -> m.hasPassword && m.account.isNotBlank()
        Method.GOOGLE, Method.GITHUB, Method.OTHER_OAUTH -> true      // the provider's own session in the WebView
        Method.CODE -> m.account.isNotBlank()                         // code read from the linked mailbox / SMS
        Method.WEB -> false
    }

    /** Next-time order for a site. Unknown site → straight to the card. */
    fun replay(m: Memo?): List<Step> {
        if (m == null) return listOf(Step.CARD)
        val out = ArrayList<Step>()
        if (m.hasSession) out += Step.REUSE_SESSION
        if (canSilent(m)) out += Step.SILENT_RELOGIN
        out += Step.CARD
        return out
    }

    /** Merge a new success into the list: one memo per (site, account); a new [chosen] clears the others' flag on that site. */
    fun record(memos: List<Memo>, m: Memo): List<Memo> {
        val rest = memos.filterNot { it.site == m.site && it.account.equals(m.account, ignoreCase = true) }
            .map { if (m.chosen && it.site == m.site) it.copy(chosen = false) else it }
        val prev = memos.firstOrNull { it.site == m.site && it.account.equals(m.account, ignoreCase = true) }
        val merged = if (prev == null) m else m.copy(
            codeVia = m.codeVia.ifBlank { prev.codeVia },
            hasPassword = m.hasPassword || prev.hasPassword,
            chosen = m.chosen || prev.chosen,
        )
        return (rest + merged).sortedByDescending { it.at }.take(300)
    }

    /** 「上次用 sm***@example.com 登录 · 密码」 (account masked in the middle). */
    fun lastLine(m: Memo?): String? {
        if (m == null || m.account.isBlank()) return null
        val via = when (m.method) {
            Method.CODE -> if (m.codeVia.isNotBlank()) " · 验证码（${m.codeVia}）" else " · 验证码"
            Method.WEB -> ""
            else -> " · " + m.method.label
        }
        return "上次用 ${maskAccount(m.account)} 登录$via"
    }

    fun maskAccount(a: String): String {
        val t = a.trim()
        val at = t.indexOf('@')
        if (at > 0) {
            val user = t.substring(0, at)
            val keep = if (user.length <= 2) user.take(1) else user.take(2)
            return keep + "***" + t.substring(at)
        }
        val d = t.filter { it.isDigit() }
        if (d.length >= 7 && d.length == t.length) return d.take(3) + "****" + d.takeLast(4)
        return if (t.length <= 3) t.take(1) + "**" else t.take(2) + "***" + t.takeLast(1)
    }

    // ───────────────────────── the secure login card ─────────────────────────

    enum class Field(val label: String) { ACCOUNT("账号"), EMAIL("邮箱"), PHONE("手机号"), PASSWORD("密码"), CODE("验证码") }

    /** What the sign-in page shows (from LoginAssist.FIELDS_JS). [user] = "phone" / "email" / "user" / "". */
    data class Page(val user: String, val password: Boolean, val code: Boolean)

    data class Ask(val fields: List<Field>, val title: String, val refused: Boolean)

    const val CODE_WAIT_MS = 30_000L

    /**
     * Whether to show the card now and with which fields. [haveAccount] / [havePassword]: something saved to fill;
     * [codeWaitMs]: how long the page has been waiting for a code with none arriving (−1 = not waiting);
     * [refused]: the page rejected the saved password.
     */
    fun cardFields(page: Page, haveAccount: Boolean, havePassword: Boolean, codeWaitMs: Long, refused: Boolean, siteName: String): Ask? {
        val acct = when (page.user) { "phone" -> Field.PHONE; "email" -> Field.EMAIL; "user" -> Field.ACCOUNT; else -> null }
        if (refused && page.password) return Ask(listOfNotNull(acct, Field.PASSWORD), "密码不对，重新输入", true)
        val f = ArrayList<Field>()
        if (acct != null && !haveAccount) f += acct
        if (page.password && !havePassword) { if (acct != null && acct !in f) f += acct; f += Field.PASSWORD }
        if (page.code && codeWaitMs >= CODE_WAIT_MS) f += Field.CODE
        if (f.isEmpty()) return null
        return Ask(f.distinct(), if (f == listOf(Field.CODE)) "输入验证码" else "登录 $siteName", false)
    }
}

package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] 台账 (the 密码库 accounts): which saved account to use on a site — decided by Mines / the AI, not asked.
 * User 2026-10-21: an account that is in the 台账 never pops the password card; it is filled in ANY conversation. With
 * several accounts for one site the order is:
 *  1. an account / e-mail the user named in this conversation (messages or task);
 *  2. the account already bound to this conversation;
 *  3. a 备注 that matches the task (主号 / 测试 / 工作 / a project name mentioned in the chat);
 *  4. the account last used successfully on that site;
 *  5. the one marked 默认 for that platform;
 *  then the first saved. Ask only when nothing distinguishes them AND the choice matters (paying).
 * Pure, unit-tested (LedgerCoreTest). Passwords never pass through here.
 */
object LedgerCore {
    data class Cand(
        val id: String, val host: String, val account: String, val note: String = "",
        val lastOkAt: Long = 0L, val isDefault: Boolean = false,
    )

    enum class Why(val zh: String) {
        NAMED("你在对话里提到了它"), BOUND("这个对话之前用的就是它"), NOTE("备注和任务对得上"),
        LAST_OK("上次在这个网站用它登录成功"), DEFAULT("它是这个平台的默认账号"), ONLY("这个网站只存了这一个"), FIRST("没有别的区分，用了第一个")
    }

    data class Pick(val cand: Cand?, val why: Why?, val ask: Boolean, val ordered: List<Cand>)

    private val MULTI_TLD = setOf("com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "co.uk", "com.hk", "com.tw", "co.jp", "com.au")

    /** registrable root: a.b.example.com → example.com, x.taobao.com.cn → taobao.com.cn */
    fun root(host: String): String {
        val p = host.lowercase().trim().removePrefix("www.").trimEnd('.').split('.').filter { it.isNotBlank() }
        if (p.size <= 2) return p.joinToString(".")
        val last2 = p.takeLast(2).joinToString(".")
        return if (last2 in MULTI_TLD && p.size >= 3) p.takeLast(3).joinToString(".") else last2
    }

    /** same site: exact host, or same registrable domain (login.example.com ↔ example.com) */
    fun sameSite(entryHost: String, pageHost: String): Boolean {
        val a = entryHost.lowercase().removePrefix("www."); val b = pageHost.lowercase().removePrefix("www.")
        if (a.isBlank() || b.isBlank()) return false
        return a == b || root(a) == root(b)
    }

    fun forSite(all: List<Cand>, pageHost: String): List<Cand> =
        all.filter { sameSite(it.host, pageHost) }.distinctBy { it.host.lowercase() + "|" + it.account.lowercase() }

    /** words in a 备注 worth matching against the task (主号 / 测试 / 工作 / project names; ≥ 2 chars) */
    fun noteWords(note: String): List<String> =
        note.lowercase().split(Regex("[\\s,，、/|;；:：()（）]+")).map { it.trim() }.filter { it.length >= 2 }

    private fun named(text: String, account: String): Boolean {
        val a = account.trim().lowercase(); if (a.length < 3) return false
        val t = text.lowercase()
        if (a in t) return true
        // the local part of an e-mail ("用 deningyang740 那个号")
        val local = a.substringBefore('@'); return a.contains('@') && local.length >= 4 && local in t
    }

    /**
     * [convText] = this conversation's user messages + the task; [bound] = account bound to this conversation (ConvProfiles);
     * [choiceMatters] = money / irreversible (支付 / 付款 / 充值 …).
     */
    fun pick(cands: List<Cand>, convText: String, bound: String?, choiceMatters: Boolean = false): Pick {
        if (cands.isEmpty()) return Pick(null, null, false, emptyList())
        if (cands.size == 1) return Pick(cands[0], Why.ONLY, false, cands)
        val text = convText.take(20_000)
        fun score(c: Cand): Pair<Int, Why?> = when {
            named(text, c.account) -> 5 to Why.NAMED
            bound != null && c.account.equals(bound, ignoreCase = true) -> 4 to Why.BOUND
            noteWords(c.note).any { it in text.lowercase() } -> 3 to Why.NOTE
            else -> 0 to null
        }
        val scored = cands.map { it to score(it) }
        val top = scored.maxOf { it.second.first }
        if (top > 0) {
            val best = scored.filter { it.second.first == top }
            if (best.size == 1) return Pick(best[0].first, best[0].second.second, false, order(cands, best[0].first))
            // tie on the same rule: last success, then default, among the tied ones
            return tieBreak(best.map { it.first }, cands, choiceMatters, best[0].second.second)
        }
        return tieBreak(cands, cands, choiceMatters, null)
    }

    private fun tieBreak(tied: List<Cand>, all: List<Cand>, choiceMatters: Boolean, rule: Why?): Pick {
        val lastOk = tied.filter { it.lastOkAt > 0 }.maxByOrNull { it.lastOkAt }
        val withLast = tied.count { it.lastOkAt > 0 }
        if (lastOk != null && (withLast == 1 || tied.filter { it.lastOkAt == lastOk.lastOkAt }.size == 1)) return Pick(lastOk, rule ?: Why.LAST_OK, false, order(all, lastOk))
        val defs = tied.filter { it.isDefault }
        if (defs.size == 1) return Pick(defs[0], rule ?: Why.DEFAULT, false, order(all, defs[0]))
        // nothing distinguishes them
        return if (choiceMatters) Pick(null, null, true, all) else Pick(tied[0], rule ?: Why.FIRST, false, order(all, tied[0]))
    }

    private fun order(all: List<Cand>, first: Cand) = listOf(first) + all.filter { it !== first }

    /** does this task involve money / something that cannot be undone (then an indistinct choice is asked) */
    fun choiceMatters(task: String): Boolean = Regex("支付|付款|付钱|购买|下单|充值|转账|打款|提现|pay|purchase|checkout|transfer", RegexOption.IGNORE_CASE).containsMatchIn(task)

    /** the chip text: 「已用 台账：a@b.com」 */
    fun chip(c: Cand): String = "已用 台账：" + c.account.ifBlank { c.host }

    /** one line for the agent per account — never a password */
    fun line(c: Cand, here: Boolean, now: Long): String = buildString {
        append("[").append(c.id).append("] ").append(c.host).append(" · ").append(c.account.ifBlank { "（无账号名）" })
        if (c.note.isNotBlank()) append(" · 备注：").append(c.note.take(40))
        if (c.isDefault) append(" · 默认")
        if (here) append(" · 本对话在用")
        if (c.lastOkAt > 0) append(" · 上次用 ").append(ago(now - c.lastOkAt))
    }

    fun ago(ms: Long): String = when {
        ms < 3_600_000L -> "${(ms / 60_000L).coerceAtLeast(1)} 分钟前"
        ms < 86_400_000L -> "${ms / 3_600_000L} 小时前"
        else -> "${ms / 86_400_000L} 天前"
    }

    /** stable id for an account (no secret in it) */
    fun idOf(host: String, account: String): String {
        val s = (root(host) + "|" + account.lowercase()).toByteArray()
        var h = 1125899906842597L; for (b in s) h = 31 * h + b
        return "acct_" + java.lang.Long.toString(h and 0x7fffffffffffL, 36)
    }
}

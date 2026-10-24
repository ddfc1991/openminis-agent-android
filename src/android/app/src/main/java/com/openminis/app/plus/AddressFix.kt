package com.openminis.app.plus

/**
 * [Mines-38.9.1] user 2026-10-16 「拿过都说不行？」: the saved Gmail address had one extra digit (typed by hand on the phone —
 * nothing in the APK holds an address), so the saved, valid app password failed every IMAP login and Mines asked for a
 * new password. Pure part: which known address (signed-in Google account, other mailboxes in the identity store) the
 * stored one is a near-typo of. The app then tries the saved password with that address and, only if the real login
 * succeeds, rewrites the address (and the add-mailbox default) — the password is never re-asked. Unit-tested.
 */
object AddressFix {
    /** last automatic fix, shown once by the 邮箱 page / 账号连接 */
    @Volatile var fixedNote: String? = null

    fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) { var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }
        return d[b.length]
    }

    /** Known addresses the stored one may be a typo of: same domain, 1–2 edits in the local part, closest first. */
    fun candidates(stored: String, known: Collection<String>): List<String> {
        val s = stored.trim().lowercase()
        if (!s.contains('@')) return emptyList()
        val (sl, sd) = s.substringBefore('@') to s.substringAfter('@')
        return known.map { it.trim().lowercase() }.filter { it.contains('@') && it != s && it.substringAfter('@') == sd }
            .map { it to distance(it.substringBefore('@'), sl) }.filter { it.second in 1..2 }.sortedBy { it.second }.map { it.first }.distinct()
    }

    /** Plain reason for an IMAP refusal with an address that looks wrong. */
    fun reason(stored: String, known: Collection<String>): String =
        candidates(stored, known).firstOrNull()?.let { "地址和应用专用密码对不上：你填的是 $stored，Mines 里登录的是 $it，地址可能多打或少打了一个字" }
            ?: "地址和应用专用密码对不上，请检查地址（$stored），或在 Google 里看看这个应用专用密码是不是被撤销了"
}

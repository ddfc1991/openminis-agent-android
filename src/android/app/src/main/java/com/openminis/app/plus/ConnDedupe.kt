package com.openminis.app.plus

/**
 * [Mines-38.9.4] issue #2 Bug 2: `minis-connect list` showed cloudflare twice — via=key (not default) and via=chrome
 * (default) — both feeding CLOUDFLARE_API_TOKEN. Two entries of one service are the SAME account when they do not carry
 * different keys and do not name different identities; the extra one is folded into the default entry (its key moves
 * over when the default has none) and removed. Genuine second accounts (another key / another identity) stay.
 */
object ConnDedupe {
    data class E(val id: String, val via: String, val account: String, val secret: String?)
    data class Fold(val keep: String, val drop: String, val moveSecret: Boolean, val account: String?)

    private val generic = setOf("", "网页登录", "网页登录（自动发现）", "Chrome 已登录", "通过无障碍操作", "已连接")
    private fun named(a: String) = a.trim().takeIf { it !in generic }

    fun base(id: String) = id.substringBefore('#')

    /** [defaultOf] = the default entry id of a base (null → the first). */
    fun plan(entries: List<E>, defaultOf: (String) -> String?): List<Fold> {
        val out = ArrayList<Fold>()
        for ((b, group) in entries.groupBy { base(it.id) }) {
            if (group.size < 2) continue
            val keepId = defaultOf(b)?.takeIf { d -> group.any { it.id == d } } ?: group.sortedBy { it.id.substringAfter('#', "1").toIntOrNull() ?: 1 }.first().id
            var keep = group.first { it.id == keepId }
            for (o in group) {
                if (o.id == keep.id) continue
                val s1 = keep.secret?.takeIf { it.isNotBlank() }; val s2 = o.secret?.takeIf { it.isNotBlank() }
                if (s1 == null && s2 == null) continue                           // two web sessions: separate cookie jars, keep both
                if (s1 != null && s2 != null && s1 != s2) continue              // two different keys = two accounts
                val n1 = named(keep.account); val n2 = named(o.account)
                if (n1 != null && n2 != null && !n1.equals(n2, ignoreCase = true)) continue   // two identities
                val move = s1 == null && s2 != null
                val acct = if (n1 == null && n2 != null) n2 else null
                out += Fold(keep.id, o.id, move, acct)
                keep = keep.copy(secret = s1 ?: s2, account = acct ?: keep.account)
            }
        }
        return out
    }
}

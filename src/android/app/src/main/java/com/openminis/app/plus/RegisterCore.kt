package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] 多号注册 (user 2026-10-21): when the agent registers several accounts on a site it takes the e-mails
 * from the 台账 itself — never asks the user — picking ones NOT yet registered on that site, mailboxes Mines can read codes
 * from first, least-used first; every new account goes back into the 台账 (site, e-mail, secret id, 备注 「注册于 日期」).
 * Pure, unit-tested (RegisterCoreTest). No password passes through here.
 */
object RegisterCore {
    data class Mail(val addr: String, val readable: Boolean = false, val sites: Int = 0)

    private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    fun isEmail(s: String) = EMAIL.matches(s.trim())

    /**
     * The e-mail pool: every address known to the 台账 ([ledger] = (host, account) pairs), identities and code mailboxes,
     * deduplicated (case-insensitive); [readable] = addresses whose mailbox Mines can read codes from.
     */
    fun pool(ledger: List<Pair<String, String>>, identities: List<String>, readable: Set<String>): List<Mail> {
        val count = HashMap<String, Int>(); val first = LinkedHashMap<String, String>()
        ledger.forEach { (_, a) -> if (isEmail(a)) { val k = a.trim().lowercase(); first.putIfAbsent(k, a.trim()); count[k] = (count[k] ?: 0) + 1 } }
        (identities + readable).forEach { a -> if (isEmail(a)) first.putIfAbsent(a.trim().lowercase(), a.trim()) }
        val rd = readable.map { it.trim().lowercase() }.toSet()
        return first.map { (k, v) -> Mail(v, k in rd, count[k] ?: 0) }
    }

    /** e-mails already used on [site] (same registrable domain), from the 台账 */
    fun usedOn(ledger: List<Pair<String, String>>, site: String): Set<String> =
        ledger.filter { (h, _) -> LedgerCore.sameSite(h, site) }.map { it.second.trim().lowercase() }.toSet()

    /** up to [n] e-mails for registering on [site]: unused there, readable mailboxes first, then the least used overall */
    fun pick(pool: List<Mail>, used: Set<String>, n: Int, exclude: Set<String> = emptySet()): List<Mail> {
        val skip = used + exclude.map { it.lowercase() }
        return pool.filter { it.addr.lowercase() !in skip }
            .sortedWith(compareByDescending<Mail> { it.readable }.thenBy { it.sites }.thenBy { it.addr.lowercase() })
            .take(n.coerceIn(0, 20))
    }

    /** the 台账 备注 of a new account */
    fun note(date: String, ok: Boolean, extra: String = ""): String =
        (if (ok) "注册于 $date" else "注册中 $date") + (if (extra.isNotBlank()) " · " + extra.take(30) else "")

    /** a strong password: 16 chars, upper + lower + digit + symbol, from [rnd] (SecureRandom on the device) */
    fun password(rnd: java.util.Random, len: Int = 16): String {
        val U = "ABCDEFGHJKLMNPQRSTUVWXYZ"; val L = "abcdefghijkmnpqrstuvwxyz"; val D = "23456789"; val S = "!@#%^*-_+"
        val all = U + L + D + S
        val cs = mutableListOf(U[rnd.nextInt(U.length)], L[rnd.nextInt(L.length)], D[rnd.nextInt(D.length)], S[rnd.nextInt(S.length)])
        while (cs.size < len.coerceAtLeast(8)) cs += all[rnd.nextInt(all.length)]
        for (i in cs.indices.reversed()) { val j = rnd.nextInt(i + 1); val t = cs[i]; cs[i] = cs[j]; cs[j] = t }
        return cs.joinToString("")
    }
}

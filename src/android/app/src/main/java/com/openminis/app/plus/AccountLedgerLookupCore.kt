package com.openminis.app.plus

/** Deterministic account-ledger lookup shared by the local CLI. Never pick the first fuzzy hit. */
object AccountLedgerLookupCore {
    data class Result(val entries: List<AutoAccounts.Entry>, val exact: Boolean)

    fun find(entries: List<AutoAccounts.Entry>, query: String): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result(emptyList(), exact = false)
        val exact = entries.filter { it.site.equals(q, ignoreCase = true) || it.host.equals(q, ignoreCase = true) }
        if (exact.isNotEmpty()) return Result(stable(exact), exact = true)
        val fuzzy = entries.filter {
            it.site.contains(q, ignoreCase = true) || it.host.contains(q, ignoreCase = true)
        }
        return Result(stable(fuzzy), exact = false)
    }

    private fun stable(entries: List<AutoAccounts.Entry>) = entries.sortedWith(
        compareBy<AutoAccounts.Entry>({ it.site.lowercase() }, { it.host.lowercase() }, { it.account.lowercase() }, { it.at }),
    )
}

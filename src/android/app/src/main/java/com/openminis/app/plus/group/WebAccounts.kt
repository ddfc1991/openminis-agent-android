package com.openminis.app.plus.group

/**
 * [Mines-38.7] Several accounts per AI web app (user 2026-10-15 「如果是多账号的，一定要做好隔离」).
 *
 * Member ids: "web:doubao" = the first account, "web:doubao#2", "web:doubao#3" … = more accounts. Each one is its own
 * group member with its own sign-in (cookies / localStorage isolated by [com.openminis.app.plus.AccountJar]), its own
 * web conversation and its own 已登录 state. When a platform has more than one account, every one of them is shown as
 * 「豆包·账号A」, 「豆包·账号B」 … (a name the user gave wins).
 */
object WebAccounts {
    fun site(id: String) = id.substringBefore('#')
    fun number(id: String) = id.substringAfter('#', "1").toIntOrNull() ?: 1

    /** Account member ids of [site] (always at least the first). */
    fun ids(site: String): List<String> {
        val extra = GroupPrefs.get("webaccts_$site")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 1 }?.distinct()?.sorted().orEmpty()
        return listOf(site) + extra.map { "$site#$it" }
    }

    /** Adds one more account slot for [site]; returns its member id (then the user signs it in). */
    fun add(site: String): String {
        val cur = ids(site).map { number(it) }
        val n = (cur.maxOrNull() ?: 1) + 1
        GroupPrefs.put("webaccts_$site", (cur.filter { it > 1 } + n).joinToString(","))
        return "$site#$n"
    }

    /** Drops an extra account slot (the first account stays, only signed out). */
    fun drop(id: String) {
        if (!id.contains('#')) return
        val s = site(id)
        GroupPrefs.put("webaccts_$s", ids(s).filter { it != id && it.contains('#') }.map { number(it) }.joinToString(",").ifBlank { null })
        GroupPrefs.put("webname_$id", null)
    }

    fun setName(id: String, name: String?) = GroupPrefs.put("webname_$id", name?.trim()?.take(12))

    /** 「账号A」/「账号B」 by position (or the user's own name for it). */
    fun label(id: String): String {
        GroupPrefs.get("webname_$id")?.let { return it }
        val i = ids(site(id)).indexOf(id).coerceAtLeast(0)
        return "账号" + ('A' + i)
    }

    /** 「豆包」 with one account, 「豆包·账号A」 when the platform has several. */
    fun display(platformName: String, id: String): String =
        if (ids(site(id)).size > 1 || id.contains('#')) "$platformName·${label(id)}" else platformName
}

package com.openminis.app.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-mail-ledger] 临时邮箱台账。
 *
 * 用户 2026-10-27 明确要求：**两类必须分清，不能混**
 *  - TEMP   临时邮箱（Mail.tm / Moakt）—— 一次性，可能随时失效
 *  - ALIAS  Gmail 别名（<gmail-alias>）—— 永久有效
 *
 * 台账每条记录：
 *  - kind        来源类别 TEMP / ALIAS
 *  - provider    具体来源 "mail.tm" / "moakt" / "gmail"
 *  - address     邮箱地址
 *  - domain      域名（14 个可选：drmail.in / teml.net / ...）
 *  - sites       已注册平台（JSON 数组 [{site, verified, at}]）
 *  - lastCode    最近收到的验证码 + 时间
 *  - keepAlive   保活设置 {enabled, intervalH, lastAt}
 *  - note        备注
 *
 * 存在 SharedPreferences `mines_mail_ledger` 的 "list" 键里（和 AutoAccounts 一样的做法）。
 */
object MailLedger {
    private const val PREFS = "mines_mail_ledger"
    private const val KEY = "list"

    const val TEMP = "TEMP"
    const val ALIAS = "ALIAS"

    /** 已知临时邮箱可选域名（用户要的 14 个选择器，可在设置里扩展）。 */
    val TEMP_DOMAINS = listOf(
        "drmail.in", "teml.net", "tmpeml.com", "tmpbox.net", "moakt.cc",
        "disbox.net", "tmpmail.org", "tmpmail.net", "tmails.net", "disbox.org",
        "moakt.co", "moakt.ws", "tmail.ws", "bareed.ws",
    )

    data class Site(val site: String, val verified: Boolean, val at: Long)

    data class Entry(
        val id: String,
        val kind: String,
        val provider: String,
        val address: String,
        val domain: String,
        val sites: List<Site>,
        val lastCode: String,
        val lastCodeAt: Long,
        val keepAliveOn: Boolean,
        val keepAliveH: Int,
        val keepAliveAt: Long,
        val note: String,
        val at: Long,
        val lastUsedAt: Long = 0L,
    )

    @Volatile private var loaded = false
    private val cache = ArrayList<Entry>()

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        cache.clear()
        runCatching {
            val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sites = ArrayList<Site>()
                o.optJSONArray("sites")?.let { sa ->
                    for (j in 0 until sa.length()) {
                        val so = sa.optJSONObject(j) ?: continue
                        sites.add(
                            Site(
                                so.optString("site"),
                                so.optBoolean("verified"),
                                so.optLong("at"),
                            ),
                        )
                    }
                }
                cache.add(
                    Entry(
                        id = o.optString("id"),
                        kind = o.optString("kind", TEMP).ifBlank { TEMP },
                        provider = o.optString("provider"),
                        address = o.optString("address"),
                        domain = o.optString("domain"),
                        sites = sites,
                        lastCode = o.optString("lastCode"),
                        lastCodeAt = o.optLong("lastCodeAt"),
                        keepAliveOn = o.optBoolean("keepAliveOn"),
                        keepAliveH = o.optInt("keepAliveH", 6),
                        keepAliveAt = o.optLong("keepAliveAt"),
                        note = o.optString("note"),
                        at = o.optLong("at"),
                        lastUsedAt = o.optLong("lastUsedAt"),
                    ),
                )
            }
        }
        loaded = true
    }

    @Synchronized
    fun all(c: Context): List<Entry> { init(c); return cache.toList() }

    @Synchronized
    private fun save(c: Context) {
        val arr = JSONArray()
        cache.forEach { e ->
            val sa = JSONArray()
            e.sites.forEach { s ->
                sa.put(JSONObject().put("site", s.site).put("verified", s.verified).put("at", s.at))
            }
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("kind", e.kind)
                    .put("provider", e.provider)
                    .put("address", e.address)
                    .put("domain", e.domain)
                    .put("sites", sa)
                    .put("lastCode", e.lastCode)
                    .put("lastCodeAt", e.lastCodeAt)
                    .put("keepAliveOn", e.keepAliveOn)
                    .put("keepAliveH", e.keepAliveH)
                    .put("keepAliveAt", e.keepAliveAt)
                    .put("note", e.note)
                    .put("at", e.at)
                    .put("lastUsedAt", e.lastUsedAt),
            )
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    /** Replace an existing exact address record after an explicit CLI state change. */
    @Synchronized
    fun replace(c: Context, e: Entry): Entry? {
        init(c)
        val matches = cache.indices.filter { cache[it].address.equals(e.address, true) }
        if (matches.size != 1) return null
        cache[matches.single()] = e
        save(c)
        return e
    }

    /** 新增或更新一条。同地址视为同一条，合并 sites。 */
    @Synchronized
    fun upsert(c: Context, e: Entry): Entry {
        init(c)
        val idx = cache.indexOfFirst { it.address.equals(e.address, true) }
        val out = if (idx >= 0) {
            val old = cache[idx]
            val merged = LinkedHashMap<String, Site>()
            old.sites.forEach { merged[it.site.lowercase()] = it }
            e.sites.forEach { merged[it.site.lowercase()] = it }
            old.copy(
                kind = e.kind.ifBlank { old.kind },
                provider = e.provider.ifBlank { old.provider },
                domain = e.domain.ifBlank { old.domain },
                sites = merged.values.toList(),
                lastCode = e.lastCode.ifBlank { old.lastCode },
                lastCodeAt = if (e.lastCode.isNotBlank()) e.lastCodeAt else old.lastCodeAt,
                keepAliveOn = e.keepAliveOn || old.keepAliveOn,
                keepAliveH = if (e.keepAliveH > 0) e.keepAliveH else old.keepAliveH,
                keepAliveAt = maxOf(e.keepAliveAt, old.keepAliveAt),
                lastUsedAt = maxOf(e.lastUsedAt, old.lastUsedAt),
                note = e.note.ifBlank { old.note },
            ).also { cache[idx] = it }
        } else {
            e.also { cache.add(it) }
        }
        save(c)
        return out
    }

    @Synchronized
    fun remove(c: Context, addressOrId: String): Boolean {
        init(c)
        val matches = cache.indices.filter {
            cache[it].address.equals(addressOrId, true) || cache[it].id == addressOrId
        }
        if (matches.size != 1) return false
        cache.removeAt(matches.single())
        save(c)
        return true
    }

    /** 记一条验证码 */
    @Synchronized
    fun recordCode(c: Context, addressOrId: String, code: String): Entry? {
        init(c)
        val matches = cache.indices.filter {
            cache[it].address.equals(addressOrId, true) || cache[it].id == addressOrId
        }
        if (matches.size != 1) return null
        val idx = matches.single()
        val now = System.currentTimeMillis()
        val e = cache[idx].copy(lastCode = code, lastCodeAt = now, lastUsedAt = now)
        cache[idx] = e
        save(c)
        return e
    }

    /** 标记某个平台上已验证 */
    @Synchronized
    fun markSite(c: Context, addressOrId: String, site: String, verified: Boolean): Entry? {
        init(c)
        val matches = cache.indices.filter {
            cache[it].address.equals(addressOrId, true) || cache[it].id == addressOrId
        }
        if (matches.size != 1) return null
        val idx = matches.single()
        val e = cache[idx]
        val m = LinkedHashMap<String, Site>()
        e.sites.forEach { m[it.site.lowercase()] = it }
        m[site.lowercase()] = Site(site, verified, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val ne = e.copy(sites = m.values.toList(), lastUsedAt = now)
        cache[idx] = ne
        save(c)
        return ne
    }

    /** 哪些该保活了：开了保活且超过间隔 */
    fun dueForKeepAlive(c: Context, now: Long = System.currentTimeMillis()): List<Entry> {
        init(c)
        return cache.filter { e ->
            e.keepAliveOn && e.kind == TEMP &&
                (e.keepAliveAt == 0L || now - e.keepAliveAt >= e.keepAliveH * 3600_000L)
        }
    }
}

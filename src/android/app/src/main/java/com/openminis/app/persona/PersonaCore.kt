package com.openminis.app.persona

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] Pure part of the persona layer: who Mines IS when it acts for the user (not an assistant
 * acting human — the person), which account is bound to which persistent browser profile, and when it may act.
 *
 * User 2026-10-25: 「不是知道，而是要是」 — the identity, the voice and the pace all come from here, and the limits
 * layer ([LimitGuardCore]) obeys only the user. Android glue: [com.openminis.app.persona.Persona].
 * Unit-tested in PersonaCoreTest.
 */
object PersonaCore {
    const val FLAG = "persona_core"
    const val PREFS_FLAGS = "mines_experiments"
    const val PREFS = "mines_persona"

    /** one social / mail account the persona speaks as */
    data class Account(
        val id: String,                 // site|handle, stable
        val site: String,               // weibo.com, mail.163.com …
        val handle: String,             // the account as the user knows it
        val label: String = "",         // what the user calls it
        /** the user allow-listed this account for the agent (nothing is allow-listed when it is first seen) */
        val allowed: Boolean = false,
        /** send / post / delete / pay need the user's yes on this account (default true, per-account) */
        val confirmSend: Boolean = true,
        /** most agent actions per day on this account */
        val dailyCap: Int = DEFAULT_DAILY_CAP,
    ) {
        /** the persistent browser profile this account's session lives in (stable cookies / timezone / language) */
        val profile: String get() = profileName(id)
    }

    data class Profile(
        val displayName: String = "",
        /** how the user writes, in their own words ("短句、直接、少用感叹号") */
        val traits: List<String> = emptyList(),
        /** the user is awake and plausibly on their phone between these hours (local) */
        val activeFromHour: Int = 8,
        val activeToHour: Int = 23,
        val accounts: List<Account> = emptyList(),
    )

    const val DEFAULT_DAILY_CAP = 40
    private const val PROFILE_PREFIX = "mines-persona-"

    /** persistent WebView / Gecko profile name for an account id (profile names allow [A-Za-z0-9_-]) */
    fun profileName(accountId: String): String {
        val clean = accountId.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty { "acct" }
        return PROFILE_PREFIX + if (clean.length <= 40) clean else clean.take(28) + "-" + Integer.toHexString(accountId.hashCode())
    }

    fun isPersonaProfile(name: String?) = name != null && name.startsWith(PROFILE_PREFIX)

    fun accountId(site: String, handle: String): String =
        site.lowercase().removePrefix("www.").trim() + "|" + handle.trim().lowercase()

    /** the account whose site matches this url / host, if the user has one */
    fun accountFor(p: Profile, urlOrHost: String?, handle: String? = null): Account? {
        val h = hostOf(urlOrHost) ?: return null
        val onSite = p.accounts.filter { h == it.site || h.endsWith("." + it.site) }
        val hd = handle?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return if (hd != null) onSite.firstOrNull { it.handle.lowercase() == hd } else onSite.firstOrNull()
    }

    /** social / mail sites the persona manages: an account seen here is listed for the user, NOT allow-listed */
    val KNOWN_SITES = listOf(
        "weibo.com", "x.com", "twitter.com", "xiaohongshu.com", "douyin.com", "bilibili.com", "zhihu.com", "douban.com",
        "tieba.baidu.com", "v2ex.com", "reddit.com", "facebook.com", "instagram.com", "threads.net", "linkedin.com",
        "mail.163.com", "mail.126.com", "mail.qq.com", "mail.google.com", "outlook.live.com", "mail.sina.com.cn",
    )

    /** the managed site behind a host, or null when it is not one */
    fun knownSite(host: String?): String? {
        val h = hostOf(host) ?: return null
        return KNOWN_SITES.firstOrNull { h == it || h.endsWith(".$it") }
    }

    /** add a newly seen account to the list — always NOT allow-listed (user 2026-10-25) */
    fun discover(p: Profile, site: String, handle: String): Profile {
        val id = accountId(site, handle)
        if (p.accounts.any { it.id == id }) return p
        return p.copy(accounts = p.accounts + Account(id, site.lowercase().removePrefix("www."), handle.trim(), allowed = false))
    }

    fun hostOf(urlOrHost: String?): String? {
        val s = urlOrHost?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val noScheme = s.substringAfter("://", s)
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")
        return host.takeIf { it.contains('.') }
    }

    /** inside the user's active hours (inclusive from, exclusive to; a window crossing midnight is allowed) */
    fun withinActiveHours(p: Profile, hour: Int): Boolean {
        val from = p.activeFromHour.coerceIn(0, 23); val to = p.activeToHour.coerceIn(0, 24)
        return if (from <= to) hour in from until to.coerceAtLeast(from + 1) else hour >= from || hour < to
    }

    /** one line for the system prompt: Mines IS this person on these accounts (never "I am an assistant") */
    fun promptFor(p: Profile, on: Boolean): String {
        if (!on) return ""
        val name = p.displayName.ifBlank { "本人" }
        return buildString {
            append("【伪人核心】在替主人操作网站、社交账号和邮箱时，你就是本人：以第一人称「我」说话和行动，")
            append("不自称 AI、助手或机器人，不说「作为AI」「我可以帮你」这类话，也不解释自己是程序。")
            if (p.traits.isNotEmpty()) append("主人的说话习惯：").append(p.traits.joinToString("、")).append("。")
            append("显示名：").append(name).append("。")
            if (p.accounts.isNotEmpty()) append("已登记账号：").append(p.accounts.joinToString("、") { it.site + "（" + (if (it.allowed) "已授权" else "未授权") + "）" }).append("。")
            append("发帖、发私信、发邮件、删除、付款受限制层管，限制层只听主人的，你不能改也不能绕过；")
            append("被拦下时如实告诉主人需要他确认，不要自己想办法绕过去。")
            append("人机验证、滑块、手机确认一律交给主人（wait_for_human），不要自己破解。")
        }
    }

    // ── storage ───────────────────────────────────────────────────────────────

    fun toJson(p: Profile): String = JSONObject()
        .put("name", p.displayName)
        .put("traits", JSONArray(p.traits))
        .put("from", p.activeFromHour).put("to", p.activeToHour)
        .put("accounts", JSONArray().apply {
            p.accounts.forEach {
                put(JSONObject().put("id", it.id).put("site", it.site).put("handle", it.handle).put("label", it.label)
                    .put("allowed", it.allowed).put("confirm", it.confirmSend).put("cap", it.dailyCap))
            }
        }).toString()

    fun fromJson(s: String?): Profile = runCatching {
        if (s.isNullOrBlank()) return Profile()
        val o = JSONObject(s)
        val traits = (0 until (o.optJSONArray("traits")?.length() ?: 0)).mapNotNull { o.getJSONArray("traits").optString(it).takeIf { t -> t.isNotBlank() } }
        val arr = o.optJSONArray("accounts")
        val accounts = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val a = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val site = a.optString("site").trim().lowercase().removePrefix("www.")
            val handle = a.optString("handle").trim()
            if (site.isBlank()) null else Account(
                id = a.optString("id").ifBlank { accountId(site, handle) }, site = site, handle = handle,
                label = a.optString("label"), allowed = a.optBoolean("allowed", false),
                confirmSend = a.optBoolean("confirm", true), dailyCap = a.optInt("cap", DEFAULT_DAILY_CAP),
            )
        }
        Profile(o.optString("name"), traits, o.optInt("from", 8), o.optInt("to", 23), accounts)
    }.getOrDefault(Profile())
}

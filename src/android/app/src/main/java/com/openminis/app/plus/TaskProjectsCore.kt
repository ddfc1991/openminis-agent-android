package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.13 task_projects] user 2026-10-24 14:49: 任务取名 / 归类 / 归档 / 立项.
 *  - a task is named after the project it is doing (「Railway 注册」), not its first sentence;
 *  - the same project again = 「第 2 次」, another account on it = 「账号 2」, both under the one project;
 *  - small tasks (看文章 / 查问题) auto-archive after [SMALL_DAYS] days idle; projects never auto-archive;
 *  - a small task that turns into registering / opening an account becomes a project and registers
 *    站点 · 账号名 · 手机/邮箱 · 日期 (never a password — passwords stay in the phone's autofill).
 * Pure; unit-tested in TaskProjectsCoreTest.
 */
object TaskProjectsCore {
    const val SMALL_DAYS = 2

    enum class Kind(val zh: String) { ARTICLE("看文章"), QUESTION("查问题"), OTHER("") }

    /** one account on the project; [idx] = 1, 2, … (「账号 2」) */
    data class Acct(val idx: Int, val name: String, val contact: String, val date: String)

    /** one conversation working on the project; [n] = 1, 2, … (「第 2 次」), [acct] = Acct.idx (0 = none yet) */
    data class Attempt(val sid: String, val n: Int, val acct: Int, val at: Long)

    data class Project(val id: String, val site: String, val verb: String, val createdAt: Long,
                       val accounts: List<Acct> = emptyList(), val attempts: List<Attempt> = emptyList()) {
        val name: String get() = projectName(site, verb)
    }

    // ── naming ──

    private val URL = Regex("""(?i)\bhttps?://([a-z0-9.\-]+\.[a-z]{2,})""")
    private val DOMAIN = Regex("""(?i)\b((?:[a-z0-9\-]+\.)+(?:com|io|app|dev|ai|net|org|co|cn|me|so|sh|cc|xyz|cloud|tech|run|gg|tv))\b""")
    private val REGISTER = Regex("""(?i)注册|开通|开户|开个号|开个账号|申请账号|激活|sign ?up|register|create (an )?account""")

    /** first host named in [text] (URL first, then a bare domain), without www. */
    fun siteOf(text: String): String? {
        val h = URL.find(text)?.groupValues?.get(1) ?: DOMAIN.find(text)?.groupValues?.get(1) ?: return null
        return h.lowercase().removePrefix("www.").trimEnd('.')
    }

    fun registerIntent(text: String): Boolean = REGISTER.containsMatchIn(text)

    fun verbOf(text: String): String = if (text.contains("激活")) "激活" else "注册"

    /** railway.com → Railway, dash.cloudflare.com → Cloudflare, huggingface.co → Huggingface */
    fun brand(site: String): String {
        val root = LedgerCore.root(hostOf(site))
        val label = root.substringBefore('.')
        return label.replaceFirstChar { it.uppercase() }
    }

    fun projectName(site: String, verb: String = "注册"): String = (brand(site).ifBlank { "新项目" }) + " " + verb

    fun kindOf(title: String, text: String): Kind {
        val t = "$title\n$text"
        return when {
            Regex("""(?i)文章|这篇|阅读|读一下|总结一下|https?://""").containsMatchIn(t) -> Kind.ARTICLE
            Regex("""怎么|为什么|查一下|查查|报错|连不上|不行|问题|[?？]""").containsMatchIn(t) -> Kind.QUESTION
            else -> Kind.OTHER
        }
    }

    /** 「看文章：Cloudflare 新免费额度」 — the kind in front of a small task's title (kept as is when already there) */
    fun smallTitle(title: String, kind: Kind): String {
        val t = title.trim()
        if (kind == Kind.OTHER || t.isEmpty() || t.startsWith(kind.zh)) return t
        return kind.zh + "：" + t
    }

    // ── grouping ──

    fun projectOf(projects: List<Project>, sid: String): Project? = projects.firstOrNull { p -> p.attempts.any { it.sid == sid } }

    fun attemptOf(p: Project, sid: String): Attempt? = p.attempts.firstOrNull { it.sid == sid }

    /** https://www.railway.com/login → railway.com */
    fun hostOf(site: String): String =
        site.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").trimEnd('.')

    /** the project for [site] (same registrable domain) or null */
    fun forSite(projects: List<Project>, site: String): Project? =
        if (site.isBlank()) null else projects.firstOrNull { LedgerCore.sameSite(it.site, hostOf(site)) }

    /**
     * 立项 / 登记: conversation [sid] works on [site]. Same site → the existing project gets 第 N+1 次 (unless [sid] is
     * already one of its attempts); [contact] not seen on it yet → 账号 N+1. Returns the new list.
     */
    fun register(projects: List<Project>, sid: String, site: String, verb: String, name: String, contact: String,
                 date: String, now: Long): List<Project> {
        val s = hostOf(site)
        val old = if (s.isBlank()) projectOf(projects, sid) else forSite(projects, s)
        var p = old ?: Project("p$now", s, verb, now)
        val c = contact.trim(); val n = name.trim()
        var acctIdx = 0
        if (c.isNotEmpty() || n.isNotEmpty()) {
            val hit = p.accounts.firstOrNull { (c.isNotEmpty() && it.contact.equals(c, true)) || (c.isEmpty() && it.name.equals(n, true)) }
            if (hit != null) {
                acctIdx = hit.idx
                if (hit.name.isBlank() && n.isNotEmpty()) p = p.copy(accounts = p.accounts.map { if (it.idx == hit.idx) it.copy(name = n) else it })
            } else {
                acctIdx = (p.accounts.maxOfOrNull { it.idx } ?: 0) + 1
                p = p.copy(accounts = p.accounts + Acct(acctIdx, n, c, date))
            }
        }
        val at = attemptOf(p, sid)
        p = if (at != null) p.copy(attempts = p.attempts.map { if (it.sid == sid && acctIdx > 0) it.copy(acct = acctIdx) else it })
        else p.copy(attempts = p.attempts + Attempt(sid, (p.attempts.maxOfOrNull { it.n } ?: 0) + 1, acctIdx, now))
        // a conversation sits in one project only
        val rest = projects.filter { it.id != p.id }.map { o -> o.copy(attempts = o.attempts.filter { it.sid != sid }) }
        return rest + p
    }

    /** 「关联另一个账号」 on the project page: 账号 N+1 (an address already on the project is not added twice) */
    fun addAccount(projects: List<Project>, projectId: String, name: String, contact: String, date: String): List<Project> =
        projects.map { p ->
            if (p.id != projectId || (contact.isBlank() && name.isBlank()) ||
                p.accounts.any { contact.isNotBlank() && it.contact.equals(contact.trim(), true) }) p
            else p.copy(accounts = p.accounts + Acct((p.accounts.maxOfOrNull { it.idx } ?: 0) + 1, name.trim(), contact.trim(), date))
        }

    /** a conversation id changed (draft → real id) */
    fun moveSid(projects: List<Project>, from: String, to: String): List<Project> =
        projects.map { p -> p.copy(attempts = p.attempts.map { if (it.sid == from) it.copy(sid = to) else it }) }

    /** 「Railway 注册 · 第2次」 — the name the handoff popups show for a project conversation (null = not a project) */
    fun displayName(projects: List<Project>, sid: String): String? {
        val p = projectOf(projects, sid) ?: return null
        val a = attemptOf(p, sid) ?: return p.name
        return p.name + " · 第" + a.n + "次"
    }

    /** the conversation's title: the first attempt is just the project name, later ones carry 「· 第N次」 */
    fun titleFor(projects: List<Project>, sid: String): String? {
        val p = projectOf(projects, sid) ?: return null
        val a = attemptOf(p, sid)
        return if (a == null || a.n <= 1) p.name else p.name + " · 第" + a.n + "次"
    }

    // ── archive ──

    /** auto-archive days for a conversation: null = never (a project); small tasks at most [SMALL_DAYS] */
    fun archiveDays(isProject: Boolean, userDays: Int): Int? = if (isProject) null else minOf(userDays, SMALL_DAYS).coerceAtLeast(1)

    /** 「1天后归档」 for a small task idle since [touch] (null when it is already due) */
    fun archiveHint(touch: Long, now: Long, days: Int = SMALL_DAYS): String? {
        val left = touch + days * ArchiveCore.DAY_MS - now
        if (left <= 0) return null
        val d = ((left + ArchiveCore.DAY_MS - 1) / ArchiveCore.DAY_MS).toInt()
        return "${d}天后归档"
    }

    /** archived search: title or last message contains every word of [q] (case-insensitive) */
    fun matches(q: String, vararg fields: String?): Boolean {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = fields.joinToString("\n") { it.orEmpty() }.lowercase()
        return words.all { hay.contains(it) }
    }

    // ── 台账 link (no password ever) ──

    /** the 台账 备注 of a project account: keeps an existing 「注册于 / 注册中」 note, adds the project name */
    fun ledgerNote(existing: String, project: String, date: String): String =
        if (existing.startsWith("注册于") || existing.startsWith("注册中")) existing.takeIf { it.contains(project) } ?: (existing + " · " + project).take(60)
        else "注册中 $date · $project".take(60)

    // ── JSON ──

    fun toJson(l: List<Project>): String = JSONArray().apply {
        l.forEach { p ->
            put(JSONObject().put("id", p.id).put("site", p.site).put("verb", p.verb).put("at", p.createdAt)
                .put("acc", JSONArray().apply { p.accounts.forEach { put(JSONObject().put("i", it.idx).put("n", it.name).put("c", it.contact).put("d", it.date)) } })
                .put("try", JSONArray().apply { p.attempts.forEach { put(JSONObject().put("s", it.sid).put("n", it.n).put("a", it.acct).put("t", it.at)) } }))
        }
    }.toString()

    fun fromJson(s: String?): List<Project> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val acc = o.optJSONArray("acc") ?: JSONArray(); val tr = o.optJSONArray("try") ?: JSONArray()
            Project(o.optString("id"), o.optString("site"), o.optString("verb", "注册"), o.optLong("at"),
                (0 until acc.length()).map { j -> acc.getJSONObject(j).let { Acct(it.optInt("i"), it.optString("n"), it.optString("c"), it.optString("d")) } },
                (0 until tr.length()).map { j -> tr.getJSONObject(j).let { Attempt(it.optString("s"), it.optInt("n"), it.optInt("a"), it.optLong("t")) } })
        }
    }.getOrDefault(emptyList())
}

package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.13 task_projects] Android side of [TaskProjectsCore]: the project list (SharedPreferences JSON), the
 * feature flag (设置 → 实验功能, default on) and the 「这个任务已变成注册 → 立项」 proposal shown by TaskProjectSheetHost.
 * The 台账 link only writes a 备注 on an account the 台账 already holds — no password ever passes through here.
 */
object TaskProjects {
    private const val FILE = "mines_task_projects"
    @Volatile private var app: Context? = null

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _projects = MutableStateFlow<List<TaskProjectsCore.Project>>(emptyList())
    val projects: StateFlow<List<TaskProjectsCore.Project>> = _projects.asStateFlow()
    /** conversations where the user said 「先不立项」 (not asked again) */
    @Volatile private var declined: Set<String> = emptySet()

    data class Proposal(val sid: String, val site: String, val verb: String, val contact: String, val at: Long = System.nanoTime())
    private val _proposal = MutableStateFlow<Proposal?>(null)
    val proposal: StateFlow<Proposal?> = _proposal.asStateFlow()

    val loaded: Boolean get() = app != null
    fun enabled(): Boolean = loaded && _on.value

    fun init(c: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _on.value = p.getBoolean("on", true)
            _projects.value = TaskProjectsCore.fromJson(p.getString("projects", "[]"))
            declined = p.getStringSet("declined", emptySet()).orEmpty().toSet()
            app = c.applicationContext
        }
    }

    private fun prefs() = app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun save() { prefs()?.edit()?.putString("projects", TaskProjectsCore.toJson(_projects.value))?.apply() }

    fun setOn(c: Context, v: Boolean) { init(c); _on.value = v; prefs()?.edit()?.putBoolean("on", v)?.apply(); if (!v) _proposal.value = null }

    fun projectOf(sid: String): TaskProjectsCore.Project? = TaskProjectsCore.projectOf(_projects.value, sid)
    fun isProject(sid: String): Boolean = enabled() && projectOf(sid) != null

    // ── hooks ──

    /** the user's message (TaskProgress.onTurnStart): 「那就帮我注册一个吧」 in a small task → propose 立项 */
    fun onUserText(sid: String, text: String?) {
        if (!enabled() || sid.isBlank() || sid.startsWith("__new__") || text.isNullOrBlank()) return
        if (projectOf(sid) != null || sid in declined || !TaskProjectsCore.registerIntent(text)) return
        val site = TaskProjectsCore.siteOf(text) ?: TaskProjectsCore.siteOf(Ledger.textOf(sid)).orEmpty()
        propose(sid, site, TaskProjectsCore.verbOf(text), "")
    }

    /** the agent started / finished a registration (accounts register_pick / register_done) */
    fun onRegister(c: Context, sid: String, site: String, contact: String) {
        init(c)
        if (!enabled() || sid.isBlank() || site.isBlank()) return
        val p = projectOf(sid)
        if (p != null) {
            // already a project: a new account the agent used is linked at once (the user approved the project)
            if (contact.isNotBlank() && p.accounts.none { it.contact.equals(contact, true) })
                confirm(c, sid, site, p.verb, "", contact, today())
            return
        }
        if (sid !in declined) propose(sid, site, "注册", contact)
    }

    private fun propose(sid: String, site: String, verb: String, contact: String) {
        if (ChatHome.isMain(sid)) return   // [Mines-38.9.14 chat_home] 聊天 itself is never a project (its background tasks are)
        val cur = _proposal.value
        if (cur != null && cur.sid == sid && cur.site.isNotBlank()) return
        _proposal.value = Proposal(sid, TaskProjectsCore.hostOf(site), verb, contact.ifBlank { cur?.contact.orEmpty() })
    }

    fun decline(sid: String) {
        declined = declined + sid
        prefs()?.edit()?.putStringSet("declined", declined)?.apply()
        if (_proposal.value?.sid == sid) _proposal.value = null
    }

    fun dismiss() { _proposal.value = null }

    /** 登记并关联账号: project + attempt + account, 台账 备注 on that account, conversation renamed after the project */
    fun confirm(c: Context, sid: String, site: String, verb: String, name: String, contact: String, date: String) {
        init(c)
        val host = TaskProjectsCore.hostOf(site)
        _projects.value = TaskProjectsCore.register(_projects.value, sid, host, verb, name, contact, date, System.currentTimeMillis())
        save()
        if (_proposal.value?.sid == sid) _proposal.value = null
        val proj = projectOf(sid) ?: return
        if (contact.isNotBlank()) runCatching {
            val cand = Ledger.cands(c, host).firstOrNull { it.account.equals(contact.trim(), true) }
            if (cand != null) Ledger.setNote(c, cand.host, cand.account, TaskProjectsCore.ledgerNote(cand.note, proj.name, date))
        }
        val title = TaskProjectsCore.titleFor(_projects.value, sid) ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val dao = com.openminis.app.data.db.AppDatabase.getInstance(c.applicationContext).chatDao()
                if (dao.getSession(sid)?.title != title) dao.updateSessionTitle(sid, title, System.currentTimeMillis())
            }
        }
    }

    /** 「关联另一个账号」 on the project page (+ 台账 备注 when the 台账 holds that account) */
    fun addAccount(c: Context, projectId: String, name: String, contact: String, date: String) {
        init(c)
        _projects.value = TaskProjectsCore.addAccount(_projects.value, projectId, name, contact, date); save()
        val p = _projects.value.firstOrNull { it.id == projectId } ?: return
        if (contact.isNotBlank()) runCatching {
            val cand = Ledger.cands(c, p.site).firstOrNull { it.account.equals(contact.trim(), true) }
            if (cand != null) Ledger.setNote(c, cand.host, cand.account, TaskProjectsCore.ledgerNote(cand.note, p.name, date))
        }
    }

    /** a new chat got its real id */
    fun move(from: String, to: String) {
        if (!loaded || from.isBlank() || to.isBlank() || from == to) return
        if (_proposal.value?.sid == from) _proposal.value = _proposal.value?.copy(sid = to)
        if (TaskProjectsCore.projectOf(_projects.value, from) == null) return
        _projects.value = TaskProjectsCore.moveSid(_projects.value, from, to); save()
    }

    /** project accounts the 台账 (密码库) does not hold — shown in 注册台账 so every project account is there (no password) */
    fun ledgerRows(have: List<Inbox3912Core.RegRow>): List<Inbox3912Core.RegRow> {
        if (!enabled()) return emptyList()
        return _projects.value.flatMap { p -> p.accounts.filter { it.contact.isNotBlank() }.map { p to it } }
            .filter { (p, a) -> have.none { LedgerCore.sameSite(it.host, p.site) && it.account.equals(a.contact, true) } }
            .map { (p, a) -> Inbox3912Core.RegRow(p.site, a.contact, "立项 ${a.date} · ${p.name} · 密码在手机自动填充", Inbox3912Core.RegSt.SAVED) }
    }

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}

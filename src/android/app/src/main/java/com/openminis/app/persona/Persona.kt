package com.openminis.app.persona

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] Android side: storage, the flag, the limits the USER owns, the audit log, the kill switch,
 * the per-account daily counters and the learned style.
 *
 * The limits are writable only through [userSet] calls that carry [LimitGuardCore.USER_TAP]; the 伪人核心 screen is the
 * only caller, and it asks for the device lock first where the phone has one. No agent tool reaches these setters,
 * and [guard] is what every tool path goes through.
 */
object Persona {
    private const val FILE = "mines_persona"
    @Volatile private var app: Context? = null

    private val _on = MutableStateFlow(true)                       // flag 设置 → 实验功能 「伪人核心」, default ON
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _profile = MutableStateFlow(PersonaCore.Profile())
    val profile: StateFlow<PersonaCore.Profile> = _profile.asStateFlow()
    private val _limits = MutableStateFlow(LimitGuardCore.Limits())
    val limits: StateFlow<LimitGuardCore.Limits> = _limits.asStateFlow()
    private val _log = MutableStateFlow<List<LimitGuardCore.Entry>>(emptyList())
    val log: StateFlow<List<LimitGuardCore.Entry>> = _log.asStateFlow()
    private val _style = MutableStateFlow(VoiceFilterCore.Style())
    val style: StateFlow<VoiceFilterCore.Style> = _style.asStateFlow()
    private val _contacts = MutableStateFlow<Map<String, ContactMemoryCore.Contact>>(emptyMap())
    val contacts: StateFlow<Map<String, ContactMemoryCore.Contact>> = _contacts.asStateFlow()
    private val counts = HashMap<String, Int>()                    // "account@yyyymmdd" → actions today
    /** a refused send / post / delete / pay waiting for the user's 同意 (id, account, act, note, at) */
    data class Pending(val id: String, val account: String, val act: String, val note: String, val at: Long)
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()
    private val approvals = HashMap<String, Long>()                // "account|act" → valid until (one use)
    const val APPROVAL_MS = 10 * 60_000L

    val loaded: Boolean get() = app != null
    fun enabled(): Boolean = loaded && _on.value && !_limits.value.killed

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val p = prefs() ?: return
        _on.value = c.applicationContext.getSharedPreferences(PersonaCore.PREFS_FLAGS, Context.MODE_PRIVATE)
            .getBoolean(PersonaCore.FLAG, true)
        _profile.value = PersonaCore.fromJson(p.getString("profile", null))
        _limits.value = LimitGuardCore.limitsFromJson(p.getString("limits", null))
        _log.value = LimitGuardCore.logFromJson(p.getString("log", null))
        _style.value = VoiceFilterCore.styleFromJson(p.getString("style", null))
        _contacts.value = ContactMemoryCore.fromJson(p.getString("contacts", null))
        runCatching { JSONObject(p.getString("counts", "{}") ?: "{}") }.getOrNull()?.let { o ->
            o.keys().forEach { k -> counts[k] = o.optInt(k) }
        }
    }

    private fun prefs() = app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun save() {
        prefs()?.edit()
            ?.putString("profile", PersonaCore.toJson(_profile.value))
            ?.putString("limits", LimitGuardCore.limitsToJson(_limits.value))
            ?.putString("log", LimitGuardCore.logToJson(_log.value))
            ?.putString("style", VoiceFilterCore.styleJson(_style.value))
            ?.putString("contacts", ContactMemoryCore.toJson(_contacts.value))
            ?.putString("counts", JSONObject(counts as Map<*, *>).toString())
            ?.apply()
    }

    /** the experiment flag (设置 → 实验功能). Turning the feature off is not the same as the kill switch. */
    fun setFlag(c: Context, v: Boolean) {
        init(c)
        _on.value = v
        c.applicationContext.getSharedPreferences(PersonaCore.PREFS_FLAGS, Context.MODE_PRIVATE).edit().putBoolean(PersonaCore.FLAG, v).apply()
    }

    // ── the user's own settings: every setter needs the user's tap ────────────

    /** refuses anything that is not the 伪人核心 UI; this is what makes the limits the user's and not the agent's */
    private fun userOnly(token: String): Boolean = token == LimitGuardCore.USER_TAP

    fun userSetKill(c: Context, token: String, killed: Boolean): Boolean {
        init(c); if (!userOnly(token)) return false
        _limits.value = _limits.value.copy(killed = killed); save()
        record("", if (killed) "kill-on" else "kill-off", true, "user", "")
        return true
    }

    fun userSetQuietHours(c: Context, token: String, on: Boolean): Boolean {
        init(c); if (!userOnly(token)) return false
        _limits.value = _limits.value.copy(quietHours = on); save(); return true
    }

    fun userSetProfile(c: Context, token: String, p: PersonaCore.Profile): Boolean {
        init(c); if (!userOnly(token)) return false
        _profile.value = p; save(); return true
    }

    fun userSetAccount(c: Context, token: String, a: PersonaCore.Account): Boolean {
        init(c); if (!userOnly(token)) return false
        val cur = _profile.value
        val list = if (cur.accounts.any { it.id == a.id }) cur.accounts.map { if (it.id == a.id) a else it } else cur.accounts + a
        _profile.value = cur.copy(accounts = list); save()
        record(a.id, if (a.allowed) "allow" else "disallow", true, "user", a.site)
        return true
    }

    fun userRemoveAccount(c: Context, token: String, id: String): Boolean {
        init(c); if (!userOnly(token)) return false
        _profile.value = _profile.value.copy(accounts = _profile.value.accounts.filter { it.id != id }); save(); return true
    }

    // ── the guard every tool path calls ───────────────────────────────────────

    fun today(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))

    fun usedToday(accountId: String, now: Long = System.currentTimeMillis()): Int =
        counts[LimitGuardCore.capKey(accountId, today(now))] ?: 0

    private fun bump(accountId: String) {
        if (accountId.isBlank()) return
        val k = LimitGuardCore.capKey(accountId, today())
        counts[k] = (counts[k] ?: 0) + 1
        if (counts.size > 200) counts.keys.filter { !it.endsWith(today()) }.forEach { counts.remove(it) }
        save()
    }

    /**
     * May the agent do [act] on the page / account behind [urlOrHost]? Allowed actions are counted against the
     * account's daily cap. Every decision is written to the audit log the user can read.
     */
    fun guard(c: Context, urlOrHost: String?, act: LimitGuardCore.Act, note: String = "", handle: String? = null, requireAccount: Boolean = false): LimitGuardCore.Decision {
        init(c)
        // a mailbox or a managed social site the user has not listed yet appears in the list, switched off
        val site = if (!handle.isNullOrBlank() && handle.contains('@')) handle.substringAfter('@').lowercase() else PersonaCore.knownSite(urlOrHost)
        if (site != null && PersonaCore.accountFor(_profile.value, site, handle.takeIf { !it.isNullOrBlank() }) == null) {
            _profile.value = PersonaCore.discover(_profile.value, site, handle.orEmpty()); save()
        }
        val p = _profile.value
        val acct = PersonaCore.accountFor(p, urlOrHost, handle)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val ak = (acct?.id ?: "") + "|" + act.id
        val approved = synchronized(approvals) { (approvals[ak] ?: 0L) > System.currentTimeMillis() }
        val d = LimitGuardCore.check(
            flagOn = _on.value, limits = _limits.value, account = acct, act = act,
            usedToday = acct?.let { usedToday(it.id) } ?: 0,
            withinHours = PersonaCore.withinActiveHours(p, hour),
            approved = approved, requireAccount = requireAccount,
        )
        if (d.allowed && approved && act.needsConfirmByDefault) synchronized(approvals) { approvals.remove(ak) }   // one use
        if (d.allowed && act != LimitGuardCore.Act.READ && acct != null) bump(acct.id)
        if (!d.allowed && d.why == LimitGuardCore.Why.NEEDS_CONFIRM && acct != null)
            _pending.value = (_pending.value.filter { !(it.account == acct.id && it.act == act.id) } +
                Pending("p" + System.currentTimeMillis(), acct.id, act.id, note.take(80), System.currentTimeMillis())).takeLast(20)
        if (act != LimitGuardCore.Act.READ && (acct != null || !d.allowed)) record(acct?.id ?: PersonaCore.hostOf(urlOrHost).orEmpty(), act.id, d.allowed, d.why.name, note)
        return d
    }

    /**
     * A refused call: the tool answers with the wall, and the conversation's task goes to 「等你处理」 (chat-home task
     * states read TaskProgress), so the user sees it waiting on them instead of the agent finding another way.
     */
    fun blocked(sid: String, d: LimitGuardCore.Decision): com.openminis.app.tools.ToolExecutionResult {
        runCatching { com.openminis.app.plus.TaskProgress.onWaiting(sid, "限制层：" + d.zh.substringBefore('，').take(40)) }
        runCatching { PersonaNotify.refresh() }
        return com.openminis.app.tools.ToolExecutionResult(LimitGuardCore.refusal(d), false)
    }

    fun record(account: String, act: String, allowed: Boolean, why: String, note: String) {
        val e = LimitGuardCore.Entry(System.currentTimeMillis(), account, act, allowed, why, note)
        _log.value = (_log.value + e).takeLast(200)
        prefs()?.edit()?.putString("log", LimitGuardCore.logToJson(_log.value))?.apply()
    }

    /** the user's 同意 on one waiting step: that account may do that act once in the next 10 minutes */
    fun userApprove(c: Context, token: String, id: String, yes: Boolean): Boolean {
        init(c); if (!userOnly(token)) return false
        val p = _pending.value.firstOrNull { it.id == id } ?: return false
        if (yes) synchronized(approvals) { approvals[p.account + "|" + p.act] = System.currentTimeMillis() + APPROVAL_MS }
        _pending.value = _pending.value.filter { it.id != id }
        record(p.account, p.act, yes, if (yes) "user-approved" else "user-declined", p.note)
        return true
    }

    fun clearLog(c: Context, token: String): Boolean {
        init(c); if (!userOnly(token)) return false
        _log.value = emptyList(); save(); return true
    }

    // ── contact memory ────────────────────────────────────────────────────────

    fun remember(c: Context, who: String, topic: String?, name: String? = null, relation: String? = null, note: String? = null) {
        init(c)
        _contacts.value = ContactMemoryCore.touch(_contacts.value, who, topic, System.currentTimeMillis(), name, relation, note)
        save()
    }

    fun briefFor(who: String): String = ContactMemoryCore.brief(_contacts.value[ContactMemoryCore.keyOf(who)])

    // ── style, learned from the user's own sent mail ──────────────────────────

    /**
     * Learn how the user writes from their own sent messages. Reads at most [max] of them and keeps only the derived
     * shape ([VoiceFilterCore.Style]) — never the text. Safe to call repeatedly; it is a no-op without samples.
     */
    suspend fun learnStyle(c: Context, max: Int = 30): VoiceFilterCore.Style = withContext(Dispatchers.IO) {
        init(c)
        // only the user's OWN sent mail (Sent folder of each mailbox with an 授权码), never what others wrote to them
        val samples = PersonaMail.boxes().flatMap { b -> runCatching { PersonaMail.sentBodies(b, max) }.getOrDefault(emptyList()) }.take(max)
        val s = VoiceFilterCore.learn(samples)
        if (s.samples > 0) { _style.value = s; save() }
        s
    }

    /** the 伪人 system-prompt block; empty when the feature is off */
    fun prompt(): String = PersonaCore.promptFor(_profile.value, enabled())

    /** smoke / 自检 */
    fun stateJson(): String = JSONObject()
        .put("on", _on.value).put("killed", _limits.value.killed).put("quiet", _limits.value.quietHours)
        .put("name", _profile.value.displayName).put("style", _style.value.samples)
        .put("accounts", JSONArray().apply {
            _profile.value.accounts.forEach {
                put(JSONObject().put("id", it.id).put("site", it.site).put("allowed", it.allowed)
                    .put("confirm", it.confirmSend).put("cap", it.dailyCap).put("used", usedToday(it.id)).put("profile", it.profile))
            }
        })
        .put("log", JSONArray().apply { _log.value.takeLast(10).forEach { put(LimitGuardCore.entryJson(it)) } })
        .put("pending", _pending.value.size)
        .toString()
}

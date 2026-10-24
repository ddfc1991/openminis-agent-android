package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.11.3] Android side of [LedgerCore]: the 台账 (密码库 / [SavedLogins]) accounts with their 备注 / 默认 / last
 * success (meta only — passwords stay in SavedLogins), the recent user text per conversation (for 「你在对话里提到了它」),
 * and the small non-blocking chip 「已用 台账：邮箱」 · 换一个 · 设为默认.
 */
object Ledger {
    private const val PREFS = "mines_ledger"
    private val text = ConcurrentHashMap<String, String>()        // sessionId → recent user text (≤ 4000 chars)
    @Volatile private var meta: JSONObject? = null

    data class Chip(
        val sid: String?, val host: String, val site: String, val account: String, val others: List<String>,
        val id: String, val isDefault: Boolean, val eval: (suspend (String) -> String?)?,
    ) { val at = System.nanoTime() }

    private val _chip = MutableStateFlow<Chip?>(null)
    val chip: StateFlow<Chip?> = _chip.asStateFlow()

    // ── conversation text ──
    fun noteText(sid: String?, t: String) {
        if (sid.isNullOrBlank() || t.isBlank()) return
        text[sid] = ((text[sid].orEmpty()) + "\n" + t.take(2000)).takeLast(4000)
        if (text.size > 64) text.keys.take(text.size - 64).forEach { text.remove(it) }
    }
    fun textOf(sid: String?): String = sid?.let { text[it] }.orEmpty()

    // ── meta: id → {n: note, d: default, ok: lastOkAt} ──
    @Synchronized private fun m(c: Context): JSONObject {
        meta?.let { return it }
        val o = runCatching { JSONObject(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("meta", "{}")!!) }.getOrElse { JSONObject() }
        meta = o; return o
    }
    @Synchronized private fun save(c: Context) {
        val o = meta ?: return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("meta", o.toString()).apply()
    }
    @Synchronized private fun entry(c: Context, id: String): JSONObject = m(c).optJSONObject(id) ?: JSONObject().also { m(c).put(id, it) }

    fun cands(c: Context, pageHost: String): List<LedgerCore.Cand> {
        val all = runCatching { SavedLogins.all(c) }.getOrDefault(emptyList()).filter { it.pass.isNotBlank() }
        val mm = m(c)
        return LedgerCore.forSite(all.map { e ->
            val id = LedgerCore.idOf(e.host, e.user); val o = mm.optJSONObject(id)
            LedgerCore.Cand(id, e.host, e.user, o?.optString("n").orEmpty(), o?.optLong("ok") ?: 0L, o?.optBoolean("d") == true)
        }, pageHost)
    }

    /** the account to fill on [host] in conversation [sid] (null = nothing saved there / the user must choose) */
    fun choose(c: Context, host: String, sid: String?, bound: String?): LedgerCore.Pick =
        LedgerCore.pick(cands(c, host), textOf(sid), bound, choiceMatters = false)

    @Synchronized fun markOk(c: Context, host: String, account: String) {
        if (host.isBlank() || account.isBlank()) return
        val id = LedgerCore.idOf(host, account)
        if (cands(c, host).none { it.id == id }) return
        entry(c, id).put("ok", System.currentTimeMillis()); save(c)
    }

    /** one 默认 per site (registrable domain) */
    @Synchronized fun setDefault(c: Context, host: String, account: String) {
        val id = LedgerCore.idOf(host, account)
        cands(c, host).forEach { o -> m(c).optJSONObject(o.id)?.remove("d") }
        entry(c, id).put("d", true); save(c)
    }

    @Synchronized fun setNote(c: Context, host: String, account: String, note: String) {
        entry(c, LedgerCore.idOf(host, account)).put("n", note.take(60)); save(c)
    }

    // ── chip ──
    fun showChip(c: Context, sid: String?, host: String, site: String, account: String, eval: (suspend (String) -> String?)?) {
        val cs = cands(c, host)
        val me = cs.firstOrNull { it.account.equals(account, true) }
        _chip.value = Chip(sid, host, site, account, cs.map { it.account }.filterNot { it.equals(account, true) },
            me?.id ?: LedgerCore.idOf(host, account), me?.isDefault == true, eval)
    }
    fun clearChip(ch: Chip?) { if (ch == null || _chip.value?.at == ch.at) _chip.value = null }

    /** 换一个: bind [account] to the conversation and type it into the page over the current values (best effort) */
    suspend fun switchTo(c: Context, ch: Chip, account: String): Boolean {
        val e = runCatching { SavedLogins.find(c, ch.host, account) }.getOrNull() ?: return false
        runCatching { com.openminis.app.browser.ConvProfiles.bind(ch.sid, Keychain.root(ch.host).ifBlank { ch.host }, account) }
        val r = runCatching { ch.eval?.invoke(SavedLogins.fillJs(e, false, overwrite = true)) }.getOrNull()
        showChip(c, ch.sid, ch.host, ch.site, account, ch.eval)
        return r != null && r != "none"
    }

    /** for the agent: the 台账 accounts of a site, best first — never a password */
    fun agentLines(c: Context, host: String, sid: String?, bound: String?): String {
        val cs = cands(c, host)
        if (cs.isEmpty()) return "台账里没有 $host 的账号"
        val p = LedgerCore.pick(cs, textOf(sid), bound)
        val now = System.currentTimeMillis()
        val sb = StringBuilder("台账里 $host 的账号（不含密码，按 Mines 的选择排序）：\n")
        p.ordered.forEach { sb.append("- ").append(LedgerCore.line(it, it.account.equals(bound, true), now))
            .append(" · 密码 {{secret:").append(SecretRefs.idForSaved(c, it.host, it.account)).append("}}").append('\n') }
        p.cand?.let { sb.append("会用：").append(it.account).append("（").append(p.why?.zh.orEmpty()).append("）。登录页出现时 Mines 自动填，不弹框；要换就 accounts(action=use, site, account)。") }
        return sb.toString().trim()
    }
    // ── [38.9.11.3] 多号注册: e-mails from the 台账, new accounts written back (RegisterCore) ──
    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    private val rnd = java.security.SecureRandom()

    /** [n] e-mails for registering on [site] (unused there), each pre-saved to the 台账 with a fresh password + 「注册中」;
     *  returns one line per account for the agent: e-mail, {{user:ID}} / {{secret:ID}}, whether Mines can read its code */
    fun registerPick(c: Context, site: String, n: Int): String {
        val host = site.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        if (host.isBlank()) return "site 是空的"
        val all = runCatching { SavedLogins.all(c) }.getOrDefault(emptyList())
        val ledger = all.map { it.host to it.user }
        val idents = runCatching { IdentityStore.current(c).filter { it.type == IdentityCore.IdType.EMAIL }.map { it.value } }.getOrDefault(emptyList())
        val boxes = runCatching { CodeSources.enabledBoxes(c).map { it.label } }.getOrDefault(emptyList())
        val pool0 = RegisterCore.pool(ledger, idents, emptySet())
        val readable = pool0.filter { m -> boxes.any { it.contains(m.addr, ignoreCase = true) } }.map { it.addr }.toSet()
        val pool = RegisterCore.pool(ledger, idents, readable)
        val used = RegisterCore.usedOn(ledger, host)
        val got = RegisterCore.pick(pool, used, n)
        if (got.isEmpty()) return "台账里没有还没在 $host 注册过的邮箱（已用 ${used.size} 个）。可以用手机号或请用户在「账号」里加邮箱；不要在聊天里要密码。"
        val d = today()
        val sb = StringBuilder("从台账取了 ${got.size} 个还没在 $host 注册过的邮箱（已自动跳过 ${used.size} 个注册过的），每个都已生成密码并先存进台账（备注 注册中）：\n")
        got.forEachIndexed { i, m ->
            SavedLogins.put(c, SavedLogins.Entry(AutoAccounts.siteOf(host).ifBlank { host }, "https://$host/", m.addr, RegisterCore.password(rnd)))
            setNote(c, host, m.addr, RegisterCore.note(d, false))
            val id = SecretRefs.idForSaved(c, host, m.addr)
            sb.append("${i + 1}. ${m.addr} · 账号 {{user:$id}} · 密码 {{secret:$id}}" + (if (m.readable) " · Mines 能读这个邮箱的验证码" else " · 验证码要从这个邮箱取") + "\n")
        }
        sb.append("在 browser 的 type 里直接写 {{user:ID}} / {{secret:ID}}，Mines 输入时换成真值（你看不到）。每个注册完调用 accounts(action=register_done, site, account, ok=true|false)。")
        return sb.toString()
    }

    /** one registration finished: ok → 备注 「注册于 日期」 (+ [extra]); failed → removed from the 台账 again */
    fun registerDone(c: Context, site: String, account: String, ok: Boolean, extra: String = ""): String {
        val host = site.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
        val e = runCatching { SavedLogins.find(c, host, account) }.getOrNull() ?: return "台账里没有 $host 的 $account（先用 register_pick）"
        if (ok) {
            setNote(c, e.host.ifBlank { host }, e.user, RegisterCore.note(today(), true, extra)); markOk(c, e.host.ifBlank { host }, e.user)
            return "已写进台账：$host · ${e.user} · 备注 ${RegisterCore.note(today(), true, extra)}（密码用 {{secret:${SecretRefs.idForSaved(c, host, e.user)}}}）"
        }
        SavedLogins.remove(c, e.host, e.user)
        return "没注册成功：已把 $host · ${e.user} 从台账删掉"
    }
}

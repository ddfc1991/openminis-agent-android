package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.KeychainCore.Status
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9] 账号关联 as a keychain (mockups r389/mock 01–05). One list of every account Mines knows, grouped by the
 * phone number / mailbox it belongs to:
 *  - 账号连接 entries (PlusVault) and the web AI apps' sign-ins (LinkAll states),
 *  - accounts Mines noticed by itself (AutoAccounts: sign-ups / sign-ins in Mines, SMS codes, welcome mails),
 *  - passwords imported from Edge / Chrome or saved by 「用 Mines 填入」 (SavedLogins, Keystore-encrypted).
 * Status chips 正常 / 登录失效 / 异常登录; 有密码 / 有两步验证 icons; TOTP secrets stored encrypted (PlusVault secrets);
 * a login history per account (this phone + new-device alerts read from mail / SMS). An alert asks 「是我」/「不是我，帮我处理」 —
 * the latter only opens the site's security page with a short guide; Mines never changes a password by itself.
 */
object Keychain {
    data class Acct(
        val id: String, val site: String, val host: String, val account: String, val status: Status,
        val hasPw: Boolean, val has2fa: Boolean, val source: String, val lastLogin: Long, val kindId: String? = null, val linkId: String? = null,
        // [Mines-38.9.1] from the one identity store
        val method: IdentityCore.Method = IdentityCore.Method.UNKNOWN, val provider: IdentityCore.IdType? = null, val via: String = "",
        val identType: IdentityCore.IdType? = null, val legacyId: String = "",
    ) {
        val identity get() = KeychainCore.identity(account)
        val methodText: String get() = when {
            method == IdentityCore.Method.OAUTH && provider != null -> "通过 ${provider.label} 登录" + via.substringAfter(':', "").takeIf { it.isNotBlank() }?.let { " · ${KeychainCore.maskAccount(it)}" }.orEmpty()
            method == IdentityCore.Method.UNKNOWN || method == IdentityCore.Method.WEB -> ""
            else -> method.label
        }
        /** a provider account itself (Google / GitHub / X …): its detail lists the sites signed in through it */
        val isProvider: Boolean get() = identType?.provider == true && identType.host.isNotBlank() && Keychain.root(identType.host) == Keychain.root(host)
    }

    data class Event(val key: String, val device: String, val place: String, val at: Long, val how: String)

    private const val PREFS = "mines_keychain"
    @Volatile private var app: Context? = null
    private val _alerts = MutableStateFlow<List<Pair<KeychainCore.Alert, String>>>(emptyList())   // alert, state: new / me / notme / done
    val alerts: StateFlow<List<Pair<KeychainCore.Alert, String>>> = _alerts
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events
    /** bumps whenever something the list shows changed (UI re-reads) */
    val rev = MutableStateFlow(0)
    /** 「已记下 X 账号」 glass toast on the Mines screen (AutoAccounts) */
    private val _toast = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val toast: SharedFlow<Pair<String, String>> = _toast

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _alerts.value = runCatching { JSONArray(p.getString("alerts", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            KeychainCore.Alert(o.optString("site"), o.optString("host"), o.optString("acct"), o.optString("dev"), o.optString("place"), o.optLong("at"), o.optString("src"), o.optString("key")) to o.optString("st", "new") } } } }.getOrDefault(emptyList())
        _events.value = runCatching { JSONArray(p.getString("events", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Event(o.optString("k"), o.optString("d"), o.optString("p"), o.optLong("at"), o.optString("h")) } } } }.getOrDefault(emptyList())
    }

    private fun save(c: Context) {
        val a = JSONArray(); _alerts.value.take(100).forEach { (x, st) -> a.put(JSONObject().put("site", x.site).put("host", x.host).put("acct", x.account).put("dev", x.device)
            .put("place", x.place).put("at", x.at).put("src", x.source).put("key", x.key).put("st", st)) }
        val e = JSONArray(); _events.value.take(300).forEach { x -> e.put(JSONObject().put("k", x.key).put("d", x.device).put("p", x.place).put("at", x.at).put("h", x.how)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alerts", a.toString()).putString("events", e.toString()).apply()
        rev.value++
    }

    fun root(h: String): String = h.lowercase().removePrefix("www.").split('.').filter { it.isNotBlank() }.let { p ->
        if (p.size >= 3 && p[p.size - 2] in setOf("com", "net", "org", "co", "gov", "edu")) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".") }
    private fun keyOf(host: String, site: String, account: String) = (root(host).ifBlank { site.lowercase() } + "|" + account.lowercase())
    /** [Mines-38.9.3] a real site name for [host]: the 账号连接 / AI catalog name of that domain, else its domain word. */
    fun siteName(host: String): String {
        val r = root(host)
        if (r.isBlank()) return host
        runCatching { PlusVault.kinds.firstOrNull { k -> !k.id.contains('#') && !k.id.startsWith("site:") &&
            listOfNotNull(k.loginUrl, k.site, k.panelUrl, k.keyUrl).any { root(hostOf(it)) == r } }?.let { return it.name } }
        runCatching { com.openminis.app.plus.group.AiCatalog.webApps.firstOrNull { root(hostOf(it.webUrl)) == r }?.let { return it.name } }
        return AutoAccounts.siteOf(r)
    }

    /** [Mines-38.9.6 i13] does the WebView store hold any non-noise cookie for [site] (root, www., app./my./web. and remembered login pages)? */
    fun hasSessionCookie(site: String): Boolean = runCatching {
        if (site.isBlank() || !site.contains('.')) return@runCatching true   // not a web site: nothing to check
        val cm = android.webkit.CookieManager.getInstance()
        val hosts = linkedSetOf(site, "www.$site", "app.$site", "my.$site", "web.$site", "accounts.$site")
        runCatching { com.openminis.app.plus.group.PresetAuth.memohOrigin()?.let { o -> hostOf(o).takeIf { root(it) == root(site) }?.let { hosts += it } } }
        hosts.any { h -> SessionStatus.cookiePairs(runCatching { cm.getCookie("https://$h/") }.getOrNull()).keys.any { SessionStatus.keepCookie(it) } }
    }.getOrDefault(true)

    fun hostOf(url: String?): String = runCatching { android.net.Uri.parse(url ?: "").host.orEmpty().removePrefix("www.") }.getOrDefault("")

    // ---------------- the list ----------------

    /**
     * [Mines-38.9.1] every account, read from the ONE identity store ([IdentityStore], which first pulls what 账号连接,
     * web AI sign-ins, AutoAccounts and saved passwords hold). Alerts / 2FA / last login are laid on top.
     */
    fun all(c: Context): List<Acct> {
        init(c); PlusVault.init(c); com.openminis.app.plus.group.GroupPrefs.init(c); AutoAccounts.init(c)
        runCatching { IdentityStore.sync(c) }
        val recs = IdentityStore.recs.value
        val open = _alerts.value.filter { it.second == "new" || it.second == "notme" }.map { it.first }
        return recs.map { r ->
            val legacy = keyOf(r.site, r.name, r.identity)
            val pw = r.hasPw || (r.site.isNotBlank() && runCatching { SavedLogins.find(c, r.site, r.identity) != null }.getOrDefault(false))
            val last = _events.value.filter { it.key == legacy || (r.identity.isBlank() && it.key.startsWith(keyOf(r.site, r.name, ""))) }.maxOfOrNull { it.at } ?: r.at
            val bad = open.any { al -> ((al.host.isNotBlank() && root(al.host) == root(r.site)) || al.site.equals(r.name, true)) &&
                (al.account.isBlank() || r.identity.isBlank() || al.account.equals(r.identity, true)) }
            // [Mines-38.9.3] 「登录失效」 only for a CONFIRMED sign-out (its platform's probe saw the login wall / cookie gone after
            // a real check); recorded-but-unverified, never-probed, network blips → 未检查 (grey), not a warning
            val st0 = when (SessionStatus.loginPill(r.status, bad, r.status == IdentityCore.EXPIRED && IdentityStore.confirmedOut(r.site))) {
                SessionStatus.Pill.BAD -> Status.ABNORMAL; SessionStatus.Pill.OK -> Status.OK
                SessionStatus.Pill.WARN -> Status.EXPIRED; SessionStatus.Pill.UNCHECKED -> Status.UNCHECKED
            }
            // [Mines-38.9.6 i13] user 2026-10-18 (Memoh 账号详情 正常 with no password and no session): a web sign-in with no saved
            // password counts as 正常 only while the site still holds a session cookie
            val st = if (st0 == Status.OK && !pw && r.method == IdentityCore.Method.WEB && !hasSessionCookie(r.site)) Status.EXPIRED else st0
            Acct(r.key, r.name.ifBlank { AutoAccounts.siteOf(r.site) }, r.site, r.identity, st, pw, totp(r.key) != null || totp(legacy) != null,
                r.sources.joinToString(" · ").ifBlank { "账号关联" }, last, method = r.method, provider = r.provider, via = r.via,
                identType = r.identType, legacyId = legacy)
        }.filterNot { it.method == IdentityCore.Method.KEY && it.source == "账号连接" }
            .sortedWith(compareBy<Acct> { it.status.rank }.thenBy { it.site.lowercase() })
    }

    /** [Mines-38.9.1] groups made from the identities that exist (any type), 「通过 Google」 accounts under that Gmail. */
    fun idGroups(c: Context, l: List<Acct>): List<Pair<IdentityCore.Group, List<Acct>>> {
        val byKey = l.associateBy { it.id }
        val recs = IdentityStore.recs.value.filter { it.key in byKey }
        return IdentityCore.groups(IdentityStore.current(c), recs).map { g -> g to g.recs.mapNotNull { byKey[it.key] } }
            .filter { it.second.isNotEmpty() || it.first.ident?.source == "手动添加" }
    }

    /** Sites signed in through a provider account (its detail page). */
    fun linkedVia(c: Context, a: Acct): List<Acct> {
        val t = a.identType ?: return emptyList()
        val keys = IdentityCore.linkedVia(IdentityStore.recs.value, t, a.account).map { it.key }.toSet()
        return all(c).filter { it.id in keys }
    }

    private fun looksLikeAccount(s: String) = s.contains('@') || s.count { it.isDigit() } >= 7 || (s.length in 3..32 && !s.contains(' '))

    /** Grouped by identity: phone numbers first, then mailboxes (by size), then 其他. */
    fun groups(l: List<Acct>): List<Pair<KeychainCore.Identity, List<Acct>>> =
        l.groupBy { it.identity.key }.map { (_, v) -> v.first().identity to v }
            .sortedWith(compareBy<Pair<KeychainCore.Identity, List<Acct>>> { it.first.kind.ordinal }.thenByDescending { it.second.size })

    fun find(c: Context, id: String): Acct? = all(c).firstOrNull { it.id == id }

    // ---------------- TOTP (encrypted) ----------------

    fun totpOf(a: Acct): KeychainCore.Totp? = totp(a.id) ?: a.legacyId.takeIf { it.isNotBlank() }?.let { totp(it) }
    fun totp(id: String): KeychainCore.Totp? = PlusVault.secretExtra("kc_totp", id.hashCode().toString(16) + id.length)?.let { KeychainCore.parseTotp(it) }
    fun setTotp(id: String, raw: String?) {
        PlusVault.setSecretExtra("kc_totp", id.hashCode().toString(16) + id.length, raw?.trim()?.takeIf { KeychainCore.parseTotp(it) != null })
        rev.value++
    }

    // ---------------- login history + alerts ----------------

    fun deviceName(): String = "本机 · " + (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() })

    /** A sign-in seen in a Mines WebView (AutoAccounts.onPage) → the account's last login on this phone. */
    fun recordLogin(c: Context, host: String, account: String, how: String) {
        init(c)
        _events.value = listOf(Event(keyOf(host, AutoAccounts.siteOf(host), account), deviceName(), "", System.currentTimeMillis(), how)) + _events.value
        save(c)
    }

    private fun seenIds(c: Context): MutableSet<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("seen", emptySet())!!.toMutableSet()

    private fun addAlert(c: Context, a: KeychainCore.Alert, msgId: String) {
        val seen = seenIds(c)
        if (msgId in seen || _alerts.value.any { it.first.key == a.key }) return
        seen += msgId
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("seen", seen.toList().takeLast(400).toSet()).apply()
        _alerts.value = listOf(a to "new") + _alerts.value
        _events.value = listOf(Event(keyOf(a.host, a.site, a.account), a.device.ifBlank { "新设备" }, a.place, a.at, "提醒 · " + a.source)) + _events.value
        save(c)
    }

    /** Called for every mail Mines reads (MailInbox): a 「new sign-in / 新设备登录」 mail from a site → an alert (≤ 3 days old). */
    fun onMail(c: Context, m: MailInbox.Mail) {
        init(c)
        if (System.currentTimeMillis() - m.at > 3 * 86_400_000L) return
        val host = root(m.fromAddr.substringAfter('@', ""))
        if (host.isBlank()) return
        val a = KeychainCore.detectAlert(AutoAccounts.siteOf(host), host, m.address, m.subject, m.text, m.at, "邮件") ?: return
        addAlert(c, a, "m:" + m.id)
    }

    /** An SMS 【站点】…异地登录 / 新设备… → an alert for the number it went to. */
    fun onSms(c: Context, body: String) {
        init(c)
        val site = Regex("[【\\[]([^】\\]]{2,16})[】\\]]").find(body)?.groupValues?.get(1)?.trim() ?: return
        val phone = runCatching { LoginAssist.phoneNumber(c) }.getOrNull().orEmpty()
        val a = KeychainCore.detectAlert(site, "", phone, "", body, System.currentTimeMillis(), "短信") ?: return
        addAlert(c, a, "s:" + body.hashCode())
    }

    fun pending(): KeychainCore.Alert? = _alerts.value.firstOrNull { it.second == "new" }?.first

    /** 「是我」: the alert is closed, the account back to 正常. */
    fun confirmMe(c: Context, a: KeychainCore.Alert) { _alerts.value = _alerts.value.map { if (it.first.key == a.key) it.first to "me" else it }; save(c) }
    /** 「不是我」: stays 异常登录 until the user says it is handled; returns the security page to open. */
    fun notMe(c: Context, a: KeychainCore.Alert): String {
        _alerts.value = _alerts.value.map { if (it.first.key == a.key) it.first to "notme" else it }; save(c)
        return KeychainCore.securityUrl(a.host.ifBlank { guessHost(c, a.site) })
    }
    fun handled(c: Context, acct: Acct) {
        _alerts.value = _alerts.value.map { (x, st) -> if ((st == "new" || st == "notme") && ((x.host.isNotBlank() && root(x.host) == root(acct.host)) || x.site.equals(acct.site, true))) x to "done" else x to st }
        save(c)
    }
    fun alertFor(acct: Acct): KeychainCore.Alert? = _alerts.value.firstOrNull { (x, st) -> (st == "new" || st == "notme") && ((x.host.isNotBlank() && root(x.host) == root(acct.host)) || x.site.equals(acct.site, true)) }?.first

    private fun guessHost(c: Context, site: String): String = all(c).firstOrNull { it.site.equals(site, true) }?.host.orEmpty()

    /** 消息 › 账号异常 cards (one per open alert). */
    fun digestItems(c: Context): List<Digest.Item> {
        init(c)
        return _alerts.value.filter { it.second == "new" || it.second == "notme" }.take(5).map { (a, _) ->
            Digest.Item("kc:" + a.key, "账号巡查", "patrol", a.site, "${a.site} 有新设备登录",
                listOf(a.device, a.place).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "看一下是不是你" }, a.at,
                act = "route:" + com.openminis.app.ui.plus.PlusRoutes.KEYCHAIN)
        }
    }

    /** AutoAccounts recorded a new account: glass toast inside Mines, system toast when Mines is in the background. */
    fun announce(c: Context, site: String, sub: String) {
        if (MinesJobs.appInForeground && _toast.tryEmit(site to sub)) return
        runCatching { android.widget.Toast.makeText(c.applicationContext, "已记下 $site 账号", android.widget.Toast.LENGTH_SHORT).show() }
    }
}

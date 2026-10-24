package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.IdentityCore.IdType
import com.openminis.app.plus.IdentityCore.Ident
import com.openminis.app.plus.IdentityCore.Method
import com.openminis.app.plus.IdentityCore.Rec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.1] THE identity store (user 2026-10-16 「账号之间记录也没打通」). Before this, every feature kept its own
 * list — AutoAccounts (site + account), SavedLogins (encrypted passwords), PlusVault (账号连接 entries), GroupPrefs
 * weblogin_* (web AI app sign-ins), linkres_* (一键关联 notes) — and 账号关联 only merged them for display, so nothing
 * written by one was ever read by another (一键关联 never knew which mailbox a site used; nobody knew a site was signed
 * in 「通过 Google」). Now every writer also writes here, every reader reads here:
 *  - [idents]: what the user signs in WITH (phone, mailboxes, Google / X / GitHub / … accounts, user names, passkeys),
 *    only what was seen or added by the user — nothing pre-filled;
 *  - [recs]: one record per site + identity with its login method and the provider identity it hangs off.
 * Secrets stay where they were (passwords in SavedLogins, keys / tokens in PlusVault): this store holds no secret.
 * [sync] pulls everything the old stores hold on every start (idempotent, nothing is removed), which is also the
 * one-time migration of existing data.
 */
object IdentityStore {
    private const val PREFS = "mines_identity_v1"
    @Volatile private var app: Context? = null
    private val _idents = MutableStateFlow<List<Ident>>(emptyList())
    val idents: StateFlow<List<Ident>> = _idents
    private val _recs = MutableStateFlow<List<Rec>>(emptyList())
    val recs: StateFlow<List<Rec>> = _recs
    private var lastSync = 0L

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _idents.value = runCatching { JSONArray(p.getString("idents", "[]")).let { a -> (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).let { o ->
            IdType.of(o.optString("t"))?.let { Ident(it, o.optString("v"), o.optString("n"), o.optString("s"), o.optBoolean("in"), o.optLong("at"), o.optInt("u")) } } } } }.getOrDefault(emptyList())
        _recs.value = runCatching { JSONArray(p.getString("recs", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Rec(o.optString("site"), o.optString("name"), o.optString("id"), IdType.of(o.optString("it")), Method.of(o.optString("m")),
                IdType.of(o.optString("p")), o.optString("via"), o.optString("st", IdentityCore.LINKED),
                o.optJSONArray("src")?.let { s -> (0 until s.length()).map { s.optString(it) }.toSet() } ?: emptySet(),
                o.optBoolean("pw"), o.optLong("at")) } } } }.getOrDefault(emptyList())
    }

    private fun save() {
        val c = app ?: return
        val a = JSONArray(); _idents.value.forEach { i -> a.put(JSONObject().put("t", i.type.id).put("v", i.value).put("n", i.note).put("s", i.source)
            .put("in", i.signedIn).put("at", i.at).put("u", i.uses)) }
        val r = JSONArray(); _recs.value.take(600).forEach { x -> r.put(JSONObject().put("site", x.site).put("name", x.name).put("id", x.identity)
            .put("it", x.identType?.id ?: "").put("m", x.method.id).put("p", x.provider?.id ?: "").put("via", x.via).put("st", x.status)
            .put("src", JSONArray(x.sources.toList())).put("pw", x.hasPw).put("at", x.at)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("idents", a.toString()).putString("recs", r.toString()).apply()
        runCatching { Keychain.rev.value++ }
    }

    // ---------------- writes ----------------

    @Synchronized fun addIdent(c: Context, i: Ident) { init(c); val n = IdentityCore.mergeIdent(_idents.value, i); if (n != _idents.value) { _idents.value = n; save() } }

    @Synchronized fun removeIdent(c: Context, key: String) { init(c); _idents.value = _idents.value.filterNot { it.key == key }; save() }

    /** Add / upgrade one account record; its identity (and provider identity) are registered too. */
    /** smoke only (GroupSelfTest 'i396 memohbad'): store a record exactly as an older build did, bypassing the checks. */
    @Synchronized fun smokeInject(c: Context, r: Rec) { init(c); _recs.value = _recs.value.filterNot { it.key == r.key } + r; save() }

    @Synchronized fun addRec(c: Context, r: Rec) {
        // [Mines-38.9.6 i13] never store control-char / escape-text account names
        if (LoginValueCore.badAccount(r.identity)) return addRec(c, r.copy(identity = LoginValueCore.cleanAccount(r.identity), identType = null))
        init(c)
        if (r.site.isBlank()) return
        var ids = _idents.value
        if (r.identity.isNotBlank()) ids = IdentityCore.mergeIdent(ids, Ident(r.identType ?: IdentityCore.guessType(r.identity), r.identity, source = r.sources.firstOrNull().orEmpty(), at = r.at))
        if (r.via.isNotBlank()) IdType.of(r.via.substringBefore(':'))?.let { t -> ids = IdentityCore.mergeIdent(ids, Ident(t, r.via.substringAfter(':'), source = "第三方登录", at = r.at)) }
        val n = IdentityCore.merge(_recs.value, r)
        if (n != _recs.value || ids != _idents.value) { _recs.value = n; _idents.value = ids; save() }
    }

    /** 一键关联 / any in-app sign-in finished with [plan]: the method that worked is recorded for the site. */
    fun recordSuccess(c: Context, host: String, name: String, plan: IdentityCore.Plan?) {
        val site = Keychain.root(host).ifBlank { host }
        if (site.isBlank()) return
        val id = plan?.ident
        val r = when (plan?.method) {
            null -> Rec(site, name, "", null, Method.WEB, sources = setOf("网页登录"), at = System.currentTimeMillis())
            Method.OAUTH -> Rec(site, name, "", null, Method.OAUTH, plan.provider, via = id?.let { plan.provider!!.id + ":" + it.value }.orEmpty(),
                sources = setOf("一键关联"), at = System.currentTimeMillis())
            else -> Rec(site, name, id?.value.orEmpty(), id?.type, plan.method, sources = setOf("一键关联"), at = System.currentTimeMillis())
        }
        addRec(c, r)
        // [Mines-38.9.5 i] remembered for next time (method, account, code mailbox, encrypted session snapshot)
        runCatching { LoginMemory.record(c, host, if (plan?.method == Method.OAUTH) plan.ident?.value.orEmpty() else id?.value.orEmpty(),
            LoginMemory.methodOf(plan), if (plan?.method == Method.EMAIL_CODE) CodeSources.lastVia else "") }
        if (id != null) { init(c); _idents.value = _idents.value.map { if (it.key == id.key) it.copy(uses = it.uses + 1, at = System.currentTimeMillis()) else it }; save() }
    }

    fun recFor(c: Context, host: String): Rec? { init(c); val s = Keychain.root(host); return _recs.value.filter { it.site == s }.maxByOrNull { if (it.method != Method.UNKNOWN && it.method != Method.WEB) it.at + Long.MAX_VALUE / 2 else it.at } }

    // ---------------- signed-in providers (cookies in Mines' own WebView store) ----------------

    /** Which third-party providers the user is signed into inside Mines right now (one-tap 「用 X 登录」). */
    fun signedInProviders(): Map<IdType, String> {
        val out = HashMap<IdType, String>()
        val cm = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull() ?: return out
        fun ck(u: String) = runCatching { cm.getCookie(u) }.getOrNull().orEmpty()
        if (MailParse.cookieHas(ck("https://accounts.google.com/"), listOf("SID", "__Secure-1PSID"))) out[IdType.GOOGLE] = ""
        ck("https://github.com/").let { s -> if (Regex("(^|; )logged_in=yes").containsMatchIn(s)) out[IdType.GITHUB] = Regex("dotcom_user=([^;]+)").find(s)?.groupValues?.get(1).orEmpty() }
        if (MailParse.cookieHas(ck("https://x.com/"), listOf("auth_token"))) out[IdType.X] = ""
        if (MailParse.cookieHas(ck("https://login.live.com/"), listOf("MSPAuth", "__Host-MSAAUTH"))) out[IdType.MICROSOFT] = ""
        if (MailParse.cookieHas(ck("https://appleid.apple.com/"), listOf("myacinfo", "aidsp"))) out[IdType.APPLE] = ""
        return out
    }

    /** Idents with [Ident.signedIn] refreshed from the cookie store (a provider with an unknown handle borrows its mailbox). */
    fun current(c: Context): List<Ident> {
        init(c)
        val sp = signedInProviders()
        var ids = _idents.value.map { if (it.type.provider) it.copy(signedIn = it.type in sp) else it }
        for ((t, handle) in sp) {
            if (ids.any { it.type == t }) continue
            val v = handle.ifBlank { if (t == IdType.GOOGLE) ids.firstOrNull { it.type == IdType.EMAIL && it.value.endsWith("@gmail.com", true) }?.value.orEmpty() else "" }
            if (v.isNotBlank()) ids = ids + Ident(t, v, source = "Mines 里已登录", signedIn = true, at = System.currentTimeMillis())
        }
        if (ids != _idents.value) synchronized(this) { _idents.value = ids; save() }
        return ids
    }

    /**
     * [Mines-38.9.3] migration / clean-up (idempotent):
     *  - a record named after a mail sender (「Matt from WarpStati.」) gets its real site name;
     *  - records that are only an API-key service imported from 账号连接 leave the login list (they show in 账号 › 服务);
     *  - the 38.9.1 rule wrote 过期了 for anything not seen signed in: an 过期了 record whose platform is not confirmed signed
     *    out is 未检查 now (re-checked before any badge).
     */
    @Synchronized fun cleanup(c: Context) {
        init(c)
        runCatching { AutoAccounts.cleanNames(c) }
        val before = _recs.value
        val after = before.mapNotNull { r ->
            if (r.method == Method.KEY && r.sources.isNotEmpty() && r.sources.all { it == "账号连接" }) return@mapNotNull null
            var x = r
            // [Mines-38.9.6 i13] 「\u0001\u0001」 accounts saved by the evaluateJavascript decode bug → no account (re-filled on next sign-in)
            if (LoginValueCore.badAccount(x.identity)) x = x.copy(identity = LoginValueCore.cleanAccount(x.identity), identType = null)
            if (r.sources.any { it.contains("邮件") } && IdentityCore.looksLikeSenderName(r.name) && r.site.contains('.'))
                x = x.copy(name = Keychain.siteName(r.site))
            if (x.status == IdentityCore.EXPIRED) {
                val pid = runCatching { com.openminis.app.ui.plus.LinkAll.platformFor(x.site) }.getOrNull()
                val confirmed = pid != null && runCatching { com.openminis.app.ui.plus.LinkAll.state(pid) == com.openminis.app.ui.plus.LinkAll.St.EXPIRED }.getOrDefault(false)
                if (!confirmed) x = x.copy(status = IdentityCore.UNCHECKED)
            }
            x
        }
        val dedup = after.distinctBy { it.key }
        if (dedup != before) { _recs.value = dedup; save() }
    }

    /** [Mines-38.9.3] the record is confirmed signed out right now (its platform's probe / key test says so). */
    fun confirmedOut(site: String): Boolean = runCatching {
        val pid = com.openminis.app.ui.plus.LinkAll.platformFor(site) ?: return false
        com.openminis.app.ui.plus.LinkAll.state(pid) == com.openminis.app.ui.plus.LinkAll.St.EXPIRED
    }.getOrDefault(false)

    /** [Mines-38.9.3] status after a re-check (UI) */
    fun setStatus(c: Context, key: String, status: String) {
        init(c)
        val n = _recs.value.map { if (it.key == key) it.copy(status = status, at = System.currentTimeMillis()) else it }
        if (n != _recs.value) synchronized(this) { _recs.value = n; save() }
    }

    // ---------------- pull from the old stores (migration + keeps them in step) ----------------

    fun sync(c: Context, force: Boolean = false) {
        init(c)
        if (!force && System.currentTimeMillis() - lastSync < 20_000L) return
        lastSync = System.currentTimeMillis()
        runCatching { cleanup(c) }
        runCatching { PlusVault.init(c); com.openminis.app.plus.group.GroupPrefs.init(c); AutoAccounts.init(c) }
        val now = System.currentTimeMillis()
        // phone of this SIM (only when the permission was granted; never typed in by Mines)
        runCatching { LoginAssist.phoneNumber(c) }.getOrNull()?.let { addIdent(c, Ident(IdType.PHONE, it, source = "本机号码", at = now)) }
        // mailboxes the user added to 消息 › 邮箱 (IMAP / web)
        runCatching { MailInbox.load(c) }.getOrDefault(emptyList()).forEach { a ->
            if (a.address.contains('@')) addIdent(c, Ident(IdType.EMAIL, a.address, source = "邮箱", at = now))
        }
        // accounts Mines noticed (sign-ups / sign-ins, SMS, mail)
        AutoAccounts.list.value.forEach { e ->
            addRec(c, Rec(Keychain.root(e.host).ifBlank { e.site.lowercase() }, e.site, e.account, null,
                if (e.source == "短信") Method.PHONE_SMS else Method.UNKNOWN,
                status = if (e.status == AutoAccounts.LINKED) IdentityCore.LINKED else IdentityCore.USER, sources = setOf("自动记下 · " + e.source), at = e.at))
        }
        // saved passwords (Edge / Chrome import, 用 Mines 填入, autofill saves)
        runCatching { SavedLogins.all(c) }.getOrDefault(emptyList()).forEach { e ->
            if (e.host.isBlank()) return@forEach
            val t = IdentityCore.guessType(e.user)
            addRec(c, Rec(Keychain.root(e.host), e.name.ifBlank { AutoAccounts.siteOf(e.host) }, e.user, t,
                when (t) { IdType.EMAIL -> Method.EMAIL_PW; IdType.PHONE -> Method.PHONE_PW; else -> Method.USER_PW },
                sources = setOf("密码"), hasPw = true, at = 0L))
        }
        // 账号连接 entries + web AI apps (their sign-in state)
        runCatching {
            for (id in com.openminis.app.ui.plus.LinkAll.platforms()) {
                val st = com.openminis.app.ui.plus.LinkAll.state(id)
                if (st != com.openminis.app.ui.plus.LinkAll.St.LINKED && st != com.openminis.app.ui.plus.LinkAll.St.EXPIRED &&
                    st != com.openminis.app.ui.plus.LinkAll.St.UNKNOWN) continue
                val k = com.openminis.app.ui.plus.LinkAll.connKind(id)
                val name = com.openminis.app.ui.plus.LinkAll.name(id)
                val host = if (k != null) Keychain.hostOf(k.loginUrl ?: k.site ?: k.panelUrl ?: k.keyUrl) else Keychain.hostOf(com.openminis.app.plus.group.AiCatalog.spec(id)?.webUrl)
                if (host.isBlank()) continue
                val e = if (k != null) PlusVault.defaultAccount(k.id)?.let { PlusVault.entries.value[it] } else null
                val acct = e?.account.orEmpty().takeIf { it.contains('@') || it.count { ch -> ch.isDigit() } >= 7 || (it.length in 3..32 && !it.contains(' ')) }.orEmpty()
                val m = when (e?.via) { "oauth" -> Method.OAUTH; "key", "token", "model" -> Method.KEY; else -> Method.WEB }
                // [Mines-38.9.3] an API-key / token service lives in 账号 › 服务 only (each site once); it is never a web login
                if (m == Method.KEY) continue
                val prov = if (m == Method.OAUTH) when (k?.method) { ConnMethod.GITHUB -> IdType.GITHUB; ConnMethod.MICROSOFT -> IdType.MICROSOFT; ConnMethod.GOOGLE -> IdType.GOOGLE; else -> null } else null
                addRec(c, Rec(Keychain.root(host), name, if (prov != null) "" else acct, null, m, prov, via = if (prov != null && acct.isNotBlank()) prov.id + ":" + acct else "",
                    status = when (st) { com.openminis.app.ui.plus.LinkAll.St.LINKED -> IdentityCore.LINKED
                        com.openminis.app.ui.plus.LinkAll.St.EXPIRED -> IdentityCore.EXPIRED; else -> IdentityCore.UNCHECKED },
                    sources = setOf(if (k != null) "账号连接" else "网页登录"), at = e?.updatedAt ?: 0L))
            }
        }
        // a signed-in provider is itself an account (its detail lists the sites signed in through it)
        current(c).filter { it.type.provider && it.value.isNotBlank() }.forEach { i ->
            if (i.type.host.isNotBlank() && i.signedIn) addRec(c, Rec(i.type.host, i.type.label, i.value, i.type, Method.WEB, sources = setOf("Mines 里已登录"), at = now))
        }
    }
}

package com.openminis.app.plus

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.8] user 2026-10-16 (memoh: registered, never recorded): every account Mines sees being made or used is
 * written down by itself, no reminder needed —
 *  - a sign-up / sign-in finished in a Mines WebView (agent browser, in-app sign-in pages): an auth page
 *    (login / register / signup / 登录 / 注册 …) of a site, then a normal page of the same site with cookies → 已关联;
 *  - an SMS code or a welcome / verification mail from a site that is not in 账号关联 yet → 需要你操作 (until a sign-in
 *    is seen, which turns it into 已关联).
 * Kept on the phone (site, account = email / phone / user name typed or the number the code went to, status, time),
 * deduplicated by site + account, shown in 关联结果 as 「自动记下的账号」, and the user is told once: 「已记下 <站点> 账号」.
 * Never stores passwords or codes.
 */
object AutoAccounts {
    data class Entry(val site: String, val host: String, val account: String, val status: String, val source: String, val at: Long)

    const val LINKED = "已关联"
    const val USER = "需要你操作"
    private const val PREFS = "mines_auto_accounts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _list = MutableStateFlow<List<Entry>>(emptyList())
    val list: StateFlow<List<Entry>> = _list
    @Volatile private var app: Context? = null

    private val AUTH = Regex("(?i)(log-?in|sign-?in|sign_in|sign-?up|sign_up|register|registration|join|passport|auth|account/new|注册|登录|登陆)")
    private val STEP = Regex("(?i)(verify|verification|confirm|captcha|2fa|mfa|otp|challenge)")

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        _list.value = load(c.applicationContext)
    }

    private fun load(c: Context): List<Entry> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Entry(o.optString("site"), o.optString("host"), o.optString("account"), o.optString("status"), o.optString("source"), o.optLong("at")) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, l: List<Entry>) {
        val a = JSONArray(); l.forEach { e -> a.put(JSONObject().put("site", e.site).put("host", e.host).put("account", e.account)
            .put("status", e.status).put("source", e.source).put("at", e.at)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s·._\\-]"), "").removePrefix("www")

    /** Already in 账号关联 (a linked platform / source with the same name or host)? */
    private fun known(site: String, host: String): Boolean = runCatching {
        val n = norm(site); val h = host.removePrefix("www.").substringBeforeLast('.').substringAfterLast('.')
        com.openminis.app.ui.plus.LinkAll.platforms().any { id ->
            val nm = norm(com.openminis.app.ui.plus.LinkAll.name(id))
            com.openminis.app.ui.plus.LinkAll.state(id) == com.openminis.app.ui.plus.LinkAll.St.LINKED &&
                (nm == n || (h.length >= 3 && nm.contains(norm(h))))
        }
    }.getOrDefault(false)

    /** Readable site name from a host: memoh.ai → Memoh, app.memoh.io → Memoh, passport.bilibili.com → Bilibili. */
    fun siteOf(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return host
        val sld = if (parts.size >= 3 && parts[parts.size - 2] in setOf("com", "net", "org", "co", "gov", "edu")) parts[parts.size - 3]
            else if (parts.size >= 2) parts[parts.size - 2] else parts[0]
        return sld.replaceFirstChar { it.uppercase() }
    }

    /** Add or upgrade one account; tells the user once (「已记下 X 账号」). Returns the line, or null when nothing new. */
    fun record(c: Context, site: String, host: String, account: String, status: String, source: String): String? {
        init(c)
        val s = site.trim().take(24); if (s.isBlank()) return null
        val acct = account.trim().take(64)
        val cur = _list.value
        // [Mines-38.9.1] every record also goes into the one identity store
        runCatching { IdentityStore.addRec(c, IdentityCore.Rec(Keychain.root(host).ifBlank { s.lowercase() }, s, acct, null,
            if (source == "短信") IdentityCore.Method.PHONE_SMS else IdentityCore.Method.UNKNOWN,
            status = if (status == LINKED) IdentityCore.LINKED else IdentityCore.USER, sources = setOf("自动记下 · $source"), at = System.currentTimeMillis())) }
        val same = cur.firstOrNull { norm(it.site) == norm(s) && (it.account.isBlank() || acct.isBlank() || it.account.equals(acct, true)) }
        if (same != null) {
            // dedup: only fill a missing account / upgrade 需要你操作 → 已关联
            val up = same.copy(account = same.account.ifBlank { acct }, status = if (status == LINKED) LINKED else same.status,
                host = same.host.ifBlank { host })
            if (up != same) { val l = cur.map { if (it === same) up else it }; _list.value = l; save(c, l) }
            return null
        }
        if (known(s, host)) return null
        val l = listOf(Entry(s, host, acct, status, source, System.currentTimeMillis())) + cur
        _list.value = l.take(200); save(c, _list.value)
        val line = "已记下 $s 账号"
        // [Mines-38.9] glass toast 「已记下 X 账号 · 账号 · 有密码」 with 查看 › (Keychain), system toast when Mines is in the background
        scope.launch { runCatching {
            val pw = runCatching { SavedLogins.find(c, host, acct) != null }.getOrDefault(false)
            Keychain.announce(c, s, listOf(KeychainCore.maskAccount(acct).let { if (it.length > 16) it.take(12) + "…" else it }, if (pw) "有密码" else "").filter { it.isNotBlank() }.joinToString(" · "))
        } }
        return line
    }

    /** [Mines-38.9.3] rename entries recorded from a mail with the sender's display name (「Matt from WarpStati.」). */
    fun cleanNames(c: Context) {
        init(c)
        val l = _list.value.map { e ->
            if (e.source == "邮件" && e.host.isNotBlank() && IdentityCore.looksLikeSenderName(e.site)) e.copy(site = Keychain.siteName(e.host)) else e
        }
        if (l != _list.value) { _list.value = l; save(c, l) }
    }

    fun remove(c: Context, e: Entry) { init(c); val l = _list.value.filterNot { it == e }; _list.value = l; save(c, l) }

    // ---------------- WebView sign-up / sign-in ----------------

    private class Seen(val host: String, var at: Long, var account: String = "")
    private val seen = java.util.WeakHashMap<WebView, Seen>()

    private const val ACCT_JS = """(function(){try{var q=document.querySelectorAll('input');var best='';for(var i=0;i<q.length;i++){var e=q[i];
var t=(e.type||'').toLowerCase(),n=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.autocomplete||'')).toLowerCase();
if(t==='password'||t==='hidden'||!e.value)continue;
if(t==='email'||t==='tel'||/mail|phone|mobile|user|account|login|手机|邮箱|账号|用户名/.test(n)){best=e.value;if(t==='email'||/mail|邮箱/.test(n))break;}}
return best.slice(0,64);}catch(x){return ''}})()"""

    private fun hostOf(url: String) = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("").lowercase()
    private fun root(h: String) = h.removePrefix("www.").split('.').takeLast(2).joinToString(".")

    /** Call from a WebViewClient's onPageFinished (agent browser, in-app sign-in pages). */
    fun onPage(view: WebView, url: String?) {
        val u = url ?: return
        if (!u.startsWith("http")) return
        val c = view.context.applicationContext; init(c)
        val h = hostOf(u); if (h.isBlank()) return
        val path = runCatching { android.net.Uri.parse(u).let { (it.path.orEmpty() + "?" + it.query.orEmpty()) } }.getOrDefault("")
        val isAuth = AUTH.containsMatchIn(path) || AUTH.containsMatchIn(h.substringBefore('.'))
        val s = seen[view]
        if (isAuth) {
            val st = if (s != null && root(s.host) == root(h)) s.also { it.at = System.currentTimeMillis() } else Seen(h, System.currentTimeMillis())
            seen[view] = st
            // keep reading what was typed into the email / phone / user-name box while the auth page is open
            scope.launch {
                repeat(90) {
                    if (seen[view] !== st || hostOf(view.url.orEmpty()).let { root(it) != root(h) }) return@launch
                    view.evaluateJavascript(ACCT_JS) { v -> v?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }?.let { st.account = it } }
                    delay(2000)
                }
            }
            return
        }
        if (s == null || root(s.host) != root(h) || System.currentTimeMillis() - s.at > 15 * 60_000L) return
        if (STEP.containsMatchIn(path)) return
        val cookies = runCatching { android.webkit.CookieManager.getInstance().getCookie(u).orEmpty() }.getOrDefault("")
        if (cookies.isBlank()) return
        seen.remove(view)
        runCatching { Keychain.recordLogin(c, root(h), s.account, "网页登录") }   // [Mines-38.9] last login on this phone
        record(c, siteOf(h), root(h), s.account, LINKED, "网页登录")
        // [Mines-38.9.1] the one identity store learns which identity + method signed in (一键关联 plan, or what was typed)
        runCatching {
            val plan = LoginAssist.planFor(h)
            if (plan != null) IdentityStore.recordSuccess(c, h, siteOf(h), plan)
            else IdentityStore.addRec(c, IdentityCore.Rec(Keychain.root(h), siteOf(h), s.account, null, IdentityCore.Method.WEB, sources = setOf("网页登录"), at = System.currentTimeMillis()))
        }
    }

    // ---------------- SMS codes / welcome mail ----------------

    private val SIGN = Regex("[【\\[]([^】\\]]{2,16})[】\\]]")
    private val SIGNUP_WORDS = Regex("(?i)(注册|登录|登陆|验证码|欢迎|sign ?up|log ?in|verification|verify|welcome|confirm)")

    /** An SMS with a code from 【站点】: not in 账号关联 yet → recorded as 需要你操作 with the number it went to. */
    fun onSms(c: Context, body: String) {
        val m = SIGN.find(body) ?: return
        if (!SIGNUP_WORDS.containsMatchIn(body)) return
        val site = m.groupValues[1].trim()
        if (Regex("(银行|支付|快递|运营商|移动|联通|电信|医保|社保|税务|公安|12306)").containsMatchIn(site)) return
        val phone = runCatching { LoginAssist.phoneNumber(c) }.getOrNull().orEmpty()
        val done = Regex("(注册成功|欢迎|已开通|welcome)", RegexOption.IGNORE_CASE).containsMatchIn(body)
        record(c, site, "", phone, if (done) LINKED else USER, "短信")
    }

    /** A welcome / verify-your-email mail from a site → recorded with the mailbox it was sent to. */
    fun onMail(c: Context, from: String, fromAddr: String, subject: String, to: String) {
        if (!Regex("(?i)(welcome|欢迎|verify your|confirm your|activate|激活|注册|account created|验证您的邮箱|验证你的邮箱)").containsMatchIn(subject)) return
        val host = fromAddr.substringAfter('@', "").lowercase()
        if (host.isBlank() || Regex("(?i)(gmail|163|126|qq|outlook|hotmail|yahoo|icloud)\\.").containsMatchIn(host)) return
        // [Mines-38.9.3] the site is named from its domain / the catalog, never from the From display name
        // (「Matt from WarpStatic」 became a site row under Gmail)
        val site = Keychain.siteName(host)
        val done = Regex("(?i)(welcome|欢迎|account created)").containsMatchIn(subject)
        record(c, site, root(host), to, if (done) LINKED else USER, "邮件")
    }
}

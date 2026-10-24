package com.openminis.app.plus

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [Mines-38.7] 多账号隔离 (user 2026-10-15 「如果是多账号的，一定要做好隔离」): every account of one platform keeps its own
 * cookies, localStorage and token, and signing one in / out / expiring never touches another.
 *
 * Account ids: "x" = the platform's first account (the app's default WebView store, so existing logins stay),
 * "x#2", "x#3" … = further accounts.
 *
 * Two ways, picked per phone:
 *  - PROFILE (WebView supports androidx.webkit MULTI_PROFILE): "x#n" uses its own WebView profile "mines_acct_x_n"
 *    (own cookies, localStorage, IndexedDB, cache). Accounts can be used at the same time.
 *  - SWAP (older WebView, e.g. the API 34 emulator): the shared store is switched per account. Before an account's
 *    WebView loads anything, the cookies of the platform's domains are saved for the account that had them and replaced
 *    by this account's saved set; localStorage is saved after each use and put back by a document-start script that
 *    runs before the page's own code. One account of a platform at a time — callers hold [lock] for the whole use.
 * Saved sets live in the encrypted vault (PlusVault secret extras), never in plain prefs.
 */
object AccountJar {
    /** smoke: force SWAP even where profiles work, so both paths are tested. */
    @Volatile var forceSwap = false

    fun profiles(): Boolean = !forceSwap && LoginWeb.profilesSupported()
    fun mode(): String = if (profiles()) "profile" else "swap"

    fun base(acct: String) = acct.substringBefore('#')
    fun isExtra(acct: String) = acct.contains('#')

    private val locks = HashMap<String, Mutex>()
    /** One user of a platform's shared store at a time (SWAP); a no-op contention-wise for PROFILE accounts. */
    fun lock(acct: String): Mutex = synchronized(locks) {
        locks.getOrPut(if (profiles()) acct else base(acct)) { Mutex() }
    }

    private fun profileName(acct: String) = "mines_acct_" + acct.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** The cookie store [acct] uses right now (its profile, or the shared one). */
    fun cookieManager(acct: String): CookieManager {
        if (isExtra(acct) && profiles()) runCatching {
            return androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profileName(acct)).cookieManager
        }
        return CookieManager.getInstance()
    }

    // ---------------- SWAP bookkeeping ----------------

    private fun activeKey(base: String) = "jar_active_" + base
    fun active(base: String): String? = PlusVault.extra("accountjar", activeKey(base))?.takeIf { it.isNotBlank() }
    private fun setActive(base: String, acct: String?) = PlusVault.setExtra("accountjar", activeKey(base), acct ?: "")

    private fun saved(acct: String): JSONObject = runCatching { JSONObject(PlusVault.secretExtra("accountjar", acct) ?: "{}") }.getOrDefault(JSONObject())
    private fun putSaved(acct: String, o: JSONObject?) = PlusVault.setSecretExtra("accountjar", acct, o?.toString())

    private fun hostOf(u: String) = runCatching { java.net.URI(u).host }.getOrNull().orEmpty()
    private fun root(host: String): String {
        val p = host.split('.')
        return if (p.size <= 2) host else p.takeLast(if (p[p.size - 2].length <= 3 && p.last().length <= 2) 3 else 2).joinToString(".")
    }

    /** "a=b; c=d" → map. */
    private fun pairs(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull { kv ->
        val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
    }.toMap()

    private fun isIp(h: String) = h.matches(Regex("[0-9.]+")) || h.contains(':') || h == "localhost"

    /** The page urls whose cookies make one session: host + registrable root (+www), same scheme / port as given. */
    private fun urlsOf(urls: List<String>): List<String> = urls.flatMap { u ->
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return@flatMap emptyList()
        val h = uri.host ?: return@flatMap emptyList()
        val sch = uri.scheme ?: "https"
        val port = if (uri.port > 0) ":" + uri.port else ""
        if (isIp(h)) listOf("$sch://$h$port/") else listOf("$sch://$h$port/", "$sch://" + root(h) + "/", "$sch://www." + root(h) + "/")
    }.distinct()

    private fun attrs(u: String): String {
        val h = hostOf(u)
        // [Mines-38.9.3] Max-Age: a swapped-back session was written as a SESSION cookie and died with the process (→ 「登录失效」)
        return "; Path=/; Max-Age=" + (SessionStatus.KEEP_MS / 1000) + (if (isIp(h)) "" else "; Domain=." + root(h)) + (if (u.startsWith("https")) "; Secure; SameSite=None" else "")
    }

    /** Snapshot of the shared store for [urls] (cookies per url). */
    private fun snapshotCookies(urls: List<String>): JSONObject {
        val cm = CookieManager.getInstance()
        val o = JSONObject()
        for (u in urlsOf(urls)) {
            val p = pairs(runCatching { cm.getCookie(u) }.getOrNull())
            if (p.isNotEmpty()) o.put(u, JSONObject(p as Map<*, *>))
        }
        return o
    }

    private fun clearCookies(urls: List<String>) {
        val cm = CookieManager.getInstance()
        for (u in urlsOf(urls)) {
            val names = pairs(runCatching { cm.getCookie(u) }.getOrNull()).keys
            val h = hostOf(u); val r = root(h)
            for (n in names) LoginValueCore.deleteLines(n, r, isIp(h)).forEach { cm.setCookie(u, it) }   // [Mines-38.9.6 i13] __Host- aware
        }
    }

    private fun restoreCookies(o: JSONObject) {
        val cm = CookieManager.getInstance()
        for (u in o.keys()) {
            val m = o.optJSONObject(u) ?: continue
            // [Mines-38.9.6 i13] __Host- cookies were written with Domain= → rejected → swapped-back session gone
            for (n in m.keys()) { val h = hostOf(u); cm.setCookie(u, LoginValueCore.cookieLine(n, m.optString(n), if (isIp(h)) "" else root(h), u.startsWith("https"), SessionStatus.KEEP_MS / 1000, sameSiteNone = true)) }
        }
    }

    /**
     * Makes the shared store hold [acct]'s session for [urls] (SWAP); a no-op with profiles or when it already does.
     * Main thread. Callers hold [lock] and destroy WebViews of the previous account first.
     */
    suspend fun activate(acct: String, urls: List<String>) = withContext(Dispatchers.Main) { activateNow(acct, urls) }

    /** Same as [activate], on the caller's (main) thread: the 账号连接 login screen swaps before its WebView loads. */
    fun activateNow(acct: String, urls: List<String>) {
        if (profiles()) return   // profiles: "x" = default store, "x#n" = own profile; nothing to swap
        val b = base(acct)
        val cur = active(b) ?: b      // nobody swapped yet: the store holds the first account
        if (cur == acct) return
        // save who was there (cookies; localStorage was saved by [saveLocal] after its last use)
        if (cur != "-") { val keep = saved(cur); keep.put("cookies", snapshotCookies(urls)); putSaved(cur, keep) }
        clearCookies(urls)
        saved(acct).optJSONObject("cookies")?.let { restoreCookies(it) }
        runCatching { CookieManager.getInstance().flush() }
        setActive(b, acct)
    }

    /** After a use / sign-in: remember [acct]'s current cookies (SWAP) so a later swap back restores them exactly. */
    suspend fun remember(acct: String, urls: List<String>) = withContext(Dispatchers.Main) { rememberNow(acct, urls) }

    fun rememberNow(acct: String, urls: List<String>) {
        if (profiles()) return
        if ((active(base(acct)) ?: base(acct)) != acct) return
        val keep = saved(acct); keep.put("cookies", snapshotCookies(urls)); putSaved(acct, keep)
    }

    /**
     * 账号连接 sign-in of an extra account finished (SWAP): keep its session (cookies + this page's localStorage) and give
     * the shared store back to the first account, so the in-app browser / first account never see the extra one.
     */
    fun afterLogin(wv: WebView?, acct: String, urls: List<String>) {
        if (profiles() || !isExtra(acct)) return
        val done = { dump: String? -> saveLocal(acct, dump); rememberNow(acct, urls); activateNow(base(acct), urls) }
        if (wv == null) { done(null); return }
        runCatching { wv.evaluateJavascript(DUMP_LOCAL_JS) { r -> done(runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull()) } }
            .onFailure { done(null) }
    }

    /** JS returning the page origin's localStorage as JSON (stored per account per origin, SWAP only). */
    const val DUMP_LOCAL_JS = "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k!='__mines_acct')o[k]=localStorage.getItem(k);}return JSON.stringify({origin:location.origin,ls:o});}catch(e){return ''}})()"

    fun saveLocal(acct: String, dump: String?) {
        if (profiles() || dump.isNullOrBlank()) return
        val d = runCatching { JSONObject(dump) }.getOrNull() ?: return
        val keep = saved(acct)
        val ls = keep.optJSONObject("ls") ?: JSONObject()
        ls.put(d.optString("origin"), d.optJSONObject("ls") ?: JSONObject())
        keep.put("ls", ls); putSaved(acct, keep)
    }

    /**
     * Prepares a WebView for [acct] before anything is loaded: its own profile (PROFILE), or the document-start script
     * that swaps localStorage to [acct]'s saved set the first time each origin loads for this account (SWAP).
     * Returns false when the account cannot be isolated on this phone (no profile, no document-start script).
     */
    fun prepare(wv: WebView, acct: String): Boolean {
        if (profiles()) {
            if (!isExtra(acct)) return true
            return runCatching {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profileName(acct))
                WebViewCompat.setProfile(wv, profileName(acct)); true
            }.getOrDefault(false)
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        val ls = saved(acct).optJSONObject("ls") ?: JSONObject()
        // the first account adopts whatever the store already holds (a sign-in from before 38.7 is not wiped)
        val js = "(function(){try{var me=" + JSONObject.quote(acct) + ";var cur=localStorage.getItem('__mines_acct');if(cur===me)return;" +
            "var all0=" + ls.toString() + ";if(!cur&&" + (!isExtra(acct)) + "&&!all0[location.origin]){localStorage.setItem('__mines_acct',me);return;}" +
            "var all=" + ls.toString() + ";var d=all[location.origin]||{};localStorage.clear();" +
            "for(var k in d){try{localStorage.setItem(k,d[k]);}catch(e){}}localStorage.setItem('__mines_acct',me);}catch(e){}})();"
        return runCatching { WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*")); true }.getOrDefault(false)
    }

    /**
     * Signs [acct] out on this phone only: its cookies / profile / saved set are deleted; other accounts untouched.
     * Main thread; callers destroy the account's WebViews first.
     */
    suspend fun signOut(acct: String, urls: List<String>) = withContext(Dispatchers.Main) {
        if (profiles() && isExtra(acct)) {
            runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(profileName(acct)) }
                .onFailure { runCatching { cookieManager(acct).removeAllCookies(null) } }
        } else {
            // the shared store: clear it only when it holds this account's session right now
            if ((active(base(acct)) ?: base(acct)) == acct) {
                clearCookies(urls)
                if (!profiles()) setActive(base(acct), "-")   // nobody is in the store now
            }
        }
        putSaved(acct, null)
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Cookie header for an HTTP read as [acct] (feeds): its profile / the live store when it is active / its saved set. */
    suspend fun cookieHeader(acct: String, url: String): String = withContext(Dispatchers.Main) { cookieOf(acct, url) }

    fun cookieOf(acct: String, url: String): String {
        if (profiles() || (active(base(acct)) ?: base(acct)) == acct) return runCatching { cookieManager(acct).getCookie(url) }.getOrNull().orEmpty()
        val c = saved(acct).optJSONObject("cookies") ?: return ""
        val h = hostOf(url); val r = root(h)
        val out = LinkedHashMap<String, String>()
        for (u in c.keys()) { val uh = hostOf(u); if (uh == h || uh == r || h.endsWith(".$r") && (uh == r || uh == "www.$r")) c.optJSONObject(u)?.let { m -> m.keys().forEach { k -> out[k] = m.optString(k) } } }
        return out.entries.joinToString("; ") { it.key + "=" + it.value }
    }
}

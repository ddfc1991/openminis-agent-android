package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject

/**
 * [Mines-38.9.3] user 2026-10-17 「动不动就账号失效登录」 / 「现在老是这样优化一下」. Why sign-ins kept disappearing:
 *  - Chromium (the Android WebView) keeps SESSION cookies — Set-Cookie without Expires / Max-Age — in memory only. Many
 *    sites (阿里云, 豆包 …) put the sign-in in such a cookie, so every time Android killed Mines in the background or the
 *    app was updated, the session was gone and the next check saw a login page → 「登录失效」;
 *  - the two places that put saved cookies back ([AccountJar] swap, [PlusConnect.restoreCookies]) wrote them back as
 *    session cookies again (no Max-Age), and PlusConnect only did so when the site had NO cookie at all (one analytics
 *    cookie that survived blocked the restore).
 * Now: the cookies of every site the user has an account on are snapshotted (encrypted, in PlusVault secret extras) when
 * Mines goes to the background and after sign-ins; on the next start the ones the store lost are put back as PERSISTENT
 * cookies (30 days, the site's registrable domain). A cookie that disappears while Mines runs (a real sign-out) leaves
 * the snapshot too, so a sign-out is never undone; an explicit 「退出」 deletes the snapshot.
 */
object SessionKeeper {
    private const val VAULT = "sessionkeep"
    @Volatile private var restored = false
    @Volatile private var lastSnap = 0L
    @Volatile private var app: Context? = null

    /** From [LoginWeb.flush] (after pages / sign-ins): throttled snapshot with the context [restore] saw. */
    fun snapshotQuiet() { app?.let { snapshot(it) } }

    /** Sites (page urls) whose sign-in is worth keeping, keyed by account id (conn:<kind>, web:<ai account>, site:<root>). */
    private fun targets(c: Context): Map<String, Pair<List<String>, String?>> {
        val out = LinkedHashMap<String, Pair<List<String>, String?>>()
        runCatching {
            PlusVault.init(c)
            for (e in PlusVault.entries.value.values) {
                val k = PlusVault.kind(e.kindId) ?: continue
                if (k.method != ConnMethod.WEB && e.via != "web" && e.via != "chrome" && e.via != "oauth") continue
                val urls = listOfNotNull(k.loginUrl, k.panelUrl, k.site).filter { it.startsWith("http") }
                if (urls.isNotEmpty()) out["conn:" + e.kindId] = urls to null
            }
        }
        runCatching {
            com.openminis.app.plus.group.GroupPrefs.init(c)
            for (app in com.openminis.app.plus.group.AiCatalog.webApps) for (id in com.openminis.app.plus.group.WebAccounts.ids(app.id)) {
                val spec = com.openminis.app.plus.group.AiCatalog.spec(id) ?: continue
                if (com.openminis.app.plus.group.GroupPrefs.get("weblogin_$id") == "0") continue
                // SWAP: only the account that holds the shared store right now is in the live jar (others are saved by AccountJar)
                if (!AccountJar.profiles() && (AccountJar.active(com.openminis.app.plus.group.WebAccounts.site(id)) ?: com.openminis.app.plus.group.WebAccounts.site(id)) != id) continue
                out["web:$id"] = com.openminis.app.plus.group.WebChat.urls(spec, id) to id
            }
        }
        runCatching {   // [Mines-38.9.5 i] the registry's web-chat bridges (Hark / Manus / Meta Muse web) — their sign-in was never kept
            com.openminis.app.plus.group.GroupPrefs.init(c)
            for (p in com.openminis.app.plus.group.PresetRegistry.all) {
                val web = p.web ?: continue
                if (com.openminis.app.plus.group.GroupPrefs.get("weblogin_${p.id}") != "1") continue
                val urls = (listOf(com.openminis.app.plus.group.PresetRegistry.webUrl(p.id)) + web.extraUrls).filter { it.startsWith("http") }
                if (urls.isNotEmpty()) out.putIfAbsent("web:" + p.id, urls to p.id)
            }
        }
        runCatching {   // [Mines-38.9.4] Memoh web sign-in (hosted app.memoh.net keeps it only as a cookie)
            com.openminis.app.plus.group.GroupPrefs.init(c)
            if (com.openminis.app.plus.group.GroupPrefs.get("memoh_web") == "1") com.openminis.app.plus.group.PresetAuth.memohOrigin()?.let { o ->
                out["site:memoh"] = listOf("$o/", "$o/login") to null
            }
        }
        runCatching {
            IdentityStore.init(c)
            for (r in IdentityStore.recs.value) if (r.status == IdentityCore.LINKED && r.site.contains('.') && r.method != IdentityCore.Method.KEY)
                out.putIfAbsent("site:" + r.site, listOf("https://" + r.site + "/", "https://www." + r.site + "/") to null)
        }
        return out
    }

    private fun cm(acct: String?): CookieManager? = runCatching { if (acct != null) AccountJar.cookieManager(acct) else CookieManager.getInstance() }.getOrNull()
    private fun hostOf(u: String) = runCatching { java.net.URI(u).host.orEmpty() }.getOrDefault("")

    /** Snapshot now (throttled to once per [minGapMs]); never before this process restored, or a lost cookie would be saved as gone. */
    fun snapshot(c: Context, minGapMs: Long = 15_000L) {
        if (!restored) return
        val now = System.currentTimeMillis()
        if (now - lastSnap < minGapMs) return
        lastSnap = now
        runCatching {
            for ((key, t) in targets(c)) {
                val m = cm(t.second) ?: continue
                val o = JSONObject()
                for (u in t.first) {
                    val p = SessionStatus.cookiePairs(runCatching { m.getCookie(u) }.getOrNull()).filterKeys { SessionStatus.keepCookie(it) }
                    if (p.isNotEmpty()) o.put(u, JSONObject(p as Map<*, *>))
                }
                if (o.length() == 0) continue
                PlusVault.setSecretExtra(VAULT, key, JSONObject().put("at", now).put("c", o).toString())
            }
        }
    }

    /** [Mines-38.9.11 b] 清除网页数据: drop every kept snapshot whose pages are on [root], so a wiped sign-in is not put back. */
    fun forgetSite(c: Context, root: String): Int {
        if (root.isBlank()) return 0
        var n = 0
        runCatching {
            for ((key, t) in targets(c)) {
                if (key == "site:$root" || t.first.any { u -> val h = hostOf(u); h == root || h.endsWith(".$root") }) {
                    PlusVault.setSecretExtra(VAULT, key, null); n++
                }
            }
            PlusVault.setSecretExtra(VAULT, "site:$root", null)
        }
        return n
    }

    /** Once per process, before any check / page: put back what the store lost since the last run. Main thread. */
    fun restore(c: Context): Int {
        if (restored) return 0
        app = c.applicationContext
        var n = 0
        runCatching {
            val now = System.currentTimeMillis()
            for ((key, t) in targets(c)) {
                val raw = PlusVault.secretExtra(VAULT, key) ?: continue
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val at = o.optLong("at"); val cs = o.optJSONObject("c") ?: continue
                val m = cm(t.second) ?: continue
                for (u in cs.keys()) {
                    val snap = cs.optJSONObject(u) ?: continue
                    val saved = snap.keys().asSequence().associateWith { snap.optString(it) }
                    val live = SessionStatus.cookiePairs(runCatching { m.getCookie(u) }.getOrNull())
                    val root = Keychain.root(hostOf(u))
                    for ((name, v) in SessionStatus.missingCookies(saved, live, at, now)) {
                        runCatching { m.setCookie(u, SessionStatus.persistentCookie(name, v, root, u.startsWith("https"))) }; n++
                    }
                }
            }
            runCatching { CookieManager.getInstance().flush() }
        }
        restored = true
        if (n > 0) android.util.Log.i("SessionKeeper", "restored $n cookies lost since the last run")
        return n
    }

    /** A real sign-out / account removal: forget what was kept for it. */
    fun forget(key: String) { runCatching { PlusVault.setSecretExtra(VAULT, key, null) } }

    fun isRestored() = restored
}

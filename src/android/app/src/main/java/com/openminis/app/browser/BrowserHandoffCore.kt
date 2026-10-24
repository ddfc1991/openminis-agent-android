package com.openminis.app.browser

/**
 * [Mines-38.9.7] 浏览器接管保持登录 (user approved 2026-10-18). Root cause of 「我在 Mines 浏览器里登录了，AI 接手却是没登录」:
 *  1. the browser on the home screen (☰ › 打开浏览器) had its OWN private tab pool, separate from every chat's pool, so an
 *     agent in a chat never saw the user's tab: it opened a blank tab of its own;
 *  2. even in the same chat the agent took the first free tab (often tab 0), not the tab the user was looking at;
 *  3. a new tab starts with an empty sessionStorage (per tab) and the pool's default UA — sites that keep the sign-in in
 *     sessionStorage (many consoles / SPAs) or bind it to the UA (Cloudflare cf_clearance, 阿里云 risk checks, the per-site
 *     电脑版 chip) look signed-out there, although the cookies are shared;
 *  4. Google / Apple sign-in goes to a Chrome Custom Tab (Google blocks WebView sign-in), so THAT session lives in Chrome
 *     and can never be handed to a WebView — the agent is told so instead of guessing.
 * Fix: the home browser uses the process-wide shared pool; the agent prefers the user's selected tab; when the agent's
 * pool has no tab yet it opens the user's last page (≤ 20 min old) with the same UA and that origin's sessionStorage
 * (document-start script), after cookies were flushed. Pure parts here (unit-tested).
 */
object BrowserHandoffCore {
    const val MAX_AGE_MS = 20 * 60_000L

    data class TabLite(val id: Int, val inUse: Boolean, val mayUse: Boolean)
    data class UserPage(val url: String, val desktop: Boolean, val at: Long, val sessionJson: String?, val poolKey: Int, val incognito: Boolean = false) {
        val origin: String get() = originOf(url)
    }

    fun originOf(url: String): String = runCatching {
        val u = java.net.URI(url); if (u.scheme == null || u.host == null) "" else u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }.getOrDefault("")

    /** The tab an agent (no tab id given) drives: the user's selected tab when it is free, else the first free one. */
    fun pickAgentTab(selected: Int?, tabs: List<TabLite>): Int? =
        tabs.firstOrNull { it.id == selected && !it.inUse && it.mayUse }?.id ?: tabs.firstOrNull { !it.inUse && it.mayUse }?.id

    /** Adopt the user's last page into an agent pool that has no tab yet. Never an incognito page. */
    fun adopt(page: UserPage?, now: Long, poolHasTabs: Boolean, poolKey: Int): Boolean =
        page != null && !page.incognito && !poolHasTabs && page.poolKey != poolKey && now - page.at in 0..MAX_AGE_MS &&
            (page.url.startsWith("https://") || page.url.startsWith("http://"))

    const val DUMP_SESSION_JS = "(function(){try{var o={};for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);if(k.indexOf('__m')!==0)o[k]=sessionStorage.getItem(k);}return JSON.stringify(o);}catch(e){return '';}})()"

    /** Document-start script putting [json] (a sessionStorage dump) back once, only on [origin]. */
    fun restoreSessionJs(origin: String, json: String): String {
        val o = org.json.JSONObject.quote(origin)
        return "(function(){try{if(location.origin!==$o||sessionStorage.getItem('__mh_ho'))return;var d=$json;" +
            "for(var k in d){if(sessionStorage.getItem(k)===null)sessionStorage.setItem(k,d[k]);}sessionStorage.setItem('__mh_ho','1');}catch(e){}})();"
    }

    /** True when a dump is a JSON object worth restoring. */
    fun usableDump(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.length < 3 || !s.startsWith("{")) return null
        return runCatching { val o = org.json.JSONObject(s); if (o.length() == 0) null else o.toString() }.getOrNull()
    }

    /** One line for the agent when the user signed in with Google / Apple (Custom Tab, Chrome's jar). */
    const val CUSTOM_TAB_NOTE = "This site's Google / Apple sign-in ran in Chrome (Custom Tab), not in the app's WebView, so that session cannot be used here; ask the user to sign in on the site's own form (email / phone / QR) inside the Mines browser."
}

package com.openminis.app.browser

/**
 * [Mines-38.9.7] 无痕标签 (user 2026-10-18); [Mines-38.9.11.2] made truly clean (user 2026-10-21 「我们app的无痕浏览器，要搞得干净」).
 * Pure rules, unit-tested (IncognitoCoreTest):
 *  - isolation, two paths:
 *     PROFILE  — the phone's WebView supports androidx.webkit MULTI_PROFILE: every 无痕 session gets a FRESH profile
 *                ("mines-incog-<time>-<rand>"), so it shares no cookies / localStorage / IndexedDB / cache / service workers with
 *                the normal browser or with an earlier 无痕 session; the profile is wiped and deleted when the session closes;
 *     PROCESS  — no profile support (older WebView, the CI emulator): the session runs in its own process (":incog") whose
 *                WebView data directory has its own suffix ("incog"), wiped before the first WebView starts and again on close.
 *    Never the old trick of snapshot / clear / restore of the shared cookie jar.
 *  - leave no trace: no cache (LOAD_NO_CACHE), no form / password autosave (WebView or Android Autofill), no history, no
 *    third-party cookies, geolocation / camera / mic denied; on close history, cache, form data, storage, cookies go and the
 *    WebView is stopped, blanked and destroyed;
 *  - kill-safe: a marker file says "a session was open"; the next launch wipes leftover profiles / the ":incog" data dir;
 *  - a 无痕 session never feeds 账号关联 / LoginMemory / SessionKeeper and never auto-saves a login; only the user's tap on
 *    「保存到密码库」 saves one.
 */
object IncognitoCore {
    /** legacy (38.9.7–38.9.11.1) fixed profile name: always treated as leftover */
    const val PROFILE = "mines-incognito"
    const val PROFILE_PREFIX = "mines-incog-"
    const val DATA_SUFFIX = "incog"
    const val PROCESS_SUFFIX = ":incog"
    const val TITLE_PREFIX = "无痕 · "
    const val INDICATOR = "无痕"
    const val CLEAR_ACTION = "清除并关闭"
    const val SAVE_ACTION = "保存到密码库"
    const val NO_PROFILE_MSG = "这台手机的系统 WebView 不支持独立配置，无痕标签改在单独的进程里打开。"
    const val CLOSED_MSG = "无痕标签已关闭，Cookie、网页存储、缓存和浏览记录已清除"
    const val SAVED_MSG = "已保存到密码库"
    const val NOTHING_TO_SAVE_MSG = "这个页面上没有找到填好的账号和密码"

    enum class Path { PROFILE, PROCESS, NONE }

    /**
     * which isolation the phone gets (never "share the normal jar"): profiles when the WebView has them, else the separate
     * process — which needs WebView.setDataDirectorySuffix (Android 9 / API 28+); below that nothing isolates → refuse.
     */
    fun path(profileSupported: Boolean, sdk: Int): Path = when {
        profileSupported -> Path.PROFILE
        sdk >= 28 -> Path.PROCESS
        else -> Path.NONE
    }
    const val NO_ISOLATION_MSG = "这台手机的系统太旧（Android 9 以下）且系统 WebView 不支持独立配置，无法保证无痕隔离，所以不打开。请在应用商店更新「Android System WebView」后再试。"

    // kept for callers / tests of 38.9.7: opening is never refused any more, it falls back to the process path
    sealed class Plan { object Open : Plan(); data class Refuse(val why: String) : Plan() }
    fun plan(@Suppress("UNUSED_PARAMETER") profileSupported: Boolean): Plan = Plan.Open

    /** a fresh, never-reused profile name for one session */
    fun newProfileName(nowMs: Long, rnd: Long): String =
        PROFILE_PREFIX + java.lang.Long.toString(nowMs, 36) + "-" + java.lang.Long.toString(rnd and 0xFFFFFFFFL, 36)

    fun isIncognitoProfile(name: String?): Boolean = name != null && (name == PROFILE || name.startsWith(PROFILE_PREFIX))

    /** profiles to delete: every 无痕 profile except the ones still open */
    fun leftovers(all: Collection<String>, open: Collection<String>): List<String> =
        all.filter { isIncognitoProfile(it) && it !in open }.distinct()

    fun isIncognitoProcess(processName: String?, pkg: String): Boolean = processName == pkg + PROCESS_SUFFIX

    /**
     * Files of the ":incog" process's WebView (relative to the app's data dir). WebView appends "_<suffix>" to its data and
     * cache directory names; both are deleted.
     */
    fun processDataDirs(): List<String> = listOf("app_webview_$DATA_SUFFIX", "cache/WebView_$DATA_SUFFIX", "app_textures_$DATA_SUFFIX",
        "cache/org.chromium.android_webview_$DATA_SUFFIX")

    /** what a 无痕 WebView is allowed to do */
    data class Policy(
        val noCache: Boolean = true,
        val saveFormData: Boolean = false,
        val autofill: Boolean = false,
        val thirdPartyCookies: Boolean = false,
        val geolocation: Boolean = false,
        val mediaPermissions: Boolean = false,
        val history: Boolean = false,
    )
    val POLICY = Policy()

    /** What an incognito tab may feed: nothing that outlives it. */
    fun recordsHistory(incognito: Boolean) = !incognito
    fun feedsAccounts(incognito: Boolean) = !incognito      // AutoAccounts / LoginMemory / SessionKeeper / AutoSources
    fun handsOver(incognito: Boolean) = !incognito          // BrowserHandoff (agent takeover)
    fun persistsTabs(incognito: Boolean) = !incognito       // browser_tabs/<sid>.json
    /** a login seen in a 无痕 page is saved only when the user tapped 保存到密码库 */
    fun savesLogin(incognito: Boolean, userTapped: Boolean) = !incognito || userTapped

    fun title(base: String, incognito: Boolean) = if (incognito) TITLE_PREFIX + base else base

    /**
     * The 「保存到密码库」 page script result: `{"u": "...", "p": "...", "url": "..."}` (JSON from evaluateJavascript, which
     * arrives as a quoted JSON string). Returns (url, user, pass) or null when no password was typed.
     */
    fun parseSaveResult(raw: String?): Triple<String, String, String>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = runCatching { if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue() as String else raw }.getOrNull() ?: return null
        val o = runCatching { org.json.JSONObject(inner) }.getOrNull() ?: return null
        val p = o.optString("p"); if (p.isEmpty()) return null
        return Triple(o.optString("url"), o.optString("u"), p)
    }

    /** reads the focused form's username + password (never logged; handed straight to the vault) */
    const val SAVE_JS = """(function(){try{var ps=[].slice.call(document.querySelectorAll('input[type=password]')).filter(function(e){return e.value});
if(!ps.length)return null;var p=ps[0];var f=p.form||document;var us=[].slice.call(f.querySelectorAll('input')).filter(function(e){
var t=(e.type||'text').toLowerCase();return e.value&&(t=='text'||t=='email'||t=='tel')});
return JSON.stringify({u:us.length?us[us.length-1].value:'',p:p.value,url:location.origin+'/'});}catch(e){return null}})()"""

    /** 自检 line. */
    fun selfCheck(supported: Boolean, lastClose: String?): Pair<Boolean, String> =
        true to ((if (supported) "独立配置（每次新建，关闭即删除）" else "单独进程（系统 WebView 不支持独立配置）") +
            (lastClose?.let { " · 上次关闭：$it" } ?: ""))
}

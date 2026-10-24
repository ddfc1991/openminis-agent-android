package com.openminis.app.browser

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

/**
 * [Mines-38.9.7 → 38.9.11.2] Android side of [IncognitoCore]. Main thread unless noted.
 *  PROFILE path: a fresh WebView profile per 无痕 session (in-app browser sheet), wiped + deleted on close.
 *  PROCESS path: [IncognitoActivity] in the ":incog" process with its own WebView data directory (see [IncognitoProcess]).
 */
object Incognito {
    private const val TAG = "Incognito"
    private const val MARKER = "incognito.open"
    @Volatile var lastClose: String? = null; private set
    @Volatile var lastPath: IncognitoCore.Path? = null; private set
    /** smoke only: use the process path even where profiles work (so both paths can be exercised) */
    @Volatile var forceProcess: Boolean = false
    /** profiles of 无痕 sessions still open (main thread) */
    private val openProfiles = LinkedHashSet<String>()
    fun openProfiles(): List<String> = openProfiles.toList()

    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** [38.9.11.3] 无痕 uses 严格代理 by default (never the real IP) */
    @Volatile var strict: Boolean = com.openminis.app.plus.StrictWebCore.STRICT_INCOGNITO_DEFAULT
    // [38.9.11.3] strict and no passing proxy → the :incog process (its own proxy override blocks) instead of a main-process profile
    fun path(): IncognitoCore.Path = IncognitoCore.path(supported() && !forceProcess &&
        !com.openminis.app.plus.StrictWebCore.incognitoInProcess(true, strict, runCatching { com.openminis.app.plus.WebProxy.passingPort() }.getOrNull()), android.os.Build.VERSION.SDK_INT)

    private fun marker(c: Context) = File(c.applicationContext.filesDir, MARKER)
    fun markOpen(c: Context, open: Boolean) { runCatching { val f = marker(c); if (open) f.writeText(System.currentTimeMillis().toString()) else f.delete() } }
    fun markerPresent(c: Context) = marker(c).exists()

    private fun stamp(r: String) {
        lastClose = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date()) + " " + r
    }

    /**
     * Opens a 无痕 session on [url] (blank when empty). PROFILE → [onPool] gets a fresh pool to show in the browser sheet;
     * PROCESS → [IncognitoActivity] starts in its own process; NONE → a one-line refusal toast.
     */
    fun open(c: Context, url: String?, onPool: (BrowserTabPool) -> Unit) {
        val p = path(); lastPath = p
        when (p) {
            IncognitoCore.Path.PROFILE -> {
                val name = newSession(c)
                if (name == null) { openProcess(c, url); return }
                val pool = BrowserTabPool(c, incognito = true, incognitoProfile = name)
                val ok = runCatching { if (!url.isNullOrBlank()) pool.selectOrCreateTabForURL(url) ?: pool.ensureTabForUI() else pool.ensureTabForUI() }.isSuccess
                if (ok) onPool(pool) else { wipeProfile(name); openProcess(c, url) }
            }
            IncognitoCore.Path.PROCESS -> openProcess(c, url)
            IncognitoCore.Path.NONE -> android.widget.Toast.makeText(c, IncognitoCore.NO_ISOLATION_MSG, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openProcess(c: Context, url: String?) {
        lastPath = IncognitoCore.Path.PROCESS
        markOpen(c, true)
        val i = Intent(c, IncognitoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!url.isNullOrBlank()) i.putExtra(IncognitoActivity.EXTRA_URL, url)
        // [38.9.11.3] the :incog process has no LocalProxy: hand it the proxy rule (passing port, or a closed port when strict)
        com.openminis.app.plus.StrictWebCore.rule(strict, runCatching { com.openminis.app.plus.WebProxy.passingPort() }.getOrNull())?.let { i.putExtra(IncognitoActivity.EXTRA_PROXY, it) }
        c.startActivity(i)
    }

    fun noteProcessClosed() { stamp("已清除（单独进程）") }

    /** a new profile for one session (after sweeping leftovers); null when the profile store fails */
    fun newSession(c: Context): String? = runCatching {
        sweepProfiles()
        val name = IncognitoCore.newProfileName(System.currentTimeMillis(), java.security.SecureRandom().nextLong())
        ProfileStore.getInstance().getOrCreateProfile(name)
        openProfiles += name
        markOpen(c, true)
        name
    }.getOrElse { android.util.Log.w(TAG, "profile create failed: ${it.javaClass.simpleName}"); null }

    /** Must run right after `WebView(context)`, before anything else touches [wv]. */
    fun attach(wv: WebView, profile: String?): Boolean {
        if (profile == null || !supported()) return false
        return runCatching { WebViewCompat.setProfile(wv, profile); true }
            .getOrElse { android.util.Log.w(TAG, "setProfile failed: ${it.javaClass.simpleName}"); false }
    }

    /** the 无痕 policy on one WebView (after the browser's own setup, which turns third-party cookies on) */
    @Suppress("DEPRECATION")
    fun harden(wv: WebView, profile: String?) {
        val pol = IncognitoCore.POLICY
        com.openminis.app.plus.WebProxy.guardWebRtc(wv)   // [38.9.11.3] no WebRTC IP leak in 无痕
        runCatching {
            wv.settings.cacheMode = if (pol.noCache) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            wv.settings.saveFormData = pol.saveFormData
            wv.settings.setGeolocationEnabled(pol.geolocation)
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
        }
        if (!pol.autofill) runCatching { wv.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        runCatching {
            val cm = profile?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager } ?: CookieManager.getInstance()
            cm.setAcceptThirdPartyCookies(wv, pol.thirdPartyCookies)
        }
        debugging()
    }

    /** remote debugging of page contents only in debug builds */
    fun debugging() { runCatching { WebView.setWebContentsDebuggingEnabled(com.openminis.app.BuildConfig.DEBUG) } }

    /** Before the tabs' WebViews are destroyed. */
    fun wipeViews(views: List<WebView>) {
        for (v in views) runCatching { v.stopLoading(); v.clearHistory(); v.clearCache(true); v.clearFormData(); v.clearSslPreferences(); v.clearMatches() }
    }

    /** After the tabs are destroyed: cookies + storage gone, profile deleted (retried once while a WebView still lets go). */
    fun wipeProfile(name: String?, attempt: Int = 0) {
        if (name == null) return
        val r = runCatching {
            val store = ProfileStore.getInstance()
            store.getProfile(name)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.cookieManager.flush()
                p.webStorage.deleteAllData()
            }
            store.deleteProfile(name)
            "已清除"
        }.getOrElse { it }
        if (r is Throwable) {
            if (attempt < 3) { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ wipeProfile(name, attempt + 1) }, 700L); return }
            stamp("清除未完成（下次启动再清）"); android.util.Log.w(TAG, "deleteProfile failed: ${r.javaClass.simpleName}")
            return
        }
        openProfiles -= name
        stamp(r as String)
    }

    /** Close an incognito pool: wipe its views, destroy the tabs, wipe + delete its profile. */
    fun close(pool: BrowserTabPool) {
        if (!pool.incognito) return
        val name = pool.incognitoProfile
        wipeViews(pool.tabs.value.map { it.manager.webView })
        pool.destroyAllTabs()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            wipeProfile(name)
            val c = pool.contextForIncognito()
            if (openProfiles.isEmpty() && !IncognitoProcess.running(c)) markOpen(c, false)
        }, 300)
    }

    /** every 无痕 profile that is not open now (leftovers of a killed app / the 38.9.7 fixed profile) */
    fun sweepProfiles(): Int {
        if (!supported()) return 0
        return runCatching {
            val store = ProfileStore.getInstance()
            val gone = IncognitoCore.leftovers(store.allProfileNames, openProfiles)
            for (n in gone) runCatching {
                store.getProfile(n)?.let { p -> p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData() }
                store.deleteProfile(n)
            }
            gone.size
        }.getOrDefault(0)
    }

    /**
     * Kill-safe: called once after the main process starts (main thread, idle). When the marker says a 无痕 session was
     * open, its leftovers go: profiles and (if the ":incog" process is not running) that process's WebView data dirs.
     */
    fun sweepOnLaunch(c: Context) {
        if (!markerPresent(c)) return
        val n = sweepProfiles()
        val d = if (!IncognitoProcess.running(c)) IncognitoProcess.wipeDirs(c) else 0
        if (!IncognitoProcess.running(c)) markOpen(c, false)
        stamp("启动时清除了上次留下的无痕数据（$n 个配置，$d 个目录）")
    }
}

package com.openminis.app.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** [Mines-38.9.7] Android side of [BrowserHandoffCore]: remembers the user's page, prepares an agent tab with it. */
object BrowserHandoff {
    @Volatile var last: BrowserHandoffCore.UserPage? = null; private set
    @Volatile var adopted: Int = 0; private set

    /** The user is looking at [wv] in [pool] (sheet shown / page changed / sheet closed). Main thread. */
    fun noteUser(pool: Any, wv: WebView, url: String, desktop: Boolean, incognito: Boolean = false) {
        if (incognito) return   // 无痕: never handed over, never remembered
        if (!url.startsWith("http")) return
        runCatching { com.openminis.app.plus.LoginWeb.flush() }   // CookieManager.flush (+ profiles) before any handoff
        val key = System.identityHashCode(pool)
        last = BrowserHandoffCore.UserPage(url, desktop, System.currentTimeMillis(), last?.takeIf { it.url == url }?.sessionJson, key)
        runCatching {
            wv.evaluateJavascript(BrowserHandoffCore.DUMP_SESSION_JS) { r ->
                val dump = BrowserHandoffCore.usableDump(runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull())
                val cur = last
                if (cur != null && cur.url == url) last = cur.copy(sessionJson = dump)
            }
        }
    }

    /** The page to open in [pool]'s first agent tab, or null. */
    fun adoptFor(pool: Any, poolHasTabs: Boolean): BrowserHandoffCore.UserPage? {
        val p = last
        return if (BrowserHandoffCore.adopt(p, System.currentTimeMillis(), poolHasTabs, System.identityHashCode(pool))) p else null
    }

    /** Before the first load: the page's sessionStorage comes back on its origin. */
    fun prepare(wv: WebView, page: BrowserHandoffCore.UserPage) {
        adopted++
        val js = page.sessionJson ?: return
        if (page.origin.isBlank()) return
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(wv, BrowserHandoffCore.restoreSessionJs(page.origin, js), setOf(page.origin))
        }
    }
}

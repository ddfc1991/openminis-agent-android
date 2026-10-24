package com.openminis.app.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * [Mines-zoom-34] The in-app browser must always be zoomable (user is partially
 * sighted; vulbox.com login rendered tiny and could not be pinch-zoomed in 手机 or
 * 电脑 mode because builtInZoomControls was false).
 *
 * - pinch + double-tap zoom on (no on-screen +/- buttons), wide viewport + overview
 * - pages that lock zoom (meta viewport user-scalable=no / maximum-scale=1) are
 *   rewritten to maximum-scale=5, user-scalable=yes, at document start (MutationObserver)
 *   and again on page finish
 * - 文字大小 (textZoom 100–200 %) remembered per site; default follows the system font size
 */
object BrowserZoom {
    private const val PREFS = "mines_browser_zoom"
    const val MIN = 100
    const val MAX = 200

    /** Rewrites zoom-locking meta viewport tags; safe to run any number of times. */
    const val UNLOCK_JS = """(function(){try{
var fix=function(m){if(!m||!m.getAttribute)return;var n=(m.getAttribute('name')||'').toLowerCase();if(n!=='viewport')return;
var c=m.getAttribute('content')||'';var o=c;
c=c.replace(/user-scalable\s*=\s*(no|0)/ig,'user-scalable=yes').replace(/maximum-scale\s*=\s*[0-9.]+/ig,'maximum-scale=5').replace(/minimum-scale\s*=\s*[0-9.]+/ig,'minimum-scale=0.25');
if(!/maximum-scale/i.test(c))c+=(c?',':'')+'maximum-scale=5';if(!/user-scalable/i.test(c))c+=',user-scalable=yes';
if(c!==o)m.setAttribute('content',c);};
var all=function(){var l=document.querySelectorAll('meta[name=viewport],meta[name=Viewport]');for(var i=0;i<l.length;i++)fix(l[i]);};
all();
if(!window.__minesZoom){window.__minesZoom=1;try{new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var a=ms[i].addedNodes;for(var j=0;j<a.length;j++){var x=a[j];if(x.tagName==='META')fix(x);}if(ms[i].type==='attributes')fix(ms[i].target);}}).observe(document.documentElement||document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});}catch(e){}}
try{document.documentElement.style.touchAction='auto';}catch(e){}
}catch(e){}})();"""

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lowest default text size (a system font set smaller than normal). */
    const val DEFAULT_MIN = 80

    /**
     * Default text size: [Mines-webfit-38.3] follows the system font size × Mines' own 字号 setting
     * (设置 → 外观), like the rest of the app, 80–200 %. A size the user set for a site wins.
     */
    fun defaultTextZoom(c: Context): Int {
        val app = runCatching {
            val lvl = com.openminis.app.ui.settings.getAppearancePrefs(c).getInt(com.openminis.app.ui.settings.KEY_FONT_APP_BASE, 0)
            com.openminis.app.ui.settings.fontScaleForLevel(lvl)
        }.getOrDefault(1f)
        return (c.resources.configuration.fontScale * app * 100).toInt().coerceIn(DEFAULT_MIN, MAX)
    }

    fun hostOf(url: String?): String = runCatching { Uri.parse(url ?: "").host.orEmpty().removePrefix("www.") }.getOrDefault("")

    fun textZoomFor(c: Context, url: String?): Int {
        val h = hostOf(url)
        return if (h.isEmpty()) defaultTextZoom(c) else prefs(c).getInt("t:$h", -1).let { if (it <= 0) defaultTextZoom(c) else it.coerceIn(MIN, MAX) }
    }

    fun setTextZoom(c: Context, url: String?, pct: Int) {
        val h = hostOf(url); val v = pct.coerceIn(MIN, MAX)
        if (h.isNotEmpty()) prefs(c).edit().putInt("t:$h", v).apply()
    }

    /** Zoom-related settings; called from configureWebView for every tab, both UA modes. */
    fun configure(webView: WebView) {
        webView.settings.apply {
            setSupportZoom(true)
            builtInZoomControls = true      // enables pinch zoom
            displayZoomControls = false     // no floating +/- buttons
            useWideViewPort = true
            loadWithOverviewMode = true     // also enables double-tap zoom
            textZoom = defaultTextZoom(webView.context)
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, UNLOCK_JS, setOf("*")) }
        }
    }

    /** On page start/finish: per-site text size + unlock zoom (fallback when document-start scripts aren't supported). */
    fun onPage(webView: WebView, url: String?, finished: Boolean) {
        runCatching {
            val t = textZoomFor(webView.context, url)
            if (webView.settings.textZoom != t) webView.settings.textZoom = t
            if (finished) webView.evaluateJavascript(UNLOCK_JS, null)
        }
    }

    /** For the selftest / smoke: zoom state of a WebView. */
    fun describe(webView: WebView): String {
        val s = webView.settings
        return "supportZoom=${s.supportZoom()} builtIn=${s.builtInZoomControls} display=${s.displayZoomControls} " +
            "wide=${s.useWideViewPort} overview=${s.loadWithOverviewMode} textZoom=${s.textZoom} " +
            "docStart=${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}"
    }
}

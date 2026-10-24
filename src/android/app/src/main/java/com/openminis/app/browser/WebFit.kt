package com.openminis.app.browser

import android.content.Context
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * [Mines-webfit-38.3] Every in-app WebView fits the phone screen (user 2026-10-14:
 * 「所有内置浏览器/WebView 必须自适应屏幕」, realme Neo7 Turbo 1280×2800 / 480 dpi ≈ 427 dp wide).
 *
 * - wide viewport + overview: pages with `<meta viewport width=device-width>` lay out at the
 *   screen width; pages without one lay out at 980 CSS px and are zoomed out to the screen width
 *   (never cut off on the right)
 * - pinch / double-tap zoom on, no floating +/- buttons, zoom-locking viewports unlocked ([BrowserZoom])
 * - text size follows the system font size × Mines' own 字号 setting ([BrowserZoom.defaultTextZoom])
 * - mobile user agent (contains "Mobile"); the in-app browser's 电脑 mode keeps its desktop UA and
 *   is shrunk to the screen width by BrowserUseManager.applyShrinkToFit
 *
 * Callers host the WebView with fillMaxSize / MATCH_PARENT and pad for the keyboard (imePadding).
 */
object WebFit {
    /** This phone's mobile Chrome UA (always contains "Mobile"). */
    fun mobileUa(c: Context): String = com.openminis.app.plus.LoginWeb.userAgent(c)

    /** Applies the fit-to-screen settings. [keepUa] leaves a UA the caller chose on purpose (电脑 mode). */
    fun apply(wv: WebView, keepUa: Boolean = false) {
        BrowserZoom.configure(wv)
        if (!keepUa) {
            val ua = wv.settings.userAgentString.orEmpty()
            if (!ua.contains("Mobile")) wv.settings.userAgentString = mobileUa(wv.context)
        }
    }

    /** The WebView the user is looking at in the in-app browser (for the smoke self-test). */
    @Volatile var shown: WeakReference<WebView>? = null

    /** Layout probe: CSS viewport vs screen, horizontal overflow, the page's meta viewport and UA. */
    const val PROBE_JS = """(function(){try{var d=document.documentElement,b=document.body;
var m=document.querySelector('meta[name=viewport]');
return JSON.stringify({url:location.href,title:document.title,innerWidth:window.innerWidth,screenWidth:screen.width,
dpr:window.devicePixelRatio,scrollWidth:Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0),clientWidth:d?d.clientWidth:0,
vvScale:window.visualViewport?visualViewport.scale:0,vvWidth:window.visualViewport?visualViewport.width:0,
metaViewport:m?m.getAttribute('content'):'',ua:navigator.userAgent});}catch(e){return JSON.stringify({error:String(e)});}})()"""
}

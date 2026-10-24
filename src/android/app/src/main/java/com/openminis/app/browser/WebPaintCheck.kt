package com.openminis.app.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [Mines-webpaint-38.4] The in-app browser must really draw the page it loaded.
 *
 * Smoke 44 (1280×2800 / 480 dpi): www.coze.cn was fully in the accessibility tree (DOM laid out, text
 * visible to the page) but the screenshot of the browser sheet showed only the page's background colour.
 * The sheet's WebView was drawn through a hardware layer (LAYER_TYPE_HARDWARE, an old workaround for
 * blank WebViews in dialogs on some GPUs); with a long, layer-heavy page Chromium hit its tile memory
 * limit ("tile memory limits exceeded, some content may not draw") and the content tiles never made it
 * into the layer.
 *
 * What the sheet does now: the on-screen WebView draws directly (LAYER_TYPE_NONE: real on-screen
 * position for tile priority, no extra layer copy) and [heal] watches it. When the page finished loading
 * but the pixels on screen are one flat colour while the page itself reports visible text in the
 * viewport, the layer type is switched (none → hardware → software) until the page shows. A page whose
 * visibility went "hidden" while on screen is resumed. Each step is logged and kept in [lastHeal]
 * for the self-test.
 */
object WebPaintCheck {
    private const val TAG = "WebPaintCheck"

    /** What the last check / heal did (self-test probe). */
    @Volatile var lastHeal: String = ""

    /**
     * Visible text in the viewport as the page sees it: text nodes inside the viewport whose element chain is
     * displayed, not visibility:hidden and has an effective opacity > 0.1. `hidden` counts text that is in the
     * viewport but transparent (e.g. a fade-in animation that never ran).
     */
    const val TEXT_JS = """(function(){try{var W=innerWidth,H=innerHeight,n=0,hid=0,s=[],c=0,t;
if(!document.body)return JSON.stringify({vis:document.visibilityState,rs:document.readyState,shown:0,hidden:0});
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
while((t=w.nextNode())&&c<5000){c++;var v=t.nodeValue;if(!v||v.trim().length<2)continue;var e=t.parentElement;if(!e)continue;
var r=e.getBoundingClientRect();if(r.bottom<=0||r.top>=H||r.right<=0||r.left>=W||r.width<2||r.height<2)continue;
var o=1,ok=true,x=e;while(x&&x.nodeType==1){var cs=getComputedStyle(x);if(cs.display=='none'||cs.visibility=='hidden'){ok=false;break}o*=parseFloat(cs.opacity||'1');x=x.parentElement}
if(ok&&o>0.1)n++;else{hid++;if(s.length<6)s.push(e.tagName+':'+o.toFixed(2)+':'+v.trim().slice(0,16))}}
return JSON.stringify({vis:document.visibilityState,rs:document.readyState,shown:n,hidden:hid,sample:s,raf:window.__minesRaf||-1,
bg:getComputedStyle(document.body).backgroundColor,scrollY:window.scrollY});}catch(e){return JSON.stringify({err:String(e)})}})()"""

    /** The window [v] is drawn in (a Compose dialog / bottom sheet window, else the activity's). */
    fun windowOf(v: View): Window? {
        var cur: Any? = v
        while (cur is View) {
            if (cur is androidx.compose.ui.window.DialogWindowProvider) return cur.window
            cur = cur.parent
        }
        var c = v.context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c.window
            c = c.baseContext
        }
        return null
    }

    /**
     * Share (in 1/10000) of pixels of [b] that differ from its most common colour (5 bits per channel), i.e. how
     * much is drawn on top of the background. 0 = one flat colour.
     */
    fun inkPermyriad(b: Bitmap): Int {
        val n = b.width * b.height
        if (n <= 0) return 0
        val px = IntArray(n); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val counts = HashMap<Int, Int>()
        for (p in px) { val k = p and 0x00F8F8F8; counts[k] = (counts[k] ?: 0) + 1 }
        val top = counts.values.maxOrNull() ?: n
        return ((n - top).toLong() * 10000 / n).toInt()
    }

    /**
     * [inkPermyriad] of what is really on screen inside [v] (PixelCopy of its window), or -1 when it cannot be
     * read (secure window, not attached, copy failed).
     */
    suspend fun screenInk(v: View): Int {
        val win = windowOf(v) ?: return -1
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return -1
        if ((win.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) return -1
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        val bw = (v.width / 4).coerceAtLeast(1); val bh = (v.height / 4).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            try {
                PixelCopy.request(win, r, bmp, { res -> if (cont.isActive) cont.resume(res == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) { if (cont.isActive) cont.resume(false) }
        }
        return if (ok) inkPermyriad(bmp).also { bmp.recycle() } else { bmp.recycle(); -1 }
    }

    /** [inkPermyriad] of a software draw of [wv] (what the page looks like without the GPU path). */
    fun softwareInk(wv: WebView): Int = runCatching {
        val bw = (wv.width / 4).coerceAtLeast(1); val bh = (wv.height / 4).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(0.25f, 0.25f); c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
        wv.draw(c)
        inkPermyriad(bmp).also { bmp.recycle() }
    }.getOrDefault(-1)

    fun layerName(t: Int) = when (t) { View.LAYER_TYPE_HARDWARE -> "hardware"; View.LAYER_TYPE_SOFTWARE -> "software"; else -> "none" }

    private suspend fun pageText(wv: WebView): org.json.JSONObject? = suspendCancellableCoroutine { cont ->
        try {
            wv.evaluateJavascript(TEXT_JS) { r ->
                val o = runCatching { org.json.JSONObject(org.json.JSONTokener(r ?: "\"\"").nextValue() as String) }.getOrNull()
                if (cont.isActive) cont.resume(o)
            }
        } catch (t: Throwable) { if (cont.isActive) cont.resume(null) }
    }

    /**
     * Watches the on-screen browser WebView while it is mounted (call from a LaunchedEffect keyed on it).
     * Cheap: one small PixelCopy every 1.5 s, only for the first ~30 s after each page finished loading.
     */
    suspend fun heal(wv: WebView) {
        var url: String? = null
        var checks = 0
        var healthy = false
        while (true) {
            delay(1500)
            if (!wv.isAttachedToWindow || wv.width <= 0) continue
            val u = wv.url
            if (u != url) { url = u; checks = 0; healthy = false }
            if (healthy || wv.progress < 100 || u.isNullOrBlank() || !u.startsWith("http")) continue
            if (++checks > 20) continue
            val ink = screenInk(wv)
            if (ink < 0) continue
            if (ink >= 10) {   // ≥ 0.1 % of the view drawn over the background
                healthy = true
                lastHeal = "ok ink=$ink layer=${layerName(wv.layerType)} url=$u"
                continue
            }
            // flat on screen: is the page itself showing text right now?
            val p = pageText(wv) ?: continue
            if (p.optString("vis") == "hidden") {
                Log.w(TAG, "page hidden while on screen → onResume (${BrowserLogMask.mask(u)})")
                wv.onResume()
            }
            val shown = p.optInt("shown")
            val from = wv.layerType
            val to = when (from) { View.LAYER_TYPE_NONE -> View.LAYER_TYPE_HARDWARE; View.LAYER_TYPE_HARDWARE -> View.LAYER_TYPE_SOFTWARE; else -> -1 }
            if (shown >= 3 && checks >= 2 && to >= 0) {
                Log.w(TAG, "blank paint: ink=$ink text=$shown layer ${layerName(from)} → ${layerName(to)} (${BrowserLogMask.mask(u)})")
                wv.setLayerType(to, null)
                wv.invalidate()
                lastHeal = "switched ${layerName(from)}→${layerName(to)} ink=$ink text=$shown url=$u"
            } else {
                lastHeal = "flat ink=$ink text=$shown hiddenText=${p.optInt("hidden")} layer=${layerName(from)} url=$u"
            }
        }
    }
}

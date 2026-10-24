package com.openminis.app.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/**
 * [Mines-render-34] "我的浏览器渲染不了" (user report 2026-10-13, vulbox.com task).
 *
 * Three ways the agent's browser looked "unrendered" to the model:
 *  1. Text-only main models (DeepSeek / GLM on 商汤 / AMD) never receive a screenshot's
 *     pixels — the provider swaps them for a "does not support vision input" placeholder,
 *     so a screenshot result carried nothing the model could read. Screenshot results now
 *     always carry the viewport's visible text + the inputs/buttons on screen.
 *  2. The headless GPU capture can hand back the Presentation's white background before the
 *     WebView's first real frame (slow GPUs): a blank image with no fallback. A blank capture
 *     now falls back to the software draw, and a still-blank shot says so in the result.
 *  3. SPA pages (vulbox.com is a uni-app H5 page: an empty <div id=app> until JS runs) fire
 *     onPageFinished before anything is in the DOM, so navigate → get_text read an empty
 *     page. Navigate now waits (bounded) for visible content.
 */
object BrowserRenderCheck {

    /** Probe: is there anything to see yet? */
    const val CONTENT_PROBE_JS = """(function(){try{var b=document.body;var t=b?(b.innerText||'').replace(/\s+/g,' ').trim():'';
return JSON.stringify({t:t.length,img:document.images?document.images.length:0,
m:b?b.querySelectorAll('canvas,svg,video,iframe,input,button').length:0,
vis:document.visibilityState||'',rs:document.readyState||'',w:window.innerWidth||0,h:window.innerHeight||0});}
catch(e){return JSON.stringify({t:0,err:String(e)});}})()"""

    /** Visible text inside the current viewport + the controls on screen, for text-only models. */
    const val VIEWPORT_TEXT_JS = """(function(){try{var W=window.innerWidth,H=window.innerHeight,out=[],n=0,b=document.body;
if(!b)return JSON.stringify({t:'',all:0,el:[]});
var tw=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null),x;
while((x=tw.nextNode())&&n<1600){var s=(x.nodeValue||'').replace(/\s+/g,' ').trim();if(!s)continue;var p=x.parentElement;if(!p)continue;
var r=p.getBoundingClientRect();if(r.width<1||r.height<1||r.bottom<0||r.top>H||r.right<0||r.left>W)continue;
var cs=window.getComputedStyle(p);if(cs.visibility==='hidden'||cs.display==='none'||cs.opacity==='0')continue;
if(out.length&&out[out.length-1]===s)continue;out.push(s);n+=s.length+3;}
var el=[].slice.call(b.querySelectorAll('input,textarea,select,button,a[href],[role=button]')).filter(function(e){var r=e.getBoundingClientRect();
return r.width>0&&r.height>0&&r.bottom>0&&r.top<H&&r.right>0&&r.left<W;}).slice(0,30).map(function(e){var g=e.tagName.toLowerCase();
var l=(e.getAttribute('placeholder')||e.getAttribute('aria-label')||(g==='input'?'':(e.innerText||''))||e.getAttribute('title')||'').replace(/\s+/g,' ').trim().slice(0,40);
var r=e.getBoundingClientRect();return g+(g==='input'&&e.type?'['+e.type+']':'')+(l?' "'+l+'"':'')+' @'+Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);});
return JSON.stringify({t:out.join(' | ').slice(0,1600),all:(b.innerText||'').length,el:el});}catch(e){return JSON.stringify({t:'',all:0,el:[],err:String(e)});}})()"""

    data class Blankness(val dominant: Double, val colors: Int) {
        /** One flat colour (white, black, the page background) with nothing drawn on it. */
        val blank: Boolean get() = dominant >= 0.997 || colors <= 2
        fun describe() = "dominant=${"%.4f".format(dominant)} colors=$colors"
    }

    /** Samples a ~96×96 grid; colours quantised to 4 bits per channel. */
    fun blankness(bmp: Bitmap): Blankness {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return Blankness(1.0, 0)
        val stepX = maxOf(1, w / 96); val stepY = maxOf(1, h / 96)
        val counts = HashMap<Int, Int>()
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val q = (((p shr 20) and 0xF) shl 8) or (((p shr 12) and 0xF) shl 4) or ((p shr 4) and 0xF)
                counts[q] = (counts[q] ?: 0) + 1
                total++
                x += stepX
            }
            y += stepY
        }
        val max = counts.values.maxOrNull() ?: 0
        return Blankness(if (total == 0) 1.0 else max.toDouble() / total, counts.size)
    }

    fun blanknessOfFile(path: String?): Blankness? = runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(path ?: return null, o)?.let { b -> blankness(b).also { b.recycle() } }
    }.getOrNull()

    /** Formats [VIEWPORT_TEXT_JS]'s answer as plain lines for a tool result. */
    fun formatViewportText(json: String?): String? = runCatching {
        val o = JSONObject(json ?: return null)
        val t = o.optString("t")
        val el = o.optJSONArray("el")
        buildString {
            appendLine("  Visible text (this viewport): " + t.ifBlank { "(none)" })
            if (el != null && el.length() > 0) {
                append("  On screen: ")
                append((0 until el.length()).joinToString("; ") { el.optString(it) })
                appendLine()
            }
            append("  Page text total: ${o.optInt("all")} chars")
        }
    }.getOrNull()

    /**
     * Self-test (adb SelfTestProvider "webrender"): drives the agent's real browser path —
     * a [BrowserTabPool] and its actions, exactly what browser_use runs — on [url] in the
     * given UA profile, and checks DOM text + a non-blank screenshot.
     */
    suspend fun selfTest(context: Context, url: String, profile: UserAgentProfile, needText: Int = 20): String {
        val o = JSONObject().put("url", url).put("profile", profile.value)
        val pool = BrowserTabPool(context.applicationContext)
        try {
            pool.execute(BrowserActionInput(action = BrowserAction.SET_USER_AGENT, userAgent = profile), singleTab = true, owner = null)
            val t0 = System.currentTimeMillis()
            val nav = pool.execute(BrowserActionInput(action = BrowserAction.NAVIGATE, url = url), singleTab = true, owner = null)
            o.put("navMs", System.currentTimeMillis() - t0).put("navOk", nav.success).put("nav", nav.text.take(500))
            val txt = pool.execute(BrowserActionInput(action = BrowserAction.GET_TEXT), singleTab = true, owner = null)
            val body = txt.text
            o.put("textOk", txt.success).put("textLen", body.length).put("text", body.take(160))
            val shot = pool.execute(BrowserActionInput(action = BrowserAction.SCREENSHOT), singleTab = true, owner = null)
            o.put("shotOk", shot.success && shot.base64Image != null)
            val b = blanknessOfFile(shot.imageFilePath)
            o.put("blank", b?.blank ?: true).put("pixels", b?.describe() ?: "no image")
            o.put("shotText", shot.text.take(700))
            o.put("hasViewportText", shot.text.contains("Visible text"))
            o.put("gpuDisabled", HeadlessRenderHost.disabled ?: "")
            // set_user_agent right before navigate is what agents do when switching 手机/电脑 mode;
            // the navigate must report the real page, never the about:blank placeholder.
            val navBlank = nav.text.startsWith("Navigated to about:blank") || shot.text.contains("URL: about:blank")
            o.put("navBlank", navBlank)
            val ok = !navBlank && nav.success && txt.success && body.length >= needText && shot.success &&
                shot.base64Image != null && b != null && !b.blank && shot.text.contains("Visible text")
            o.put("ok", ok)
        } catch (e: Throwable) {
            o.put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { pool.destroyAllTabs() } }
            runCatching { BrowserTabPoolRegistry.unregister(pool) }
        }
        return o.toString()
    }
}

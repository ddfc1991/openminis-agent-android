package com.openminis.app.browser

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [Mines-translate-35] 网页翻译 for the in-app browser (user, 2026-10-14: 内置网页没有翻译功能). The user
 * reads Chinese only.
 *
 * The page's visible text nodes are collected by JS (scripts/code/inputs skipped), translated in
 * batches and written back in place, so layout, links and buttons keep working; 「显示原文」 restores
 * them. Engines, most reliable first:
 *  1. Google's free web translator (translate.googleapis.com, client=gtx; needs the VPN in mainland China);
 *  2. the user's own model key (DeepSeek / 智谱 / 硅基流动, as 消息 uses — works on a domestic network).
 * Pages already mostly Chinese are left alone; [detectForeign] drives the 「这页是外文，翻译成中文？」 hint.
 */
object WebTranslate {
    private const val TAG = "WebTranslate"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()

    /** "foreign" when the page's visible text is mostly non-CJK letters, else "zh"/"empty". */
    const val DETECT_JS = """(function(){try{var t=((document.body&&document.body.innerText)||'').slice(0,4000);
var cjk=(t.match(/[\u4e00-\u9fff]/g)||[]).length;var lat=(t.match(/[A-Za-z\u00C0-\u024F\u0400-\u04FF\u3040-\u30ff\uac00-\ud7af]/g)||[]).length;
if(cjk+lat<40)return 'empty';return (lat>cjk*3)?'foreign':'zh';}catch(e){return 'zh'}})()"""

    /** Collects up to 600 translatable text nodes; returns a JSON array of strings (index = node id). */
    private const val COLLECT_JS = """(function(){try{
var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,INPUT:1,SELECT:1,OPTION:1,SVG:1,MATH:1,KBD:1,SAMP:1};
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
 var p=n.parentNode;while(p&&p!==document.body){if(skip[p.nodeName])return NodeFilter.FILTER_REJECT;if(p.isContentEditable)return NodeFilter.FILTER_REJECT;p=p.parentNode;}
 var s=n.nodeValue;if(!s||s.trim().length<2)return NodeFilter.FILTER_REJECT;
 if(!/[A-Za-z\u00C0-\u024F\u0400-\u04FF\u3040-\u30ff\uac00-\ud7af]/.test(s))return NodeFilter.FILTER_REJECT;
 if(/[\u4e00-\u9fff]/.test(s)&&!/[A-Za-z]{4,}/.test(s))return NodeFilter.FILTER_REJECT;
 return NodeFilter.FILTER_ACCEPT;}});
var nodes=[],out=[];while(w.nextNode()&&nodes.length<600){nodes.push(w.currentNode);}
window.__minesTr={nodes:nodes,orig:nodes.map(function(n){return n.nodeValue})};
for(var i=0;i<nodes.length;i++)out.push(nodes[i].nodeValue.replace(/\s+/g,' ').trim().slice(0,600));
return JSON.stringify(out);}catch(e){return '[]'}})()"""

    private fun applyJs(from: Int, arr: JSONArray) = """(function(){try{var t=window.__minesTr;if(!t)return 0;var a=${arr};var n=0;
for(var i=0;i<a.length;i++){var node=t.nodes[$from+i];if(node&&a[i]){var o=t.orig[$from+i];var lead=(o.match(/^\s*/)||[''])[0],tail=(o.match(/\s*$/)||[''])[0];node.nodeValue=lead+a[i]+tail;n++;}}
document.documentElement.setAttribute('data-mines-translated','1');return n;}catch(e){return -1}})()"""

    const val RESTORE_JS = """(function(){try{var t=window.__minesTr;if(!t)return 0;for(var i=0;i<t.nodes.length;i++){t.nodes[i].nodeValue=t.orig[i];}
document.documentElement.removeAttribute('data-mines-translated');return 1;}catch(e){return 0}})()"""

    private suspend fun eval(wv: WebView, js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            runCatching {
                wv.evaluateJavascript(js) { r ->
                    val v = runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue()?.toString() }.getOrNull() ?: ""
                    if (c.isActive) c.resume(v)
                }
            }.onFailure { if (c.isActive) c.resume("") }
        }
    }

    suspend fun detectForeign(wv: WebView): Boolean = eval(wv, DETECT_JS) == "foreign"

    // ---- engine 1: Google's free web translator (translate_a/t, client=gtx, many pieces per call) ----
    // (Edge's free endpoint edge.microsoft.com/translate/auth is gone — 404 as of 2026-10-14.)
    fun googleTranslate(texts: List<String>): List<String> {
        val form = okhttp3.FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val req = Request.Builder().url("https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=zh-CN&format=text")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0 Mobile Safari/537.36")
            .post(form).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("翻译服务出错 HTTP ${r.code}")
            val a = JSONArray(r.body?.string().orEmpty())
            if (texts.size == 1) return listOf(a.opt(0).let { if (it is JSONArray) it.optString(0) else a.optString(0) })
            return (0 until a.length()).map { i -> a.opt(i).let { if (it is JSONArray) it.optString(0) else it?.toString().orEmpty() } }
        }
    }

    // ---- engine 2: the user's own model ----
    private fun modelTranslate(texts: List<String>): List<String> {
        val ep = listOfNotNull(
            com.openminis.app.plus.PlusVault.defaultAccount("deepseek")?.let { com.openminis.app.plus.PlusVault.secret(it) }?.let { Triple("https://api.deepseek.com/chat/completions", it, "deepseek-chat") },
            com.openminis.app.plus.PlusVault.defaultAccount("zhipu")?.let { com.openminis.app.plus.PlusVault.secret(it) }?.let { Triple("https://open.bigmodel.cn/api/paas/v4/chat/completions", it, "glm-4-flash") },
            com.openminis.app.plus.PlusVault.defaultAccount("siliconflow")?.let { com.openminis.app.plus.PlusVault.secret(it) }?.let { Triple("https://api.siliconflow.cn/v1/chat/completions", it, "Qwen/Qwen2.5-7B-Instruct") },
        ).firstOrNull() ?: throw IllegalStateException("没有可用的翻译服务")
        val prompt = "把下面 JSON 数组里的每一项翻译成简体中文，保持顺序和条数，只输出 JSON 字符串数组：\n" + JSONArray(texts).toString()
        val body = JSONObject().put("model", ep.third).put("temperature", 0.1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        http.newCall(Request.Builder().url(ep.first).header("Authorization", "Bearer ${ep.second}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("模型翻译出错 HTTP ${r.code}")
            val txt = JSONObject(r.body?.string().orEmpty()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val a = JSONArray(txt.substring(txt.indexOf('['), txt.lastIndexOf(']') + 1))
            return (0 until a.length()).map { a.optString(it) }
        }
    }

    private fun translateBatch(texts: List<String>): List<String> =
        try { googleTranslate(texts).also { if (it.size != texts.size) throw IllegalStateException("条数不对") } }
        catch (e: Exception) {
            Log.w(TAG, "google: ${e.message}")
            try { modelTranslate(texts) } catch (m: Exception) {
                throw IllegalStateException(if (m.message?.contains("没有可用") == true) "翻译服务连不上（Google 翻译要能访问外网；也可以在 账号连接 填 DeepSeek / 智谱 / 硅基流动 的密钥）" else (m.message ?: "翻译失败"))
            }
        }

    /**
     * Translates the page in place. Returns how many text pieces changed. Throws with a plain reason.
     */
    suspend fun translate(@Suppress("UNUSED_PARAMETER") c: Context, wv: WebView, onProgress: (String) -> Unit = {}): Int {
        val raw = eval(wv, COLLECT_JS)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: throw IllegalStateException("读不到这页的文字")
        val texts = (0 until arr.length()).map { arr.optString(it) }
        if (texts.isEmpty()) return 0
        // batches of ≤ 50 pieces / ≤ 4500 chars
        val batches = ArrayList<IntRange>()
        var start = 0; var chars = 0
        for (i in texts.indices) {
            if (i > start && (i - start >= 50 || chars + texts[i].length > 4500)) { batches += start until i; start = i; chars = 0 }
            chars += texts[i].length
        }
        batches += start until texts.size
        var done = 0; var applied = 0
        coroutineScope {
            batches.chunked(3).forEach { group ->
                val results = group.map { r -> async(Dispatchers.IO) { r to translateBatch(texts.subList(r.first, r.last + 1)) } }.awaitAll()
                for ((r, out) in results) {
                    val n = eval(wv, applyJs(r.first, JSONArray(out))).toIntOrNull() ?: 0
                    applied += n.coerceAtLeast(0); done += r.count()
                    onProgress("正在翻译 ${done * 100 / texts.size}%")
                }
            }
        }
        return applied
    }

    suspend fun restore(wv: WebView) { eval(wv, RESTORE_JS) }
}

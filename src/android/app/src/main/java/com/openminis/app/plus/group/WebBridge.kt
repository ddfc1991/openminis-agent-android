package com.openminis.app.plus.group

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openminis.app.plus.AccountJar
import com.openminis.app.plus.LoginWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * [Mines-38.8] Web-chat bridge for the registry's WEBVIEW presets (Hark, Manus web, Meta Muse web) — sites whose pages
 * could not be probed, so nothing here depends on a site's own class names:
 *  - the account's own isolated store (38.7 [AccountJar]: one WebView profile per member id, "web:hark", "web:hark#2" …);
 *  - the page is classified with 38.7's [WebChat.probeJs] / [WebChat.classify] + the preset's wall regex first;
 *  - the question goes into the bottom-most visible textarea / contenteditable, then a send-ish button or Enter;
 *  - a MutationObserver installed at send time records the newest text block that appeared after the question
 *    (assistant-looking containers first, any new block otherwise) and when the page last changed; the reply is done
 *    when the page has been quiet for [WebProfile.stableMs] and no Stop button shows;
 *  - CAPTCHA / 2FA / age check / login wall → a plain 「需要你操作」 error that sends the user to 「登录」 (Mines never
 *    solves those itself).
 */
object WebBridge {
    fun handles(id: String): Boolean = PresetRegistry.webProfile(id) != null

    private val verifyRx = Regex("Human Verification|确认您是人类|人机验证|访问验证|滑动验证|Just a moment|Verify you are human|Checking your browser|" +
        "two-factor|2-step|两步验证|二次验证|verification code|输入验证码|age verification|verify your age|confirm your age")

    private class Held(val wv: WebView, var used: Long, val url: String)
    private val held = HashMap<String, Held>()

    @SuppressLint("SetJavaScriptEnabled")
    private fun newView(context: Context, id: String): WebView {
        val wv = WebView(context.applicationContext)
        if (!AccountJar.prepare(wv, id) && id.contains('#')) {
            runCatching { wv.destroy() }
            throw MemberError("这台手机的系统 WebView 太旧，第二个账号没法单独隔离：先更新「Android System WebView」")
        }
        LoginWeb.configure(wv)
        wv.webViewClient = WebViewClient()
        val w = 1080; val h = 2200
        wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        wv.layout(0, 0, w, h)
        return wv
    }

    private suspend fun eval(wv: WebView, js: String): String = suspendCancellableCoroutine { c ->
        runCatching {
            wv.evaluateJavascript(js) { r ->
                val v = runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
                if (c.isActive) c.resume(v)
            }
        }.onFailure { if (c.isActive) c.resume("") }
    }

    private fun urls(spec: AiSpec, id: String): List<String> = listOf(PresetRegistry.webUrl(id).ifBlank { spec.webUrl }) + (PresetRegistry.webProfile(id)?.extraUrls ?: emptyList())

    private suspend fun drop(id: String) {
        val h = held.remove(id) ?: return
        AccountJar.saveLocal(id, eval(h.wv, AccountJar.DUMP_LOCAL_JS))
        runCatching { h.wv.destroy() }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        held.entries.filter { now - it.value.used > 600_000 }.toList().forEach { runCatching { it.value.wv.destroy() }; held.remove(it.key) }
    }

    /** Installs the observer, types the question and presses send. Returns 'ok' / 'noinput' / 'err:…'. */
    fun sendJs(site: WebChat.Site, q: String): String = """(function(){try{
function vis(e){if(!e)return false;var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
function pick(sel){if(!sel)return null;var l;try{l=document.querySelectorAll(sel);}catch(e){return null;}var b=null;for(var i=0;i<l.length;i++){if(l[i].disabled||!vis(l[i]))continue;var r=l[i].getBoundingClientRect();if(!b||r.bottom>=b.getBoundingClientRect().bottom)b=l[i];}return b;}
var q=${JSONObject.quote(q)};var RS=${JSONObject.quote(site.reply)};
var inp=pick(${JSONObject.quote(site.input)})||pick('textarea')||pick('[contenteditable="true"]');
if(!inp)return 'noinput';
var M=window.__mb={q:q,t:Date.now(),last:'',lastNode:null,pre:new WeakSet(),inp:inp};
try{var ex=document.querySelectorAll(RS||'p');for(var i=0;i<ex.length;i++)M.pre.add(ex[i]);}catch(e){}
function inInput(n){for(var x=n;x;x=x.parentElement){if(x===inp||x.tagName=='TEXTAREA'||x.isContentEditable)return true;}return false;}
function block(n){var e=n.nodeType==1?n:n.parentElement;while(e&&e!==document.body){var d=getComputedStyle(e).display;if((d=='block'||d=='flex'||d=='grid'||d=='list-item')&&(e.innerText||'').length>0)return e;e=e.parentElement;}return null;}
function best(){var c=null;if(RS){try{var l=document.querySelectorAll(RS);for(var i=l.length-1;i>=0;i--){if(!M.pre.has(l[i])&&!inInput(l[i])&&vis(l[i])&&(l[i].innerText||'').trim().length>0){c=l[i];break;}}}catch(e){}}
 return c;}
if(window.__mbObs)try{window.__mbObs.disconnect();}catch(e){}
window.__mbObs=new MutationObserver(function(ms){M.t=Date.now();var c=best();
 if(!c){for(var i=ms.length-1;i>=0&&!c;i--){var m=ms[i];var ns=m.addedNodes&&m.addedNodes.length?m.addedNodes:[m.target];for(var j=ns.length-1;j>=0;j--){if(inInput(ns[j]))continue;var b=block(ns[j]);if(b&&!inInput(b)){var tx=(b.innerText||'').trim();if(tx.length>0&&tx.indexOf(q.slice(0,40))<0){c=b;break;}}}}}
 if(c){M.lastNode=c;}
 if(M.lastNode)M.last=(M.lastNode.innerText||'');});
window.__mbObs.observe(document.body,{childList:true,subtree:true,characterData:true});
inp.focus();
function cur(){return inp.tagName=='TEXTAREA'||inp.tagName=='INPUT'?inp.value:(inp.innerText||'');}
if(inp.tagName=='TEXTAREA'||inp.tagName=='INPUT'){var proto=inp.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
Object.getOwnPropertyDescriptor(proto,'value').set.call(inp,q);inp.dispatchEvent(new Event('input',{bubbles:true}));inp.dispatchEvent(new Event('change',{bubbles:true}));}
else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,q);
 if(cur().indexOf(q.slice(0,12))<0){try{var dt=new DataTransfer();dt.setData('text/plain',q);inp.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));}catch(e){}}
 if(cur().trim().length<2){inp.innerText=q;inp.dispatchEvent(new InputEvent('input',{bubbles:true,data:q}));}}
var tries=0;
function fire(){var b=pick(${JSONObject.quote(site.send)});
 if(b&&(b.disabled||b.getAttribute('aria-disabled')=='true'))b=null;
 if(!b){var bs=document.querySelectorAll('button,[role=button]');for(var i=bs.length-1;i>=0;i--){var x=bs[i];var a=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('data-testid')||'')+' '+(x.getAttribute('class')||'')+' '+(x.innerText||'')).toLowerCase();
  if(!x.disabled&&x.getAttribute('aria-disabled')!='true'&&vis(x)&&/send|发送|submit|提交|arrow-up|arrowup/.test(a)){b=x;break;}}}
 if(b){b.click();M.sent='click';return;}
 if(++tries<8){setTimeout(fire,350);return;}
 ['keydown','keypress','keyup'].forEach(function(t){inp.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});M.sent='enter';}
setTimeout(fire,400);
return 'ok';}catch(e){return 'err:'+e}})()"""

    /** {text, idle, busy, page}: newest reply text, ms since the page last changed, Stop button shown, page tail. */
    const val READ_JS = """(function(){try{var M=window.__mb||{};var t=(document.body&&document.body.innerText)||'';var out=M.last||'';
if(!out&&M.q){var key=M.q.slice(-24);var i=t.lastIndexOf(key);if(i>=0)out=t.slice(i+key.length);}
var busy=!!document.querySelector('[aria-label*="停止"],[aria-label*="Stop"],[aria-label*="stop"],[data-testid*="stop"],[class*="stop-button"],[class*="stopBtn"]');
return JSON.stringify({text:out,idle:Date.now()-(M.t||0),busy:busy,page:t.slice(-2500)});}catch(e){return JSON.stringify({text:'',idle:0,busy:false,page:''})}})()"""

    private val baseNoise = setOf("复制", "重新生成", "分享", "编辑", "朗读", "Copy", "Regenerate", "Share", "Edit", "发送", "停止", "Stop", "Retry", "重试")

    fun clean(t: String, noise: Set<String>, question: String): String = t.lines().map { it.trim() }
        .filter { l -> l.isNotEmpty() && l !in baseNoise && noise.none { n -> l.equals(n, true) || (l.length < 40 && l.startsWith(n, true)) } && l != question.trim() }
        .joinToString("\n").trim()

    // [Mines-38.9.1] user 2026-10-17: 「没成功：Hark 需要你操作：先登录。在「AI 伙伴」里点…」 — one sentence, the button does the rest
    private fun needsUser(name: String, what: String) =
        MemberError(if (what == "先登录") "$name 还没登录，点这里去登录" else "$name 要你先做一步：$what（点这里去登录页完成）")

    suspend fun ask(context: Context, p: Partner, question: String): AiClient.Reply = AccountJar.lock(p.id).withLock {
        val prof = PresetRegistry.webProfile(p.id) ?: throw MemberError("不认识这个网页成员")
        val site = prof.site
        val name = p.spec.name
        val url = PresetRegistry.webUrl(p.id).ifBlank { p.base }
        if (url.isBlank() || !url.startsWith("http")) throw MemberError(if (prof.urlRequired) "$name 还没填网页地址：在「AI 伙伴 › 更多免费接入」里填" else "$name 网页地址不对")
        withContext(Dispatchers.Main) {
            sweep()
            if (!AccountJar.profiles()) held.keys.filter { WebAccounts.site(it) == WebAccounts.site(p.id) && it != p.id }.forEach { drop(it) }
            AccountJar.activate(p.id, urls(p.spec, p.id))
            var h = held[p.id]
            if (h == null || h.url != url) {
                h?.let { runCatching { it.wv.destroy() } }
                val wv = newView(context, p.id)
                wv.loadUrl(url)
                h = Held(wv, System.currentTimeMillis(), url); held[p.id] = h
            }
            h.used = System.currentTimeMillis()
            val wv = h.wv
            // 1. classify the page (wall / robot check / chat box)
            val t0 = System.currentTimeMillis()
            var st = WebChat.PageState.LOADING; var text = ""
            while (System.currentTimeMillis() - t0 < 30_000) {
                delay(1000)
                val o = runCatching { JSONObject(eval(wv, WebChat.probeJs(site))) }.getOrNull() ?: continue
                st = WebChat.classify(site, o); text = o.optString("text")
                if (verifyRx.containsMatchIn(text.take(2000)) && !o.optBoolean("input")) st = WebChat.PageState.VERIFY
                if (st == WebChat.PageState.SIGNED_IN || st == WebChat.PageState.GUEST) { if (System.currentTimeMillis() - t0 > 3000) break }
                else if (st != WebChat.PageState.LOADING && System.currentTimeMillis() - t0 > 8000) break
            }
            when (st) {
                WebChat.PageState.VERIFY -> { drop(p.id); throw needsUser(name, if (Regex("age|年龄").containsMatchIn(text)) "年龄验证" else "人机验证 / 两步验证") }
                WebChat.PageState.LOGIN -> {
                    drop(p.id); WebChat.markProbe(p.id, p.spec, WebChat.PageState.LOGIN)
                    if (prof.proxyPin == "us" && Regex("not available in your (country|region)").containsMatchIn(text))
                        throw MemberError("$name 只在美国网络能用：在代理 App 里给 Mines 开美国线路（全局），再试")
                    throw needsUser(name, "先登录")
                }
                WebChat.PageState.LOADING -> { drop(p.id); throw MemberError("$name 网页没打开聊天框（可能改版、网络慢或要开代理），这轮跳过") }
                WebChat.PageState.GUEST -> { WebChat.markProbe(p.id, p.spec, WebChat.PageState.GUEST); throw needsUser(name, "先登录") }
                WebChat.PageState.SIGNED_IN -> WebChat.markSignedIn(p.id, true)
            }
            // 2. type + send (observer installed first)
            val s = eval(wv, sendJs(site, question))
            if (s != "ok") throw MemberError("$name 网页没法输入（$s）")
            // 3. wait until the page has been quiet long enough and no Stop button shows
            var last = ""
            val t1 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t1 < prof.timeoutMs) {
                delay(1500)
                val o = runCatching { JSONObject(eval(wv, READ_JS)) }.getOrNull() ?: continue
                val txt = clean(o.optString("text"), prof.noise, question)
                val page = o.optString("page")
                if (txt.isEmpty() && System.currentTimeMillis() - t1 > 4000) {
                    if (verifyRx.containsMatchIn(page)) { drop(p.id); throw needsUser(name, "人机验证 / 两步验证") }
                    if (site.wall?.containsMatchIn(page) == true) { drop(p.id); WebChat.markProbe(p.id, p.spec, WebChat.PageState.LOGIN); throw needsUser(name, "先登录") }
                }
                if (txt.isNotEmpty()) last = txt
                if (last.isNotEmpty() && !o.optBoolean("busy") && o.optLong("idle") >= prof.stableMs) break
            }
            h.used = System.currentTimeMillis()
            AccountJar.saveLocal(p.id, eval(wv, AccountJar.DUMP_LOCAL_JS))
            AccountJar.remember(p.id, urls(p.spec, p.id))
            LoginWeb.flush()
            if (last.isBlank()) throw MemberError("$name ${prof.timeoutMs / 60_000} 分钟内没回复，这轮跳过")
            AiClient.Reply(last.take(6000), model = "网页版")
        }
    }
}

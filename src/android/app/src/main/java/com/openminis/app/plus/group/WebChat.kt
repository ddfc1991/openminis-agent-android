package com.openminis.app.plus.group

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openminis.app.plus.AccountJar
import com.openminis.app.plus.LoginWeb
import com.openminis.app.plus.MailParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * [Mines-38.6 → 38.7] An AI app as a group / 单聊 member through its web chat, signed in with the user's own account. No key:
 * Mines types the question into the page's chat box, presses send and reads the reply when it stops growing.
 *
 * 38.7 (checked against the real sites from a GitHub runner, mobile Chrome, 2026-10-15 — probe in hark-build/probe):
 *  - per-site chat box / send button / reply selectors fixed for the current pages (千问 moved to www.qianwen.com,
 *    Gemini's reply is `model-response .markdown`, 千问's `.qk-markdown`, 元宝 says 「请登录后输入内容」 in its box …);
 *  - the page is classified before anything is sent: SIGNED_IN, GUEST (chat box works without an account: Gemini, 千问
 *    from that network), LOGIN (a login wall / 「请先登录」 / region lock: 豆包, 元宝), VERIFY (a robot check the user
 *    has to pass in the login page: DeepSeek's AWS check, 智谱清言's slider, ChatGPT's Cloudflare page);
 *  - a login dialog that only appears after sending (Kimi 「手机号码登录」, Grok 「注册以无缝继续使用」) is caught too;
 *  - every account is its own member ("web:x", "web:x#2" …) with an isolated store ([AccountJar]).
 */
object WebChat {
    class Site(
        val input: String = "", val send: String = "", val reply: String = "",
        /** visible element that only a signed-OUT page shows (header 登录 button …) */
        val loggedOut: String = "",
        /** text a hard login wall / region lock shows */
        val wall: Regex? = null,
        /** the site lets a visitor chat without an account (seen on the real site) */
        val guestOk: Boolean = false,
        /** [Mines-38.7 v11] session cookie a signed-in account always has: without it the page is a visitor's (GUEST) */
        val sessionCookie: String = "",
    )

    private val genericWall = Regex("请先登录|请登录后|登录后(才能|即可|使用|继续|输入)|受区域限制|手机号码?登录|发送验证码|获取验证码|注册以无缝继续|免费注册|Log in to continue|Sign in to continue|Sign up to continue")
    private val verifyRx = Regex("Human Verification|确认您是人类|人机验证|访问验证|滑动验证|按住滑块|拖动到最右|Just a moment|请稍候|Verify you are human|Checking your browser|安全检查")

    val sites = mapOf(
        "web:doubao" to Site("textarea[data-testid='chat_input_input'],textarea", "button[data-testid='chat_input_send_button'],#flow-end-msg-send",
            "[data-testid='receive_message'] [data-testid='message_text_content'],[data-testid='receive_message']",
            loggedOut = "[data-testid='to_login_button'],button[class*='login']", wall = Regex("受区域限制|请先登录再使用豆包")),
        "web:yuanbao" to Site(".ql-editor[contenteditable='true'],[contenteditable='true']", "#yuanbao-send-btn,a[class*='send-btn'],[class*='send-btn']",
            ".agent-chat__list__item--ai .hyc-content-md,.agent-chat__list__item--ai",
            loggedOut = ".agent-dialogue__tool__login", wall = Regex("请登录后输入内容"),
            // smoke 2026-10-15 21:2x: the never-signed-in 元宝 page (chat box, no 登录 text in the page yet) was taken for signed in
            sessionCookie = "hy_token"),
        "web:kimi" to Site(".chat-input-editor[contenteditable='true'],[contenteditable='true'],textarea", ".send-button-container,.send-button,[class*='send-button']",
            ".segment-assistant .markdown,.chat-content-item-assistant .markdown,.segment-assistant",
            loggedOut = ".login-button,.next-sidebar-history-list__login"),
        "web:deepseek" to Site("textarea#chat-input,textarea", "div[role='button'][aria-disabled='false']:last-of-type", ".ds-markdown",
            loggedOut = "a[href*='sign_in']"),
        "web:tongyi" to Site("[contenteditable='true'],textarea", "button[aria-label='发送消息'],[class*='operateBtn'],[class*='sendBtn']",
            ".qk-markdown,[class*='answer-common-card'],[class*='answerItem'] [class*='markdown']",
            guestOk = true),
        "web:chatglm" to Site("textarea", ".enter,[class*='send']", ".answer-content,.markdown-body"),
        "web:chatgpt" to Site("#prompt-textarea,[contenteditable='true'],textarea", "button[data-testid='send-button'],#composer-submit-button",
            "[data-message-author-role='assistant']", loggedOut = "button[data-testid='login-button']"),
        "web:gemini" to Site(".ql-editor[contenteditable='true'],rich-textarea [contenteditable='true'],textarea",
            "button[aria-label='发送'],button[aria-label*='Send'],button.send-button",
            "model-response .markdown,model-response message-content,structured-content-container.model-response-text",
            loggedOut = "a[href*='ServiceLogin'],button[aria-label='登录'],a[aria-label*='Sign in']", guestOk = true),
        "web:grok" to Site("textarea,[contenteditable='true']", "button[data-testid='chat-submit'],button[type='submit'],button[aria-label*='提交']",
            ".message-bubble:not([data-testid='user-message']) .response-content-markdown,div[id^='response-'] .message-bubble",
            loggedOut = "a[href*='sign-in'],a[href*='/sign-up']"),
    )

    fun site(id: String): Site = sites[WebAccounts.site(id)] ?: PresetRegistry.webSite(id) ?: Site()  // [Mines-38.8] registry bridges
    fun guestOk(id: String) = site(id).guestOk

    /** The domains whose cookies make up one account's session (for [AccountJar] swaps / sign-out). */
    fun urls(spec: AiSpec, id: String): List<String> {
        val u = GroupPrefs.get("base_${WebAccounts.site(id)}") ?: spec.webUrl
        val extra = when (WebAccounts.site(id)) {
            "web:gemini" -> listOf("https://accounts.google.com/", "https://www.google.com/")
            "web:tongyi" -> listOf("https://www.tongyi.com/", "https://www.qianwen.com/")
            "web:kimi" -> listOf("https://kimi.moonshot.cn/")
            else -> emptyList()
        }
        return listOf(u) + extra
    }

    private val sessionNameRx = Regex("(?i)(session|sess|auth|token|jwt|sid|refresh|logged|user_?id)")
    private val noiseNameRx = Regex("(?i)^(__cf_bm|cf_clearance|__cflb|_ga.*|_gid|_gcl.*|_fbp|ajs_.*|intercom.*|mp_.*|amplitude.*|optimizely.*|__stripe.*)$")
    /** [Mines-38.9.1] the site's cookie jar holds a cookie that looks like a sign-in (on the page host, its root or app./api. hosts). */
    internal fun sessionLike(cm: android.webkit.CookieManager, urls: List<String>): Boolean = urls.flatMap { u ->
        val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return@flatMap emptyList()
        val parts = host.split('.'); val root = if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
        listOf(u, "https://$host/", "https://$root/", "https://www.$root/", "https://app.$root/", "https://api.$root/")
    }.any { u -> cookieNamesLookSignedIn(runCatching { cm.getCookie(u) }.getOrNull().orEmpty()) }

    /** pure part (unit-testable): "a=1; hark_session=x" → true; only Cloudflare / analytics cookies → false */
    fun cookieNamesLookSignedIn(cookie: String): Boolean = cookie.split(';').map { it.substringBefore('=').trim() }
        .any { n -> n.isNotEmpty() && !noiseNameRx.matches(n) && sessionNameRx.containsMatchIn(n) }

    /** True when this account is signed in (a confirmed sign-in / a run past the wall, or the session cookie in its store). */
    fun signedIn(spec: AiSpec, id: String = spec.id): Boolean {
        when (GroupPrefs.get("weblogin_$id")) { "1" -> return true; "0" -> return false }
        // [Mines-38.9.1] user 2026-10-17: Hark said 「先登录」 right after a sign-in. Registry web presets (Hark, Manus …) carry
        // no known session-cookie names, so a sign-in was only ever believed after a chat page had been seen. Now any
        // session-looking cookie (session / auth / token / sid / jwt …, not Cloudflare / analytics) on the site counts.
        if (spec.cookies.isEmpty()) return runCatching { sessionLike(AccountJar.cookieManager(id), urls(spec, id)) }.getOrDefault(false)
        // with SWAP only the account that holds the shared store right now can be checked by cookie
        if (!AccountJar.profiles() && (AccountJar.active(WebAccounts.site(id)) ?: WebAccounts.site(id)) != id) return false
        val cm = AccountJar.cookieManager(id)
        return urls(spec, id).flatMap { u ->
            val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return@flatMap emptyList()
            val parts = host.split('.'); val root = if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
            listOf(u, "https://$host/", "https://$root/", "https://www.$root/")
        }.any { u -> MailParse.cookieHas(runCatching { cm.getCookie(u) }.getOrNull().orEmpty(), spec.cookies) }
    }

    /** [Mines-38.7 v11] the account's own cookie store holds [names] for this site (profile or swapped store). */
    fun hasCookie(spec: AiSpec, id: String, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val cm = AccountJar.cookieManager(id)
        return urls(spec, id).flatMap { u ->
            val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return@flatMap emptyList()
            val parts = host.split('.'); val root = if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
            listOf(u, "https://$host/", "https://$root/", "https://www.$root/")
        }.any { u -> MailParse.cookieHas(runCatching { cm.getCookie(u) }.getOrNull().orEmpty(), names) }
    }

    /** [classify] plus the account's session cookie: a SIGNED_IN look without it is a visitor (GUEST). */
    fun classifyFor(spec: AiSpec?, id: String, o: JSONObject): PageState {
        val site = site(id)
        val st = classify(site, o)
        if (st != PageState.SIGNED_IN || site.sessionCookie.isEmpty() || spec == null) return st
        return if (runCatching { hasCookie(spec, id, listOf(site.sessionCookie)) }.getOrDefault(true)) st else PageState.GUEST
    }

    fun markSignedIn(id: String, on: Boolean?) {
        if (on == true) GroupPrefs.put("weblogin_ever_$id", "1")   // [Mines-38.9.3] a site never signed in is not 「失效」
        GroupPrefs.put("weblogin_$id", when (on) { true -> "1"; false -> "0"; null -> null })
    }
    fun everSignedIn(id: String): Boolean = GroupPrefs.get("weblogin_ever_$id") == "1" || GroupPrefs.get("weblogin_$id") != null

    /**
     * [Mines-38.9.1] a probe result changes the saved sign-in state only when it is sure ([com.openminis.app.plus.SessionStatus]):
     * one guest-looking page / one login wall with the session cookie still there is 「未知」, not 「掉了」.
     */
    fun markProbe(id: String, spec: AiSpec?, st: PageState) {
        val walls = GroupPrefs.get("weblogin_walls_$id")?.toIntOrNull() ?: 0
        val names = (listOfNotNull(site(id).sessionCookie.takeIf { it.isNotEmpty() }) + (spec?.cookies ?: emptyList())).distinct()
        val cookie = if (spec == null || names.isEmpty()) true else runCatching { hasCookie(spec, id, names) }.getOrDefault(true)
        val p = when (st) {
            PageState.SIGNED_IN -> com.openminis.app.plus.SessionStatus.Probe.SIGNED_IN
            PageState.GUEST -> com.openminis.app.plus.SessionStatus.Probe.GUEST
            PageState.LOGIN -> com.openminis.app.plus.SessionStatus.Probe.LOGIN
            PageState.VERIFY -> com.openminis.app.plus.SessionStatus.Probe.VERIFY
            PageState.LOADING -> com.openminis.app.plus.SessionStatus.Probe.LOADING
        }
        when (com.openminis.app.plus.SessionStatus.web(p, cookie, walls, everSignedIn(id))) {
            com.openminis.app.plus.SessionStatus.Verdict.OK -> markSignedIn(id, true)
            com.openminis.app.plus.SessionStatus.Verdict.EXPIRED -> markSignedIn(id, false)
            com.openminis.app.plus.SessionStatus.Verdict.UNKNOWN -> {}
        }
        GroupPrefs.put("weblogin_walls_$id", when (st) { PageState.LOGIN -> (walls + 1).toString(); PageState.SIGNED_IN -> null; else -> walls.takeIf { it > 0 }?.toString() })
    }

    private class Held(val wv: WebView, var used: Long, val url: String)
    private val held = HashMap<String, Held>()

    /** JS page classification: chat box, signed-out hints, wall, robot check. */
    fun probeJs(site: Site) = """(function(){try{
function vis(e){if(!e)return false;var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
function pick(sel){if(!sel)return null;var l;try{l=document.querySelectorAll(sel);}catch(e){return null;}var b=null,any=null;for(var i=0;i<l.length;i++){if(l[i].disabled)continue;var s=getComputedStyle(l[i]);if(s.display!='none'&&s.visibility!='hidden')any=l[i];if(vis(l[i])){var r=l[i].getBoundingClientRect();if(!b||r.bottom>=b.getBoundingClientRect().bottom)b=l[i];}}return b||any;}
var inp=pick(${JSONObject.quote(site.input)})||pick('textarea')||pick('[contenteditable="true"]');
var ph=inp?((inp.getAttribute('placeholder')||inp.getAttribute('data-placeholder')||'')+' '+(inp.getAttribute('aria-label')||'')):'';
var t=((document.title||'')+'\n'+((document.body&&document.body.innerText)||'')).slice(0,5000);
var lo=false;var lsel=${JSONObject.quote(site.loggedOut)};if(lsel){try{var ls=document.querySelectorAll(lsel);for(var i=0;i<ls.length;i++)if(vis(ls[i])){lo=true;break;}}catch(e){}}
if(!lo){var bs=document.querySelectorAll('a,button,[role=button]');for(var j=0;j<bs.length&&j<400;j++){var x=bs[j];var tx=(x.innerText||x.getAttribute('aria-label')||'').trim();if(/^(登录|登录\/注册|登录 \/ 注册|注册|Log in|Sign in|Sign up)$/i.test(tx)&&vis(x)&&x.getBoundingClientRect().top<innerHeight*0.25){lo=true;break;}}}
return JSON.stringify({input:!!inp,ph:ph,loggedOut:lo,text:t,url:location.href,len:t.length});}catch(e){return JSON.stringify({input:false,loggedOut:false,text:'',err:String(e)})}})()"""

    enum class PageState { SIGNED_IN, GUEST, LOGIN, VERIFY, LOADING }

    fun classify(site: Site, o: JSONObject): PageState {
        val t = o.optString("text"); val ph = o.optString("ph")
        return when {
            verifyRx.containsMatchIn(t.take(1500)) && !o.optBoolean("input") -> PageState.VERIFY
            site.wall?.containsMatchIn(t) == true || site.wall?.containsMatchIn(ph) == true || Regex("请登录|登录后").containsMatchIn(ph) -> PageState.LOGIN
            !o.optBoolean("input") && genericWall.containsMatchIn(t) -> PageState.LOGIN
            !o.optBoolean("input") -> PageState.LOADING
            o.optBoolean("loggedOut") -> PageState.GUEST
            // [Mines-38.7] 千问 shows its 登录 button in the side panel, with 「登录可同步历史对话」 (seen in smoke 2026-10-15)
            Regex("登录可同步|登录后可同步|登录以同步|登录解锁").containsMatchIn(t) -> PageState.GUEST
            // [Mines-38.7 v8] 千问 guest page (smoke 2026-10-15 18:06, qianwen.com): chat box + 「继续使用即表示您同意 用户协议 和
            // 隐私政策」 under it and no 登录 button near the top — it was taken for signed in. That consent line is the guest note.
            Regex("继续使用即表示您?同意|继续使用即代表您?同意|未登录|游客模式|登录后(即可|可以)?(保存|同步|查看)").containsMatchIn(t) -> PageState.GUEST
            else -> PageState.SIGNED_IN
        }
    }

    private fun sendJs(site: Site, q: String) = """(function(){try{
function vis(e){if(!e)return false;var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.display!='none'&&s.visibility!='hidden';}
function pick(sel){if(!sel)return null;var l;try{l=document.querySelectorAll(sel);}catch(e){return null;}var b=null,any=null;for(var i=0;i<l.length;i++){if(l[i].disabled)continue;var s=getComputedStyle(l[i]);if(s.display!='none'&&s.visibility!='hidden')any=l[i];if(vis(l[i])){var r=l[i].getBoundingClientRect();if(!b||r.bottom>=b.getBoundingClientRect().bottom)b=l[i];}}return b||any;}
var q=${JSONObject.quote(q)};
var rs=${JSONObject.quote(site.reply)};try{window.__minesReplyCount=rs?document.querySelectorAll(rs).length:0;}catch(e){window.__minesReplyCount=0;}
var inp=pick(${JSONObject.quote(site.input)})||pick('textarea')||pick('[contenteditable="true"]');
if(!inp)return 'noinput';
inp.focus();
function cur(){return inp.tagName=='TEXTAREA'||inp.tagName=='INPUT'?inp.value:(inp.innerText||'');}
if(inp.tagName=='TEXTAREA'||inp.tagName=='INPUT'){var proto=inp.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
Object.getOwnPropertyDescriptor(proto,'value').set.call(inp,q);inp.dispatchEvent(new Event('input',{bubbles:true}));inp.dispatchEvent(new Event('change',{bubbles:true}));}
else{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,q);
 if(cur().indexOf(q.slice(0,12))<0){try{var dt=new DataTransfer();dt.setData('text/plain',q);inp.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));}catch(e){}}
 if(cur().trim().length<2){inp.innerText=q;inp.dispatchEvent(new InputEvent('input',{bubbles:true,data:q}));}}
window.__minesQ=q;window.__minesSent='';
var tries=0;
function fire(){
 var b=pick(${JSONObject.quote(site.send)});
 if(b&&(b.disabled||b.getAttribute('aria-disabled')=='true'))b=null;
 if(!b){var bs=document.querySelectorAll('button,[role=button]');for(var i=bs.length-1;i>=0;i--){var x=bs[i];var a=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('data-testid')||'')+' '+(x.getAttribute('class')||'')+' '+(x.innerText||'')).toLowerCase();
 if(!x.disabled&&x.getAttribute('aria-disabled')!='true'&&vis(x)&&/send|发送|submit|提交/.test(a)){b=x;break;}}}
 if(b){b.click();window.__minesSent='click';return;}
 if(++tries<8){setTimeout(fire,350);return;}
 ['keydown','keypress','keyup'].forEach(function(t){inp.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});window.__minesSent='enter';
}
setTimeout(fire,400);
return 'ok';}catch(e){return 'err:'+e}})()"""

    private fun readJs(site: Site) = """(function(){try{
var rs=${JSONObject.quote(site.reply)};var out='';
if(rs){try{var l=document.querySelectorAll(rs);if(l.length>(window.__minesReplyCount||0))out=l[l.length-1].innerText||'';}catch(e){}}
var t=(document.body&&document.body.innerText)||'';
if(!out){var q=window.__minesQ||'';var key=q.slice(-24);var i=key?t.lastIndexOf(key):-1;
if(i>=0)out=t.slice(i+key.length);}
var busy=!!document.querySelector('[aria-label*="停止"],[aria-label*="Stop"],[data-testid*="stop"],[class*="stop-button"],[class*="stopBtn"]');
return JSON.stringify({text:out,busy:busy,page:t.slice(-3000)});}catch(e){return JSON.stringify({text:'',busy:false,page:''})}})()"""

    private val uiNoise = setOf("复制", "重新生成", "分享", "点赞", "点踩", "编辑", "朗读", "Copy", "Regenerate", "Share", "Edit", "发送", "停止", "内容由 AI 生成",
        "Gemini 说", "你说", "登录", "打开 App", "打开APP", "下载 APP")

    private fun clean(t: String): String = t.lines().map { it.trim() }
        .filter { it.isNotEmpty() && it !in uiNoise && !it.startsWith("内容由") && !it.contains("AI 生成") && !it.contains("AI生成") && !it.startsWith("Gemini 是一款") }
        .joinToString("\n").trim()

    @SuppressLint("SetJavaScriptEnabled")
    private fun newView(context: Context, id: String): WebView {
        val wv = WebView(context.applicationContext)
        // isolation first: own profile or the document-start localStorage swap, BEFORE anything loads
        if (!AccountJar.prepare(wv, id) && id.contains('#')) {
            runCatching { wv.destroy() }
            throw MemberError("这台手机的系统 WebView 太旧，第二个账号没法单独隔离：先更新「Android System WebView」")
        }
        LoginWeb.configure(wv)
        wv.webViewClient = WebViewClient()
        // laid out off screen at a phone size so the page's own visibility checks see real boxes
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

    /** Main thread: drops the held page of [id] (saving its localStorage for SWAP first). */
    private suspend fun drop(id: String) {
        val h = held.remove(id) ?: return
        AccountJar.saveLocal(id, eval(h.wv, AccountJar.DUMP_LOCAL_JS))
        runCatching { h.wv.destroy() }
    }

    /** Result of [check] for the smoke test / the 「检测」 row. */
    data class Check(val state: PageState, val url: String, val text: String)

    /**
     * Opens the account's page and classifies it (no question sent). Used by the login screen check and the real-site
     * smoke; also records SIGNED_IN / LOGIN for [signedIn].
     */
    suspend fun check(context: Context, spec: AiSpec, id: String, waitMs: Long = 25_000): Check = AccountJar.lock(id).withLock {
        withContext(Dispatchers.Main) {
            val h = open(context, spec, id)
            val (st, o) = waitState(h.wv, site(id), waitMs, spec, id)
            markProbe(id, spec, st)
            AccountJar.saveLocal(id, eval(h.wv, AccountJar.DUMP_LOCAL_JS)); AccountJar.remember(id, urls(spec, id))
            Check(st, o?.optString("url").orEmpty(), o?.optString("text").orEmpty().take(400))
        }
    }

    private suspend fun open(context: Context, spec: AiSpec, id: String, urlOverride: String? = null): Held {
        sweep()
        val url = urlOverride ?: GroupPrefs.get("base_${WebAccounts.site(id)}") ?: spec.webUrl
        // SWAP: the other accounts of this platform leave the shared store first
        if (!AccountJar.profiles()) held.keys.filter { WebAccounts.site(it) == WebAccounts.site(id) && it != id }.forEach { drop(it) }
        AccountJar.activate(id, urls(spec, id))
        var h = held[id]
        if (h == null || h.url != url) {
            h?.let { runCatching { it.wv.destroy() } }
            val wv = newView(context, id)
            wv.loadUrl(url)
            h = Held(wv, System.currentTimeMillis(), url); held[id] = h
        }
        h.used = System.currentTimeMillis()
        return h
    }

    private suspend fun waitState(wv: WebView, site: Site, waitMs: Long, spec: AiSpec? = null, id: String = ""): Pair<PageState, JSONObject?> {
        val t0 = System.currentTimeMillis()
        var last: JSONObject? = null; var st = PageState.LOADING
        while (System.currentTimeMillis() - t0 < waitMs) {
            delay(1000)
            val o = runCatching { JSONObject(eval(wv, probeJs(site))) }.getOrNull() ?: continue
            last = o; st = if (spec != null) classifyFor(spec, id, o) else classify(site, o)
            // a chat box decides at once; walls / checks get a few seconds (pages redirect / hydrate)
            if (st == PageState.SIGNED_IN || st == PageState.GUEST) { if (System.currentTimeMillis() - t0 > 3000) break }
            else if (st != PageState.LOADING && System.currentTimeMillis() - t0 > 8000) break
        }
        return st to last
    }

    /**
     * Asks [question] on the member's web chat; throws [MemberError] with a plain reason on a login wall / robot check / no
     * reply. [allowGuest] lets a not-signed-in account chat as a visitor where the site allows it (smoke / 游客试用).
     */
    suspend fun ask(context: Context, p: Partner, question: String, timeoutMs: Long = 180_000, allowGuest: Boolean = false): AiClient.Reply =
        AccountJar.lock(p.id).withLock {
        val site = site(p.id)
        val name = p.spec.name
        withContext(Dispatchers.Main) {
            val h = open(context, p.spec, p.id)
            val wv = h.wv
            // 1. what the page is: chat box / signed out / wall / robot check
            val (st, _) = waitState(wv, site, 30_000, p.spec, p.id)
            when (st) {
                PageState.VERIFY -> { drop(p.id); throw MemberError("$name 网页要先做一次人机验证：在「AI 伙伴」里点「登录」，在页面里过一下") }
                PageState.LOGIN -> { drop(p.id); markProbe(p.id, p.spec, PageState.LOGIN); throw MemberError("$name 要先登录：在「AI 伙伴」里点「登录」") }
                PageState.LOADING -> { drop(p.id); throw MemberError("$name 网页没打开聊天框（可能改版或网络慢），这轮跳过") }
                PageState.GUEST -> if (!allowGuest) { markProbe(p.id, p.spec, PageState.GUEST); throw MemberError("$name 要先登录：在「AI 伙伴」里点「登录」") }
                PageState.SIGNED_IN -> markSignedIn(p.id, true)
            }
            // 2. type + send
            val s = eval(wv, sendJs(site, question))
            if (s != "ok") throw MemberError("$name 网页没法输入（$s）")
            // 3. read until the reply stops growing; a login dialog that pops up after sending ends it
            var last = ""; var same = 0
            val t1 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t1 < timeoutMs) {
                delay(1500)
                val o = runCatching { JSONObject(eval(wv, readJs(site))) }.getOrNull() ?: continue
                val txt = clean(o.optString("text"))
                // [Mines-38.7 v11] a login / sign-up wall that pops up after sending is not a reply (smoke: Kimi 「手机号码登录 发送验证码」, Grok 「免费注册」)
                if ((txt.isEmpty() && System.currentTimeMillis() - t1 > 4000 && genericWall.containsMatchIn(o.optString("page"))) ||
                    (st == PageState.GUEST && txt.isNotEmpty() && genericWall.containsMatchIn(txt))) {
                    drop(p.id); markProbe(p.id, p.spec, PageState.LOGIN)
                    throw MemberError("$name 要先登录：在「AI 伙伴」里点「登录」")
                }
                if (txt.isNotEmpty() && txt == last && !o.optBoolean("busy")) { same++; if (same >= 3) break } else same = 0
                last = txt
            }
            h.used = System.currentTimeMillis()
            AccountJar.saveLocal(p.id, eval(wv, AccountJar.DUMP_LOCAL_JS))
            AccountJar.remember(p.id, urls(p.spec, p.id))
            LoginWeb.flush()
            if (last.isBlank()) throw MemberError("$name ${timeoutMs / 60_000} 分钟内没回复，这轮跳过")
            AiClient.Reply(last, model = if (st == PageState.GUEST) "网页版 · 游客" else "网页版")
        }
    }

    /**
     * smoke: signs [id] in by opening [loginUrl] in the account's own (isolated) page — the fake site sets its session cookie
     * and localStorage there — then keeps that session for the account exactly as a real sign-in in the login screen does.
     */
    suspend fun testLogin(context: Context, spec: AiSpec, id: String, loginUrl: String): String = AccountJar.lock(id).withLock {
        withContext(Dispatchers.Main) {
            val h = open(context, spec, id, loginUrl)
            delay(3500)
            AccountJar.saveLocal(id, eval(h.wv, AccountJar.DUMP_LOCAL_JS)); AccountJar.remember(id, urls(spec, id))
            held.remove(id)?.let { runCatching { it.wv.destroy() } }   // next ask opens the chat page fresh
            LoginWeb.flush(); markSignedIn(id, true)
            AccountJar.mode()
        }
    }

    /** 「退出这个账号」: this account only — its page, store and saved session go; the platform's other accounts stay. */
    suspend fun signOut(spec: AiSpec, id: String) = AccountJar.lock(id).withLock {
        withContext(Dispatchers.Main) { held.remove(id)?.let { runCatching { it.wv.destroy() } } }
        AccountJar.signOut(id, urls(spec, id))
        markSignedIn(id, false)
        com.openminis.app.plus.SessionKeeper.forget("web:$id")   // [Mines-38.9.3] a real sign-out is never undone
    }

    /** Drops WebViews idle for 10 minutes (main thread). */
    private fun sweep() {
        val now = System.currentTimeMillis()
        held.entries.filter { now - it.value.used > 600_000 }.toList().forEach { runCatching { it.value.wv.destroy() }; held.remove(it.key) }
    }
}

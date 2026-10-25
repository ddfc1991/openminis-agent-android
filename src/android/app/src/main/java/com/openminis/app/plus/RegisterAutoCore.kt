package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 register_auto] pure side of 一键注册 that runs by itself (user 2026-10-25 「这个一键注册好鸡肋。用临时邮箱注册这些，
 * 再加多一个自定义url或者网站」): the platform list + 自定义网址, one Mail.tm box per platform, the page classifier the runner
 * uses after every step, the verification link / code picked from the box, and the one folded task row
 * (「一键注册 · 1/2」 → 「Zeabur 注册 · 完成 ›」). No Android, unit-tested (RegisterAutoCoreTest).
 *
 * Rules kept from the 38.9.12 card: 一人一号 (one account per platform, a new box each), 不批量 (only the platforms the user
 * ticked, one after another, never two at once); captcha / phone number / payment → stop and ask the user.
 */
object RegisterAutoCore {
    const val MAX_PICK = 8                 // one run never registers more than this many platforms
    const val MAIL_WAIT_MS = 3 * 60_000L   // how long a box is polled for the code / link
    const val ASK_WAIT_MS = 15 * 60_000L   // a stop-and-ask waits this long for the user, then the row stays 未完成

    data class Platform(val name: String, val url: String, val custom: Boolean = false) {
        val host: String get() = hostOf(url)
    }

    // ── 自定义网址 ──

    private val HOST_RE = Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}$""")
    private val IP_RE = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    /**
     * 「zeabur.com」, 「https://zeabur.com/signup」, 「www.Zeabur.com/」 → a full URL (https:// when none given);
     * null when it is not a web address (spaces, javascript:, no dot, bad port …).
     */
    fun normUrl(input: String): String? {
        var s = input.trim().trim('「', '」', '"', '\'', '“', '”', '<', '>')
        if (s.isEmpty() || s.length > 300 || s.any { it.isWhitespace() }) return null
        val scheme = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://""").find(s)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) {
            if (s.contains(':') && !Regex("""^[^/:]+:\d{1,5}(/.*)?$""").matches(s)) return null   // mailto:, javascript: …
            s = "https://$s"
        } else if (scheme != "http" && scheme != "https") return null
        val rest = s.substringAfter("://")
        val auth = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (auth.isEmpty() || auth.contains('@')) return null
        val host = auth.substringBefore(':').lowercase()
        val port = auth.substringAfter(':', "")
        if (port.isNotEmpty() && (port.length > 5 || !port.all { it.isDigit() } || port.toInt() !in 1..65535)) return null
        val ipOk = IP_RE.matches(host) && host.split('.').all { it.toInt() in 0..255 }
        if (!ipOk && host != "localhost" && !HOST_RE.matches(host)) return null
        val path = rest.substring(auth.length)
        return s.substringBefore("://").lowercase() + "://" + host + (if (port.isNotEmpty()) ":$port" else "") + path.ifEmpty { "/" }
    }

    fun hostOf(url: String): String =
        url.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').removePrefix("www.")

    /** zeabur.com → Zeabur, app.netlify.com → Netlify, 10.0.2.2 → 10.0.2.2 */
    fun nameFor(url: String): String {
        val h = hostOf(url)
        if (IP_RE.matches(h) || h == "localhost") return h
        val p = h.split('.').filter { it.isNotBlank() }
        val two = setOf("com", "net", "org", "co", "gov", "edu", "ac")
        val label = when {
            p.size >= 3 && p[p.size - 2] in two && p.last().length == 2 -> p[p.size - 3]   // foo.com.cn
            p.size >= 2 -> p[p.size - 2]
            else -> h
        }
        return label.replaceFirstChar { it.uppercase() }
    }

    /** a custom entry added to the list (null + the reason when it can not be used) */
    fun custom(input: String, list: List<Platform>): Pair<Platform?, String?> {
        val u = normUrl(input) ?: return null to "这不是网址。填域名或完整链接，例如 zeabur.com"
        // [ledger v2] a custom site is called by its domain (zeabur.com); an IP-only site 「自定义网站」 until its page names it
        val h = hostOf(u)
        val p = Platform(if (RegLedgerCore.isIpHost(h)) "自定义网站" else h, u, custom = true)
        if (list.any { it.host == p.host }) return null to "${p.name} 已经在列表里"
        return p to null
    }

    /** what the user ticked, in list order, one per host (一人一号), at most [MAX_PICK] */
    fun picked(list: List<Platform>, ticked: Set<String>): List<Platform> =
        list.filter { it.host in ticked }.distinctBy { it.host }.take(MAX_PICK)

    // ── page classifier ──

    enum class Page { CAPTCHA, PHONE, PAYMENT, CODE, FORM, CHECK_MAIL, DONE, UNKNOWN }

    /** what the runner reads off the page ([SCAN_JS] result + HumanVerify's captcha kind) */
    data class Scan(
        val captcha: String = "", val email: Boolean = false, val pass: Int = 0, val code: Boolean = false, val codeEmpty: Boolean = false,
        val phone: Boolean = false, val phoneRequired: Boolean = false, val card: Boolean = false, val submit: Boolean = false,
        val checkMail: Boolean = false, val success: Boolean = false, val error: String = "", val url: String = "",
    )

    fun parseScan(raw: String?, captcha: String = ""): Scan = runCatching {
        val o = JSONObject(raw?.removePrefix("R:") ?: "{}")
        Scan(captcha, o.optBoolean("email"), o.optInt("pass"), o.optBoolean("code"), o.optBoolean("codeEmpty"), o.optBoolean("phone"),
            o.optBoolean("phoneReq"), o.optBoolean("card"), o.optBoolean("submit"), o.optBoolean("checkMail"), o.optBoolean("success"),
            o.optString("err").take(80), o.optString("url"))
    }.getOrDefault(Scan(captcha))

    /** priority: the three stop-and-ask cases first, then the code box, the form, 「查收邮件」, done */
    fun classify(s: Scan): Page = when {
        s.captcha.isNotBlank() -> Page.CAPTCHA
        s.card -> Page.PAYMENT
        s.code && s.codeEmpty && !s.email -> Page.CODE
        s.email || s.pass > 0 -> Page.FORM
        s.phone && s.phoneRequired -> Page.PHONE
        s.success -> Page.DONE
        s.checkMail -> Page.CHECK_MAIL
        s.code && s.codeEmpty -> Page.CODE
        else -> Page.UNKNOWN
    }

    /** the one short Chinese prompt for a stop-and-ask */
    fun askLine(name: String, p: Page, detail: String = ""): String = when (p) {
        Page.CAPTCHA -> "$name 要做人机验证" + (if (detail.isNotBlank() && detail != "人机验证") "（$detail）" else "") + "，点这里完成后我接着注册。"
        Page.PHONE -> "$name 要手机号验证，点这里填好后我接着注册。"
        Page.PAYMENT -> "$name 要填付款信息，我不替你付款。点这里自己决定。"
        else -> "$name 需要你看一下，点这里。"
    }

    /** [ledger v2] the takeover screen's own line: the user is already on the page, so no 「点这里」 */
    fun takeoverLine(p: Page): String = when (p) {
        Page.CAPTCHA -> "在下面完成人机验证，然后点「好了，继续」。"
        Page.PHONE -> "在下面填手机号和短信验证码，然后点「好了，继续」。"
        Page.PAYMENT -> "这一步要付款，Mines 不代付。自己决定，或点「跳过这个平台」。"
        else -> "在下面处理好，然后点「好了，继续」。"
    }

    // ── mail ──

    data class Msg(val id: String, val from: String, val subject: String, val text: String, val at: Long)

    /** GET /messages → newest first */
    fun parseMessages(json: String): List<Msg> = runCatching {
        val t = json.trim()
        val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("hydra:member") ?: it.optJSONArray("member") ?: JSONArray() }
        (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            Msg(m.optString("id"), m.optJSONObject("from")?.optString("address").orEmpty(), m.optString("subject"), m.optString("intro"), isoMs(m.optString("createdAt")))
        }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    /** GET /messages/{id} → text + every href of the html part */
    fun parseMessage(json: String): String = runCatching {
        val o = JSONObject(json)
        val html = o.optJSONArray("html")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } } ?: o.optString("html")
        val hrefs = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.joinToString("\n")
        listOf(o.optString("subject"), o.optString("text"), hrefs).filter { it.isNotBlank() }.joinToString("\n")
    }.getOrDefault("")

    private fun isoMs(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

    private val LINK_RE = Regex("""https?://[^\s"'<>()\]]+""")
    private val VERIFY_WORDS = Regex("(?i)(verif|confirm|activat|validat|magic|token=|/auth/|signup|register|email-?link|code=|激活|验证)")
    private val NOT_VERIFY = Regex("(?i)(unsubscribe|privacy|terms|/help|support|facebook\\.com|twitter\\.com|x\\.com/|linkedin|instagram|\\.png|\\.jpg|\\.gif)")

    /** the confirmation link in a mail: verify-like words, same site as the platform when possible; null = none */
    fun pickLink(text: String, host: String): String? {
        val links = LINK_RE.findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().filter { !NOT_VERIFY.containsMatchIn(it) }.toList()
        val verify = links.filter { VERIFY_WORDS.containsMatchIn(it) }
        val base = host.split('.').takeLast(2).joinToString(".")
        return verify.firstOrNull { hostOf(it).endsWith(base) } ?: verify.firstOrNull()
    }

    // ── run state + the folded task row ──

    enum class St(val zh: String) { QUEUED("排队"), RUNNING("进行中"), WAITING("等你"), DONE("完成"), PENDING("未完成") }

    data class Item(val p: Platform, val st: St = St.QUEUED, val line: String = "", val email: String = "", val ask: Page? = null)
    data class Run(val id: String, val items: List<Item>, val startedAt: Long = 0L, val endedAt: Long = 0L) {
        val finished: Boolean get() = items.all { it.st == St.DONE || it.st == St.PENDING }
    }

    /** 「Zeabur 注册 · 完成 ›」 */
    fun itemTitle(i: Item): String = "${i.p.name} 注册 · ${i.st.zh}"

    /** the one folded row: 「一键注册 · 进行中 1/3」 / 「一键注册 · 等你」 / 「一键注册 · 完成 2/2」 */
    fun runTitle(r: Run): String {
        val done = r.items.count { it.st == St.DONE }
        val st = when {
            r.items.any { it.st == St.WAITING } -> "等你"
            !r.finished -> "进行中 ${r.items.count { it.st == St.DONE || it.st == St.PENDING }}/${r.items.size}"
            done == r.items.size -> "完成 $done/${r.items.size}"
            else -> "完成 $done/${r.items.size} · ${r.items.size - done} 个未完成"
        }
        return "一键注册 · $st"
    }

    /** the line under the folded row (what is happening / what came out) */
    fun runLine(r: Run): String = r.items.firstOrNull { it.st == St.WAITING }?.line
        ?: r.items.firstOrNull { it.st == St.RUNNING }?.let { "${it.p.name}：${it.line.ifBlank { "注册中" }}" }
        ?: r.items.joinToString("，") { "${it.p.name} ${it.st.zh}" }

    /** ChatHome state for the 任务 tab: 等你 > 进行中 > 完成 */
    fun chatSt(r: Run): ChatHomeCore.St = when {
        r.items.any { it.st == St.WAITING } -> ChatHomeCore.St.WAITING
        !r.finished -> ChatHomeCore.St.RUNNING
        else -> ChatHomeCore.St.DONE
    }

    /** the ledger 备注 for a finished item: 「注册于 date · Mail.tm」 or 「注册中 date · reason」 */
    fun ledgerNote(date: String, ok: Boolean, why: String = ""): String = RegisterCore.note(date, ok, if (ok) "Mail.tm" else why)

    // ── page scripts (run in the WebView; values passed as JSON strings) ──

    /** reads the page: fields present, stop-and-ask signals, 「查收邮件」 / success words. Returns "R:" + JSON. */
    const val SCAN_JS = """(function(){try{
var vis=function(e){if(!e)return false;var r=e.getBoundingClientRect();if(r.width<4||r.height<4)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';};
var h=function(e){return ((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.autocomplete||'')).toLowerCase();};
var ins=[].slice.call(document.querySelectorAll('input,select')).filter(function(e){return vis(e)&&!e.disabled;});
var email=false,pass=0,code=false,codeEmpty=false,phone=false,phoneReq=false,card=false;
ins.forEach(function(e){var t=(e.type||'text').toLowerCase(),k=h(e);
 if(t==='email'||/e-?mail|邮箱/.test(k))email=true;
 else if(t==='password')pass++;
 else if(/cc-number|card.?num|cardnumber|cc-csc|cvc|cvv|卡号/.test(k))card=true;
 else if(t==='tel'||/phone|mobile|手机/.test(k)){phone=true;if(e.required||/required/.test(e.className||''))phoneReq=true;}
 else if((e.autocomplete||'').indexOf('one-time-code')>=0||/otp|verif.*code|confirm.*code|验证码|code/.test(k)){if(!/captcha|图形|image|invite|promo|coupon|邀请/.test(k)){code=true;if(!e.value)codeEmpty=true;}}});
var single=ins.filter(function(e){return e.maxLength===1&&!e.value;});if(single.length>=4){code=true;codeEmpty=true;}
var b=((document.body&&document.body.innerText)||'').slice(0,5000).toLowerCase();
if(/sms|短信|手机号验证|verify your phone|phone number/.test(b)&&phone&&!email)phoneReq=true;
var checkMail=/check your (e-?mail|inbox)|verify your e-?mail|confirm your e-?mail|we('ve| have)? sent|sent (you )?(an? )?(e-?mail|link|code)|查收|已发送|验证邮件|激活邮件/.test(b);
var success=/welcome|you('re| are) (all set|signed up|registered)|account (has been )?(created|verified|activated)|registration (complete|successful)|email (verified|confirmed)|注册成功|验证成功|欢迎/.test(b)&&!email&&pass===0;
var btn=[].slice.call(document.querySelectorAll('button,input[type=submit],[role=button]')).filter(vis);
var err='';var ee=document.querySelector('[role=alert],.error,.alert-danger,.form-error');if(ee&&vis(ee))err=(ee.innerText||'').trim();
return 'R:'+JSON.stringify({email:email,pass:pass,code:code,codeEmpty:codeEmpty,phone:phone,phoneReq:phoneReq,card:card,submit:btn.length>0,checkMail:checkMail,success:success,err:err,url:location.href});
}catch(x){return 'R:{}';}})()"""

    /**
     * fills the sign-up form (e-mail, every password box, a user name, the terms box) and presses the submit button.
     * Returns "R:filled:<n>:<clicked>" / "R:nofield".
     */
    fun fillJs(email: String, password: String, user: String): String {
        val e = JSONObject.quote(email); val p = JSONObject.quote(password); val u = JSONObject.quote(user)
        return """(function(){try{
var EM=$e,PW=$p,US=$u;
var vis=function(e){var r=e.getBoundingClientRect();if(r.width<4||r.height<4)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';};
var setv=function(e,v){e.focus();var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(d&&d.set)d.set.call(e,v);else e.value=v;
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new Event('blur',{bubbles:true}));};
var h=function(e){return ((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.autocomplete||'')).toLowerCase();};
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&!e.readOnly;});
var n=0,form=null;
ins.forEach(function(e){var t=(e.type||'text').toLowerCase(),k=h(e);
 if(t==='email'||/e-?mail|邮箱/.test(k)){setv(e,EM);n++;form=form||e.form;}
 else if(t==='password'){setv(e,PW);n++;form=form||e.form;}
 else if((t==='text'||t==='')&&/user.?name|login|nick|handle|昵称|用户名|(^|\s)name(\s|$)|full.?name|display/.test(k)&&!/code|otp|captcha|invite|promo/.test(k)){setv(e,US);n++;}
 else if(t==='checkbox'&&!e.checked){var lb=(e.closest('label')||document.querySelector('label[for="'+e.id+'"]')||{}).innerText||'';
  if(/agree|terms|accept|policy|同意|条款|协议/.test((lb+' '+k).toLowerCase())){e.click();}}});
if(n===0)return 'R:nofield';
var root=form||document;
var btns=[].slice.call(root.querySelectorAll('button,input[type=submit],[role=button]')).filter(function(b){return vis(b)&&!b.disabled;});
var words=/sign ?up|create|register|continue|next|submit|get started|join|注册|创建|继续|下一步|提交/i;
var bad=/google|github|apple|microsoft|facebook|sso|log ?in|sign ?in|登录/i;
var b=btns.find(function(x){var t=(x.innerText||x.value||'');return words.test(t)&&!bad.test(t);})||btns.find(function(x){return (x.type||'')==='submit'&&!bad.test(x.innerText||x.value||'');});
var clicked=false;
if(b){b.click();clicked=true;}else if(form&&form.requestSubmit){form.requestSubmit();clicked=true;}
return 'R:filled:'+n+':'+clicked;
}catch(x){return 'R:nofield';}})()"""
    }

    /** presses the button next to a filled code box (verify / continue / confirm); "R:clicked" / "R:none" */
    const val SUBMIT_CODE_JS = """(function(){try{
var vis=function(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;};
var btns=[].slice.call(document.querySelectorAll('button,input[type=submit],[role=button]')).filter(function(b){return vis(b)&&!b.disabled;});
var w=/verify|confirm|continue|submit|next|done|验证|确认|继续|提交|下一步|完成/i;
var b=btns.find(function(x){return w.test(x.innerText||x.value||'')&&!/resend|重新发送|重发/i.test(x.innerText||x.value||'');});
if(b){b.click();return 'R:clicked';}
var f=document.querySelector('form');if(f&&f.requestSubmit){f.requestSubmit();return 'R:clicked';}return 'R:none';
}catch(x){return 'R:none';}})()"""

    /** a user name for sites that want one: the e-mail's local part, letters + digits, 6..20 chars */
    fun userFor(email: String): String {
        val u = email.substringBefore('@').lowercase().filter { it.isLetterOrDigit() }
        return (if (u.firstOrNull()?.isLetter() == true) u else "m$u").take(20).padEnd(6, '7')
    }

    // ── run JSON (kept across a restart: an unfinished item becomes 未完成) ──

    fun toJson(r: Run): String = JSONObject().put("id", r.id).put("at", r.startedAt).put("end", r.endedAt).put("items", JSONArray().apply {
        r.items.forEach { i -> put(JSONObject().put("n", i.p.name).put("u", i.p.url).put("c", i.p.custom).put("st", i.st.name).put("l", i.line).put("e", i.email)) }
    }).toString()

    fun fromJson(s: String?): Run? = runCatching {
        val o = JSONObject(s ?: return null)
        val a = o.getJSONArray("items")
        Run(o.getString("id"), (0 until a.length()).map { k ->
            val i = a.getJSONObject(k)
            val st = St.valueOf(i.optString("st", "QUEUED"))
            val cut = st == St.RUNNING || st == St.WAITING || st == St.QUEUED
            Item(Platform(i.getString("n"), i.getString("u"), i.optBoolean("c")), if (cut) St.PENDING else st,
                if (cut) "Mines 重启，这个没做完" else i.optString("l"), i.optString("e"))
        }, o.optLong("at"), o.optLong("end"))
    }.getOrNull()
}

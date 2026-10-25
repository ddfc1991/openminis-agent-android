package com.openminis.app.plus

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * [Mines-38.7] Helps on any sign-in page inside Mines (user 2026-10-15 「要提高它对手机权限的使用」):
 *  - the phone number is typed in for the user when the page has an empty phone field (READ_PHONE_NUMBERS granted);
 *  - an SMS / mail / notification code ([HumanVerify], [SmsCodes]) is typed into the code field the moment it arrives,
 *    then 「已自动填好验证码」 is shown and read aloud (TalkBack reads toasts);
 *  - in 一键关联 ([auto] = true) it also presses 「获取验证码」 after the number is in, ticks the agreement box the page
 *    requires, and presses 「登录」 once the code is in. Scanning a QR code or a robot check is never automated: the
 *    caller shows one plain step for the user instead ([needsUser]).
 */
object LoginAssist {
    fun phoneNumber(c: Context): String? {
        val ok = listOf(Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE).any {
            ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!ok) return null
        val raw = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val sm = c.getSystemService(android.telephony.SubscriptionManager::class.java)
                @Suppress("MissingPermission")
                val subs = sm?.activeSubscriptionInfoList.orEmpty()
                @Suppress("MissingPermission")
                subs.firstNotNullOfOrNull { s -> runCatching { sm?.getPhoneNumber(s.subscriptionId) }.getOrNull()?.takeIf { it.isNotBlank() } }
            } else null
        }.getOrNull() ?: runCatching {
            @Suppress("MissingPermission", "DEPRECATION", "HardwareIds")
            c.getSystemService(android.telephony.TelephonyManager::class.java)?.line1Number
        }.getOrNull()
        val d = raw?.filter { it.isDigit() } ?: return null
        val n = if (d.startsWith("86") && d.length == 13) d.drop(2) else d
        return n.takeIf { it.length in 7..15 }
    }

    /** JS: fills an empty phone field; [click] presses 「获取验证码」 and ticks the agreement. Returns filled/none/clicked. */
    fun phoneJs(number: String, click: Boolean) = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!='hidden';}
function setv(e,v){var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&!e.readOnly;});
var ph=ins.find(function(e){var h=((e.type||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();return e.type=='tel'||/手机|phone|mobile|tel/.test(h);});
if(!ph)return 'none';var out='none';
if(!ph.value){ph.focus();setv(ph,${JSONObject.quote(number)});out='filled';}
if(${if (click) "true" else "false"}){
 var boxes=[].slice.call(document.querySelectorAll('input[type=checkbox]')).filter(function(e){return !e.checked;});
 boxes.forEach(function(b){var t=(b.parentElement&&b.parentElement.innerText)||'';if(/同意|协议|agree|terms|隐私/.test(t)){b.click();}});
 var bs=[].slice.call(document.querySelectorAll('button,a,[role=button],span,div')).filter(function(e){var t=(e.innerText||'').trim();return vis(e)&&t.length<10&&/^(获取验证码|发送验证码|获取短信验证码|Send code|Get code)$/.test(t);});
 if(bs.length&&!window.__minesAskedCode){bs[bs.length-1].click();window.__minesAskedCode=1;out='clicked';}
}
return out;}catch(e){return 'none';}})()"""

    /** JS: presses the page's 登录 / 下一步 button (一键关联, after the code is in). */
    const val SUBMIT_JS = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;}
var bs=[].slice.call(document.querySelectorAll('button,[role=button],input[type=submit]')).filter(function(e){var t=(e.innerText||e.value||'').trim();return vis(e)&&!e.disabled&&/^(登录|登 录|立即登录|下一步|确定|Log in|Sign in|Continue|Verify)$/i.test(t);});
if(!bs.length)return 'none';bs[bs.length-1].click();return 'clicked';}catch(e){return 'none';}})()"""

    /** Page text that means the user has to act (QR code / robot check), in plain words; null when nothing to do. */
    const val USER_STEP_JS = """(function(){var t=((document.body&&document.body.innerText)||'').slice(0,4000);
if(/扫码登录|扫一扫|用.{0,6}App扫码|二维码/.test(t))return '扫码';
if(/滑块|拖动|人机验证|安全验证|Human Verification|确认您是人类|Verify you are human|Just a moment/.test(t))return '验证';return '';})()"""

    /** [Mines-38.9.5 i] what the visible sign-in form wants: {"user":"phone|email|user|","pw":bool,"code":bool,"pwEmpty":bool}. */
    const val FIELDS_JS = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!='hidden';}
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&!e.readOnly&&e.type!='hidden'&&e.type!='checkbox'&&e.type!='radio';});
function h(e){return ((e.type||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.autocomplete||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();}
var pw=ins.filter(function(e){return e.type=='password';});
var code=ins.find(function(e){return /one-time-code|otp|验证码|code|captcha|短信/.test(h(e))&&e.type!='password';});
var us=ins.find(function(e){return e!==code&&e.type!='password'&&/user|email|login|account|phone|mobile|tel|账号|邮箱|手机|用户名/.test(h(e));});
var k='';if(us){var t=h(us);k=(us.type=='tel'||/手机|phone|mobile|tel/.test(t))?'phone':(us.type=='email'||/mail|邮箱/.test(t))?'email':'user';}
return JSON.stringify({user:k,pw:pw.length>0,pwEmpty:pw.length>0&&!pw[0].value,code:!!code,codeEmpty:!!code&&!code.value});}catch(e){return '{}';}})()"""

    /** Page text that says the password was wrong. */
    val PW_WRONG = Regex("(?i)(密码错误|密码不正确|密码有误|账号或密码|用户名或密码|incorrect password|wrong password|password is incorrect|invalid password|invalid credentials|incorrect (?:email|username) or password)")

    private suspend fun eval(wv: WebView, js: String): String = suspendCancellableCoroutine { c ->
        runCatching { wv.evaluateJavascript(js) { r -> if (c.isActive) c.resume(runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()) } }
            .onFailure { if (c.isActive) c.resume("") }
    }

    // ---------------- [Mines-38.9.1] every login method, not only the phone ----------------

    /** JS: what the page offers — tabs, buttons (incl. icon-only provider buttons by aria-label / title / alt), fields. */
    const val OFFERS_JS = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();if(r.width<6||r.height<6)return false;var s=getComputedStyle(e);return s.visibility!='hidden'&&s.display!='none'&&s.opacity!='0';}
function lab(e){var t=(e.innerText||'').trim().replace(/\s+/g,' ');if(!t){t=(e.getAttribute('aria-label')||e.getAttribute('title')||'').trim();if(!t){var i=e.querySelector&&e.querySelector('img[alt],svg[aria-label]');if(i)t=(i.getAttribute('alt')||i.getAttribute('aria-label')||'').trim();}}return t;}
var tabs=[],buttons=[],seen={};
[].slice.call(document.querySelectorAll('[role=tab],.tab,.tabs *,[class*=tab-item],[class*=Tab] > *,[class*=switch] span')).forEach(function(e){if(!vis(e))return;var t=lab(e);if(t&&t.length<=14&&!seen['t'+t]){seen['t'+t]=1;tabs.push(t);}});
[].slice.call(document.querySelectorAll('button,a,[role=button],[role=link],li,span,div,img[alt]')).forEach(function(e){if(buttons.length>80||!vis(e))return;var t=lab(e);if(!t||t.length>36)return;
 if(e.children.length>3)return;if(seen['b'+t])return;seen['b'+t]=1;buttons.push(t);});
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&e.type!='hidden'&&e.type!='checkbox'&&e.type!='radio';});
function h(e){return((e.type||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.autocomplete||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();}
var o={tabs:tabs,buttons:buttons,tel:false,email:false,user:false,userHint:'',pw:false,code:false,sendCode:false,qr:false,passkey:false};
ins.forEach(function(e){var x=h(e);
 if(e.type=='password')o.pw=true;
 else if(e.type=='email'||/e-?mail|邮箱/.test(x)&&!/手机|phone|tel/.test(x))o.email=true;
 else if(e.type=='tel'||/^(?!.*(mail|邮箱)).*(手机|phone|mobile|\btel\b)/.test(x))o.tel=true;
 else if(/验证码|code|otp|动态码/.test(x))o.code=true;
 else if(/user|login|account|账号|帐号|用户名|identifier|username|text/.test(x)){o.user=true;o.userHint+=' '+(e.placeholder||e.getAttribute('aria-label')||e.name||'');}});
o.sendCode=buttons.some(function(t){return /^(获取验证码|发送验证码|获取短信验证码|获取邮箱验证码|Send code|Get code|Send verification code)$/i.test(t);});
var body=((document.body&&document.body.innerText)||'').slice(0,3000);
o.qr=/扫码登录|扫一扫|二维码登录/.test(body);o.passkey=/通行密钥|passkey/i.test(body);
return JSON.stringify(o);}catch(e){return '{}';}})()"""

    /** JS: presses the visible element whose text (or aria-label / title / alt) is exactly [text]. */
    fun clickJs(text: String) = """(function(){try{var w=${JSONObject.quote(text)};
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!='hidden';}
function lab(e){var t=(e.innerText||'').trim().replace(/\s+/g,' ');if(!t){t=(e.getAttribute('aria-label')||e.getAttribute('title')||'').trim();if(!t){var i=e.querySelector&&e.querySelector('img[alt],svg[aria-label]');if(i)t=(i.getAttribute('alt')||i.getAttribute('aria-label')||'').trim();}}return t;}
var c=[].slice.call(document.querySelectorAll('[role=tab],button,a,[role=button],li,span,div,img[alt]')).filter(function(e){return vis(e)&&lab(e)==w;});
if(!c.length)return 'none';c.sort(function(a,b){return a.querySelectorAll('*').length-b.querySelectorAll('*').length;});
var e=c[0];var b=e.closest('button,a,[role=button],[role=tab]')||e;b.click();return 'clicked';}catch(e){return 'none';}})()"""

    /** JS: types [email] into the visible e-mail / account field; [click] then presses 获取验证码 / 下一步. */
    fun emailJs(email: String, click: Boolean) = """(function(){try{
function vis(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!='hidden';}
function setv(e,v){var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&!e.readOnly&&e.type!='password'&&e.type!='hidden'&&e.type!='checkbox';});
function h(e){return((e.type||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.autocomplete||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();}
var f=ins.find(function(e){return e.type=='email'||/e-?mail|邮箱/.test(h(e));})||ins.find(function(e){var x=h(e);return e.type!='tel'&&/user|login|account|账号|帐号|identifier|username/.test(x);});
if(!f)return 'none';var out='none';
if(f.value!=${JSONObject.quote(email)}){f.focus();setv(f,${JSONObject.quote(email)});out='filled';}
if(${if (click) "true" else "false"}&&!window.__minesAskedMail){
 var boxes=[].slice.call(document.querySelectorAll('input[type=checkbox]')).filter(function(e){return !e.checked;});
 boxes.forEach(function(b){var t=(b.parentElement&&b.parentElement.innerText)||'';if(/同意|协议|agree|terms|隐私/.test(t)){b.click();}});
 var bs=[].slice.call(document.querySelectorAll('button,a,[role=button],span,div,input[type=submit]')).filter(function(e){var t=(e.innerText||e.value||'').trim();return vis(e)&&t.length<16&&/^(获取验证码|发送验证码|获取邮箱验证码|Send code|Get code|Send verification code|下一步|继续|Next|Continue|Continue with email|使用邮箱继续)$/i.test(t);});
 if(bs.length){bs[bs.length-1].click();window.__minesAskedMail=1;out='clicked';}
}
return out;}catch(e){return 'none';}})()"""

    /** JS: on Google's account chooser / consent page, picks [email] (or the first signed-in account) and continues. */
    fun googlePickJs(email: String) = """(function(){try{var m=${JSONObject.quote(email.lowercase())};
var a=document.querySelector('[data-identifier="'+m+'"],[data-email="'+m+'"]');
if(!a&&m){a=[].slice.call(document.querySelectorAll('div,li,span')).find(function(e){return (e.innerText||'').trim().toLowerCase()==m;});}
if(!a)a=document.querySelector('[data-identifier],[data-email]');
if(a){(a.closest('[role=link],li,[jsaction]')||a).click();return 'picked';}
var b=[].slice.call(document.querySelectorAll('button,[role=button]')).find(function(e){return /^(继续|Continue|允许|Allow)$/i.test((e.innerText||'').trim());});
if(b){b.click();return 'continue';}return 'none';}catch(e){return 'none';}})()"""

    /** JS: a short error line on the page (wrong format / not supported / not registered) or ''. */
    const val ERROR_JS = """(function(){try{var t=[].slice.call(document.querySelectorAll('[role=alert],.error,[class*=error],[class*=Error],[class*=toast],[class*=Toast],[class*=message],[class*=tip]')).map(function(e){return (e.innerText||'').trim();}).filter(function(s){return s&&s.length<80;}).join(' | ');
var b=((document.body&&document.body.innerText)||'');if(/disallowed_useragent|此浏览器或应用可能不安全|This browser or app may not be secure|Couldn.t sign you in/i.test(b))t+=' 不支持 webview';return t.slice(0,300);}catch(e){return '';}})()"""

    /** Set when the sign-in page refused to go to a provider (Google / Apple block WebViews): the plan falls through at once. */
    @Volatile var providerBlockedAt = 0L

    /** The plan being tried on each site root right now (read by AutoAccounts / 一键关联 when the sign-in finishes). */
    private val current = java.util.concurrent.ConcurrentHashMap<String, IdentityCore.Plan>()
    fun planFor(host: String): IdentityCore.Plan? = current[Keychain.root(host)]

    /** Pure order of attempts for a page JSON (exposed for the self-test). */
    fun plansFor(context: Context, host: String, offersJson: String): List<IdentityCore.Plan> {
        IdentityStore.init(context)
        val offers = IdentityCore.parseOffers(offersJson)
        return IdentityCore.choose(offers, IdentityStore.recFor(context, host), IdentityStore.current(context)) { v ->
            runCatching { if (v.isBlank()) SavedLogins.forUrl(context, "https://$host/") != null else SavedLogins.find(context, host, v) != null }.getOrDefault(false)
        }.let { runCatching { SignIn.reorder(host, it) }.getOrDefault(it) }   // [38.9.15] the site's linked sign-in group first, failed ways dropped
    }

    /**
     * Runs while a sign-in page is open (call from a LaunchedEffect on the main thread). [onStatus] gets plain lines for
     * the user; [needsUser] gets 「扫码」/「验证」 when only the user can do the next step.
     *
     * [Mines-38.9.1] root cause of 「只会手机号，不会邮箱登录」: this loop only ever knew ONE identity — the SIM's number
     * ([phoneNumber], and nothing at all without the phone permission) — and only looked for a phone field; it never
     * read the user's mailboxes / Google / GitHub sign-ins, never pressed a 邮箱登录 tab and never moved on when the
     * phone way failed. Now it reads the page's offers ([OFFERS_JS]), orders the attempts with [IdentityCore.choose]
     * (recorded method → signed-in provider → e-mail → phone) and falls through to the next one on an error line or a
     * time-out. Mail codes come from the user's mailboxes ([HumanVerify] mail watch = MailSources / MailInbox accounts).
     */
    suspend fun run(context: Context, web: () -> WebView?, auto: Boolean, isDone: () -> Boolean,
                    onStatus: (String) -> Unit, needsUser: (String) -> Unit = {}) {
        val hv = HumanVerify
        hv.init(context)
        runCatching { IdentityStore.init(context); IdentityStore.sync(context) }
        var codeFilledAt = 0L
        var submitted = false
        var pwDone = false
        var plans: List<IdentityCore.Plan>? = null
        var planRoot = ""
        var pi = 0
        var stage = 0
        var planAt = 0L
        var firstAt = 0L
        var challengeAt = 0L
        var challengeAsked = false
        // [Mines-38.9.5 i] remembered sign-ins + the secure login card
        var restoredRoot = ""
        var cardRoot = ""
        var cardAccount = ""
        var refusedAsks = 0
        var codeWaitAt = 0L
        var codeCardAt = 0L
        while (!isDone()) {
            delay(2000)
            val wv = web() ?: continue
            val url = wv.url.orEmpty()
            val host = Keychain.hostOf(url)
            val root = Keychain.root(host)
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            // [Mines-38.7] a login imported from the Edge / Chrome password export: typed in (and submitted in 一键关联)
            if (!pwDone) SavedLogins.forUrl(context, url)?.let { e ->
                when (eval(wv, SavedLogins.fillJs(e, auto))) {
                    "filled" -> { pwDone = true; onStatus(if (auto) "已填好保存的账号密码，正在登录…" else "已填好保存的账号密码，点页面上的「登录」") }
                    "user" -> onStatus("已填好账号，正在进入下一步…")
                }
            }
            // provider pages (Google account chooser / consent) while a 「用 X 登录」 plan runs
            val cur = plans?.getOrNull(pi)
            if (cur?.method == IdentityCore.Method.OAUTH && root != planRoot && (host.contains("accounts.google.") || host.endsWith("google.com"))) {
                if (eval(wv, googlePickJs(cur.ident?.value.orEmpty())) != "none") onStatus("在 Google 里选了 ${cur.ident?.value ?: "你的账号"}…")
            }
            // (re)plan when a new site's login page shows up
            if (root.isNotBlank() && !host.contains("accounts.google.")) hv.expectSite = AccountsCore.accountSite(host)  // [Mines-38.9.5 i]
            if (root.isNotBlank() && root != planRoot && !(cur?.method == IdentityCore.Method.OAUTH && stage == 1)) {
                val json = eval(wv, OFFERS_JS)
                val p = runCatching { plansFor(context, host, json) }.getOrDefault(emptyList())
                if (p.isNotEmpty() || System.currentTimeMillis() - firstAt > 8000) {
                    plans = p; planRoot = root; pi = 0; stage = 0
                    if (p.isNotEmpty()) onStatus("这个页面能用：" + p.take(3).joinToString(" / ") { it.method.label.ifBlank { it.text } } + if (p.size > 3) " …" else "")
                }
            }
            val plan = plans?.getOrNull(pi)
            if (plan != null && root == planRoot) {
                current[root] = plan
                if (stage == 0) {
                    // in a manual sign-in only fill what is on screen: never switch tabs or press provider buttons
                    if (auto && plan.click.isNotBlank()) { eval(wv, clickJs(plan.click)); delay(1200) }
                    when (plan.method) {
                        IdentityCore.Method.OAUTH -> if (auto) onStatus("正在${plan.text}…") else stage = 9
                        IdentityCore.Method.EMAIL_CODE -> {
                            val r = eval(wv, emailJs(plan.ident?.value.orEmpty(), auto))
                            if (r != "none") { hv.armOtp(); onStatus(if (r == "clicked") "已填好邮箱 ${plan.ident?.value}，正在要验证码（从你的邮箱读）…" else "已填好邮箱 ${plan.ident?.value}") }
                            else if (!auto) stage = 9
                        }
                        IdentityCore.Method.EMAIL_PW, IdentityCore.Method.PHONE_PW, IdentityCore.Method.USER_PW -> {
                            val e = if (plan.ident != null) SavedLogins.find(context, host, plan.ident.value) else SavedLogins.forUrl(context, url)
                            if (e != null && eval(wv, SavedLogins.fillJs(e, auto)) != "none") { pwDone = true; onStatus("已填好保存的账号密码" + if (auto) "，正在登录…" else "") }
                        }
                        IdentityCore.Method.PHONE_SMS -> {
                            val num = plan.ident?.value?.let { KeychainCore.digits(it).let { d -> if (d.length == 13 && d.startsWith("86")) d.drop(2) else d } }.orEmpty()
                            if (num.isNotBlank()) {
                                val r = eval(wv, phoneJs(num, auto))
                                if (r == "filled" || r == "clicked") { hv.armOtp(); onStatus(if (r == "clicked") "已填好手机号，正在要验证码…" else "已填好手机号") }
                            }
                        }
                        else -> {}
                    }
                    if (stage == 0) { stage = 1; planAt = System.currentTimeMillis() }
                } else if (stage == 1 && auto) {
                    val err = eval(wv, ERROR_JS)
                    val limit = when (plan.method) { IdentityCore.Method.OAUTH -> 70_000L; IdentityCore.Method.EMAIL_CODE, IdentityCore.Method.PHONE_SMS -> 80_000L; else -> 30_000L }
                    val refused = (err.isNotBlank() && IdentityCore.REFUSED.containsMatchIn(err)) ||
                        (plan.method == IdentityCore.Method.OAUTH && providerBlockedAt > planAt)
                    if ((refused || System.currentTimeMillis() - planAt > limit) && codeFilledAt < planAt) {
                        pi++; stage = 0; submitted = false
                        val nx = plans?.getOrNull(pi)
                        onStatus(if (nx != null) (if (refused) "这个方式不行（${err.take(30)}），" else "这个方式没成，") + "换成：${nx.text}" else "这个页面的登录方式都试过了")
                        if (nx == null) current.remove(root)
                        runCatching { wv.evaluateJavascript("window.__minesAskedCode=0;window.__minesAskedMail=0;", null) }
                    }
                }
            }
            // [Mines-38.9.5 i] silent reuse first: put back the encrypted session snapshot when the WebView lost it
            if (auto && root.isNotBlank() && root != restoredRoot) {
                restoredRoot = root
                if (LoginMemory.restoreSession(context, host)) { onStatus("用上次的登录…"); runCatching { wv.reload() }; continue }
            }
            // [Mines-38.9.5 i] user 22:42: anything Mines does not have → the secure login card, values straight into the page
            if (auto && root.isNotBlank() && !(plan?.method == IdentityCore.Method.OAUTH && stage == 1)) {
                val fj = runCatching { JSONObject(eval(wv, FIELDS_JS)) }.getOrNull()
                if (fj != null && fj.length() > 0) {
                    val page = LoginMemoryCore.Page(fj.optString("user"), fj.optBoolean("pw") && fj.optBoolean("pwEmpty", true) || (pwDone && fj.optBoolean("pw")), fj.optBoolean("code") && fj.optBoolean("codeEmpty"))
                    val saved = SavedLogins.forUrl(context, url)
                    val memo = LoginMemory.memoFor(context, host)
                    val refused = pwDone && refusedAsks < 2 && fj.optBoolean("pw") && PW_WRONG.containsMatchIn(eval(wv, ERROR_JS))
                    if (page.code) { if (codeWaitAt == 0L) { codeWaitAt = System.currentTimeMillis(); hv.armOtp() } } else codeWaitAt = 0L
                    val waited = if (codeWaitAt == 0L || hv.hasFreshCode()) -1L else System.currentTimeMillis() - codeWaitAt
                    val haveAcct = saved != null || cardAccount.isNotBlank() || plan?.ident != null
                    val havePw = saved != null || pwDone
                    val ask = LoginMemoryCore.cardFields(page, haveAcct, havePw, waited, refused, AutoAccounts.siteOf(host))
                    val firstCard = ask != null && (ask.refused || ask.fields == listOf(LoginMemoryCore.Field.CODE) || cardRoot != root)
                    if (ask != null && firstCard && (ask.fields != listOf(LoginMemoryCore.Field.CODE) || System.currentTimeMillis() - codeCardAt > 60_000L)) {
                        if (ask.refused) refusedAsks++
                        if (ask.fields == listOf(LoginMemoryCore.Field.CODE)) codeCardAt = System.currentTimeMillis() else cardRoot = root
                        onStatus(if (ask.refused) "密码不对，等你重新输入" else "等你输入登录信息")
                        val a = LoginCard.ask(LoginCard.Req(host, AutoAccounts.siteOf(host), ask,
                            saved?.user ?: memo?.account ?: plan?.ident?.value.orEmpty(), LoginMemoryCore.lastLine(memo)))
                        if (a == null) { onStatus("已取消登录"); continue }
                        if (a.code.isNotBlank()) {
                            if (eval(wv, hv.fillJs(a.code, null)) == "ok") { codeFilledAt = System.currentTimeMillis(); delay(600); if (eval(wv, SUBMIT_JS) == "clicked") submitted = true }
                        }
                        if (a.account.isNotBlank() || a.password.isNotBlank()) {
                            cardAccount = a.account
                            val e = SavedLogins.Entry(AutoAccounts.siteOf(host), "https://$host/", a.account, a.password)
                            if (a.save && a.password.isNotBlank()) runCatching { SavedLogins.put(context, e) }
                            val r = eval(wv, SavedLogins.fillJs(e, true))
                            if (r != "none") { pwDone = true; onStatus("已填好，正在登录…") }
                            else if (page.user == "phone" || page.user == "email") {
                                val rr = eval(wv, if (page.user == "phone") phoneJs(a.account, true) else emailJs(a.account, true))
                                if (rr != "none") { hv.armOtp(); onStatus("已填好，正在要验证码…") }
                            }
                        }
                        continue
                    }
                }
            }
            val step = eval(wv, USER_STEP_JS)
            if (step.isNotBlank() && plan?.method != IdentityCore.Method.OAUTH) needsUser(step)
            // [Mines-38.9.5 i] user 18:57 / 19:14: challenges are never solved or bypassed. Cloudflare Turnstile usually passes
            // by itself in a real WebView (real UA, JS / cookies / DOM storage on) → wait quietly up to 15 s; 易盾 / 滑块 / 点字 /
            // reCAPTCHA / hCaptcha need a person → say so once (the page is on screen, or the hidden re-login stops and the
            // account row offers 「一键重新登录」); when it is gone the sign-in continues by itself.
            val ch = runCatching { hv.parseScan(eval(wv, hv.DETECT_JS)).kind }.getOrDefault("")
            val nowMs = System.currentTimeMillis()
            when (AccountsCore.challenge(ch)) {
                AccountsCore.Challenge.SILENT_WAIT -> {
                    if (challengeAt == 0L) { challengeAt = nowMs; onStatus("正在等 Cloudflare 自动验证（最多 15 秒）…") }
                    else if (nowMs - challengeAt > AccountsCore.TURNSTILE_WAIT_MS && !challengeAsked) {
                        challengeAsked = true; onStatus("Cloudflare 要你点一下验证，点完会自动继续"); needsUser("Cloudflare 验证要你点一下")
                    }
                    continue
                }
                AccountsCore.Challenge.ASK_USER -> {
                    if (!challengeAsked) { challengeAsked = true; onStatus("页面要人机验证（$ch），请你点一下，完成后自动继续"); needsUser("页面要人机验证（$ch）") }
                    continue
                }
                AccountsCore.Challenge.NONE -> if (challengeAt != 0L || challengeAsked) {
                    challengeAt = 0L; challengeAsked = false; onStatus("验证通过了，继续登录…")
                }
            }
            if (!hv.settings.value.otpAutofill) continue
            val scan = hv.parseScan(eval(wv, hv.DETECT_JS))
            if (scan.otpField && scan.otpEmpty && System.currentTimeMillis() - codeFilledAt > 20_000L) {
                hv.armOtp()
                val code = hv.takeCode()
                if (code != null) {
                    if (eval(wv, hv.fillJs(code, null)) == "ok") {
                        codeFilledAt = System.currentTimeMillis()
                        onStatus("已自动填好验证码")
                        runCatching { android.widget.Toast.makeText(context, "已自动填好验证码", android.widget.Toast.LENGTH_SHORT).show() }
                        wv.announceForAccessibility("已自动填好验证码")
                        if (auto && !submitted) { delay(800); if (eval(wv, SUBMIT_JS) == "clicked") { submitted = true; onStatus("已填好验证码，正在登录…") } }
                    } else hv.putBack(code)
                }
            }
        }
    }
}

package com.openminis.app.plus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Human verification for the agent's browser.
 *
 * 1. Challenges: after the agent navigates / clicks / types, the page is
 *    checked for Cloudflare (interstitial + Turnstile), hCaptcha, reCAPTCHA
 *    and slider captchas (Alibaba Baxia / NoCaptcha, Geetest, Tencent,
 *    NetEase). When one shows up the agent's call waits, the user gets a
 *    notification and an in-chat prompt 「需要你点一下验证」 that opens the
 *    browser on that tab with the controls handed over, and the call returns
 *    as soon as the challenge clears (cf_clearance set / challenge gone / URL
 *    changed). The agent never tries to solve it itself.
 * 2. One-time codes: when a page asks for an SMS / e-mail code, codes that
 *    arrive in notifications in the next 2 minutes (4–8 digits next to
 *    验证码 / code / OTP …) are filled into the code field. Codes stay in
 *    memory only: never logged, never written to disk, never shown to the
 *    model.
 * Cookies live in the WebView's persistent store and are flushed after every
 * cleared challenge, so a site stays signed in across restarts.
 */
object HumanVerify {
    private const val FILE = "mines_human_verify"
    private const val CHANNEL = "mines_verify"
    private const val NOTIF_ID = 47021
    /** Each wait inside one browser call stays under the tool's 75 s guard. */
    const val WAIT_SLICE_MS = 50_000L
    private const val OTP_WINDOW_MS = 120_000L

    data class Settings(val challenges: Boolean = true, val otpAutofill: Boolean = true)
    data class Pending(val url: String, val kind: String, val since: Long = System.currentTimeMillis()) {
        val host: String get() = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
    }

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appCtx: Context? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            appCtx = context.applicationContext
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            _settings.value = Settings(p.getBoolean("challenges", true), p.getBoolean("otp", true))
        }
    }

    fun update(context: Context, block: (Settings) -> Settings) {
        init(context)
        val n = block(_settings.value)
        _settings.value = n
        prefs?.edit()?.putBoolean("challenges", n.challenges)?.putBoolean("otp", n.otpAutofill)?.apply()
    }

    fun clearPending(context: Context?) {
        _pending.value = null
        context?.let { runCatching { androidx.core.app.NotificationManagerCompat.from(it).cancel(NOTIF_ID) } }
    }

    // ── challenge detection ────────────────────────────────────────────

    /** Returns a JSON string {kind, otp, otpEmpty}. kind "" = no challenge. */
    const val DETECT_JS = """(function(){
try{
var vis=function(e){if(!e)return false;var r=e.getBoundingClientRect();if(r.width<8||r.height<8)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';};
var q=function(s){try{return document.querySelectorAll(s);}catch(x){return [];}};
var any=function(s){var l=q(s);for(var i=0;i<l.length;i++){if(vis(l[i]))return true;}return false;};
var t=(document.title||'').toLowerCase();var b=((document.body&&document.body.innerText)||'').slice(0,4000).toLowerCase();
var kind='';
if(t.indexOf('just a moment')>=0||t.indexOf('attention required')>=0||t.indexOf('请稍候')>=0||any('#challenge-form,#cf-challenge-running,.cf-browser-verification,#challenge-stage,#cf-please-wait'))kind='Cloudflare';
if(!kind){var tr=q('input[name="cf-turnstile-response"]');var trEmpty=false;for(var i=0;i<tr.length;i++){if(!tr[i].value)trEmpty=true;}
 if((any('iframe[src*="challenges.cloudflare.com"]')||any('.cf-turnstile'))&&(tr.length===0||trEmpty))kind='Cloudflare Turnstile';}
if(!kind){var hr=q('textarea[name="h-captcha-response"]');var hEmpty=hr.length===0;for(var i=0;i<hr.length;i++){if(!hr[i].value)hEmpty=true;}
 if(any('iframe[src*="hcaptcha.com"]')&&hEmpty)kind='hCaptcha';}
if(!kind){var rr=q('textarea[name="g-recaptcha-response"]');var rEmpty=rr.length===0;for(var i=0;i<rr.length;i++){if(!rr[i].value)rEmpty=true;}
 var fr=q('iframe[src*="recaptcha/api2/anchor"],iframe[src*="recaptcha/enterprise/anchor"],iframe[src*="recaptcha/api2/bframe"]');
 for(var i=0;i<fr.length;i++){var src=fr[i].getAttribute('src')||'';if(src.indexOf('size=invisible')<0&&vis(fr[i])&&rEmpty){kind='reCAPTCHA';break;}}}
if(!kind&&any('#nc_1_n1z,.nc_iconfont.btn_slide,#nc_1_wrapper,.baxia-dialog,#baxia-dialog-content,iframe[src*="punish"],iframe[src*="_____tmd_____"]'))kind='阿里滑块验证';
if(!kind&&any('.geetest_slider_button,.geetest_panel_box,.geetest_holder,.geetest_box'))kind='极验验证';
if(!kind&&any('#tcaptcha_iframe,#tcaptcha_iframe_dy,iframe[src*="captcha.qq.com"],iframe[src*="turing.captcha"]'))kind='腾讯验证码';
if(!kind&&any('.yidun_slider,.yidun_panel,.yidun'))kind='网易易盾验证';
if(!kind&&(b.indexOf('verify you are human')>=0||b.indexOf('确认您是真人')>=0||b.indexOf('请按住滑块')>=0||b.indexOf('拖动滑块')>=0||b.indexOf('请完成安全验证')>=0)&&b.length<1500)kind='人机验证';
var otp=null;var ins=q('input');var cands=[];
for(var i=0;i<ins.length;i++){var e=ins[i];if(!vis(e)||e.disabled||e.readOnly)continue;var ty=(e.type||'text').toLowerCase();if(['text','tel','number','password',''].indexOf(ty)<0)continue;
 var h=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.className||'')).toLowerCase();
 var ml=e.maxLength;
 if((e.autocomplete||'').indexOf('one-time-code')>=0||/otp|smscode|sms_code|verif.*code|checkcode|authcode|验证码|校验码|动态码|code/.test(h)){if(!/captcha|图形|image/.test(h))cands.push(e);}
 else if(ml===1&&(ty==='tel'||ty==='number'||ty==='text')){cands.push(e);}}
var single=cands.filter(function(e){return e.maxLength===1;});
var otpEmpty=cands.length>0&&cands.every(function(e){return !e.value;});
return JSON.stringify({kind:kind,otp:cands.length>0,split:single.length>=4,otpEmpty:otpEmpty});
}catch(x){return JSON.stringify({kind:'',otp:false,split:false,otpEmpty:false});}
})()"""

    data class Scan(val kind: String, val otpField: Boolean, val otpEmpty: Boolean)

    fun parseScan(raw: String?): Scan = runCatching {
        val o = JSONObject(raw ?: "{}")
        Scan(o.optString("kind"), o.optBoolean("otp"), o.optBoolean("otpEmpty"))
    }.getOrDefault(Scan("", false, false))

    private fun hasClearance(url: String): Boolean = runCatching {
        CookieManager.getInstance().getCookie(url)?.contains("cf_clearance=") == true
    }.getOrDefault(false)

    private fun notify(context: Context, p: Pending) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "需要你验证", NotificationManager.IMPORTANCE_HIGH))
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = launch?.let { PendingIntent.getActivity(context, NOTIF_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("需要你点一下验证")
                .setContentText("${p.host.ifBlank { "网页" }} 弹出了${p.kind}，Mines 在等你，点完会自动继续")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            nm.notify(NOTIF_ID, n)
            MinesCall.onNeedsAttention(context, "需要你验证", "${p.host.ifBlank { "网页" }} 弹出了${p.kind}，Mines 在等你")   // [Mines-38.9.12 b]
        }
    }

    /**
     * Called by the browser after a page-changing action. If a challenge is up,
     * waits (≤ [WAIT_SLICE_MS]) for the user to clear it.
     * @return null when there was nothing to do, otherwise a note for the agent.
     */
    suspend fun handle(
        context: Context?,
        scan: Scan,
        url: String,
        rescan: suspend () -> Scan,
        currentUrl: suspend () -> String,
    ): String? {
        if (context == null || !_settings.value.challenges) return null
        if (scan.kind.isBlank()) {
            if (_pending.value != null) clearPending(context)
            return null
        }
        // Shorter here: this wait follows the action itself inside one guarded call.
        return waitForHuman(context, scan.kind, url, rescan, currentUrl, maxWaitMs = 30_000L)
    }

    /**
     * [Mines-38.9.5 i] Cloudflare / Turnstile usually clears by itself in a real WebView: wait up to 15 s quietly
     * before bothering the user (never clicked or solved by Mines). Returns the agent note when it cleared, else null.
     */
    suspend fun trySilent(kind: String, url: String, rescan: suspend () -> Scan, currentUrl: suspend () -> String): String? {
        if (AccountsCore.challenge(kind) != AccountsCore.Challenge.SILENT_WAIT) return null
        val t0 = System.currentTimeMillis()
        val had = hasClearance(url)
        while (System.currentTimeMillis() - t0 < AccountsCore.TURNSTILE_WAIT_MS) {
            delay(1500)
            val now = runCatching { currentUrl() }.getOrDefault(url)
            if (now.substringBefore('#') != url.substringBefore('#') || (!had && hasClearance(now)) || runCatching { rescan().kind.isBlank() }.getOrDefault(false)) {
                delay(800); runCatching { CookieManager.getInstance().flush() }
                return "[人工验证] 页面出现$kind，已自动通过（没有打扰用户），现在可以继续操作（先截图看看当前页面）。"
            }
        }
        return null
    }

    suspend fun waitForHuman(
        context: Context,
        kind: String,
        url: String,
        rescan: suspend () -> Scan,
        currentUrl: suspend () -> String,
        maxWaitMs: Long = WAIT_SLICE_MS,
    ): String {
        if (_pending.value == null) trySilent(kind, url, rescan, currentUrl)?.let { return it }
        val prior = _pending.value
        if (prior == null || prior.url.substringBefore('#') != url.substringBefore('#')) {
            val p = Pending(url, kind)
            _pending.value = p
            notify(context, p)
        }
        val hadClearance = hasClearance(url)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            delay(1500)
            val now = runCatching { currentUrl() }.getOrDefault(url)
            val urlChanged = now.substringBefore('#') != url.substringBefore('#')
            val cleared = urlChanged || (!hadClearance && hasClearance(now)) || runCatching { rescan().kind.isBlank() }.getOrDefault(false)
            if (cleared) {
                delay(800) // let the site finish its redirect
                clearPending(context)
                runCatching { CookieManager.getInstance().flush() }
                return "[人工验证] 页面出现$kind，用户已完成验证，现在可以继续操作（先截图看看当前页面）。"
            }
        }
        return "[需要人工验证] 页面出现$kind，已通知用户点一下。不要自己去点或破解验证码；请调用 browser 的 wait_for_human 动作继续等待（每次最多约 50 秒），用户完成后会自动继续。"
    }

    // ── one-time codes ─────────────────────────────────────────────────

    @Volatile private var armedAt = 0L
    @Volatile private var code: String? = null
    @Volatile private var codeAt = 0L

    /**
     * [Mines-sources-33] A code may arrive a little before the page shows its code
     * field (the agent clicks 发送验证码, the mail lands, then the field appears), so
     * codes that came in up to [EARLY_MS] before arming still count.
     */
    private const val EARLY_MS = 90_000L
    private val watchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var watching = false
    /** Codes already handed out (memory only), so the same mail is not filled twice. */
    private val used = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())

    /** [Mines-38.9.5 i] registrable domain of the page asking for a code ("" = unknown → any code, the old behaviour) */
    @Volatile var expectSite: String = ""

    fun armedSince(): Long = armedAt

    /** A page is asking for a code (or the agent said it requested one). */
    fun armOtp() {
        if (System.currentTimeMillis() - armedAt > OTP_WINDOW_MS) {
            armedAt = System.currentTimeMillis()
            if (codeAt < armedAt - EARLY_MS) code = null
        }
        // [Mines-38.7] SMS: inbox (READ_SMS) for a code that just arrived; Google's one-message consent when no SMS permission
        appCtx?.let { c -> if (!hasFreshCode()) { runCatching { SmsCodes.scanInbox(c, armedAt - EARLY_MS) }; runCatching { SmsCodes.startConsent(c) } } }
        startMailWatch()
    }

    /**
     * [Mines-sources-33] While a page waits for a code, read every signed-in mailbox
     * (Gmail web session, Outlook, IMAP) every ~6 s for up to 2 minutes; the
     * notification listener keeps working in parallel.
     */
    private fun startMailWatch() {
        val ctx = appCtx ?: return
        if (watching || !_settings.value.otpAutofill) return
        if (!runCatching { MailSources.available(ctx) || AgentMailbox.boxes().isNotEmpty() }.getOrDefault(false)) return
        watching = true
        val since = armedAt - EARLY_MS
        watchScope.launch {
            try {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < OTP_WINDOW_MS && System.currentTimeMillis() - armedAt <= OTP_WINDOW_MS) {
                    if (hasFreshCode()) break
                    // [Mines-38.9.5 i] only switched-on sources, only mail from the site being signed into (when known)
                    runCatching { CodeSources.find(ctx, since, expectSite)?.code }.getOrNull()?.let { offer(it) }
                    if (hasFreshCode()) break
                    delay(6000)
                }
            } finally { watching = false }
        }
    }

    /** A code found in mail (or by a self-test). Ignored when it was handed out before. */
    fun offer(c: String) {
        val now = System.currentTimeMillis()
        synchronized(used) { used.entries.removeAll { now - it.value > 15 * 60_000L } }
        if (used.containsKey(c)) return
        code = c; codeAt = now
    }

    private val DIGITS = Regex("(?<![0-9])([0-9]{4,8})(?![0-9])")

    /** From the notification listener. Never logs the text or the code. */
    fun onNotification(context: Context, sbn: StatusBarNotification) {
        if (!_settings.value.otpAutofill) return
        val now = System.currentTimeMillis()
        // [Mines-sources-33] always keep the newest code (memory only): it is used only if a page asks within EARLY_MS
        if (sbn.packageName == context.packageName) return
        val ex = sbn.notification?.extras ?: return
        val text = listOfNotNull(
            ex.getCharSequence("android.title"), ex.getCharSequence("android.text"), ex.getCharSequence("android.bigText"),
        ).joinToString(" ")
        extractCode(text)?.let { if (!used.containsKey(it)) { code = it; codeAt = now } }
    }

    fun extractCode(text: String): String? = MailParse.extractCode(text)

    /** A code that arrived after (or just before) the page asked, not used yet. Consumes it. */
    fun takeCode(): String? {
        val c = code ?: return null
        if (codeAt < armedAt - EARLY_MS || System.currentTimeMillis() - codeAt > OTP_WINDOW_MS) return null
        code = null
        used[c] = System.currentTimeMillis()
        return c
    }

    /** Keep a code that could not be placed yet (still within its window). */
    fun putBack(c: String) { used.remove(c); code = c; codeAt = System.currentTimeMillis() }

    fun hasFreshCode(): Boolean = code != null && codeAt >= armedAt - EARLY_MS && System.currentTimeMillis() - codeAt <= OTP_WINDOW_MS

    /** Waits up to [timeoutMs] for a code; null on timeout. */
    suspend fun awaitCode(timeoutMs: Long): String? {
        armOtp()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            takeCode()?.let { return it }
            delay(700)
        }
        return null
    }

    /** JS that types [code] into the page's code field(s); returns "ok" / "nofield". */
    fun fillJs(code: String, selector: String?): String {
        val sel = JSONObject.quote(selector ?: "")
        val c = JSONObject.quote(code)
        return """(function(){try{
var code=$c,sel=$sel;
var setv=function(e,v){var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set){d.set.call(e,v);}else{e.value=v;}
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));};
var vis=function(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;};
if(sel){var t=document.querySelector(sel);if(!t)return 'nofield';t.focus();setv(t,code);return 'ok';}
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){return vis(e)&&!e.disabled&&!e.readOnly;});
var single=ins.filter(function(e){return e.maxLength===1;});
if(single.length>=code.length){for(var i=0;i<code.length;i++){single[i].focus();setv(single[i],code[i]);}return 'ok';}
var t=ins.find(function(e){var h=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();
 return (e.autocomplete||'').indexOf('one-time-code')>=0||(/otp|smscode|sms_code|verif.*code|checkcode|authcode|验证码|校验码|动态码|code/.test(h)&&!/captcha|图形|image/.test(h));});
if(!t)return 'nofield';t.focus();setv(t,code);return 'ok';
}catch(x){return 'nofield';}})()"""
    }
}

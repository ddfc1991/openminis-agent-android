package com.openminis.app.plus

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [Mines-cards-35.1] 支付卡 (user 2026-10-14): the user's own cards, so Mines can WRITE them into a payment form
 * when the user taps 「填卡」. Rules:
 *  - stored only on this phone, AES-GCM with a key in the Android Keystore (never in env vars, chats, logs);
 *  - opening the page or filling needs fingerprint / screen lock ([unlock], 5 minutes);
 *  - the number never goes to a model or into a conversation; the UI only shows the last four digits;
 *  - fill only, never submit / never click a pay or confirm button.
 */
object PayCards {
    data class Card(
        val id: String = java.util.UUID.randomUUID().toString(),
        val number: String,
        val expMonth: String,
        val expYear: String,
        val cvc: String = "",
        val name: String = "",
        val country: String = "",
        val line1: String = "",
        val city: String = "",
        val state: String = "",
        val postal: String = "",
        val label: String = "",
    ) {
        val last4: String get() = number.takeLast(4)
        val brand: String get() = brandOf(number)
        val masked: String get() = "$brand •••• $last4"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("number", number).put("expMonth", expMonth).put("expYear", expYear)
            .put("cvc", cvc).put("name", name).put("country", country).put("line1", line1).put("city", city).put("state", state)
            .put("postal", postal).put("label", label)
        companion object {
            fun from(o: JSONObject) = Card(o.optString("id"), o.optString("number"), o.optString("expMonth"), o.optString("expYear"),
                o.optString("cvc"), o.optString("name"), o.optString("country"), o.optString("line1"), o.optString("city"),
                o.optString("state"), o.optString("postal"), o.optString("label"))
        }
    }

    private const val PREFS = "mines_pay_cards"
    private const val ALIAS = "mines_pay_cards_v1"
    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    val cards: StateFlow<List<Card>> = _cards.asStateFlow()
    @Volatile private var loaded = false
    @Volatile var unlockedUntil = 0L; private set

    fun isUnlocked() = System.currentTimeMillis() < unlockedUntil
    fun markUnlocked() { unlockedUntil = System.currentTimeMillis() + 5 * 60_000L }
    fun lock() { unlockedUntil = 0L }

    // ---- storage ------------------------------------------------------------
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val (iv, data) = s.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun load(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("blob", null)
        _cards.value = if (raw == null) emptyList() else runCatching {
            val a = JSONArray(decrypt(raw)); (0 until a.length()).map { Card.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        loaded = true
    }

    private fun persist(context: Context) {
        val a = JSONArray(); _cards.value.forEach { a.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("blob", encrypt(a.toString())).apply()
    }

    fun upsert(context: Context, card: Card) {
        load(context)
        _cards.value = _cards.value.filterNot { it.id == card.id } + card
        persist(context)
    }

    fun delete(context: Context, id: String) {
        load(context); _cards.value = _cards.value.filterNot { it.id == id }; persist(context)
    }

    // ---- validation / parsing ----------------------------------------------
    fun digits(s: String) = s.filter { it.isDigit() }

    fun luhn(num: String): Boolean {
        val d = digits(num); if (d.length !in 12..19) return false
        var sum = 0; var alt = false
        for (i in d.indices.reversed()) { var n = d[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }

    fun brandOf(num: String): String {
        val d = digits(num)
        return when {
            d.startsWith("4") -> "Visa"
            Regex("^(5[1-5]|2[2-7])").containsMatchIn(d) -> "Mastercard"
            Regex("^3[47]").containsMatchIn(d) -> "Amex"
            d.startsWith("62") -> "银联"
            Regex("^35(2[89]|[3-8])").containsMatchIn(d) -> "JCB"
            Regex("^(6011|65|64[4-9])").containsMatchIn(d) -> "Discover"
            else -> "卡"
        }
    }

    data class Scan(val number: String?, val expMonth: String?, val expYear: String?, val name: String?, val cvc: String?)

    /** Card fields from on-device OCR text (ML Kit). Number must pass Luhn. */
    fun parseOcr(text: String): Scan {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val flat = text.replace('O', '0').replace('o', '0')
        var number: String? = null
        Regex("""(?<!\d)(\d[\d \-]{10,24}\d)(?!\d)""").findAll(flat).forEach { m ->
            val d = digits(m.value); if (number == null && d.length in 13..19 && luhn(d)) number = d
        }
        var mm: String? = null; var yy: String? = null
        Regex("""(?<!\d)(0[1-9]|1[0-2])\s*[/\-]\s*(\d{4}|\d{2})(?!\d)""").find(flat)?.let { m ->
            mm = m.groupValues[1]; yy = m.groupValues[2].takeLast(2)
        }
        val stop = Regex("(?i)valid|thru|until|expir|bank|visa|master|debit|credit|card|member|since|month|year|platinum|gold|classic|world|electronic|use|only|international|union|pay|signature|good|from")
        val name = lines.firstOrNull { l -> l.matches(Regex("^[A-Z][A-Z.'\\-]+( [A-Z][A-Z.'\\-]+){1,3}$")) && !stop.containsMatchIn(l) }
        val cvc = Regex("""(?i)(cvc|cvv|cvn|安全码|校验码)\D{0,6}(\d{3,4})(?!\d)""").find(text)?.groupValues?.get(2)
        return Scan(number, mm, yy, name, cvc)
    }

    /**
     * [Mines-cards-35.1] A card the user once put into Mines' 「变量」 (env vars, readable by the sandbox): offer
     * once to move it into 支付卡 and delete the variables. Returns the variable ids that look like a card.
     */
    fun cardVars(context: Context): List<Triple<String, String, String>> = runCatching {
        val repo = (context.applicationContext as com.openminis.app.MinisApp).envVarRepository
        repo.entries.value.mapNotNull { e ->
            val v = repo.getValue(e.key).orEmpty()
            val k = e.key.uppercase()
            val looksCard = luhn(v) && digits(v).length in 13..19 && v.count { it.isLetter() } == 0
            val related = Regex("CARD|CVC|CVV|EXP|VISA|MASTER|CC_").containsMatchIn(k)
            if (looksCard || (related && v.isNotBlank() && v.length <= 32)) Triple(e.id, e.key, v) else null
        }
    }.getOrDefault(emptyList())

    fun importFromVars(context: Context): Card? {
        val vars = cardVars(context)
        val num = vars.firstOrNull { luhn(it.third) }?.third?.let { digits(it) } ?: return null
        fun find(re: String) = vars.firstOrNull { Regex(re).containsMatchIn(it.second.uppercase()) && !luhn(it.third) }?.third.orEmpty()
        val exp = find("EXP")
        val m = Regex("""(0[1-9]|1[0-2])\D*(\d{2,4})""").find(exp)
        val card = Card(number = num, expMonth = m?.groupValues?.get(1).orEmpty(), expYear = m?.groupValues?.get(2)?.takeLast(2).orEmpty(),
            cvc = digits(find("CVC|CVV")).take(4), name = find("NAME|HOLDER"))
        upsert(context, card)
        val repo = (context.applicationContext as com.openminis.app.MinisApp).envVarRepository
        vars.forEach { runCatching { repo.delete(it.first) } }
        return card
    }

    // ---- unlock (fingerprint / face / screen lock) ---------------------------
    /** Asks for biometrics or the screen lock; [done] true when the user passed (or the phone has no lock at all). */
    fun unlock(context: Context, title: String, done: (Boolean, String?) -> Unit) {
        if (isUnlocked()) { done(true, null); return }
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) { markUnlocked(); done(true, "手机没有设置锁屏，建议设置锁屏密码保护你的卡"); return }
        if (Build.VERSION.SDK_INT < 29) { markUnlocked(); done(true, null); return }
        // [Mines-38.1] USE_BIOMETRIC is now in the manifest (build-35.1..38 threw 「Must have USE_BIOMETRIC permission」).
        // Strong biometrics OR the screen lock (PIN / pattern / password); when no fingerprint is enrolled the
        // prompt goes straight to the screen lock. Errors are shown in plain Chinese, never the raw exception.
        val auth = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val exec = context.mainExecutor
        fun build(withBio: Boolean): android.hardware.biometrics.BiometricPrompt {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(context).setTitle(title).setSubtitle("验证是你本人")
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(
                if (withBio) auth else android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            return b.build()
        }
        val cb = object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { markUnlocked(); done(true, null) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done(false, plainAuthError(errorCode)) }
        }
        val bioOk = runCatching {
            if (Build.VERSION.SDK_INT >= 30) context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                ?.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS else true
        }.getOrDefault(false)
        runCatching { build(bioOk).authenticate(android.os.CancellationSignal(), exec, cb) }
            .recoverCatching { build(false).authenticate(android.os.CancellationSignal(), exec, cb) }
            .onFailure { android.util.Log.w("PayCards", "unlock", it); done(false, "验证没能弹出来。可以点「再试一次」，或到系统设置里检查锁屏密码") }
    }

    /** [Mines-38.1] BiometricPrompt error code → one plain Chinese sentence (no English exception text). */
    fun plainAuthError(code: Int): String = when (code) {
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> "已取消验证，点「再试一次」继续"
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> "试错太多次，指纹暂时锁住了，可以用锁屏密码"
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> "指纹已锁住，请用锁屏密码解锁一次手机后再试"
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> "验证超时了，点「再试一次」"
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> "手机没录指纹，可以用锁屏密码验证"
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT -> "指纹暂时用不了，可以用锁屏密码验证"
        else -> "没有通过验证，点「再试一次」"
    }

    /** [Mines-38.1] selftest: does canAuthenticate run without a permission exception? */
    fun biometricSelfTest(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT < 30) "skip<30" else "ok:" + context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            .canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    }.getOrElse { "error:" + it.javaClass.simpleName + ":" + it.message?.take(60) }

    // ---- filling ------------------------------------------------------------
    /** Payment-form iframes we may write into (cross-origin card fields). */
    val PAY_ORIGINS = setOf(
        "https://js.stripe.com", "https://*.stripe.com", "https://*.braintreegateway.com", "https://*.braintree-api.com",
        "https://*.adyen.com", "https://*.checkout.com", "https://*.paddle.com", "https://*.recurly.com",
        "https://*.chargebee.com", "https://*.squareup.com", "https://*.squarecdn.com", "https://*.paypal.com",
    )
    private val PAY_HOST_RE = "(^|\\\\.)(stripe\\\\.com|braintreegateway\\\\.com|braintree-api\\\\.com|adyen\\\\.com|checkout\\\\.com|paddle\\\\.com|recurly\\\\.com|chargebee\\\\.com|squareup\\\\.com|squarecdn\\\\.com|paypal\\\\.com)$"

    /** The fill function (writes, never submits) + the postMessage listener, for allow-listed payment frames. */
    val FRAME_JS: String = """(function(){if(window.__minesCard)return;window.__minesCard=1;
var setv=function(e,v){if(!e||v==null||v==='')return false;try{e.focus();}catch(x){}
 if(e.tagName==='SELECT'){var o=[].slice.call(e.options).find(function(o){var a=(o.value||'').toUpperCase(),b=(o.text||'').toUpperCase(),w=String(v).toUpperCase();return a===w||b===w||b.indexOf(w)===0;});if(!o)return false;e.value=o.value;}
 else{var p=Object.getPrototypeOf(e);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set){d.set.call(e,v);}else{e.value=v;}}
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));try{e.dispatchEvent(new Event('blur'));}catch(x){}return true;};
var vis=function(e){var r=e.getBoundingClientRect();return r.width>2&&r.height>2&&!e.disabled&&!e.readOnly&&e.type!=='hidden';};
var pick=function(ac,words){var all=[].slice.call(document.querySelectorAll('input,select')).filter(vis);
 var a=all.find(function(e){return ac.indexOf((e.getAttribute('autocomplete')||'').toLowerCase().trim())>=0;});if(a)return a;
 return all.find(function(e){var h=((e.name||'')+' '+(e.id||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('data-elements-stable-field-name')||'')).toLowerCase();
  return words.some(function(w){return h.indexOf(w)>=0;});});};
window.__minesFillCard=function(c){var n=0;
 if(setv(pick(['cc-number'],['cardnumber','card-number','card number','cardno','卡号','ccnumber','card_number','number']),c.number))n++;
 var mon=pick(['cc-exp-month'],['exp-month','expmonth','exp_month','月']),yr=pick(['cc-exp-year'],['exp-year','expyear','exp_year','年']);
 if(mon&&yr){if(setv(mon,c.mm))n++;var y4=(c.yy.length===2?'20'+c.yy:c.yy);if(!setv(yr,y4))setv(yr,c.yy);}
 else if(setv(pick(['cc-exp'],['exp-date','expiry','expiration','exp','有效期','mm / yy','mm/yy']),c.mm+' / '+c.yy))n++;
 if(c.cvc&&setv(pick(['cc-csc'],['cvc','cvv','csc','security code','安全码']),c.cvc))n++;
 if(setv(pick(['cc-name','name'],['cardholder','card holder','nameoncard','name on card','full name','fullname','持卡人','姓名']),c.name))n++;
 setv(pick(['country','country-name'],['country','国家']),c.country);
 setv(pick(['address-line1','street-address'],['address','line1','street','地址']),c.line1);
 setv(pick(['address-level2'],['city','城市']),c.city);
 setv(pick(['address-level1'],['state','province','region','省']),c.state);
 setv(pick(['postal-code'],['postal','zip','postcode','邮编']),c.postal);
 return n;};
window.addEventListener('message',function(ev){var d=ev.data;if(!d||typeof d!=='object'||!d.__minesCardFill)return;try{window.__minesFillCard(d.__minesCardFill);}catch(x){}},false);
})();"""

    /** Installs [FRAME_JS] into payment iframes of [wv] (WebViewCompat document-start script, allow-listed origins only). */
    fun install(wv: WebView) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, FRAME_JS, PAY_ORIGINS)
        }
    }

    /** "1" when the page (or one of its frames) is a payment form. */
    val DETECT_JS = """(function(){try{
if(document.querySelector('input[autocomplete^="cc-"],input[name*="cardnumber" i],input[name*="card_number" i],input[id*="cardnumber" i]'))return '1';
var re=new RegExp('$PAY_HOST_RE');var f=[].slice.call(document.querySelectorAll('iframe'));
for(var i=0;i<f.length;i++){try{var h=new URL(f[i].src,location.href).hostname;if(re.test(h))return '1';}catch(e){}}
return '';}catch(e){return ''}})()"""

    /** Pages where filling happened: the agent may not read or screenshot them until the page changes. */
    private val filled = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, String>())
    fun lockedFor(wv: WebView, url: String?): Boolean = filled[wv]?.let { it == url } == true
    fun clearLock(wv: WebView) { filled.remove(wv) }

    /** Writes [c] into the page and its payment iframes. Never submits. Calls [done] with the main-frame count. */
    fun fill(wv: WebView, c: Card, done: (Int) -> Unit) {
        val data = JSONObject().put("number", digits(c.number)).put("mm", c.expMonth.padStart(2, '0')).put("yy", c.expYear.takeLast(2))
            .put("cvc", c.cvc).put("name", c.name).put("country", c.country).put("line1", c.line1).put("city", c.city)
            .put("state", c.state).put("postal", c.postal)
        val js = """(function(){try{$FRAME_JS
var c=$data;var n=window.__minesFillCard(c);var re=new RegExp('$PAY_HOST_RE');
[].slice.call(document.querySelectorAll('iframe')).forEach(function(f){try{var u=new URL(f.src,location.href);if(re.test(u.hostname)&&f.contentWindow)f.contentWindow.postMessage({__minesCardFill:c},u.origin);}catch(e){}});
c=null;return String(n);}catch(e){return '0'}})()"""
        filled[wv] = wv.url ?: ""
        wv.evaluateJavascript(js) { r -> done(r?.trim('"')?.toIntOrNull() ?: 0) }
    }
}

package com.openminis.app.plus

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * [Mines-38.7] SMS verification codes are read for the user (user 2026-10-15 「那么多东西开了，跟我说要我自己接受验证码」).
 *
 * Sources, best first, all feeding [HumanVerify] (whose login-page loop types the code in):
 *  1. RECEIVE_SMS granted → [SmsCodeReceiver] sees every new SMS at once (works on phones without Google Play, e.g.
 *     realme's Chinese ROM);
 *  2. READ_SMS granted → when a page starts asking for a code, the inbox is read for a code that came in just before;
 *  3. no SMS permission but Google Play services → SMS User Consent API: Android shows 「允许 Mines 读取这条短信？」 once
 *     for that one message ([SmsConsentActivity]);
 *  4. the notification listener (already in [HumanVerify.onNotification]) when notification access is on.
 * Codes stay in memory only, are never logged, and are used only while a login page waits for one.
 */
object SmsCodes {
    fun canReceive(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    fun canRead(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** smoke / 权限 page: what is in place right now. */
    fun sources(c: Context): List<String> = buildList {
        if (canReceive(c)) add("短信（直接收）")
        if (canRead(c)) add("短信收件箱")
        if (consentAvailable(c)) add("Google 短信授权")
        if (notificationAccess(c)) add("通知")
    }

    fun notificationAccess(c: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    }.getOrDefault(false)

    /** One SMS body → a code (4–8 digits next to 验证码 / code words). */
    fun offerText(c: Context, text: String, from: String) {
        runCatching { AutoAccounts.onSms(c, text) }   // [Mines-38.8] a code from a site not in 账号关联 yet → recorded
        runCatching { Keychain.onSms(c, text) }   // [Mines-38.9] 异地 / 新设备登录 SMS → alert
        val code = HumanVerify.extractCode(text) ?: return
        HumanVerify.init(c)
        if (!HumanVerify.settings.value.otpAutofill) return
        HumanVerify.offer(code)
        lastSource = from
    }

    @Volatile var lastSource: String = ""

    /** A page started asking for a code: look at the inbox (READ_SMS) for one that arrived in the last 2 minutes. */
    fun scanInbox(c: Context, sinceMs: Long) {
        if (!canRead(c)) return
        runCatching {
            c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?", arrayOf(sinceMs.toString()), "${Telephony.Sms.DATE} DESC")?.use { cur ->
                var n = 0
                while (cur.moveToNext() && n < 5) {
                    n++
                    val body = cur.getString(0) ?: continue
                    if (HumanVerify.extractCode(body) != null) { offerText(c, body, "短信收件箱"); return }
                }
            }
        }
    }

    // ---------------- SMS User Consent (Google Play services) ----------------

    fun consentAvailable(c: Context): Boolean = runCatching {
        com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)

    @Volatile private var consentReg: BroadcastReceiver? = null
    @Volatile private var consentAt = 0L

    /** Starts a 5-minute consent listen (no-op with SMS permission, without Play services, or when one is running). */
    fun startConsent(c: Context) {
        val app = c.applicationContext
        if (canReceive(app) || !consentAvailable(app)) return
        if (System.currentTimeMillis() - consentAt < 4 * 60_000L) return
        consentAt = System.currentTimeMillis()
        runCatching {
            com.google.android.gms.auth.api.phone.SmsRetriever.getClient(app).startSmsUserConsent(null)
            if (consentReg == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action != com.google.android.gms.auth.api.phone.SmsRetriever.SMS_RETRIEVED_ACTION) return
                        val ex: Bundle = intent.extras ?: return
                        val st = ex.get(com.google.android.gms.auth.api.phone.SmsRetriever.EXTRA_STATUS) as? com.google.android.gms.common.api.Status ?: return
                        if (st.statusCode != com.google.android.gms.common.api.CommonStatusCodes.SUCCESS) return
                        @Suppress("DEPRECATION")
                        val consent = ex.getParcelable<Intent>(com.google.android.gms.auth.api.phone.SmsRetriever.EXTRA_CONSENT_INTENT) ?: return
                        SmsConsentActivity.pending = consent
                        runCatching { ctx.startActivity(Intent(ctx, SmsConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                val f = IntentFilter(com.google.android.gms.auth.api.phone.SmsRetriever.SMS_RETRIEVED_ACTION)
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, com.google.android.gms.auth.api.phone.SmsRetriever.SEND_PERMISSION, null, Context.RECEIVER_EXPORTED)
                else app.registerReceiver(r, f, com.google.android.gms.auth.api.phone.SmsRetriever.SEND_PERMISSION, null)
                consentReg = r
            }
        }
    }
}

/** New SMS (RECEIVE_SMS granted): the code goes straight to the waiting login page. */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val text = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent).joinToString("") { it.messageBody ?: "" } }.getOrDefault("")
        if (text.isNotBlank()) SmsCodes.offerText(context, text, "短信")
    }
}

/** Shows Android's one-message consent dialog and hands the approved SMS to [SmsCodes]. */
class SmsConsentActivity : androidx.activity.ComponentActivity() {
    companion object { @Volatile var pending: Intent? = null }

    private val launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringExtra(com.google.android.gms.auth.api.phone.SmsRetriever.EXTRA_SMS_MESSAGE)?.let { SmsCodes.offerText(this, it, "Google 短信授权") }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = pending.also { pending = null }
        if (i == null) { finish(); return }
        runCatching { launcher.launch(i) }.onFailure { finish() }
    }
}

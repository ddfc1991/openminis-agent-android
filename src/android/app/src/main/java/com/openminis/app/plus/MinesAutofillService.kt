package com.openminis.app.plus

import android.app.Activity
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

/**
 * [Mines-38.9] 「用 Mines 填入」 in other apps and browsers (mock r389/02). The user picks Mines once as the phone's autofill
 * service (账号关联 › 开启自动填, one step). On a sign-in screen Mines offers the saved logins of that site / app; filling
 * needs the phone's screen lock or fingerprint first ([AutofillAuthActivity]). A sign-in typed by hand is offered for
 * saving (Android's own 「保存到 Mines？」). Passwords stay Keystore-encrypted in [SavedLogins].
 */
class MinesAutofillService : AutofillService() {

    data class Fields(val user: AutofillId?, val pass: AutofillId?, val webDomain: String, val pkg: String, val userText: String, val passText: String)

    // [38.9.11.3.1] fill / save run off the main thread: SavedLogins decrypts through Android Keystore (seconds on some
    // phones) and this service shares Mines' main thread — the realme ANR 2026-10-22 00:23 came right after two binds.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "mines-autofill").apply { isDaemon = true } }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        runCatching { worker.execute { fill(request, callback) } }.onFailure { callback.onSuccess(null) }
    }

    private fun fill(request: FillRequest, callback: FillCallback) {
        runCatching {
            val st = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
            val f = parse(st)
            if (f.pkg == packageName || (f.user == null && f.pass == null)) return callback.onSuccess(null)
            val ids = listOfNotNull(f.user, f.pass).toTypedArray()
            val b = FillResponse.Builder()
            val cands = candidates(this, f).take(4)
            for (e in cands) {
                val pres = row(this, "用 Mines 填入", e.name.ifBlank { e.host } + " · " + KeychainCore.maskAccount(e.user))
                val auth = Intent(this, AutofillAuthActivity::class.java)
                    .putExtra("host", e.host).putExtra("user", e.user)
                    .putExtra("uid", f.user).putExtra("pid", f.pass)
                val pi = PendingIntent.getActivity(this, (e.host + e.user).hashCode(), auth,
                    PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                val d = Dataset.Builder(pres)
                f.user?.let { d.setValue(it, null, pres) }
                f.pass?.let { d.setValue(it, null, pres) }
                d.setAuthentication(pi.intentSender)
                b.addDataset(d.build())
            }
            if (f.pass != null) b.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (f.user != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0), ids).build())
            if (cands.isEmpty() && f.pass == null) return callback.onSuccess(null)
            callback.onSuccess(b.build())
        }.onFailure { runCatching { callback.onSuccess(null) } }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        runCatching { worker.execute { save(request, callback) } }.onFailure { callback.onSuccess() }
    }

    private fun save(request: SaveRequest, callback: SaveCallback) {
        runCatching {
            val st = request.fillContexts.lastOrNull()?.structure
            val f = st?.let { parse(it) }
            if (f != null && f.passText.isNotBlank()) {
                val host = f.webDomain.ifBlank { "android." + f.pkg }
                val name = if (f.webDomain.isNotBlank()) AutoAccounts.siteOf(f.webDomain) else appLabel(this, f.pkg)
                SavedLogins.put(this, SavedLogins.Entry(name, if (f.webDomain.isNotBlank()) "https://${f.webDomain}/" else "https://$host/", f.userText, f.passText))
                if (f.userText.isNotBlank()) AutoAccounts.record(this, name, if (f.webDomain.isNotBlank()) Keychain.root(f.webDomain) else host, f.userText, AutoAccounts.LINKED, "自动填")
            }
        }
        runCatching { callback.onSuccess() }
    }

    companion object {
        private val USER = Regex("(?i)(user|email|e-mail|mail|login|account|phone|mobile|tel|手机|邮箱|账号|帐号|用户名|登录名)")
        private val PASS = Regex("(?i)(pass|pwd|密码)")
        private val NOISE = setOf("com", "cn", "net", "org", "android", "app", "apps", "mobile", "client", "www", "lite", "global", "intl", "pro", "plus", "main")

        fun parse(st: AssistStructure): Fields {
            var user: AutofillId? = null; var pass: AutofillId? = null; var web = ""; var ut = ""; var pt = ""
            fun walk(n: AssistStructure.ViewNode) {
                n.webDomain?.takeIf { it.isNotBlank() }?.let { if (web.isEmpty()) web = it }
                val hints = n.autofillHints?.joinToString(" ").orEmpty()
                val it = n.inputType
                val isPwType = it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                    (it and InputType.TYPE_MASK_VARIATION).let { v -> v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD } ||
                    (it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER && (it and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                val html = n.htmlInfo?.attributes?.firstOrNull { a -> a.first == "type" }?.second.orEmpty()
                val words = listOfNotNull(n.idEntry, n.hint, n.htmlInfo?.attributes?.firstOrNull { a -> a.first == "name" }?.second, hints).joinToString(" ")
                val id = n.autofillId
                if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
                    val isPass = hints.contains(View.AUTOFILL_HINT_PASSWORD, true) || isPwType || html.equals("password", true) || (PASS.containsMatchIn(words) && !USER.containsMatchIn(words))
                    val isUser = !isPass && (hints.contains(View.AUTOFILL_HINT_USERNAME, true) || hints.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS, true) ||
                        hints.contains(View.AUTOFILL_HINT_PHONE, true) || html.equals("email", true) || html.equals("tel", true) || USER.containsMatchIn(words))
                    if (isPass && pass == null) { pass = id; pt = n.autofillValue?.takeIf { v -> v.isText }?.textValue?.toString().orEmpty() }
                    else if (isUser && user == null) { user = id; ut = n.autofillValue?.takeIf { v -> v.isText }?.textValue?.toString().orEmpty() }
                }
                for (i in 0 until n.childCount) walk(n.getChildAt(i))
            }
            for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode)
            // a lone text field right before the password field is the user name (common when ids say nothing)
            return Fields(user, pass, web.removePrefix("www."), st.activityComponent?.packageName.orEmpty(), ut, pt)
        }

        /** Saved logins for a web domain, else for the app (its package / label matched against the saved sites). */
        fun candidates(c: Context, f: Fields): List<SavedLogins.Entry> {
            val all = SavedLogins.all(c)
            if (f.webDomain.isNotBlank()) {
                val r = Keychain.root(f.webDomain)
                return all.filter { it.host == f.webDomain || Keychain.root(it.host) == r }
            }
            val toks = (f.pkg.lowercase().split('.', '_') + appLabel(c, f.pkg).lowercase().split(' ')).filter { it.length >= 3 && it !in NOISE }.toSet()
            if (toks.isEmpty()) return emptyList()
            return all.filter { e -> e.host.startsWith("android.${f.pkg}") || Keychain.root(e.host).substringBefore('.').let { s -> s.length >= 3 && toks.any { t -> t == s || t.contains(s) || s.contains(t) } } }
        }

        fun appLabel(c: Context, pkg: String): String = runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        fun row(c: Context, title: String, sub: String): RemoteViews = RemoteViews(c.packageName, com.openminis.app.R.layout.mines_autofill_item).apply {
            setTextViewText(com.openminis.app.R.id.mines_af_title, title)
            setTextViewText(com.openminis.app.R.id.mines_af_sub, sub)
        }

        fun enabled(c: Context): Boolean = runCatching {
            c.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
        }.getOrDefault(false)

        /** The one step: Android's own 「自动填充服务」 picker with Mines pre-selected. */
        fun askEnable(c: Context) {
            runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }
}

/**
 * Screen lock / fingerprint before Mines hands a password to another app (and before 显示 / 复制 in 账号详情).
 * Android 10+: the system BiometricPrompt with the device credential allowed; Android 8–9: the keyguard confirm screen.
 * A phone with no screen lock at all is not gated (there is nothing to check against).
 */
object Unlock {
    /** Same check as 支付卡 (fingerprint or the screen lock, 5 min grace; no lock on the phone → allowed). */
    fun prompt(ctx: Context, title: String, done: (Boolean) -> Unit) = PayCards.unlock(ctx, title) { ok, _ -> done(ok) }
}

class AutofillAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra("host").orEmpty(); val user = intent.getStringExtra("user").orEmpty()
        @Suppress("DEPRECATION") val uid = intent.getParcelableExtra<AutofillId>("uid")
        @Suppress("DEPRECATION") val pid = intent.getParcelableExtra<AutofillId>("pid")
        Unlock.prompt(this, "用 Mines 填入 ${AutoAccounts.siteOf(host)}") { ok ->
            val e = if (ok) SavedLogins.all(this).firstOrNull { it.host == host && it.user == user } else null
            if (e == null) { setResult(RESULT_CANCELED); finish(); return@prompt }
            val pres = MinesAutofillService.row(this, "用 Mines 填入", e.name.ifBlank { e.host })
            val d = Dataset.Builder(pres)
            uid?.let { d.setValue(it, AutofillValue.forText(e.user)) }
            pid?.let { d.setValue(it, AutofillValue.forText(e.pass)) }
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, d.build()))
            runCatching { Keychain.recordLogin(this, Keychain.root(host.removePrefix("android.")), user, "自动填 · 别的 App") }
            finish()
        }
    }
}

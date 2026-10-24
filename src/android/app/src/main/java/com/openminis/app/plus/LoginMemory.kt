package com.openminis.app.plus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
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
 * [Mines-38.9.5 i] user 22:43 「这个账号关联有记录，下次就可以不用那么麻烦了」: every successful sign-in is remembered per site
 * (LoginMemoryCore.Memo: account, method, code mailbox, the user's pick) plus an encrypted snapshot of the site's session
 * cookies (Android Keystore AES-GCM, never leaves the phone, never logged, never shown to the AI). Next time:
 * [restoreSession] (silent reuse) → the saved method (LoginAssist) → [LoginCard] only when both fail.
 */
object LoginMemory {
    private const val PREFS = "mines_login_memory"
    private const val ALIAS = "mines_login_memory_v1"
    @Volatile private var cache: List<LoginMemoryCore.Memo>? = null

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    private fun enc(s: String): String {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ci.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun dec(s: String): String {
        val (iv, ct) = s.split(':', limit = 2)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(ci.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }

    fun all(c: Context): List<LoginMemoryCore.Memo> {
        cache?.let { return it }
        // [38.9.11.2] atomic file (+2 backups) first, the prefs copy as the last fallback; one notice if everything fails
        val (txt, allFailed) = AtomicStore.read(c, "login_memory.json") { runCatching { JSONArray(it); true }.getOrDefault(false) }
        val a = txt?.let { runCatching { JSONArray(it) }.getOrNull() } ?: runCatching { JSONArray(p(c).getString("memos", "[]")) }.getOrNull()
            ?: JSONArray().also { if (allFailed) AtomicStore.notice(c, "登录记录") }
        val l = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            LoginMemoryCore.Memo(o.optString("site"), o.optString("account"), LoginMemoryCore.Method.of(o.optString("method")), o.optString("via"),
                p(c).contains("ck_" + o.optString("site")), o.optBoolean("chosen"), o.optBoolean("pw"), o.optLong("at"))
        } }
        cache = l
        return l
    }

    private fun save(c: Context, l: List<LoginMemoryCore.Memo>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("site", it.site).put("account", it.account).put("method", it.method.id).put("via", it.codeVia)
            .put("chosen", it.chosen).put("pw", it.hasPassword).put("at", it.at)) }
        AtomicStore.write(c, "login_memory.json", a.toString())
        p(c).edit().putString("memos", a.toString()).apply()
        cache = null
    }

    fun memoFor(c: Context, host: String): LoginMemoryCore.Memo? = LoginMemoryCore.pick(all(c), Keychain.root(host).ifBlank { host })

    /** A sign-in on [host] just succeeded: remember how, and snapshot the session cookies (encrypted). */
    fun record(c: Context, host: String, account: String, method: LoginMemoryCore.Method, codeVia: String = "", chosen: Boolean = false) {
        val site = Keychain.root(host).ifBlank { host }
        if (site.isBlank()) return
        val hasPw = account.isNotBlank() && runCatching { SavedLogins.find(c, host, account) != null }.getOrDefault(false)
        save(c, LoginMemoryCore.record(all(c), LoginMemoryCore.Memo(site, account, method, codeVia, false, chosen, hasPw, System.currentTimeMillis())))
        if (hasPw) runCatching { Ledger.markOk(c, host, account) }   // [38.9.11.3] 台账 ranking: last success on the site
        snapshotSession(c, host)
    }

    fun methodOf(plan: IdentityCore.Plan?): LoginMemoryCore.Method = when (plan?.method) {
        IdentityCore.Method.EMAIL_PW, IdentityCore.Method.PHONE_PW, IdentityCore.Method.USER_PW -> LoginMemoryCore.Method.PASSWORD
        IdentityCore.Method.EMAIL_CODE, IdentityCore.Method.PHONE_SMS -> LoginMemoryCore.Method.CODE
        IdentityCore.Method.OAUTH -> when (plan.provider) {
            IdentityCore.IdType.GOOGLE -> LoginMemoryCore.Method.GOOGLE
            IdentityCore.IdType.GITHUB -> LoginMemoryCore.Method.GITHUB
            else -> LoginMemoryCore.Method.OTHER_OAUTH
        }
        else -> LoginMemoryCore.Method.WEB
    }

    private fun urls(site: String) = listOf("https://$site", "https://www.$site")

    fun snapshotSession(c: Context, host: String) = runCatching {
        val site = Keychain.root(host).ifBlank { host }
        val cm = android.webkit.CookieManager.getInstance()
        cm.flush()
        val o = JSONObject()
        (urls(site) + "https://$host").distinct().forEach { u -> cm.getCookie(u)?.takeIf { it.isNotBlank() }?.let { o.put(u, it) } }
        if (o.length() > 0) p(c).edit().putString("ck_$site", enc(o.toString())).apply()
        cache = null
    }

    /** Silent reuse: when the WebView jar lost the site's cookies (cleared, other profile), put the saved ones back. True = restored now (reload the page). */
    fun restoreSession(c: Context, host: String): Boolean = runCatching {
        val site = Keychain.root(host).ifBlank { host }
        val raw = p(c).getString("ck_$site", null) ?: return false
        val cm = android.webkit.CookieManager.getInstance()
        if (!cm.getCookie("https://$site").isNullOrBlank()) return false
        val o = JSONObject(dec(raw))
        o.keys().forEach { u -> o.optString(u).split(';').map { it.trim() }.filter { it.contains('=') }.forEach { kv -> cm.setCookie(u, "$kv; path=/; secure") } }
        cm.flush()
        true
    }.getOrDefault(false)

    /** [Mines-38.9.12] the saved session snapshot of a site (url → "k=v; k2=v2"), decrypted, or empty — for 账号自动调用 */
    fun snapshotCookies(c: Context, host: String): Map<String, String> = runCatching {
        val site = Keychain.root(host).ifBlank { host }
        val raw = p(c).getString("ck_$site", null) ?: return emptyMap()
        val o = JSONObject(dec(raw))
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    fun forget(c: Context, host: String) {
        val site = Keychain.root(host).ifBlank { host }
        save(c, all(c).filterNot { it.site == site }); p(c).edit().remove("ck_$site").apply()
    }
}

/**
 * [Mines-38.9.5 i] user 22:42 「第一次就搞成你们那种输入进去的」: the secure login card. A sign-in that needs something Mines does
 * not have asks here (LoginCardHost shows a glass bottom sheet with the site logo + name and only the fields the page
 * wants); the answer goes straight into the page in the background WebView. Values never reach chat, logs or the AI.
 */
object LoginCard {
    /** [38.9.13 handoff_v2.label] [task] = the task name shown above the site name (「Railway 注册 · 第2次」) */
    data class Req(val host: String, val name: String, val ask: LoginMemoryCore.Ask, val account: String, val lastLine: String?, val task: String = "") {
        val id = System.nanoTime()
    }
    data class Answer(val account: String, val password: String, val code: String, val save: Boolean)

    private val _pending = MutableStateFlow<Req?>(null)
    val pending: StateFlow<Req?> = _pending.asStateFlow()
    private var waiter: CompletableDeferred<Answer?>? = null

    /** Shows the card and waits for the user (null = closed). One card at a time. */
    suspend fun ask(r: Req, ctx: Context? = null): Answer? {
        demoShown = false
        // [38.9.11.1] Mines in the background → a notification that opens it (the card is waiting on top)
        ctx?.let { c -> if ((c.applicationContext as? com.openminis.app.MinisApp)?.isAppForeground() == false) notifyCard(c, r) }
        waiter?.complete(null)
        val d = CompletableDeferred<Answer?>()
        waiter = d
        _pending.value = r
        return try { d.await() } finally { if (_pending.value?.id == r.id) _pending.value = null }
    }

    private fun notifyCard(context: Context, r: Req) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("mines_login") == null)
                nm.createNotificationChannel(android.app.NotificationChannel("mines_login", "需要你登录", android.app.NotificationManager.IMPORTANCE_HIGH))
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = launch?.let { android.app.PendingIntent.getActivity(context, 7311, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT) }
            val n = androidx.core.app.NotificationCompat.Builder(context, "mines_login")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("需要你登录 ${r.name}")
                .setContentText("Mines 在等你填账号密码（安全输入框，不会出现在聊天里）")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }.build()
            nm.notify(7311, n)
            MinesCall.onNeedsAttention(context, "需要你登录", "${r.name} 需要登录，Mines 在等你")   // [Mines-38.9.12 b]
        }
    }

    fun answer(a: Answer?) { val w = waiter; waiter = null; _pending.value = null; demoShown = false; w?.complete(a) }

    /** smoke demo card: not FLAG_SECURE so the screenshot driver can capture it (fake values only). */
    @Volatile var demoShown: Boolean = false; private set

    /** smoke: show the card with fake values (no page behind it). */
    fun demo(refused: Boolean) {
        demoShown = true
        val ask = if (refused) LoginMemoryCore.Ask(listOf(LoginMemoryCore.Field.EMAIL, LoginMemoryCore.Field.PASSWORD), "密码不对，重新输入", true)
            else LoginMemoryCore.Ask(listOf(LoginMemoryCore.Field.EMAIL, LoginMemoryCore.Field.PASSWORD, LoginMemoryCore.Field.CODE), "登录 Notion", false)
        waiter?.complete(null); waiter = CompletableDeferred()
        _pending.value = Req("notion.so", "Notion", ask, "smoke@example.com", "上次用 sm***@example.com 登录 · 密码")
    }
}

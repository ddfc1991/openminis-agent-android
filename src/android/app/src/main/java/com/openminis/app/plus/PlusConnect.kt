package com.openminis.app.plus

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.confirm.ConfirmOutcome
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Modern mobile Chrome UA for the login WebView (sites refuse "wv" UAs). */
const val MINES_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; RMX5062) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

/**
 * Connection flows that do not need a page of their own:
 * OAuth device flows (GitHub, Microsoft), clipboard key detection,
 * web-session snapshots, Wispbyte keep-alive, and the agent's permission check.
 */
object PlusConnect {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    // ---------------- OAuth device flow ----------------

    data class DeviceCode(
        val userCode: String,
        val verifyUrl: String,
        val deviceCode: String,
        val interval: Int,
        val expiresIn: Int,
    )

    sealed class PollResult {
        data class Done(val token: String, val refresh: String?, val expiresIn: Long) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Failed(val message: String) : PollResult()
    }

    /** GitHub CLI's public OAuth app (device flow enabled, no client secret needed). */
    private const val GH_CLIENT = "178c6fc778ccc68e1d6a"
    private const val GH_SCOPES = "repo read:org gist workflow notifications"

    /** Microsoft Graph Command Line Tools public client (device code, delegated scopes). */
    private const val MS_CLIENT = "14d82eec-204b-4c2f-b7e8-296a70dab67e"
    private const val MS_SCOPES = "offline_access User.Read Mail.ReadWrite Mail.Send Calendars.ReadWrite Files.ReadWrite"
    private const val MS_BASE = "https://login.microsoftonline.com/common/oauth2/v2.0"

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "http_${r.code}") }
        }
    }

    fun startDevice(kindId: String): DeviceCode {
        val o = if (kindId == "github") postForm("https://github.com/login/device/code",
            mapOf("client_id" to GH_CLIENT, "scope" to GH_SCOPES))
        else postForm("$MS_BASE/devicecode", mapOf("client_id" to MS_CLIENT, "scope" to MS_SCOPES))
        if (!o.has("device_code")) error(o.optString("error_description", o.optString("error", "请求失败")))
        return DeviceCode(
            userCode = o.getString("user_code"),
            verifyUrl = o.optString("verification_uri", if (kindId == "github") "https://github.com/login/device" else "https://microsoft.com/devicelogin"),
            deviceCode = o.getString("device_code"),
            interval = o.optInt("interval", 5),
            expiresIn = o.optInt("expires_in", 900),
        )
    }

    fun pollDevice(kindId: String, code: DeviceCode): PollResult = runCatching {
        val o = if (kindId == "github") postForm("https://github.com/login/oauth/access_token", mapOf(
            "client_id" to GH_CLIENT, "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        else postForm("$MS_BASE/token", mapOf(
            "client_id" to MS_CLIENT, "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        val token = o.optString("access_token")
        when {
            token.isNotEmpty() -> PollResult.Done(token, o.optString("refresh_token").ifEmpty { null }, o.optLong("expires_in", 0))
            o.optString("error") == "authorization_pending" -> PollResult.Pending
            o.optString("error") == "slow_down" -> PollResult.SlowDown
            else -> PollResult.Failed(o.optString("error_description", o.optString("error", "授权失败")))
        }
    }.getOrElse { PollResult.Pending } // network blip: keep polling

    /** Account name for a fresh token. */
    fun whoAmI(kindId: String, token: String): String = runCatching {
        val (url, field) = if (kindId == "github") "https://api.github.com/user" to "login"
        else "https://graph.microsoft.com/v1.0/me" to "userPrincipalName"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").header("User-Agent", "Mines").build()
        http.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()).optString(field) }
    }.getOrDefault("")

    fun saveOAuth(context: Context, kind: ConnectionKind, r: PollResult.Done, account: String) {
        PlusVault.init(context)
        PlusVault.save(context, kind, account, r.token, share = PlusVault.entries.value[kind.id]?.shareWithAi ?: true, via = "oauth")
        PlusVault.setSecretExtra(kind.id, "refresh", r.refresh)
        if (r.expiresIn > 0) PlusVault.setExtra(kind.id, "expires", (System.currentTimeMillis() + r.expiresIn * 1000).toString())
    }

    /** Fresh Microsoft Graph token (refreshes when within 5 minutes of expiry). */
    fun microsoftToken(context: Context): String? {
        PlusVault.init(context)
        val id = PlusVault.defaultAccount("microsoft") ?: return null
        val tok = PlusVault.secret(id) ?: return null
        val exp = PlusVault.extra(id, "expires")?.toLongOrNull() ?: 0L
        if (exp > System.currentTimeMillis() + 300_000) return tok
        val refresh = PlusVault.secretExtra(id, "refresh") ?: return tok
        return runCatching {
            val o = postForm("$MS_BASE/token", mapOf("client_id" to MS_CLIENT, "grant_type" to "refresh_token",
                "refresh_token" to refresh, "scope" to MS_SCOPES))
            val t = o.optString("access_token")
            if (t.isEmpty()) return@runCatching tok
            val kind = PlusVault.kind(id)!!
            saveOAuth(context, kind, PollResult.Done(t, o.optString("refresh_token").ifEmpty { refresh }, o.optLong("expires_in", 3600)),
                PlusVault.entries.value[id]?.account ?: "")
            t
        }.getOrDefault(tok)
    }

    // ---------------- clipboard key pick-up ----------------

    /** A key for [kind] currently on the clipboard, if any. */
    fun keyOnClipboard(context: Context, kind: ConnectionKind): String? {
        val rx = kind.keyRegex ?: return null
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull() ?: return null
        if (text.length > 4000) return null
        val m = rx.find(text.trim()) ?: return null
        return m.value.let { if (kind.id == "gmail") it.replace(" ", "") else it }
    }

    // ---------------- web sessions ----------------

    fun hostOf(url: String?): String = runCatching { URI(url ?: "").host?.lowercase() }.getOrNull().orEmpty()

    /** Snapshot the cookies of a signed-in site into the encrypted vault (restored if WebView data is cleared). */
    fun snapshotCookies(kind: ConnectionKind, urls: Collection<String>) {
        val cm = CookieManager.getInstance()
        cm.flush()
        val arr = JSONArray()
        urls.map { hostOf(it) }.filter { it.isNotEmpty() }.distinct().forEach { host ->
            val c = cm.getCookie("https://$host/") ?: return@forEach
            arr.put(JSONObject().put("host", host).put("cookie", c))
        }
        if (arr.length() > 0) PlusVault.setSecretExtra(kind.id, "cookies", arr.toString())
    }

    /** Put saved cookies back when the WebView store lost them (e.g. after "clear browser data"). */
    fun restoreCookies(kind: ConnectionKind) {
        val raw = PlusVault.secretExtra(kind.id, "cookies") ?: return
        val cm = CookieManager.getInstance()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val host = o.getString("host")
                // [Mines-38.9.3] put back every sign-in cookie the store lost (not only when the site has NO cookie at
                // all — one surviving analytics cookie blocked it), as a persistent cookie (was a session cookie again)
                val live = SessionStatus.cookiePairs(cm.getCookie("https://$host/"))
                SessionStatus.cookiePairs(o.getString("cookie")).forEach { (n, v) ->
                    if (n !in live && SessionStatus.keepCookie(n) && v.isNotEmpty())
                        cm.setCookie("https://$host/", "$n=$v; Path=/; Max-Age=${SessionStatus.KEEP_MS / 1000}; Secure")
                }
            }
            cm.flush()
        }
    }

    fun clearCookies(kind: ConnectionKind) {
        val raw = PlusVault.secretExtra(kind.id, "cookies") ?: return
        val cm = CookieManager.getInstance()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val host = arr.getJSONObject(i).getString("host")
                cm.getCookie("https://$host/")?.split(";")?.map { it.substringBefore('=').trim() }?.forEach { name ->
                    cm.setCookie("https://$host/", "$name=; Max-Age=0; Path=/")
                    cm.setCookie("https://$host/", "$name=; Max-Age=0; Path=/; Domain=.$host")
                }
            }
            cm.flush()
        }
    }

    // ---------------- Wispbyte keep-alive ----------------

    private const val KEEP_CHANNEL = "mines_keepalive"
    private const val KEEP_EVERY_MS = 10L * 24 * 3600 * 1000

    fun keepAliveOn(context: Context, kindId: String): Boolean {
        PlusVault.init(context)
        return PlusVault.extra(kindId, "keepalive") == "1"
    }

    fun setKeepAlive(context: Context, kindId: String, on: Boolean) {
        PlusVault.init(context)
        PlusVault.setExtra(kindId, "keepalive", if (on) "1" else "0")
        schedule(context, kindId, on)
    }

    /** Re-arm every enabled keep-alive (called at app start; alarms die on reboot / update). */
    fun rearm(context: Context) {
        PlusVault.init(context)
        PlusVault.entries.value.keys.filter { PlusVault.extra(it, "keepalive") == "1" }.forEach { schedule(context, it, true) }
    }

    private fun pending(context: Context, kindId: String) = PendingIntent.getBroadcast(
        context, kindId.hashCode(),
        Intent(context, KeepAliveReceiver::class.java).putExtra("kind", kindId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(context: Context, kindId: String, on: Boolean) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, kindId)
        am.cancel(pi)
        if (!on) return
        val last = PlusVault.extra(kindId, "keepalive_last")?.toLongOrNull() ?: System.currentTimeMillis()
        val dueIn = (last + KEEP_EVERY_MS - System.currentTimeMillis()).coerceIn(60_000L, KEEP_EVERY_MS)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + dueIn, pi)
    }

    fun notifyUser(context: Context, title: String, text: String, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(KEEP_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(KEEP_CHANNEL, "账号保活", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(id, android.app.Notification.Builder(context, KEEP_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }

    /** Opens the panel in a hidden WebView with the saved session; reports when the session is gone. */
    @SuppressLint("SetJavaScriptEnabled")
    fun runKeepAlive(context: Context, kindId: String, done: () -> Unit) {
        val kind = PlusVault.kind(kindId)
        val url = kind?.panelUrl ?: kind?.loginUrl
        if (kind == null || url == null) { done(); return }
        restoreCookies(kind)
        val main = Handler(Looper.getMainLooper())
        main.post {
            var finished = false
            val wv = runCatching { WebView(context.applicationContext) }.getOrNull()
            if (wv == null) { done(); return@post }
            fun finish(ok: Boolean?) {
                if (finished) return
                finished = true
                when (ok) {
                    true -> {
                        PlusVault.setExtra(kindId, "keepalive_last", System.currentTimeMillis().toString())
                        snapshotCookies(kind, listOf(url))
                    }
                    false -> notifyUser(context, "${kind.name} 登录已过期",
                        "保活没成功：登录已失效。打开 Mines → 设置 → 账号连接 → ${kind.name}，点「重新登录」就好。", 7400 + (kindId.hashCode() and 0xff))
                    null -> Unit // inconclusive (network); try again next round
                }
                schedule(context, kindId, true)
                runCatching { wv.stopLoading(); wv.destroy() }
                done()
            }
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = MINES_MOBILE_UA
            com.openminis.app.browser.WebFit.apply(wv)  // [Mines-webfit-38.3]
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    val loggedOut = u.contains("/login") || u.contains("github.com/session") || u.contains("/signin")
                    val matched = kind.doneUrl?.matches(u) == true
                    if (loggedOut) finish(false) else if (matched) finish(true)
                }
            }
            wv.loadUrl(url)
            main.postDelayed({
                val u = wv.url.orEmpty()
                finish(if (kind.doneUrl?.matches(u) == true) true else if (u.contains("login")) false else null)
            }, 40_000)
        }
    }

    // ---------------- agent permission gate (minis-connect) ----------------

    fun promptLine(): String {
        val e = PlusVault.entries.value
        if (e.isEmpty()) return ""
        return "\n- Mines connected accounts: " + e.keys.sorted().joinToString(", ") +
            ". Before acting through one of them run `minis-connect check <service> <capability>` (capabilities: `minis-connect list`); exit 0 = go ahead, otherwise do not."
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kindId = intent.getStringExtra("kind") ?: return
        PlusVault.init(context)
        if (!PlusConnect.keepAliveOn(context, kindId)) return
        val result = goAsync()
        PlusConnect.runKeepAlive(context, kindId) { runCatching { result.finish() } }
    }
}

/**
 * `minis-connect` — lets the agent see what is connected and ask before acting.
 *   minis-connect list
 *   minis-connect check <service> <capability> [--why "what I'm about to do"]
 *   minis-connect token microsoft      (prints a fresh Graph token after a read check)
 */
class ConnectOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        PlusVault.init(context)
        val args = request.argv.drop(1)
        val sub = args.firstOrNull() ?: "list"
        return when (sub) {
            "list" -> NativeOffloadResult(0, list())
            "check" -> check(args.getOrNull(1), args.getOrNull(2), why(args), opt(args, "--account"))
            "token" -> token(args.getOrNull(1))
            "remind" -> remind(args.getOrNull(1), args.getOrNull(2), args.drop(3).joinToString(" "))
            else -> NativeOffloadResult(0, HELP)
        }
    }

    private fun why(args: List<String>): String? {
        val i = args.indexOf("--why")
        if (i < 0) return null
        return args.drop(i + 1).takeWhile { it != "--account" }.joinToString(" ").ifBlank { null }
    }

    private fun opt(args: List<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0) args.getOrNull(i + 1) else null
    }

    private fun list(): String {
        val out = JSONArray()
        PlusVault.entries.value.values.forEach { e ->
            val k = PlusVault.kind(e.kindId) ?: return@forEach
            out.put(JSONObject().apply {
                put("service", PlusVault.baseId(k.id)); put("account_id", k.id); put("name", k.name)
                // [Mines-38.9.4] issue #2 Bug 1: browser-session rows had an empty account — say how it is signed in
                // (「网页登录」 / 「Chrome 已登录」 / masked identity) like google / gmail already did
                val known = AccountLabel.identity(k.id, e)
                put("account", known ?: AccountLabel.method(k, e)); put("account_known", known != null); put("via", e.via)
                put("default", PlusVault.defaultAccount(PlusVault.baseId(k.id)) == k.id)
                put("env", if (e.shareWithAi && k.envKey != null) k.envKey else JSONObject.NULL)
                put("browser_session", e.via == "web")
                put("capabilities", JSONObject().apply {
                    PlusVault.capabilities(k.profile).forEach { c -> put(c.id, PlusVault.level(k, c).name.lowercase()) }
                })
            })
        }
        return JSONObject().put("connections", out)
            .put("note", "allow = go ahead; ask = run `minis-connect check` and wait for the user's answer; deny = never do it. Signed-in web sessions are shared with the browser tool.")
            .toString(2)
    }

    private fun check(service: String?, capId: String?, why: String?, account: String?): NativeOffloadResult {
        if (service == null || capId == null) return NativeOffloadResult(2, "usage: minis-connect check <service> <capability> [--why text]")
        PlusVault.kind(service) ?: return NativeOffloadResult(4, "unknown service '$service' (see minis-connect list)")
        val entryId = if (service.contains('#')) service.takeIf { PlusVault.entries.value.containsKey(it) }
            else PlusVault.resolveAccount(PlusVault.baseId(service), account)
        val kind = entryId?.let { PlusVault.kind(it) }
            ?: return NativeOffloadResult(4, (if (account != null) "no account '$account' on $service. " else "$service is not connected. ") +
                "Ask the user to connect it in Settings → 账号连接 (see minis-connect list).")
        val who = PlusVault.entries.value[kind.id]?.account?.takeIf { it.isNotBlank() }?.let { " (" + AccountLabel.maskIdentity(it) + ")" } ?: ""  // [Mines-privacy-35.3]
        val cap = PlusVault.capabilities(kind.profile).firstOrNull { it.id == capId }
            ?: PlusVault.capabilities(kind.profile).firstOrNull { it.id == "publish" && capId in setOf("send", "post", "write", "deploy", "push") }
            ?: return NativeOffloadResult(2, "unknown capability '$capId' for ${kind.id}; known: " +
                PlusVault.capabilities(kind.profile).joinToString(",") { it.id })
        return when (PlusVault.level(kind, cap)) {
            PermLevel.ALLOW -> NativeOffloadResult(0, "allowed: ${kind.name}$who · ${cap.label}" +
                (if (PlusVault.defaultAccount(PlusVault.baseId(kind.id)) != kind.id) ". This is not the default account: use its value from ${kind.envKey ?: "the env"}_ACCOUNTS" else ""))
            PermLevel.DENY -> NativeOffloadResult(3, "denied: the user does not allow ${cap.label} on ${kind.name}. Do not do it; tell the user it is switched off in 账号连接.")
            PermLevel.ASK -> {
                val change = PendingConfigChange(
                    items = listOf(PendingConfigChangeItem(
                        displayName = "${kind.name}$who · ${cap.label}",
                        path = "connections.${kind.id}.${cap.id}",
                        oldDisplay = "",
                        newDisplay = why ?: "AI 想用 ${kind.name} ${cap.label}",
                        verb = "允许一次",
                        risk = if (cap.id in setOf("pay", "trade", "delete")) ConfigRisk.DESTRUCTIVE else ConfigRisk.SENSITIVE,
                    )),
                    caption = "AI 想用你连接的 ${kind.name} 做一件事：${why ?: cap.label}",
                )
                when (runBlocking { ConfigConfirmationGate.requestConfirmation(change) }) {
                    is ConfirmOutcome.Approved -> NativeOffloadResult(0, "approved once by the user: ${kind.name} · ${cap.label}")
                    is ConfirmOutcome.Rejected -> NativeOffloadResult(3, "the user said no. Do not do it.")
                    is ConfirmOutcome.TimedOut -> NativeOffloadResult(5, "no answer from the user within 2 minutes. Do not do it; ask in chat.")
                }
            }
        }
    }

    /** [Mines-patrol-32] expiry reminder for an account (trial end, credits, token). */
    private fun remind(service: String?, date: String?, note: String): NativeOffloadResult {
        if (service == null || date == null) return NativeOffloadResult(2, "usage: minis-connect remind <service> <yyyy-mm-dd|clear> [note]")
        val id = if (service.contains('#')) service else PlusVault.defaultAccount(PlusVault.baseId(service))
            ?: return NativeOffloadResult(4, "$service is not connected")
        if (date == "clear") { Expiry.clear(id); return NativeOffloadResult(0, "reminder cleared for $id") }
        if (!date.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return NativeOffloadResult(2, "date must be yyyy-mm-dd")
        Expiry.set(id, date, note)
        return NativeOffloadResult(0, "reminder set: $id $date ${note.ifBlank { "到期" }} (账号巡查 warns 7 days before)")
    }

    private fun token(service: String?): NativeOffloadResult {
        if (service != "microsoft") return NativeOffloadResult(2, "only `minis-connect token microsoft` is supported; other tokens are in env vars")
        val kind = PlusVault.kind(PlusVault.defaultAccount("microsoft") ?: "microsoft")!!
        val read = PlusVault.capabilities(kind.profile).first { it.id == "read" }
        if (PlusVault.level(kind, read) == PermLevel.DENY) return NativeOffloadResult(3, "denied by the user")
        PlusConnect.microsoftToken(context) ?: return NativeOffloadResult(4, "Microsoft is not connected")
        // The token goes into the redacted env var, never into the transcript.
        PlusVault.setShare(context, kind, true)
        return NativeOffloadResult(0, "MS_GRAPH_TOKEN refreshed; use \$MS_GRAPH_TOKEN in your next shell command (never print it).")
    }

    companion object {
        const val HELP = """minis-connect — accounts the user connected in Mines (设置 → 账号连接)
  minis-connect list                                  services, accounts, env vars, permission levels
  minis-connect check <service> <capability> [--account <name>] [--why "what you are about to do"]
        without --account the service's default account is used; <VAR> env = default account,
        <VAR>_ACCOUNTS = JSON {account: secret} when several accounts are shared
        exit 0 = allowed (or the user approved just now), 3 = denied/refused, 5 = no answer
  minis-connect token microsoft                       refresh ${'$'}MS_GRAPH_TOKEN (Microsoft Graph)
  minis-connect remind <service> <yyyy-mm-dd|clear> [note]   expiry reminder (trial end, credits run out)
Run `check` before every action through a connected account; never act when it does not exit 0."""
    }
}

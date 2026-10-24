package com.openminis.app.plus.group

import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [Mines-38.8] Small cancellable HTTP helper for the preset adapters (self-hosted / domestic hosts: direct connection). */
object PresetHttp {
    data class Res(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    val JSON = "application/json".toMediaType()

    private fun client(readSec: Long) = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS).callTimeout(readSec + 15, TimeUnit.SECONDS).build()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    /** Throws [MemberError] with a plain reason when the host cannot be reached. */
    suspend fun call(url: String, token: String? = null, body: JSONObject? = null, readSec: Long = 30, method: String? = null, cookie: String? = null): Res {
        val b = try { Request.Builder().url(url) } catch (e: IllegalArgumentException) { throw MemberError("地址不对：$url") }
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)   // [Mines-38.9.4] hosted Memoh: the WebView sign-in session
        b.header("Accept", "application/json")
        when {
            body != null -> b.method(method ?: "POST", body.toString().toRequestBody(JSON))
            method != null -> b.method(method, if (method == "GET" || method == "HEAD") null else "{}".toRequestBody(JSON))
        }
        return try {
            client(readSec).newCall(b.build()).await().use { r -> Res(r.code, r.body?.string().orEmpty()) }
        } catch (e: IOException) {
            val m = (e.message ?: "").lowercase()
            throw MemberError(when {
                m.contains("timeout") || m.contains("timed out") -> "等太久没回复（超时）"
                m.contains("unable to resolve") || m.contains("no address") -> "连不上（域名解析失败）"
                m.contains("refused") -> "连不上（服务器没开或端口不对）"
                m.contains("canceled") -> "已停止"
                else -> "连不上（网络问题）"
            }, e.message)
        }
    }

    /** The error code a relay / server put in its body ({"error":"bad_key"} / {"code":"…"} / {"error":{"code":…}}). */
    fun errCode(r: Res): String {
        val o = r.json() ?: return ""
        o.optJSONObject("error")?.let { e -> return e.optString("code").ifBlank { e.optString("type") } }
        return o.optString("error").ifBlank { o.optString("code") }.ifBlank { o.optString("message") }.take(60)
    }
}

/**
 * [Mines-38.8] Credentials of login-flow presets, encrypted in PlusVault (kind "preset_auth"); plain settings (server
 * address, user name, e-mail) in GroupPrefs. Never shown in the UI.
 */
object PresetAuth {
    private const val V = "preset_auth"

    fun key(id: String): String? {
        val base = id.substringBefore(':')
        return PlusVault.secretExtra(V, "key_$base")?.trim()?.takeIf { it.isNotBlank() }
    }
    fun setKey(id: String, v: String?) = PlusVault.setSecretExtra(V, "key_$id", v?.trim())

    // ── nanoMuse relay ────────────────────────────────────────────────────

    // [Mines-38.9.5 i] the relay's API lives under /v1 (POST /v1/auth/code, /v1/auth/verify, /v1/chat/completions): an address
    // saved without /v1 sent the sign-in to /auth/* (404 → 「中继地址不对」) and chat to the wrong path
    fun relayBase(): String = com.openminis.app.plus.RouteStatusCore.relayBase(GroupPrefs.get("base_nanomuse")).ifBlank { AiCatalog.NANOMUSE_BASE }
    fun relayEmail(): String? = GroupPrefs.get("nanomuse_email")

    private fun relayProblem(r: PresetHttp.Res): String = when (val c = PresetHttp.errCode(r)) {
        "code_too_often", "rate_limited" -> "发得太频繁了，等一分钟再试"
        "domain_limited" -> "这个邮箱域名今天开号太多了，换个常用邮箱"
        "signup_closed", "signups_closed", "signup_paused" -> "中继没开放新账号：用中继里已有的邮箱"
        "account_disabled" -> "这个账号在中继里被停用了"
        "bad_code", "wrong_code", "code_expired", "invalid_code", "too_many_attempts" -> "验证码不对或过期了，重新获取"
        else -> when (r.code) {
            401, 403 -> "验证码不对或过期了（$c）"
            404 -> "中继地址不对（没有登录接口）"
            429 -> "发得太频繁了，等一分钟再试"
            in 500..599 -> "中继服务器出错了"
            else -> "中继没接受（HTTP ${r.code} ${c.ifBlank { r.body.take(60) }}）"
        }
    }

    /** Step 1: the relay sends (or, with CODE_SENDER=log, prints in its service log) a 6-digit code. */
    suspend fun relayRequestCode(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val r = PresetHttp.call(relayBase() + "/auth/code", body = JSONObject().put("identifier", email.trim()))
            if (!r.ok) throw MemberError(relayProblem(r), r.body.take(200))
            GroupPrefs.put("nanomuse_email", email.trim())
        }
    }

    /** Pulls the nm_ key out of the verify answer ({"key":"nm_…"} / {"api_key":…} / anything that starts with nm_). */
    fun keyFromVerify(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (o != null) {
            for (k in listOf("key", "api_key", "apiKey", "token", "access_token")) o.optString(k).takeIf { it.startsWith("nm_") || (it.length > 20 && k != "token") }?.let { return it }
            o.optJSONObject("data")?.let { d -> for (k in listOf("key", "api_key", "token")) d.optString(k).takeIf { it.isNotBlank() }?.let { return it } }
        }
        return Regex("nm_[A-Za-z0-9_\\-]{16,}").find(body)?.value
    }

    /** Step 2: code → nm_ key, stored; the 「Muse 中继」 member becomes ready on the next refresh. */
    suspend fun relayVerify(email: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val r = PresetHttp.call(relayBase() + "/auth/verify",
                body = JSONObject().put("identifier", email.trim()).put("code", code.trim()).put("device", "Mines"))
            if (!r.ok) throw MemberError(relayProblem(r), r.body.take(200))
            val k = keyFromVerify(r.body) ?: throw MemberError("中继回了，但没找到密钥", r.body.take(200))
            setKey("nanomuse", k)
            // [Mines-38.9.5 i] verify answers {api_key, base_url, …}: keep the relay's own base (always …/v1)
            r.json()?.optString("base_url")?.takeIf { it.startsWith("http") }?.let { GroupPrefs.put("base_nanomuse", com.openminis.app.plus.RouteStatusCore.relayBase(it)) }
                ?: GroupPrefs.get("base_nanomuse")?.let { GroupPrefs.put("base_nanomuse", com.openminis.app.plus.RouteStatusCore.relayBase(it)) }
            GroupPrefs.put("nanomuse_email", email.trim())
            k
        }
    }

    // ── Memoh ─────────────────────────────────────────────────────────────

    fun memohBase(): String? = GroupPrefs.get("memoh_base")?.trimEnd('/')
    fun memohUser(): String? = GroupPrefs.get("memoh_user")?.let { u ->
        // [Mines-38.9.6 i13] drop 「\u0001\u0001」 saved by the old page-value decode
        if (com.openminis.app.plus.LoginValueCore.badAccount(u)) { GroupPrefs.put("memoh_user", null); null } else u.takeIf { it.isNotBlank() }
    }

    // [Mines-38.9.4] user 2026-10-17: signed in on app.memoh.net inside Mines, 我已登录好了 → still 需要登录. Hosted Memoh
    // (app.memoh.net) has no JWT in localStorage: the session is an HttpOnly cookie and the web UI calls /api/memoh/* and
    // /api/v1/users/me with credentials. So a hosted sign-in is the WebView cookie, checked with GET /api/v1/users/me.
    const val MEMOH_HOSTED = "app.memoh.net"
    fun memohOrigin(): String? = memohBase()?.let { b -> runCatching { java.net.URI(b).let { "${it.scheme}://${it.authority}" } }.getOrNull() }
    fun memohHosted(): Boolean = isMemohHosted(memohBase())
    fun isMemohHosted(url: String?): Boolean = runCatching { java.net.URI(url ?: "").host?.lowercase()?.let { it == MEMOH_HOSTED || it.endsWith(".$MEMOH_HOSTED") } == true }.getOrDefault(false)
    /** API base for a page the user signed in on: hosted → <origin>/api/memoh, self-hosted → <origin>/api. */
    fun memohApiBaseFor(pageUrl: String): String {
        val u = runCatching { java.net.URI(pageUrl.trim()) }.getOrNull()
        val origin = if (u?.scheme != null && u.authority != null) "${u.scheme}://${u.authority}" else pageUrl.trim().trimEnd('/')
        return if (isMemohHosted(pageUrl)) "$origin/api/memoh" else "$origin/api"
    }
    fun memohWebCookie(): String? = memohOrigin()?.let { o -> runCatching { android.webkit.CookieManager.getInstance().getCookie(o) }.getOrNull() }?.takeIf { it.isNotBlank() }
    /** Real probe of the web session: 200 = signed in, 401/403 = signed out, null = could not tell (network). */
    suspend fun memohWebSignedIn(): Boolean? {
        val o = memohOrigin() ?: return false
        val c = memohWebCookie() ?: return false
        return runCatching { PresetHttp.call("$o/api/v1/users/me", cookie = c, readSec = 12).code }.getOrNull()?.let { code ->
            when (code) { in 200..299 -> true; 401, 403 -> false; else -> null }
        }
    }
    /** 应用代理 subtitle: hosted vs self-hosted. */
    fun memohWhere(): String? = memohBase()?.let { if (memohHosted()) "官方托管：$MEMOH_HOSTED" else "自建：" + (runCatching { java.net.URI(it).host }.getOrNull() ?: it) }

    /** The user types the address they open in the browser; the bundled web UI calls the API under /api. */
    fun normalizeMemohBase(input: String): List<String> {
        var u = input.trim().trimEnd('/')
        if (u.isEmpty()) return emptyList()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return if (u.endsWith("/api")) listOf(u, u.removeSuffix("/api")) else listOf("$u/api", u)
    }

    /** Logs in (tries "<url>/api" then "<url>"), stores JWT + password, returns the base that worked. */
    suspend fun memohLogin(input: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var last: MemberError? = null
            for (base in normalizeMemohBase(input)) {
                try {
                    val r = PresetHttp.call("$base/auth/login", body = JSONObject().put("username", user.trim()).put("password", pass))
                    if (r.code == 404 || r.code == 405 || (r.ok && r.json()?.optString("access_token").isNullOrBlank())) {
                        last = MemberError("这个地址不像 Memoh（没有登录接口）", r.body.take(160)); continue
                    }
                    if (r.code == 401 || r.code == 403) throw MemberError("用户名或密码不对", r.body.take(160))
                    if (!r.ok) throw MemberError("Memoh 没接受（HTTP ${r.code}）", r.body.take(160))
                    val jwt = r.json()!!.optString("access_token")
                    GroupPrefs.put("memoh_base", base); GroupPrefs.put("memoh_user", user.trim())
                    PlusVault.setSecretExtra(V, "memoh_pass", pass)
                    setKey(PresetRegistry.MEMOH, jwt)
                    return@runCatching base
                } catch (e: MemberError) {
                    if (e.message?.startsWith("用户名或密码") == true) throw e
                    last = e
                }
            }
            throw last ?: MemberError("地址是空的")
        }
    }

    /**
     * Current JWT; [force] logs in again with the saved password (after a 401).
     * [Mines-38.9.6 i13] the saved account + password are used silently; only when there is none, or the server refused it,
     * the secure login card (LoginCardHost: shared glass sheet, masked + 显示 toggle, FLAG_SECURE) asks — once per process
     * unless the saved password was just refused. Self-hosted only (hosted app.memoh.net signs in on its own web page).
     */
    suspend fun memohToken(force: Boolean = false): String? {
        if (!force) key(PresetRegistry.MEMOH)?.let { return it }
        val base = memohBase() ?: return null
        val user = memohUser()
        val pass = PlusVault.secretExtra(V, "memoh_pass")
        if (user != null && pass != null) {
            val r = memohLogin(base, user, pass)
            if (r.isSuccess) return key(PresetRegistry.MEMOH)
            val refused = (r.exceptionOrNull()?.message ?: "").contains("密码不对")
            return if (refused) memohAskAndLogin(base, refused = true) else null   // network: keep the saved login, no card
        }
        return memohAskAndLogin(base, refused = false)
    }

    @Volatile private var memohAsked = false

    private suspend fun memohAskAndLogin(base: String, refused: Boolean): String? {
        if (memohHosted() || isMemohHosted(base)) return null
        if (memohAsked && !refused) return null
        memohAsked = true
        val host = runCatching { java.net.URI(base).host }.getOrNull().orEmpty()
        var wrong = refused
        repeat(2) {
            val ask = com.openminis.app.plus.LoginMemoryCore.Ask(
                listOf(com.openminis.app.plus.LoginMemoryCore.Field.ACCOUNT, com.openminis.app.plus.LoginMemoryCore.Field.PASSWORD),
                if (wrong) "密码不对，重新输入" else "登录 Memoh", wrong)
            val a = kotlinx.coroutines.withTimeoutOrNull(180_000L) {
                com.openminis.app.plus.LoginCard.ask(com.openminis.app.plus.LoginCard.Req(host, "Memoh", ask, memohUser().orEmpty(), null))
            } ?: return null
            if (a.account.isBlank() || a.password.isEmpty()) return null
            val r = memohLogin(base, a.account, a.password)
            if (r.isSuccess) {
                if (!a.save) PlusVault.setSecretExtra(V, "memoh_pass", null)
                return key(PresetRegistry.MEMOH)
            }
            if (!(r.exceptionOrNull()?.message ?: "").contains("密码不对")) return null
            wrong = true
        }
        return null
    }

    /** 去登录 tapped: the user asked, so the card may show again this process. */
    fun memohAllowAsk() { memohAsked = false }

    fun memohSignOut() {
        setKey(PresetRegistry.MEMOH, null); PlusVault.setSecretExtra(V, "memoh_pass", null)
        GroupPrefs.put("memoh_bots", null)
    }

    fun relaySignOut() = setKey("nanomuse", null)
}

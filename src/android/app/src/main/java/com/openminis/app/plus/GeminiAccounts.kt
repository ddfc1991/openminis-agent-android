package com.openminis.app.plus

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.LLMError
import com.openminis.app.plus.GeminiAccountsCore.ErrKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.11 b] The Google-account pool behind 「Gemini（谷歌账号）」 (see [GeminiAccountsCore] for the protocol).
 *
 * Isolation (user 08:44 「记得把账号隔离做好」): every account is one slot with its OWN token entry in an encrypted store
 * (key tok_<slot>), its own Code Assist project, quota, cool-down and error, and signs in inside its own WebView profile
 * (mines_gca_<slot>: own cookies / storage / cache, see [webProfile]) so one account's Google session never leaks into the
 * next sign-in. Nothing here is shared between slots except the network route.
 *
 * Requests: [transport] → [execute] walks [GeminiAccountsCore.order] (round-robin over usable accounts, then the cooling
 * ones), refreshing tokens, onboarding a fresh account on first use, cooling an account on 429 and marking it 要登录 on a
 * dead refresh token. When Google refuses the region it switches once to a proxy route (分流 / US exit / 系统代理, same
 * candidates as the Gemini voice call) and keeps that route.
 */
object GeminiAccounts {
    private const val TAG = "GeminiAccounts"
    private const val PREFS = "mines_gemini_accounts"
    const val PROFILE_PREFIX = "mines_gca_"
    const val INSTANCE_LABEL = "Gemini（谷歌账号）"

    data class Acct(
        val id: String,
        val email: String = "",
        val signedIn: Boolean = false,
        val needsLogin: Boolean = false,
        val project: String? = null,
        val tierId: String? = null,
        val tierName: String? = null,
        val quota: String = "",
        /** last check / use result in plain Chinese ("" = never checked) */
        val status: String = "",
        val ok: Boolean? = null,
        val coolUntil: Long = 0L,
        val blocked: Boolean = false,
        val disabled: Boolean = false,
        val lastUsedAt: Long = 0L,
        val checkedAt: Long = 0L,
    ) {
        fun slot() = GeminiAccountsCore.Slot(id, email, signedIn, needsLogin, coolUntil, blocked, disabled)
        val title: String get() = email.ifBlank { "未登录的谷歌账号" }
    }

    private val _accounts = MutableStateFlow<List<Acct>>(emptyList())
    val accounts: StateFlow<List<Acct>> = _accounts.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null
    @Volatile private var cursor = 0
    /** route chosen after a region refusal (null = the app's normal routing / ProxySelector) */
    @Volatile var route: Proxy? = null; private set
    @Volatile var routeLabel: String = ""; private set
    private val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    @Volatile private var sp: android.content.SharedPreferences? = null
    private fun prefs(c: Context): android.content.SharedPreferences = sp ?: synchronized(this) {
        sp ?: com.openminis.app.util.EncryptedPrefsFactory.safeCreate(c.applicationContext, PREFS).also { sp = it }
    }

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            app = c.applicationContext
            _accounts.value = runCatching {
                val a = JSONArray(prefs(c).getString("list", "[]"))
                (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun toJson(a: Acct) = JSONObject().put("id", a.id).put("email", a.email).put("in", a.signedIn).put("rl", a.needsLogin)
        .put("p", a.project ?: "").put("t", a.tierId ?: "").put("tn", a.tierName ?: "").put("q", a.quota).put("s", a.status)
        .put("ok", a.ok?.let { if (it) 1 else 0 } ?: -1).put("cu", a.coolUntil).put("b", a.blocked).put("d", a.disabled)
        .put("lu", a.lastUsedAt).put("ca", a.checkedAt)

    private fun fromJson(o: JSONObject) = Acct(o.getString("id"), o.optString("email"), o.optBoolean("in"), o.optBoolean("rl"),
        o.optString("p").ifBlank { null }, o.optString("t").ifBlank { null }, o.optString("tn").ifBlank { null }, o.optString("q"),
        o.optString("s"), when (o.optInt("ok", -1)) { 1 -> true; 0 -> false; else -> null }, o.optLong("cu"), o.optBoolean("b"),
        o.optBoolean("d"), o.optLong("lu"), o.optLong("ca"))

    private fun save(c: Context, l: List<Acct>) {
        _accounts.value = l
        val a = JSONArray(); l.forEach { a.put(toJson(it)) }
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    private fun update(c: Context, id: String, f: (Acct) -> Acct) = synchronized(this) {
        save(c, _accounts.value.map { if (it.id == id) f(it) else it })
    }

    fun get(id: String): Acct? = _accounts.value.firstOrNull { it.id == id }

    fun hasSignedIn(c: Context): Boolean { init(c); return _accounts.value.any { it.signedIn && !it.disabled } }

    /** A new empty slot (「添加谷歌账号」); [hint] = the address the user typed, if any. */
    fun addSlot(c: Context, hint: String = ""): Acct {
        init(c)
        val a = Acct(UUID.randomUUID().toString().take(12), email = hint.trim())
        synchronized(this) { save(c, _accounts.value + a) }
        return a
    }

    /** Remove an account: its tokens, its WebView profile (cookies / storage) and its row. */
    fun remove(c: Context, id: String) {
        init(c)
        prefs(c).edit().remove("tok_$id").apply()
        wipeWebData(id)
        synchronized(this) { save(c, _accounts.value.filterNot { it.id == id }) }
    }

    fun setDisabled(c: Context, id: String, off: Boolean) = update(c, id) { it.copy(disabled = off) }

    // ---------------- per-account WebView profile ----------------

    fun webProfile(id: String): String = PROFILE_PREFIX + id.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** 清除网页数据: drop this account's WebView profile (Google cookies, storage, cache). Tokens stay. */
    fun wipeWebData(id: String): Boolean = runCatching {
        if (!LoginWeb.profilesSupported()) return false
        val ps = androidx.webkit.ProfileStore.getInstance()
        val name = webProfile(id)
        if (ps.getProfile(name) == null) return true
        runCatching { ps.getProfile(name)?.cookieManager?.removeAllCookies(null) }
        runCatching { ps.getProfile(name)?.webStorage?.deleteAllData() }
        runCatching { ps.deleteProfile(name) }.getOrDefault(false)
    }.getOrDefault(false)

    // ---------------- sign-in ----------------

    private class Pending(val slotId: String, val state: String, val verifier: String)
    @Volatile private var pending: Pending? = null

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** The Google sign-in URL for [slotId] (remembers state + PKCE verifier until [finishLogin]). */
    fun beginLogin(c: Context, slotId: String): String {
        init(c)
        val rnd = SecureRandom()
        val v = ByteArray(48).also { rnd.nextBytes(it) }.let(::b64url)
        val st = ByteArray(16).also { rnd.nextBytes(it) }.let(::b64url)
        pending = Pending(slotId, st, v)
        val ch = b64url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
        return GeminiAccountsCore.authUrl(st, ch, get(slotId)?.email?.takeIf { it.contains('@') })
    }

    fun pendingSlot(): String? = pending?.slotId

    /**
     * Code from the redirect (WebView) or pasted from Chrome → tokens for the pending slot, then setup + quota.
     * Returns null on success, else a plain Chinese reason. A Google account already in another slot is merged into that slot.
     */
    suspend fun finishLogin(c: Context, code: String, state: String?): String? = withContext(Dispatchers.IO) {
        val p = pending ?: return@withContext "登录已经过期了，再点一次「登录」"
        if (state != null && state != p.state) return@withContext "登录回来的信息对不上（可能点了旧的登录页），再点一次「登录」"
        val now = System.currentTimeMillis()
        val r = runCatching { post(GeminiAccountsCore.TOKEN_URL, GeminiAccountsCore.tokenForm(code, p.verifier)) }
            .getOrElse { return@withContext "连不上谷歌（${shortErr(it)}）。打开 应用代理 里的美国线路再试" }
        if (r.first !in 200..299) return@withContext "谷歌没给登录凭证（${r.first}）：" + tokenErr(r.second)
        val t = GeminiAccountsCore.parseTokens(r.second, now) ?: return@withContext "谷歌回的登录凭证看不懂，再试一次"
        if (t.refresh == null) return@withContext "谷歌没给长期凭证，请在登录页点「继续 / 允许」后再试"
        pending = null
        val email = t.idEmail ?: runCatching { userEmail(t.access) }.getOrNull().orEmpty()
        var slot = p.slotId
        synchronized(this) {
            val dup = _accounts.value.firstOrNull { it.id != slot && email.isNotBlank() && it.email.equals(email, true) }
            if (dup != null) { // same Google account signed in again from a new slot → refresh the old slot, drop the new one
                save(c, _accounts.value.filterNot { it.id == slot }); wipeWebData(slot); slot = dup.id
            }
        }
        prefs(c).edit().putString("tok_$slot", GeminiAccountsCore.tokenJson(t)).apply()
        update(c, slot) { it.copy(email = email.ifBlank { it.email }, signedIn = true, needsLogin = false, status = "已登录，正在开通…", ok = null, coolUntil = 0L) }
        check(c, slot)
        runCatching { ensureProvider(c) }
        null
    }

    private fun tokenErr(body: String): String = runCatching {
        val o = JSONObject(body); val e = o.optString("error")
        when (e) {
            "invalid_grant" -> "这个登录码已经用过或过期了，再点一次「登录」"
            "redirect_uri_mismatch", "invalid_client" -> "谷歌不认这个登录方式（$e），请更新 Mines"
            else -> o.optString("error_description").ifBlank { e }.take(120)
        }
    }.getOrElse { body.take(120) }

    // ---------------- tokens + setup ----------------

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).writeTimeout(60, TimeUnit.SECONDS).build()
    }
    private fun client(): OkHttpClient = route?.let { baseClient.newBuilder().proxy(it).build() } ?: baseClient

    private fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val fb = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        client().newCall(Request.Builder().url(url).post(fb).header("User-Agent", GeminiAccountsCore.USER_AGENT).build()).execute().use {
            return it.code to (it.body?.string() ?: "")
        }
    }

    private fun postJson(url: String, token: String, body: JSONObject): Pair<Int, String> {
        val rq = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token").header("User-Agent", GeminiAccountsCore.USER_AGENT).build()
        client().newCall(rq).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun userEmail(token: String): String? {
        val rq = Request.Builder().url(GeminiAccountsCore.USERINFO_URL).header("Authorization", "Bearer $token").build()
        client().newCall(rq).execute().use { r -> if (!r.isSuccessful) return null; return JSONObject(r.body?.string() ?: "{}").optString("email").ifBlank { null } }
    }

    private fun shortErr(t: Throwable): String = (t.message ?: t.javaClass.simpleName).take(60)

    /** Valid access token for [id] (refreshes when < 5 min left). Null = needs a new sign-in (slot marked). */
    suspend fun accessToken(c: Context, id: String): String? {
        val lock = refreshLocks.getOrPut(id) { Mutex() }
        return lock.withLock {
            val t = GeminiAccountsCore.tokenFromJson(prefs(c).getString("tok_$id", null)) ?: return@withLock null
            val now = System.currentTimeMillis()
            if (!GeminiAccountsCore.needsRefresh(t, now)) return@withLock t.access
            val rt = t.refresh ?: return@withLock null
            val r = withContext(Dispatchers.IO) { runCatching { post(GeminiAccountsCore.TOKEN_URL, GeminiAccountsCore.refreshForm(rt)) }.getOrNull() }
                ?: return@withLock t.access.takeIf { now < t.expiresAt } // network: keep the old token while it lives
            if (r.first in 200..299) {
                val nt = GeminiAccountsCore.parseTokens(r.second, now, rt) ?: return@withLock null
                prefs(c).edit().putString("tok_$id", GeminiAccountsCore.tokenJson(nt)).apply()
                nt.access
            } else if (r.first == 400 || r.first == 401) {
                update(c, id) { it.copy(needsLogin = true, ok = false, status = "登录过期了，点「登录」重新登录") }
                null
            } else t.access.takeIf { now < t.expiresAt }
        }
    }

    /** loadCodeAssist (+ onboardUser for a fresh account) → project + tier saved on the slot. Null = ok, else reason. */
    private suspend fun setup(c: Context, id: String, token: String): String? = withContext(Dispatchers.IO) {
        val a = get(id) ?: return@withContext "账号不见了"
        val (code, body) = callWithRoute { postJson("${GeminiAccountsCore.API}:loadCodeAssist", token, GeminiAccountsCore.loadBody(a.project)) }
        if (code !in 200..299) return@withContext GeminiAccountsCore.classify(code, body).zh
        val s = GeminiAccountsCore.parseLoad(body)
        if (s.blocked != null) { update(c, id) { it.copy(blocked = true, ok = false, status = s.blocked) }; return@withContext s.blocked }
        if (s.tierId != null && s.project != null) {
            update(c, id) { it.copy(project = s.project, tierId = s.tierId, tierName = s.tierName, blocked = false) }; return@withContext null
        }
        if (s.tierId != null && s.project == null && !s.tierId.contains("free", true))
            return@withContext "这个账号是付费版，要先在 Google Cloud 里选一个项目（设置 GOOGLE_CLOUD_PROJECT）"
        // onboard (free tier needs no project of its own: Google assigns one)
        val tier = s.tierId ?: s.onboardTier ?: "free-tier"
        var project: String? = s.project
        for (i in 0 until 12) {
            val (oc, ob) = postJson("${GeminiAccountsCore.API}:onboardUser", token, GeminiAccountsCore.onboardBody(tier, project))
            if (oc !in 200..299) return@withContext "开通 Gemini 编程助手失败：" + GeminiAccountsCore.classify(oc, ob).zh
            val (done, p) = GeminiAccountsCore.parseOnboard(ob)
            if (done) { project = p ?: project; break }
            kotlinx.coroutines.delay(5_000)
        }
        update(c, id) { it.copy(project = project, tierId = tier, tierName = null, blocked = false) }
        null
    }

    /** Real check of one account: token → setup → quota. Updates the row. */
    suspend fun check(c: Context, id: String) {
        init(c)
        val token = accessToken(c, id)
        if (token == null) {
            update(c, id) { it.copy(ok = false, signedIn = it.signedIn, needsLogin = true, status = if (it.signedIn) "登录过期了，点「登录」重新登录" else "还没登录", checkedAt = System.currentTimeMillis()) }
            return
        }
        val err = runCatching { setup(c, id, token) }.getOrElse { "连不上谷歌（${shortErr(it)}）" }
        if (err != null) { update(c, id) { it.copy(ok = false, status = err, checkedAt = System.currentTimeMillis()) }; return }
        val q = runCatching {
            val p = get(id)?.project
            val body = JSONObject().apply { if (!p.isNullOrBlank()) put("project", p) }
            val (code, b) = withContext(Dispatchers.IO) { postJson("${GeminiAccountsCore.API}:retrieveUserQuota", token, body) }
            if (code in 200..299) GeminiAccountsCore.quotaLine(GeminiAccountsCore.parseQuota(b)) else ""
        }.getOrDefault("")
        update(c, id) { it.copy(ok = true, quota = q, status = "能用 · " + GeminiAccountsCore.tierLabel(it.tierId, it.tierName) +
            (if (routeLabel.isNotBlank()) " · 走$routeLabel" else ""), checkedAt = System.currentTimeMillis(), needsLogin = false) }
    }

    suspend fun checkAll(c: Context) { init(c); _accounts.value.filter { it.signedIn }.forEach { runCatching { check(c, it.id) } } }

    // ---------------- route (region) ----------------

    /** Runs [call]; on a region refusal tries the proxy routes once and keeps the first that is accepted. */
    private fun callWithRoute(call: () -> Pair<Int, String>): Pair<Int, String> {
        val first = runCatching(call)
        val r = first.getOrNull()
        val regionRefused = r != null && GeminiAccountsCore.classify(r.first, r.second).kind == ErrKind.LOCATION
        val netFailed = first.isFailure
        if (!regionRefused && !netFailed) return r!!
        val prev = route; val prevLabel = routeLabel
        val ctx = app
        val routes = runCatching { GeminiRoute.routes(ctx, false) }.getOrDefault(emptyList())
            .filter { !it.label.startsWith("中转") && it.proxy != Proxy.NO_PROXY && it.proxy != prev }
        for (rt in routes) {
            route = rt.proxy; routeLabel = rt.label
            val x = runCatching(call).getOrNull() ?: continue
            if (GeminiAccountsCore.classify(x.first, x.second).kind != ErrKind.LOCATION) { Log.i(TAG, "route → ${rt.label}"); return x }
        }
        route = prev; routeLabel = prevLabel
        return r ?: throw (first.exceptionOrNull() ?: IllegalStateException("network"))
    }

    // ---------------- requests (the pool) ----------------

    fun transport(c: Context): com.openminis.app.provider.gemini.CodeAssistTransport {
        init(c)
        val ctx = c.applicationContext
        return object : com.openminis.app.provider.gemini.CodeAssistTransport {
            override suspend fun execute(modelId: String, request: JSONObject, stream: Boolean): okhttp3.Response = execute(ctx, modelId, request, stream)
        }
    }

    suspend fun execute(c: Context, modelId: String, request: JSONObject, stream: Boolean): okhttp3.Response = withContext(Dispatchers.IO) {
        init(c)
        val now = System.currentTimeMillis()
        val order = GeminiAccountsCore.order(_accounts.value.map { it.slot() }, now, cursor++)
        if (order.isEmpty()) {
            val any = _accounts.value.isNotEmpty()
            throw LLMError.InvalidApiKey(if (any) "谷歌账号都要重新登录：去 AI 服务商 › Gemini（谷歌账号）点「登录」" else "还没登录谷歌账号：去 AI 服务商 › 添加 › Google Gemini › 谷歌账号登录")
        }
        val reasons = ArrayList<String>()
        var quotaOnly = true
        for (s in order) {
            val token = accessToken(c, s.id) ?: run { reasons += "${GeminiAccountsCore.mask(s.email)} 要重新登录"; quotaOnly = false; null } ?: continue
            if (get(s.id)?.project == null) {
                val e = runCatching { setup(c, s.id, token) }.getOrElse { "连不上谷歌（${shortErr(it)}）" }
                if (e != null) { reasons += "${GeminiAccountsCore.mask(s.email)}：$e"; quotaOnly = false; continue }
            }
            val project = get(s.id)?.project
            val body = GeminiAccountsCore.wrap(modelId, project, request, UUID.randomUUID().toString())
            val url = "${GeminiAccountsCore.API}:" + if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            val rq = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token").header("User-Agent", GeminiAccountsCore.USER_AGENT).build()
            var resp: okhttp3.Response? = runCatching { client().newCall(rq).execute() }.getOrNull()
            if (resp == null) {
                // the direct path to Google failed (blocked / reset): switch to a proxy route once, then retry
                runCatching { callWithRoute { postJson("${GeminiAccountsCore.API}:loadCodeAssist", token, GeminiAccountsCore.loadBody(project)) } }
                resp = runCatching { client().newCall(rq).execute() }.getOrNull()
            }
            if (resp == null) { reasons += "连不上谷歌（网络或代理断了）"; quotaOnly = false; continue }
            if (resp.isSuccessful) {
                update(c, s.id) { it.copy(lastUsedAt = System.currentTimeMillis(), ok = true, coolUntil = 0L) }
                return@withContext resp
            }
            val code = resp.code; val eb = resp.body?.string().orEmpty(); resp.close()
            var err = GeminiAccountsCore.classify(code, eb)
            if (err.kind == ErrKind.LOCATION) {
                runCatching { callWithRoute { postJson("${GeminiAccountsCore.API}:loadCodeAssist", token, GeminiAccountsCore.loadBody(project)) } }
                val again = runCatching { client().newCall(rq).execute() }.getOrNull()
                if (again != null && again.isSuccessful) {
                    update(c, s.id) { it.copy(lastUsedAt = System.currentTimeMillis(), ok = true, coolUntil = 0L) }
                    return@withContext again
                }
                if (again != null) { err = GeminiAccountsCore.classify(again.code, again.body?.string().orEmpty()); again.close() }
                if (err.kind == ErrKind.LOCATION) throw LLMError.ProviderError(
                    if (route == null) "Gemini 不对你现在的网络地区开放，也没找到能用的美国线路：在 应用代理 打开内置 Clash 或加一个美国出口后再试"
                    else "Gemini 不对你现在的网络地区开放，换到「$routeLabel」也被拒了：换一个美国出口再试", code)
            }
            when (err.kind) {
                ErrKind.QUOTA -> update(c, s.id) { it.copy(coolUntil = System.currentTimeMillis() + err.retryAfterMs.coerceIn(30_000L, 6 * 3600_000L), ok = false, status = err.zh) }
                ErrKind.AUTH -> update(c, s.id) { it.copy(needsLogin = true, ok = false, status = err.zh) }
                ErrKind.PROJECT -> update(c, s.id) { it.copy(project = null, ok = false, status = err.zh) }
                else -> {}
            }
            if (!err.rotate) {
                if (err.kind == ErrKind.TRANSIENT) throw LLMError.TransientError(err.zh, code)
                throw LLMError.ProviderError(err.zh, code)
            }
            if (err.kind != ErrKind.QUOTA) quotaOnly = false
            reasons += "${GeminiAccountsCore.mask(s.email)}：${err.zh}"
        }
        val line = "所有谷歌账号都暂时用不了：" + reasons.distinct().take(3).joinToString("；")
        if (quotaOnly) throw LLMError.RateLimited(line) else throw LLMError.ProviderError(line, 503)
    }

    // ---------------- provider entry ----------------

    /** Make sure 「Gemini（谷歌账号）」 exists as a provider (so it can be picked in 模型组). Returns its instance id. */
    fun ensureProvider(c: Context): String? {
        val repo = (c.applicationContext as? com.openminis.app.MinisApp)?.providerRepositoryOrNull ?: return null
        val have = repo.config.value.instances.firstOrNull {
            it.providerType == com.openminis.app.data.model.ProviderType.gemini && it.credentialType == com.openminis.app.data.model.ProviderCredential.oauth
        }
        if (have != null) return have.id
        val inst = com.openminis.app.data.model.ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = INSTANCE_LABEL,
            providerType = com.openminis.app.data.model.ProviderType.gemini,
            credentialType = com.openminis.app.data.model.ProviderCredential.oauth,
        )
        repo.addInstance(inst)
        return inst.id
    }

    /** Opens the 谷歌账号 page (own activity, no NavHost route needed). */
    fun open(c: Context, addNew: Boolean = false) {
        val i = Intent(c, com.openminis.app.ui.plus.GeminiAccountsActivity::class.java).putExtra("add", addNew)
        if (c !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }
}

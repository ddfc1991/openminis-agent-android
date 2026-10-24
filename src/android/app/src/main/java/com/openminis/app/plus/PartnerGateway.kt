package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.plus.group.AiClient
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.MemberError
import com.openminis.app.plus.group.Partner
import com.openminis.app.plus.group.Partners
import com.openminis.app.plus.group.PresetAuth
import com.openminis.app.plus.group.Proto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:50: the free login-based routes (扣子 → PAT, Muse 中继 → nm_ key, Memoh → JWT /
 * web cookie, the web-chat bridges that drive a signed-in page) could not be called from 模型组 — they only existed as
 * 「AI 伙伴」 members, 模型组 had no provider for them. This serves every one of them on 127.0.0.1:[PORT] as an
 * OpenAI-compatible endpoint and keeps one provider 「免登录 AI（Mines）」 in 模型组 pointing at it. The sign-in stays in
 * Mines (vault / WebView session); a failed call answers with the route's own plain Chinese reason (no silent
 * swallowing), and an expired relay key is renewed by itself with a code read from the user's linked mailbox.
 * Also: [status] = one truthful row per route + every self-hosted gateway in 模型组 (e.g. a new-api on the user's
 * server answering 502 after a reinstall is said plainly; the server is not touched).
 */
object PartnerGateway {
    const val PORT = 8793   // [i] 8791 is DshWidgets (minis-dsh web); 8790 LLM bridge, 8792 LinkBridge
    private const val PREFS = "mines_partner_gateway"
    private const val KEY = "mines-local"
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "partner-gw").apply { isDaemon = true } }
    private val _rows = MutableStateFlow<List<RouteStatusCore.Row>>(emptyList())
    val rows: StateFlow<List<RouteStatusCore.Row>> = _rows.asStateFlow()
    @Volatile private var app: Context? = null

    /** The login-based routes (not plain API-key providers). */
    fun isLoginRoute(p: Partner): Boolean = p.spec.proto == Proto.COZE || p.spec.proto == Proto.WEBAPP || p.spec.proto == Proto.ADAPTER ||
        p.id == "nanomuse" || p.id.startsWith("memoh") || p.id.startsWith("coze") || p.id.startsWith("web:")

    // ---------------- server ----------------

    fun ensure(c: Context) {
        app = c.applicationContext
        if (server?.isClosed == false) return
        synchronized(this) {
            if (server?.isClosed == false) return
            runCatching {
                val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
                server = ss
                Thread({ while (!ss.isClosed) { val s = runCatching { ss.accept() }.getOrNull() ?: break; pool.execute { handle(s) } } }, "partner-gw-accept")
                    .apply { isDaemon = true }.start()
            }
        }
    }

    /**
     * Called once per process from MinisApp.onCreate (IO thread): opens the port, waits for 模型组 to load, adds the
     * provider row when at least one login route exists (never re-adds one the user deleted), then reads every route's
     * status so the 模型组 card is filled before the user opens it.
     */
    suspend fun startAtLaunch(c: Context) {
        ensure(c)
        val mapp = c.applicationContext as? MinisApp
        var waited = 0
        while (mapp?.providerRepositoryOrNull == null && waited < 30) { delay(1000); waited++ }
        val repo = mapp?.providerRepositoryOrNull ?: return
        waited = 0
        while (!repo.configLoaded.value && waited < 30) { delay(1000); waited++ }
        delay(4000) // let the session / keychain restore finish first
        // 百炼: a workspace key (sk-ws-…) saved on a provider that still points at the legacy dashscope host → workspace endpoint
        runCatching {
            repo.config.value.instances.filter { it.customBaseURL?.contains("dashscope.aliyuncs.com") == true }.forEach { inst ->
                val k = runCatching { repo.loadApiKey(inst.id) }.getOrNull()
                if (RouteStatusCore.bailianWorkspaceKey(k)) {
                    repo.updateInstance(inst.copy(customBaseURL = RouteStatusCore.bailianBase(k, inst.customBaseURL, GroupPrefs.get("bailian_ws")), appendV1Suffix = false))
                    repo.triggerAsyncModelReconcile(inst.id, forceRefresh = true)
                }
            }
        }
        val rows = runCatching { refreshStatus(c) }.getOrDefault(emptyList())
        // [Mines-38.9.8] live-test the routes (≤ once per 12 h each) so dead ones leave 模型组; never blocks the launch
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { delay(20_000); runCatching { FreeRoutes.testAll(c) } }
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // [Mines-38.9.5 i] smoke 39037975665: auto-adding at first launch put an EMPTY 「免登录 AI（Mines）」 first in onboarding's
        // 「Select Models」 (fresh install, nothing signed in) so the user's own provider's models never showed → onboarding
        // could not finish. Auto-add only after onboarding (another provider exists) and once a route is actually usable.
        val usable = rows.any { !it.id.startsWith("inst:") && (it.st == RouteStatusCore.St.OK || it.st == RouteStatusCore.St.WARN) }
        val onboarded = repo.config.value.instances.any { it.id != prefs.getString("inst", null) }
        if (usable && onboarded && !prefs.getBoolean("user_removed", false)) runCatching { ensureProvider(c, auto = true) }
    }

    /** Adds / keeps the 「免登录 AI（Mines）」 provider in 模型组 (once; the user may rename or disable it). */
    fun ensureProvider(c: Context, auto: Boolean = false): String {
        ensure(c)
        val repo = (c.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return "供应商还没加载"
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("inst", null)?.let { id ->
            if (repo.instance(id) != null) { repo.triggerAsyncModelReconcile(id, forceRefresh = true); return "已在模型组" }
            // the row was there and is gone → the user deleted it; only an explicit tap adds it back
            if (auto) { prefs.edit().putBoolean("user_removed", true).apply(); return "已删除（不自动加回）" }
        }
        prefs.edit().putBoolean("user_removed", false).apply()
        val inst = ProviderInstance(
            id = UUID.randomUUID().toString(), label = "免登录 AI（Mines）", providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey, customBaseURL = "http://127.0.0.1:$PORT/v1", appendV1Suffix = true,
        )
        repo.addInstance(inst)
        repo.saveApiKey(inst.id, KEY)
        repo.triggerAsyncModelReconcile(inst.id, forceRefresh = true)
        prefs.edit().putString("inst", inst.id).apply()
        return "已加到模型组"
    }

    fun providerAdded(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("inst", null)
        ?.let { id -> (c.applicationContext as? MinisApp)?.providerRepositoryOrNull?.instance(id) != null } == true

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun read(s: Socket): Req? {
        val inp = BufferedInputStream(s.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return null; head.append(b.toChar()); if (head.length > 16_384) return null }
        val lines = head.toString().split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) return null
        val h = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len); var got = 0
        while (got < len) { val n = inp.read(buf, got, len - got); if (n < 0) break; got += n }
        return Req(first[0], first[1].substringBefore('?'), h, String(buf, 0, got, Charsets.UTF_8))
    }

    private fun send(out: OutputStream, code: Int, body: String, type: String = "application/json") {
        val b = body.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(b); out.flush()
    }

    private fun err(out: OutputStream, code: Int, msg: String) =
        send(out, code, JSONObject().put("error", JSONObject().put("message", msg).put("type", "mines_route").put("code", code)).toString())

    private fun handle(s: Socket) = s.use { sock ->
        runCatching {
            sock.soTimeout = 15_000
            val r = read(sock) ?: return@runCatching
            val out = sock.getOutputStream()
            if (r.headers["authorization"]?.removePrefix("Bearer ")?.trim() != KEY) { err(out, 401, "本机网关只给 Mines 自己用"); return@runCatching }
            val c = app ?: run { err(out, 503, "Mines 还没准备好"); return@runCatching }
            when {
                r.method == "GET" && r.path.endsWith("/models") -> {
                    // [Mines-38.9.8] only routes that answered their live test (or are not tested yet); same names made distinct
                    val list = runBlocking { routes(c) }.filter { FreeRoutes.visible(c, it.id) }
                    val names = FreeRouteCore.distinctNames(list.map { it.id to it.spec.name })
                    send(out, 200, JSONObject().put("object", "list").put("data", JSONArray(list.map { p ->
                        JSONObject().put("id", p.id).put("object", "model").put("owned_by", "mines").put("name", names[p.id] ?: p.spec.name) })).toString())
                }
                r.method == "POST" && r.path.endsWith("/chat/completions") -> chat(c, r.body, out, sock)
                else -> err(out, 404, "没有这个接口")
            }
        }
    }

    /** [Mines-38.9.8] the login routes that are signed in (input of the live test) */
    suspend fun readyRoutes(c: Context): List<Partner> = routes(c, refresh = true)

    private suspend fun routes(c: Context, refresh: Boolean = false): List<Partner> {
        val l = if (refresh || Partners.list.value.isEmpty()) runCatching { Partners.refresh(c) }.getOrDefault(Partners.list.value) else Partners.list.value
        return l.filter { isLoginRoute(it) && it.state == Partner.State.READY }
    }

    private fun text(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text") }.joinToString("\n")
        else -> ""
    }

    private fun chat(c: Context, body: String, out: OutputStream, sock: Socket) {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return err(out, 400, "请求不是 JSON")
        val model = o.optString("model")
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        val system = (0 until msgs.length()).mapNotNull { msgs.optJSONObject(it) }.filter { it.optString("role") == "system" || it.optString("role") == "developer" }
            .joinToString("\n") { text(it.opt("content")) }.take(4000)
        val turns = (0 until msgs.length()).mapNotNull { msgs.optJSONObject(it) }.filter { it.optString("role") == "user" || it.optString("role") == "assistant" }
            .map { AiClient.Turn(it.optString("role"), text(it.opt("content"))) }.filter { it.text.isNotBlank() }
        if (turns.isEmpty()) return err(out, 400, "没有要发的内容")
        sock.soTimeout = 0
        val result: Result<AiClient.Reply> = runBlocking {
            val p = Partners.find(model) ?: routes(c, refresh = true).firstOrNull { it.id == model }
                ?: return@runBlocking Result.failure(MemberError(statusOf(model)?.reason?.ifBlank { null } ?: "这个免登录模型现在不能用（没登录或已过期）：在模型组顶部的「免登录 AI」里看原因"))
            // [Mines-38.9.8] real phone: a web route that never answers left the caller on 「测试中…」 — 90 s limit with a reason
            val call = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { callWithRelogin(c, p, system, turns, o.optInt("max_tokens", 2000).coerceIn(64, 8000)) }
            kotlinx.coroutines.withTimeoutOrNull(FreeRouteCore.CHAT_TIMEOUT_MS) { call.await() }
                ?: Result.failure<AiClient.Reply>(MemberError(FreeRouteCore.timeoutReason(FreeRouteCore.CHAT_TIMEOUT_MS))).also { call.cancel() }
        }
        result.fold({ rep ->
            val id = "chatcmpl-" + UUID.randomUUID().toString().take(12)
            if (o.optBoolean("stream", false)) {
                val sb = StringBuilder()
                fun chunk(d: JSONObject, fin: String?) = sb.append("data: ").append(JSONObject().put("id", id).put("object", "chat.completion.chunk").put("model", model)
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", d).put("finish_reason", fin ?: JSONObject.NULL)))).append("\n\n")
                chunk(JSONObject().put("role", "assistant").put("content", rep.text), null)
                chunk(JSONObject(), "stop")
                sb.append("data: [DONE]\n\n")
                send(out, 200, sb.toString(), "text/event-stream")
            } else send(out, 200, JSONObject().put("id", id).put("object", "chat.completion").put("model", model)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", JSONObject().put("role", "assistant").put("content", rep.text)).put("finish_reason", "stop")))
                .put("usage", JSONObject().put("prompt_tokens", rep.inTok).put("completion_tokens", rep.outTok).put("total_tokens", rep.inTok + rep.outTok)).toString())
            mark(model, RouteStatusCore.St.OK, "")
            FreeRoutes.record(c, FreeRouteCore.Live(model, true, "", System.currentTimeMillis()))
        }, { e ->
            val why = (e as? MemberError)?.message ?: e.message ?: "没有回复"
            FreeRoutes.record(c, FreeRouteCore.Live(model, false, why.take(80), System.currentTimeMillis()))
            val name = Partners.find(model)?.spec?.name ?: model
            mark(model, if (Regex("登录|过期|失效|密钥").containsMatchIn(why)) RouteStatusCore.St.RELOGIN else RouteStatusCore.St.WARN, why)
            err(out, 502, "$name：$why")
        })
    }

    /** One call; a refused relay key is renewed once with a code read from the user's own mailbox, then retried. */
    private suspend fun callWithRelogin(c: Context, p: Partner, system: String, turns: List<AiClient.Turn>, maxTokens: Int): Result<AiClient.Reply> {
        val first = runCatching { AiClient.reply(p, system, turns, maxTokens) }
        val e = first.exceptionOrNull() ?: return first
        val msg = (e.message ?: "") + " " + ((e as? MemberError)?.detail ?: "")
        if (p.id == "nanomuse" && Regex("bad_key|account_deleted|401").containsMatchIn(msg)) {
            val renewed = relayAutoRelogin(c)
            if (renewed == null) {
                val p2 = runCatching { Partners.refresh(c) }.getOrNull()?.firstOrNull { it.id == "nanomuse" } ?: p
                return runCatching { AiClient.reply(p2, system, turns, maxTokens) }
            }
            return Result.failure(MemberError("中继密钥失效了，自动重新登录没成：$renewed"))
        }
        return first
    }

    /**
     * Muse 中继: ask for a code to the saved e-mail and read it from the user's linked mailboxes (≤ 2 min). null = renewed,
     * else the reason (no mailbox linked / code did not come / relay refused).
     */
    suspend fun relayAutoRelogin(c: Context): String? = withContext(Dispatchers.IO) {
        val email = PresetAuth.relayEmail() ?: return@withContext "不知道登录用的邮箱：在 AI 伙伴 › Muse 中继 里登录一次"
        if (CodeSources.enabledBoxes(c).isEmpty()) return@withContext "没有可读验证码的邮箱：在 AI 服务 › 验证码来源 打开一个"
        val since = System.currentTimeMillis() - 5_000
        PresetAuth.relayRequestCode(email).exceptionOrNull()?.let { return@withContext it.message ?: "中继没发验证码" }
        var code: String? = null
        val t0 = System.currentTimeMillis()
        while (code == null && System.currentTimeMillis() - t0 < 120_000) { delay(6000); code = runCatching { CodeSources.find(c, since, "")?.code }.getOrNull() }
        code ?: return@withContext "2 分钟内邮箱里没收到中继的验证码（中继若设成「验证码写日志」就收不到邮件）"
        PresetAuth.relayVerify(email, code).exceptionOrNull()?.let { return@withContext it.message ?: "验证码没通过" }
        null
    }

    // ---------------- status for 模型组 ----------------

    private fun mark(id: String, st: RouteStatusCore.St, why: String) {
        _rows.value = _rows.value.map { if (it.id == id) it.copy(st = st, reason = why) else it }
    }

    fun statusOf(id: String): RouteStatusCore.Row? = _rows.value.firstOrNull { it.id == id }

    /** Re-reads every login route (AI 伙伴 state) + probes each self-hosted gateway provider in 模型组 (GET …/models). */
    suspend fun refreshStatus(c: Context): List<RouteStatusCore.Row> = withContext(Dispatchers.IO) {
        GroupPrefs.init(c)
        val parts = runCatching { Partners.refresh(c) }.getOrDefault(Partners.list.value).filter { isLoginRoute(it) }
        val rows = parts.map { p ->
            val auto = p.id == "nanomuse" || p.id.startsWith("memoh") || p.id.startsWith("coze") || p.spec.proto == Proto.WEBAPP
            RouteStatusCore.fromPartner(p.id, p.spec.name, p.via.ifBlank { when (p.spec.proto) { Proto.WEBAPP -> "网页登录"; Proto.COZE -> "扣子令牌"; else -> "登录" } },
                p.state.name, p.reason, auto)
        }.toMutableList()
        val repo = (c.applicationContext as? MinisApp)?.providerRepositoryOrNull
        runCatching {
            repo?.config?.value?.instances?.filter { it.isEnabled }?.forEach { inst ->
                val base = inst.effectiveBaseURL ?: return@forEach
                val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
                if (host == "127.0.0.1" || !RouteStatusCore.selfHostedGateway(host)) return@forEach
                val key = runCatching { repo.loadApiKey(inst.id) }.getOrNull()?.trim()
                val code = runCatching {
                    val u = java.net.URL(base.trimEnd('/') + "/models").openConnection() as java.net.HttpURLConnection
                    u.connectTimeout = 6000; u.readTimeout = 8000
                    if (!key.isNullOrBlank()) u.setRequestProperty("Authorization", "Bearer $key")
                    u.responseCode
                }.getOrDefault(0)
                val st = when { code in 200..299 -> RouteStatusCore.St.OK; code == 401 || code == 403 -> RouteStatusCore.St.RELOGIN; code == 0 -> RouteStatusCore.St.DOWN; else -> RouteStatusCore.St.DOWN }
                rows += RouteStatusCore.Row("inst:" + inst.id, inst.label, "模型组 · $host", st, if (st == RouteStatusCore.St.OK) "" else RouteStatusCore.httpReason(code, "", host))
            }
        }
        _rows.value = rows
        rows
    }
}

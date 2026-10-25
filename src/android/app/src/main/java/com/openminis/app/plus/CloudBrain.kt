package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * [Mines-38.10 cloud_brain] 「云端执行」: a task runs on the user's own server (mines-brain) instead of in the app, so
 * freezing the app, locking the phone, losing the network or killing Mines never kills the work.
 *
 * Link: the app's existing SSH session to the server ([VpsSsh]) with a local port forward to the brain's
 * 127.0.0.1:8790 — no new public port, no new security-group rule. The token is read once over that same SSH session
 * and kept in the app vault ([PlusVault] encrypted extra `cloudbrain` / serverId, not a visible vault entry).
 *
 * Progress: /v1/events long-poll with a resume cursor, so a reconnect picks up exactly where the phone left off
 * (this is what the old in-app runs could not do: "Software caused connection abort" lost everything).
 * 限制层: the brain refuses send / post / delete / pay and any account the user has not allowed; the refusal arrives
 * as an `ask` event → the task goes to 等你处理 and waits for the user's 同意 / 拒绝 from the phone.
 */
object CloudBrain {
    private const val FILE = "mines_cloud_brain"
    private const val TAG = "CloudBrain"

    @Volatile private var app: Context? = null
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _server = MutableStateFlow<String?>(null)
    /** name of the server the brain was reached on (null = not reachable yet) */
    val server: StateFlow<String?> = _server.asStateFlow()
    private val _err = MutableStateFlow<String?>(null)
    val err: StateFlow<String?> = _err.asStateFlow()
    private val _models = MutableStateFlow(0)
    val models: StateFlow<Int> = _models.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var cursor: Long = 0
    @Volatile private var pumping = false
    @Volatile var lastPage: CloudBrainCore.Page? = null      // smoke / 自检
        private set

    val loaded: Boolean get() = app != null
    fun enabled(): Boolean = loaded && _on.value
    /** the brain is reachable right now */
    fun ready(): Boolean = enabled() && _server.value != null

    fun init(c: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _on.value = p.getBoolean("on", true)
            cursor = p.getLong("cursor", 0)
            app = c.applicationContext
        }
        scope.launch { probe() }
        pump()
    }

    private fun prefs() = app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setOn(c: Context, v: Boolean) {
        init(c); _on.value = v; prefs()?.edit()?.putBoolean("on", v)?.apply()
        if (v) { scope.launch { probe() }; pump() }
    }

    // ── link ──

    /** the server the brain lives on: the one whose SSH login works and that answers /v1/health */
    private fun candidates(): List<VpsServer> = PlusPrefs.state.value.servers.filter { it.enabled && it.authorized }

    private fun tokenOf(s: VpsServer): String? { app?.let { PlusVault.init(it) }; return PlusVault.secretExtra("cloudbrain", s.id) }

    /** one HTTP call to the brain through an SSH local port forward; the session is VpsSsh's cached one */
    private fun call(s: VpsServer, method: String, path: String, body: String?, token: String, timeoutMs: Int = 70_000): Pair<Int, String> {
        val c = app ?: throw IOException("no context")
        val (sess, _) = VpsSsh.session(c, s)
        val lp = synchronized(forwards) {
            forwards[s.id]?.takeIf { it.first === sess }?.second
                ?: sess.setPortForwardingL(0, "127.0.0.1", CloudBrainCore.REMOTE_PORT).also { forwards[s.id] = sess to it }
        }
        val url = java.net.URL("http://127.0.0.1:$lp$path")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray()) } }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally { conn.disconnect() }
    }

    private val forwards = HashMap<String, Pair<com.jcraft.jsch.Session, Int>>()

    /** forget the forward; the SSH session itself is dropped only when it is dead (other Mines pages share it) */
    private fun dropForward(id: String) {
        val f = synchronized(forwards) { forwards.remove(id) } ?: return
        runCatching { f.first.delPortForwardingL(f.second) }
        if (!f.first.isConnected) VpsSsh.drop(id)
    }

    /** find the server running mines-brain, read its token once, sync the user's models */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        val c = app ?: return@withContext false
        if (!_on.value) { _server.value = null; return@withContext false }
        var lastErr: String? = null
        for (s in candidates()) {
            try {
                var token = tokenOf(s)
                if (token == null) {
                    val (r, _) = VpsSsh.run(c, s, CloudBrainCore.TOKEN_CMD, 15_000, attempts = 2)
                    token = CloudBrainCore.cleanToken(r.output) ?: continue
                    PlusVault.setSecretExtra("cloudbrain", s.id, token)
                }
                val (code, body) = call(s, "GET", "/v1/info", null, token, 20_000)
                if (code != 200) { lastErr = "HTTP $code"; if (code == 401) PlusVault.setSecretExtra("cloudbrain", s.id, null); continue }
                val o = JSONObject(body)
                _server.value = s.name.ifBlank { s.host }
                serverId = s.id
                _models.value = o.optJSONArray("models")?.length() ?: 0
                _err.value = null
                if (_models.value == 0) runCatching { syncProviders() }
                return@withContext true
            } catch (t: Throwable) {
                lastErr = VpsSsh.reason(t); dropForward(s.id)
            }
        }
        _server.value = null
        _err.value = lastErr ?: (if (candidates().isEmpty()) "还没有已登录的服务器" else "连不上")
        false
    }

    @Volatile private var serverId: String = ""
    private fun current(): Pair<VpsServer, String>? {
        val s = PlusPrefs.state.value.servers.firstOrNull { it.id == serverId } ?: return null
        val t = tokenOf(s) ?: return null
        return s to t
    }

    /** the user's own models, as the brain will call them (OpenAI-compatible endpoints only) */
    suspend fun syncProviders(): Int = withContext(Dispatchers.IO) {
        val c = app as? com.openminis.app.MinisApp ?: return@withContext 0
        val (s, token) = current() ?: return@withContext 0
        val repo = c.providerRepository
        val out = ArrayList<CloudBrainCore.Prov>()
        for (e in repo.allVisibleEntries()) {
            if (!ErrZhCore.chatCapable(e.baseModel.id)) continue
            val inst = repo.instance(e.providerInstanceId) ?: continue
            if (!inst.isEnabled || inst.useResponsesAPI) continue
            val type = inst.providerType.name
            if (type != "openAI" && type != "openRouter" && type != "xAI") continue     // OpenAI-compatible only
            val base = inst.effectiveBaseURL ?: CloudBrainCore.defaultBase(type) ?: continue
            val key = repo.usableApiKey(inst) ?: continue
            out += CloudBrainCore.Prov(inst.label.ifBlank { e.baseModel.id }, base, key, e.baseModel.id, inst.customUserAgent.orEmpty())
        }
        if (out.isEmpty()) return@withContext 0
        val (code, body) = call(s, "POST", "/v1/providers", CloudBrainCore.providersJson(out), token, 20_000)
        val n = if (code == 200) JSONObject(body).optInt("count") else 0
        _models.value = n
        n
    }

    // ── tasks ──

    /** hand a task to the cloud; returns the brain's task id (null = it stays on the phone) */
    suspend fun submit(t: AutoTask): String? = withContext(Dispatchers.IO) {
        if (!enabled() || CloudBrainCore.phoneOnly(t.goal)) return@withContext null
        if (_server.value == null && !probe()) return@withContext null
        val (s, token) = current() ?: return@withContext null
        runCatching {
            if (_models.value == 0) syncProviders()
            val (code, body) = call(s, "POST", "/v1/tasks", CloudBrainCore.submitJson(t.goal, t.name, CloudBrainCore.clientRef(t.id)), token, 30_000)
            if (code !in 200..299) { _err.value = "HTTP $code"; return@withContext null }
            JSONObject(body).optString("id").takeIf { it.isNotBlank() }   // ChatHome maps it, then starts the pump
        
        }.onFailure { _err.value = VpsSsh.reason(it); dropForward(s.id) }.getOrNull()
    }

    suspend fun reply(cloudId: String, text: String, waitingApproval: Boolean): Boolean = withContext(Dispatchers.IO) {
        val (s, token) = current() ?: return@withContext false
        val v = CloudBrainCore.verbOf(text, waitingApproval)
        runCatching {
            val (path, body) = when (v) {
                CloudBrainCore.Verb.CANCEL -> "/v1/tasks/$cloudId/cancel" to "{}"
                CloudBrainCore.Verb.ALLOW -> "/v1/tasks/$cloudId/decide" to JSONObject().put("allow", true).toString()
                CloudBrainCore.Verb.DENY -> "/v1/tasks/$cloudId/decide" to JSONObject().put("allow", false).toString()
                CloudBrainCore.Verb.TEXT -> "/v1/tasks/$cloudId/reply" to JSONObject().put("text", text).toString()
            }
            val (code, _) = call(s, "POST", path, body, token, 30_000)
            pump()
            code in 200..299
        }.onFailure { dropForward(s.id) }.getOrDefault(false)
    }

    suspend fun cancel(cloudId: String): Boolean = reply(cloudId, "取消", false)

    // ── event pump: one long poll, resumed from [cursor] ──

    fun pump() {
        if (!enabled() || pumping) return
        scope.launch {
            lock.withLock {
                if (pumping) return@withLock
                pumping = true
                var fails = 0
                try {
                    while (enabled() && anyOpen()) {
                        val cur = current()
                        if (cur == null) { if (!probe()) { delay(CloudBrainCore.backoffMs(++fails)); continue } else { fails = 0; continue } }
                        val (s, token) = cur
                        // a submit in flight: its first events must not slip past the cursor before the task knows its id
                        if (ChatHome.tasks.value.any { it.cloud == "…" }) { delay(1_000); continue }
                        try {
                            val (code, body) = call(s, "GET", "/v1/events?after=$cursor&wait=25", null, token, 40_000)
                            if (code != 200) throw IOException("HTTP $code")
                            fails = 0
                            val page = CloudBrainCore.parsePage(body, cursor)
                            lastPage = page
                            if (page.events.isNotEmpty()) {
                                deliver(page.events)
                                cursor = page.cursor
                                prefs()?.edit()?.putLong("cursor", cursor)?.apply()
                            }
                        } catch (t: Throwable) {
                            dropForward(s.id)
                            _err.value = VpsSsh.reason(t)
                            delay(CloudBrainCore.backoffMs(++fails))
                        }
                    }
                } finally { pumping = false }
            }
        }
    }

    /** cloud events → the task's state, its conversation rows, and the 聊天 notice ChatHome already posts */
    private suspend fun deliver(events: List<CloudBrainCore.Event>) {
        val c = app as? com.openminis.app.MinisApp ?: return
        for (e in events) {
            val t = ChatHome.tasks.value.firstOrNull { it.cloud == e.task } ?: continue
            CloudBrainCore.row(e)?.let { line ->
                runCatching {
                    c.chatRepository.appendMessage(t.sid, "assistant",
                        org.json.JSONArray().put(JSONObject().put("type", "text").put("value", line)).toString())
                }
                withContext(Dispatchers.Main) { runCatching { com.openminis.app.ui.chat.ChatViewModelStore.existing(t.sid)?.minesReload() } }
            }
            ChatHome.applyCloud(t.id) { CloudBrainCore.apply(it, e, System.currentTimeMillis()) }
        }
    }

    /** one catch-up read (CloudBrainJob, app in the background or just started by the job): deliver what happened */
    suspend fun catchUpOnce(): Int = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext 0
        if (pumping || ChatHome.tasks.value.any { it.cloud == "…" }) { delay(30_000); return@withContext 0 }   // the live pump delivers
        if (current() == null && !probe()) return@withContext 0
        val (s, token) = current() ?: return@withContext 0
        lock.withLock {
            runCatching {
                var n = 0
                while (true) {
                    val (code, body) = call(s, "GET", "/v1/events?after=$cursor", null, token, 20_000)
                    if (code != 200) break
                    val page = CloudBrainCore.parsePage(body, cursor)
                    if (page.events.isEmpty()) break
                    deliver(page.events); n += page.events.size
                    cursor = page.cursor; prefs()?.edit()?.putLong("cursor", cursor)?.apply()
                    if (!page.more) break
                }
                n
            }.onFailure { dropForward(s.id) }.getOrDefault(0)
        }
    }

    /** any cloud task still open (the job keeps checking while there is) */
    fun anyOpen(): Boolean = ChatHome.tasks.value.any { it.cloud.isNotEmpty() && it.st != St.DONE && it.st != St.ERROR }

    /** smoke / 自检: drop the link as a frozen app / VPN switch would (the next read reconnects and resumes) */
    fun dropLink() { serverId.takeIf { it.isNotEmpty() }?.let { dropForward(it) } }

    /** smoke / 自检 */
    fun stateJson(): String = JSONObject().put("on", _on.value).put("server", _server.value ?: "").put("models", _models.value)
        .put("err", _err.value ?: "").put("cursor", cursor).put("pumping", pumping)
        .put("last", lastPage?.events?.size ?: -1).toString()
}

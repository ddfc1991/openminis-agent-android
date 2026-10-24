package com.openminis.app.plus

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * [Mines-link-31] The phone's outbound HTTPS-looking channel to a server's
 * mines-link worker (hark-build/vps-worker). One wss:// connection per
 * server, heartbeat every 15 s, automatic reconnect (1 → 15 s backoff and at
 * once on a network change), and resumable job output: each job's byte
 * offset is remembered, so after a drop the stream continues exactly where
 * it broke while the job itself keeps running on the server.
 *
 * The brain stays on the phone: the worker only executes.
 * Pairing = the 连接码 the installer prints:
 *   mines-link://IP:PORT/mines#token=…&pin=<sha256 of the server cert>
 */
object MinesLink {
    private const val TAG = "MinesLink"

    data class Code(val url: String, val token: String, val pin: String)

    fun parse(code: String): Code? = runCatching {
        val c = code.trim()
        require(c.startsWith("mines-link://"))
        val main = c.removePrefix("mines-link://").substringBefore('#')
        val frag = c.substringAfter('#', "")
        val kv = frag.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        Code("wss://$main", kv["token"]!!, kv["pin"]!!.lowercase().replace(":", ""))
    }.getOrNull()

    enum class State { OFF, CONNECTING, ONLINE, RETRYING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = ConcurrentHashMap<String, Conn>()
    private val _states = MutableStateFlow<Map<String, Pair<State, String>>>(emptyMap())
    /** serverId → (state, plain-words detail). */
    val states: StateFlow<Map<String, Pair<State, String>>> = _states.asStateFlow()

    /** [Mines-link-32] App context, so a link can fall back to an SSH port-forward (no new port to open). */
    @Volatile var appCtx: Context? = null
    fun init(context: Context) { if (appCtx == null) appCtx = context.applicationContext }

    fun conn(s: VpsServer): Conn? {
        val code = parse(s.linkUrl) ?: return null
        val c = conns[s.id]
        if (c != null && c.code == code) return c
        c?.close()
        return Conn(s.id, code).also { conns[s.id] = it; it.start() }
    }

    /** [Mines-link-32] Online now (directly or through the SSH channel). */
    fun isOnline(serverId: String): Boolean = _states.value[serverId]?.first == State.ONLINE

    /** Re-dial every link now (network changed). */
    fun kick() { conns.values.forEach { it.kick() } }

    fun closeAll() { conns.values.forEach { it.close() }; conns.clear() }

    private fun setState(id: String, st: State, detail: String) {
        _states.value = _states.value + (id to (st to detail))
    }

    class JobSub(val id: String, @Volatile var offset: Long, val onOut: (ByteArray) -> Unit, val onExit: (Int) -> Unit)

    class Conn(val serverId: String, val code: Code) {
        @Volatile private var ws: WebSocket? = null
        @Volatile var online = false; private set
        @Volatile private var closed = false
        @Volatile private var lastPong = 0L
        /** [Mines-link-32] Direct dial failed: carry the link over an SSH port-forward instead. */
        @Volatile private var viaSsh = false
        @Volatile private var directFails = 0
        private val rid = AtomicLong(1)
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
        private val subs = ConcurrentHashMap<String, JobSub>()
        private var loop: Job? = null
        private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        private val onlineWait = MutableStateFlow(false)

        private val client: OkHttpClient by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                    val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                    if (fp != code.pin) throw java.security.cert.CertificateException("证书指纹不对（可能被中间人替换）")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { _, _ -> true } // identity = pinned certificate
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        fun start() {
            loop = scope.launch {
                var backoff = 1000L
                while (!closed) {
                    setState(serverId, if (backoff == 1000L) State.CONNECTING else State.RETRYING, "正在连接…")
                    val err = runOnce()
                    if (!online && !viaSsh) directFails++
                    online = false; onlineWait.value = false
                    pending.values.forEach { it.completeExceptionally(java.io.IOException("断线")) }; pending.clear()
                    if (closed) break
                    setState(serverId, State.RETRYING, "断线了：$err · ${backoff / 1000} 秒后重连")
                    withTimeoutOrNull(backoff) { wake.receive() }
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                    if (lastPong > 0 && System.currentTimeMillis() - lastPong < 60_000) backoff = 1000L
                }
                setState(serverId, State.OFF, "已关闭")
            }
        }

        fun kick() { wake.trySend(Unit) }

        fun close() { closed = true; runCatching { ws?.close(1000, "bye") }; loop?.cancel() }

        /** One connection lifetime; returns why it ended. */
        private suspend fun runOnce(): String {
            val done = CompletableDeferred<String>()
            // [Mines-link-32] After a failed direct dial (e.g. Aliyun security group
            // without TCP 8443), use the SSH session that already works and forward a
            // local port to the worker on the server's loopback. The cert pin still
            // applies, so the channel is just as safe; no new port has to be opened.
            var url = code.url
            var fwd: Pair<com.jcraft.jsch.Session, Int>? = null
            if (directFails >= 1) {
                val ctx = appCtx
                val srv = PlusPrefs.state.value.servers.firstOrNull { it.id == serverId }
                val remotePort = runCatching { java.net.URI(code.url.replace("wss://", "https://")).port }.getOrDefault(-1).takeIf { it > 0 } ?: 8443
                if (ctx != null && srv != null) runCatching {
                    val (sess, _) = VpsSsh.session(ctx, srv.copy(linkUrl = ""))
                    val lp = sess.setPortForwardingL(0, "127.0.0.1", remotePort)
                    fwd = sess to lp
                    url = "wss://127.0.0.1:$lp/" + code.url.removePrefix("wss://").substringAfter('/', "mines")
                    viaSsh = true
                }.onFailure { viaSsh = false; Log.w(TAG, "ssh channel: ${it.message}") }
            }
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("t", "hello").put("token", code.token).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (m.optString("t")) {
                        "hello" -> if (m.optBoolean("ok")) {
                            online = true; onlineWait.value = true; lastPong = System.currentTimeMillis()
                            setState(serverId, State.ONLINE, "已连接（${m.optString("host")}${if (viaSsh) "，经 SSH 通道" else ""}）")
                            if (!viaSsh) directFails = 0
                            // resume every followed job from its byte offset
                            subs.values.forEach { s -> webSocket.send(JSONObject().put("t", "sub").put("id", s.id).put("since", s.offset).toString()) }
                        } else done.complete("连接码不对（token 被拒绝）")
                        "pong" -> lastPong = System.currentTimeMillis()
                        "res" -> pending.remove(m.optLong("rid"))?.complete(m)
                        "ev" -> subs[m.optString("id")]?.let { s ->
                            val off = m.optLong("off"); val b = Base64.decode(m.optString("b"), Base64.DEFAULT)
                            if (off + b.size > s.offset) { // skip bytes already delivered
                                val skip = (s.offset - off).coerceAtLeast(0).toInt()
                                if (skip < b.size) s.onOut(b.copyOfRange(skip, b.size))
                                s.offset = off + b.size
                            }
                        }
                        "exit" -> subs.remove(m.optString("id"))?.let { s -> s.onExit(m.optInt("code", 1)) }
                        "started", "stopped", "jobs" -> pending.remove(m.optLong("rid", -1))?.complete(m)
                        "error" -> m.optLong("rid", -1).takeIf { it > 0 }?.let { pending.remove(it)?.complete(m) }
                    }
                }
                override fun onClosing(webSocket: WebSocket, c: Int, reason: String) { done.complete("服务器关闭（$c）") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.complete((response?.let { "HTTP ${it.code} " } ?: "") + (t.message ?: t.javaClass.simpleName))
                }
            })
            ws = socket
            val hb = scope.launch {
                var n = 0
                while (!done.isCompleted) {
                    delay(15_000)
                    socket.send(JSONObject().put("t", "ping").put("n", ++n).toString())
                    if (online && System.currentTimeMillis() - lastPong > 40_000) { socket.cancel(); done.complete("心跳超时") }
                }
            }
            // [Mines-link-32] a dial that never opens must not hang: 10 s to say hello
            withTimeoutOrNull(10_000) { while (!online && !done.isCompleted) delay(200) }
            if (!online && !done.isCompleted) { runCatching { socket.cancel() }; done.complete("10 秒没连上") }
            val why = done.await()
            hb.cancel(); runCatching { socket.cancel() }
            fwd?.let { (sess, lp) -> runCatching { sess.delPortForwardingL(lp) } }
            if (viaSsh && !online) viaSsh = false
            return why
        }

        private suspend fun awaitOnline(ms: Long): Boolean = online || withTimeoutOrNull(ms) { onlineWait.first { it } } == true

        private suspend fun request(o: JSONObject, waitMs: Long, onlineMs: Long = 12_000): JSONObject? {
            if (!awaitOnline(onlineMs)) return null
            val id = rid.getAndIncrement()
            val d = CompletableDeferred<JSONObject>()
            pending[id] = d
            o.put("rid", id)
            if (ws?.send(o.toString()) != true) { pending.remove(id); return null }
            return runCatching { withTimeoutOrNull(waitMs) { d.await() } }.getOrNull().also { pending.remove(id) }
        }

        /** Short command; null when the link is not up (caller falls back to SSH). */
        suspend fun exec(cmd: String, timeoutSec: Int = 60, onlineMs: Long = 12_000): Pair<Int, String>? {
            val r = request(JSONObject().put("t", "exec").put("cmd", cmd).put("timeout", timeoutSec), timeoutSec * 1000L + 15_000, onlineMs) ?: return null
            return r.optInt("code", 1) to r.optString("out")
        }

        /** Start (idempotent per [id]) and follow a long job; output resumes after drops. */
        suspend fun runJob(id: String, cmd: String, onOut: (ByteArray) -> Unit, onExit: (Int) -> Unit): Boolean {
            if (!awaitOnline(15_000)) return false
            subs[id] = JobSub(id, 0, onOut, onExit)
            ws?.send(JSONObject().put("t", "run").put("id", id).put("cmd", cmd).toString())
            ws?.send(JSONObject().put("t", "sub").put("id", id).put("since", 0).toString())
            return true
        }

        suspend fun attach(id: String, since: Long, onOut: (ByteArray) -> Unit, onExit: (Int) -> Unit): Boolean {
            if (!awaitOnline(15_000)) return false
            subs[id] = JobSub(id, since, onOut, onExit)
            ws?.send(JSONObject().put("t", "sub").put("id", id).put("since", since).toString())
            return true
        }

        /** Start a job without following it (it runs on the server until done). */
        suspend fun startOnly(id: String, cmd: String): Boolean {
            if (!awaitOnline(15_000)) return false
            return ws?.send(JSONObject().put("t", "run").put("id", id).put("cmd", cmd).toString()) == true
        }

        fun stop(id: String) { ws?.send(JSONObject().put("t", "stop").put("id", id).toString()) }

        suspend fun jobs(): String? {
            if (!awaitOnline(12_000)) return null
            val d = CompletableDeferred<JSONObject>()
            val id = rid.getAndIncrement(); pending[id] = d
            ws?.send(JSONObject().put("t", "jobs").put("rid", id).toString())
            val r = withTimeoutOrNull(15_000) { d.await() } ?: return null
            val a = r.optJSONArray("list") ?: return ""
            return (0 until a.length()).joinToString("\n") { i -> a.getJSONObject(i).let { "${it.optString("id")} ${it.optString("state")}${if (it.isNull("code")) "" else "(" + it.optInt("code") + ")"}" } }
        }

        // ---- resumable, checksummed file transfer (4 MB chunks) ----
        suspend fun put(local: java.io.File, remote: String, progress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
            val total = local.length()
            var off = request(JSONObject().put("t", "stat").put("path", remote), 60_000)?.optLong("size", -1)?.coerceAtLeast(0) ?: return false
            if (off > total) off = 0
            java.io.RandomAccessFile(local, "r").use { f ->
                val buf = ByteArray(1 shl 20)
                while (off < total) {
                    f.seek(off); val n = f.read(buf); if (n <= 0) break
                    val r = request(JSONObject().put("t", "put").put("path", remote).put("off", off)
                        .put("b", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)), 60_000) ?: return false
                    off = r.optLong("size", off); progress(off, total)
                }
            }
            val remoteSha = request(JSONObject().put("t", "stat").put("path", remote), 120_000)?.optString("sha256") ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            val ins = local.inputStream()
            try { val b = ByteArray(1 shl 16); var n = ins.read(b); while (n >= 0) { md.update(b, 0, n); n = ins.read(b) } } finally { ins.close() }
            val localSha = md.digest().joinToString("") { "%02x".format(it) }
            return remoteSha == localSha
        }
    }
}

package com.openminis.app.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [T-muse-hub] Minis as a real device on the nanoMuse hub (`/v1/hub`).
 *
 * Replaces the shared-workspace page + `get_chat` polling: one outbound
 * WebSocket, frames are pushed both ways, no polling, no SSL retries on a
 * page.
 *
 *  - Muse → Minis: `call task {text, conversation?}` runs a real Minis agent
 *    turn (HeadlessChatRunner) in a side chat "From <device>", and the final
 *    answer goes back as `result {text}`. `open {url|package}` launches an app
 *    or page here. `info` answers what this device is.
 *  - Minis → Muse: the `minis-muse` CLI (MuseHubOffloadHandler) sends
 *    `call task` / `open` / `notify` to another device and blocks until the
 *    `result` frame arrives.
 *
 * Configured with two Minis env vars (Settings → Environment variables), so no
 * secret is in code: MUSE_HUB_URL (e.g. wss://relay.example/v1/hub) and
 * MUSE_HUB_KEY (the account's nm_… device key). Optional MUSE_HUB_NAME.
 */
object MuseHubClient {
    private const val TAG = "MuseHub"
    private const val TASK_TIMEOUT_MS = 20 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)      // keeps NAT/relay from idling us out
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var app: Context? = null
    @Volatile var connected = false; private set
    @Volatile var devices: JSONArray = JSONArray(); private set
    @Volatile private var backoffMs = 2_000L

    /** call id → queue that receives the matching result/error frame. */
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<JSONObject>>()

    fun start(context: Context) {
        app = context.applicationContext
        connect()
    }

    private fun env(key: String): String? =
        runCatching { (app as MinisApp).envVarRepository.getValue(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun deviceId(): String {
        val p = app!!.getSharedPreferences("muse_hub", Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: ("minis-" + UUID.randomUUID().toString().take(8)).also {
            p.edit().putString("device_id", it).apply()
        }
    }

    fun connect() {
        val url = env("MUSE_HUB_URL"); val key = env("MUSE_HUB_KEY")
        if (url == null || key == null) {
            AppLogger.info(TAG, "not configured (set MUSE_HUB_URL and MUSE_HUB_KEY)")
            return
        }
        if (ws != null) return
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello").put("key", key)
                    .put("device", JSONObject()
                        .put("id", deviceId())
                        .put("name", env("MUSE_HUB_NAME") ?: "Minis")
                        .put("kind", "phone").put("os", "android").put("version", "openminis")
                        .put("actions", JSONArray(listOf("info", "task", "open"))))
                webSocket.send(hello.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onFrame(webSocket, JSONObject(text)) }
                    .onFailure { AppLogger.warning(TAG, "bad frame: ${it.message}") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed $code $reason")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped("failure ${t.message}")
        })
    }

    private fun dropped(why: String) {
        AppLogger.warning(TAG, "disconnected: $why; retry in ${backoffMs / 1000}s")
        connected = false; ws = null
        // Fail every waiting CLI call now instead of letting it hang.
        pending.values.forEach { it.offer(JSONObject().put("type", "error").put("code", "disconnected").put("message", why)) }
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        scope.launch { kotlinx.coroutines.delay(wait); connect() }
    }

    private fun onFrame(sock: WebSocket, f: JSONObject) {
        when (f.optString("type")) {
            "welcome" -> {
                connected = true; backoffMs = 2_000L
                devices = f.optJSONArray("devices") ?: JSONArray()
                AppLogger.info(TAG, "connected as ${f.optString("device_id")}, ${devices.length()} device(s)")
            }
            "devices" -> devices = f.optJSONArray("devices") ?: JSONArray()
            "result", "error" -> f.optString("id").takeIf { it.isNotEmpty() }?.let { pending[it]?.offer(f) }
            "event" -> Unit // progress on our own call; the CLI only needs the result
            "call" -> scope.launch { serve(sock, f) }
        }
    }

    // ---- Muse → Minis --------------------------------------------------------

    private suspend fun serve(sock: WebSocket, call: JSONObject) {
        val id = call.optString("id")
        val args = call.optJSONObject("args") ?: JSONObject()
        val from = call.optJSONObject("from")?.optString("name") ?: "Muse"
        fun ok(body: JSONObject) { sock.send(JSONObject().put("type", "result").put("id", id).put("ok", true).put("body", body).toString()) }
        fun err(code: String, msg: String) { sock.send(JSONObject().put("type", "result").put("id", id).put("ok", false).put("error", code).put("message", msg).toString()) }
        try {
            when (call.optString("action")) {
                "info" -> ok(JSONObject().put("name", env("MUSE_HUB_NAME") ?: "Minis").put("kind", "phone").put("app", "openminis"))
                "task" -> {
                    val text = args.optString("text").takeIf { it.isNotBlank() } ?: return err("bad_args", "task needs text")
                    val conv = args.optString("conversation").ifBlank { "from:$from" }
                    val sessionId = sessionFor(conv)
                    val r = com.openminis.app.debug.HeadlessChatRunner.prompt(
                        context = app!!, sessionId = sessionId,
                        text = "[来自 $from 的任务]\n$text",
                        wait = true, timeoutMs = TASK_TIMEOUT_MS,
                    )
                    if (r.timedOut) err("timeout", "Minis did not finish within ${TASK_TIMEOUT_MS / 60000} min")
                    else ok(JSONObject().put("text", r.responseText ?: "").put("conversation", conv))
                }
                "open" -> {
                    val ctx = app!!
                    val intent = when {
                        args.optString("url").isNotBlank() -> Intent(Intent.ACTION_VIEW, Uri.parse(args.optString("url")))
                        args.optString("package").isNotBlank() ->
                            ctx.packageManager.getLaunchIntentForPackage(args.optString("package"))
                                ?: return err("not_found", "app not installed")
                        else -> return err("bad_args", "open needs url or package")
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok(JSONObject().put("opened", true))
                }
                else -> err("unknown_call", "Minis does not do ${call.optString("action")}")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "serve failed: ${t.message}")
            err("failed", t.message ?: t.javaClass.simpleName)
        }
    }

    /** One Minis chat per remote conversation, kept across restarts. */
    private suspend fun sessionFor(conv: String): String {
        val p = app!!.getSharedPreferences("muse_hub_sessions", Context.MODE_PRIVATE)
        p.getString(conv, null)?.let { id ->
            if ((app as MinisApp).chatRepository.getSession(id) != null) return id
        }
        val id = com.openminis.app.debug.HeadlessChatRunner.ensureSession(app!!)
        p.edit().putString(conv, id).apply()
        return id
    }

    // ---- Minis → Muse --------------------------------------------------------

    /** Resolve a device by id or (case-insensitive) name, skipping ourselves and browser tabs. */
    fun resolve(target: String): String? {
        val me = runCatching { deviceId() }.getOrNull()
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            if (d.optString("id") == me || d.optString("kind") == "web") continue
            if (d.optString("id") == target || d.optString("name").equals(target, ignoreCase = true)) return d.optString("id")
        }
        return null
    }

    /** Send a call and block for its result. Called from the offload thread. */
    fun call(to: String, action: String, args: JSONObject, timeoutMs: Long): JSONObject {
        val sock = ws ?: return JSONObject().put("ok", false).put("error", "disconnected").put("message", "not connected to the hub")
        val id = "minis-" + UUID.randomUUID().toString()
        val q = LinkedBlockingQueue<JSONObject>(1)
        pending[id] = q
        try {
            sock.send(JSONObject().put("type", "call").put("id", id).put("to", to).put("action", action).put("args", args).toString())
            val f = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return JSONObject().put("ok", false).put("error", "timeout").put("message", "no answer in ${timeoutMs / 1000}s")
            if (f.optString("type") == "error") return JSONObject().put("ok", false).put("error", f.optString("code")).put("message", f.optString("message"))
            return f
        } finally { pending.remove(id) }
    }
}

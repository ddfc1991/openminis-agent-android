package com.openminis.app.plus

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Mines-38.9.11.3] 远程诊断 — off by default; the user turns it on in 设置 › 测试与上报. While on (≤ 24 h, then it turns
 * itself off) Mines keeps one WebSocket to the diagnostics relay and streams: per-page frame jank, main-thread stalls
 * (> 200 ms, with the main thread's stack), memory, and its own logcat W/E/F tail — every text through [DiagCore.scrub].
 * The relay can send back ONLY the signed whitelist in [DiagCore.verify]; there is no other code path.
 *
 * Endpoints are tried in order (梦云 direct first, the Cloudflare hostname second when a 诊断码 adds it), each over the app's
 * own route memory ([ProviderRoutes]: direct / Mines' proxy), ~3 s per attempt. All work runs on one background thread;
 * the main thread only posts a 100 ms heartbeat (watchdog) and the frame-metrics callback runs on that background thread.
 */
object Diag {
    private const val TAG = "MinesDiag"
    private const val PREFS = "mines_diag"
    /** built-in 诊断码: 梦云 relay (wss, self-signed cert pinned by SHA-256). The connect token is public by design. */
    const val DEFAULT_CODE_JSON = """{"t":"mdk_7iFFyujXMaewn7Tk7jdc4ldCbFniaTLw","e":[["wss://38.76.209.77:2083/mdiag/dev","daa65e9f5997a31e5ebb52db0a2d3aa68432b204fb4077a3c89e0d9aab100dab"]]}"""
    const val STALL_MS = 200L

    private lateinit var app: Context
    private val inited = AtomicBoolean(false)
    private var th: HandlerThread? = null
    private var bg: Handler? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val _on = MutableStateFlow(false); val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _status = MutableStateFlow("诊断已关闭"); val status: StateFlow<String> = _status.asStateFlow()
    /** 「来自诊断：…」 banner text (show_notice) */
    val notice = MutableStateFlow<String?>(null)
    /** pending request_screenshot_of_mines_only: the command id waiting for 「允许发送截图？」 */
    val shotAsk = MutableStateFlow<String?>(null)

    /** [38.9.11.3 g2] remote config: active changes (one per key), shown in-app with 「撤销」; all undone when 诊断 turns off */
    data class Change(val key: String, val from: String, val to: String, val at: Long)
    val changes = MutableStateFlow<List<Change>>(emptyList())
    @Volatile var navAnimOff = false

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var epIdx = 0
    @Volatile private var lastErr: String? = null
    @Volatile private var sid = ""
    @Volatile private var key = ""          // this session's command key (hex)
    @Volatile private var level = "warn"
    @Volatile private var page = "?"
    @Volatile private var act: WeakReference<Activity>? = null
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var backoffMs = 5_000L
    @Volatile private var gen = 0                      // connection generation (stale callbacks ignored)

    // ------------------------------------------------------------------ public API
    fun init(c: Context) {
        if (!inited.compareAndSet(false, true)) return
        app = c.applicationContext
        val p = prefs()
        if (p.getBoolean("on", false)) {
            if (DiagCore.autoOffDue(p.getLong("onAt", 0L), System.currentTimeMillis())) p.edit().putBoolean("on", false).apply()
            else { _on.value = true; start() }
        }
    }

    fun setOn(c: Context, v: Boolean) {
        if (!inited.get()) init(c)
        prefs().edit().putBoolean("on", v).apply { if (v) putLong("onAt", System.currentTimeMillis()) }.apply()
        _on.value = v
        if (v) start() else stop("诊断已关闭")
    }

    /** smoke / support: a 诊断码 (mdiag1:…) that replaces the built-in endpoints; blank = back to built-in */
    fun setCode(c: Context, code: String?): Boolean {
        if (!inited.get()) init(c)
        if (code.isNullOrBlank()) { prefs().edit().remove("code").apply(); restartIfOn(); return true }
        DiagCore.parseCode(code) ?: return false
        prefs().edit().putString("code", code.trim()).apply(); restartIfOn(); return true
    }

    fun notePage(route: String?) { if (!_on.value) return; page = DiagCore.pageName(route); bg?.post { flushJank(force = true) } }

    fun attach(a: Activity) {
        act = WeakReference(a)
        if (_on.value) bg?.post { hookFrames(a) }
    }
    fun detach(a: Activity) { if (act?.get() === a) { unhookFrames(); act = null } }

    fun sessionId(): String = sid
    fun isConnected(): Boolean = connected

    // ------------------------------------------------------------------ lifecycle
    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun config(): DiagCore.Config? {
        prefs().getString("code", null)?.let { DiagCore.parseCode(it) }?.let { return it }
        return DiagCore.parseCode("mdiag1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(DEFAULT_CODE_JSON.toByteArray()))
    }

    private fun restartIfOn() { if (_on.value) { stop(null); start() } }

    @Synchronized private fun start() {
        if (th != null) return
        val t = HandlerThread("mines-diag", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        th = t; bg = Handler(t.looper)
        sid = prefs().getString("sid", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(20).also { prefs().edit().putString("sid", it).apply() }
        key = DiagCore.hexOf(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        seen.clear(); backoffMs = 5_000L
        _status.value = DiagCore.status(true, false, 0, null)
        bg?.post { connect() }
        bg?.postDelayed(tick, 10_000L)
        startWatchdog()
        act?.get()?.let { a -> bg?.post { hookFrames(a) } }
    }

    @Synchronized private fun stop(statusText: String?) {
        changes.value.forEach { applyConfig(it.key, "default") }; changes.value = emptyList()
        stopWatchdog(); unhookFrames()
        gen++
        runCatching { ws?.close(1000, "off") }; ws = null; connected = false
        th?.quitSafely(); th = null; bg = null
        shotAsk.value = null
        _status.value = statusText ?: DiagCore.status(_on.value, false, 0, null)
    }

    // periodic: auto-off, memory (60 s), logcat tail (30 s), jank flush (10 s)
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            val h = bg ?: return
            runCatching {
                if (DiagCore.autoOffDue(prefs().getLong("onAt", 0L), System.currentTimeMillis())) {
                    main.post { setOn(app, false); _status.value = "诊断已关闭（24 小时到了，已自动关闭）" }; return
                }
                ticks++
                flushJank(force = false)
                if (ticks % 3 == 0) sendLogcat()
                if (ticks % 6 == 1) send(memNow())
            }
            h.postDelayed(this, 10_000L)
        }
    }

    // ------------------------------------------------------------------ connection (bg thread)
    private fun clientFor(ep: DiagCore.Endpoint, proxy: java.net.Proxy): OkHttpClient {
        val b = OkHttpClient.Builder().proxy(proxy).connectTimeout(3, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS)
        val pin = ep.pinSha256
        if (pin != null && ep.url.startsWith("wss://")) {
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = throw java.security.cert.CertificateException("no")
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    val got = DiagCore.hexOf(java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))
                    if (got != pin) throw java.security.cert.CertificateException("diag pin mismatch")
                }
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS"); ctx.init(null, arrayOf(tm), java.security.SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }   // identity = the pinned certificate itself
        }
        return b.build()
    }

    private fun connect() {
        val cfg = config() ?: run { _status.value = DiagCore.status(true, false, 0, "诊断码无效"); return }
        val my = ++gen
        for ((i, ep) in cfg.endpoints.withIndex()) {
            val host = runCatching { java.net.URI(ep.url).host }.getOrNull() ?: continue
            val local = host == "127.0.0.1" || host == "10.0.2.2" || host == "localhost"
            val routes = if (local) listOf(ProviderRouteCore.Route.DIRECT) else runCatching { ProviderRoutes.order(host) }.getOrDefault(listOf(ProviderRouteCore.Route.DIRECT))
            for (r in routes) {
                if (my != gen) return
                val proxy = if (local) java.net.Proxy.NO_PROXY else ProviderRoutes.proxyFor(r)
                val opened = CountDownLatch(1); val failMsg = arrayOf<String?>(null)
                val t0 = SystemClock.elapsedRealtime()
                val req = Request.Builder().url(ep.url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))
                    .header("X-Diag-Token", cfg.token).build()
                val sock = clientFor(ep, proxy).newWebSocket(req, Listener(my, i, opened, failMsg))
                val ok = opened.await(3500, TimeUnit.MILLISECONDS) && failMsg[0] == null
                if (ok) {
                    if (!local) runCatching { ProviderRoutes.ok(host, r, SystemClock.elapsedRealtime() - t0) }
                    ws = sock; epIdx = i; backoffMs = 5_000L
                    sock.send(DiagCore.hello(sid, com.openminis.app.BuildConfig.VERSION_NAME, com.openminis.app.BuildConfig.VERSION_CODE.toLong(),
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", android.os.Build.VERSION.SDK_INT, key).toString())
                    return
                }
                runCatching { sock.cancel() }
                if (!local) runCatching { ProviderRoutes.fail(host, r) }
                lastErr = failMsg[0] ?: "连接超时"
            }
        }
        connected = false
        _status.value = DiagCore.status(true, false, 0, (lastErr ?: "连不上").let { if (it.length > 24) it.take(24) + "…" else it })
        bg?.postDelayed({ if (my == gen) connect() }, backoffMs); backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
    }

    private class Listener(val my: Int, val idx: Int, val opened: CountDownLatch, val failMsg: Array<String?>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (my != gen) return
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (o.optString("t")) {
                "welcome" -> { connected = true; _status.value = DiagCore.status(true, true, idx, null)
                    bg?.post { send(memNow()); sendLogcat() } }
                "cmd" -> bg?.post { onCommand(text) }
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failMsg[0] = when {
                response?.code == 401 || response?.code == 403 -> "诊断码不对"
                t is javax.net.ssl.SSLException || t.cause is java.security.cert.CertificateException -> "证书不对"
                t is java.net.SocketTimeoutException -> "连接超时"
                else -> "网络不通"
            }
            opened.countDown()
            if (my == gen && ws === webSocket) lost(failMsg[0])
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (my == gen && ws === webSocket) lost("服务器断开") }
    }

    private fun lost(why: String?) {
        connected = false; ws = null; lastErr = why
        if (!_on.value) return
        _status.value = DiagCore.status(true, false, 0, why)
        val my = gen
        bg?.postDelayed({ if (my == gen && ws == null) connect() }, backoffMs); backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
    }

    private fun send(o: JSONObject) { runCatching { if (connected) ws?.send(o.put("ts", System.currentTimeMillis()).toString()) } }

    // ------------------------------------------------------------------ commands (bg thread)
    private fun onCommand(raw: String) {
        val v = DiagCore.verify(raw, key, sid, System.currentTimeMillis(), seen.toSet())
        when (v) {
            is DiagCore.Verdict.Rejected -> { send(DiagCore.rejected(v.id, v.reason)); android.util.Log.w(TAG, "command rejected: ${v.reason}") }
            is DiagCore.Verdict.Ok -> {
                seen += v.id; if (seen.size > 500) seen.remove(seen.first())
                runCatching { exec(v) }.onFailure { send(DiagCore.result(v.id, v.cmd, JSONObject().put("error", DiagCore.scrub(it.toString(), 200)))) }
            }
        }
    }

    private fun exec(v: DiagCore.Verdict.Ok) {
        val id = v.id; val cmd = v.cmd
        when (cmd) {
            "ping" -> send(DiagCore.result(id, cmd, JSONObject().put("pong", System.currentTimeMillis()).put("page", page)
                .put("uptime_s", SystemClock.elapsedRealtime() / 1000).put("route_idx", epIdx)))
            "dump_main_thread_now" -> send(DiagCore.result(id, cmd, JSONObject().put("page", page)
                .put("stack", DiagCore.scrubStack(stackText(Looper.getMainLooper().thread.stackTrace), 60))))
            "set_log_level" -> { level = v.args["level"] as String; send(DiagCore.result(id, cmd, JSONObject().put("level", level))) }
            "get_recent_crash" -> send(DiagCore.result(id, cmd, recentCrash()))
            "set_config" -> {
                val k = v.args["key"] as String; val to = v.args["value"] as String; val from = configNow(k)
                applyConfig(k, to)
                changes.value = changes.value.filter { it.key != k } + if (to == "default") emptyList() else listOf(Change(k, from, to, System.currentTimeMillis()))
                android.util.Log.i(TAG, "remote config $k: $from -> $to")
                send(DiagCore.result(id, cmd, JSONObject().put("key", k).put("from", from).put("to", to)))
            }
            "show_notice" -> { val t = v.args["text"] as String; main.post { notice.value = t }; send(DiagCore.result(id, cmd, JSONObject().put("shown", true))) }
            "capture_jank_trace" -> {
                val secs = v.args["seconds"] as Int
                synchronized(trace) { trace.clear() }; traceUntil = SystemClock.elapsedRealtime() + secs * 1000L; traceStalls.clear()
                bg?.postDelayed({
                    traceUntil = 0L
                    val d = synchronized(trace) { trace.toList() }
                    val fs = DiagCore.frameSum(d.map { it.second }, budgetMs())
                    val pages = JSONObject(); d.groupBy { it.first }.forEach { (p, l) -> val s = DiagCore.frameSum(l.map { it.second }, budgetMs())
                        pages.put(p, JSONObject().put("frames", s.frames).put("janky", s.janky).put("slowest_ms", s.slowestMs).put("p90_ms", s.p90Ms)) }
                    send(DiagCore.result(id, cmd, JSONObject().put("seconds", secs).put("frames", fs.frames).put("janky", fs.janky)
                        .put("slowest_ms", Math.round(fs.slowestMs * 10) / 10.0).put("p90_ms", Math.round(fs.p90Ms * 10) / 10.0).put("budget_ms", budgetMs())
                        .put("pages", pages).put("frame_ms", JSONArray(d.takeLast(600).map { Math.round(it.second * 10) / 10.0 }))
                        .put("stalls", JSONArray(traceStalls.toList()))))
                }, secs * 1000L + 300L)
            }
            "request_screenshot_of_mines_only" -> {
                main.post { shotAsk.value = id }
                bg?.postDelayed({ if (shotAsk.value == id) { main.post { if (shotAsk.value == id) shotAsk.value = null }
                    send(DiagCore.result(id, cmd, JSONObject().put("denied", true).put("why", "60 秒内没有回答"))) } }, 60_000L)
            }
        }
    }

    private fun configNow(key: String): String = when (key) {
        "sky_mode" -> com.openminis.app.ui.plus.WeatherSky.modeOverride?.key ?: "default"
        "sky_fps_cap" -> com.openminis.app.ui.plus.WeatherSky.fpsCap.takeIf { it > 0 }?.toString() ?: "default"
        "nav_anim" -> if (navAnimOff) "off" else "default"
        "log_level" -> if (level == "warn") "default" else level
        "handoff_v2", "handoff_v2.resume", "handoff_v2.tab", "handoff_v2.oauth", "handoff_v2.label" -> HandoffFlags.remoteValue(key)   // [38.9.13]
        else -> "default"
    }

    private fun applyConfig(key: String, value: String) {
        val W = com.openminis.app.ui.plus.WeatherSky
        when (key) {
            "sky_mode" -> { W.modeOverride = if (value == "default") null else SkyModeCore.parse(value); W.bump() }
            "sky_fps_cap" -> { W.fpsCap = value.toIntOrNull() ?: 0; W.bump() }
            "nav_anim" -> navAnimOff = value == "off"
            "log_level" -> level = if (value == "default") "warn" else value
            "handoff_v2", "handoff_v2.resume", "handoff_v2.tab", "handoff_v2.oauth", "handoff_v2.label" -> HandoffFlags.setRemote(key, value)   // [38.9.13]
        }
    }

    /** UI: one tap 「撤销」 → back to the user's own value */
    fun revertConfig(key: String) {
        val c = changes.value.firstOrNull { it.key == key } ?: return
        applyConfig(key, "default"); changes.value = changes.value.filter { it.key != key }
        android.util.Log.i(TAG, "remote config $key reverted by the user")
        bg?.post { send(JSONObject().put("t", "config_reverted").put("key", key).put("was", c.to).put("by", "user")) }
    }

    /** UI: the user's answer to 「允许发送截图？」 */
    fun answerShot(id: String, allow: Boolean) {
        if (shotAsk.value != id) return
        shotAsk.value = null
        if (!allow) { bg?.post { send(DiagCore.result(id, "request_screenshot_of_mines_only", JSONObject().put("denied", true).put("why", "用户不允许"))) }; return }
        main.postDelayed({ shoot(id) }, 450L)   // after the dialog (and the glass blur behind it) is gone
    }

    private fun shoot(id: String) {
        val a = act?.get() ?: run { bg?.post { send(DiagCore.result(id, "request_screenshot_of_mines_only", JSONObject().put("error", "Mines 不在前台"))) }; return }
        val w = a.window; val dv = w.decorView
        if (dv.width <= 0 || dv.height <= 0) return
        val sc = (720f / dv.width).coerceAtMost(1f)
        val full = android.graphics.Bitmap.createBitmap(dv.width, dv.height, android.graphics.Bitmap.Config.ARGB_8888)
        val h = bg ?: return
        // PixelCopy of Mines' own window only (never the screen, never other apps)
        android.view.PixelCopy.request(w, full, { res ->
            runCatching {
                if (res != android.view.PixelCopy.SUCCESS) { send(DiagCore.result(id, "request_screenshot_of_mines_only", JSONObject().put("error", "截图失败 $res"))); return@runCatching }
                val small = android.graphics.Bitmap.createScaledBitmap(full, (dv.width * sc).toInt(), (dv.height * sc).toInt(), true)
                var q = 70; var bytes: ByteArray
                do { bytes = java.io.ByteArrayOutputStream().also { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, it) }.toByteArray(); q -= 15 } while (bytes.size > 380_000 && q > 20)
                send(DiagCore.result(id, "request_screenshot_of_mines_only", JSONObject().put("page", page).put("w", small.width).put("h", small.height)
                    .put("jpeg_b64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))))
                small.recycle()
            }
            full.recycle()
        }, h)
    }

    private fun recentCrash(): JSONObject {
        val dir = java.io.File(app.filesDir, "logs")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") && (it.name.startsWith("crash-") || it.name.startsWith("native-crash-") || it.name.startsWith("exitinfo-")) }.orEmpty() +
            listOfNotNull(java.io.File(app.filesDir, "mines-last-crash.txt").takeIf { it.isFile })
        val f = files.maxByOrNull { it.lastModified() } ?: return JSONObject().put("none", true)
        val txt = runCatching { f.readText().take(200_000) }.getOrDefault("")
        val kind = when { f.name.startsWith("exitinfo-") -> "exitinfo"; f.name.startsWith("native-") -> "native"; else -> "jvm" }
        return DiagCore.crashEvent(kind, com.openminis.app.crash.CrashRedactCore.title(txt.take(400)), com.openminis.app.crash.CrashRedactCore.forClipboard(txt))
            .put("age_min", (System.currentTimeMillis() - f.lastModified()) / 60_000)
    }

    private fun memNow(): JSONObject {
        val mi = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val ami = android.app.ActivityManager.MemoryInfo().also { runCatching { am.getMemoryInfo(it) } }
        return DiagCore.memEvent(mi.totalPss.toLong(), (rt.totalMemory() - rt.freeMemory()) / 1024, rt.maxMemory() / 1024,
            android.os.Debug.getNativeHeapAllocatedSize() / 1024, ami.lowMemory).put("page", page).put("avail_mb", ami.availMem / 1048576)
    }

    private var lastLogLine: String? = null
    private fun sendLogcat() {
        if (!connected) return
        val lines = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "400", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines(); p.waitFor(3, TimeUnit.SECONDS); p.destroy(); out
        }.getOrDefault(emptyList())
        val fresh = lastLogLine?.let { ll -> val i = lines.lastIndexOf(ll); if (i >= 0) lines.drop(i + 1) else lines } ?: lines
        lines.lastOrNull()?.let { lastLogLine = it }
        val f = DiagCore.filterLogcat(fresh.filter { !it.contains(" $TAG") }, level)
        if (f.isNotEmpty()) send(JSONObject().put("t", "log").put("level", level).put("lines", JSONArray(f)))
    }

    // ------------------------------------------------------------------ frames (per-page jank)
    private val frames = ArrayList<Double>()
    private var framesPage = "?"
    private val trace = ArrayList<Pair<String, Double>>()
    @Volatile private var traceUntil = 0L
    private val traceStalls = ConcurrentLinkedDeque<JSONObject>()
    private var frameListener: android.view.Window.OnFrameMetricsAvailableListener? = null
    private var frameWindow: WeakReference<android.view.Window>? = null
    @Volatile private var refreshHz = 60f

    private fun budgetMs(): Double = 1000.0 / refreshHz.coerceIn(30f, 144f)

    private fun hookFrames(a: Activity) {
        val h = bg ?: return
        main.post {
            runCatching {
                unhookFrames()
                @Suppress("DEPRECATION") val hz = runCatching { a.windowManager.defaultDisplay.refreshRate }.getOrDefault(60f); refreshHz = hz
                val l = android.view.Window.OnFrameMetricsAvailableListener { _, fm, _ ->
                    if (fm.getMetric(android.view.FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
                    val ms = fm.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                    val p = page
                    synchronized(frames) { if (p != framesPage) { flushJankLocked(); framesPage = p }; frames += ms }
                    if (traceUntil > 0L && SystemClock.elapsedRealtime() < traceUntil) synchronized(trace) { trace += p to ms }
                }
                a.window.addOnFrameMetricsAvailableListener(l, h)
                frameListener = l; frameWindow = WeakReference(a.window)
            }
        }
    }

    private fun unhookFrames() {
        val l = frameListener ?: return; val w = frameWindow?.get()
        frameListener = null; frameWindow = null
        main.post { runCatching { w?.removeOnFrameMetricsAvailableListener(l) } }
    }

    private fun flushJank(force: Boolean) = synchronized(frames) { if (force || frames.size >= 30) flushJankLocked() }
    private fun flushJankLocked() {
        if (frames.size < 3) { frames.clear(); return }
        val s = DiagCore.frameSum(frames, budgetMs()); frames.clear()
        if (s.janky > 0 || s.frames >= 30) send(DiagCore.jankEvent(framesPage, s.frames, s.janky, s.slowestMs, s.p90Ms))
    }

    // ------------------------------------------------------------------ main-thread stall watchdog (> 200 ms → sample)
    @Volatile private var beat = 0L
    @Volatile private var wdRun = false
    private val beatR = object : Runnable { override fun run() { beat = SystemClock.uptimeMillis(); if (wdRun) main.postDelayed(this, 100L) } }

    private fun startWatchdog() {
        if (wdRun) return
        wdRun = true; beat = SystemClock.uptimeMillis(); main.post(beatR)
        Thread({
            var episode = 0L; var sampled = 0; var stack = ""; var stallPage = "?"
            while (wdRun) {
                runCatching { Thread.sleep(50L) }
                val gap = SystemClock.uptimeMillis() - beat
                if (gap > STALL_MS + 100L) {             // heartbeat every 100 ms → > 200 ms of main thread blocked
                    if (episode == 0L) { episode = gap; sampled = 0; stallPage = page }
                    episode = maxOf(episode, gap)
                    if (sampled == 0 || (sampled == 1 && gap > 1000L)) {
                        stack = stackText(Looper.getMainLooper().thread.stackTrace); sampled++
                    }
                } else if (episode > 0L) {
                    val ms = episode - 100L
                    val e = DiagCore.stallEvent(stallPage, ms, stack)
                    if (traceUntil > 0L) traceStalls += JSONObject().put("page", stallPage).put("ms", ms).put("top", stack.lineSequence().take(6).joinToString("\n"))
                    bg?.post { send(e) }
                    episode = 0L
                }
            }
        }, "mines-diag-wd").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }.start()
    }

    private fun stopWatchdog() { wdRun = false; main.removeCallbacks(beatR) }

    private fun stackText(st: Array<StackTraceElement>): String = buildString {
        append("main\n"); st.take(50).forEach { append("\tat ").append(it.toString()).append('\n') }
    }
}

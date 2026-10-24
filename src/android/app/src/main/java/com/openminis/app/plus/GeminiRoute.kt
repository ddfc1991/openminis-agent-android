package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * [Mines-voice-26] How the live call reaches Gemini, and which live model to use.
 *
 * Gemini is not reachable from mainland China and refuses Hong Kong
 * ("User location is not supported"). build-25 always opened the WebSocket
 * directly (or through whatever the app's ProxySelector said), so from the
 * user's network the call never connected and the screen only said
 * "网络断了，正在自动重连". Now every call first probes the REST models list
 * through each possible route — direct, the 分流 proxy, each configured
 * upstream (e.g. a US exit), the system proxy, then proxy ports of VPN apps
 * found on the phone — takes the first route that really answers, shows it on
 * screen, and reads the list of models that support bidiGenerateContent so a
 * stale model id falls back to a valid live model.
 */
object GeminiRoute {
    private const val TAG = "GeminiRoute"
    const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key="

    /** [Mines-38.1] [dns]/[port] = a TLS-passthrough relay: generativelanguage.googleapis.com resolves to the relay
     *  host, which forwards raw TCP to Google through a US exit; TLS (and the key) stay end-to-end. */
    data class Route(val label: String, val proxy: Proxy, val dns: okhttp3.Dns? = null, val port: Int = 443)

    const val GOOGLE_HOST = "generativelanguage.googleapis.com"
    fun origin(r: Route?): String = if (r == null || r.port == 443) GOOGLE_HOST else "$GOOGLE_HOST:${r.port}"
    fun wsUrl(r: Route?): String = "wss://" + origin(r) + "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    fun modelsUrl(r: Route?): String = "https://" + origin(r) + "/v1beta/models?pageSize=1000&key="

    /** 语音设置 → 通话线路 → 中转地址 ("host" or "host:port"); empty = none. */
    fun relaySetting(): String = PlusVault.extra("gemini", "relay").orEmpty().trim()
    fun relayRoute(): Route? {
        val v = relaySetting().removePrefix("https://").removePrefix("wss://").trimEnd('/')
        if (v.isBlank()) return null
        val host = v.substringBefore(':'); val port = v.substringAfter(':', "443").toIntOrNull() ?: 443
        val dns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                if (hostname == GOOGLE_HOST) java.net.InetAddress.getAllByName(host).toList() else okhttp3.Dns.SYSTEM.lookup(hostname)
        }
        return Route("中转 $host", Proxy.NO_PROXY, dns, port)
    }
    data class Probe(val route: Route, val ok: Boolean, val note: String, val models: List<String> = emptyList(), val keyProblem: Boolean = false)

    @Volatile private var lastGood: String? = null
    /** [Mines-voice-30] Prefix of a handshake result that is a config/protocol rejection, not a network failure. */
    const val CONFIG_MARK = "✗⚙ "

    /** The Gemini key: default account under 账号连接 → Gemini, else the plain entry. */
    fun apiKey(): String? = runCatching { PlusVault.defaultAccount("gemini")?.let { PlusVault.secret(it) } }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: PlusVault.secret("gemini")?.takeIf { it.isNotBlank() }

    fun client(route: Route, readMs: Long = 12_000): OkHttpClient = OkHttpClient.Builder()
        .proxy(route.proxy)
        .apply { route.dns?.let { dns(it) } }
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(readMs, TimeUnit.MILLISECONDS)
        .callTimeout(if (readMs == 0L) 0 else readMs + 8000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Candidate routes in the order voice should try them (Mines build-29):
     * working proxies first (分流代理, saved upstreams, the system proxy),
     * then VPN-app ports detected on the phone, then direct. The last route
     * that worked moves to the front. Blocking (resolves proxy hosts); IO.
     */
    fun routes(context: Context?, withDetect: Boolean): List<Route> {
        val out = ArrayList<Route>()
        relayRoute()?.let { out += it }  // [Mines-38.1] the user's own relay first when set
        if (SplitProxy.active && SplitProxy.settings.value.hasUpstream)
            out += Route("分流代理（127.0.0.1:${SplitProxy.PORT}）", Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SplitProxy.PORT)))
        SplitProxy.settings.value.all.forEach { u ->
            runCatching {
                out += Route("代理 ${u.label.ifBlank { u.host }}:${u.port}",
                    Proxy(if (u.type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(u.host, u.port)))
            }
        }
        runCatching {
            val cm = context?.getSystemService(android.net.ConnectivityManager::class.java)
            val pi = cm?.defaultProxy
            if (pi != null && !pi.host.isNullOrBlank() && pi.port > 0 && !(pi.host == "127.0.0.1" && pi.port == SplitProxy.PORT))
                out += Route("系统代理 ${pi.host}:${pi.port}", Proxy(Proxy.Type.HTTP, InetSocketAddress(pi.host, pi.port)))
        }
        if (withDetect && context != null) runCatching {
            SplitProxy.detect(context).forEach { f ->
                val u = f.upstream
                out += Route("${f.source} ${u.host}:${u.port}", Proxy(if (u.type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(u.host, u.port)))
            }
        }
        out += Route("直连", Proxy.NO_PROXY)
        val dedup = out.distinctBy { it.proxy.toString() + "|" + it.label.takeIf { l -> l.startsWith("中转") }.orEmpty() }
        // [Mines-38.1] remembered across calls: the last route that held, then the steadiest (fewest drops per minute)
        val lg = lastGood ?: VoiceDiag.lastGood()
        return dedup.sortedWith(compareBy<Route>({ if (it.label == lg) 0 else 1 }, { VoiceDiag.score(it.label) }))
    }

    /** [Mines-voice-29] Is something listening on the proxy port at all (≤ 800 ms)? */
    fun proxyPortUp(route: Route): Boolean {
        val a = route.proxy.address() as? InetSocketAddress ?: return true
        return runCatching {
            java.net.Socket().use { it.connect(InetSocketAddress(a.hostString, a.port), 800) }; true
        }.getOrDefault(false)
    }

    /** What went wrong on a route, for the one plain line shown at the end. */
    enum class Fail { NONE, PROXY_DOWN, BLOCKED, REGION, KEY, LIVE }
    data class Tried(val route: Route, val fail: Fail, val note: String)

    fun probe(route: Route, key: String): Probe = runCatching {
        client(route, 10_000).newCall(Request.Builder().url(modelsUrl(route) + key).build()).execute().use { r -> fromResponse(route, r) }
    }.getOrElse { e -> Probe(route, false, shortErr(e)) }

    private fun fromResponse(route: Route, r: Response): Probe {
        val body = r.body?.string().orEmpty()
        if (r.isSuccessful) {
            val models = runCatching {
                val a = JSONObject(body).optJSONArray("models") ?: JSONArray()
                (0 until a.length()).mapNotNull { i ->
                    val m = a.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val bidi = methods != null && (0 until methods.length()).any { methods.optString(it) == "bidiGenerateContent" }
                    if (bidi) m.optString("name").removePrefix("models/") else null
                }
            }.getOrDefault(emptyList())
            return Probe(route, true, "通（${r.code}）", models)
        }
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(160)
        val keyBad = msg.contains("API key", true) || msg.contains("API_KEY", true) || r.code == 401 ||
            (r.code == 403 && msg.contains("permission", true) && !msg.contains("location", true))
        val region = msg.contains("location is not supported", true) || msg.contains("not available in your country", true)
        return Probe(route, false, when {
            region -> "这个出口所在地区不支持 Gemini（${r.code}）"
            keyBad -> "API Key 被拒：$msg"
            else -> "HTTP ${r.code}：$msg"
        }, keyProblem = keyBad)
    }

    fun shortErr(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.SocketTimeoutException || m.contains("timeout", true) -> "超时"
            e is java.net.UnknownHostException -> "域名解析失败"
            m.contains("refused", true) -> "连接被拒绝"
            m.contains("reset", true) -> "连接被重置"
            e is javax.net.ssl.SSLException -> "TLS 失败：${m.take(60)}"
            else -> (e.javaClass.simpleName + " " + m).take(80)
        }
    }

    data class Pick(val route: Route?, val models: List<String>, val report: List<String>, val keyProblem: String? = null,
                    val model: String? = null, val tried: List<Tried> = emptyList(), val configProblem: String? = null)

    /**
     * [Mines-voice-29] Tries the routes in order, each with a REAL check of
     * what the call needs: proxy port up → models list over HTTPS → a Live
     * WebSocket setup handshake (the same wss URL, proxy and setup as the
     * call). The REST check alone passed on proxies that then closed the
     * WebSocket. [exclude] = route labels that already failed this call.
     */
    fun pick(context: Context?, key: String, wantedModel: String = GeminiLive.DEFAULT_MODEL,
             exclude: Set<String> = emptySet(), onStep: (String) -> Unit = {}): Pick {
        val report = ArrayList<String>()
        val tried = ArrayList<Tried>()
        val seen = HashSet<String>()
        for (r in routes(context, withDetect = true)) {
            if (!seen.add(r.proxy.toString() + "|" + r.label.takeIf { l -> l.startsWith("中转") }.orEmpty())) continue
            if (r.label in exclude) { report += "✗ ${r.label}：这次通话里已经断过，跳过"; continue }
            onStep("正在连接…")  // [Mines-38.1] no route names on screen (was 「正在试：直连…」)
            if (r.proxy != Proxy.NO_PROXY && !proxyPortUp(r)) {
                report += "✗ ${r.label}：端口没开（代理软件没在运行）"
                tried += Tried(r, Fail.PROXY_DOWN, "端口没开"); continue
            }
            val p = probe(r, key)
            if (p.keyProblem) { report += "✗ ${r.label}：${p.note}"; return Pick(null, emptyList(), report, p.note, tried = tried) }
            if (!p.ok) {
                report += "✗ ${r.label}：${p.note}"
                tried += Tried(r, if (p.note.contains("地区")) Fail.REGION else Fail.BLOCKED, p.note); continue
            }
            val model = chooseModel(wantedModel, p.models)
            onStep("正在连接…")
            val hs = liveHandshake(r, key, model)
            report += "${if (hs.startsWith("✓")) "✓" else "✗"} ${r.label}：HTTPS ${p.note}；${hs.removePrefix("✓ ").removePrefix(CONFIG_MARK).removePrefix("✗ ")}"
            if (hs.startsWith("✓")) { lastGood = r.label; VoiceDiag.recordGood(r.label); return Pick(r, p.models, report, model = model, tried = tried + Tried(r, Fail.NONE, "ok")) }
            // [Mines-voice-30] The route works (HTTPS ok, socket opened) but the server rejected the setup:
            // every other route would get the same answer, so stop and report it as a config error.
            // [Mines-voice-35.2] a bad key closes with 1007 "API key not valid": key problem, not config
            if (hs.contains("API key", true) || hs.contains("API_KEY", true))
                return Pick(null, emptyList(), report, hs.removePrefix(CONFIG_MARK).removePrefix("✗ "), tried = tried)
            if (hs.startsWith(CONFIG_MARK)) return Pick(null, p.models, report, model = model,
                tried = tried + Tried(r, Fail.NONE, "config"), configProblem = hs.removePrefix(CONFIG_MARK))
            tried += Tried(r, Fail.LIVE, hs.removePrefix("✗ "))
        }
        return Pick(null, emptyList(), report, tried = tried)
    }

    /** [Mines-voice-29] One plain sentence for "nothing works", e.g. 「代理 Clash 没在工作，Google 直连也连不上」. */
    fun plainSummary(tried: List<Tried>): String {
        val parts = ArrayList<String>()
        val proxies = tried.filter { it.route.proxy != Proxy.NO_PROXY }
        val down = proxies.filter { it.fail == Fail.PROXY_DOWN }.map { it.route.label }
        if (down.isNotEmpty()) parts += down.joinToString("、") { it.removePrefix("代理 ") } + " 没在工作（代理软件没开？）"
        val region = proxies.filter { it.fail == Fail.REGION }
        if (region.isNotEmpty()) parts += region.joinToString("、") { it.route.label } + " 的出口地区不支持 Gemini"
        val blocked = proxies.filter { it.fail == Fail.BLOCKED || it.fail == Fail.LIVE }
        if (blocked.isNotEmpty()) parts += blocked.joinToString("、") { it.route.label } + " 连不上 Google 实时通道"
        val direct = tried.firstOrNull { it.route.proxy == Proxy.NO_PROXY }
        if (direct != null && direct.fail != Fail.NONE) parts += "Google 直连" + (if (proxies.isEmpty()) "" else "也") + "连不上"
        if (proxies.isEmpty()) parts += "没有可用的代理/VPN"
        return parts.joinToString("，").ifBlank { "所有线路都连不上 Gemini" }
    }

    /** The model to use: the configured one when the server lists it, else a valid live model. */
    fun chooseModel(wanted: String, live: List<String>): String {
        if (live.isEmpty() || wanted in live) return wanted
        return live.firstOrNull { it == GeminiLive.DEFAULT_MODEL }
            ?: live.filter { it.contains("live") }.maxByOrNull { it }
            ?: live.first()
    }

    /**
     * 「测试语音」: key, every route, model list, a real Live setup handshake,
     * microphone and speaker. Blocking (≈10–30 s); IO thread. One line per check.
     */
    @SuppressLint("MissingPermission")
    fun diagnose(context: Context, wantedModel: String, keyOverride: String? = null): List<String> {
        val out = ArrayList<String>()
        val key = keyOverride ?: apiKey()
        if (key == null) { out += "✗ 没有 Gemini API Key：账号连接 → Gemini 里填写"; return out }
        out += "✓ API Key：…${key.takeLast(4)}"
        // [Mines-voice-29] Same selection as the call: proxy port check, HTTPS, Live handshake per route.
        val pick = pick(context, key, wantedModel)
        out += pick.report
        if (pick.keyProblem != null) { out += "→ 去 账号连接 → Gemini 换一个有效的 Key"; return out }
        if (pick.configProblem != null) {
            out += "✗ 线路是通的，但 Gemini 拒绝了通话设置（配置错误，不是网络问题）：${pick.configProblem}"
            out += "→ 这是 Mines 的问题，请把这一页截图发给开发者；换线路没有用"
            return out
        }
        val route = pick.route
        if (route == null) {
            out += "✗ " + plainSummary(pick.tried)
            out += "→ 在 设置 → 模型路由与分流 填一个美国/日本/新加坡出口的代理（HTTP 或 SOCKS5），或打开能覆盖 Mines 的 VPN"
        } else {
            out += "✓ 通话将走：${route.label}"
            val model = pick.model ?: chooseModel(wantedModel, pick.models)
            if (pick.models.isNotEmpty()) out += "· 这个 Key 能用的实时模型：" + pick.models.take(6).joinToString("、")
            out += if (model == wantedModel) "✓ 模型 $model 可用" else "⚠ 模型 $wantedModel 不在列表里，通话会改用 $model"
            out += "✓ Live 实时通道握手成功（$model，同一条线路）"
        }
        // microphone
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            out += "✗ 没有麦克风权限：打开实时语音时允许麦克风"
        } else runCatching {
            val wait = System.currentTimeMillis() + 2500
            while (PetBus.wakeMicHeld.value && System.currentTimeMillis() < wait) Thread.sleep(100)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); out += "✗ 麦克风打不开（被别的应用占用？）"; return@runCatching }
            rec.startRecording()
            val buf = ShortArray(1600); var peak = 0.0; val until = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < until) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) { var s = 0.0; for (i in 0 until n) s += buf[i] * buf[i].toDouble(); peak = maxOf(peak, sqrt(s / n)) }
            }
            rec.stop(); rec.release()
            out += if (peak > 1) "✓ 麦克风正常（音量 ${peak.toInt()}）" else "⚠ 麦克风打开了但没有声音（被占用或静音）"
        }.onFailure { out += "✗ 麦克风出错：${it.message}" }
        runCatching {
            val t = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            out += if (t > 0) "✓ 扬声器输出（24 kHz）可用" else "✗ 扬声器输出初始化失败"
        }
        if (PlusVault.extra("gemini", "voice_user") == "1") out += "· 声音：${PlusVault.extra("gemini", "voice")}"
        if (VoiceClone.speaker(context) != null) out += "· 开着自定义声音（硅基流动）；它出错时会自动改回 Gemini 声音"
        return out
    }

    /** Opens a Live WebSocket, sends setup, waits for setupComplete (or the server's reason). */
    fun liveHandshake(route: Route, key: String, model: String): String {
        val done = CountDownLatch(1)
        var result = "✗ Live 握手超时（10 秒没有 setupComplete）"
        val ws = client(route, 0).newWebSocket(Request.Builder().url(wsUrl(route) + "?key=" + key).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // [Mines-voice-30] Same setup shape as the call (generationConfig.responseModalities).
                // [Mines-voice-35.2] the call's full setup (tools, transcription hints, resumption, compression)
                webSocket.send(GeminiLive.buildSetup(model, GeminiLive.DEFAULT_VOICE, GeminiLive.DEFAULT_PROMPT,
                    tools = GeminiLive.callTools(true), resumption = true).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("setupComplete")) { result = "✓ Live 实时通道握手成功（$model）"; done.countDown() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onMessage(webSocket, bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                result = (if (GeminiLive.isConfigError(code, reason)) CONFIG_MARK else "✗ ") +
                    "服务器关闭了连接（代码 $code）：${reason.ifBlank { "服务器没写原因" }}"; done.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result = "✗ Live 连接失败：" + (response?.let { "HTTP ${it.code} ${it.message} " } ?: "") + shortErr(t); done.countDown()
            }
        })
        done.await(10, TimeUnit.SECONDS)
        runCatching { ws.close(1000, "test") }
        Log.i(TAG, "handshake: $result")
        return result
    }
}

package com.openminis.app.plus

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Real-time voice with the Gemini Live API (raw WebSocket).
 * Mic: 16 kHz 16-bit mono PCM up; speaker: 24 kHz PCM down.
 * Echo cancellation lets the user talk over the model (barge-in); when the
 * server says "interrupted" the playback buffer is dropped at once.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String = DEFAULT_VOICE,
    private val systemPrompt: String = DEFAULT_PROMPT,
    // [Mines-voice] Playback speed / pitch (AudioTrack PlaybackParams) and
    // phone control by voice (function calls into the fast UI backend).
    private val speed: Float = 1f,
    private val pitch: Float = 1f,
    private val appContext: android.content.Context? = null,
    /**
     * [Mines-voiceclone-25] When set, Gemini's own audio is muted and its
     * output transcript is spoken by this cloned voice instead, one sentence
     * at a time and streamed, so the first words start after one sentence.
     */
    private val cloneVoice: TtsSpeaker? = null,
    /** [Mines-voice-34] context for mines_task when phone control is off. */
    private val bridgeContext: android.content.Context? = null,
) {
    companion object {
        /** Female by default (user asked, 2026-10-12). */
        const val DEFAULT_VOICE = "Kore"
        /** Gemini prebuilt voices with a short Chinese description. */
        val voiceLabels = linkedMapOf(
            "Kore" to "女声 · 沉稳", "Aoede" to "女声 · 轻快", "Leda" to "女声 · 年轻", "Zephyr" to "女声 · 明亮",
            "Puck" to "男声 · 活泼", "Charon" to "男声 · 低沉", "Fenrir" to "男声 · 有力", "Orus" to "男声 · 稳重",
        )
        const val PHONE_PROMPT = "你还能直接操作这台手机：先调用 phone_screen 看当前屏幕（编号列表），" +
            "再用 phone_do 一次执行多步（如 \"tap 3; type 5 你好; key enter\"），打开应用用 phone_open_app。" +
            "每步做完告诉用户结果。付款、转账、下单、发送消息之前，必须先用一句话口头确认，用户明确说确认后才带 confirmed=true 执行。"
        private const val TAG = "GeminiLive"
        const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** Checked against ai.google.dev/gemini-api/docs/live-api/get-started-websocket (2026-09-15). */
        const val DEFAULT_MODEL = "gemini-3.8-live"

        /**
         * [Mines-voice-30] The Live setup message, per ai.google.dev/api/live
         * (BidiGenerateContentSetup): responseModalities and speechConfig live
         * in setup.generationConfig. build-25..29 put them directly under
         * setup → server closed with 1007 「Unknown name "responseModalities"
         * at 'setup'」 on every route. Shared by the call and 测试语音.
         */
        /** [Mines-voice-35.2] Words the input transcription should prefer (they were misheard on the phone). */
        val VOCAB = listOf("Mines", "声纹", "录入", "桌宠", "Gemini", "语音", "设置", "硅基流动", "DeepSeek", "OpenRouter", "服务器")

        fun buildSetup(model: String, voice: String?, prompt: String?, tools: JSONArray? = null,
                       resumeHandle: String? = null, resumption: Boolean = false, safe: Boolean = false): JSONObject {
            val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            if (!voice.isNullOrBlank()) gen.put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            val setup = JSONObject().put("model", if (model.startsWith("models/")) model else "models/$model")
                .put("generationConfig", gen)
            if (!prompt.isNullOrBlank()) setup.put("systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            if (tools != null && tools.length() > 0) setup.put("tools", tools)
            // [Mines-voice-35.2] The user's words were transcribed with automatic language detection
            // (「冷不冷交通管制」): hint Chinese first + a small vocabulary (AudioTranscriptionConfig
            // languageCodes / customVocabulary, ai.google.dev/api/live). [safe] = the minimal setup
            // used once after a rejection, without these extras.
            setup.put("inputAudioTranscription", if (safe) JSONObject() else JSONObject()
                .put("languageCodes", JSONArray().put("zh-CN").put("en-US"))
                .put("customVocabulary", JSONArray(VOCAB)))
            setup.put("outputAudioTranscription", JSONObject())
            if (resumption) setup.put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
            if (safe) return JSONObject().put("setup", setup)
            // [Mines-voice-31] Without context-window compression an audio
            // session is capped (~15 min) and the server ends it; the sliding
            // window keeps the system prompt and drops the oldest turns.
            // Field per ai.google.dev/api/live ContextWindowCompressionConfig.
            setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            return JSONObject().put("setup", setup)
        }

            fun phoneTools(): JSONArray {
            fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
            fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
            fun bool(d: String) = JSONObject().put("type", "BOOLEAN").put("description", d)
            return JSONArray()
                .put(JSONObject().put("name", "phone_screen").put("description", "读取手机当前屏幕，返回带编号的可操作元素列表"))
                .put(JSONObject().put("name", "phone_do")
                    .put("description", "在手机上按顺序执行多步操作，最后返回新的屏幕列表。步骤用分号分隔：tap N | tap \"文字\" | type N 文字 | key back/home/enter | scroll down/up [次数] | wait 毫秒 | open 包名")
                    .put("parameters", obj("steps" to str("要执行的步骤"), "confirmed" to bool("付款/下单/发送类操作：用户已口头确认时为 true"), required = listOf("steps"))))
                .put(JSONObject().put("name", "phone_open_app").put("description", "按名字打开手机上的应用（如 微信、支付宝、美团）")
                    .put("parameters", obj("name" to str("应用名或包名"), required = listOf("name"))))
        }

        /** [Mines-voice-35.2] The call's tool list (also used by 测试语音, so it tests what the call really sends). */
        fun callTools(withPhone: Boolean): JSONArray {
            val decls = if (withPhone) phoneTools() else JSONArray()
            decls.put(MinesVoiceBridge.toolDeclaration())
            decls.put(VoiceIntents.toolDeclaration())
            return JSONArray().put(JSONObject().put("functionDeclarations", decls))
        }

        /** [Mines-voice-30] Server rejected the request itself (not the network): never fail over on this. */
        fun isConfigError(code: Int, reason: String): Boolean {
            // [Mines-voice-35.2] A bad key also closes with 1007 ("API key not valid"): that is a key error.
            if (isKeyError(code, reason)) return false
            if (code in listOf(1003, 1007, 1008)) return true
            return listOf("Invalid JSON", "Unknown name", "Cannot find field", "INVALID_ARGUMENT", "invalid argument",
                "HTTP 400").any { reason.contains(it, true) }
        }
        fun isKeyError(code: Int, reason: String): Boolean =
            reason.contains("API key", true) || reason.contains("API_KEY", true) || (code == 1008 && reason.contains("key", true))

        /** [Mines-voice-29] Full passes over every route before falling back to 普通语音模式. */
        const val MAX_ROUNDS = 3
        const val DEFAULT_PROMPT =
            "你是 ${com.openminis.app.ui.brand.Brand.NAME}，用户的私人助手。用户视力不好，请用简短、口语化的中文回答，一次只说一件事。"
        val voices = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
    }

    enum class Phase { IDLE, CONNECTING, LISTENING, SPEAKING, ERROR }

    data class Line(val user: Boolean, val text: String)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()
    /** 0..1 mic loudness, for the animated orb. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    /** [Mines-voice-26] Which way the call reaches Gemini (shown on screen). */
    private val _route = MutableStateFlow("")
    val route: StateFlow<String> = _route.asStateFlow()
    /** [Mines-voice-26] The live model really in use (may differ from the setting). */
    private val _modelInUse = MutableStateFlow(model)
    val modelInUse: StateFlow<String> = _modelInUse.asStateFlow()
    @Volatile private var routeSel: GeminiRoute.Route? = null
    // [Mines-voice-29] Route failover state for this call.
    private val badRoutes = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var rounds = 0
    @Volatile private var setupAt = 0L
    @Volatile private var sameRouteDrops = 0
    private val _allFailed = MutableStateFlow(false)
    /** True after every route failed [MAX_ROUNDS] rounds: UI offers 普通语音模式. */
    val allFailed: StateFlow<Boolean> = _allFailed.asStateFlow()
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var modelChecked = false
    // [Mines-voice-35.2] What the server said last and what we sent last (besides mic audio):
    // shown when the server closes without a reason, so the screen never ends in an empty 「：」.
    @Volatile private var lastServerMsg = ""
    @Volatile private var lastSent = ""
    @Volatile private var configRetries = 0
    @Volatile private var safeMode = false
    private val cancelledCalls = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var lastToolAt = 0L
    @Volatile private var handledUser = ""
    @Volatile private var lastUserLineAt = 0L
    private val _fallback = MutableStateFlow<String?>(null)
    /**
     * [Mines-voice-35.2] Set (with a plain reason) when Gemini can't carry the call: the server
     * rejected the setup/model/key or no route works. VoiceSession then switches the call to the
     * backup channel (phone speech recognition + Mines agent + external/Edge voice) by itself.
     */
    val fallback: StateFlow<String?> = _fallback.asStateFlow()
    /** Off as soon as the cloned voice fails: Gemini's own audio plays again. */
    @Volatile private var cloneOk = cloneVoice != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun wsClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(routeSel?.proxy ?: java.net.Proxy.NO_PROXY)
        .apply { routeSel?.dns?.let { dns(it) } }
        .connectTimeout(10, TimeUnit.SECONDS)
        // [Mines-38.1] keepalive every 15 s (user 16:09); a missed pong fails the socket, which hops silently
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    /** [Mines-voice-31] The socket whose setup completed: mic audio goes here. */
    @Volatile private var liveWs: WebSocket? = null
    /** [Mines-voice-31] A silent hop (goAway / drop) is in progress: nothing is shown unless it fails. */
    @Volatile private var hopping = false
    @Volatile private var hopFails = 0
    @Volatile private var hopForDrop = false
    @Volatile private var upSince = 0L
    /** [Mines-voice-33] Connection log for ⚙ → 连接详情: every close code/reason, goAway, hop. */
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()
    private fun logEvent(e: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _events.value = (_events.value + "$t  $e").takeLast(30)
        android.util.Log.i("MinesVoice", e)
    }
    /** The voice really sent in the setup message (shown in ⚙). */
    val voiceName: String get() = voice
    private var proactiveJob: kotlinx.coroutines.Job? = null
    private val _reconnects = MutableStateFlow(0)
    /** Seamless reconnects so far in this call (diagnostics). */
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()
    private val _voiceprintOn = MutableStateFlow(false)
    /** [Mines-voiceprint-34] true while the call only listens to the enrolled voice. */
    val voiceprintOn: StateFlow<Boolean> = _voiceprintOn.asStateFlow()
    private var micJob: Job? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    @Volatile private var setupDone = false
    @Volatile private var stopped = false
    @Volatile private var lastAudioAt = 0L
    // [Mines-voice-reconnect] Auto-reconnect state: attempts since the last
    // good session, the pending retry, and Gemini's session-resumption handle
    // so a reconnect continues the same conversation instead of starting over.
    @Volatile private var retries = 0
    @Volatile private var modelSwitchTried = false
    private var retryJob: Job? = null
    @Volatile private var resumeHandle: String? = null
    private val maxRetries = 8

    // [Mines-voiceclone-25] sentence queue for the cloned voice
    private val sentences = kotlinx.coroutines.channels.Channel<Pair<Int, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val pendingText = StringBuilder()
    @Volatile private var speakGen = 0
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)
    private var speakJob: Job? = null

    private fun queueSpeech(frag: String, flush: Boolean = false) {
        if (cloneVoice == null || !cloneOk) return
        synchronized(pendingText) {
            pendingText.append(frag)
            while (true) {
                val i = pendingText.indexOfFirst { it in "。！？!?；;\n" }
                val cut = when {
                    i >= 0 -> i + 1
                    pendingText.length > 48 -> pendingText.indexOfLast { it == '，' || it == ',' }.takeIf { it > 8 }?.plus(1) ?: -1
                    else -> -1
                }
                if (cut <= 0) break
                val sentence = pendingText.substring(0, cut).trim()
                pendingText.delete(0, cut)
                if (sentence.isNotBlank() && sentences.trySend(speakGen to sentence).isSuccess) queued.incrementAndGet()
            }
            if (flush && pendingText.isNotBlank()) { if (sentences.trySend(speakGen to pendingText.toString().trim()).isSuccess) queued.incrementAndGet(); pendingText.clear() }
        }
    }

    private fun dropSpeech() {
        speakGen++
        synchronized(pendingText) { pendingText.clear() }
    }

    private fun startSpeaker() {
        val v = cloneVoice ?: return
        if (speakJob?.isActive == true) return
        speakJob = scope.launch {
            for ((gen, text) in sentences) {
                queued.decrementAndGet()
                if (gen != speakGen || stopped) continue
                _phase.value = Phase.SPEAKING
                runCatching {
                    v.stream(text, { gen != speakGen || stopped }) { b, n ->
                        lastAudioAt = System.currentTimeMillis()
                        track?.write(b, 0, n)
                        PetBus.feedPcm(b, n)
                    }
                }.onFailure { e ->
                    // [Mines-voice-26] Never leave the call silent: back to Gemini's own voice.
                    Log.w(TAG, "clone tts: ${e.message}")
                    cloneOk = false
                    dropSpeech()
                    _error.value = "自定义声音出错，已改回 Gemini 声音：${e.message}"
                }
                if (queued.get() <= 0) {
                    kotlinx.coroutines.delay(500)
                    if (queued.get() <= 0 && _phase.value == Phase.SPEAKING) _phase.value = Phase.LISTENING
                }
            }
        }
    }

    fun start() {
        (appContext ?: bridgeContext)?.let { VoiceDiag.init(it) }
        hopForDrop = false; upSince = 0L
        stopped = false
        hopping = false; hopFails = 0; setupAt = 0L
        startSpeaker()
        retries = 0
        retryJob?.cancel()
        _error.value = null
        routeSel = null
        badRoutes.clear(); rounds = 0; sameRouteDrops = 0
        _allFailed.value = false
        configRetries = 0; safeMode = false; _fallback.value = null; lastServerMsg = ""; lastSent = ""
        connect()
    }


    private fun isSocketLevel(reason: String) = listOf("Socket closed", "reset", "EOF", "refused", "超时", "timeout",
        "Broken pipe", "被重置", "被拒绝", "SocketException", "Connection", "unexpected end").any { reason.contains(it, true) }

    /** [Mines-voice-29] Give up on Live for now; say why in one line; retry when the network changes. */
    private fun giveUp(tried: List<GeminiRoute.Tried>, report: List<String>) {
        _route.value = ""
        _allFailed.value = true
        _error.value = GeminiRoute.plainSummary(tried) + "。\n可以先用「普通语音模式」（本机识别 + 你平时的聊天模型 + 朗读），网络变化时会自动再试 Gemini。" +
            "\n（详细：" + report.takeLast(6).joinToString("；") + "）"
        _phase.value = Phase.ERROR
        _fallback.value = GeminiRoute.plainSummary(tried)
        watchNetwork()
    }

    private fun watchNetwork() {
        val ctx = appContext ?: return
        if (netCb != null) return
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        var last = 0L
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = kick()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = kick()
            private fun kick() {
                val now = System.currentTimeMillis()
                if (now - last < 15_000) return
                last = now
                scope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (stopped || !_allFailed.value) return@launch
                    val k = apiKey
                    val p = GeminiRoute.pick(appContext, k, model)
                    if (p.route != null && !stopped && _allFailed.value) {
                        _allFailed.value = false
                        badRoutes.clear(); rounds = 0
                        routeSel = p.route; _route.value = p.route.label
                        _modelInUse.value = p.model ?: _modelInUse.value
                        _error.value = "网络变了，Gemini 又能连上了（${p.route.label}）"
                        openSocket()
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCb = cb }
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        netCb = null
        runCatching { appContext?.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    fun setMuted(m: Boolean) { _muted.value = m }

    fun sendText(text: String) {
        if (text.isBlank()) return
        append(true, text)
        (liveWs ?: ws)?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    private fun connect() {
        if (!hopForDrop) _phase.value = Phase.CONNECTING
        setupDone = false
        // [Mines-voice-29] ROOT CAUSE of the build-28 「连接断了…原因：SocketException
        // Socket closed（线路：代理 Clash:7890）」: connect() cancelled the old
        // socket while `ws` still pointed at it (the new one is only assigned
        // after the async route pick). OkHttp then reported the cancel as
        // onFailure("Socket closed") on a socket that still counted as current,
        // which started another reconnect — every 重新连接 / settings change /
        // 测试语音 → 重新连接 during a live call produced it. Detach first.
        val old = ws
        ws = null
        if (!hopping) { liveWs = null; runCatching { old?.cancel() } }
        else if (old != null && old !== liveWs) runCatching { old.cancel() }
        if (routeSel == null) {
            // [Mines-voice-26/29] Find a route that really reaches Gemini: port, HTTPS and a Live handshake.
            scope.launch {
                val pick = GeminiRoute.pick(appContext, apiKey, model, badRoutes.keys.toSet()) { step ->
                    _error.value = if (hopForDrop) "网络抖了一下，正在接回…" else step }
                if (stopped) return@launch
                if (pick.keyProblem != null) {
                    _error.value = "Gemini 拒绝了这个 API Key：${pick.keyProblem}（请在 账号连接 → Gemini 重新填写）"
                    _phase.value = Phase.ERROR
                    _fallback.value = "Gemini Key 不对"
                    return@launch
                }
                if (pick.configProblem != null) {
                    _error.value = "Gemini 拒绝了通话设置（配置错误，不是网络问题）：${pick.configProblem}"
                    _phase.value = Phase.ERROR
                    _fallback.value = "Gemini 拒绝了通话设置：${pick.configProblem}"
                    return@launch
                }
                val r = pick.route
                if (r == null) {
                    rounds++
                    if (rounds < MAX_ROUNDS) {
                        badRoutes.clear()
                        _error.value = "所有线路都没连上（第 $rounds 轮），3 秒后再试一轮…\n" + pick.report.takeLast(4).joinToString("\n")
                        retryJob?.cancel()
                        retryJob = scope.launch { kotlinx.coroutines.delay(3000); if (!stopped) connect() }
                        return@launch
                    }
                    giveUp(pick.tried, pick.report)
                    return@launch
                }
                routeSel = r
                _route.value = r.label
                val use = pick.model ?: GeminiRoute.chooseModel(model, pick.models)
                modelChecked = pick.models.isNotEmpty()
                _modelInUse.value = use
                _error.value = if (use != model) "模型 $model 不可用，已改用 $use" else null
                openSocket()
            }
            return
        }
        openSocket()
    }

    private fun openSocket() {
        setupDone = false
        val req = Request.Builder().url(GeminiRoute.wsUrl(routeSel) + "?key=$apiKey").build()
        ws = wsClient().newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastSent = if (safeMode) "setup（精简）" else "setup"
                webSocket.send(setupMessage().toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (webSocket === liveWs && webSocket !== ws) { liveWs = null; return } // old socket of a hop
                if (webSocket === ws) onClosed(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                // A socket we already replaced (reconnect / goAway) is not news.
                // [Mines-voice-26] Keep the real reason: HTTP status of a refused handshake, socket error.
                val why = (response?.let { "HTTP ${it.code} ${it.message} " } ?: "") + GeminiRoute.shortErr(t)
                if (webSocket === liveWs && webSocket !== ws) { liveWs = null; return }
                if (webSocket === ws) onClosed(-1, why)
            }
        })
    }

    private fun onClosed(code: Int, reason: String) {
        if (stopped) return
        // [Mines-38.1] disconnect diary + route stability memory
        routeSel?.label?.let { r ->
            if (upSince > 0) { VoiceDiag.recordUp(r, System.currentTimeMillis() - upSince); upSince = 0L }
            if (setupDone) VoiceDiag.recordDrop(r)
        }
        VoiceDiag.log(if (setupDone) "断开" else if (hopping) "重连失败" else "连接失败", code, reason, routeSel?.label,
            "已通话 ${if (setupAt > 0) (System.currentTimeMillis() - setupAt) / 1000 else 0} 秒 · 续接句柄=${resumeHandle != null}")
        logEvent("连接关闭 code=$code" + (if (reason.isNotBlank()) " 原因=$reason" else "") + (routeSel?.label?.let { " 线路=$it" } ?: ""))
        if (liveWs != null && liveWs === ws) liveWs = null
        // [Mines-voice-31] An established call that drops for a network reason
        // hops silently: audio keeps running (no audible restart), the session
        // resumes with the latest handle, and nothing is shown unless the hop
        // itself fails twice.
        val established = setupAt > 0 && !GeminiLive.isConfigError(code, reason) &&
            !(reason.contains("API key", true) || code == 1008 && reason.contains("key", true))
        // [Mines-38.1] ROOT CAUSE of 「一下断一下断」 (code): build-38 gave a dropped call only 3 silent
        // retries within ~3 s (150 ms / 0.8 s / 2 s). Any network blip longer than that fell through to the
        // failover path: stopAudio() (audible cut), a full route re-probe with 「正在试：直连…」 on screen,
        // and — when the server sent a close code — a fresh session WITHOUT the resumption handle
        // (Gemini started over). Now: up to 8 silent hops over ~25 s with the latest handle, audio and
        // playback never stop, then a route switch that still keeps the handle and the audio.
        if (established && hopFails < 8 && routeSel != null) {
            if (hopping && !setupDone) hopFails++
            hopping = true; hopForDrop = true
            setupDone = false
            retryJob?.cancel()
            logEvent("静默重连（第 ${hopFails + 1} 次，带续接句柄=${resumeHandle != null}）")
            if (hopFails >= 2) _error.value = "网络抖了一下，正在接回…"
            retryJob = scope.launch {
                kotlinx.coroutines.delay(when (hopFails) { 0 -> 150L; 1 -> 500L; 2 -> 1000L; 3 -> 2000L; 4 -> 3000L; 5 -> 4000L; else -> 6000L })
                if (!stopped) openSocket()
            }
            return
        }
        if (established && routeSel != null && resumeHandle != null && !GeminiLive.isConfigError(code, reason)) {
            // hops on this route are used up: try the next route, keep handle + audio running
            val bad = routeSel?.label
            if (bad != null) badRoutes[bad] = reason.ifBlank { "代码 $code" }
            hopping = true; hopFails = 0; hopForDrop = true
            routeSel = null
            _error.value = "网络抖了一下，正在换条路接回…"
            logEvent("这条线路连不回来，换线路续接")
            retryJob?.cancel()
            retryJob = scope.launch { kotlinx.coroutines.delay(300); if (!stopped) connect() }
            return
        }
        hopping = false; hopFails = 0
        stopAudio()
        // [Mines-voice-26] A model the server doesn't take: re-read the list once and switch.
        if (!setupDone && (reason.contains("not found", true) || reason.contains("not supported", true) && reason.contains("model", true)) && !modelSwitchTried) {
            modelSwitchTried = true
            scope.launch {
                val r = routeSel ?: return@launch
                val p = GeminiRoute.probe(r, apiKey)
                val use = GeminiRoute.chooseModel(GeminiLive.DEFAULT_MODEL, p.models.filter { it != _modelInUse.value })
                _error.value = "服务器说：$reason → 改用 $use"
                _modelInUse.value = use
                if (!stopped) openSocket()
            }
            return
        }
        val keyProblem = GeminiLive.isKeyError(code, reason)
        if (keyProblem) {
            _error.value = "Gemini API Key 不对（服务器：$reason），请在 账号连接 里重新填写"
            _phase.value = Phase.ERROR
            _fallback.value = "Gemini Key 不对"
            return
        }
        // [Mines-voice-30] A protocol/config rejection (1007 invalid payload,
        // 1008 policy, 1003, HTTP 400) is NOT a network problem: every route
        // would get the same answer. Stop here, keep the route, show the
        // server's own words. Only socket-level errors fail over.
        if (GeminiLive.isConfigError(code, reason)) {
            retryJob?.cancel()
            val detail = closeDetail(code, reason)
            logEvent("服务器拒绝：$detail")
            // [Mines-voice-35.2] build-35.1 on the phone: the call ran 2 min, then 「Gemini 拒绝了通话设置…：」
            // with nothing after the colon — the reason sat on a 2nd line the call screen never shows.
            // The setup had been accepted on this call, so it is not the setup: open a fresh session
            // (no resumption handle) once or twice; then try the minimal setup; only then give up on
            // Gemini and let VoiceSession move the call to the backup channel.
            if (setupAt > 0 && configRetries < 2) {
                configRetries++
                // [Mines-38.1] first retry resumes the same conversation; only the second starts fresh
                if (configRetries >= 2) resumeHandle = null
                _phase.value = Phase.CONNECTING
                _error.value = "网络抖了一下，正在接回…"
                retryJob = scope.launch { kotlinx.coroutines.delay(300); if (!stopped) openSocket() }
                return
            }
            if (!safeMode) {
                safeMode = true
                resumeHandle = null
                logEvent("改用精简设置再试一次")
                _phase.value = Phase.CONNECTING
                retryJob = scope.launch { kotlinx.coroutines.delay(300); if (!stopped) openSocket() }
                return
            }
            _error.value = "Gemini 拒绝了通话（$detail）"
            _phase.value = Phase.ERROR
            _fallback.value = "Gemini 拒绝了通话（$detail）"
            return
        }
        // [Mines-voice-29] Route failover. A socket that dies before setup, or
        // dies with a socket-level error within 30 s of setup, marks its route
        // bad for this call and the next route is tried at once (instead of
        // reconnecting the same dead proxy with backoff). An established
        // session that drops later reconnects on the same route once (with
        // session resumption); if that fails too, it fails over.
        val label = routeSel?.label
        val age = if (setupAt > 0) System.currentTimeMillis() - setupAt else 0L
        val failOver = !setupDone || (isSocketLevel(reason) && age < 30_000) || sameRouteDrops >= 1
        if (failOver) {
            if (label != null) badRoutes[label] = reason.ifBlank { "代码 $code" }
            routeSel = null
            sameRouteDrops = 0
            _phase.value = Phase.CONNECTING
            _error.value = "线路「${label ?: "?"}」不通：" + reason.ifBlank { "代码 $code" } + "\n正在换下一条线路…"
            retryJob?.cancel()
            retryJob = scope.launch { kotlinx.coroutines.delay(400); if (!stopped) connect() }
            return
        }
        sameRouteDrops++
        retries++
        _phase.value = Phase.CONNECTING
        _error.value = "连接断了，正在重连（线路：${label ?: "?"}）…\n原因：" + reason.ifBlank { "代码 $code" }
        retryJob?.cancel()
        retryJob = scope.launch {
            kotlinx.coroutines.delay(1000)
            if (!stopped) connect()
        }
    }

    /** [Mines-voice-35.2] 「代码 1007：<server reason>」, or the last server message when the reason is empty. */
    private fun closeDetail(code: Int, reason: String): String {
        val r = reason.trim()
        if (r.isNotEmpty()) return "代码 $code：$r"
        return "代码 $code，服务器没写原因" +
            (lastServerMsg.takeIf { it.isNotBlank() }?.let { "；最后一条服务器消息：$it" } ?: "") +
            (lastSent.takeIf { it.isNotBlank() }?.let { "；最后发出：$it" } ?: "")
    }

    private fun setupMessage(): JSONObject {
        val prompt = if (appContext != null) systemPrompt + "\n" + PHONE_PROMPT else systemPrompt
        // [Mines-voice-34] mines_task (hand the request to the Mines agent) is always there; phone tools when allowed.
        return buildSetup(_modelInUse.value, voice, prompt + "\n" + VoiceIntents.PROMPT,
            tools = callTools(appContext != null),
            resumeHandle = resumeHandle, resumption = true, safe = safeMode)
    }

    /** [Mines-voice-34] A note the model reads (not shown as the user's words), e.g. a finished Mines task. */
    fun sendNote(text: String) {
        if (text.isBlank() || stopped) return
        lastSent = "备注文字（${text.take(20)}…）"
        (liveWs ?: ws)?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    /** Execute the model's tool calls (Mines task, open a page, phone control) and send the results back. */
    private fun handleToolCall(calls: JSONArray) {
        // [Mines-voice-34] mines_task works without phone control (uses the app context of the bridge)
        val ctx = appContext ?: bridgeContext
        lastToolAt = System.currentTimeMillis()
        scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                logEvent("工具调用 $name " + args.toString().take(80))
                var failed = false
                val out = if (ctx == null) { failed = true; "没办成：Mines 还没准备好（没有应用上下文）" } else runCatching {
                    when (name) {
                        "mines_task" -> MinesVoiceBridge.start(ctx, args.optString("task")) { result ->
                            val bad = result.startsWith("没办成")
                            sendNote("（Mines 刚完成你交给它的任务，结果如下，请用一两句口语告诉用户" +
                                (if (bad) "；任务失败了，要直说没办成和原因" else "") + "）\n" + result)
                            append(false, (if (bad) "✗ " else "✓ ") + result.take(120))
                        }
                        VoiceIntents.TOOL -> VoiceIntents.open(ctx, args.optString("page")).also { append(false, "→ $it") }
                        "phone_screen" -> com.openminis.app.sandbox.offload.FastUi(ctx).voiceScreen()
                        "phone_do" -> com.openminis.app.sandbox.offload.FastUi(ctx).voiceDo(args.optString("steps"), args.optBoolean("confirmed", false))
                        "phone_open_app" -> com.openminis.app.sandbox.offload.FastUi(ctx).voiceOpenApp(args.optString("name"))
                        else -> { failed = true; "没办成：没有叫 $name 的工具" }
                    }
                }.getOrElse { failed = true; "没办成：${it.message ?: it.javaClass.simpleName}" }
                // [Mines-voice-35.2] a failed tool is said out loud, never silently swallowed
                if (failed) append(false, "✗ $name：" + out.removePrefix("没办成：").take(100))
                val id = c.optString("id")
                if (id.isNotBlank() && cancelledCalls.remove(id)) { logEvent("工具 $name 已被服务器取消，不回结果"); continue }
                responses.put(JSONObject().apply { if (id.isNotBlank()) put("id", id) }.put("name", name)
                    .put("response", JSONObject().put("result", out.take(6000))))
            }
            if (responses.length() == 0) return@launch
            lastSent = "toolResponse（" + (0 until responses.length()).joinToString { responses.getJSONObject(it).optString("name") } + "）"
            val w = liveWs ?: ws
            if (w == null) logEvent("工具结果没发出去：连接已断")
            else w.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            logEvent("已回工具结果 " + lastSent)
        }
    }

    /**
     * [Mines-voice-35.2] Safety net for "答应了却没做" (phone, build-35.1: 「设定好我的声音录入」 →
     * 「我马上为您设置」 and nothing happened). When the user's turn names something Mines can
     * open directly (声纹录入 …) it opens here, whatever the model does. When the model only
     * promised out loud (「我去办 / 马上 / 现在就」) and called no tool in that turn, the request
     * goes to the Mines agent anyway and the transcript says so.
     */
    private fun afterTurn() {
        val ctx = appContext ?: bridgeContext ?: return
        val ls = _lines.value
        val user = ls.lastOrNull { it.user }?.text?.trim().orEmpty()
        val answer = ls.lastOrNull()?.takeIf { !it.user }?.text.orEmpty()
        if (user.isBlank() || user == handledUser) return
        handledUser = user
        val userAt = lastUserLineAt
        val page = VoiceIntents.match(user)
        if (page != null) {
            if (VoiceIntents.recentlyOpened(page)) return
            val r = VoiceIntents.open(ctx, page)
            append(false, "→ $r")
            sendNote("（系统备注：已经替用户打开了「${VoiceIntents.label(page)}」，请简短告诉用户照屏幕提示做，不要再说你会去设置）")
            return
        }
        if (!VoiceIntents.isPromise(answer) || System.currentTimeMillis() - userAt > 60_000) return
        scope.launch {
            // the model may still send its tool call right after the spoken promise (async calling)
            kotlinx.coroutines.delay(2500)
            if (stopped || lastToolAt >= userAt) return@launch
            logEvent("只口头答应、没调用工具 → 直接交给 Mines：" + user.take(40))
            append(false, "（刚才只是口头答应，现在真的交给 Mines 去办）")
            MinesVoiceBridge.start(ctx, user) { result ->
                val bad = result.startsWith("没办成")
                sendNote("（Mines 刚完成用户刚才的请求，结果如下，请用一两句口语告诉用户）\n" + result)
                append(false, (if (bad) "✗ " else "✓ ") + result.take(120))
            }
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        lastServerMsg = summarize(msg)
        msg.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            for (i in 0 until ids.length()) cancelledCalls.add(ids.optString(i))
            logEvent("服务器取消了工具调用 " + (0 until ids.length()).joinToString { ids.optString(it) })
            return
        }
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { handleToolCall(it); return }
        msg.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable", true)) u.optString("newHandle").takeIf { it.isNotBlank() }?.let { resumeHandle = it }
        }
        if (msg.has("goAway")) {
            logEvent("服务器 goAway（剩余 ${msg.optJSONObject("goAway")?.optString("timeLeft") ?: "?"}）→ 提前换新连接")
            // [Mines-voice-31] Server is about to close this connection: open the
            // next one (same route, latest resumption handle) while the old one
            // still plays, switch the mic over on setupComplete, then close the
            // old one. No stopAudio → no audible restart.
            if (!hopping) {
                hopping = true; hopFails = 0
                scope.launch { if (!stopped) openSocket() }
            }
            return
        }
        if (msg.has("setupComplete")) {
            val prev = liveWs
            liveWs = ws
            upSince = System.currentTimeMillis()
            routeSel?.label?.let { VoiceDiag.recordGood(it) }
            val wasDrop = hopping && hopForDrop
            if (hopping) {
                hopping = false; hopFails = 0; hopForDrop = false
                _reconnects.value = _reconnects.value + 1
                VoiceDiag.log("已续接", 0, "", routeSel?.label)
                if (prev != null && prev !== ws) scope.launch { kotlinx.coroutines.delay(1500); runCatching { prev.close(1000, "hop") } }
            }
            setupDone = true
            setupAt = System.currentTimeMillis()
            logEvent((if (prev != null && prev !== ws) "已无缝换到新连接" else "已连接") + " · 声音=$voice · 模型=${_modelInUse.value}" + (routeSel?.label?.let { " · 线路=$it" } ?: ""))
            // [Mines-voice-33] connections are capped (~10 min): hop before the limit even if goAway never comes
            proactiveJob?.cancel()
            val mine = ws
            proactiveJob = scope.launch {
                kotlinx.coroutines.delay(9 * 60_000L)
                if (!stopped && ws === mine && !hopping) { logEvent("连接快到 10 分钟上限 → 提前换新连接"); hopping = true; hopFails = 0; openSocket() }
            }
            rounds = 0; sameRouteDrops = 0
            _allFailed.value = false
            retries = 0
            if (wasDrop) {
                _error.value = "网络抖了一下，已接上"
                scope.launch { kotlinx.coroutines.delay(3000); if (_error.value == "网络抖了一下，已接上") _error.value = null }
            } else _error.value = null
            startAudio()
            if (!wasDrop || _phase.value != Phase.SPEAKING) _phase.value = Phase.LISTENING
            return
        }
        msg.optJSONObject("error")?.let { e ->
            _error.value = e.optString("message", "出错了"); _phase.value = Phase.ERROR
        }
        val sc = msg.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) {
            dropSpeech()
            track?.let { runCatching { it.pause(); it.flush(); it.play() } }
            _phase.value = Phase.LISTENING
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotBlank()) { appendStream(true, it); lastUserLineAt = System.currentTimeMillis() } }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotBlank()) { appendStream(false, it); queueSpeech(it) } }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            if (!inline.optString("mimeType").startsWith("audio/")) continue
            if (cloneOk) continue // the cloned voice speaks the transcript instead (falls back on failure)
            val pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT)
            _phase.value = Phase.SPEAKING
            lastAudioAt = System.currentTimeMillis()
            track?.write(pcm, 0, pcm.size)
            PetBus.feedPcm(pcm, pcm.size)
        }
        if (sc.optBoolean("turnComplete")) {
            queueSpeech("", flush = true)
            runCatching { afterTurn() }.onFailure { Log.w(TAG, "afterTurn", it) }
            if (cloneOk) return
            scope.launch {
                kotlinx.coroutines.delay(600)
                if (System.currentTimeMillis() - lastAudioAt > 500 && _phase.value == Phase.SPEAKING) _phase.value = Phase.LISTENING
            }
        }
    }

    /** Short description of a server message for the error line (never the audio payload). */
    private fun summarize(m: JSONObject): String {
        val k = m.keys().asSequence().toList()
        val sc = m.optJSONObject("serverContent")
        val body = when {
            m.has("error") -> m.optJSONObject("error")?.toString()?.take(160)
            sc != null -> sc.keys().asSequence().filter { it != "modelTurn" }.joinToString(",") +
                (if (sc.has("modelTurn")) ",modelTurn" else "")
            m.has("toolCall") -> m.optJSONObject("toolCall")?.toString()?.take(120)
            else -> m.toString().take(120)
        }
        return k.joinToString(",") + (body?.let { " $it" } ?: "")
    }

    private fun append(user: Boolean, text: String) {
        _lines.value = (_lines.value + Line(user, text)).takeLast(60)
    }

    /** Transcripts arrive in fragments: glue them onto the current turn. */
    private fun appendStream(user: Boolean, frag: String) {
        val cur = _lines.value
        val last = cur.lastOrNull()
        _lines.value = if (last != null && last.user == user) {
            cur.dropLast(1) + last.copy(text = last.text + frag)
        } else {
            (cur + Line(user, frag.trimStart())).takeLast(60)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (micJob?.isActive == true) return
        val outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(outMin * 4, 24000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { t ->
                if (speed != 1f || pitch != 1f) runCatching {
                    t.playbackParams = android.media.PlaybackParams().setSpeed(speed.coerceIn(0.5f, 2f)).setPitch(pitch.coerceIn(0.5f, 2f))
                }
                t.play()
            }

        // [Mines-voice-26] The wake-word listener lets go of the mic when the call opens;
        // wait for it so the call doesn't record silence.
        val until = System.currentTimeMillis() + 2500
        while (PetBus.wakeMicHeld.value && System.currentTimeMillis() < until) Thread.sleep(100)
        val rec = openMic()
        if (rec == null) {
            _error.value = "麦克风打不开（可能被别的应用占用），点 测试语音 看看"
            _phase.value = Phase.ERROR
            return
        }
        // [Mines-voiceprint-34] only the enrolled voice reaches Gemini when 声纹 is on
        val vpCtx = appContext ?: bridgeContext
        val vpGate = vpCtx?.let { runCatching { Voiceprint.gate(it) }.getOrNull() }
        _voiceprintOn.value = vpGate != null
        micJob = scope.launch {
            val buf = ByteArray(3200) // 100 ms
            var r: AudioRecord? = rec
            while (isActive && !stopped) {
                // [Mines-voice-35.2] 声纹录入 (opened from the call) needs the mic: let go of it
                // while it records, take it back afterwards; the call itself stays up.
                if (PetBus.micExclusive.value || PetBus.enrolling.value) {
                    if (r != null) { releaseMic(); r = null; _level.value = 0f; logEvent("麦克风让给声纹录入") }
                    kotlinx.coroutines.delay(200); continue
                }
                if (r == null) {
                    r = openMic()
                    if (r == null) { kotlinx.coroutines.delay(500); continue }
                    logEvent("麦克风已拿回")
                }
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    sum += (s.toShort().toDouble()) * s.toShort()
                    i += 2
                }
                _level.value = (sqrt(sum / (n / 2)) / 6000.0).toFloat().coerceIn(0f, 1f)
                if (_muted.value) continue
                val out = liveWs ?: continue // [Mines-voice-31] between sockets of a hop: drop 100 ms frames
                val frames = if (vpGate != null) runCatching { vpGate.process(buf, n) }.getOrElse { listOf(buf.copyOf(n)) } else listOf(buf.copyOf(n))
                for (f in frames) {
                    val b64 = Base64.encodeToString(f, 0, f.size, Base64.NO_WRAP)
                    out.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val inMin = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, 3200) * 2) }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; return null }
        record = rec
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        rec.startRecording()
        return rec
    }

    private fun releaseMic() {
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
    }

    private fun stopAudio() {
        micJob?.cancel(); micJob = null
        releaseMic()
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        _level.value = 0f
    }

    fun stop() {
        stopped = true
        hopping = false
        runCatching { liveWs?.close(1000, "bye") }; liveWs = null
        dropSpeech()
        speakJob?.cancel(); speakJob = null
        retryJob?.cancel()
        stopAudio()
        val w = ws
        ws = null
        runCatching { w?.close(1000, "bye") }
        unwatchNetwork()
        _phase.value = Phase.IDLE
    }
}

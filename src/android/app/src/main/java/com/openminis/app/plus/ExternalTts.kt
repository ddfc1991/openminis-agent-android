package com.openminis.app.plus

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [Mines-tts-34] Anything that can speak a sentence as 24 kHz mono 16-bit PCM. */
interface TtsSpeaker {
    /** Streams PCM for [text] into [sink]; false when [cancelled] stopped it. Throws on failure. */
    fun stream(text: String, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit): Boolean
}

/**
 * [Mines-tts-34] External voice for the live call (user, 2026-10-13):
 * Gemini Live keeps listening / understanding; its own audio is muted and the
 * transcript is spoken by
 *  1. OpenRouter TTS with the user's OpenRouter key (POST /api/v1/audio/speech,
 *     OpenAI-compatible; models listed by GET /api/v1/models?output_modalities=speech,
 *     each with `supported_voices`), mp3 → decoded on the phone;
 *  2. free Microsoft Edge read-aloud voices (晓晓 / 云希 …, no key) when OpenRouter
 *     fails or the balance runs out (switches automatically, retries OpenRouter after 10 min);
 *  3. the phone's own TextToSpeech engine as the last resort, so a call never goes silent.
 * Speed and pitch are applied on playback (AudioTrack PlaybackParams), the same for every engine.
 */
object ExternalTts {
    private const val TAG = "ExternalTts"
    private const val PREFS = "mines_tts"
    const val ENGINE_GEMINI = "gemini"
    const val ENGINE_OPENROUTER = "openrouter"
    const val ENGINE_EDGE = "edge"
    const val OUT_RATE = 24000

    const val DEFAULT_OR_MODEL = "google/gemini-3.8-flash-tts"
    const val DEFAULT_OR_VOICE = "Kore"
    const val DEFAULT_EDGE_VOICE = "zh-CN-XiaoxiaoNeural"

    /** Free Edge voices worth offering (Chinese first). */
    val edgeVoices = linkedMapOf(
        "zh-CN-XiaoxiaoNeural" to "晓晓 · 女声 温暖",
        "zh-CN-XiaoyiNeural" to "晓伊 · 女声 活泼",
        "zh-CN-YunxiNeural" to "云希 · 男声 阳光",
        "zh-CN-YunjianNeural" to "云健 · 男声 有力",
        "zh-CN-YunyangNeural" to "云扬 · 男声 播音",
        "zh-CN-YunxiaNeural" to "云夏 · 少年",
        "zh-CN-liaoning-XiaobeiNeural" to "晓北 · 东北话",
        "zh-CN-shaanxi-XiaoniNeural" to "晓妮 · 陕西话",
        "zh-TW-HsiaoChenNeural" to "曉臻 · 台湾女声",
        "zh-HK-HiuGaaiNeural" to "曉佳 · 粤语女声",
    )

    data class Settings(
        val engine: String, val orModel: String, val orVoice: String, val edgeVoice: String,
        val speed: Float, val pitch: Float,
    )

    data class OrModel(val id: String, val name: String, val voices: List<String>)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        return Settings(
            p.getString("engine", ENGINE_GEMINI) ?: ENGINE_GEMINI,
            p.getString("or_model", DEFAULT_OR_MODEL) ?: DEFAULT_OR_MODEL,
            p.getString("or_voice", DEFAULT_OR_VOICE) ?: DEFAULT_OR_VOICE,
            p.getString("edge_voice", DEFAULT_EDGE_VOICE) ?: DEFAULT_EDGE_VOICE,
            p.getFloat("speed", 1f), p.getFloat("pitch", 1f),
        )
    }

    fun save(c: Context, s: Settings) {
        prefs(c).edit().putString("engine", s.engine).putString("or_model", s.orModel).putString("or_voice", s.orVoice)
            .putString("edge_voice", s.edgeVoice).putFloat("speed", s.speed).putFloat("pitch", s.pitch).apply()
    }

    /** True when an external voice replaces Gemini's own audio. */
    fun active(c: Context): Boolean = settings(c).engine != ENGINE_GEMINI

    /** Last fallback notice (shown in 语音设置 and on the call screen once). */
    @Volatile var lastNotice: String? = null
    @Volatile private var orDownUntil = 0L

    fun openRouterKey(c: Context): String? {
        ProviderKeys.scan(c).firstOrNull { it.kindId == "openrouter" }?.key?.let { return it }
        PlusVault.init(c)
        return PlusVault.secret("openrouter")
    }

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    /** OpenRouter speech models with their voices (public endpoint, no key needed). */
    suspend fun listOpenRouterModels(): List<OrModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models?output_modalities=speech").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("拿不到模型列表（HTTP ${r.code}）")
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val vs = o.optJSONArray("supported_voices")
                OrModel(o.optString("id"), o.optString("name").ifBlank { o.optString("id") },
                    if (vs == null) emptyList() else (0 until vs.length()).map { vs.optString(it) }.filter { it.isNotBlank() })
            }.filter { it.id.isNotBlank() }
        }
    }

    /** The speaker for the live call, or null when Gemini's own voice is used. */
    fun speaker(c: Context): TtsSpeaker? {
        val s = settings(c)
        if (s.engine == ENGINE_GEMINI) return null
        return ChainSpeaker(c.applicationContext, s)
    }

    /** [Mines-voice-35.2] Voice for the backup channel: the user's external voice, else free Edge (→ phone TTS). */
    fun backupSpeaker(c: Context): TtsSpeaker {
        val s = settings(c)
        return ChainSpeaker(c.applicationContext, if (s.engine == ENGINE_GEMINI) s.copy(engine = ENGINE_EDGE) else s)
    }

    /** Engine chain with automatic fallback. */
    private class ChainSpeaker(private val c: Context, private val s: Settings) : TtsSpeaker {
        override fun stream(text: String, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit): Boolean {
            val clean = text.trim()
            if (clean.isEmpty()) return true
            if (s.engine == ENGINE_OPENROUTER && System.currentTimeMillis() > orDownUntil) {
                val key = openRouterKey(c)
                if (key != null) {
                    try {
                        return emitPcm(openRouterPcm(key, s.orModel, s.orVoice, clean), cancelled, sink)
                    } catch (e: Exception) {
                        orDownUntil = System.currentTimeMillis() + 10 * 60_000L
                        lastNotice = when {
                            e.message.orEmpty().contains("402") || e.message.orEmpty().contains("credit", true) ->
                                "OpenRouter 余额不够了，已换成免费声音"
                            else -> "OpenRouter 声音暂时用不了，已换成免费声音"
                        }
                        Log.w(TAG, "openrouter → edge: ${e.message}")
                    }
                } else lastNotice = "没找到 OpenRouter Key，先用免费声音"
            }
            try {
                return emitPcm(edgePcm(s.edgeVoice, clean), cancelled, sink)
            } catch (e: Exception) {
                Log.w(TAG, "edge → system: ${e.message}")
                lastNotice = "免费声音暂时连不上，先用手机自带朗读"
            }
            return emitPcm(systemPcm(c, clean), cancelled, sink)
        }
    }

    private fun emitPcm(pcm: ShortArray, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit): Boolean {
        val chunk = 2400 // 100 ms
        var i = 0
        val buf = ByteArray(chunk * 2)
        while (i < pcm.size) {
            if (cancelled()) return false
            val n = minOf(chunk, pcm.size - i)
            for (k in 0 until n) { val v = pcm[i + k].toInt(); buf[2 * k] = (v and 0xFF).toByte(); buf[2 * k + 1] = ((v shr 8) and 0xFF).toByte() }
            sink(buf, n * 2)
            i += n
        }
        return true
    }

    private fun decodeTo24k(bytes: ByteArray, ext: String): ShortArray {
        val f = File.createTempFile("tts", ".$ext")
        try {
            f.writeBytes(bytes)
            val (mono, rate) = VoiceClone.decodeFileToMono(f.absolutePath)
            return VoiceClone.resample(mono, rate, OUT_RATE)
        } finally { f.delete() }
    }

    /** OpenRouter /audio/speech → mp3 → 24 kHz PCM. */
    fun openRouterPcm(key: String, model: String, voice: String, text: String): ShortArray {
        val body = JSONObject().put("model", model).put("input", text).put("voice", voice).put("response_format", "mp3")
        val req = Request.Builder().url("https://openrouter.ai/api/v1/audio/speech")
            .header("Authorization", "Bearer $key").header("X-Title", "Mines")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} " + String(bytes.take(200).toByteArray()))
            if (bytes.size < 64) throw IllegalStateException("空的音频")
            return decodeTo24k(bytes, "mp3")
        }
    }

    // ── Edge read-aloud (free, no key) ───────────────────────────────────
    // [Mines-tts-35] Protocol as edge-tts 7.3.1 (github.com/rany2/edge-tts): Sec-MS-GEC v2 token
    // (EdgeDrm) + Sec-MS-GEC-Version=2-<chromium>. build-34 sent the v1 token → the server closed the socket.
    private const val EDGE_TOKEN = EdgeDrm.TRUSTED_CLIENT_TOKEN
    private const val EDGE_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    @Volatile private var edgeSkewSec = 0.0

    internal fun secMsGec(nowSec: Double): String = EdgeDrm.secMsGec(nowSec)

    private fun xmlEscape(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")

    fun edgePcm(voice: String, text: String): ShortArray {
        val mp3 = edgeMp3(voice, text)
        return decodeTo24k(mp3, "mp3")
    }

    /** One retry after a 403 with the server's clock (edge-tts does the same on skew). */
    private fun edgeMp3(voice: String, text: String): ByteArray = try { edgeMp3Once(voice, text) } catch (e: EdgeSkew) { edgeMp3Once(voice, text) }

    private class EdgeSkew : Exception("Edge 403")

    private fun edgeMp3Once(voice: String, text: String): ByteArray {
        val conn = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis() / 1000.0 + edgeSkewSec
        val url = "$EDGE_URL?TrustedClientToken=$EDGE_TOKEN&ConnectionId=$conn&Sec-MS-GEC=${secMsGec(now)}&Sec-MS-GEC-Version=${EdgeDrm.SEC_MS_GEC_VERSION}"
        val major = EdgeDrm.CHROMIUM_MAJOR
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase() + ";")
            .build()
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var err: String? = null
        var skew = false
        val df = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = df.format(Date())
                webSocket.send("X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'>" +
                    "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${xmlEscape(text)}</prosody></voice></speak>"
                webSocket.send("X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) { webSocket.close(1000, null); done.countDown() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val hl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (2 + hl > b.size) return
                val head = String(b, 2, hl, Charsets.UTF_8)
                if (head.contains("Path:audio") && b.size > 2 + hl) synchronized(out) { out.write(b, 2 + hl, b.size - 2 - hl) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                err = "Edge ${response?.code ?: ""} ${t.message ?: ""}".replace(Regex("\\s+"), " ").trim()
                Log.w(TAG, "edge failure: $err")
                // clock skew: follow the server's Date next time (as edge-tts does on 403)
                if (response?.code == 403) response.header("Date")?.let { d ->
                    runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(d)?.time }.getOrNull()
                        ?.let { val adj = it / 1000.0 - System.currentTimeMillis() / 1000.0; if (Math.abs(adj - edgeSkewSec) > 30) { edgeSkewSec = adj; skew = true } }
                }
                done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
        if (!done.await(25, TimeUnit.SECONDS)) { ws.cancel(); throw IllegalStateException("Edge 超时") }
        if (skew) throw EdgeSkew()
        err?.let { throw IllegalStateException(it) }
        val bytes = synchronized(out) { out.toByteArray() }
        if (bytes.size < 64) throw IllegalStateException("Edge 没有返回声音")
        return bytes
    }

    // ── Phone's own TTS (last resort) ────────────────────────────────────
    @Volatile private var sys: TextToSpeech? = null

    private fun systemPcm(c: Context, text: String): ShortArray {
        val tts = sys ?: run {
            val ready = CountDownLatch(1)
            var ok = false
            val t = TextToSpeech(c) { st -> ok = st == TextToSpeech.SUCCESS; ready.countDown() }
            ready.await(5, TimeUnit.SECONDS)
            if (!ok) throw IllegalStateException("手机没有可用的朗读引擎")
            runCatching { t.language = Locale.SIMPLIFIED_CHINESE }
            sys = t; t
        }
        val f = File(c.cacheDir, "tts_sys_${System.nanoTime()}.wav")
        val done = CountDownLatch(1)
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("deprecated in API") override fun onError(utteranceId: String?) { if (utteranceId == id) done.countDown() }
        })
        tts.synthesizeToFile(text, android.os.Bundle(), f, id)
        done.await(30, TimeUnit.SECONDS)
        try {
            if (!f.exists() || f.length() < 64) throw IllegalStateException("手机朗读失败")
            val (mono, rate) = VoiceClone.decodeFileToMono(f.absolutePath)
            return VoiceClone.resample(mono, rate, OUT_RATE)
        } finally { f.delete() }
    }

    /**
     * 试听: plays a sample with the given settings (speed / pitch on playback) through the
     * same chain the call uses. [Mines-tts-35] build-34 only showed 「试听失败：Edge …」 and
     * played nothing; now OpenRouter → Edge → the phone's own TTS, and whatever spoke is returned
     * ("用的是：手机自带朗读（免费声音连不上：…）").
     */
    suspend fun preview(c: Context, s: Settings, text: String = "你好，我是 Mines。以后我就用这个声音和你聊天。"): String = withContext(Dispatchers.IO) {
        lastNotice = null
        val (pcm, used) = synthChain(c, s, text)
        val track = VoiceClone.playbackTrack()
        try {
            if (s.speed != 1f || s.pitch != 1f) runCatching {
                track.playbackParams = android.media.PlaybackParams().setSpeed(s.speed.coerceIn(0.5f, 2f)).setPitch(s.pitch.coerceIn(0.5f, 2f))
            }
            emitPcm(pcm, { false }) { b, n -> track.write(b, 0, n) }
            Thread.sleep(500)
        } finally { runCatching { track.stop() }; track.release() }
        used
    }

    /** PCM + a plain description of which engine produced it (and why an earlier one was skipped). */
    fun synthChain(c: Context, s: Settings, text: String): Pair<ShortArray, String> {
        val skipped = ArrayList<String>()
        if (s.engine == ENGINE_OPENROUTER) {
            val key = openRouterKey(c)
            if (key == null) skipped += "没找到 OpenRouter Key"
            else try { return openRouterPcm(key, s.orModel, s.orVoice, text) to "OpenRouter" }
            catch (e: Exception) { skipped += "OpenRouter 不行：" + plainError(e) }
        }
        try { return edgePcm(s.edgeVoice, text) to ("免费 Edge 声音" + note(skipped)) }
        catch (e: Exception) { skipped += "免费声音连不上：" + plainError(e); Log.w(TAG, "preview edge: ${e.message}") }
        val pcm = systemPcm(c, text)
        lastNotice = "免费声音暂时连不上，先用手机自带朗读"
        return pcm to ("手机自带朗读" + note(skipped))
    }

    private fun note(l: List<String>) = if (l.isEmpty()) "" else "（" + l.joinToString("；") + "）"

    fun plainError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("403") -> "服务拒绝了（403）"
            m.contains("timeout", true) || m.contains("超时") -> "超时"
            m.contains("closed", true) || m.contains("reset", true) || m.contains("EOF", true) -> "连接被断开"
            m.contains("Unable to resolve", true) || m.contains("UnknownHost", true) -> "网络不通"
            else -> m.take(40)
        }
    }

    /** Self-test: Edge only (no fallback). Returns PCM length in samples. */
    fun edgeSelfTest(text: String = "你好"): Int = edgePcm(DEFAULT_EDGE_VOICE, text).size
}

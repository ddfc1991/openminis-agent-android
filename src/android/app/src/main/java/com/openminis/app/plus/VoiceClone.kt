package com.openminis.app.plus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * [Mines-voiceclone-25] Custom voice for the live call: the user imports an
 * audio clip (or a video — its sound track is used), Mines cleans it on the
 * phone (MediaCodec decode → mono → 16 kHz → DC/silence trim → 5–30 s → peak
 * normalise → WAV), transcribes it and enrols it as a voice with a cloning TTS
 * provider. During a call, Gemini Live's text transcript is spoken in that
 * voice, sentence by sentence and streamed, while Gemini's own audio is muted.
 *
 * Provider: SiliconFlow CosyVoice2 (documented upload / speech / transcription
 * APIs: docs.siliconflow.cn → 语音合成). Key from 账号连接 → 硅基流动; nothing
 * is pre-filled. Enrolling a "user preset" voice needs SiliconFlow real-name
 * verification; without it Mines falls back to sending the reference clip with
 * each sentence ("dynamic voice"), which works but uploads more.
 */
object VoiceClone {
    private const val TAG = "VoiceClone"
    const val BASE = "https://api.siliconflow.cn/v1"
    const val MODEL = "FunAudioLLM/CosyVoice2-0.5B"
    const val ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
    const val OUT_RATE = 24000
    private const val REF_RATE = 16000
    const val MIN_SEC = 5
    const val MAX_SEC = 30

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("mines_voice_clone", Context.MODE_PRIVATE)
    fun refFile(c: Context) = File(c.filesDir, "voice_clone_ref.wav")

    data class Settings(val enabled: Boolean, val uri: String, val text: String, val seconds: Float, val speed: Float)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        return Settings(p.getBoolean("enabled", false), p.getString("uri", "") ?: "", p.getString("text", "") ?: "",
            p.getFloat("seconds", 0f), p.getFloat("speed", 1f))
    }
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()
    fun setText(c: Context, t: String) = prefs(c).edit().putString("text", t).apply()
    fun setSpeed(c: Context, s: Float) = prefs(c).edit().putFloat("speed", s).apply()
    fun clear(c: Context) { prefs(c).edit().clear().apply(); refFile(c).delete() }
    fun hasReference(c: Context) = refFile(c).exists() && settings(c).text.isNotBlank()

    fun apiKey(): String? = PlusVault.defaultAccount("siliconflow")?.let { PlusVault.secret(it) } ?: PlusVault.secret("siliconflow")

    /** The voice to use in calls, or null → Gemini's built-in voice. */
    fun speaker(c: Context): Speaker? {
        val s = settings(c)
        val key = apiKey() ?: return null
        if (!s.enabled || !hasReference(c)) return null
        return Speaker(key, s.uri, if (s.uri.isBlank()) refFile(c) else null, s.text, s.speed)
    }

    // ---- 1. import + clean -------------------------------------------------

    /**
     * Decodes the first audio track of [uri] (audio or video file), and writes
     * a cleaned mono 16 kHz WAV reference. Returns its length in seconds.
     */
    suspend fun importClip(c: Context, uri: Uri): Float = withContext(Dispatchers.IO) {
        val pcm = decodeToMono(c, uri)
        val rate = pcm.second
        var mono = resample(pcm.first, rate, REF_RATE)
        mono = removeDc(mono)
        mono = trimSilence(mono, REF_RATE)
        val sec = mono.size / REF_RATE.toFloat()
        if (sec < MIN_SEC) throw IllegalStateException("有效人声只有 ${"%.1f".format(sec)} 秒，至少要 $MIN_SEC 秒（10–30 秒最好）")
        if (sec > MAX_SEC) mono = loudestWindow(mono, REF_RATE * MAX_SEC)
        mono = normalize(mono)
        writeWav(refFile(c), mono, REF_RATE)
        val out = mono.size / REF_RATE.toFloat()
        prefs(c).edit().putFloat("seconds", out).putString("uri", "").putString("text", "").apply()
        out
    }

    private fun decodeToMono(c: Context, uri: Uri): Pair<ShortArray, Int> {
        val ex = MediaExtractor()
        c.contentResolver.openFileDescriptor(uri, "r")?.use { fd -> ex.setDataSource(fd.fileDescriptor) }
            ?: throw IllegalStateException("打不开这个文件")
        return decodeExtractor(ex)
    }

    /** [Mines-tts-34] Decode an audio file (mp3 / wav / ogg …) to mono PCM. */
    internal fun decodeFileToMono(path: String): Pair<ShortArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        return decodeExtractor(ex)
    }

    private fun decodeExtractor(ex: MediaExtractor): Pair<ShortArray, Int> {
        try {
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) throw IllegalStateException("这个文件里没有声音")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            val maxBytes = 2L * 48000 * 2 * 120 // ≤ 2 min of stereo 48 kHz is plenty
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.size > 0) {
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset); ob.get(bytes, 0, info.size)
                            out.write(bytes)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (out.size() > maxBytes) outputDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            runCatching { codec.stop() }; codec.release()
            val raw = out.toByteArray()
            val sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val all = ShortArray(sb.remaining()); sb.get(all)
            val ch = max(1, channels)
            val mono = ShortArray(all.size / ch)
            for (i in mono.indices) {
                var s = 0
                for (k in 0 until ch) s += all[i * ch + k]
                mono[i] = (s / ch).toShort()
            }
            return mono to rate
        } finally { ex.release() }
    }

    internal fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * step
            val j = pos.toInt(); val f = pos - j
            val a = src[min(j, src.size - 1)]; val b = src[min(j + 1, src.size - 1)]
            out[i] = (a + (b - a) * f).toInt().toShort()
        }
        return out
    }

    private fun removeDc(s: ShortArray): ShortArray {
        // one-pole high-pass (~60 Hz at 16 kHz): removes hum and DC offset
        val out = ShortArray(s.size)
        var prevIn = 0.0; var prevOut = 0.0
        val r = 0.976
        for (i in s.indices) {
            val x = s[i].toDouble()
            val y = x - prevIn + r * prevOut
            prevIn = x; prevOut = y
            out[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun rms(s: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += s[i].toDouble() * s[i]
        return sqrt(sum / max(1, to - from))
    }

    /** Drops leading / trailing silence and squeezes pauses longer than 0.6 s. */
    private fun trimSilence(s: ShortArray, rate: Int): ShortArray {
        val win = rate / 50 // 20 ms
        if (s.size < win * 4) return s
        val levels = (0 until s.size / win).map { rms(s, it * win, (it + 1) * win) }
        val floor = levels.sorted()[levels.size / 10]
        val thr = max(300.0, floor * 3)
        val voiced = levels.map { it > thr }
        val first = voiced.indexOfFirst { it }; val last = voiced.indexOfLast { it }
        if (first < 0) return ShortArray(0)
        val out = ShortArray(s.size)
        var w = 0; var quiet = 0
        for (k in max(0, first - 5)..min(levels.lastIndex, last + 5)) {
            quiet = if (voiced[k]) 0 else quiet + 1
            if (quiet > 30) continue // keep at most 0.6 s of any pause
            System.arraycopy(s, k * win, out, w, win); w += win
        }
        return out.copyOf(w)
    }

    private fun loudestWindow(s: ShortArray, len: Int): ShortArray {
        if (s.size <= len) return s
        val step = len / 10
        var best = 0; var bestE = -1.0
        var i = 0
        while (i + len <= s.size) { val e = rms(s, i, i + len); if (e > bestE) { bestE = e; best = i }; i += step }
        return s.copyOfRange(best, best + len)
    }

    private fun normalize(s: ShortArray): ShortArray {
        var peak = 1
        for (v in s) peak = max(peak, abs(v.toInt()))
        val g = (29000.0 / peak).coerceAtMost(8.0)
        return ShortArray(s.size) { (s[it] * g).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    private fun writeWav(f: File, s: ShortArray, rate: Int) {
        val data = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.forEach { data.putShort(it) }
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + s.size * 2); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(s.size * 2)
            raf.write(h.array()); raf.write(data.array())
        }
    }

    // ---- 2. transcribe + enrol --------------------------------------------

    private fun err(code: Int, body: String): String {
        val m = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optJSONObject("error")?.optString("message") ?: "" } } }.getOrDefault("")
        return when (code) {
            401 -> "硅基流动 Key 不对或过期了"
            402, 403 -> "硅基流动拒绝了（余额不足或未实名）：$m"
            429 -> "请求太频繁，稍后再试"
            else -> "硅基流动出错 $code：${m.ifBlank { body.take(120) }}"
        }
    }

    /** What is said in the reference clip (the cloning API needs it). */
    suspend fun transcribe(c: Context): String = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw IllegalStateException("先在 账号连接 里填硅基流动的 Key")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", ASR_MODEL)
            .addFormDataPart("file", "ref.wav", refFile(c).asRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("$BASE/audio/transcriptions").header("Authorization", "Bearer $key").post(body).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException(err(r.code, txt))
            val t = JSONObject(txt).optString("text").trim()
            setText(c, t)
            t
        }
    }

    /**
     * Uploads the reference as a reusable voice (`speech:…` uri). If the
     * account can't enrol (e.g. not real-name verified) the clip is kept and
     * used as a dynamic reference instead; returns a note for the user.
     */
    suspend fun enroll(c: Context): String = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw IllegalStateException("先在 账号连接 里填硅基流动的 Key")
        val s = settings(c)
        if (s.text.isBlank()) throw IllegalStateException("先填参考音频里说的话")
        val b64 = Base64.encodeToString(refFile(c).readBytes(), Base64.NO_WRAP)
        val json = JSONObject().put("model", MODEL).put("customName", "mines-" + (System.currentTimeMillis() / 1000))
            .put("audio", "data:audio/wav;base64,$b64").put("text", s.text)
        val req = Request.Builder().url("$BASE/uploads/audio/voice").header("Authorization", "Bearer $key")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (r.isSuccessful) {
                val uri = JSONObject(txt).optString("uri")
                if (uri.isNotBlank()) {
                    prefs(c).edit().putString("uri", uri).putBoolean("enabled", true).apply()
                    return@withContext "声音已保存到硅基流动，通话会用这个声音"
                }
            }
            Log.w(TAG, "enroll failed ${r.code}: ${txt.take(200)}")
            prefs(c).edit().putString("uri", "").putBoolean("enabled", true).apply()
            "没能保存成预置音色（${err(r.code, txt)}），改为每句话带上参考音频，同样能用"
        }
    }

    // ---- 3. speak -----------------------------------------------------------

    class Speaker(private val key: String, private val voiceUri: String, private val ref: File?, private val refText: String,
                  private val speed: Float) : TtsSpeaker {
        private val refB64: String? by lazy { ref?.let { "data:audio/wav;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }

        /**
         * Streams 16-bit PCM at [OUT_RATE] for [text] into [sink] as it arrives.
         * Returns false when [cancelled] stopped it.
         */
        override fun stream(text: String, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit): Boolean {
            val body = JSONObject().put("model", MODEL).put("input", text.replace(" ", ""))
                .put("response_format", "pcm").put("sample_rate", OUT_RATE).put("stream", true)
                .put("speed", speed.toDouble())
            if (voiceUri.isNotBlank()) body.put("voice", voiceUri)
            else body.put("voice", "").put("references", org.json.JSONArray().put(JSONObject().put("audio", refB64).put("text", refText)))
            val req = Request.Builder().url("$BASE/audio/speech").header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val call = http.newCall(req)
            call.execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException(err(r.code, r.body?.string().orEmpty()))
                val input = r.body?.byteStream() ?: return true
                val buf = ByteArray(4800)
                var carry = -1
                while (true) {
                    if (cancelled()) { call.cancel(); return false }
                    val n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                    if (n < 0) break
                    var len = n
                    if (carry >= 0) { buf[0] = carry.toByte(); len += 1; carry = -1 }
                    if (len % 2 == 1) { carry = buf[len - 1].toInt(); len -= 1 }
                    if (len > 0) sink(buf, len)
                }
            }
            return true
        }
    }

    /** Plays [text] in the cloned voice (settings preview). */
    suspend fun preview(c: Context, text: String = "你好，我是 Mines。以后我就用这个声音陪你聊天。"): Unit = withContext(Dispatchers.IO) {
        val sp = speaker(c) ?: run {
            val key = apiKey() ?: throw IllegalStateException("先在 账号连接 里填硅基流动的 Key")
            if (!hasReference(c)) throw IllegalStateException("先导入一段声音")
            val s = settings(c)
            Speaker(key, s.uri, if (s.uri.isBlank()) refFile(c) else null, s.text, s.speed)
        }
        val track = playbackTrack()
        try { sp.stream(text, { false }) { b, n -> track.write(b, 0, n) } ; Thread.sleep(400) }
        finally { runCatching { track.stop() }; track.release() }
    }

    /** Plays the cleaned reference clip as it will be sent. */
    suspend fun playReference(c: Context): Unit = withContext(Dispatchers.IO) {
        val f = refFile(c); if (!f.exists()) return@withContext
        val bytes = f.readBytes()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(REF_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(REF_RATE).setTransferMode(AudioTrack.MODE_STREAM).build()
        try { t.play(); t.write(bytes, 44, bytes.size - 44); Thread.sleep(300) } finally { runCatching { t.stop() }; t.release() }
    }

    fun playbackTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(min * 4, OUT_RATE)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
    }
}

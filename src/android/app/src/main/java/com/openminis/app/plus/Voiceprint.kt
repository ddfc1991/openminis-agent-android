package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * [Mines-voiceprint-34] On-device voiceprint / speaker verification (user, 2026-10-13:
 * other people's voices were picked up and answers got muddled).
 *
 * Model: 3D-Speaker CAM++ (zh-cn, 16 kHz, 512-dim) run by sherpa-onnx
 * (k2-fsa/sherpa-onnx 1.13.8, static ONNX Runtime). The model (~28 MB) is
 * downloaded once on first enrolment; nothing leaves the phone afterwards.
 *
 * Enrolment: three short phrases → embeddings averaged → saved.
 * Live: [Gate] sits in the call's mic loop. At the start of each utterance it
 * holds ≤ 0.8 s of audio, scores it against the enrolled voice, then either
 * flushes the held frames (pass) or sends silence (someone else). While speech
 * continues it re-checks every ~1.5 s. One CAM++ pass on 1 s of audio is a few
 * tens of ms, so the only added delay is the 0.8 s hold at utterance onset,
 * which is flushed in one burst.
 */
object Voiceprint {
    private const val TAG = "Voiceprint"
    private const val PREFS = "mines_voiceprint"
    const val RATE = 16000
    const val MODEL_NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    private val MODEL_URLS = listOf(
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL_NAME",
        "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL_NAME",
        "https://hf-mirror.com/csukuangfj/speaker-embedding-models/resolve/main/$MODEL_NAME",
    )
    val PHRASES = listOf(
        "你好 Mines，今天帮我看看有什么要处理的。",
        "把明天上午的事情提醒我一下，谢谢。",
        "这是我的声音，只听我说的话。",
    )

    data class Settings(val enabled: Boolean, val threshold: Float, val enrolled: Boolean)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): Settings {
        val p = prefs(c)
        return Settings(p.getBoolean("enabled", false), p.getFloat("threshold", 0.50f), p.getString("embedding", null) != null)
    }

    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("enabled", on).apply() }
    fun setThreshold(c: Context, t: Float) { prefs(c).edit().putFloat("threshold", t.coerceIn(0.25f, 0.80f)).apply() }
    fun clear(c: Context) { prefs(c).edit().remove("embedding").putBoolean("enabled", false).apply() }

    private fun enrolled(c: Context): FloatArray? = prefs(c).getString("embedding", null)?.let { s ->
        runCatching { val a = JSONArray(s); FloatArray(a.length()) { a.getDouble(it).toFloat() } }.getOrNull()
    }

    fun modelFile(c: Context) = File(File(c.filesDir, "voiceprint").apply { mkdirs() }, MODEL_NAME)
    fun hasModel(c: Context) = modelFile(c).let { it.exists() && it.length() > 1_000_000 }

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /**
     * Downloads the CAM++ model once (tries GitHub, then two mirrors). [Mines-voiceprint-35]
     * build-34 showed nothing while it connected (progress only after the first byte) and had no
     * overall time limit, so on a slow/blocked GitHub link the enrolment page sat on
     * 「正在听…」 for minutes. Now: progress from the first moment, 20 s stall limit per read,
     * 150 s cap per source, and a plain reason at the end.
     */
    suspend fun ensureModel(c: Context) = withContext(Dispatchers.IO) {
        if (hasModel(c) || extractor != null) return@withContext
        modelMutex.lock()
        try {
            if (hasModel(c)) return@withContext
            // [Mines-voiceprint-35.3] the model ships inside the APK (assets/models/campplus.onnx): copy, no download
            if (fromAssets(c)) return@withContext
            // [Mines-models-35] mirrored, resumable, checksummed (ModelFetch); plain reason on failure
            _progress.value = "首次使用：下载声纹模型（约 28 MB）…"
            try {
                ModelFetch.fetch(ModelFetch.CAMPP, modelFile(c), File(c.filesDir, "voiceprint/dl")) { _, line -> _progress.value = "声纹模型：$line" }
            } catch (e: Exception) {
                _progress.value = null
                throw IllegalStateException((e.message ?: "声纹模型下载失败").replace("模型下载失败", "声纹模型下载失败") + "（录好的声音会保留）")
            }
            _progress.value = null
        } finally { modelMutex.unlock() }
    }

    private val modelMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * [Mines-38.1] Save took 12.9 s on the emulator: the 28 MB model copy and the ONNX session were both
     * created only when 「保存」 was pressed. Now the enrolment page calls [warm] as soon as it opens:
     * model copy + extractor session are made in the background while the user is still speaking, and
     * the session is reused for every clip and for the call's voice gate.
     */
    @Volatile private var warmJob: kotlinx.coroutines.Job? = null
    fun warm(c: Context) {
        if (extractor != null || warmJob?.isActive == true) return
        val app = c.applicationContext
        warmJob = bgScope.launch {
            runCatching { ensureModel(app); extractor(app) }.onFailure { Log.w(TAG, "warm: ${it.message}") }
        }
    }
    /** Waits for a running [warm] (shows 「正在准备声纹模型…」 meanwhile). */
    suspend fun awaitWarm() { warmJob?.join() }

    const val ASSET = "models/campplus.onnx"
    fun hasAsset(c: Context): Boolean = runCatching { c.assets.open(ASSET).use { true } }.getOrDefault(false)

    /** Copies the bundled model to filesDir (well under a second). False when this build has no bundled model. */
    private fun fromAssets(c: Context): Boolean {
        val dest = modelFile(c)
        val tmp = File(dest.parentFile, "$MODEL_NAME.part")
        return runCatching {
            _progress.value = "正在准备声纹模型…"
            c.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            if (tmp.length() != ModelFetch.CAMPP.size) { tmp.delete(); return false }
            dest.delete(); if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
            _progress.value = null
            true
        }.getOrElse { Log.w(TAG, "asset model: ${it.message}"); tmp.delete(); _progress.value = null; false }
    }

    // ---- [Mines-voiceprint-35.3] recorded but model not ready yet: keep the clips, finish in the background ----
    private fun pendingFile(c: Context) = File(File(c.filesDir, "voiceprint").apply { mkdirs() }, "pending.pcm")
    fun hasPending(c: Context) = pendingFile(c).exists()
    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val _bgState = MutableStateFlow<String?>(null)
    /** null = idle; otherwise a plain line about the background finish (shown on the voice settings) */
    val bgState: StateFlow<String?> = _bgState.asStateFlow()

    fun savePending(c: Context, clips: List<ShortArray>) {
        java.io.DataOutputStream(pendingFile(c).outputStream().buffered()).use { o ->
            o.writeInt(clips.size)
            clips.forEach { cl -> o.writeInt(cl.size); cl.forEach { v -> o.writeShort(v.toInt()) } }
        }
    }

    private fun loadPending(c: Context): List<ShortArray>? = runCatching {
        java.io.DataInputStream(pendingFile(c).inputStream().buffered()).use { i ->
            List(i.readInt()) { ShortArray(i.readInt()) { i.readShort() } }
        }
    }.getOrNull()

    /** Downloads the model (if needed) and turns the kept clips into the voiceprint; notifies 「声纹已开启」. */
    fun finishInBackground(c: Context) {
        val app = c.applicationContext
        if (!hasPending(app) || _bgState.value?.startsWith("正在") == true) return
        _bgState.value = "正在后台准备声纹…"
        bgScope.launch {
            try {
                ensureModel(app)
                val clips = loadPending(app) ?: throw IllegalStateException("录音文件读不出来，请重新录一次")
                val q = saveEnrolment(app, clips)
                pendingFile(app).delete()
                _bgState.value = null
                notify(app, "声纹已开启", if (q > 0.6f) "以后通话只听你的声音。" else "已开启（声音有点不稳，安静的地方重录效果更好）。")
            } catch (e: Throwable) {
                _bgState.value = "声纹还没完成：" + (e.message ?: "出错了").take(120)
                if (e.message?.contains("太短") == true || e.message?.contains("读不出来") == true) pendingFile(app).delete()
            }
        }
    }

    private fun notify(c: Context, title: String, text: String) = runCatching {
        val nm = c.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
        if (nm.getNotificationChannel("mines_voiceprint") == null)
            nm.createNotificationChannel(android.app.NotificationChannel("mines_voiceprint", "声纹", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(73140, android.app.Notification.Builder(c, "mines_voiceprint").setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }

    /** Mic level 0..1 while recording (enrolment page meter). */
    val level = MutableStateFlow(0f)

    /** adb self-test only: "speech" / "silence" replaces the microphone (emulators have no real mic input). */
    @Volatile var inject: String? = null

    class MicProblem(msg: String) : Exception(msg)

    @Volatile private var extractor: com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor? = null

    private fun extractor(c: Context): com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor {
        extractor?.let { return it }
        synchronized(this) {
            extractor?.let { return it }
            val cfg = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(modelFile(c).absolutePath, 2, false, "cpu")
            return com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(null, cfg).also { extractor = it }
        }
    }

    private val embedLock = Any()

    /** Embedding of 16 kHz PCM (normalized to unit length), or null when too short. */
    fun embed(c: Context, pcm: ShortArray, from: Int = 0, len: Int = pcm.size): FloatArray? {
        val ex = try { extractor(c) } catch (t: Throwable) {
            extractor = null
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError) throw IllegalStateException("声纹引擎加载失败（这台手机的 CPU 不支持？）：${t.message?.take(60)}")
            runCatching { modelFile(c).delete() }
            throw IllegalStateException("声纹模型损坏，已删除，请重试（会重新下载）")
        }
        val f = FloatArray(len) { pcm[from + it] / 32768f }
        synchronized(embedLock) {   // [Mines-38.1b] phrases are now embedded in the background; one at a time
            val st = ex.createStream()
            try {
                st.acceptWaveform(f, RATE)
                st.inputFinished()
                if (!ex.isReady(st)) return null
                return normalize(ex.compute(st))
            } finally { st.release() }
        }
    }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val n = sqrt(s).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    /** Records [ms] of 16 kHz mono audio (试一下). Never blocks longer than [ms] + 3 s. */
    suspend fun record(ms: Int): ShortArray = recordUtterance(maxMs = ms, waitSpeechMs = ms, fixed = true)

    /**
     * [Mines-voiceprint-35] Records one utterance: waits ≤ [waitSpeechMs] for speech, stops after
     * 0.7 s of silence once ≥ 1.2 s was spoken, or at [maxMs]. Publishes [level]. Never hangs:
     * non-blocking reads with a deadline, and a plain reason for every failure (no permission /
     * mic held by another app / only silence / nobody spoke).
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUtterance(maxMs: Int = 8000, waitSpeechMs: Int = 6000, fixed: Boolean = false): ShortArray = withContext(Dispatchers.IO) {
        PetBus.micExclusive.value = true
        try {
            // let the wake-word listener release the mic (it checks every ~100 ms)
            val until = System.currentTimeMillis() + 2000
            while (PetBus.wakeMicHeld.value && System.currentTimeMillis() < until) kotlinx.coroutines.delay(50)
            inject?.let { return@withContext injected(it, maxMs, waitSpeechMs) }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200) * 2)
            } catch (e: SecurityException) { throw MicProblem("没有麦克风权限：请在系统设置 → 应用 → Mines → 权限里允许「麦克风」") }
            catch (e: Exception) { throw MicProblem("麦克风打不开：${e.message?.take(40)}") }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw MicProblem("麦克风打不开：可能正被通话或别的应用占用") }
            val out = ShortArray(RATE * maxMs / 1000)
            var got = 0
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicProblem("麦克风被别的应用占用（例如正在通话或录音），先关掉它再试")
                val t0 = System.currentTimeMillis()
                var lastData = t0
                var speechMs = 0; var silenceAfter = 0; var heardAny = false; var peak = 0
                var noise = 200.0
                val frame = ShortArray(1600)
                while (got < out.size) {
                    val now = System.currentTimeMillis()
                    val n = rec.read(frame, 0, minOf(frame.size, out.size - got), AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) throw MicProblem("麦克风出错（代码 $n）")
                    if (n == 0) {
                        if (now - lastData > 2500) throw MicProblem("麦克风没有声音数据：可能被系统或别的应用占用，或权限被收回")
                        kotlinx.coroutines.delay(15); continue
                    }
                    lastData = now
                    System.arraycopy(frame, 0, out, got, n); got += n
                    var sum = 0.0
                    for (i in 0 until n) { val v = frame[i].toInt(); sum += v.toDouble() * v; if (Math.abs(v) > peak) peak = Math.abs(v) }
                    val rms = sqrt(sum / n)
                    level.value = (rms / 4000.0).toFloat().coerceIn(0f, 1f)
                    val ms = n * 1000 / RATE
                    val voiced = rms > maxOf(noise * 2.2, 300.0)
                    if (!voiced && !heardAny) noise = noise * 0.9 + rms * 0.1
                    if (voiced) { heardAny = true; speechMs += ms; silenceAfter = 0 } else if (heardAny) silenceAfter += ms
                    if (fixed) continue
                    if (!heardAny && now - t0 > waitSpeechMs) break
                    if (heardAny && speechMs >= 1200 && silenceAfter >= 700) break
                }
                if (!fixed && !heardAny) throw MicProblem(if (peak == 0) "收到的全是静音：麦克风可能被别的应用占用（唤醒词、通话、录音），关掉后再试"
                    else "没听到你说话（声音太小），靠近手机再读一遍")
            } finally { runCatching { rec.stop() }; rec.release(); level.value = 0f }
            out.copyOf(got)
        } finally { PetBus.micExclusive.value = false }
    }

    /** Synthetic voice for the adb self-test: a gliding harmonic tone with syllable rhythm (or silence). */
    private suspend fun injected(kind: String, maxMs: Int, waitSpeechMs: Int): ShortArray {
        val ms = if (kind == "silence") waitSpeechMs else minOf(maxMs, 2600)
        val out = ShortArray(RATE * ms / 1000)
        val rnd = java.util.Random(kind.hashCode().toLong())
        var ph = 0.0
        for (i in out.indices) {
            val t = i / RATE.toDouble()
            if (kind == "silence") { out[i] = (rnd.nextGaussian() * 3).toInt().toShort(); continue }
            val f0 = 160 + 40 * kotlin.math.sin(2 * Math.PI * 0.7 * t)
            ph += 2 * Math.PI * f0 / RATE
            val env = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 4.0 * t)
            var v = 0.0
            for (h in 1..8) v += kotlin.math.sin(h * ph) / h
            out[i] = (v * env * 5000 + rnd.nextGaussian() * 80).toInt().coerceIn(-32000, 32000).toShort()
        }
        // play it out in real time so the level meter moves like a real recording
        val chunk = RATE / 10
        var i = 0
        while (i < out.size) {
            var sum = 0.0; val e = minOf(out.size, i + chunk)
            for (k in i until e) sum += out[k].toDouble() * out[k]
            level.value = (sqrt(sum / (e - i)) / 4000.0).toFloat().coerceIn(0f, 1f)
            kotlinx.coroutines.delay(100); i = e
        }
        level.value = 0f
        if (kind == "silence") throw MicProblem("收到的全是静音：麦克风可能被别的应用占用（唤醒词、通话、录音），关掉后再试")
        return out
    }

    /**
     * [Mines-38.1b] Each phrase is embedded in the background as soon as it is recorded, so 「保存」 only waits for
     * the last one (smoke run 36: all three embeddings at save time took 9.4 s on the emulator).
     */
    private val pre = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ShortArray, kotlinx.coroutines.Deferred<FloatArray?>>())
    fun prepare(c: Context, clip: ShortArray) {
        val app = c.applicationContext
        if (pre.size > 8) pre.clear()
        pre[clip] = bgScope.async(Dispatchers.Default) {
            runCatching { awaitWarm(); embed(app, clip) }.getOrNull()
        }
    }

    /** Saves the average of the phrase embeddings. Returns the self-similarity (quality hint). */
    fun saveEnrolment(c: Context, clips: List<ShortArray>): Float {
        _progress.value = "正在保存你的声纹…"
        val embs = try {
            clips.mapNotNull { clip ->
                val d = pre.remove(clip)
                (if (d != null) runCatching { kotlinx.coroutines.runBlocking { d.await() } }.getOrNull() else null) ?: embed(c, clip)
            }
        } finally { _progress.value = null }
        if (embs.size < 2) throw IllegalStateException("录到的声音太短或太小，再录一次")
        val dim = embs[0].size
        val sum = FloatArray(dim)
        for (e in embs) for (i in 0 until dim) sum[i] += e[i]
        val avg = normalize(FloatArray(dim) { i -> sum[i] / embs.size })
        val a = JSONArray(); avg.forEach { a.put(it.toDouble()) }
        prefs(c).edit().putString("embedding", a.toString()).putBoolean("enabled", true).apply()
        return embs.map { cosine(it, avg) }.average().toFloat()
    }

    /** Score of a test clip against the enrolled voice (0..1-ish), or null when nothing is enrolled. */
    fun score(c: Context, pcm: ShortArray): Float? {
        val e = enrolled(c) ?: return null
        return embed(c, pcm)?.let { cosine(it, e) }
    }

    /** The live gate for the call's mic, or null when off / not enrolled / model missing. */
    fun gate(c: Context): Gate? {
        val s = settings(c)
        if (!s.enabled || !s.enrolled || !hasModel(c)) return null
        val e = enrolled(c) ?: return null
        return runCatching { extractor(c); Gate(c.applicationContext, e, s.threshold) }.getOrNull()
    }

    /** Last decision, for the call screen ("只听你的声音 · 刚才不是你"). */
    val lastRejectAt = MutableStateFlow(0L)

    class Gate(private val c: Context, private val enrolledEmb: FloatArray, private val threshold: Float) {
        private enum class St { SILENT, PENDING, PASS, BLOCK }
        private var st = St.SILENT
        private val pending = ArrayList<ByteArray>()
        // [Mines-voice-35.2] Speech held back while blocked. build-34/35 replaced it with silence
        // for good, so when a later check said "it IS you" the first 1–2 s were already gone and
        // Gemini heard the sentence without its start (phone: 「冷不冷交通管制…」). Now it is kept
        // (≤ 2.5 s) and sent first when a re-check passes.
        private val held = ArrayList<ByteArray>()
        private val ring = ShortArray(RATE * 2)
        private var ringPos = 0
        private var ringFill = 0
        private var silentMs = 0
        private var voicedSinceCheck = 0
        private var noise = 300.0
        private val silence = ByteArray(3200)

        private fun push(frame: ByteArray, n: Int) {
            var i = 0
            while (i + 1 < n) {
                ring[ringPos] = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort()
                ringPos = (ringPos + 1) % ring.size; if (ringFill < ring.size) ringFill++
                i += 2
            }
        }

        private fun lastSamples(n: Int): ShortArray {
            val k = minOf(n, ringFill)
            return ShortArray(k) { ring[(ringPos - k + it + ring.size) % ring.size] }
        }

        private fun score(): Float = runCatching { embed(c, lastSamples(RATE))?.let { cosine(it, enrolledEmb) } ?: 1f }.getOrDefault(1f)

        /** Frames to forward for this 100 ms mic frame (0..n frames; rejected speech becomes silence). */
        fun process(frame: ByteArray, n: Int): List<ByteArray> {
            val copy = frame.copyOf(n)
            push(copy, n)
            var sum = 0.0; var i = 0
            while (i + 1 < n) { val v = ((copy[i].toInt() and 0xFF) or (copy[i + 1].toInt() shl 8)).toShort().toDouble(); sum += v * v; i += 2 }
            val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
            val voiced = rms > maxOf(noise * 2.5, 350.0)
            if (!voiced) noise = noise * 0.95 + rms * 0.05
            if (voiced) { silentMs = 0; voicedSinceCheck += 100 } else silentMs += 100
            when (st) {
                St.SILENT -> {
                    if (!voiced) return listOf(copy)
                    st = St.PENDING; pending.clear(); pending.add(copy); voicedSinceCheck = 100
                    return emptyList()
                }
                St.PENDING -> {
                    pending.add(copy)
                    if (voicedSinceCheck < 800 && pending.size < 12) return emptyList()
                    val s = score(); voicedSinceCheck = 0
                    return if (s >= threshold) { st = St.PASS; val out = ArrayList(pending); pending.clear(); out }
                    else { st = St.BLOCK; lastRejectAt.value = System.currentTimeMillis(); held.clear(); held.addAll(pending); val k = pending.size; pending.clear(); List(k) { silence.copyOf(n) } }
                }
                St.PASS -> {
                    if (silentMs >= 1200) st = St.SILENT
                    else if (voicedSinceCheck >= 1500) {
                        voicedSinceCheck = 0
                        if (score() < threshold - 0.10f) { st = St.BLOCK; lastRejectAt.value = System.currentTimeMillis(); return listOf(silence.copyOf(n)) }
                    }
                    return listOf(copy)
                }
                St.BLOCK -> {
                    if (silentMs >= 800) { st = St.SILENT; held.clear(); return listOf(copy) }
                    held.add(copy); while (held.size > 25) held.removeAt(0)
                    if (voicedSinceCheck >= 600) {
                        voicedSinceCheck = 0
                        if (score() >= threshold + 0.05f) { st = St.PASS; val out = ArrayList(held); held.clear(); return out }
                    }
                    return listOf(silence.copyOf(n))
                }
            }
        }
    }
}

package com.openminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.openminis.app.MinisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Mines-offline-asr] Free, on-device speech recognition.
 *
 * Why this exists. The two engines that shipped before were both unusable on
 * the device this was written for: the System engine needs a recognition
 * service the ROM actually ships (Chinese ROMs without the Google app have
 * none, so `SpeechRecognizer.isRecognitionAvailable` is false AND the
 * `queryIntentServices` backstop returns empty), and the Provider engine needs
 * a Voice Input group with a paid cloud model. With neither available the
 * voice panel showed "当前没有可用的语音识别" and the mic was dead.
 *
 * Vosk runs the model entirely locally: no network, no key, no per-minute
 * charge, and it streams partial results (unlike the one-shot REST provider
 * path). Model is Apache-2.0 licensed and unpacked once on first use.
 *
 * The protocol mirrors BaiLongma's local Whisper service (manager.js spawns a
 * long-lived recogniser process; here it is an in-process Vosk model instead,
 * because Android gives us no Python).
 */
class OfflineAsrEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "OfflineASR"

        /**
         * Vosk model name. `vosk-model-small-cn-0.22` is ~40 MB unpacked and is
         * the smallest one that is actually usable for Mandarin dictation;
         * `vosk-model-small-en-us-0.15` covers English.
         */
        const val MODEL_ZH = "vosk-model-small-cn-0.22"
        const val MODEL_EN = "vosk-model-small-en-us-0.15"

        /** Where [ensureModel] lands the model. */
        fun modelDir(context: Context, name: String) = File(context.filesDir, name)

        /** True when [name] is unpacked and loadable. */
        fun modelReady(context: Context, name: String): Boolean {
            val d = modelDir(context, name)
            return d.isDirectory && (d.listFiles()?.isNotEmpty() == true)
        }

        /**
         * Download + unpack a model. Idempotent, and safe to call repeatedly:
         * a partial unpack is replaced, not resumed.
         *
         * Blocking — call from a background dispatcher.
         */
        fun ensureModel(context: Context, name: String, onProgress: (Float) -> Unit = {}): Boolean =
            runCatching {
                lastError = null
                // [Mines-offline-asr] vosk-android's StorageService only unpacks a
                // model bundled in assets; it cannot download. Fetch the zip
                // ourselves, unpack into a temp dir, then swap it in, so a
                // half-finished download never reads as a ready model.
                val dest = modelDir(context, name)
                val tmpZip = File(context.cacheDir, "$name.zip.part")
                val tmpDir = File(context.filesDir, "$name.unpacking")
                tmpDir.deleteRecursively()
                if (name == MODEL_ZH) {
                    // [Mines-models-35] mirrored + resumable + checksummed (ModelFetch); the user's realme got
                    // 「离线模型下载失败」 from the single alphacephei.com URL
                    val zip = File(context.filesDir, "models-dl/$name.zip")
                    com.openminis.app.plus.ModelFetch.fetch(com.openminis.app.plus.ModelFetch.VOSK_CN, zip, File(context.filesDir, "models-dl")) { f, line ->
                        lastStatus = line; onProgress(f * 0.9f)
                    }
                    zip.copyTo(tmpZip, overwrite = true); zip.delete()
                } else {
                val conn = (java.net.URL("https://alphacephei.com/vosk/models/$name.zip").openConnection()
                    as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong.coerceAtLeast(1L)
                conn.inputStream.use { input ->
                    tmpZip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f) * 0.9f)
                        }
                    }
                }
                }
                java.util.zip.ZipInputStream(tmpZip.inputStream().buffered()).use { zin ->
                    val root = tmpDir.canonicalPath
                    while (true) {
                        val e = zin.nextEntry ?: break
                        // Strip the archive's top folder (`<name>/...`).
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty()) continue
                        val f = File(tmpDir, rel)
                        if (!f.canonicalPath.startsWith(root)) continue // zip-slip guard
                        if (e.isDirectory) f.mkdirs()
                        else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                    }
                }
                tmpZip.delete()
                dest.deleteRecursively()
                if (!tmpDir.renameTo(dest)) { tmpDir.copyRecursively(dest, overwrite = true); tmpDir.deleteRecursively() }
                onProgress(1f)
                modelReady(context, name)
            }.onFailure { Log.w(TAG, "model download failed: ${it.message}"); lastError = it.message }.getOrDefault(false)

        /** [Mines-models-35] plain reason of the last failed download / current progress line, for the UI. */
        @Volatile var lastError: String? = null
        @Volatile var lastStatus: String? = null

        private const val SAMPLE_RATE = 16_000
        private const val MAX_RECORD_SECONDS = 120
    }

    override val id: String = "offline-vosk"
    override val displayName: String = "离线识别（本机，免费）"
    override val supportsPartialResults: Boolean = true

    /**
     * Cheap: the model directory exists and is non-empty. A model that failed
     * to download leaves an empty or partial directory, which reads as not
     * ready rather than as a broken engine.
     */
    override val isAvailable: Boolean
        get() = !degraded && (modelReady(appContext, MODEL_ZH) || modelReady(appContext, MODEL_EN))

    override val supportedLocales: List<Locale> = listOf(Locale.CHINA, Locale.US)

    @Volatile private var degraded = false
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recording = AtomicBoolean(false)

    override fun markDegraded() { degraded = true }

    override fun clearDegraded() { degraded = false }

    /** Pick the model that matches the requested locale, preferring what is already on disk. */
    private fun modelFor(locale: Locale): String {
        val wantEn = locale.language.equals("en", ignoreCase = true)
        val zh = modelReady(appContext, MODEL_ZH)
        val en = modelReady(appContext, MODEL_EN)
        return when {
            wantEn && en -> MODEL_EN
            !wantEn && zh -> MODEL_ZH
            zh -> MODEL_ZH
            en -> MODEL_EN
            else -> if (wantEn) MODEL_EN else MODEL_ZH
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val name = modelFor(locale)
        if (!modelReady(appContext, name)) {
            listener.onError(
                RecognitionError.UNKNOWN,
                "离线语音包还没下载：$name",
            )
            return
        }
        stopCapture()
        recording.set(true)
        scope.launch {
            try {
                val m = withContext(Dispatchers.IO) { Model(modelDir(appContext, name).absolutePath) }
                model = m
                // 16 kHz mono float grammar off: Vosk's small models are
                // dictation-oriented, and a grammar would reject anything the
                // user says that is not in it.
                val rec = Recognizer(m, SAMPLE_RATE.toFloat())
                recognizer = rec
            } catch (e: Throwable) {
                Log.e(TAG, "model load failed", e)
                recording.set(false)
                listener.onError(RecognitionError.UNKNOWN, "离线模型加载失败：${e.message}")
                return@launch
            }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                recording.set(false)
                listener.onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                return@launch
            }
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (e: Throwable) {
                recording.set(false)
                listener.onError(
                    com.openminis.app.speech.MicInUse.captureFailure(appContext),
                    "AudioRecord init failed: ${e.message}",
                )
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                recording.set(false)
                rec.release()
                listener.onError(
                    com.openminis.app.speech.MicInUse.captureFailure(appContext),
                    "AudioRecord not initialized.",
                )
                return@launch
            }
            recorder = rec
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            var total = 0
            val buf = ByteArray(minBuf)
            val pending = StringBuilder()
            try {
                rec.startRecording()
                listener.onReadyForSpeech()
                while (recording.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    total += n
                    if (total > maxBytes) {
                        listener.onError(RecognitionError.UNKNOWN, "已达到单次录音上限")
                        break
                    }
                    val rms = rmsOf(buf, n)
                    listener.onRmsDb(rms)
                    // acceptWaveForm returns a JSON envelope; a non-empty
                    // "partial" means an interim hypothesis we can show live.
                    if (recognizer?.acceptWaveForm(buf, n) == true) {
                        val text = finalText()
                        if (text.isNotEmpty()) {
                            pending.append(text)
                            listener.onFinal(pending.toString())
                            pending.setLength(0)
                        }
                    } else {
                        val p = partialText()
                        if (p.isNotEmpty()) listener.onPartial(p)
                    }
                }
                // Flush whatever is buffered when the user stops.
                val tail = finalText()
                val rest = pending.toString() + tail
                if (rest.isNotBlank()) listener.onFinal(rest.trim())
            } catch (e: Throwable) {
                Log.e(TAG, "capture failed", e)
                listener.onError(
                    com.openminis.app.speech.MicInUse.captureFailure(appContext),
                    e.message,
                )
            } finally {
                stopCapture()
            }
        }
    }

    override fun stop() {
        // Setting the flag false makes the capture loop exit and flush its
        // final result through the listener.
        recording.set(false)
    }

    override fun cancel() {
        recording.set(false)
        scope.launch { stopCapture() }
    }

    private fun stopCapture() {
        recording.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recognizer?.close() }
        recognizer = null
        // Model is the expensive part (tens of MB mmap'd); keep it cached so
        // the next take starts instantly.
    }

    private fun finalText(): String = runCatching {
        val r = recognizer?.result ?: return ""
        JSONObject(r).optString("text", "")
    }.getOrDefault("")

    private fun partialText(): String = runCatching {
        val r = recognizer?.partialResult ?: return ""
        JSONObject(r).optString("partial", "")
    }.getOrDefault("")

    private fun rmsOf(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort().toInt()
            sum += v.toDouble() * v
            i += 2
        }
        val mean = if (n > 0) sum / (n / 2) else 0.0
        return (10 * kotlin.math.log10(mean.coerceAtLeast(1.0))).toFloat()
    }
}

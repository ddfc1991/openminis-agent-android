package com.openminis.app.plus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.openminis.app.speech.OfflineAsrEngine
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.SpeechRecognitionEngine
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [Mines-stt-29] One utterance → text, with fallbacks. Used by
 * `android-speech listen` and by 普通语音模式 (voice chat without Google).
 *
 * build-28 bug (realme UI 16): `android-speech listen` returned "Client error"
 * while `status` said available. The handler always created the ON-DEVICE
 * recognizer on Android 12+ (`createOnDeviceSpeechRecognizer`). On ROMs whose
 * recognition service has no on-device model (realme / ColorOS ship one only
 * for some languages, or none), creation succeeds but `startListening`
 * answers ERROR_CLIENT (5) or LANGUAGE_NOT_SUPPORTED/UNAVAILABLE (12/13) at
 * once — and there was no second attempt. Order now:
 *   1. the regular system recognizer (the ROM's / Google's service),
 *   2. the on-device recognizer,
 *   3. Vosk offline (model already downloaded, or downloaded now: ~42 MB once),
 * each tried only after the previous one failed for a reason that another
 * engine could fix (not "no speech").
 */
object Listen {
    private const val TAG = "MinesListen"

    data class Result(val text: String?, val engine: String, val error: String? = null, val tried: List<String> = emptyList())

    /** Blocking; never call on the main thread. */
    fun once(context: Context, maxSec: Int = 15, language: String? = null, allowDownload: Boolean = true): Result {
        val app = context.applicationContext
        val tried = ArrayList<String>()
        val systemOk = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
        if (systemOk) {
            val r = system(app, onDevice = false, maxSec = maxSec, language = language)
            tried += "系统识别：" + (r.error ?: "OK")
            if (r.text != null || r.error == NO_SPEECH) return r.copy(tried = tried)
        } else tried += "系统识别：这台手机没有可用的识别服务"
        if (Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)) {
            val r = system(app, onDevice = true, maxSec = maxSec, language = language)
            tried += "本机识别：" + (r.error ?: "OK")
            if (r.text != null || r.error == NO_SPEECH) return r.copy(tried = tried)
        }
        val zh = language == null || !language.startsWith("en")
        val model = if (zh) OfflineAsrEngine.MODEL_ZH else OfflineAsrEngine.MODEL_EN
        if (!OfflineAsrEngine.modelReady(app, model)) {
            if (!allowDownload) {
                tried += "离线识别：语音包没下载"
                return Result(null, "none", "没有能用的语音识别：" + tried.joinToString("；"), tried)
            }
            tried += "离线识别：正在下载语音包（约 42 MB，只下载一次）"
            if (!OfflineAsrEngine.ensureModel(app, model)) {
                return Result(null, "none", "没有能用的语音识别，离线语音包也下载失败：" + tried.joinToString("；"), tried)
            }
        }
        val r = vosk(app, maxSec, if (zh) Locale.CHINA else Locale.US)
        tried += "离线识别：" + (r.error ?: "OK")
        return r.copy(tried = tried)
    }

    const val NO_SPEECH = "没听到说话"

    private fun system(app: Context, onDevice: Boolean, maxSec: Int, language: String?): Result {
        val engine = if (onDevice) "on-device" else "system"
        val latch = CountDownLatch(1)
        val out = AtomicReference(Result(null, engine, "没有结果"))
        val main = Handler(Looper.getMainLooper())
        val ref = AtomicReference<SpeechRecognizer?>(null)
        main.post {
            val rec = runCatching {
                if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                else SpeechRecognizer.createSpeechRecognizer(app)
            }.getOrElse { e -> out.set(Result(null, engine, "建不了识别器：${e.message}")); latch.countDown(); return@post }
            ref.set(rec)
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    out.set(if (t.isNullOrBlank()) Result(null, engine, NO_SPEECH) else Result(t, engine))
                    latch.countDown()
                }
                override fun onError(error: Int) {
                    out.set(Result(null, engine, if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) NO_SPEECH else describe(error)))
                    latch.countDown()
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language ?: Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            }
            runCatching { rec.startListening(intent) }.onFailure { e ->
                out.set(Result(null, engine, "开始识别失败：${e.message}")); latch.countDown()
            }
        }
        if (!latch.await(maxSec + 8L, TimeUnit.SECONDS)) out.set(Result(null, engine, "识别超时"))
        main.post { runCatching { ref.getAndSet(null)?.destroy() } }
        Log.i(TAG, "$engine → ${out.get().error ?: "ok"}")
        return out.get()
    }

    fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "Client error（识别服务拒绝了请求，常见于没有本机语音模型的手机）"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙"
        SpeechRecognizer.ERROR_SERVER -> "识别服务器出错"
        12 -> "不支持这个语言"
        13 -> "这个语言的识别包不可用"
        10 -> "请求太多"
        11 -> "识别服务断开"
        else -> "错误码 $error"
    }

    /** Vosk until ~1.2 s of quiet after speech, or [maxSec]. */
    private fun vosk(app: Context, maxSec: Int, locale: Locale): Result {
        val eng = OfflineAsrEngine(app)
        val acc = StringBuilder()
        val err = AtomicReference<String?>(null)
        var floor = Float.MAX_VALUE
        var spokeAt = 0L
        var quietSince = 0L
        val start = System.currentTimeMillis()
        val ended = CountDownLatch(1)
        eng.start(locale, object : SpeechRecognitionEngine.Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {
                synchronized(acc) { if (text.isNotBlank()) { if (acc.isNotEmpty()) acc.append(' '); acc.append(text.trim()) } }
            }
            override fun onError(error: RecognitionError, message: String?) { err.set(message ?: error.name); ended.countDown() }
            override fun onRmsDb(rms: Float) {
                val now = System.currentTimeMillis()
                if (now - start < 400) { floor = minOf(floor, rms); return }
                if (floor == Float.MAX_VALUE) floor = rms
                if (rms > floor + 12) { spokeAt = now; quietSince = 0L }
                else if (spokeAt > 0 && quietSince == 0L) quietSince = now
                if ((spokeAt > 0 && quietSince > 0 && now - quietSince > 1200) || now - start > maxSec * 1000L ||
                    (spokeAt == 0L && now - start > 6000)) ended.countDown()
            }
        })
        ended.await(maxSec + 5L, TimeUnit.SECONDS)
        eng.stop()
        Thread.sleep(700)
        eng.cancel()
        val t = synchronized(acc) { acc.toString().trim() }
        return when {
            t.isNotEmpty() -> Result(t, "vosk")
            err.get() != null -> Result(null, "vosk", err.get())
            else -> Result(null, "vosk", NO_SPEECH)
        }
    }
}

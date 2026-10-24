package com.openminis.app.plus

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Mines-voice-29] 普通语音模式: voice chat that needs no Google.
 *
 * listen (system recognizer → on-device → Vosk offline, see [Listen]) →
 * the user's normal chat model in a regular Mines chat session (so DeepSeek
 * etc. reached directly, tools included) → read the reply aloud with the
 * system TTS → listen again. Offered by the Live screen when every Gemini
 * route failed.
 */
class PlainVoice(private val context: Context, initialNote: String? = null) {
    /**
     * [Mines-voice-35.2] Small line on the call screen saying the backup channel is in use and
     * why (e.g. 「Gemini 拒绝了通话（代码 1007：…）」); null when the user chose this mode.
     */
    private val _note = MutableStateFlow(initialNote)
    val note: StateFlow<String?> = _note.asStateFlow()
    fun setNote(n: String?) { _note.value = n }
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _lines = MutableStateFlow<List<GeminiLive.Line>>(emptyList())
    val lines: StateFlow<List<GeminiLive.Line>> = _lines.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var sessionId: String? = null

    fun start() {
        if (job?.isActive == true) return
        initTts()
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ttsReady = false
        _phase.value = Phase.IDLE
    }

    private fun initTts() {
        if (tts != null) return
        val latch = CountDownLatch(1)
        tts = TextToSpeech(context.applicationContext) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.SIMPLIFIED_CHINESE }
            latch.countDown()
        }
        scope.launch { latch.await(5, TimeUnit.SECONDS) }
    }

    private suspend fun ensureSession(): String? {
        sessionId?.let { return it }
        val app = context.applicationContext as? MinisApp ?: return null
        val model = app.providerRepository.lastUsedVisibleEntry()?.baseModel?.id
            ?: app.providerRepository.allVisibleEntries().firstOrNull { com.openminis.app.plus.ErrZhCore.chatCapable(it.baseModel.id) }?.baseModel?.id ?: return null
        val s = app.chatRepository.createSession(modelId = model, title = "语音对话")
        runCatching { app.chatRepository.dao.updateSource(s.id, "voice") }
        sessionId = s.id
        return s.id
    }

    private suspend fun loop() {
        var misses = 0
        while (currentCoroutineContextActive()) {
            // [Mines-voice-35.2] 声纹录入 is recording: don't fight it for the mic
            if (PetBus.enrolling.value || PetBus.micExclusive.value) { _status.value = "正在录入声纹，录完继续"; delay(400); continue }
            _phase.value = Phase.LISTENING
            _status.value = "请说话…"
            val heard = withContext(Dispatchers.IO) { Listen.once(context, maxSec = 20) }
            if (heard.text.isNullOrBlank()) {
                if (heard.error == Listen.NO_SPEECH) {
                    misses++
                    if (misses >= 3) { _status.value = "好像没人说话，已暂停。点「继续」再聊。"; _phase.value = Phase.IDLE; return }
                    continue
                }
                _status.value = "听不了：${heard.error}"
                _phase.value = Phase.ERROR
                return
            }
            misses = 0
            add(true, heard.text)
            // [Mines-voice-35.2] 「录入我的声音」 etc.: open the page, don't just talk about it
            val page = VoiceIntents.match(heard.text)
            if (page != null) {
                val r = VoiceIntents.open(context, page)
                add(false, "→ $r")
                _phase.value = Phase.SPEAKING
                speak(if (page == VoiceIntents.VOICEPRINT) "好，已经打开声纹录入，照屏幕提示读三句话就行。" else "好，已经打开${VoiceIntents.label(page)}。")
                delay(1500)
                continue
            }
            _phase.value = Phase.THINKING
            _status.value = "正在想…"
            val sid = ensureSession()
            if (sid == null) { _status.value = "没有可用的聊天模型：先在 设置 → 模型 里添加一个"; _phase.value = Phase.ERROR; return }
            val r = runCatching {
                HeadlessChatRunner.prompt(context, sid,
                    heard.text + "\n（这是语音对话：请用简短口语回答，不要用 Markdown、表格或代码块。）",
                    wait = true, timeoutMs = 120_000)
            }.getOrNull()
            val reply = r?.responseText?.takeIf { it.isNotBlank() && r.status != "Error" && r.status != "Rejected" }
            if (reply == null) {
                _status.value = "模型没有回答（${r?.status ?: "出错"}${r?.responseText?.let { "：" + it.take(80) } ?: ""}）"
                _phase.value = Phase.ERROR
                return
            }
            add(false, reply)
            _phase.value = Phase.SPEAKING
            _status.value = null
            speak(clean(reply))
            delay(250)
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun clean(t: String) = t.replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        .replace(Regex("[*#>`|_]+"), "").replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1").take(1500)

    /**
     * [Mines-voice-35.2] Read the answer with the user's voice choice: cloned voice → external
     * (OpenRouter) → free Edge → phone TTS, the same chain as a Gemini call with an external voice.
     */
    private fun speak(text: String) {
        if (text.isBlank()) return
        val sp = runCatching { VoiceClone.speaker(context) ?: ExternalTts.backupSpeaker(context) }.getOrNull()
        if (sp != null) {
            val ok = runCatching {
                val min = android.media.AudioTrack.getMinBufferSize(24000, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(24000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min * 4, 48000)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
                var bytes = 0L
                try {
                    track.play()
                    for (part in text.split(Regex("(?<=[。！？!?；;\\n])")).map { it.trim() }.filter { it.isNotEmpty() }) {
                        if (job?.isActive != true) break
                        sp.stream(part, { job?.isActive != true }) { b, n -> track.write(b, 0, n); bytes += n; PetBus.feedPcm(b, n) }
                    }
                    // let the buffer play out
                    Thread.sleep(minOf(1500L, 200L + bytes / 48))
                } finally { runCatching { track.stop() }; track.release() }
            }.isSuccess
            if (ok) return
        }
        val t = tts ?: return
        if (!ttsReady) return
        val done = CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.countDown() }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.countDown() }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mines-plain")
        done.await((text.length / 3L + 10).coerceAtMost(180), TimeUnit.SECONDS)
    }

    private fun add(user: Boolean, text: String) {
        _lines.value = (_lines.value + GeminiLive.Line(user, text)).takeLast(40)
    }
}

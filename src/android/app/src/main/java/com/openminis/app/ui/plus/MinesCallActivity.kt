package com.openminis.app.ui.plus

import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.MinesCall
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons

/**
 * [Mines-38.9.12] the Mines 来电 screen (design ws2 ⑤): 挂断 · 转文字 · 接听.
 * [Mines-38.9.12.1] user 2026-10-23: 接听 no longer jumps to the 实时语音 page — the call screen itself becomes the in-call
 * screen (timer, the caller's message read aloud first, then live voice on the same engine as 实时语音 — Gemini Live, or
 * its backup channel —, 静音 / 扬声器 / 挂断, orb + waveform from the voice level, live caption bubbles). 转文字 stays here
 * too: the message and the conversation's latest reply as text bubbles, with a small 「打开对话」 link.
 */
class MinesCallActivity : ComponentActivity() {
    private var tone: Ringtone? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var tts: android.speech.tts.TextToSpeech? = null
    /** RING → TEXT / CALL */
    val mode = MutableStateFlow("ring")
    val note = MutableStateFlow("")
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(true)
    val summary = MutableStateFlow("")
    private var body = ""
    private var sid: String? = null
    private val micAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else note.value = "没有麦克风权限，只能听不能说。可以在系统设置里给 Mines 麦克风权限。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "任务完成" }
        body = intent.getStringExtra("body").orEmpty()
        sid = intent.getStringExtra("sid")
        CallState.last = this
        if (!intent.getBooleanExtra("silent", false)) runCatching {
            tone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }
        h.postDelayed({ stopTone() }, 30_000L)
        h.postDelayed({ if (!isFinishing && mode.value == "ring") finish() }, 45_000L)   // unanswered: give up
        setContent {
            CallScreen(this, title, body,
                onHangUp = { hangUp() },
                onText = { toText() },
                onAnswer = { answer() },
                onOpenChat = { openChat() })
        }
    }

    private fun stopTone() { runCatching { tone?.stop() }; tone = null }

    fun toText() {
        stopTone(); MinesCall.cancel(this); mode.value = "text"
        val s = sid ?: return
        loadSummary(s)
    }

    private fun loadSummary(s: String) {
        Thread({
            runCatching {
                val app = applicationContext as com.openminis.app.MinisApp
                val last = kotlinx.coroutines.runBlocking { app.chatRepository.loadMessages(s).lastOrNull { it.role == "assistant" } } ?: return@runCatching
                val arr = org.json.JSONArray(last.partsJson)
                val txt = buildString { for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; if (o.optString("type") == "text") append(o.optString("value")) } }.trim()
                if (txt.isNotBlank() && txt != body.trim()) summary.value = txt.take(1200)
            }
        }, "mines-call-summary").start()
    }

    fun answer() {
        stopTone(); MinesCall.cancel(this); mode.value = "call"
        CallState.answeredAt = android.os.SystemClock.elapsedRealtime()
        applySpeaker(true)
        // the caller's message first, then the live voice (so the mic does not hear the read-out)
        if (body.isNotBlank()) speakThen(body) { askOrStart() } else askOrStart()
    }

    private fun askOrStart() {
        if (isFinishing || mode.value != "call") return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
        else runCatching { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) }
    }

    private fun startVoice() {
        if (isFinishing || mode.value != "call") return
        val r = runCatching { com.openminis.app.plus.VoiceSession.start(this) }.getOrNull()
        when (r) {
            com.openminis.app.plus.VoiceSession.StartResult.OK, com.openminis.app.plus.VoiceSession.StartResult.ALREADY -> note.value = ""
            // no Gemini key: the same backup channel 实时语音 uses (phone recognition → Mines → read-out)
            else -> { runCatching { com.openminis.app.plus.VoiceSession.startBackup(this, null) }; note.value = "普通语音通道" }
        }
        CallState.voiceStarted = r?.name ?: "BACKUP"
    }

    private fun speakThen(text: String, then: () -> Unit) {
        var done = false
        val fin: () -> Unit = { if (!done) { done = true; h.post { then() } } }
        h.postDelayed({ fin() }, (4_000L + text.length * 260L).coerceAtMost(40_000L))   // never wait forever
        tts = android.speech.tts.TextToSpeech(applicationContext, android.speech.tts.TextToSpeech.OnInitListener { st ->
            val t = tts
            if (st != android.speech.tts.TextToSpeech.SUCCESS || t == null) fin()
            else {
                runCatching { t.language = java.util.Locale.CHINA }
                t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { CallState.spoke = true }
                    override fun onDone(id: String?) { fin() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { fin() }
                })
                if (t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "mines-call") != android.speech.tts.TextToSpeech.SUCCESS) fin()
            }
        })
    }

    fun toggleMute() {
        val m = !muted.value; muted.value = m
        com.openminis.app.plus.VoiceSession.live.value?.setMuted(m)
        com.openminis.app.plus.VoiceSession.backup.value?.let { if (m) it.stop() else it.start() }
    }

    fun toggleSpeaker() { applySpeaker(!speaker.value) }

    @Suppress("DEPRECATION")
    private fun applySpeaker(on: Boolean) {
        speaker.value = on
        runCatching { getSystemService(android.media.AudioManager::class.java)?.isSpeakerphoneOn = on }
    }

    fun hangUp() {
        stopTone(); MinesCall.cancel(this)
        if (mode.value == "call") runCatching { com.openminis.app.plus.VoiceSession.stop(this) }
        finish()
    }

    fun openChat() {
        hangUp()
        runCatching {
            startActivity(Intent(this, com.openminis.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("mines_route", if (!sid.isNullOrBlank()) "chat/$sid" else "sessions"))
        }
    }

    override fun onDestroy() {
        stopTone(); h.removeCallbacksAndMessages(null)
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null
        if (mode.value == "call") runCatching { com.openminis.app.plus.VoiceSession.stop(this) }
        if (CallState.last === this) CallState.last = null
        super.onDestroy()
    }
}

/** smoke / 自检 readout of the last call screen */
object CallState {
    @Volatile var last: MinesCallActivity? = null
    @Volatile var answeredAt = 0L
    @Volatile var voiceStarted = ""
    @Volatile var spoke = false
    fun json(): String = org.json.JSONObject().put("mode", last?.mode?.value ?: "none").put("voice", voiceStarted).put("spoke", spoke)
        .put("muted", last?.muted?.value ?: false).put("speaker", last?.speaker?.value ?: false).put("summary", (last?.summary?.value ?: "").take(80)).toString()
}

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d".format(s / 60, s % 60) }

@Composable
private fun CallScreen(a: MinesCallActivity, title: String, body: String, onHangUp: () -> Unit, onText: () -> Unit, onAnswer: () -> Unit, onOpenChat: () -> Unit) {
    val mode by a.mode.collectAsState()
    val muted by a.muted.collectAsState()
    val speaker by a.speaker.collectAsState()
    val note by a.note.collectAsState()
    val summary by a.summary.collectAsState()
    val live by com.openminis.app.plus.VoiceSession.live.collectAsState()
    val backup by com.openminis.app.plus.VoiceSession.backup.collectAsState()
    val zeroLevel = remember { MutableStateFlow(0f) }
    val noLines = remember { MutableStateFlow(emptyList<com.openminis.app.plus.GeminiLive.Line>()) }
    val level by (live?.level ?: zeroLevel).collectAsState()
    val liveLines by (live?.lines ?: backup?.lines ?: noLines).collectAsState()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(mode) { while (mode == "call") { now = android.os.SystemClock.elapsedRealtime(); delay(500) } }
    val inf = rememberInfiniteTransition(label = "call")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val wave by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val inCall = mode == "call"
    val textMode = mode == "text"
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C1626), Color(0xFF122338), Color(0xFF0A1220))))) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * (if (textMode) 0.14f else 0.30f))
            val base = size.width * (if (textMode) 0.09f else 0.17f)
            val lv = if (inCall) (0.15f + level.coerceIn(0f, 1f)) else 0f
            for (k in 0 until 4) {
                val f = (pulse + k / 4f) % 1f
                drawCircle(Color(0xFF7AE8FF).copy(alpha = 0.22f * (1 - f)), radius = base * (1.2f + 2.4f * f), center = c, style = Stroke(1.5.dp.toPx()))
            }
            val orb = base * 1.25f * (1f + 0.18f * lv)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0xFF9FF0FF), Color(0xFF2B7EA6), Color(0x00102030)), center = c, radius = orb),
                radius = orb, center = c)
            if (inCall) {   // waveform under the orb, driven by the voice level
                val bars = 28; val bw = size.width * 0.6f / bars; val y0 = c.y + base * 1.95f   // [38.9.12.1 e] just under the orb, clear of the title
                for (b in 0 until bars) {
                    val amp = (0.25f + 0.75f * lv) * (0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin(wave + b * 0.55f)))
                    val hh = 6.dp.toPx() + amp * base * 0.7f
                    val x = size.width * 0.2f + b * bw + bw / 2f
                    drawLine(Color(0xFF9FF0FF).copy(alpha = 0.75f), Offset(x, y0 - hh / 2f), Offset(x, y0 + hh / 2f), strokeWidth = bw * 0.45f)
                }
            }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.fillMaxHeight(if (textMode) 0.24f else if (inCall) 0.53f else 0.40f))
            Text("Mines", color = Color.White, fontSize = if (textMode) 22.sp else 30.sp, fontWeight = FontWeight.SemiBold)
            Text(if (inCall) "通话中 " + mmss(now - CallState.answeredAt) else title, color = Color(0xFFB8CCE0), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (note.isNotBlank()) Text(note, color = Color(0xFF8FA6BD), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (!inCall && !textMode && body.isNotBlank()) Text(body, color = Color(0xFFE6EEF6), fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Start, maxLines = 5,
                modifier = Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF))
                    .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp))
            if (textMode || inCall) {
                // caption / transcript bubbles: the message, the latest reply (转文字), then the live lines
                val bubbles = buildList {
                    if (body.isNotBlank()) add(false to body)
                    if (textMode && summary.isNotBlank()) add(false to summary)
                    liveLines.takeLast(30).forEach { add(it.user to it.text) }
                }
                val ls = rememberLazyListState()
                LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) ls.animateScrollToItem(bubbles.size - 1) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp), state = ls, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bubbles) { (user, text) ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                            Text(text, color = Color(0xFFE6EEF6), fontSize = 14.sp, lineHeight = 21.sp,
                                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (user) Color(0x3334B562) else Color(0x1FFFFFFF))
                                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
                if (textMode) Text("打开对话", color = Color(0xFF7AE8FF), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onOpenChat).padding(8.dp).semantics { contentDescription = "打开对话" })
            } else Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 36.dp), horizontalArrangement = if (textMode) Arrangement.Center else Arrangement.SpaceBetween) {
                when {
                    inCall -> {
                        CallButton(if (muted) "取消静音" else "静音", if (muted) "mic-off" else "mic", if (muted) Color(0xFFE6EEF6).copy(alpha = 0.35f) else Color(0xFF3A4656)) { a.toggleMute() }
                        CallButton("挂断", "x", Color(0xFFE5483E), onHangUp)
                        CallButton(if (speaker) "扬声器 开" else "扬声器 关", "volume-2", if (speaker) Color(0xFF2B7EA6) else Color(0xFF3A4656)) { a.toggleSpeaker() }
                    }
                    textMode -> CallButton("关闭", "x", Color(0xFF3A4656), onHangUp)
                    else -> {
                        CallButton("挂断", "x", Color(0xFFE5483E), onHangUp)
                        CallButton("转文字", "messages-square", Color(0xFF3A4656), onText)
                        CallButton("接听", "mic", Color(0xFF34B562), onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallButton(label: String, icon: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(color).clickable(onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center) {
            val ic = MinesIcons.named(icon) ?: MinesIcons.named("mic")!!
            MinesIcon(ic, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

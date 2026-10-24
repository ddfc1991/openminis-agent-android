package com.openminis.app.ui.plus

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.openminis.app.plus.Voiceprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-voiceprint-34] 语音设置 → 「只听我的声音」: on/off, 录入声纹 (three phrases,
 * full-screen page), 试一下 (score), sensitivity, 删除. Everything runs on the phone.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VoiceprintSection() {
    val c = LocalContext.current
    val m = mines()
    var s by remember { mutableStateOf(Voiceprint.settings(c)) }
    var enrol by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val progress by Voiceprint.progress.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // [Mines-38.5] status + action (not a switch that looks like the status), sensitivity in words, no stray dot
        Text("录下你的声音后，通话里别人的说话不会传给 AI。全部在手机上识别，不上传。",
            fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            com.openminis.app.ui.brand.MinesIcon(com.openminis.app.ui.brand.MinesIcons.named(if (s.enrolled) "circle-check" else "mic")!!, null,
                tint = if (s.enrolled) (if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success) else m.ink3, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (s.enrolled) "已录入你的声音" else "还没录入你的声音", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
            if (s.enrolled) GlassTextButton("重新录入") { enrol = true }
        }
        if (!s.enrolled) GlassPill("录入声纹（念 3 句话）", modifier = Modifier.fillMaxWidth()) { enrol = true }
        if (s.enrolled) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通话时只听我的声音", fontSize = MT.Body, color = m.ink)
                    Text(if (s.enabled) "已开启" else "已关闭，谁说话都会传给 AI", fontSize = MT.Sub, color = m.ink2)
                }
                Switch(checked = s.enabled, onCheckedChange = { Voiceprint.setEnabled(c, it); s = Voiceprint.settings(c) },
                    modifier = Modifier.semantics { contentDescription = "通话时只听我的声音" })
            }
            Text("识别要多严格", fontSize = MT.Body, color = m.ink)
            Slider(s.threshold, { Voiceprint.setThreshold(c, (it * 100).toInt() / 100f); s = Voiceprint.settings(c) }, valueRange = 0.30f..0.75f,
                track = { st -> androidx.compose.material3.SliderDefaults.Track(sliderState = st, drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
                modifier = Modifier.semantics { contentDescription = "识别严格程度" })
            Row(Modifier.fillMaxWidth()) {
                Text("宽松（容易认出我）", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.weight(1f))
                Text(when { s.threshold < 0.42f -> "现在：宽松"; s.threshold < 0.58f -> "现在：适中"; else -> "现在：严格" }, fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = m.primaryText)
                Text("严格（不认错人）", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSecondaryPill(if (busy) "正在听…" else "试一下", Modifier.weight(1f)) {
                    if (busy) return@GlassSecondaryPill
                    busy = true; msg = "说一句话（3 秒）…"
                    scope.launch {
                        msg = runCatching {
                            if (!Voiceprint.hasModel(c)) Voiceprint.ensureModel(c)
                            val pcm = Voiceprint.recordUtterance(maxMs = 5000, waitSpeechMs = 5000)
                            val sc = withContext(Dispatchers.Default) { Voiceprint.score(c, pcm) }
                            when {
                                sc == null -> "声音太短，再说长一点"
                                sc >= s.threshold -> "是你 ✓（相似度 ${"%.2f".format(sc)}）"
                                else -> "不像你（相似度 ${"%.2f".format(sc)}），可以调低灵敏度或重新录入"
                            }
                        }.getOrElse { "没测成：${it.message?.take(60)}" }
                        busy = false
                    }
                }
                GlassTextButton("删除声纹", color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) {
                    Voiceprint.clear(c); s = Voiceprint.settings(c); msg = "已删除"
                }
            }
        }
        (progress ?: msg)?.let { Text(it, fontSize = MT.Sub, color = m.ink2) }
        // [Mines-voiceprint-35.3] background finish of a recorded voiceprint: state + 重试
        val bg by Voiceprint.bgState.collectAsState()
        androidx.compose.runtime.LaunchedEffect(bg) { s = Voiceprint.settings(c) }
        bg?.let { b ->
            Text(b, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2)
            if (b.startsWith("声纹还没完成") && Voiceprint.hasPending(c)) PillButton("重试", Modifier.fillMaxWidth(), primary = false) { Voiceprint.finishInBackground(c) }
        }
    }
    if (enrol) VoiceprintEnrolDialog(onDone = { enrol = false; s = Voiceprint.settings(c) })
}

/**
 * [Mines-voiceprint-35] Enrolment page. build-34 hung on 「正在听…」: the button label said
 * "listening" while it was really still connecting to GitHub for the 28 MB model (progress only
 * appeared after the first byte, no overall limit), then recorded a fixed 4 s with no feedback.
 * Now the model downloads in the background from the moment the page opens, each phrase records
 * on its own (level meter, auto-stop after you finish, 6 s timeout with a plain reason), and the
 * clips are kept if the model is still missing so a retry never asks you to read again.
 */
@Composable
fun VoiceprintEnrolDialog(onDone: () -> Unit) {
    val c = LocalContext.current
    val m = mines()
    val danger = toneColors(Tone.DANGER).first
    val scope = rememberCoroutineScope()
    val clips = remember { mutableStateListOf<ShortArray>() }
    var recording by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var modelErr by remember { mutableStateOf<String?>(null) }
    var modelReady by remember { mutableStateOf(Voiceprint.hasModel(c)) }
    var denied by remember { mutableStateOf(false) }
    val progress by Voiceprint.progress.collectAsState()
    // [Mines-voice-35.2] a running call lets go of the mic while this page is open
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.openminis.app.plus.PetBus.enrolling.value = true
        onDispose { com.openminis.app.plus.PetBus.enrolling.value = false }
    }
    val level by Voiceprint.level.collectAsState()
    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasMic = ok; denied = !ok
        msg = if (ok) "可以了，按「开始」读第 1 句" else "没有麦克风权限就录不了。点「去设置打开」，在 权限 → 麦克风 里选「允许」。"
        err = !ok
    }

    fun fetchModel() {
        if (modelReady) return
        modelErr = null
        scope.launch {
            runCatching { Voiceprint.ensureModel(c) }.onSuccess { modelReady = true }.onFailure { modelErr = it.message ?: "声纹模型下载失败" }
        }
    }
    // [Mines-38.1] model + engine session warm up in the background while the user records
    androidx.compose.runtime.LaunchedEffect(Unit) { Voiceprint.warm(c); fetchModel() }

    fun save() {
        saving = true; msg = null; err = false
        // [Mines-voiceprint-35.3] user 2026-10-14 「声纹一直不动」: never wait on the download here. Without the model
        // (a build without the bundled one), the clips are kept and the voiceprint finishes in the background.
        if (!Voiceprint.hasModel(c) && !Voiceprint.hasAsset(c)) {
            runCatching { Voiceprint.savePending(c, clips.toList()); Voiceprint.finishInBackground(c) }
                .onSuccess { msg = "已录好 ✓ 声纹模型下载完会自动完成，可以关掉这个页面，好了会通知你「声纹已开启」。"; done = true }
                .onFailure { msg = "录音没存下来：${it.message}"; err = true }
            saving = false
            return
        }
        scope.launch {
            runCatching {
                if (!Voiceprint.hasModel(c)) Voiceprint.ensureModel(c)
                modelReady = true
                withContext(Dispatchers.Default) { Voiceprint.saveEnrolment(c, clips.toList()) }
            }.onSuccess { q ->
                msg = if (q > 0.6f) "录好了，以后通话只听你的声音。" else "录好了（声音有点不稳，安静的地方重录效果更好）。"
                done = true
            }.onFailure { e ->
                msg = e.message ?: "没保存成功，再试一次"; err = true
                if (e.message?.contains("太短") == true) clips.clear()
            }
            saving = false
        }
    }

    fun recordOne() {
        if (!hasMic) {
            if (denied) {
                runCatching {
                    c.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", c.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                denied = false
            } else ask.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (clips.size >= Voiceprint.PHRASES.size) { save(); return }
        recording = true; msg = null; err = false
        scope.launch {
            runCatching {
                kotlinx.coroutines.withTimeout(15_000) { Voiceprint.recordUtterance(maxMs = 8000, waitSpeechMs = 6000) }
            }.onSuccess { pcm ->
                clips.add(pcm)
                Voiceprint.prepare(c, pcm)
                if (clips.size >= Voiceprint.PHRASES.size) { recording = false; save() }
                else msg = "好的 ✓ 接着读第 ${clips.size + 1} 句"
            }.onFailure { e ->
                msg = when (e) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "等了 15 秒没录完：麦克风可能被占用，关掉别的录音或通话后再试"
                    else -> e.message ?: "没录成，再试一次"
                }
                err = true
                if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { hasMic = false; denied = true }
            }
            recording = false
        }
    }

    Dialog(onDismissRequest = { if (!recording) onDone() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(m.bg)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.height(20.dp))
                Text("录入你的声音", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
                Text("在安静的地方，按「开始」后用平常的声音读出下面这句，读完会自动停。一共三句。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Voiceprint.PHRASES.indices.forEach { i ->
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < clips.size) m.primary else m.hairline))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("${clips.size}/${Voiceprint.PHRASES.size}", fontSize = MT.Body, color = m.ink3,
                        modifier = Modifier.semantics { contentDescription = "已录 ${clips.size} 句，共 ${Voiceprint.PHRASES.size} 句" })
                }
                val phrase = Voiceprint.PHRASES.getOrNull(clips.size)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(m.glassStrong).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (phrase != null) "第 ${clips.size + 1} 句" else "三句都录好了", fontSize = MT.Body, color = m.ink3)
                    Text(phrase ?: if (done) "完成" else "正在保存你的声纹…", fontSize = MT.PageTitle, lineHeight = MT.TitleLH, fontWeight = FontWeight.Medium, color = m.ink)
                }
                LevelMeter(if (recording) level else 0f, recording)
                msg?.let {
                    Text(it, fontSize = MT.Body, lineHeight = MT.BodyLH, color = if (err) danger else m.ink2,
                        modifier = Modifier.semantics { contentDescription = "提示：$it" })
                }
                when {
                    modelReady -> {}
                    modelErr != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modelErr ?: "", fontSize = MT.Body, lineHeight = MT.BodyTightLH, color = danger, modifier = Modifier.weight(1f))
                        PillButton("重试", Modifier, primary = false) { fetchModel() }
                    }
                    else -> Text(progress ?: "正在准备声纹模型…", fontSize = MT.Body, color = m.ink3)
                }
                if (!done) PillButton(
                    when {
                        saving -> "正在保存…"
                        recording -> "正在听…读完会自动停"
                        !hasMic && denied -> "去设置打开麦克风"
                        !hasMic -> "允许使用麦克风"
                        clips.size >= Voiceprint.PHRASES.size -> "重试保存"
                        err -> "再试一次"
                        clips.isEmpty() -> "开始"
                        else -> "读第 ${clips.size + 1} 句"
                    },
                    Modifier.fillMaxWidth(), primary = true, enabled = !recording && !saving) { recordOne() }
                PillButton(if (done) "完成" else "取消", Modifier.fillMaxWidth(), primary = done, enabled = !recording && !saving) { onDone() }
            }
        }
    }
}

/** [Mines-voiceprint-35] 16-bar mic level meter (accessible: announces 听到声音 / 没有声音). */
@Composable
private fun LevelMeter(level: Float, active: Boolean) {
    val m = mines()
    val bars = 16
    val lit = (level * bars * 1.6f).toInt().coerceIn(0, bars)
    Row(Modifier.fillMaxWidth().height(28.dp).semantics {
        contentDescription = if (!active) "音量表：未在录音" else if (lit > 1) "音量表：听到声音" else "音量表：没有声音"
    }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until bars) {
            val h = 8 + (i % 4) * 4 + if (i < lit) 6 else 0
            Box(Modifier.weight(1f).height(h.dp).clip(RoundedCornerShape(3.dp))
                .background(if (active && i < lit) m.primary else m.hairline))
        }
    }
}

package com.openminis.app.ui.plus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.VoiceClone
import kotlinx.coroutines.launch

/**
 * [Mines-voiceclone-25] 自定义声音: import a clip (audio, or the sound of a
 * video), Mines cleans it, transcribes it, enrols it with SiliconFlow
 * CosyVoice2, and calls then speak in that voice. Built-in Gemini voices stay
 * the default; this only takes over when switched on.
 */
@Composable
fun CustomVoiceSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val s = remember(tick) { VoiceClone.settings(context) }
    val hasKey = remember(tick) { VoiceClone.apiKey() != null }
    val hasRef = remember(tick) { VoiceClone.refFile(context).exists() }
    var text by remember(tick) { mutableStateOf(s.text) }
    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    fun importFrom(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            busy = "正在提取和清理声音…"; msg = ""
            runCatching {
                val sec = VoiceClone.importClip(context, uri)
                busy = "正在识别这段话（${"%.0f".format(sec)} 秒）…"
                runCatching { VoiceClone.transcribe(context) }.onFailure { msg = "自动识别没成功：${it.message}。请在下面手动填上这段话。" }
                busy = ""
                if (msg.isBlank()) msg = "已导入 ${"%.0f".format(sec)} 秒。核对一下文字，再点「生成声音」。"
            }.onFailure { busy = ""; msg = "导入失败：${it.message}" }
            tick++
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFrom(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importFrom(it) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("自定义声音（声音克隆）", fontSize = MT.Body)
        Text(
            "导入一段 10–30 秒、只有一个人说话的清楚录音（音频或视频都行），通话就用这个声音说话。" +
                "用硅基流动的 CosyVoice2，需要在 账号连接 → 硅基流动 填 Key（按字数计费）。只克隆你有权使用的声音。",
            fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasKey) Text("还没有硅基流动的 Key：先去 账号连接 填写。", fontSize = MT.Body, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = busy.isBlank(), onClick = { pickAudio.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) { Text("导入音频") }
            OutlinedButton(enabled = busy.isBlank(), onClick = { pickVideo.launch(arrayOf("video/*")) }, modifier = Modifier.weight(1f)) { Text("从视频提取") }
        }
        if (busy.isNotBlank()) Text(busy, fontSize = MT.Body)
        if (hasRef) {
            Text("参考声音：${"%.0f".format(s.seconds)} 秒（已转单声道 16 kHz、去静音、音量归一）", fontSize = MT.Caption)
            OutlinedTextField(text, { text = it; VoiceClone.setText(context, it) }, label = { Text("这段录音里说的话") },
                minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { runCatching { VoiceClone.playReference(context) } } }) { Text("听原声") }
                Button(enabled = hasKey && text.isNotBlank() && busy.isBlank(), onClick = {
                    scope.launch {
                        busy = "正在生成声音…"
                        msg = runCatching { VoiceClone.enroll(context) }.getOrElse { "生成失败：${it.message}" }
                        busy = ""; tick++
                    }
                }) { Text(if (s.enabled) "重新生成" else "生成声音") }
                TextButton(enabled = hasKey && busy.isBlank(), onClick = {
                    scope.launch {
                        busy = "试听中…"
                        runCatching { VoiceClone.preview(context) }.onFailure { msg = "试听失败：${it.message}" }
                        busy = ""
                    }
                }) { Text("试听克隆") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通话用这个声音", fontSize = MT.Body)
                    Text(if (s.uri.isNotBlank()) "已保存为预置音色" else "每句话带上参考音频（未实名也能用）", fontSize = MT.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(s.enabled, onCheckedChange = { VoiceClone.setEnabled(context, it); tick++ })
            }
            Text("克隆声音语速 ${"%.1f".format(s.speed)}×", fontSize = MT.Body)
            Slider(s.speed, { VoiceClone.setSpeed(context, (it * 10).toInt() / 10f); tick++ }, valueRange = 0.7f..1.5f)
            TextButton(onClick = { VoiceClone.clear(context); msg = "已删除自定义声音，通话改回 Gemini 自带的声音"; tick++ }) {
                Text("删除自定义声音", color = MaterialTheme.colorScheme.error)
            }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = MT.Body)
    }
}

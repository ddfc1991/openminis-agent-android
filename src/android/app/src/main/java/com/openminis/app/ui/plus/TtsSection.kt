package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.openminis.app.plus.ExternalTts
import kotlinx.coroutines.launch

/**
 * [Mines-tts-34] 语音设置 → 「说话的声音」: Gemini 自带 / OpenRouter（你的 Key）/ 免费声音（Edge）,
 * model + voice pickers, speed / pitch sliders (shown whenever an external voice is on) and 试听.
 * When OpenRouter fails or runs out of credit the call switches to the free voice by itself.
 */
@Composable
fun TtsSection(onChanged: () -> Unit, only: String? = null, key: Int = 0) {
    val c = LocalContext.current
    val m = mines()
    var s by remember(key) { mutableStateOf(ExternalTts.settings(c)) }  // [Mines-38.1] re-read after a character switch
    var models by remember { mutableStateOf<List<ExternalTts.OrModel>>(emptyList()) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(n: ExternalTts.Settings) { s = n; ExternalTts.save(c, n); onChanged() }
    // [Mines-voice-36] only = one source's own list (語音设置 picks the source above; nothing else shown)
    val eng = only ?: s.engine
    LaunchedEffect(eng) {
        if (eng == ExternalTts.ENGINE_OPENROUTER && models.isEmpty()) {
            runCatching { ExternalTts.listOpenRouterModels() }.onSuccess { models = it; loadErr = null }
                .onFailure { loadErr = "模型列表拿不到，先用默认的（${it.message?.take(60)}）" }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (only == null) Text("说话的声音", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink2)
        if (only == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ExternalTts.ENGINE_GEMINI to "Gemini 自带", ExternalTts.ENGINE_OPENROUTER to "OpenRouter", ExternalTts.ENGINE_EDGE to "免费声音")
                .forEach { (id, label) ->
                    val sel = s.engine == id
                    Text(label, fontSize = MT.Body, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) m.onPrimary else m.ink,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                            .background(if (sel) m.primary else m.surface2)
                            .clickable {
                                save(s.copy(engine = id))
                                // the cloned voice would mute these: one external voice at a time
                                if (id != ExternalTts.ENGINE_GEMINI) com.openminis.app.plus.VoiceClone.setEnabled(c, false)
                            }
                            .padding(vertical = 12.dp)
                            .semantics { contentDescription = label + if (sel) "（已选）" else "" },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                }
        }
        when (eng) {
            ExternalTts.ENGINE_GEMINI -> Text("用 Gemini 实时语音自己的声音（下面选声音）。", fontSize = MT.Caption, color = m.ink3)
            ExternalTts.ENGINE_OPENROUTER -> {
                Text("用你在 AI 服务商里的 OpenRouter Key。余额用完或出错时，自动换成免费声音。", fontSize = MT.Caption, lineHeight = MT.BodyTightLH, color = m.ink3)
                if (ExternalTts.openRouterKey(c) == null) Text("还没找到 OpenRouter Key", fontSize = MT.Caption, color = MinesDesign.Danger)
                loadErr?.let { Text(it, fontSize = MT.Caption, color = m.ink3) }
                PickerRow("模型", models.firstOrNull { it.id == s.orModel }?.name ?: s.orModel) { showModels = !showModels }
                if (showModels) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(m.surface2)
                    .verticalScroll(rememberScrollState())) {
                    (if (models.isEmpty()) listOf(ExternalTts.OrModel(ExternalTts.DEFAULT_OR_MODEL, ExternalTts.DEFAULT_OR_MODEL, listOf(ExternalTts.DEFAULT_OR_VOICE))) else models)
                        .forEach { mo ->
                            Text(mo.name, fontSize = MT.Body, color = if (mo.id == s.orModel) m.primaryText else m.ink,
                                fontWeight = if (mo.id == s.orModel) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    save(s.copy(orModel = mo.id, orVoice = mo.voices.firstOrNull { v -> v == s.orVoice } ?: mo.voices.firstOrNull() ?: s.orVoice))
                                    showModels = false
                                }.padding(horizontal = 14.dp, vertical = 12.dp))
                        }
                }
                val voices = models.firstOrNull { it.id == s.orModel }?.voices.orEmpty()
                if (voices.isNotEmpty()) {
                    Text("声音", fontSize = MT.Caption, color = m.ink2)
                    ChipFlow(voices.take(40), s.orVoice) { save(s.copy(orVoice = it)) }
                }
            }
            ExternalTts.ENGINE_EDGE -> {
                Text("微软 Edge 朗读的免费声音，不用 Key。", fontSize = MT.Caption, color = m.ink3)
                ExternalTts.edgeVoices.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (s.edgeVoice == id && s.engine == ExternalTts.ENGINE_EDGE) m.container else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { save(s.copy(edgeVoice = id)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = MT.Body, color = if (s.edgeVoice == id) m.onContainer else m.ink, modifier = Modifier.weight(1f))
                        if (s.edgeVoice == id && s.engine == ExternalTts.ENGINE_EDGE) Text("✓", fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = m.icon)
                    }
                }
            }
        }
        if (eng != ExternalTts.ENGINE_GEMINI) {
            Text("语速 ${"%.1f".format(s.speed)}×", fontSize = MT.Body, color = m.ink)
            Slider(s.speed, { save(s.copy(speed = (it * 10).toInt() / 10f)) }, valueRange = 0.6f..1.8f)
            Text("音调 ${"%.1f".format(s.pitch)}", fontSize = MT.Body, color = m.ink)
            Slider(s.pitch, { save(s.copy(pitch = (it * 10).toInt() / 10f)) }, valueRange = 0.7f..1.4f)
            PillButton(busy ?: "试听", Modifier.fillMaxWidth(), primary = true, enabled = busy == null) {
                busy = "正在合成…"; msg = null
                scope.launch {
                    msg = runCatching { "用的是：" + ExternalTts.preview(c, s) }.getOrElse { e ->
                        "试听失败：" + ExternalTts.plainError(e) + "（手机自带朗读也不可用，可在系统设置里装一个朗读引擎）"
                    }
                    busy = null
                }
            }
        }
        (msg ?: ExternalTts.lastNotice)?.let { Text(it, fontSize = MT.Caption, color = m.ink2) }
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(m.surface2).clickable(onClick = onClick)
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = MT.Body, color = m.ink2)
        Text(value, fontSize = MT.Body, color = m.ink, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 12.dp),
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("›", fontSize = MT.PageTitle, color = m.ink3)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<String>, selected: String, onPick: (String) -> Unit) {
    val m = mines()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { v ->
            val sel = v == selected
            Text(v.substringBefore(':'), fontSize = MT.Caption, color = if (sel) m.surface else m.ink,
                modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (sel) m.ink else m.glassStrong).border(1.dp, m.hairline, RoundedCornerShape(50))
                    .clickable { onPick(v) }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}


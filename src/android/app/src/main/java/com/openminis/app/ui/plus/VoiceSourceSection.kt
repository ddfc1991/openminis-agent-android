package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.ExternalTts
import com.openminis.app.plus.GeminiLive
import com.openminis.app.plus.PlusVault
import com.openminis.app.plus.VoiceClone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [Mines-voice-36] 语音设置 rebuilt (user 2026-10-14 13:31: 「这个页面太乱了，应该选哪个就用那个的页面。
 * 这样选了也不知道用谁」 — a Gemini voice and a free voice were both ticked).
 *
 * One line on top says exactly what the call speaks with (「现在用：免费声音 · 晓晓 女声 温暖」 + 试听);
 * below it the voice SOURCE is picked first (Gemini 自带 / OpenRouter / 免费声音 / 我的克隆声音); only that
 * source's own voices and settings are shown, the others are hidden. A source that cannot work (no key)
 * is greyed with 「去连接」. The whole page carries a single tick.
 */
@Composable
fun VoiceSourceSection(onChanged: () -> Unit, character: String? = null) {
    val c = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    // [Mines-38.1] the voice belongs to one AI character (Kimi / Claude / …): load its choice into the
    // live settings, and store every change back for that character only.
    val ch = character ?: com.openminis.app.plus.CharacterVoices.current()
    val chName = com.openminis.app.plus.Avatars.name(ch)
    // [Mines-38.1b] apply the character's voice BEFORE the first read, so the highlighted source card is the one
    // in use (smoke run 36: 「Gemini 自带」 was outlined while 免费声音 said 正在用 — two selections at once)
    remember(ch) { runCatching { com.openminis.app.plus.CharacterVoices.apply(c, ch) }; ch }
    fun changed() { com.openminis.app.plus.CharacterVoices.saveFromGlobals(c, ch); tick++; onChanged() }
    // the clone section toggles its own switch; re-read the stored choice now and then so the top line stays true
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val tts = remember(tick) { ExternalTts.settings(c) }
    val clone = remember(tick) { VoiceClone.settings(c) }
    val active = if (clone.enabled) SRC_CLONE else tts.engine
    var tab by rememberSaveable(ch) { mutableStateOf(active) }
    // an unavailable source is never the starting card
    LaunchedEffect(ch) { if (tab != active) tab = active }
    val geminiVoice = remember(tick) {
        PlusVault.extra("gemini", "voice").takeIf { PlusVault.extra("gemini", "voice_user") == "1" } ?: GeminiLive.DEFAULT_VOICE
    }
    val hasGemini = PlusVault.accountsOf("gemini").any { PlusVault.hasSecret(it) } || PlusVault.hasSecret("gemini")
    val hasOr = ExternalTts.openRouterKey(c) != null
    val hasSf = VoiceClone.apiKey() != null
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun activate(id: String) {
        if (id == SRC_CLONE) {
            VoiceClone.setEnabled(c, true)
            ExternalTts.save(c, ExternalTts.settings(c).copy(engine = ExternalTts.ENGINE_GEMINI))
        } else {
            VoiceClone.setEnabled(c, false)
            ExternalTts.save(c, ExternalTts.settings(c).copy(engine = id))
        }
        changed()
    }

    val nowText = when (active) {
        ExternalTts.ENGINE_OPENROUTER -> "OpenRouter · " + tts.orVoice.substringBefore(':')
        ExternalTts.ENGINE_EDGE -> "免费声音 · " + (ExternalTts.edgeVoices[tts.edgeVoice] ?: tts.edgeVoice).replace(" · ", " ")
        SRC_CLONE -> "我的克隆声音"
        else -> "Gemini 自带 · " + (GeminiLive.voiceLabels[geminiVoice]?.replace(" · ", " ") ?: geminiVoice)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- what the call uses right now ----
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(m.container).heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$chName 的声音：$nowText" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$chName 的声音", fontSize = MT.Caption, color = m.onContainer.copy(alpha = 0.85f), maxLines = 1)
                Text(nowText, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.onContainer, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            if (active != ExternalTts.ENGINE_GEMINI) {
                Spacer(Modifier.width(8.dp))
                WarmPrimaryPill(if (busy) "播放中…" else "试听", enabled = !busy, height = 40.dp, fontSize = 14,
                    icon = com.openminis.app.ui.brand.MinesIcons.named("volume-2"), description = "试听现在用的声音", onClick = {
                        busy = true; msg = null
                        scope.launch {
                            msg = runCatching {
                                if (active == SRC_CLONE) { VoiceClone.preview(c); null } else "用的是：" + ExternalTts.preview(c, ExternalTts.settings(c))
                            }.getOrElse { e -> "试听失败：" + ExternalTts.plainError(e) }
                            busy = false
                        }
                    })
            }
        }
        if (active == ExternalTts.ENGINE_GEMINI) Text("Gemini 自带的声音接通后就能听到。", fontSize = MT.Caption, color = m.ink2)
        msg?.let { Text(it, fontSize = MT.Caption, color = m.ink2) }

        // ---- 1. pick the source ----
        Text("声音来源", fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.padding(top = 4.dp))
        val sources = listOf(
            Src(ExternalTts.ENGINE_GEMINI, "Gemini 自带", "实时通话原声", hasGemini, "gemini"),
            Src(ExternalTts.ENGINE_OPENROUTER, "OpenRouter", "用你的 Key", hasOr, "openrouter"),
            Src(ExternalTts.ENGINE_EDGE, "免费声音", "不用 Key", true, null),
            Src(SRC_CLONE, "我的克隆声音", if (VoiceClone.hasReference(c)) "已录好" else "先导入一段录音", hasSf, "siliconflow"),
        )
        sources.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    SourceCard(s, shown = tab == s.id, using = active == s.id, modifier = Modifier.weight(1f)) {
                        tab = s.id
                        val ready = s.available && (s.id != SRC_CLONE || VoiceClone.hasReference(c))
                        if (ready && active != s.id) activate(s.id)
                    }
                }
            }
        }

        // ---- 2. only that source's own voices + settings ----
        val cur = sources.first { it.id == tab }
        if (!cur.available) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(m.surface2).heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(when (cur.id) {
                    ExternalTts.ENGINE_GEMINI -> "还没有 Gemini Key，连上后才能用它的声音"
                    ExternalTts.ENGINE_OPENROUTER -> "还没有 OpenRouter Key，连上后才能用"
                    else -> "克隆声音要用硅基流动的 Key"
                }, fontSize = MT.Caption, color = m.ink, modifier = Modifier.weight(1f))
                cur.connect?.let { k ->
                    Spacer(Modifier.width(8.dp))
                    WarmPrimaryPill("去连接", height = 36.dp, fontSize = 13, onClick = { PlusNav.openKind = k; PlusNav.go(PlusRoutes.CONNECTIONS) })
                }
            }
            if (cur.id == SRC_CLONE) CustomVoiceSection()
        } else when (cur.id) {
            ExternalTts.ENGINE_GEMINI -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(m.surface2.copy(alpha = 0.6f))) {
                GeminiLive.voiceLabels.entries.forEachIndexed { i, (id, label) ->
                    val on = active == ExternalTts.ENGINE_GEMINI && geminiVoice == id
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (on) m.container else Color.Transparent)
                        .clickable {
                            PlusVault.setExtra("gemini", "voice", id); PlusVault.setExtra("gemini", "voice_user", "1")
                            if (active != ExternalTts.ENGINE_GEMINI) activate(ExternalTts.ENGINE_GEMINI) else { changed() }
                        }
                        .semantics(mergeDescendants = true) { contentDescription = label + (if (on) "，正在用" else ""); role = Role.RadioButton }
                        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = MT.Body, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) m.onContainer else m.ink, maxLines = 1)
                            Text(id, fontSize = MT.Caption, color = if (on) m.onContainer.copy(alpha = 0.8f) else m.ink2, maxLines = 1)
                        }
                        if (on) Text("✓", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.icon)
                    }
                    if (i < GeminiLive.voiceLabels.size - 1) WarmDivider(start = 14.dp)
                }
            }
            ExternalTts.ENGINE_OPENROUTER, ExternalTts.ENGINE_EDGE -> TtsSection(onChanged = { changed() }, only = cur.id, key = tick)
            SRC_CLONE -> CustomVoiceSection()
        }
    }
}

private const val SRC_CLONE = "clone"

private data class Src(val id: String, val title: String, val sub: String, val available: Boolean, val connect: String?)

@Composable
private fun SourceCard(s: Src, shown: Boolean, using: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(18.dp)
    val alpha = if (s.available) 1f else 0.55f
    Column(
        modifier.heightIn(min = 60.dp).clip(shape).background(if (shown) m.container else minesGlassFill(strong = true))
            .border(if (shown) 1.5.dp else 1.dp, if (shown) m.primary else m.hairline, shape).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = s.title + "，" + (if (!s.available) "不可用，要先连接" else s.sub) + (if (using) "，正在用" else ""); role = Role.Tab
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = (if (shown) m.onContainer else m.ink).copy(alpha = alpha), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (using) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(m.icon)) }
        }
        Text(if (!s.available) "要先连接" else if (using) "正在用" else s.sub, fontSize = MT.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = (if (shown) m.onContainer else m.ink2).copy(alpha = if (s.available) 0.9f else 0.7f))
    }
}

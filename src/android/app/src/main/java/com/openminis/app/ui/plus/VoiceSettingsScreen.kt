package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.Avatars
import com.openminis.app.plus.CharacterVoices
import com.openminis.app.plus.PlusVault
import com.openminis.app.plus.VoiceDiag

/**
 * [Mines-38.1] 设置 → 语音: 每个 AI 的声音 (one row per character: face, name, its own voice; tap = the same
 * 声音 sheet as the call's ⋯), 声纹, 通话里操作手机, 通话线路 (optional relay, route stats) and the
 * disconnect diary with one-tap copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val m = mines()
    VoiceDiag.init(c)
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    PlusPage("语音设置", onBack = onBack) {
        PlusSection("每个 AI 的声音", footer = "换到哪个 AI，就用它自己的声音。") {
            val chars = Avatars.builtins.map { it.id } + (if (Avatars.hasCustom(c)) listOf(Avatars.CUSTOM) else emptyList())
            chars.forEachIndexed { i, id ->
                @Suppress("UNUSED_EXPRESSION") tick
                val ch = CharacterVoices.get(c, id)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editing = id }
                    .semantics(mergeDescendants = true) { contentDescription = "${Avatars.name(id)} 的声音：${CharacterVoices.label(c, ch)}，点按更换" }
                    .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PetAvatar(size = 36.dp, style = id, animated = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Avatars.name(id), fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                        Text(CharacterVoices.label(c, ch), fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (id == CharacterVoices.current()) Text("正在用", fontSize = MT.Meta, color = m.primaryText,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(m.container).padding(horizontal = 8.dp, vertical = 2.dp))
                    Text("›", fontSize = MT.PageTitle, color = m.ink3, modifier = Modifier.padding(start = 8.dp))
                }
                if (i < chars.size - 1) WarmDivider(start = 62.dp)
            }
        }
        PlusSection("只听你的声音") { Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { VoiceprintSection() } }
        PlusSection("通话") {
            var phone by remember { mutableStateOf(PlusVault.extra("gemini", "phone") != "0") }
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通话里可以操作手机", fontSize = MT.Body, color = m.ink)
                    Text("打开应用、点按钮、输入文字；付款和发消息前会先口头问你", fontSize = MT.Sub, color = m.ink2)
                }
                androidx.compose.material3.Switch(phone, { phone = it; PlusVault.setExtra("gemini", "phone", if (it) "1" else "0") })
            }
        }
        // [Mines-38.5] 中转地址 / 线路统计 / 断线记录 live under one 「高级」 fold (most people never need them)
        var adv by remember { mutableStateOf(false) }
        PlusSection(null) {
            PlusRow(WarmIcons.of("settings"), "高级", if (adv) "收起" else "通话线路、断线记录", chevron = false,
                trailing = { com.openminis.app.ui.brand.MinesIcon(WarmIcons.of(if (adv) "chevron-up" else "chevron-down")!!, null, tint = m.ink3, modifier = Modifier.size(20.dp)) },
                onClick = { adv = !adv })
        }
        if (adv) PlusSection("通话线路", footer = "Gemini 在大陆和香港直连不稳。填你自己的中转地址，Mines 会自动比较直连和中转，记住断得少的那条。密钥加密传输，中转看不到。") {
            var relay by remember { mutableStateOf(com.openminis.app.plus.GeminiRoute.relaySetting()) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(relay, { relay = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("中转地址（可不填）") }, placeholder = { Text("例如 你的服务器:443") })
                GlassPill("保存", modifier = Modifier.fillMaxWidth()) {
                    PlusVault.setExtra("gemini", "relay", relay); FeedTasks.toast(c, if (relay.isBlank()) "已清除中转" else "已保存，下次通话会先比较直连和中转")
                }
                Text(VoiceDiag.summary(), fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
            }
        }
        if (adv) PlusSection("断线记录", footer = "遇到「一下断一下断」时点复制，发给开发者。") {
            val text = remember(tick) { VoiceDiag.text(c, 60) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text.lines().takeLast(9).joinToString("\n"), fontSize = MT.Caption, lineHeight = MT.CaptionLH, fontFamily = FontFamily.Monospace, color = m.ink2,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(m.surface2).padding(10.dp))
                // two equal 48 dp buttons on one line (were a filled pill next to a smaller outlined one)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill("复制全部", modifier = Modifier.weight(1f)) {
                        val cm = c.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Mines 语音断线记录", VoiceDiag.text(c, 200)))
                        FeedTasks.toast(c, "已复制")
                    }
                    GlassSecondaryPill("清空", Modifier.weight(1f)) { VoiceDiag.clear(c); tick++ }
                }
            }
        }
    }
    editing?.let { id ->
        MinesSheet(onDismiss = {
                editing = null; tick++
                CharacterVoices.apply(c)  // back to the current character's voice
            }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${Avatars.name(id)} 的声音", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
                VoiceSourceSection(onChanged = { tick++ }, character = id)
            }
        }
    }
}


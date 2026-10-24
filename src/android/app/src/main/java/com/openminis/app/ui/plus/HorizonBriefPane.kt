package com.openminis.app.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.Horizon
import com.openminis.app.plus.HorizonCore

/** [Mines-38.9.11 c] 消息 › 每日简报 tab: today's Horizon-style briefing, the source list, and the knobs (all inline, no popups). */
const val BRIEF_TAB = "BRIEF"

@Composable
fun HorizonBriefPane(onOpenWeb: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val brief by Horizon.brief.collectAsState()
    val busy by Horizon.busy.collectAsState()
    val stage by Horizon.stage.collectAsState()
    var shownDay by remember { mutableStateOf<String?>(null) }
    var dayBrief by remember { mutableStateOf<HorizonCore.Brief?>(null) }
    var cat by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (brief == null) Horizon.load(ctx)
        val have = Horizon.brief.value
        // opened with no briefing for today → build it now (pull-to-refresh on the page rebuilds it too)
        if (have == null || have.day != Horizon.today()) Horizon.runAsync(ctx)
    }
    val b = (if (shownDay != null) dayBrief else null) ?: brief

    Column(Modifier.fillMaxWidth()) {
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("每日简报" + (b?.day?.let { " · $it" } ?: ""), fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
                Spacer(Modifier.height(4.dp))
                val line = when {
                    busy -> stage.ifBlank { "正在准备…" }
                    b == null -> Horizon.lastError.ifBlank { "还没有简报" }
                    else -> "从 ${b.collected} 条里挑出 ${b.items.size} 条值得读的" + (if (b.model.isNotBlank()) " · ${b.model}" else " · 没连上模型，按热度挑选")
                }
                Text(line, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2,
                    modifier = Modifier.semantics { contentDescription = "简报状态：$line" })
                // [38.9.11.3] Chinese lines only; the raw English only behind 「复制详情」
                b?.notes?.takeIf { it.isNotEmpty() && !busy }?.let { n -> Text(n.map { com.openminis.app.plus.ErrZhCore.legacyNote(it) }.joinToString("；"), fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink3, modifier = Modifier.padding(top = 4.dp)) }
                if (!busy && Horizon.details.isNotEmpty()) Text("复制详情", fontSize = MT.Caption, color = m.primaryText, modifier = Modifier.padding(top = 2.dp).clickable {
                    runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Mines 简报详情", Horizon.details.joinToString("\n"))) }
                    android.widget.Toast.makeText(ctx, "已复制详情", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(vertical = 8.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassPill(if (busy) "正在生成…" else "重新生成", enabled = !busy) { shownDay = null; Horizon.runAsync(ctx) }
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "收起设置" else "来源与设置", fontSize = MT.Button, color = m.primaryText) }
                }
                Text("简报也存在 共享/每日简报/ 里，手机的文件选择器能看到", fontSize = MT.Meta, color = m.ink3, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (editing) HorizonSettings()

        // earlier days
        val days = remember(brief, busy) { Horizon.days(ctx) }
        if (days.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.take(10).forEachIndexed { i, d ->
                val sel = (shownDay ?: days.first()) == d
                FilterChip(selected = sel, onClick = {
                    if (i == 0) { shownDay = null; dayBrief = null } else { shownDay = d; dayBrief = Horizon.load(ctx, d) }
                }, label = { Text(if (i == 0) "今天" else d.substring(5), fontSize = MT.Meta) })
            }
        }
        if (b != null && b.items.isNotEmpty()) {
            val cats = HorizonCore.CATEGORIES.filter { c -> b.items.any { it.category == c } }
            if (cats.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("全部 ${b.items.size}", fontSize = MT.Meta) })
                cats.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text("$c ${b.items.count { it.category == c }}", fontSize = MT.Meta) }) }
            }
            b.items.filter { cat == null || it.category == cat }.forEach { BriefCard(it, onOpenWeb) }
        } else if (b != null && !busy) {
            Text("今天没有达到门槛的内容。可以在「来源与设置」里加来源或调低门槛。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun BriefCard(it: HorizonCore.BriefItem, onOpenWeb: (String) -> Unit) {
    val m = mines()
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = if (it.url.isNotBlank()) ({ onOpenWeb(it.url) }) else null) {
        Column(Modifier.padding(16.dp)) {
            Text(it.title, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text("${it.category} · ${"%.1f".format(it.score)} 分 · ${it.sources.joinToString(" / ")}", fontSize = MT.Meta, color = m.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Text(it.summary, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            if (it.background.isNotBlank()) Labeled("背景", it.background)
            if (it.discussion.isNotBlank()) Labeled("网友讨论", it.discussion)
            if (it.url.isNotBlank()) Text("打开原文 ›", fontSize = MT.Caption, color = m.primaryText, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Labeled(label: String, text: String) {
    val m = mines()
    Text(label, fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.padding(top = 10.dp))
    Text(text, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2)
}

/** the user's own source list (add by pasting a link / r/版块 / @用户 / owner/repo, switch on/off, remove) + threshold, length, auto */
@Composable
private fun HorizonSettings() {
    val ctx = LocalContext.current
    val m = mines()
    var list by remember { mutableStateOf(Horizon.sources(ctx)) }
    var input by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("AI") }
    var err by remember { mutableStateOf<String?>(null) }
    var thr by remember { mutableStateOf(Horizon.threshold(ctx)) }
    var max by remember { mutableStateOf(Horizon.maxItems(ctx)) }
    var auto by remember { mutableStateOf(Horizon.autoOn(ctx)) }
    fun save(n: List<HorizonCore.Source>) { list = n; Horizon.setSources(ctx, n) }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("我的来源", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2)
            list.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = MT.Body, color = m.ink)
                        Text("${s.kind.zh} · ${s.category}" + (if (s.value.isNotBlank() && s.kind != HorizonCore.Kind.RSS) " · ${s.value}" else ""), fontSize = MT.Meta, color = m.ink3)
                    }
                    Switch(checked = s.on, onCheckedChange = { on -> save(list.map { if (it.id == s.id) it.copy(on = on) else it }) },
                        modifier = Modifier.semantics { contentDescription = "${s.name} ${if (s.on) "已开启" else "已关闭"}" })
                    TextButton(onClick = { save(list.filterNot { it.id == s.id }) }) { Text("删除", fontSize = MT.Caption, color = m.ink2) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("加来源：链接 / r/版块 / @用户 / owner/repo / t.me/频道", fontSize = MT.Caption) })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizonCore.CATEGORIES.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c, fontSize = MT.Meta) }) }
            }
            err?.let { Text(it, fontSize = MT.Caption, color = m.ink2) }
            GlassPill("添加", enabled = input.isNotBlank()) {
                val s = HorizonCore.parseSourceLine(input, cat)
                if (s == null) err = "看不懂这个来源。可以贴 RSS 链接、reddit.com/r/…、x.com/…、github.com/…、t.me/…"
                else if (list.any { it.id == s.id }) err = "已经有这个来源了"
                else { save(list + s); input = "" }
            }
            Spacer(Modifier.height(14.dp))
            Text("门槛（低于这个分不进简报）", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5f, 6f, 7f, 8f).forEach { v -> FilterChip(selected = thr == v, onClick = { thr = v; Horizon.setThreshold(ctx, v) }, label = { Text("${v.toInt()} 分", fontSize = MT.Meta) }) }
            }
            Text("每天最多几条", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 15, 20, 30).forEach { v -> FilterChip(selected = max == v, onClick = { max = v; Horizon.setMaxItems(ctx, v) }, label = { Text("$v 条", fontSize = MT.Meta) }) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { auto = !auto; Horizon.setAuto(ctx, auto) }, verticalAlignment = Alignment.CenterVertically) {
                Text("每天早上 7 点后自动生成", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it; Horizon.setAuto(ctx, it) })
            }
            TextButton(onClick = { save(HorizonCore.DEFAULTS) }) { Text("恢复默认来源", fontSize = MT.Caption, color = m.ink2) }
        }
    }
}

package com.openminis.app.ui.plus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [Mines-kb] 知识库: list of knowledge bases, import, try a search. */
@Composable
fun KnowledgeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bases by KnowledgeBase.bases.collectAsState()
    remember { KnowledgeBase.load(ctx); 0 }
    var openId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var defaultOn by remember { mutableStateOf(KnowledgeBase.defaultOn(ctx)) }

    val open = bases.firstOrNull { it.id == openId }
    if (open != null) {
        KnowledgeDetail(open, onBack = { openId = null })
        return
    }

    if (showNew) AlertDialog(
        onDismissRequest = { showNew = false },
        title = { Text("新建知识库") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("名字（如 工作资料、论文）") }, singleLine = true) },
        confirmButton = {
            Button(onClick = { val kb = KnowledgeBase.create(ctx, newName); newName = ""; showNew = false; openId = kb.id }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } },
    )

    // [Mines-38.5] 列表 (default) / 脑图 switch in the top bar
    // [Mines-38.7] the knowledge home IS the 脑图 (user 2026-10-15); 「列表」 shows the classic list
    var mapView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val kEntries by com.openminis.app.plus.ChatKnowledge.entries.collectAsState()
    // [Mines-38.8 v5] 脑图 view = 「星空脑图」: deep-space page with light text (dark colours just for this page)
    val pageM = mines()
    val galaxyM = remember(pageM.character.id) { minesColors(pageM.character.id, true) }
    // [Mines-38.8 v5d] the 星空 page is dark in both themes: light status-bar icons while it shows, the previous ones back on leave / 列表
    val sbView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(mapView) {
        val win = (sbView.context as? android.app.Activity)?.window
        val ctl = win?.let { androidx.core.view.WindowInsetsControllerCompat(it, sbView) }
        val prev = if (mapView) ctl?.isAppearanceLightStatusBars else null
        if (mapView) ctl?.isAppearanceLightStatusBars = false
        onDispose { if (prev != null) ctl?.isAppearanceLightStatusBars = prev }
    }
    if (mapView) androidx.compose.runtime.SideEffect {
        (sbView.context as? android.app.Activity)?.window?.let { androidx.core.view.WindowInsetsControllerCompat(it, sbView).isAppearanceLightStatusBars = false }
    }
    // [Mines-38.9.12] the map view is the approved 「NEXUS」 holographic HUD (mock-v3 kb_v3b): own full page, ≡ → 列表
    if (mapView) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            com.openminis.app.plus.ChatKnowledge.load(ctx)
            com.openminis.app.plus.ChatKnowledge.run(ctx)
        }
        NexusKnowledgePage(kEntries, onBack = onBack, onList = { mapView = false })
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMinesColors provides (if (mapView) galaxyM else pageM)) {
    PlusPage("知识库", onBack, subtitle = if (mapView) "聊过的事自动整理到这里 · 点主题展开，点要点看详情" else "你的资料和聊过的事，AI 回答前会先查这里",
        actions = { GlassTextButton(if (mapView) "列表" else "脑图") { mapView = !mapView } },
        backdrop = if (mapView) ({ GalaxyBackdrop(Modifier.fillMaxSize()) }) else null) {
        if (mapView) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.openminis.app.plus.ChatKnowledge.load(ctx)
                com.openminis.app.plus.ChatKnowledge.run(ctx)
            }
            OrganizeBar()
            // [Mines-38.8 v5] the map fills the deep-space page under the header (no card); stars/nebulae run edge to edge
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
            val mapH = (screenH - 200).coerceIn(420, 900)
            KnowledgeMindMap(kEntries, height = androidx.compose.ui.unit.Dp(mapH.toFloat()), onOpenChat = { r -> PlusNav.go(r) })
            return@PlusPage
        }
        ChatDigestSection()
        PlusSection(if (bases.isEmpty()) "我的资料" else "我的资料 · ${bases.size}") {
            bases.forEachIndexed { i, kb ->
                Row(
                    Modifier.fillMaxWidth().clickable { openId = kb.id }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(kb.name, style = MaterialTheme.typography.titleMedium)
                        Text("${kb.docs.size} 篇", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i < bases.lastIndex) PlusDivider()
            }
            if (bases.isNotEmpty()) PlusDivider()
            ActionRow(Icons.Outlined.Add, "导入资料（PDF、TXT、Markdown、网页）") { if (bases.isEmpty()) showNew = true else openId = bases.first().id }
            PlusDivider()
            ActionRow(Icons.Outlined.FileOpen, "新建一个资料库") { showNew = true }
        }
        PlusSection("对话", footer = "单个对话可在右上角 ⋯ 里开关") {
            SwitchRow("新对话默认使用知识库", null, defaultOn) { on -> defaultOn = on; KnowledgeBase.setDefaultOn(ctx, on) }
        }
        if (bases.any { it.docs.isNotEmpty() }) SearchTry(null)
    }
    }
}

@Composable
private fun KnowledgeDetail(kb: KnowledgeBase.Kb, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showUrl by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(kb.name) }

    fun runImport(label: String, block: suspend () -> KnowledgeBase.Doc) = scope.launch {
        busy = label; msg = ""
        msg = runCatching { block() }.fold(
            { d -> "已导入「${d.title}」：${d.chunks} 段，${d.chars} 字" },
            { e -> "导入失败：${e.message}" },
        )
        busy = null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            val results = ArrayList<String>()
            for ((n, u) in uris.withIndex()) {
                busy = "正在导入 ${n + 1}/${uris.size}…"
                results += runCatching { KnowledgeBase.importUri(ctx, kb.id, u) }.fold({ "✓ ${it.title}（${it.chunks} 段）" }, { "✗ ${it.message}" })
            }
            msg = results.joinToString("\n"); busy = null
        }
    }

    if (showUrl) AlertDialog(
        onDismissRequest = { showUrl = false },
        title = { Text("导入网页") },
        text = { OutlinedTextField(url, { url = it.trim() }, label = { Text("网址 https://…") }, singleLine = true) },
        confirmButton = {
            Button(enabled = url.startsWith("http"), onClick = {
                val u = url; showUrl = false; url = ""
                runImport("正在读取网页…") { KnowledgeBase.importUrl(ctx, kb.id, u) }
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = { showUrl = false }) { Text("取消") } },
    )
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("删除「${kb.name}」？") },
        text = { Text("里面的 ${kb.docs.size} 篇文档会一起删除。") },
        confirmButton = { TextButton(onClick = { showDelete = false; KnowledgeBase.delete(ctx, kb.id); onBack() }) { Text("删除", color = Color(0xFFFF3B30)) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
    )

    PlusPage(kb.name, onBack) {
        PlusSection("导入", footer = "PDF 第一次导入时会在沙箱里装一次 pdftotext（几 MB）。扫描版 PDF（图片）读不出文字。") {
            ActionRow(Icons.Outlined.FileOpen, "导入文件（PDF / TXT / Markdown）") {
                if (busy == null) pick.launch(arrayOf("application/pdf", "text/*", "application/octet-stream"))
            }
            PlusDivider()
            ActionRow(Icons.Outlined.Link, "导入网页") { if (busy == null) showUrl = true }
        }
        if (busy != null || msg.isNotBlank()) Text(busy ?: msg, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        PlusSection(if (kb.docs.isEmpty()) "文档" else "文档 · ${kb.docs.size}") {
            if (kb.docs.isEmpty()) Text("还没有文档", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            kb.docs.forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${d.chunks} 段 · ${d.chars} 字" + if (d.source.startsWith("http")) " · 网页" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { KnowledgeBase.removeDoc(ctx, kb.id, d.id) } } }) {
                        Text("删除", color = Color(0xFFFF3B30))
                    }
                }
                if (i < kb.docs.lastIndex) PlusDivider()
            }
        }
        if (kb.docs.isNotEmpty()) SearchTry(kb.id)
        PlusSection("设置") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rename, { rename = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = rename.isNotBlank() && rename != kb.name, onClick = { KnowledgeBase.rename(ctx, kb.id, rename) }) { Text("改名") }
                    TextButton(onClick = { showDelete = true }) { Text("删除知识库", color = Color(0xFFFF3B30)) }
                }
            }
        }
    }
}

/** Try a search, same engine the AI uses. */
@Composable
private fun SearchTry(kbId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<KnowledgeBase.Hit>?>(null) }
    PlusSection("试搜一下", footer = "和 AI 用的是同一个检索。") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = q.isNotBlank(), onClick = {
                scope.launch { hits = withContext(Dispatchers.Default) { KnowledgeBase.search(ctx, q, kbId?.let { listOf(it) }, 5) } }
            }, modifier = Modifier.fillMaxWidth()) { Text("搜索") }
            hits?.let { list ->
                if (list.isEmpty()) Text("没有匹配的段落", style = MaterialTheme.typography.bodyMedium)
                list.forEach { h ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${h.doc?.title ?: "?"} · 第 ${h.chunk.i + 1} 段", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(h.chunk.text.take(240), style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


/** [Mines-kb-33] 对话整理: every chat turned into knowledge automatically. */
@Composable
private fun ChatDigestSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by com.openminis.app.plus.ChatKnowledge.entries.collectAsState()
    val busy by com.openminis.app.plus.ChatKnowledge.busy.collectAsState()
    var on by remember { mutableStateOf(com.openminis.app.plus.ChatKnowledge.enabled(ctx)) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.openminis.app.plus.ChatKnowledge.load(ctx)
        com.openminis.app.plus.ChatKnowledge.run(ctx)
    }
    PlusSection(if (entries.isEmpty()) "对话整理" else "对话整理 · ${entries.size}") {
        SwitchRow("自动整理每个对话", if (busy) "正在整理…" else "聊完会自动总结成要点、结论、待办和链接", on) { v ->
            on = v; com.openminis.app.plus.ChatKnowledge.setEnabled(ctx, v)
            if (v) scope.launch { com.openminis.app.plus.ChatKnowledge.run(ctx) }
        }
        PlusDivider()
        PlusRow(Icons.Outlined.Search, if (busy) "正在整理…" else "立即整理", com.openminis.app.plus.ChatKnowledge.note.collectAsState().value.ifBlank { "不用等，马上把新聊的整理进来" },
            onClick = { if (!busy) scope.launch { com.openminis.app.plus.ChatKnowledge.runNow(ctx) } }, chevron = false)
        if (entries.isNotEmpty()) {
            PlusDivider()
            OutlinedTextField(q, { q = it }, placeholder = { Text("搜聊过的事") }, singleLine = true,
                leadingIcon = { com.openminis.app.ui.brand.MinesIcon(Icons.Outlined.Search, null) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            PlusDivider()
            PlusRow(Icons.Outlined.Search, "还没有整理好的对话", "聊完离开对话就会整理，也可以点上面「立即整理」", chevron = false)
        }
        val shown = com.openminis.app.plus.ChatKnowledge.search(q).take(40)
        shown.forEach { e ->
            PlusDivider()
            PlusRow(Icons.Outlined.Link, e.title, (if (e.topic.isNotBlank()) e.topic + " · " else "") + e.summary.take(80),
                onClick = { open = if (open == e.sessionId) null else e.sessionId }, chevron = false)
            if (open == e.sessionId) Column(Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.summary, fontSize = MT.Sub, lineHeight = MT.SubLH, color = mines().ink2)
                e.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                e.decisions.forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium) }
                e.todos.forEach { Text("☐ $it", style = MaterialTheme.typography.bodyMedium) }
                Text(e.from, fontSize = MT.Sub, color = mines().primaryText)
                GlassTextButton("回到对话") { PlusNav.go(e.route) }
                e.links.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (e.files.isNotEmpty()) Text("文件：" + e.files.joinToString("、"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/** [Mines-38.7] one line: how much is organised + 「立即整理」 (works with or without a model key). */
@Composable
private fun OrganizeBar() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = mines()
    val entries by com.openminis.app.plus.ChatKnowledge.entries.collectAsState()
    val busy by com.openminis.app.plus.ChatKnowledge.busy.collectAsState()
    val note by com.openminis.app.plus.ChatKnowledge.note.collectAsState()
    // [Mines-38.9.4] user 「知识库也要加雾」: frosted pill behind the line; 「立即整理」 ends at the same right edge as 「列表」
    Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH, vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
        .background(Color(0xFF16121A).copy(alpha = 0.55f)).border(1.dp, Color(0x29FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(when {
            busy -> "正在整理…"
            note.isNotBlank() -> note + " · 共 ${entries.size} 段"
            entries.isEmpty() -> "聊完会自动整理到这里"
            else -> "已整理 ${entries.size} 段对话，聊完自动更新"
        }, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        GlassTextButton(if (busy) "整理中…" else "立即整理") {
            if (!busy) scope.launch { com.openminis.app.plus.ChatKnowledge.runNow(ctx) }
        }
    }
}

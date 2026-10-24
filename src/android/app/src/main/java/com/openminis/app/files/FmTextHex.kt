package com.openminis.app.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.plus.GlassPill
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MinesDialog
import com.openminis.app.ui.plus.glassFieldColors
import com.openminis.app.ui.plus.mines
import com.openminis.app.ui.plus.minesReadingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** [Mines-38.9.11 d] Text editor (read-only when [FmPage.Text.readOnlyText] is set, e.g. a decoded AXML). */
@Composable
internal fun FmTextScreen(page: FmPage.Text, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    val f = page.file
    val readOnly = page.readOnlyText != null
    var text by remember(f) { mutableStateOf<String?>(page.readOnlyText) }
    var loadError by remember(f) { mutableStateOf<String?>(null) }
    var dirty by remember(f) { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var askExit by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf(true) }
    val lang = remember(f) { SyntaxCore.langOf(page.title ?: f.name) }

    LaunchedEffect(f) {
        if (text != null) return@LaunchedEffect
        if (SyntaxCore.tooBigToEdit(f.length())) { loadError = "文件超过 8 MB，用十六进制查看更合适"; return@LaunchedEffect }
        val r = withContext(Dispatchers.IO) { runCatching { f.readText() } }
        r.onSuccess { text = it }.onFailure { loadError = "读不出来：" + (it.message ?: "") }
    }

    fun save() {
        val t = text ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { f.writeText(t) } }
            r.onSuccess { dirty = false; Fm.toast(ctx, "已保存") }.onFailure { Fm.toast(ctx, "保存失败：" + (it.message ?: "")) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        FmTopBar(page.title ?: f.name, sub = (if (readOnly) "只读 · " else "") + HexCore.humanSize(f.length()),
            onBack = { if (dirty) askExit = true else onBack() }) {
            if (lang != SyntaxCore.Lang.NONE) FmIconButton(if (highlight) "palette" else "eye-off", "语法高亮", { highlight = !highlight })
            FmIconButton("languages", if (wrap) "不自动换行" else "自动换行", { wrap = !wrap })
            FmIconButton("copy", "复制全文", { text?.let { Fm.copyText(ctx, "内容", it) } })
            if (!readOnly) FmIconButton("check", "保存", { save() }, enabled = dirty)
        }
        loadError?.let { e ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e, fontSize = MT.Body, color = m.ink)
                GlassPill("返回") { onBack() }
            }
        }
        val t = text
        if (t != null) {
            if (readOnly || !highlight) {
                val style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = m.ink, fontFamily = FontFamily.Monospace)
                val base = Modifier.fillMaxSize().background(minesReadingSurface()).padding(10.dp)
                if (readOnly) {
                    Box(if (wrap) base.verticalScroll(rememberScrollState()) else base.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Text(if (highlight && lang != SyntaxCore.Lang.NONE) highlighted(t, lang, m.ink) else AnnotatedString(t), style = style, softWrap = wrap)
                    }
                } else {
                    BasicTextField(t, { text = it; dirty = true }, textStyle = style, cursorBrush = SolidColor(m.primary),
                        modifier = if (wrap) base.verticalScroll(rememberScrollState()) else base.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            } else {
                // editable + highlighted: BasicTextField with a visual transformation would reflow on every keystroke for a
                // big file, so the highlight is drawn by the same field via an AnnotatedString transformation per line
                val vScroll = rememberScrollState(); val hScroll = rememberScrollState(); val hlColors = highlightColors()
                BasicTextField(t, { text = it; dirty = true },
                    textStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = m.ink, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(m.primary),
                    visualTransformation = { src -> androidx.compose.ui.text.input.TransformedText(highlightPlain(src.text, lang, hlColors), androidx.compose.ui.text.input.OffsetMapping.Identity) },
                    modifier = Modifier.fillMaxSize().background(minesReadingSurface()).padding(10.dp)
                        .verticalScroll(vScroll).let { if (wrap) it else it.horizontalScroll(hScroll) })
            }
        }
    }

    if (askExit) MinesDialog("还没保存，要离开吗？", onDismiss = { askExit = false }, confirm = "保存并离开", onConfirm = { askExit = false; save(); onBack() },
        dismiss = "直接离开", onDismissButton = { askExit = false; onBack() }) {
        Text("「${f.name}」有改动没保存。", fontSize = MT.Sub, color = mines().ink)
    }
}

/** Token colours for the current theme (composable part of the highlighter). */
@Composable
private fun highlightColors(): Map<SyntaxCore.Token, Color> {
    val m = mines()
    return mapOf(
        SyntaxCore.Token.COMMENT to (if (m.dark) Color(0xFF8A9A7B) else Color(0xFF6B7A5A)),
        SyntaxCore.Token.STRING to (if (m.dark) Color(0xFFD9A066) else Color(0xFF9C6B2F)),
        SyntaxCore.Token.NUMBER to (if (m.dark) Color(0xFF8FB8D8) else Color(0xFF2F6B9C)),
        SyntaxCore.Token.KEYWORD to m.primaryText,
        SyntaxCore.Token.TAG to (if (m.dark) Color(0xFFB9A0E0) else Color(0xFF6B4FA0)),
        SyntaxCore.Token.ATTR to (if (m.dark) Color(0xFF8FB8D8) else Color(0xFF2F6B9C)),
    )
}

/** Line-by-line highlight for read-only views. */
@Composable
private fun highlighted(text: String, lang: SyntaxCore.Lang, ink: Color): AnnotatedString {
    val colors = highlightColors()
    val dark = mines().dark
    return remember(text, lang, dark) { highlightPlain(text, lang, colors) }
}

/** Non-composable core (also used inside the editor's VisualTransformation); capped so a very long file stays fast. */
private fun highlightPlain(text: String, lang: SyntaxCore.Lang, colors: Map<SyntaxCore.Token, Color>): AnnotatedString =
        buildAnnotatedString {
            append(text)
            if (text.length > 400_000) return@buildAnnotatedString
            var offset = 0
            var block = false
            for (line in text.lineSequence()) {
                val (spans, carry) = SyntaxCore.spans(line, lang, block)
                block = carry
                for (s in spans) colors[s.token]?.let { c ->
                    addStyle(SpanStyle(color = c, fontWeight = if (s.token == SyntaxCore.Token.KEYWORD) FontWeight.SemiBold else null),
                        offset + s.start, (offset + s.end).coerceAtMost(text.length))
                }
                offset += line.length + 1
                if (offset > text.length) break
            }
        }

/** [Mines-38.9.11 d] Hex viewer / editor: 16 bytes a row, jump to an offset, search hex or text, patch in place. */
@Composable
internal fun FmHexScreen(f: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    val pageSize = 4096
    var offset by remember(f) { mutableLongStateOf(0L) }
    var data by remember(f) { mutableStateOf(ByteArray(0)) }
    var version by remember(f) { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var cursor by remember { mutableStateOf<Long?>(null) }
    val size = remember(f, version) { f.length() }

    LaunchedEffect(f, offset, version) { data = withContext(Dispatchers.IO) { runCatching { HexCore.window(f, offset, pageSize) }.getOrDefault(ByteArray(0)) } }

    Column(Modifier.fillMaxSize()) {
        FmTopBar(f.name, sub = "十六进制 · ${HexCore.humanSize(size)} · 第 ${offset / pageSize + 1}/${(size / pageSize) + 1} 页", onBack = onBack) {
            FmIconButton("search", "查找", { dialog = "find" })
            FmIconButton("map-pin", "跳到位置", { dialog = "goto" })
            FmIconButton("pencil", "修改这里", { dialog = "edit" }, enabled = cursor != null && f.canWrite())
        }
        cursor?.let { cur ->
            FmPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                val b = data.getOrNull((cur - offset).toInt())
                FmKv("位置", "0x%08x（%d）".format(cur, cur))
                if (b != null) FmKv("这个字节", HexCore.describe(b))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            items(HexCore.rows(data, offset), key = { it.offset }) { row ->
                Row(Modifier.fillMaxWidth().clickable { cursor = row.offset }.padding(vertical = 1.dp)) {
                    Text(row.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = m.ink2)
                    Spacer(Modifier.width(8.dp))
                    Text(row.hex, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = m.ink, modifier = Modifier.weight(1f))
                    Text(row.ascii, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = m.ink2)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill("上一页", enabled = offset > 0) { offset = (offset - pageSize).coerceAtLeast(0) }
            GlassPill("下一页", enabled = offset + pageSize < size) { offset += pageSize }
            Spacer(Modifier.weight(1f))
            Text("点一行选中，再点 ✎ 修改", fontSize = 11.sp, color = m.ink2, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }

    when (dialog) {
        "goto" -> {
            var v by remember { mutableStateOf("0x") }
            MinesDialog("跳到位置", onDismiss = { dialog = null }, confirm = "跳转", onConfirm = {
                val t = v.trim().removePrefix("0x").removePrefix("0X")
                val n = t.toLongOrNull(16) ?: t.toLongOrNull()
                dialog = null
                if (n != null) { offset = (n / pageSize) * pageSize; cursor = n } else Fm.toast(ctx, "填 16 进制位置，比如 0x1f40")
            }) {
                OutlinedTextField(v, { v = it }, singleLine = true, colors = glassFieldColors(), modifier = Modifier.fillMaxWidth())
            }
        }
        "find" -> {
            var v by remember { mutableStateOf("") }
            var asHex by remember { mutableStateOf(false) }
            var hits by remember { mutableStateOf<List<Long>?>(null) }
            MinesDialog("查找", onDismiss = { dialog = null }, confirm = "查找", onConfirm = {
                val needle = if (asHex) HexCore.parseHex(v) else v.toByteArray()
                if (needle == null || needle.isEmpty()) { Fm.toast(ctx, "16 进制要成对，比如 ff 00 1a") } else {
                    scope.launch { hits = withContext(Dispatchers.IO) { HexCore.findInFile(f, needle) }
                        val first = hits?.firstOrNull()
                        if (first == null) Fm.toast(ctx, "没找到") else { offset = (first / pageSize) * pageSize; cursor = first; dialog = null
                            Fm.toast(ctx, "找到 ${hits!!.size} 处，已跳到第一处") } }
                }
            }) {
                OutlinedTextField(v, { v = it }, singleLine = true, colors = glassFieldColors(), modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (asHex) "ff 00 1a" else "要找的文字") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FmChip("文字", !asHex) { asHex = false }
                    FmChip("16 进制", asHex) { asHex = true }
                }
            }
        }
        "edit" -> {
            val cur = cursor
            if (cur == null) dialog = null else {
                val old = data.copyOfRange((cur - offset).toInt().coerceIn(0, data.size), minOf((cur - offset).toInt() + 8, data.size))
                var v by remember { mutableStateOf(Hex2.of(old.copyOfRange(0, minOf(1, old.size)), " ")) }
                MinesDialog("修改 0x%08x".format(cur), onDismiss = { dialog = null }, confirm = "写入", danger = true, onConfirm = {
                    val patch = HexCore.parseHex(v)
                    dialog = null
                    if (patch == null) Fm.toast(ctx, "16 进制要成对") else scope.launch {
                        val expect = old.copyOfRange(0, minOf(patch.size, old.size))
                        val ok = withContext(Dispatchers.IO) { runCatching { HexCore.patch(f, cur, patch, expect) } }
                        ok.onSuccess { done -> version++; Fm.toast(ctx, if (done) "已写入 ${patch.size} 个字节" else "文件变了，没有写入") }
                            .onFailure { Fm.toast(ctx, "写入失败：" + (it.message ?: "")) }
                    }
                }) {
                    Text("原来是：" + Hex2.of(old, " "), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = m.ink2)
                    OutlinedTextField(v, { v = it }, singleLine = true, colors = glassFieldColors(), modifier = Modifier.fillMaxWidth())
                    Text("长度不变，不会把文件撑长；改 APK / so 这类文件前最好先复制一份。", fontSize = 12.sp, color = m.ink2)
                }
            }
        }
    }
}

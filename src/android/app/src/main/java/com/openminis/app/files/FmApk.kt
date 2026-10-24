package com.openminis.app.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.plus.GlassPill
import com.openminis.app.ui.plus.GlassSecondaryPill
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.glassFieldColors
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** [Mines-38.9.11 d] APK info: 概览 / 权限 / 组件 / 签名 / 清单 / dex / 资源 + 安装 · 解包 · 重新签名 · 校验 · MT. */
@Composable
internal fun FmApkScreen(f: File, browser: BrowserState, onBack: () -> Unit, onOpen: (FmPage) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var info by remember(f) { mutableStateOf<ApkInfoCore.Info?>(null) }
    var error by remember(f) { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("概览") }
    var sign by remember { mutableStateOf(false) }
    var verifyLines by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(f) {
        withContext(Dispatchers.IO) { runCatching { ApkInfoCore.read(f) } }.onSuccess { info = it }.onFailure { error = "读不了这个 APK：" + (it.message ?: "") }
    }

    Column(Modifier.fillMaxSize()) {
        FmTopBar(info?.packageName ?: f.name, sub = f.name + " · " + HexCore.humanSize(f.length()), onBack = onBack) {
            FmIconButton("external-link", "用 MT 管理器打开", { Fm.openInMt(ctx, f) })
            FmIconButton("download", "安装", { Fm.install(ctx, f) })
        }
        error?.let { Text(it, fontSize = MT.Body, color = m.ink, modifier = Modifier.padding(16.dp)) }
        val i = info
        if (i == null && error == null) Text("正在读取…", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(16.dp))
        if (i != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("概览", "权限", "组件", "签名", "清单", "dex", "资源").forEach { t -> FmChip(t, tab == t) { tab = t } }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                when (tab) {
                    "概览" -> {
                        item { FmPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { i.summaryRows().forEach { (k, v) -> FmKv(k, v, mono = k.contains("SHA")) { Fm.copyText(ctx, k, v) } } } }
                        if (i.warnings.isNotEmpty()) item {
                            FmPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { i.warnings.forEach { w -> Text("· $w", fontSize = MT.Sub, color = Color(0xFFD9534F), modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) } }
                        }
                        item { Column {
                            FmSection("操作")
                            FmPanel(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp)) {
                                    FmAction("download", "安装") { Fm.install(ctx, f) }
                                    FmAction("folder-open", "解包到文件夹") {
                                        busy = "正在解包…"
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val d = FileOpsCore.uniqueTarget(f.parentFile!!, f.nameWithoutExtension)
                                                    val res = ZipCore.unpack(f, d)
                                                    File(d, ApkResignCore.STORED_LIST).writeText(ApkResignCore.storedNames(f).joinToString("\n"))
                                                    res to d
                                                }
                                            }
                                            busy = null
                                            r.onSuccess { (res, d) -> browser.left.refresh(); browser.right.refresh(); Fm.toast(ctx, "已解包 ${res.files} 个文件到「${d.name}」，改完可在文件夹上选「回编译成 APK」") }
                                                .onFailure { Fm.toast(ctx, "解包失败：" + (it.message ?: "")) }
                                        }
                                    }
                                    FmAction("key-round", "重新签名") { sign = true }
                                    FmAction("shield-check", "校验签名") {
                                        busy = "正在校验…"
                                        scope.launch {
                                            val minSdk = i.minSdk?.toIntOrNull() ?: 21
                                            val (ok, lines) = withContext(Dispatchers.IO) { ApkResignCore.verify(f, minSdk) }
                                            val al = withContext(Dispatchers.IO) { runCatching { ApkAlignCore.check(f) }.getOrNull() }
                                            busy = null
                                            verifyLines = listOf(if (ok) "✓ 系统会认这个签名" else "✗ 系统不会认这个签名") + lines +
                                                (al?.let { listOf(if (it.aligned) "✓ 已对齐（zipalign）" else "✗ 有 ${it.misaligned.size} 个未对齐的条目") } ?: emptyList())
                                        }
                                    }
                                    FmAction("external-link", "MT 管理器") { Fm.openInMt(ctx, f) }
                                    FmAction("share", "分享") { Fm.share(ctx, f) }
                                }
                                busy?.let { Text(it, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)) }
                            }
                        } }
                        verifyLines?.let { lines -> item { FmPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            lines.forEach { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = m.ink, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) } } } }
                    }
                    "权限" -> {
                        item { FmSection("${i.permissions.size} 项权限（敏感的排在前面）") }
                        items(i.permissions) { p ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(ApkInfoCore.permissionZh(p), fontSize = MT.Sub, fontWeight = if (p in ApkInfoCore.NOTABLE) FontWeight.SemiBold else null,
                                    color = if (p in ApkInfoCore.NOTABLE) m.primaryText else m.ink, modifier = Modifier.weight(0.4f))
                                Text(p, fontSize = 11.sp, color = m.ink2, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f))
                            }
                        }
                    }
                    "组件" -> {
                        val groups = i.components.groupBy { it.kind }
                        groups.forEach { (kind, list) ->
                            item { FmSection(when (kind) { "activity" -> "界面（activity）"; "activity-alias" -> "界面别名"; "service" -> "服务"; "receiver" -> "广播接收器"; else -> "内容提供器" } + " · ${list.size}") }
                            items(list) { c ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text(c.name, fontSize = 13.sp, color = m.ink, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                                    val meta = listOfNotNull(c.exported?.let { if (it) "对外开放" else "仅内部" }, c.permission?.let { "需要 $it" },
                                        if (c.intents.isNotEmpty()) "${c.intents.size} 条 intent" else null).joinToString(" · ")
                                    if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = if (c.exported == true) m.primaryText else m.ink2)
                                }
                            }
                        }
                    }
                    "签名" -> {
                        val s = i.signatures
                        if (s == null) item { Text("没读到签名", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(14.dp)) }
                        else {
                            item { FmPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                FmKv("方案", s.schemes.joinToString(" + ").ifBlank { "未签名" })
                                if (s.hasSourceStamp) FmKv("来源戳", "有（应用商店写入）")
                                FmKv("签名块", s.blockIds.joinToString(" ") { "0x%08x".format(it) }, mono = true)
                                s.error?.let { FmKv("错误", it) }
                            } }
                            (s.v31 + s.v3 + s.v2 + s.v1).forEach { signer ->
                                item { Column {
                                    FmSection(signer.scheme + " 签名者" + (signer.minSdk?.let { " · API $it–${signer.maxSdk}" } ?: ""))
                                    FmPanel(Modifier.fillMaxWidth()) {
                                        if (signer.algorithms.isNotEmpty()) FmKv("算法", signer.algorithms.joinToString("、") { ApkSigCore.algorithmName(it) })
                                        signer.certs.forEach { c ->
                                            FmKv("证书", c.subject)
                                            FmKv("SHA-256", c.sha256, mono = true) { Fm.copyText(ctx, "SHA-256", c.sha256) }
                                            FmKv("SHA-1", c.sha1, mono = true) { Fm.copyText(ctx, "SHA-1", c.sha1) }
                                            FmKv("MD5", c.md5, mono = true) { Fm.copyText(ctx, "MD5", c.md5) }
                                            FmKv("有效期", (c.notBefore?.let { Fm.time(it.time) } ?: "?") + " 至 " + (c.notAfter?.let { Fm.time(it.time) } ?: "?"))
                                            FmKv("密钥", c.keyAlgorithm + " · " + c.sigAlgorithm)
                                        }
                                    }
                                } }
                            }
                        }
                    }
                    "清单" -> item { Column {
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassSecondaryPill("全屏查看") { onOpen(FmPage.Text(f, i.manifestXml, "AndroidManifest.xml")) }
                            GlassSecondaryPill("复制") { Fm.copyText(ctx, "清单", i.manifestXml) }
                        }
                        Text(i.manifestXml.take(60_000), fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, color = m.ink, modifier = Modifier.padding(8.dp))
                    } }
                    "dex" -> {
                        item { FmSection("${i.dexFiles.size} 个 dex（只读查看）") }
                        items(i.dexFiles) { d ->
                            FmPanel(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { onOpen(FmPage.Dex(f, d)) }.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(d, fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
                                    Text("看类列表 ›", fontSize = MT.Sub, color = m.primaryText)
                                }
                            }
                        }
                    }
                    "资源" -> item {
                        FmPanel(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { onOpen(FmPage.Arsc(f, "resources.arsc")) }.padding(14.dp)) {
                                Text("resources.arsc（字符串、颜色、尺寸…）", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
                                Text("打开 ›", fontSize = MT.Sub, color = m.primaryText)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
            }
        }
    }
    if (sign) FmSignSheet(source = f, isFolder = false, minSdk = info?.minSdk?.toIntOrNull() ?: 21, packageName = info?.packageName,
        onDismiss = { sign = false; browser.left.refresh(); browser.right.refresh() })
}

/** [Mines-38.9.11 d] dex → class list (search) → smali-like listing. Read-only. */
@Composable
internal fun FmDexScreen(source: File, entry: String?, onBack: () -> Unit, onOpen: (FmPage) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var dex by remember(source, entry) { mutableStateOf<DexCore?>(null) }
    var classes by remember(source, entry) { mutableStateOf<List<DexCore.ClassInfo>>(emptyList()) }
    var error by remember(source, entry) { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var showHeader by remember { mutableStateOf(false) }

    LaunchedEffect(source, entry) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (entry != null) ZipCore.readEntry(source, entry, 256 shl 20) ?: throw FormatException("包里没有 $entry") else source.readBytes()
                val d = DexCore(bytes); d to d.classes().sortedBy { it.javaName }
            }
        }.onSuccess { (d, c) -> dex = d; classes = c }.onFailure { error = "读不了 dex：" + (it.message ?: "") }
    }

    Column(Modifier.fillMaxSize()) {
        FmTopBar(entry ?: source.name, sub = dex?.let { "dex ${it.version} · ${it.classDefsSize} 个类 · 只读" } ?: "读取中…", onBack = onBack) {
            FmIconButton("info", "文件头", { showHeader = !showHeader })
        }
        error?.let { Text(it, fontSize = MT.Body, color = m.ink, modifier = Modifier.padding(16.dp)) }
        if (showHeader) dex?.let { d -> FmPanel(Modifier.fillMaxWidth().padding(8.dp)) { d.headerLines().forEach { (k, v) -> FmKv(k, v, mono = k.contains("SHA") || k == "校验和") } } }
        OutlinedTextField(q, { q = it }, singleLine = true, colors = glassFieldColors(), placeholder = { Text("搜类名，比如 MainActivity") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
        val shown = remember(classes, q) { if (q.isBlank()) classes else classes.filter { it.javaName.contains(q.trim(), ignoreCase = true) } }
        Text("显示 ${minOf(shown.size, 3000)} / ${shown.size}", fontSize = 11.sp, color = m.ink2, modifier = Modifier.padding(start = 14.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
            items(shown.take(3000), key = { it.index }) { c ->
                Column(Modifier.fillMaxWidth().clickable {
                    val d = dex ?: return@clickable
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { runCatching { d.disassemble(c.index) }.getOrElse { "# 反汇编失败：" + it.message } }
                        onOpen(FmPage.Text(source, text, c.simpleName + ".smali"))
                    }
                }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(c.simpleName, fontSize = 13.sp, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.packageName.ifBlank { "（默认包）" } + (c.superclass?.let { " · 继承 " + DexCore.javaName(it).substringAfterLast('.') } ?: ""),
                        fontSize = 11.sp, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = ctx
}

/** [Mines-38.9.11 d] resources.arsc: package → type → config → entries. Read-only. */
@Composable
internal fun FmArscScreen(source: File, entry: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    var arsc by remember(source, entry) { mutableStateOf<ArscCore?>(null) }
    var error by remember(source, entry) { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf("string") }
    var config by remember { mutableStateOf(0) }
    var q by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ArscCore.Entry>>(emptyList()) }

    LaunchedEffect(source, entry) {
        withContext(Dispatchers.IO) {
            runCatching { ArscCore(if (entry != null) ZipCore.readEntry(source, entry, 128 shl 20) ?: throw FormatException("包里没有 $entry") else source.readBytes()) }
        }.onSuccess { arsc = it }.onFailure { error = "读不了资源表：" + (it.message ?: "") }
    }
    val pkg = arsc?.packages?.firstOrNull()
    val spec = pkg?.let { p -> arsc?.typeOf(p, type) ?: p.types.firstOrNull() }
    LaunchedEffect(arsc, type, config) {
        val a = arsc ?: return@LaunchedEffect; val p = pkg ?: return@LaunchedEffect; val s = spec ?: return@LaunchedEffect
        val chunk = s.configs.getOrNull(config) ?: s.configs.firstOrNull() ?: return@LaunchedEffect
        entries = withContext(Dispatchers.IO) { runCatching { a.entries(p, chunk, 5000) }.getOrDefault(emptyList()) }
    }

    Column(Modifier.fillMaxSize()) {
        FmTopBar("资源表", sub = (pkg?.name ?: source.name) + " · 只读", onBack = onBack)
        error?.let { Text(it, fontSize = MT.Body, color = m.ink, modifier = Modifier.padding(16.dp)) }
        if (pkg != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pkg.types.forEach { t -> FmChip(t.name, spec?.name == t.name) { type = t.name; config = 0 } }
            }
            spec?.let { s ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.configs.forEachIndexed { idx, c -> FmChip(c.config, config == idx) { config = idx } }
                }
            }
            OutlinedTextField(q, { q = it }, singleLine = true, colors = glassFieldColors(), placeholder = { Text("搜名字或内容") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
            val shown = remember(entries, q) { if (q.isBlank()) entries else entries.filter { it.key.contains(q, true) || it.value.contains(q, true) } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp)) {
                items(shown) { e ->
                    Column(Modifier.fillMaxWidth().clickable { Fm.copyText(ctx, "值", e.value) }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(e.key, fontSize = 13.sp, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ArscCore.resIdString(e.resId) + "  " + e.value, fontSize = 11.sp, color = m.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
            }
        }
    }
}

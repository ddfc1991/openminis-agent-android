package com.openminis.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.plus.GlassToast
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MinesDialog
import com.openminis.app.ui.plus.MinesSheet
import com.openminis.app.ui.plus.WarmMenu
import com.openminis.app.ui.plus.WarmMenuDivider
import com.openminis.app.ui.plus.WarmMenuItem
import com.openminis.app.ui.plus.glassFieldColors
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One pane: its folder, listing, selection. */
internal class PaneState(start: File) {
    var dir by mutableStateOf(start)
    var items by mutableStateOf<List<File>>(emptyList())
    val selection = mutableStateListOf<File>()
    var version by mutableIntStateOf(0)
    var sort by mutableStateOf(FileOpsCore.Sort.NAME)
    var showHidden by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun go(d: File) { if (d != dir) { selection.clear(); dir = d } else refresh() }
    fun refresh() { version++ }
    val selecting: Boolean get() = selection.isNotEmpty()
}

internal class BrowserState(ctx: Context) {
    val left = PaneState(FileRoots.shared(ctx))
    val right = PaneState(if (FileRoots.hasAllFiles()) FileRoots.phone() else FileRoots.work(ctx))
    var activeIdx by mutableIntStateOf(0)
    val active: PaneState get() = if (activeIdx == 0) left else right
    val other: PaneState get() = if (activeIdx == 0) right else left
    private val rootDirs = FileRoots.roots(ctx).map { it.dir.absoluteFile }

    /** Back: clear the selection, then go up until a root; false = leave the manager. */
    fun handleBack(): Boolean {
        val p = active
        if (p.selecting) { p.selection.clear(); return true }
        val parent = p.dir.parentFile
        if (p.dir.absoluteFile !in rootDirs && parent != null && parent.canRead()) { p.go(parent); return true }
        return false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FmBrowser(state: BrowserState, resumeTick: Int, onOpen: (FmPage) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }      // rename | newdir | newfile | delete | zip | props | search | open
    var menu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var signDir by remember { mutableStateOf<File?>(null) }

    fun run(label: String, block: suspend () -> String) {
        busy = label
        scope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { block() } }.getOrElse { "失败：" + (it.message ?: it.javaClass.simpleName) }
            busy = null
            Fm.toast(ctx, msg)
            state.left.refresh(); state.right.refresh()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val src = state.active.selection.firstOrNull() ?: return@rememberLauncherForActivityResult
        if (uri != null) run("正在导出…") {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: throw FormatException("写不进去")
            "已导出「${src.name}」"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) run("正在导入…") {
            val dest = state.active.dir.takeIf { it.canWrite() } ?: FileRoots.work(ctx)
            uris.forEach { importInto(ctx, it, dest) }
            "已导入 ${uris.size} 个文件到「${dest.name}」"
        }
    }

    Column(Modifier.fillMaxSize()) {
        val act = state.active
        FmTopBar("文件管理", sub = FileRoots.linuxPath(ctx, act.dir) ?: act.dir.absolutePath, onBack = onClose) {
            FmIconButton("search", "搜索", { dialog = "search" })
            Box {
                FmIconButton("ellipsis", "更多", { menu = true })
                WarmMenu(menu, { menu = false }) {
                    WarmMenuItem("新建文件夹", Fm.ic("folder")) { menu = false; dialog = "newdir" }
                    WarmMenuItem("新建文件", Fm.ic("file-text")) { menu = false; dialog = "newfile" }
                    WarmMenuDivider()
                    WarmMenuItem(if (act.showHidden) "不显示隐藏文件" else "显示隐藏文件", Fm.ic("eye")) { menu = false; act.showHidden = !act.showHidden }
                    FileOpsCore.Sort.values().forEach { s ->
                        WarmMenuItem("按" + when (s) { FileOpsCore.Sort.NAME -> "名称"; FileOpsCore.Sort.SIZE -> "大小"; FileOpsCore.Sort.TIME -> "时间"; FileOpsCore.Sort.TYPE -> "类型" } + "排序",
                            null, selected = act.sort == s) { menu = false; act.sort = s }
                    }
                    WarmMenuDivider()
                    WarmMenuItem("从系统选择器导入", Fm.ic("download")) { menu = false; importLauncher.launch(arrayOf("*/*")) }
                    WarmMenuItem("复制当前路径", Fm.ic("copy")) { menu = false; Fm.copyText(ctx, "路径", FileRoots.linuxPath(ctx, act.dir) ?: act.dir.absolutePath) }
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 640.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaneView(state, 0, resumeTick, Modifier.weight(1f), onOpen)
                    PaneView(state, 1, resumeTick, Modifier.weight(1f), onOpen)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(state.left, state.right).forEachIndexed { i, p ->
                            FmChip((if (i == 0) "左 · " else "右 · ") + p.dir.name.ifBlank { "/" } + if (p.selecting) "（${p.selection.size}）" else "",
                                state.activeIdx == i) { state.activeIdx = i }
                        }
                    }
                    PaneView(state, state.activeIdx, resumeTick, Modifier.weight(1f).padding(horizontal = 8.dp), onOpen)
                }
            }
        }

        // bottom: selection actions
        val sel = act.selection.toList()
        if (sel.isNotEmpty()) {
            FmPanel(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding()) {
                Text("已选 ${sel.size} 项 · 另一侧：${state.other.dir.name.ifBlank { "/" }}", fontSize = 12.sp, color = m.ink2,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    FmAction("copy", "复制到另一侧") { val dest = state.other.dir; run("正在复制…") { FileOpsCore.copy(sel, dest).message.also { act.selection.clear() } } }
                    FmAction("folder-input", "移动到另一侧") { val dest = state.other.dir; run("正在移动…") { FileOpsCore.move(sel, dest).message.also { act.selection.clear() } } }
                    FmAction("pencil", "重命名", enabled = sel.size == 1) { dialog = "rename" }
                    FmAction("archive", "压缩") { dialog = "zip" }
                    FmAction("trash", "删除", danger = true) { dialog = "delete" }
                    Box {
                        FmAction("ellipsis", "更多") { moreMenu = true }
                        WarmMenu(moreMenu, { moreMenu = false }) {
                            val one = sel.singleOrNull()
                            if (one != null && one.isFile) {
                                WarmMenuItem("用文本编辑", Fm.ic("pencil")) { moreMenu = false; act.selection.clear(); onOpen(FmPage.Text(one)) }
                                WarmMenuItem("用十六进制查看", Fm.ic("code")) { moreMenu = false; act.selection.clear(); onOpen(FmPage.Hex(one)) }
                                if (ZipCore.isZip(runCatching { HexCore.window(one, 0, 8) }.getOrDefault(ByteArray(0))))
                                    WarmMenuItem("解压到这里", Fm.ic("folder-open")) {
                                        moreMenu = false
                                        run("正在解压…") {
                                            val d = FileOpsCore.uniqueTarget(one.parentFile!!, one.nameWithoutExtension)
                                            val r = ZipCore.unpack(one, d)
                                            if (one.extension.equals("apk", true)) File(d, ApkResignCore.STORED_LIST).writeText(ApkResignCore.storedNames(one).joinToString("\n"))
                                            act.selection.clear()
                                            "已解压 ${r.files} 个文件到「${d.name}」" + if (r.skipped.isNotEmpty()) "，跳过 ${r.skipped.size} 个不安全的路径" else ""
                                        }
                                    }
                                WarmMenuItem("其他应用打开", Fm.ic("external-link")) { moreMenu = false; Fm.openExternal(ctx, one) }
                                WarmMenuItem("用 MT 管理器打开", Fm.ic("external-link")) { moreMenu = false; Fm.openInMt(ctx, one) }
                                WarmMenuItem("分享", Fm.ic("share")) { moreMenu = false; Fm.share(ctx, one) }
                                WarmMenuItem("导出到手机（系统选择器）", Fm.ic("download")) { moreMenu = false; exportLauncher.launch(one.name) }
                            }
                            if (one != null && one.isDirectory && File(one, "AndroidManifest.xml").isFile)
                                WarmMenuItem("回编译成 APK 并签名", Fm.ic("key-round")) { moreMenu = false; signDir = one }
                            if (one != null) WarmMenuItem("复制路径", Fm.ic("copy")) { moreMenu = false; Fm.copyText(ctx, "路径", FileRoots.linuxPath(ctx, one) ?: one.absolutePath) }
                            WarmMenuItem("属性", Fm.ic("info")) { moreMenu = false; dialog = "props" }
                            WarmMenuItem(if (sel.size == act.items.size) "取消全选" else "全选", Fm.ic("list-checks")) {
                                moreMenu = false
                                if (sel.size == act.items.size) act.selection.clear() else { act.selection.clear(); act.selection.addAll(act.items) }
                            }
                        }
                    }
                }
            }
        }
    }

    busy?.let { label ->
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
            GlassToast(Modifier.padding(horizontal = 24.dp)) { Text(label, fontSize = MT.Sub, color = m.ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        }
    }

    val act = state.active
    when (dialog) {
        "rename" -> {
            val f = act.selection.singleOrNull()
            if (f != null) NameDialog("重命名", f.name, onDismiss = { dialog = null }) { n -> dialog = null; run("正在重命名…") { FileOpsCore.rename(f, n).message.also { act.selection.clear() } } }
        }
        "newdir" -> NameDialog("新建文件夹", "新建文件夹", onDismiss = { dialog = null }) { n -> dialog = null; val d = act.dir; run("正在新建…") { FileOpsCore.newFolder(d, n).message } }
        "newfile" -> NameDialog("新建文件", "新文件.txt", onDismiss = { dialog = null }) { n -> dialog = null; val d = act.dir; run("正在新建…") { FileOpsCore.newFile(d, n).message } }
        "zip" -> NameDialog("压缩为 ZIP", (act.selection.singleOrNull()?.nameWithoutExtension ?: act.dir.name.ifBlank { "压缩包" }) + ".zip",
            onDismiss = { dialog = null }) { n ->
            dialog = null
            val sel = act.selection.toList(); val d = act.dir
            run("正在压缩…") {
                val out = FileOpsCore.uniqueTarget(d, if (n.endsWith(".zip", true)) n else "$n.zip")
                val c = ZipCore.pack(sel, out); act.selection.clear(); "已压缩 $c 个文件 →「${out.name}」"
            }
        }
        "delete" -> {
            val sel = act.selection.toList()
            val inRootfs = sel.any { it.absolutePath.startsWith(FileRoots.rootfs(ctx).absolutePath) }
            MinesDialog("删除 ${sel.size} 项？", onDismiss = { dialog = null }, confirm = "删除", danger = true, onConfirm = {
                dialog = null; run("正在删除…") { FileOpsCore.delete(sel).message.also { act.selection.clear() } }
            }) {
                Text(sel.take(5).joinToString("\n") { "· " + it.name } + if (sel.size > 5) "\n…" else "", fontSize = MT.Sub, color = m.ink)
                Text("删除后不能恢复。" + if (inRootfs) "这些在沙箱系统里，删错可能让沙箱打不开。" else "", fontSize = MT.Sub, color = m.ink2)
            }
        }
        "props" -> {
            val sel = act.selection.toList()
            var measured by remember { mutableStateOf<Pair<Long, Int>?>(null) }
            LaunchedEffect(sel) { measured = withContext(Dispatchers.IO) { FileOpsCore.measure(sel) } }
            MinesDialog("属性", onDismiss = { dialog = null }, dismiss = "关闭") {
                val one = sel.singleOrNull()
                if (one != null) {
                    FmKv("名称", one.name)
                    FmKv("位置", FileRoots.linuxPath(ctx, one) ?: one.absolutePath, mono = true) { Fm.copyText(ctx, "路径", FileRoots.linuxPath(ctx, one) ?: one.absolutePath) }
                    FmKv("修改时间", Fm.time(one.lastModified()))
                    FmKv("权限", (if (one.canRead()) "可读" else "不可读") + " · " + (if (one.canWrite()) "可写" else "只读"))
                    if (one.isFile) FmKv("类型", HexCore.sniff(runCatching { HexCore.window(one, 0, 64) }.getOrDefault(ByteArray(0))))
                }
                FmKv("大小", measured?.let { HexCore.humanSize(it.first) + " · ${it.second} 个文件" } ?: "计算中…")
            }
        }
        "search" -> SearchSheet(act.dir, onDismiss = { dialog = null }) { hit ->
            dialog = null
            if (hit.isDirectory) act.go(hit) else { hit.parentFile?.let { act.go(it) }; FmOpen.pageFor(hit)?.let(onOpen) }
        }
    }

    signDir?.let { dir -> FmSignSheet(source = dir, isFolder = true, onDismiss = { signDir = null; state.left.refresh(); state.right.refresh() }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneView(state: BrowserState, idx: Int, resumeTick: Int, modifier: Modifier, onOpen: (FmPage) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val pane = if (idx == 0) state.left else state.right
    val isActive = state.activeIdx == idx
    var rootMenu by remember { mutableStateOf(false) }
    val roots = remember(resumeTick) { FileRoots.roots(ctx) }
    val root = remember(pane.dir, resumeTick) { FileRoots.rootOf(ctx, pane.dir) }

    LaunchedEffect(pane.dir, pane.version, pane.sort, pane.showHidden, resumeTick) {
        val d = pane.dir
        pane.items = withContext(Dispatchers.IO) { runCatching { FileOpsCore.list(d, pane.sort, pane.showHidden) }.getOrDefault(emptyList()) }
        pane.error = if (!d.canRead()) "没有权限读取这个文件夹" else null
        pane.selection.retainAll(pane.items.toSet())
    }

    FmPanel(modifier.fillMaxSize().then(if (isActive) Modifier.border(1.5.dp, m.primary.copy(alpha = 0.55f), RoundedCornerShape(16.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FmIconButton("chevron-up", "上一级", {
                state.activeIdx = idx
                pane.dir.parentFile?.takeIf { root == null || pane.dir.absoluteFile != root.dir.absoluteFile }?.let { pane.go(it) }
            }, enabled = root == null || pane.dir.absoluteFile != root.dir.absoluteFile)
            Box {
                Text((root?.label ?: "根目录") + " ▾", fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { rootMenu = true }).padding(horizontal = 8.dp, vertical = 6.dp))
                WarmMenu(rootMenu, { rootMenu = false }) {
                    roots.forEach { r ->
                        WarmMenuItem(r.label, Fm.ic(when (r.id) { "phone" -> "smartphone"; "rootfs" -> "terminal"; "sessions" -> "messages-square"; else -> "folder" }),
                            selected = root?.id == r.id) { rootMenu = false; state.activeIdx = idx; pane.go(r.dir) }
                    }
                }
            }
            val rel = root?.let { pane.dir.absolutePath.removePrefix(it.dir.absolutePath) }?.ifBlank { "/" } ?: pane.dir.absolutePath
            Text(rel, fontSize = 12.sp, color = m.ink2, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            FmIconButton("refresh-cw", "刷新", { pane.refresh() })
        }

        if (root?.needsGrant == true && pane.dir.absolutePath.startsWith(FileRoots.phone().absolutePath)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("打开手机存储需要「所有文件访问」权限。", fontSize = MT.Body, color = m.ink)
                Text("点下面的按钮，在系统页面里打开 Mines 的开关，再回来。不想开也行：用右上角 ⋯ 里的「从系统选择器导入」。", fontSize = MT.Sub, color = m.ink2)
                com.openminis.app.ui.plus.GlassPill("去打开权限") { runCatching { ctx.startActivity(FileRoots.allFilesIntent(ctx)) } }
            }
            return@FmPanel
        }
        pane.error?.let { Text(it, fontSize = MT.Sub, color = Color(0xFFD9534F), modifier = Modifier.padding(14.dp)) }
        if (pane.items.isEmpty() && pane.error == null) Text("空文件夹", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(16.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(pane.items, key = { it.absolutePath }) { f ->
                val selected = f in pane.selection
                Row(Modifier.fillMaxWidth()
                    .background(if (selected) m.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .combinedClickable(
                        onClick = {
                            state.activeIdx = idx
                            if (pane.selecting) { if (selected) pane.selection.remove(f) else pane.selection.add(f) }
                            else if (f.isDirectory) pane.go(f)
                            else FmOpen.pageFor(f)?.let(onOpen)
                        },
                        onLongClick = { state.activeIdx = idx; if (selected) pane.selection.remove(f) else pane.selection.add(f) },
                    ).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (f.isDirectory) m.container else m.primary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center) {
                        MinesIcon(Fm.ic(if (selected) "check" else Fm.iconFor(f)), null, tint = m.icon, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 14.sp, lineHeight = 18.sp, color = m.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text((if (f.isDirectory) "文件夹" else HexCore.humanSize(f.length())) + " · " + Fm.time(f.lastModified()),
                            fontSize = 11.sp, lineHeight = 14.sp, color = m.ink2, maxLines = 1)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val err = FileOpsCore.badName(v)
    MinesDialog(title, onDismiss = onDismiss, confirm = "确定", confirmEnabled = err == null, onConfirm = { onOk(v.trim()) }) {
        OutlinedTextField(v, { v = it }, singleLine = true, colors = glassFieldColors(), modifier = Modifier.fillMaxWidth(),
            supportingText = err?.let { e -> { Text(e, fontSize = 12.sp) } })
    }
}

@Composable
private fun SearchSheet(root: File, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var inText by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<File>?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MinesSheet(onDismiss = onDismiss) {
        Text("在「${root.name.ifBlank { "/" }}」里搜索", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 20.dp))
        OutlinedTextField(q, { q = it }, singleLine = true, colors = glassFieldColors(), placeholder = { Text(if (inText) "要找的文字" else "文件名，可用 * 和 ?") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FmChip("搜名字", !inText) { inText = false }
            FmChip("搜文件内容", inText) { inText = true }
            Spacer(Modifier.weight(1f))
            com.openminis.app.ui.plus.GlassPill(if (running) "搜索中…" else "搜索", enabled = q.isNotBlank() && !running) {
                running = true
                scope.launch {
                    hits = withContext(Dispatchers.IO) { if (inText) FileOpsCore.search(root, "", contentText = q) else FileOpsCore.search(root, q) }
                    running = false
                }
            }
        }
        hits?.let { list ->
            Text(if (list.isEmpty()) "没找到" else "找到 ${list.size} 个" + if (list.size >= 500) "（只显示前 500 个）" else "", fontSize = MT.Sub, color = m.ink2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            list.take(200).forEach { f ->
                Row(Modifier.fillMaxWidth().combinedClickableCompat { onPick(f) }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MinesIcon(Fm.ic(Fm.iconFor(f)), null, tint = m.icon, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 14.sp, color = m.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(FileRoots.linuxPath(ctx, f) ?: f.absolutePath, fontSize = 11.sp, color = m.ink2, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/** Copy a picked document into [dest] under its display name. */
internal fun importInto(c: Context, uri: Uri, dest: File): File {
    var name = "导入的文件"
    runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0)?.let { name = it } }
    }
    val out = FileOpsCore.uniqueTarget(dest, name.replace('/', '_'))
    c.contentResolver.openInputStream(uri)?.use { ins -> out.outputStream().use { ins.copyTo(it) } } ?: throw FormatException("读取失败")
    return out
}

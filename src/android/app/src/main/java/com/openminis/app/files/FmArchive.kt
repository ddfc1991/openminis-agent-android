package com.openminis.app.files

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.plus.GlassPill
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.mines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [Mines-38.9.11 d] ZIP viewer: entry list, unpack here / to a chosen folder, and open one entry in the right viewer
 * (it is extracted to the app's cache first, so nothing is written next to the archive).
 */
@Composable
internal fun FmZipScreen(f: File, browser: BrowserState, onBack: () -> Unit, onOpen: (FmPage) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var items by remember(f) { mutableStateOf<List<ZipCore.Item>?>(null) }
    var error by remember(f) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(f) {
        withContext(Dispatchers.IO) { runCatching { ZipCore.list(f) } }.onSuccess { items = it }.onFailure { error = "读不了这个压缩包：" + (it.message ?: "") }
    }

    Column(Modifier.fillMaxSize()) {
        FmTopBar(f.name, sub = "压缩包 · " + (items?.let { "${it.size} 项" } ?: "读取中…") + " · " + HexCore.humanSize(f.length()), onBack = onBack) {
            FmIconButton("external-link", "其他应用打开", { Fm.openExternal(ctx, f) })
        }
        error?.let { Text(it, fontSize = MT.Body, color = m.ink, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            items(items ?: emptyList(), key = { it.path }) { e ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !e.isDir) {
                    busy = "正在取出…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val out = File(File(ctx.cacheDir, "fm-zip").apply { mkdirs() }, e.name)
                                out.writeBytes(ZipCore.readEntry(f, e.path) ?: throw FormatException("取不出这个条目"))
                                out
                            }
                        }
                        busy = null
                        r.onSuccess { out -> FmOpen.pageFor(out)?.let(onOpen) }.onFailure { Fm.toast(ctx, it.message ?: "取出失败") }
                    }
                }.heightIn(min = 44.dp).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MinesIcon(Fm.ic(if (e.isDir) "folder" else Fm.iconFor(File(e.name))), null, tint = m.icon, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.path, fontSize = 13.sp, lineHeight = 17.sp, color = m.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        if (!e.isDir) Text(HexCore.humanSize(e.size) + (if (e.stored) " · 未压缩" else "") , fontSize = 11.sp, color = m.ink2)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(busy ?: "解压到同目录", enabled = busy == null) {
                busy = "正在解压…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            val d = FileOpsCore.uniqueTarget(f.parentFile!!, f.nameWithoutExtension)
                            ZipCore.unpack(f, d) to d
                        }
                    }
                    busy = null
                    r.onSuccess { (res, d) ->
                        browser.left.refresh(); browser.right.refresh()
                        Fm.toast(ctx, "已解压 ${res.files} 个文件到「${d.name}」" + if (res.skipped.isNotEmpty()) "，跳过 ${res.skipped.size} 个不安全路径" else "")
                    }.onFailure { Fm.toast(ctx, "解压失败：" + (it.message ?: "")) }
                }
            }
            com.openminis.app.ui.plus.GlassSecondaryPill("解压到另一侧") {
                busy = "正在解压…"
                val dest = browser.other.dir
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { ZipCore.unpack(f, FileOpsCore.uniqueTarget(dest, f.nameWithoutExtension)) } }
                    busy = null
                    r.onSuccess { res -> browser.left.refresh(); browser.right.refresh(); Fm.toast(ctx, "已解压 ${res.files} 个文件到「${dest.name}」") }
                        .onFailure { Fm.toast(ctx, "解压失败：" + (it.message ?: "")) }
                }
            }
        }
    }
}

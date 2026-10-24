package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private data class PluginRow(val pkg: String, val enabled: Boolean, val ok: Boolean?, val reason: String, val tools: List<String>,
    val ui: Boolean = false, val llm: List<String> = emptyList(),
    /** [Mines-dsh-25] status.json came from an older socket: re-check before trusting the label. */
    val stale: Boolean = false)

/**
 * dsh plugin center. The work happens in the sandbox (`minis-dsh`), run in
 * the background with a progress dialog; this page lists what is installed
 * and which tools each plugin adds, read from the files minis-dsh keeps.
 */
@Composable
fun PluginsScreen(onBack: () -> Unit, onOpenTerminal: (String) -> Unit, onOpenMarket: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var modelMsg by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    // Re-read whenever the page comes back (e.g. returning from the terminal).
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    // [Mines-dsh] Installs / removals / checks run in the background with a
    // progress dialog instead of a pre-filled terminal waiting for Enter.
    val task = rememberShellTask { tick++ ; scope.launch { com.openminis.app.plus.DshLlmBridge.ensure(context); com.openminis.app.plus.DshWebHost.restart() } }
    val rows by produceState(initialValue = emptyList<PluginRow>(), tick) {
        value = withContext(Dispatchers.IO) { readPlugins(context) }
    }
    // [Mines-dsh-25] A status written by an older build (or none yet) is
    // refreshed in the background once per visit — no dialog, no tapping.
    var autoChecked by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (!autoChecked && rows.isNotEmpty() && rows.any { it.stale && it.enabled }) {
            autoChecked = true
            task.run("检查插件", "minis-dsh list")
        }
    }

    // [Mines-38.5] one short intro line (the long paragraph scrolled under the bar and showed only 「面悬浮挂件。」)
    PlusPage("插件", onBack, subtitle = "插件能给 Mines 加工具、加模型，或在桌面显示悬浮挂件") {
        PlusSection(null) {
            ActionRow(Icons.Outlined.Storefront, "去市场找插件和技能", onOpenMarket)
        }
        val routes = remember(tick) { com.openminis.app.plus.DshLlmBridge.routes(context) }
        if (routes.isNotEmpty()) PlusSection("插件模型", footer = "这些模型由插件提供，Mines 通过本机 127.0.0.1:${com.openminis.app.plus.DshLlmBridge.PORT} 转给插件。0 个模型多半是还没登录。") {
            routes.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("${r.proxied} 个模型可用 · 来自 ${r.plugin}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (i < routes.lastIndex) PlusDivider()
            }
            PlusDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { modelMsg = "正在启动…"; scope.launch { modelMsg = com.openminis.app.plus.DshLlmBridge.ensure(context, force = true); tick++ } },
                    modifier = Modifier.fillMaxWidth()) { Text("加入模型列表 / 刷新") }
                if (modelMsg.isNotBlank()) Text(modelMsg, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (rows.any { it.pkg == "dsh-workbuddy-connect" }) {
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) scope.launch {
                    modelMsg = "正在导入登录…"
                    val text = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    }
                    modelMsg = if (text.isNullOrBlank()) "读不到这个文件" else com.openminis.app.plus.DshLlmBridge.workBuddyLogin(context, text, international = false)
                    tick++
                }
            }
            PlusSection("WorkBuddy 手机登录", footer = "手机上没有 WorkBuddy 桌面版，插件找不到登录。三选一：① 选登录文件：电脑上用 DSH 装过这个插件后，拷贝电脑里的 ~/.dsh/.workbuddy-auth.json（国际版是 .workbuddy-ai-auth.json）到手机再选它；② 粘贴登录信息（JSON 或访问令牌）；③ 如果你手机上开着 WorkBuddy 网关 App，直接用下面「发现本机模型网关」添加，不用登录插件。新版桌面端的 workbuddy-desktop.info 是加密的，手机解不开。") {
                ActionRow(Icons.Outlined.FileOpen, "选择登录文件") { picker.launch(arrayOf("*/*")) }
                PlusDivider()
                ActionRow(Icons.Outlined.ContentPaste, "粘贴登录信息") { showPaste = true }
                if (modelMsg.isNotBlank() && routes.isEmpty()) Text(modelMsg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
            LocalGatewayCards()
        }
        if (showPaste) WorkBuddyPasteDialog(onDismiss = { showPaste = false }) { text, intl ->
            showPaste = false
            modelMsg = "正在保存登录…"
            scope.launch { modelMsg = com.openminis.app.plus.DshLlmBridge.workBuddyLogin(context, text, intl); tick++ }
        }
        PlusSection("安装") {
            var help by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it.trim() }, placeholder = { Text("插件名或 GitHub 链接", fontSize = MT.Body) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassTextButton(if (help) "收起帮助" else "怎么填？") { help = !help }
                    Spacer(Modifier.weight(1f))
                    GlassPill("安装", enabled = input.isNotBlank() && input.none { it == ' ' || it == ';' || it == '&' || it == '|' || it == '`' || it == '$' }) {
                        task.run("安装 ${normalize(input)}", "minis-dsh add ${normalize(input)}"); input = ""
                    }
                }
                if (help) Text("填 npm 包名（如 @zhaoolee/dsh-notes），或 github:用户/仓库，也可以直接粘贴 GitHub 链接。第一次安装会自动装好 Node.js。",
                    fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = mines().ink2)
            }
        }
        PlusSection(if (rows.isEmpty()) "已安装" else "已安装 · ${rows.size}") {
            if (rows.isEmpty()) Text("还没有插件", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            rows.forEachIndexed { i, r ->
                // [Mines-38.5] title · one status line · actions (switch and 卸载 were squeezed into the text, which wrapped badly)
                val m = mines()
                var more by remember(r.pkg) { mutableStateOf(false) }
                val dot = when { !r.enabled -> m.ink3; r.ok == true -> (if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success); r.ok == false -> MinesDesign.DangerFill; else -> (if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning) }
                val status = when {
                    !r.enabled -> "已停用"
                    r.stale -> "正在后台检查…"
                    r.ok == true -> listOfNotNull(if (r.ui) "挂件" else null,
                        r.tools.takeIf { it.isNotEmpty() }?.let { "${it.size} 个工具" }, r.llm.takeIf { it.isNotEmpty() }?.let { "${it.size} 个模型" })
                        .joinToString(" · ").ifEmpty { "已加载" }.let { "能用 · $it" }
                    r.ok == false -> "用不了：" + if (r.reason.contains("dsh 内部服务") || r.reason.contains("internal")) "这个插件要的功能手机版还没有" else r.reason.take(24)
                    else -> "还没检查"
                }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(r.pkg.substringAfterLast('/'), fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(status, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (more) Text(listOf(r.pkg, r.reason.takeIf { it.isNotBlank() }, r.tools.takeIf { it.isNotEmpty() }?.joinToString("、")).filterNotNull().joinToString("\n"),
                        fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink3, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.enabled) "启用" else "停用", fontSize = MT.Sub, color = m.ink2)
                        Spacer(Modifier.width(8.dp))
                        Switch(r.enabled, onCheckedChange = { on -> task.run(if (on) "启用 ${r.pkg}" else "停用 ${r.pkg}", "minis-dsh ${if (on) "on" else "off"} ${r.pkg} && minis-dsh list") })
                        Spacer(Modifier.weight(1f))
                        GlassTextButton(if (more) "收起" else "详情", color = m.ink2) { more = !more }
                        GlassTextButton("卸载", color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) { task.run("卸载 ${r.pkg}", "minis-dsh remove ${r.pkg}") }
                    }
                }
                if (r.ui && r.enabled && r.ok != false) {
                    // [Mines-dsh-ui] Widget plugins: shown as a floating window.
                    TextButton(
                        onClick = {
                            if (!com.openminis.app.plus.PluginWidgetService.canShow(context)) {
                                runCatching {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            } else scope.launch {
                                modelMsg = "正在启动挂件…"
                                val ok = com.openminis.app.plus.DshWebHost.ensure(context)
                                modelMsg = if (ok) "" else "挂件服务没起来：" + com.openminis.app.plus.DshWebHost.lastLog(context).ifBlank { "点「重新检查」再试" }
                                if (ok) com.openminis.app.plus.PluginWidgetService.start(context)
                            }
                        },
                        modifier = Modifier.padding(start = 30.dp),
                    ) { Text(if (com.openminis.app.plus.PluginWidgetService.running) "挂件已显示（长按挂件顶部小条关闭）" else "显示挂件") }
                    if (modelMsg.isNotBlank()) Text(modelMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 44.dp))
                }
                if (i < rows.lastIndex) PlusDivider()
            }
            if (rows.isNotEmpty()) {
                PlusDivider()
                ActionRow(Icons.Outlined.Refresh, "重新检查") { task.run("检查插件", "minis-dsh list") }
            }
        }
    }
}

private fun normalize(s: String): String {
    val gh = Regex("^https?://github\\.com/([^/]+)/([^/#?]+)").find(s)
    return if (gh != null) "github:${gh.groupValues[1]}/${gh.groupValues[2].removeSuffix(".git")}" else s
}

private fun readPlugins(context: android.content.Context): List<PluginRow> = runCatching {
    val root = com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
    val home = File(root, "root/.minis-dsh")
    val state = JSONObject(File(home, "plugins.json").readText())
    val status = runCatching { JSONObject(File(home, "status.json").readText()) }.getOrNull()
    val fresh = com.openminis.app.plus.DshWebHost.statusFresh(context)
    val arr = state.optJSONArray("plugins") ?: return@runCatching emptyList()
    (0 until arr.length()).map { i ->
        val p = arr.getJSONObject(i)
        val pkg = p.optString("package")
        val st = status?.optJSONObject(pkg)
        val tools = st?.optJSONArray("tools")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
        val llm = st?.optJSONArray("llm")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
        val ui = st?.optBoolean("ui") == true || st?.optString("kind") == "widget" ||
            ((!fresh || st == null) && com.openminis.app.plus.DshWebHost.looksLikeWidget(context, pkg))
        PluginRow(pkg, p.optBoolean("enabled", true), st?.optBoolean("ok"), st?.optString("reason") ?: "", tools,
            ui, llm, stale = !fresh || st == null)
    }
}.getOrDefault(emptyList())

@Composable
private fun WorkBuddyPasteDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var intl by remember { mutableStateOf(false) }
    com.openminis.app.ui.glass.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 WorkBuddy 登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("登录 JSON 或访问令牌") }, minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("国际版（WorkBuddy AI）", modifier = Modifier.weight(1f))
                    Switch(intl, onCheckedChange = { intl = it })
                }
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text.trim(), intl) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

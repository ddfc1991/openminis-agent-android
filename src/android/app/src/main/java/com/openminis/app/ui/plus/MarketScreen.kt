package com.openminis.app.ui.plus

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** [Mines-38.9.1] open 市场 on a tab next (smoke / 技能 entry points): 0 插件, 1 技能 */
object MarketNav { @Volatile var tab = 0 }

/** 市场: dsh plugins + MCP servers (tab 1) and community skills (tab 2). */
@Composable
fun MarketScreen(onBack: () -> Unit, onOpenTerminal: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(MarketNav.tab.also { MarketNav.tab = 0 }) }
    PlusPage("市场", onBack) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("插件", style = MaterialTheme.typography.titleMedium) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("技能", style = MaterialTheme.typography.titleMedium) })
        }
        if (tab == 0) SkillHubPluginMarket(onOpenTerminal) else SkillsTab()
    }
}

// ── plugins ────────────────────────────────────────────────────────────

private data class NpmPkg(val name: String, val version: String, val description: String)

private data class McpPick(val name: String, val pkg: String, val args: String, val title: String, val note: String)

private val curatedMcp = listOf(
    McpPick("memory", "@modelcontextprotocol/server-memory", "", "长期记忆", "把要记住的事存成知识图谱"),
    McpPick("thinking", "@modelcontextprotocol/server-sequential-thinking", "", "分步思考", "复杂问题一步一步想清楚"),
    McpPick("files", "@modelcontextprotocol/server-filesystem", "/root", "沙箱文件", "读写沙箱 /root 里的文件"),
    McpPick("context7", "@upstash/context7-mcp", "", "最新编程文档", "查各种库的最新文档和示例"),
    McpPick("weread", "mcp-server-weread", "", "微信读书", "读书笔记和书架（需要在 MCP 页填 Cookie）"),
)

private fun httpGet(url: String, timeoutMs: Int = 12000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
    c.setRequestProperty("User-Agent", "Mines")
    c.setRequestProperty("Accept", "application/json")
    val code = c.responseCode
    if (code == 403 || code == 429) throw IllegalStateException("对方限流了，过一会再试")
    if (code !in 200..299) throw IllegalStateException("HTTP $code")
    return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
}

private fun searchNpm(query: String): List<NpmPkg> {
    val q = if (query.isBlank()) "keywords:dsh-plugin" else "$query keywords:dsh-plugin"
    val j = JSONObject(httpGet("https://registry.npmjs.org/-/v1/search?size=60&text=" + URLEncoder.encode(q, "UTF-8")))
    val arr = j.optJSONArray("objects") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val p = arr.getJSONObject(i).optJSONObject("package") ?: return@mapNotNull null
        NpmPkg(p.optString("name"), p.optString("version"), p.optString("description"))
    }
}

private fun rootfs(ctx: Context) = com.openminis.app.sandbox.RootfsManager.getInstance(ctx).rootfsDir

private data class InstalledPlugin(val enabled: Boolean, val ok: Boolean?, val reason: String)

private fun installedPlugins(ctx: Context): Map<String, InstalledPlugin> = runCatching {
    val home = File(rootfs(ctx), "root/.minis-dsh")
    val state = JSONObject(File(home, "plugins.json").readText())
    val status = runCatching { JSONObject(File(home, "status.json").readText()) }.getOrNull()
    val arr = state.optJSONArray("plugins") ?: JSONArray()
    (0 until arr.length()).associate { i ->
        val p = arr.getJSONObject(i)
        val st = status?.optJSONObject(p.optString("package"))
        p.optString("package") to InstalledPlugin(p.optBoolean("enabled", true), st?.optBoolean("ok"), st?.optString("reason").orEmpty())
    }
}.getOrDefault(emptyMap())

private fun installedMcp(ctx: Context): Set<String> = runCatching {
    val f = File(rootfs(ctx), "var/minis/mcp-servers/servers.json")
    JSONObject(f.readText()).optJSONObject("mcpServers")?.keys()?.asSequence()?.toSet() ?: emptySet()
}.getOrDefault(emptySet())

private fun safeName(s: String) = s.isNotBlank() && s.none { it == ' ' || it == ';' || it == '&' || it == '|' || it == '`' || it == '$' || it == '\'' || it == '"' }

private data class NpmInfo(val name: String, val version: String, val description: String, val adds: List<String>, val homepage: String)

/** [Mines-market] npm metadata → what the plugin will add to Mines. */
private fun npmInfo(name: String): NpmInfo {
    val j = JSONObject(httpGet("https://registry.npmjs.org/" + name.replace("/", "%2F") + "/latest"))
    val kw = j.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } } ?: emptyList()
    val desc = j.optString("description")
    val adds = buildList {
        if (j.optJSONObject("dsh")?.has("bundle") == true || kw.any { it in setOf("widget", "webui", "web-ui", "ui") }) add("界面挂件（桌面悬浮显示）")
        if (kw.any { it in setOf("llm", "provider", "model", "models", "llm-provider") } || desc.contains("模型") || desc.contains("model", true)) add("模型（出现在模型选择里）")
        if (isEmpty() || kw.any { it in setOf("tool", "tools", "mcp") }) add("工具 / 提示词（给 AI 用）")
    }
    return NpmInfo(j.optString("name", name), j.optString("version"), desc, adds,
        j.optString("homepage").ifBlank { "https://www.npmjs.com/package/$name" })
}

@Composable
private fun TypeBadge(text: String) {
    val c = when (text) { "MCP" -> Color(0xFF5B8DEF); "技能" -> Color(0xFFAF52DE); else -> Color(0xFFD97757) }
    Text(text, fontSize = MT.Caption, color = c, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
private fun IntroDialog(
    title: String, badge: String, version: String?, desc: String, adds: List<String>, extra: String?,
    installed: Boolean, busy: Boolean, onInstall: () -> Unit, onUninstall: (() -> Unit)?, onDismiss: () -> Unit,
    /** [Mines-38.9.8] the original (English) text when [desc] is a translation → small 「原文」 toggle */
    original: String? = null,
    /** [Mines-38.9.8] plain reason it cannot run on the phone → 一键安装 disabled */
    blockNote: String? = null,
    /** [Mines-38.9.8] 「转成手机技能」 for prompt / doc plugins */
    onConvert: (() -> Unit)? = null,
) {
    var showOrig by remember { mutableStateOf(false) }
    com.openminis.app.ui.glass.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(badge); Spacer(Modifier.width(8.dp))
                Text(title, fontSize = MT.PageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!version.isNullOrBlank()) Text("版本 v$version" + if (installed) " · 已安装" else "", fontSize = MT.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (showOrig && original != null) original else desc).ifBlank { "（作者没写简介）" }, fontSize = MT.Body)
                if (original != null && original != desc) Text(if (showOrig) "看中文" else "原文", fontSize = MT.Caption, color = mines().primaryText,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showOrig = !showOrig }.padding(vertical = 2.dp, horizontal = 2.dp)
                        .semantics { contentDescription = if (showOrig) "看中文简介" else "看英文原文" })
                if (blockNote != null) Text(blockNote, fontSize = MT.Body, color = if (mines().dark) MinesDesign.WarningDark else MinesDesign.Warning,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background((if (mines().dark) MinesDesign.WarningCDark else MinesDesign.WarningC)).padding(10.dp))
                if (adds.isNotEmpty()) {
                    Text("装好后会加：", fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    adds.forEach { Text("• $it", fontSize = MT.Body) }
                }
                if (!extra.isNullOrBlank()) Text(extra, fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            if (onConvert != null && blockNote == null) Button(enabled = !busy, onClick = onConvert) { Text("转成手机技能") }
            else Button(enabled = !busy && blockNote == null, onClick = onInstall) { Text(if (installed) "更新" else "一键安装") }
        },
        dismissButton = {
            Row {
                if (onUninstall != null) TextButton(enabled = !busy, onClick = onUninstall) { Text("卸载", color = Color(0xFFFF3B30)) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun LegacyPluginMarket(@Suppress("UNUSED_PARAMETER") onOpenTerminal: (String) -> Unit, mcpOnly: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var intro by remember { mutableStateOf<NpmPkg?>(null) }
    var introInfo by remember { mutableStateOf<NpmInfo?>(null) }
    var introMcp by remember { mutableStateOf<McpPick?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<NpmPkg>>(emptyList()) }
    var msg by remember { mutableStateOf("正在加载…") }
    var tick by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<Map<String, InstalledPlugin>>(emptyMap()) }
    var mcp by remember { mutableStateOf<Set<String>>(emptySet()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { installed = installedPlugins(ctx); mcp = installedMcp(ctx) }
    }
    // [Mines-market] One tap, no terminal: runs in the background with progress.
    val task = rememberShellTask { tick++; scope.launch { com.openminis.app.plus.DshLlmBridge.ensure(ctx); com.openminis.app.plus.DshWebHost.restart() } }
    fun installPkg(name: String) { if (safeName(name)) task.run("安装 $name", "minis-dsh add $name@latest") }
    fun removePkg(name: String) { if (safeName(name)) task.run("卸载 $name", "minis-dsh remove $name") }
    fun addMcp(m: McpPick) = task.run("添加 ${m.title}", "minis-dsh mcp-add ${m.name} ${m.pkg}${if (m.args.isNotBlank()) " " + m.args else ""}")
    fun removeMcp(m: McpPick) = task.run("移除 ${m.title}", "minis-dsh mcp-remove ${m.name}")
    intro?.let { p ->
        LaunchedEffect(p.name) { introInfo = null; introInfo = withContext(Dispatchers.IO) { runCatching { npmInfo(p.name) }.getOrNull() } }
        val inst = installed[p.name]
        val info = introInfo
        IntroDialog(
            title = p.name, badge = "DSH插件", version = info?.version ?: p.version,
            desc = info?.description ?: p.description, adds = info?.adds ?: listOf("正在读取…"),
            extra = inst?.let { if (it.ok == false) "当前状态：用不了 — ${it.reason}" else "当前状态：已装" } ?: info?.homepage,
            installed = inst != null, busy = task.state.running,
            onInstall = { installPkg(p.name); intro = null },
            onUninstall = if (inst != null) ({ removePkg(p.name); intro = null }) else null,
            onDismiss = { intro = null },
        )
    }
    introMcp?.let { m ->
        val has = m.name in mcp
        IntroDialog(
            title = m.title, badge = "MCP", version = null, desc = m.note, adds = listOf("MCP 服务 ${m.pkg}（第一次用时自动下载）"),
            extra = null, installed = has, busy = task.state.running,
            onInstall = { addMcp(m); introMcp = null }, onUninstall = if (has) ({ removeMcp(m); introMcp = null }) else null,
            onDismiss = { introMcp = null },
        )
    }
    fun search() = scope.launch {
        msg = "正在搜索…"
        runCatching { withContext(Dispatchers.IO) { searchNpm(query.trim()) } }
            .onSuccess { results = it; msg = if (it.isEmpty()) "没找到" else "" }
            .onFailure { msg = "搜索失败：${it.message}" }
    }
    LaunchedEffect(Unit) { if (!mcpOnly) search() }

    if (!mcpOnly) PlusSection("搜索 dsh 插件", footer = "点条目看介绍，点「安装」一键装好（不用开终端）。装好后「插件」页显示它加了哪些工具、模型或挂件。") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("关键词（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) { Text("搜索") }
        }
    }
    PlusSection("常用 MCP 服务", footer = "一键加到 Mines 的 MCP 里，第一次用时自动下载。") {
        curatedMcp.forEachIndexed { i, m ->
            val has = m.name in mcp
            MarketRow(
                title = m.title, sub = "${m.note} · ${m.pkg}", badge = "MCP",
                status = if (has) "已添加" else null,
                primary = if (has) "移除" else "安装",
                primaryDanger = has,
                onPrimary = { if (has) removeMcp(m) else addMcp(m) },
                onClick = { introMcp = m },
            )
            if (i < curatedMcp.lastIndex) PlusDivider()
        }
    }
    if (!mcpOnly) PlusSection(if (results.isEmpty()) "插件（npm）" else "插件（npm）· ${results.size}") {
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        results.forEachIndexed { i, p ->
            val inst = installed[p.name]
            val status = when {
                inst == null -> null
                !inst.enabled -> "已装 · 已停用"
                inst.ok == true -> "已装 · 正常"
                inst.ok == false -> "已装 · 用不了：${inst.reason}"
                else -> "已装"
            }
            MarketRow(
                title = p.name, sub = "${p.description.ifBlank { "（无简介）" }} · v${p.version}", badge = "DSH插件",
                status = status, statusBad = inst?.ok == false,
                primary = if (inst == null) "安装" else "更新",
                onPrimary = { installPkg(p.name) },
                secondary = if (inst != null) "卸载" else null,
                onSecondary = { removePkg(p.name) },
                onClick = { intro = p },
            )
            if (i < results.lastIndex) PlusDivider()
        }
    }
}

@Composable
private fun MarketRow(
    title: String, sub: String, status: String?, statusBad: Boolean = false, badge: String? = null,
    primary: String, onPrimary: () -> Unit, primaryDanger: Boolean = false,
    secondary: String? = null, onSecondary: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) { TypeBadge(badge); Spacer(Modifier.width(6.dp)) }
                Text(title, fontSize = MT.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(sub, fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, fontSize = MT.Caption,
                color = if (statusBad) Color(0xFFFF3B30) else Color(0xFF34C759))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (primaryDanger) OutlinedButton(onClick = onPrimary) { Text(primary, color = Color(0xFFFF3B30)) }
            else Button(onClick = onPrimary) { Text(primary) }
            if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary, color = Color(0xFFFF3B30)) }
        }
    }
}

// ── skills ─────────────────────────────────────────────────────────────

private data class RemoteSkill(val repo: String, val dir: String, val files: List<String>) {
    val name: String get() = dir.substringAfterLast('/').ifBlank { repo.substringAfter('/') }
}

private object SkillMarketData {
    val DEFAULT_REPOS = listOf("anthropics/skills", "obra/superpowers", "ComposioHQ/awesome-claude-skills")
    private const val PREFS = "mines_skill_market"
    val cache = mutableMapOf<String, List<RemoteSkill>>()

    fun repos(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("repos", null) ?: return DEFAULT_REPOS
        return raw.split('\n').map { it.trim() }.filter { it.matches(Regex("^[\\w.-]+/[\\w.-]+$")) }
    }

    fun setRepos(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("repos", list.joinToString("\n")).apply()
    }

    fun list(repo: String): List<RemoteSkill> {
        cache[repo]?.let { return it }
        val j = JSONObject(httpGet("https://api.github.com/repos/$repo/git/trees/HEAD?recursive=1", 20000))
        val tree = j.optJSONArray("tree") ?: JSONArray()
        val blobs = (0 until tree.length()).map { tree.getJSONObject(it) }.filter { it.optString("type") == "blob" }
            .map { it.optString("path") to it.optLong("size") }
        val dirs = blobs.map { it.first }.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
            .map { it.removeSuffix("SKILL.md").trimEnd('/') }
        val out = dirs.map { d ->
            val prefix = if (d.isEmpty()) "" else "$d/"
            RemoteSkill(repo, d, blobs.filter { (p, size) ->
                p.startsWith(prefix) && size < 2_000_000 &&
                    // files of a nested skill belong to that one, not this one
                    dirs.none { o -> o != d && o.startsWith(prefix) && p.startsWith("$o/") }
            }.map { it.first }.take(200))
        }.sortedBy { it.name.lowercase() }
        cache[repo] = out
        return out
    }

    fun skillsDir(ctx: Context) = File(ctx.filesDir, "minis-global/skills")

    fun installedSource(ctx: Context, name: String): String? {
        val d = File(skillsDir(ctx), name)
        if (!d.isDirectory) return null
        return File(d, ".mines-source").takeIf { it.isFile }?.readText()?.trim() ?: "local"
    }

    fun description(s: RemoteSkill): String = runCatching {
        val path = if (s.dir.isEmpty()) "SKILL.md" else "${s.dir}/SKILL.md"
        val text = httpGet("https://raw.githubusercontent.com/${s.repo}/HEAD/$path")
        Regex("(?m)^description:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.startsWith("name:") }.orEmpty()
    }.getOrDefault("")

    fun install(ctx: Context, s: RemoteSkill): String {
        val target = File(skillsDir(ctx), s.name)
        val src = installedSource(ctx, s.name)
        if (src == "local") return "已经有一个同名的本地技能「${s.name}」，没有覆盖"
        val tmp = File(ctx.cacheDir, "skill-dl-${s.name}").apply { deleteRecursively(); mkdirs() }
        val prefix = if (s.dir.isEmpty()) "" else "${s.dir}/"
        for (p in s.files) {
            val rel = p.removePrefix(prefix)
            if (rel.contains("..")) continue
            val url = "https://raw.githubusercontent.com/${s.repo}/HEAD/" + p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 20000
            if (c.responseCode !in 200..299) continue
            val f = File(tmp, rel).apply { parentFile?.mkdirs() }
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        }
        if (!File(tmp, "SKILL.md").isFile) { tmp.deleteRecursively(); return "下载失败（没拿到 SKILL.md）" }
        File(tmp, ".mines-source").writeText("${s.repo}/${s.dir}\n")
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) { tmp.copyRecursively(target, overwrite = true); tmp.deleteRecursively() }
        (ctx.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("mines-market", force = true)
        return if (src == null) "已安装「${s.name}」" else "已更新「${s.name}」"
    }

    fun uninstall(ctx: Context, name: String): String {
        val d = File(skillsDir(ctx), name)
        if (installedSource(ctx, name) == "local") return "这是本地技能，请在 设置 → 技能 里删"
        d.deleteRecursively()
        (ctx.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("mines-market", force = true)
        return "已卸载「$name」"
    }
}

@Composable
private fun SkillMarket() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf(SkillMarketData.repos(ctx)) }
    var repo by remember { mutableStateOf(repos.firstOrNull() ?: "") }
    var skills by remember { mutableStateOf<List<RemoteSkill>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var newRepo by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var desc by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(repo) {
        if (repo.isBlank()) return@LaunchedEffect
        msg = "正在读取 $repo …"; skills = emptyList()
        runCatching { withContext(Dispatchers.IO) { SkillMarketData.list(repo) } }
            .onSuccess { skills = it; msg = if (it.isEmpty()) "这个仓库里没有 SKILL.md" else "" }
            .onFailure { msg = "读取失败：${it.message}" }
    }

    PlusSection("技能仓库", footer = "GitHub 上的 SKILL.md 技能合集。可以加自己的仓库（用户名/仓库名）。") {
        repos.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().clickable { repo = r }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (r == repo) "● " else "○ ") + r, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    color = if (r == repo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (r !in SkillMarketData.DEFAULT_REPOS) TextButton(onClick = {
                    repos = repos - r; SkillMarketData.setRepos(ctx, repos); if (repo == r) repo = repos.firstOrNull() ?: ""
                }) { Text("移除", color = Color(0xFFFF3B30)) }
            }
            if (i < repos.lastIndex) PlusDivider()
        }
        PlusDivider()
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newRepo, { newRepo = it.trim() }, label = { Text("添加仓库，如 owner/repo") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = newRepo.matches(Regex("^[\\w.-]+/[\\w.-]+$")), onClick = {
                repos = (repos + newRepo).distinct(); SkillMarketData.setRepos(ctx, repos); repo = newRepo; newRepo = ""
            }) { Text("添加") }
        }
    }

    val shown = skills.filter { filter.isBlank() || it.name.contains(filter, true) || it.dir.contains(filter, true) }
    PlusSection(if (skills.isEmpty()) "技能" else "技能 · ${skills.size}", footer = if (shown.size > 80) "只显示前 80 个，用上面的搜索框找" else null) {
        if (skills.size > 12) OutlinedTextField(filter, { filter = it }, label = { Text("搜索技能") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp))
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        val list = shown.take(80)
        list.forEachIndexed { i, s ->
            val src = remember(tick, s.name) { SkillMarketData.installedSource(ctx, s.name) }
            val key = s.repo + "/" + s.dir
            MarketRow(
                title = s.name, badge = "技能",
                sub = if (expanded == key) desc.ifBlank { "正在读取简介…" } else "${s.dir.ifBlank { "/" }} · ${s.files.size} 个文件 · 点一下看简介",
                // [Mines-38.9.5 i11] 已安装 only for THIS repo/dir (the install record), a same-name skill from elsewhere = 同名已装
                status = when { src == null -> null; src == "local" -> "已有同名本地技能"; src == "${s.repo}/${s.dir}" -> "已安装"; else -> "同名已装" },
                statusBad = src == "local" || (src != null && src != "${s.repo}/${s.dir}"),
                primary = if (busy == key) "…" else if (src == null) "安装" else if (src == "${s.repo}/${s.dir}" || src == "local") "更新" else "替换",
                onPrimary = {
                    if (busy == null && src != "local") {
                        busy = key
                        scope.launch {
                            msg = withContext(Dispatchers.IO) { runCatching { SkillMarketData.install(ctx, s) }.getOrElse { "安装失败：${it.message}" } }
                            busy = null; tick++
                        }
                    }
                },
                secondary = if (src == "${s.repo}/${s.dir}") "卸载" else null,
                onSecondary = { scope.launch { msg = withContext(Dispatchers.IO) { SkillMarketData.uninstall(ctx, s.name) }; tick++ } },
                onClick = {
                    if (expanded == key) expanded = null else {
                        expanded = key; desc = ""
                        scope.launch { desc = withContext(Dispatchers.IO) { SkillMarketData.description(s) }.ifBlank { "（没有简介）" } }
                    }
                },
            )
            if (i < list.lastIndex) PlusDivider()
        }
    }
}


// ── [Mines-38.9.1] Skills Hub (Hermes Agent's index, ~100 k skills); the GitHub-repo market above is the fallback ──

@Composable
private fun HermesSkillMarket() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val H = com.openminis.app.plus.SkillsHub
    var count by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var src by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<com.openminis.app.plus.SkillsHub.Skill>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var sources by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    fun sync(force: Boolean) {
        if (syncing) return
        syncing = true; failed = false
        scope.launch {
            status = if (count == 0) "第一次打开：正在下载 Skills Hub 目录（约 11 MB）…" else "正在检查 Skills Hub 有没有更新…"
            val r = withContext(Dispatchers.IO) { runCatching { H.sync(ctx, force) { n -> status = "正在建立本地目录… 已读 $n 个" } } }
            count = withContext(Dispatchers.IO) { H.count(ctx) }
            sources = withContext(Dispatchers.IO) { H.sources(ctx) }
            status = r.getOrElse { e -> failed = count == 0; "连不上 Skills Hub：${e.message ?: e.javaClass.simpleName}" + if (count > 0) "（先用本地缓存的目录）" else "" }
            syncing = false; tick++
        }
    }
    LaunchedEffect(Unit) {
        count = withContext(Dispatchers.IO) { H.count(ctx) }
        sources = withContext(Dispatchers.IO) { H.sources(ctx) }
        sync(false)
    }
    LaunchedEffect(query, cat, src, count, tick) {
        if (count == 0) { items = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        val l = withContext(Dispatchers.IO) { runCatching { H.page(ctx, query, cat, src, 0) }.getOrDefault(emptyList()) }
        items = l; more = l.size >= H.PAGE
    }

    if (failed && count == 0) {
        PlusSection("Skills Hub", footer = "连不上 Hermes Skills Hub，先用 GitHub 技能仓库。") {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            TextButton(onClick = { sync(true) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("重试 Skills Hub") }
        }
        SkillMarket()
        return
    }

    PlusSection(if (count > 0) "Skills Hub · $count 个技能" else "Skills Hub",
        footer = "目录来自 Hermes Agent Skills Hub（hermes-agent.nousresearch.com/docs/skills），每两天更新一次，存在手机里按页读取。") {
        OutlinedTextField(query, { query = it }, label = { Text("搜索技能（中英文都行）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("全部", fontSize = MT.Caption) })
            H.CATS.forEach { k -> FilterChip(selected = cat == k.label, onClick = { cat = if (cat == k.label) null else k.label }, label = { Text(k.label, fontSize = MT.Caption) }) }
        }
        if (sources.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = src == null, onClick = { src = null }, label = { Text("所有来源", fontSize = MT.Caption) })
            sources.forEach { (sname, n) -> FilterChip(selected = src == sname, onClick = { src = if (src == sname) null else sname }, label = { Text("$sname $n", fontSize = MT.Caption) }) }
        }
        if (status.isNotBlank()) Text(status, fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (msg.isNotBlank()) Text(msg, fontSize = MT.Body, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (!syncing && count > 0) TextButton(onClick = { sync(true) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("立即更新目录", fontSize = MT.Caption) }
    }
    if (count > 0) PlusSection(if (items.isEmpty()) "没有找到" else "技能", footer = null) {
        items.forEachIndexed { i, s ->
            val inst = remember(tick, s.id) { H.installedSource(ctx, s) }
            val canInstall = s.source != "LobeHub"
            MarketRow(
                title = s.name, badge = s.source,
                sub = if (expanded == s.id) (s.desc.ifBlank { "（没有简介）" } + "\n" + s.ident) else s.desc.ifBlank { s.ident },
                // [Mines-38.9.5 i11] matched by the install record (hub:<source>:<ident>), not by the skill's name
                status = when { inst == null -> null; inst == "local" -> "已有同名本地技能"; inst == "hub:${s.source}:${s.ident}" -> "已安装"; else -> "同名已装" },
                statusBad = inst == "local" || (inst != null && inst != "hub:${s.source}:${s.ident}"),
                primary = if (busy == s.id) "…" else if (!canInstall) "只能看" else if (inst == null) "安装" else if (inst == "hub:${s.source}:${s.ident}") "更新" else "替换",
                onPrimary = {
                    if (busy == null && inst != "local" && canInstall) {
                        busy = s.id
                        scope.launch {
                            msg = withContext(Dispatchers.IO) { runCatching { H.install(ctx, s) }.getOrElse { "安装失败：${it.message}" } }
                            busy = null; tick++
                        }
                    }
                },
                secondary = if (inst == "hub:${s.source}:${s.ident}") "卸载" else null,
                onSecondary = { scope.launch { msg = withContext(Dispatchers.IO) { H.uninstall(ctx, s) }; tick++ } },
                onClick = { expanded = if (expanded == s.id) null else s.id },
            )
            if (i < items.lastIndex) PlusDivider()
        }
        if (more) TextButton(onClick = {
            scope.launch {
                val l = withContext(Dispatchers.IO) { runCatching { H.page(ctx, query, cat, src, items.size) }.getOrDefault(emptyList()) }
                items = items + l; more = l.size >= H.PAGE
            }
        }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("再显示 ${H.PAGE} 个") }
    }
}


// ── [Mines-38.9.4] Tencent SkillHub (skillhub.cn): DSH plugins + skills, China-hosted ──

private fun installedSpecs(ctx: Context): Map<String, String> = runCatching {
    val o = JSONObject(File(rootfs(ctx), "root/.minis-dsh/package.json").readText()).optJSONObject("dependencies") ?: JSONObject()
    o.keys().asSequence().associateWith { o.optString(it) }
}.getOrDefault(emptyMap())

@Composable
private fun HubLogo(url: String, name: String) {
    if (url.isBlank()) { AcctLetter(name); return }
    val shape = RoundedCornerShape(AcctUi.LogoRadius)
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) { AcctLetter(name); return }
    androidx.compose.foundation.layout.Box(Modifier.size(AcctUi.Logo).clip(shape).background(Color.White)
        .border(1.dp, Color(0x1F3C2814), shape), contentAlignment = Alignment.Center) {
        coil.compose.AsyncImage(model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(AcctUi.Logo), onError = { failed = true })
    }
}

/** 40 dp action pill used by the market rows (安装 / 更新). */
@Composable
private fun HubAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val m = mines()
    androidx.compose.foundation.layout.Box(Modifier.padding(start = 8.dp).height(40.dp).clip(RoundedCornerShape(50))
        .background(if (enabled) m.primary else m.primary.copy(alpha = 0.45f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp)
        .semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Button, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun HubChips(options: List<Pair<String?, String>>, selected: String?, onPick: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MS.PageH, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (k, label) -> FilterChip(selected = selected == k, onClick = { onPick(k) }, label = { Text(label, fontSize = MT.Meta) }) }
    }
}

private fun starsText(n: Int): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

@Composable
private fun SkillHubPluginMarket(onOpenTerminal: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val H = com.openminis.app.plus.SkillHubCn
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("stars") }
    var verifiedOnly by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<com.openminis.app.plus.SkillHubCn.Plugin>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var unreachable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<Map<String, InstalledPlugin>>(emptyMap()) }
    var specs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var intro by remember { mutableStateOf<com.openminis.app.plus.SkillHubCn.Plugin?>(null) }
    // [Mines-38.9.8] user 01:22: 「手机能用」 (default) = only what runs here; off = also 「只能电脑用」 below
    var phoneOnly by remember { mutableStateOf(true) }
    var desktopItems by remember { mutableStateOf<List<com.openminis.app.plus.SkillHubCn.Plugin>>(emptyList()) }
    @Suppress("UNUSED_VARIABLE") val zhTick = com.openminis.app.plus.MarketZh.tick.collectAsState().value   // read now: new translations recompose
    fun zhOf(p: com.openminis.app.plus.SkillHubCn.Plugin): String = zhTick.let { _ -> com.openminis.app.plus.MarketZh.zh(ctx, "plugin", p.fullName, p.description) ?: p.description }
    LaunchedEffect(items, desktopItems, phoneOnly) {
        val list = items + (if (phoneOnly) emptyList() else desktopItems)
        if (list.isNotEmpty()) runCatching { com.openminis.app.plus.MarketZh.ensure(ctx, "plugin", list.map { it.fullName to it.description }) }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    LaunchedEffect(tick) { withContext(Dispatchers.IO) { installed = installedPlugins(ctx); specs = installedSpecs(ctx) } }
    val task = rememberShellTask { tick++; scope.launch { com.openminis.app.plus.DshLlmBridge.ensure(ctx); com.openminis.app.plus.DshWebHost.restart() } }
    LaunchedEffect(query, cat, sort, verifiedOnly) {
        kotlinx.coroutines.delay(if (query.isBlank()) 0 else 350)
        loading = true; page = 1
        val r = withContext(Dispatchers.IO) { runCatching { H.plugins(ctx, query, cat, sort, 1, verifiedOnly) } }
        r.onSuccess { items = it.items; desktopItems = it.desktop; total = it.total; unreachable = false
            status = if (it.fromCache) "SkillHub 暂时连不上，先显示缓存的目录" else if (it.items.isEmpty()) "没找到" else "" }
         .onFailure { unreachable = items.isEmpty(); status = "SkillHub 连不上，暂用 npm（${it.message ?: it.javaClass.simpleName}）" }
        loading = false
    }
    fun isInst(p: com.openminis.app.plus.SkillHubCn.Plugin) = H.isInstalled(p, installed.keys, specs)
    fun install(p: com.openminis.app.plus.SkillHubCn.Plugin) {
        if (task.state.running) return
        scope.launch {
            status = "正在读 ${p.name} 的安装计划…"
            val src = withContext(Dispatchers.IO) { runCatching { H.installSource(p) } }
            val spec = src.getOrNull() ?: "github:${p.owner}/${p.name}"
            status = src.exceptionOrNull()?.let { "安装计划读不到（${it.message}），改用 GitHub 直接装" } ?: ""
            if (safeName(spec)) task.run("安装 ${p.name}", "minis-dsh add $spec")
        }
    }
    fun uninstall(p: com.openminis.app.plus.SkillHubCn.Plugin) {
        val repo = "${p.owner}/${p.name}".lowercase()
        val pkg = specs.entries.firstOrNull { it.value.lowercase().removePrefix("github:").substringBefore('#') == repo }?.key
            ?: installed.keys.firstOrNull { it.equals(p.name, true) || it.substringAfterLast('/').equals(p.name, true) } ?: return
        if (safeName(pkg)) task.run("卸载 ${p.name}", "minis-dsh remove $pkg")
    }

    intro?.let { p ->
        val inst = isInst(p)
        val zhDesc = zhOf(p)
        IntroDialog(
            title = p.name, badge = "DSH插件", version = null, desc = zhDesc, original = p.description.takeIf { zhDesc != it },
            blockNote = p.kind.line.takeIf { p.kind.desktopOnly },
            onConvert = if (p.kind.convertible && !p.kind.desktopOnly && !p.kind.canTry) ({   // [i23b] never instead of 安装 for a runnable plugin (smoke 39151652252: DeepSeek-Reasonix)
                val target = p; intro = null
                scope.launch { status = "正在把 ${target.name} 转成手机技能…"
                    status = withContext(Dispatchers.IO) { runCatching { H.convertToSkill(ctx, target) }.getOrElse { "转不了：${it.message}" } } }
            }) else null,
            adds = listOfNotNull(p.categoryLabel.takeIf { it.isNotBlank() }?.let { "分类：$it" }, "★ ${starsText(p.stars)}",
                p.kind.line),   // [Mines-38.9.7] plain one-line reason instead of 「需手动」
            extra = p.repo.ifBlank { "${com.openminis.app.plus.SkillHubCn.WEB}" },
            installed = inst, busy = task.state.running,
            onInstall = { if (p.kind.desktopOnly) Unit else if (p.kind.canTry) install(p) else runCatching { if (p.repo.isNotBlank()) com.openminis.app.ui.plus.PlusNav.openBrowser.value = p.repo }; intro = null },
            onUninstall = if (inst) ({ uninstall(p); intro = null }) else null,
            onDismiss = { intro = null },
        )
    }

    if (unreachable) {
        Text(status, fontSize = MT.Meta, color = mines().ink2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp)
            .semantics { contentDescription = "SkillHub 连不上，暂用 npm" })
        LegacyPluginMarket(onOpenTerminal)
        return
    }
    AcctSearch(query, "搜索 SkillHub 插件（中英文都行）") { query = it }
    HubChips(listOf<Pair<String?, String>>(null to "全部") + H.PLUGIN_CATEGORIES.map { it.key to it.value }, cat) { cat = it }
    Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = sort == "stars", onClick = { sort = "stars" }, label = { Text("星标最多", fontSize = MT.Meta) })
        FilterChip(selected = sort == "updated", onClick = { sort = "updated" }, label = { Text("最新", fontSize = MT.Meta) })
        FilterChip(selected = verifiedOnly, onClick = { verifiedOnly = !verifiedOnly }, label = { Text("只看可一键装", fontSize = MT.Meta) })
        FilterChip(selected = phoneOnly, onClick = { phoneOnly = !phoneOnly }, label = { Text("手机能用", fontSize = MT.Meta) })
    }
    if (status.isNotBlank()) Text(status, fontSize = MT.Meta, color = mines().ink2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp))
    AcctHeader(if (loading && items.isEmpty()) "SkillHub 插件 · 正在加载…" else "SkillHub 插件 · ${items.size} 个手机能用", trailing = "skillhub.cn")
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
        items.forEachIndexed { i, p ->
            val inst = isInst(p)
            AcctRow({ HubLogo(p.avatar, p.name) }, p.name,
                zhOf(p).ifBlank { "（作者没写简介）" } + "\n★ ${starsText(p.stars)}" + (p.categoryLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                when { inst -> com.openminis.app.plus.SessionStatus.Pill.OK; !p.verified && !p.kind.canTry -> com.openminis.app.plus.SessionStatus.Pill.UNCHECKED; else -> null },
                // [Mines-38.9.7] 「需手动」 → what it is (电脑版 / 资料库 / 非 DSH) or 试装 (DSH plugin SkillHub has not verified yet)
                pillLabel = when { inst -> "已安装"; !p.verified && !p.kind.canTry -> p.kind.pill; else -> null },
                chevron = Chevron.NONE, subtitleLines = 3, onClick = { intro = p },
                description = "${p.name}，${p.description.take(60)}，" + (if (inst) "已安装" else p.kind.line),
                extra = { if (p.kind.canTry) HubAction(if (inst) "更新" else if (p.verified) "安装" else "试装", enabled = !task.state.running) { install(p) } })
            if (i < items.lastIndex) AcctDivider()
        }
        if (items.size < total && items.isNotEmpty()) TextButton(onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { H.plugins(ctx, query, cat, sort, page + 1, verifiedOnly) } }
                r.onSuccess { page++; items = (items + it.items).distinctBy { x -> x.fullName }; desktopItems = (desktopItems + it.desktop).distinctBy { x -> x.fullName } }.onFailure { status = "下一页没读到：${it.message}" }
            }
        }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("再显示 ${H.PAGE} 个", fontSize = MT.Button) }
    }
    // [Mines-38.9.8] computer-only plugins (and prompt / doc packs that can become a phone skill), never installable here
    if (desktopItems.isNotEmpty()) {
        if (phoneOnly) Text("另有 ${desktopItems.size} 个只能电脑用（关掉「手机能用」查看）", fontSize = MT.Meta, color = mines().ink2,
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 8.dp))
        else {
            AcctHeader("只能电脑用 · ${desktopItems.size} 个")
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                desktopItems.forEachIndexed { i, p ->
                    AcctRow({ HubLogo(p.avatar, p.name) }, p.name,
                        zhOf(p).ifBlank { "（作者没写简介）" } + "\n" + p.kind.line,
                        com.openminis.app.plus.SessionStatus.Pill.UNCHECKED,
                        pillLabel = if (p.kind.convertible && !p.kind.desktopOnly) "可转技能" else p.kind.pill,
                        chevron = Chevron.NONE, subtitleLines = 3, onClick = { intro = p },
                        description = "${p.name}，${zhOf(p).take(60)}，${p.kind.line}")
                    if (i < desktopItems.lastIndex) AcctDivider()
                }
            }
        }
    }
    // the curated MCP servers stay as they were
    LegacyPluginMarket(onOpenTerminal, mcpOnly = true)
}

@Composable
private fun SkillsTab() {
    var src by remember { mutableIntStateOf(0) }
    HubChips(listOf<Pair<String?, String>>("0" to "SkillHub", "1" to "Hermes Hub", "2" to "GitHub"), src.toString()) { src = it?.toIntOrNull() ?: 0 }
    when (src) { 0 -> SkillHubSkills(onFallback = { src = 1 }); 1 -> HermesSkillMarket(); else -> SkillMarket() }
}

@Composable
private fun SkillHubSkills(onFallback: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val H = com.openminis.app.plus.SkillHubCn
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<com.openminis.app.plus.SkillHubCn.Skill>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, cat) {
        kotlinx.coroutines.delay(if (query.isBlank()) 0 else 350)
        loading = true; page = 1
        withContext(Dispatchers.IO) { runCatching { H.skills(ctx, query, cat, 1) } }
            .onSuccess { items = it.items; total = it.total; status = if (it.fromCache) "SkillHub 暂时连不上，先显示缓存的目录" else if (it.items.isEmpty()) "没找到" else "" }
            .onFailure { status = "SkillHub 连不上（${it.message ?: it.javaClass.simpleName}）" }
        loading = false
    }
    AcctSearch(query, "搜索 SkillHub 技能（中英文都行）") { query = it }
    HubChips(listOf<Pair<String?, String>>(null to "全部") + H.SKILL_CATEGORIES.map { it.key to it.value }, cat) { cat = it }
    if (status.isNotBlank()) Text(status, fontSize = MT.Meta, color = mines().ink2, modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp))
    if (!loading && items.isEmpty() && status.startsWith("SkillHub 连不上")) {
        TextButton(onClick = onFallback, modifier = Modifier.padding(horizontal = 24.dp)) { Text("改用 Hermes Skills Hub", fontSize = MT.Button) }
        return
    }
    AcctHeader(if (loading && items.isEmpty()) "SkillHub 技能 · 正在加载…" else "SkillHub 技能 · 共 $total 个", trailing = "skillhub.cn")
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
        items.forEachIndexed { i, s ->
            // [Mines-38.9.5 i] state per publisher + slug: another publisher's same-name skill is 「同名已装」, not 已安装
            val rk = com.openminis.app.plus.SkillInstallKey.rowKey(s.namespace, s.slug)
            val st = remember(tick, rk) { H.skillState(ctx, s) }
            val inst: String? = when (st) { com.openminis.app.plus.SkillInstallKey.State.NONE -> null; com.openminis.app.plus.SkillInstallKey.State.LOCAL -> "local"
                com.openminis.app.plus.SkillInstallKey.State.OTHER -> "other"; else -> "mine" }
            val open = expanded == rk
            AcctRow({ HubLogo(s.icon, s.name) }, s.name,
                s.description.ifBlank { "（没有简介）" } + "\n" + listOfNotNull(s.categoryLabel.takeIf { it.isNotBlank() }, "下载 ${starsText(s.downloads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}",
                    s.publisher.takeIf { it.isNotBlank() }?.let { if (s.publisherVerified) "$it ✓" else it }, s.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString(" · "),
                when (inst) { null -> null; "local", "other" -> com.openminis.app.plus.SessionStatus.Pill.WARN; else -> com.openminis.app.plus.SessionStatus.Pill.OK },
                pillLabel = when (inst) { null -> null; "local" -> "同名本地"; "other" -> "同名已装"; else -> "已安装" },
                chevron = Chevron.NONE, subtitleLines = if (open) 12 else 3, onClick = { expanded = if (open) null else rk },
                onLongClick = if (inst == "mine") ({ scope.launch { status = withContext(Dispatchers.IO) { H.uninstallSkill(ctx, s.slug) }; tick++ } }) else null,
                description = "${s.name}，${s.description.take(60)}，" + (when (inst) { "mine" -> "已安装，长按卸载"; "other" -> "已装了另一位作者的同名技能"; "local" -> "有同名本地技能"; else -> "可安装" }),
                extra = {
                    if (inst != "local") HubAction(if (busy == rk) "…" else when (inst) { null -> "安装"; "other" -> "替换"; else -> "更新" }, enabled = busy == null) {
                        busy = rk
                        scope.launch {
                            status = withContext(Dispatchers.IO) { runCatching { H.installSkill(ctx, s, replace = inst == "other") }.getOrElse { "安装失败：${it.message}" } }
                            busy = null; tick++
                        }
                    }
                })
            if (open && inst == "mine") TextButton(onClick = { scope.launch { status = withContext(Dispatchers.IO) { H.uninstallSkill(ctx, s.slug) }; tick++ } },
                modifier = Modifier.padding(start = 60.dp)) { Text("卸载", color = Color(0xFFB3261E), fontSize = MT.Button) }
            if (i < items.lastIndex) AcctDivider()
        }
        if (items.size < total && items.isNotEmpty()) TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { H.skills(ctx, query, cat, page + 1) } }
                    .onSuccess { page++; items = (items + it.items).distinctBy { x -> com.openminis.app.plus.SkillInstallKey.rowKey(x.namespace, x.slug) } }.onFailure { status = "下一页没读到：${it.message}" }
            }
        }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("再显示 ${H.PAGE} 个", fontSize = MT.Button) }
    }
}

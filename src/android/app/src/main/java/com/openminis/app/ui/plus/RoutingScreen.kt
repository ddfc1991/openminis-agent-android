package com.openminis.app.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.plus.SmartRouter
import com.openminis.app.plus.SplitProxy
import kotlinx.coroutines.launch

@Composable
internal fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    PlusRow(null, title, sub, chevron = false, onClick = { onChange(!checked) },
        trailing = { Switch(checked, onCheckedChange = onChange) }) // [Mines-design-33]

/** 模型路由与分流: smart model routing + rule-based split proxy. */
@Composable
fun RoutingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val routerOn by SmartRouter.enabled.collectAsState()
    val stats by SmartRouter.stats.collectAsState()
    val testing by SmartRouter.testing.collectAsState()
    val repo = (ctx.applicationContext as? MinisApp)?.providerRepositoryOrNull
    val config = repo?.config?.collectAsState()?.value
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { SmartRouter.refreshIfStale(ctx) }

    PlusPage("模型路由与分流", onBack, subtitle = "自动挑最快、能用的模型和网络线路") {
        PlusSection("智能路由") {
            PlusRow(Icons.Outlined.Bolt, "智能路由", "先用最快的，出错自动换下一个", chevron = false,
                onClick = { SmartRouter.setEnabled(ctx, !routerOn) }, trailing = { Switch(routerOn, onCheckedChange = { SmartRouter.setEnabled(ctx, it) }) })
            PlusDivider()
            PlusRow(Icons.Outlined.Speed, if (testing) "测速中…" else "立即测速", "给每个供应商测一次快慢", onClick = { if (!testing) scope.launch { SmartRouter.testAll(ctx) } })
            // [Mines-38.7] 「同模型自动成组」 removed (user 2026-10-15 「那个同模型的不用了」: a model with only a suffix
            // such as -free / :thinking / a date became its own group, so it made a pile of groups)
        }
        // [Mines-degen-34] 防乱码: models marked 不稳定 + thinking length cap
        com.openminis.app.plus.ModelHealth.load(ctx)
        val unstable by com.openminis.app.plus.ModelHealth.marks.collectAsState()
        var thinkCap by remember { mutableStateOf(com.openminis.app.agent.RunLoopGuard.thinkingCap(ctx)) }
        PlusSection("防乱码", footer = "输出变成乱码或思考没完没了时，会自动打断、降低思考强度重试一次；再坏就换下一个模型，并把它标成「不稳定」。") {
            PlusRow(Icons.Outlined.Memory, "每轮思考上限", "超过就打断，换个方式重来", value = "${thinkCap / 1000}K 字", chevron = false)
            androidx.compose.material3.Slider(thinkCap.toFloat(), {
                thinkCap = ((it / 2000f).toInt() * 2000).coerceIn(4_000, 200_000)
                com.openminis.app.agent.RunLoopGuard.setThinkingCap(ctx, thinkCap)
            }, valueRange = 4_000f..100_000f, modifier = Modifier.padding(horizontal = 16.dp))
            if (unstable.isEmpty()) {
                PlusDivider()
                PlusRow(Icons.Outlined.Shield, "没有不稳定的模型", null, chevron = false)
            }
            unstable.forEach { mk ->
                PlusDivider()
                PlusRow(Icons.Outlined.Info, mk.name + " · 不稳定", mk.why, tint = MinesDesign.Warning, chevron = false,
                    trailing = { TextButton(onClick = { com.openminis.app.plus.ModelHealth.clear(ctx, mk.id) }) { Text("清除", fontSize = MT.Body) } })
            }
        }
        val insts = config?.instances?.filter { it.isEnabled }.orEmpty()
        PlusSection("供应商") {
            if (insts.isEmpty()) PlusRow(Icons.Outlined.Info, "还没有供应商", chevron = false)
            insts.sortedBy { stats[it.id]?.let { s -> if (s.ok) s.latencyMs else 100_000 + s.latencyMs } ?: 50_000 }
                .forEachIndexed { i, inst ->
                    val s = stats[inst.id]
                    PlusRow(Icons.Outlined.Memory, inst.label.ifBlank { inst.providerType.displayName },
                        when { s == null -> "还没测"; s.ok -> "响应 ${s.latencyMs} 毫秒"; s.code == 401 || s.code == 403 -> "密钥不对或没权限"; s.code == 429 -> "请求太多，被限流了"; s.code > 0 -> "服务出错，稍后再试"; else -> "网络连不上" },
                        tint = if (s?.ok == true) Color(0xFF34C759) else if (s == null) minesAccent() else Color(0xFFFF6B5E), chevron = false,
                        trailing = { StatusPill(when { s == null -> "未测"; s.ok -> "能用"; else -> "连不上" }, s?.ok) })
                    if (i < insts.lastIndex) PlusDivider()
                }
        }
        SplitProxySection()
    }
}

@Composable
private fun SplitProxySection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SplitProxy.settings.collectAsState()
    val running by SplitProxy.running.collectAsState()
    val recent by SplitProxy.recent.collectAsState()
    var host by remember(s.upstreamHost) { mutableStateOf(s.upstreamHost) }
    var port by remember(s.upstreamPort) { mutableStateOf(if (s.upstreamPort > 0) s.upstreamPort.toString() else "") }
    // [Mines-38.9.6 i13] proxy user / password (password in PlusVault, masked, never logged)
    var puser by remember(s.upstreamUser) { mutableStateOf(s.upstreamUser) }
    var ppass by remember(s.upstreamPass) { mutableStateOf(s.upstreamPass) }
    var showPass by remember { mutableStateOf(false) }
    var newRule by remember { mutableStateOf("") }
    var newMode by remember { mutableStateOf(SplitProxy.Mode.PROXY) }
    fun applyToSandbox() = scope.launch { runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() } }

    PlusSection("分流", footer = when {
        s.enabled && !running -> "分流没能启动，重开一下 Mines 试试"
        s.enabled && !s.hasUpstream -> "还没有代理：要走代理的网站现在会直连"
        else -> null
    }) {
        SwitchRow("开启分流", if (s.enabled && running) "国内直连，境外按规则走代理" else "按网站决定走代理还是直连", s.enabled) { on ->
            SplitProxy.update(ctx) { it.copy(enabled = on) }; applyToSandbox()
        }
        PlusDivider()
        SwitchRow("用于沙箱（命令行、插件）", null, s.forSandbox) { on -> SplitProxy.update(ctx) { it.copy(forSandbox = on) }; applyToSandbox() }
        PlusDivider()
        SwitchRow("用于 App（模型请求等）", null, s.forApp) { on -> SplitProxy.update(ctx) { it.copy(forApp = on) } }
    }
    PlusSection("自己填代理（可选）") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.upstreamType == "http", onClick = { SplitProxy.update(ctx) { it.copy(upstreamType = "http") } }, label = { Text("HTTP") })
                FilterChip(selected = s.upstreamType == "socks5", onClick = { SplitProxy.update(ctx) { it.copy(upstreamType = "socks5") } }, label = { Text("SOCKS5") })
            }
            OutlinedTextField(host, { v ->
                // pasting "http://user:pass@host:port" fills every field
                val parsed = if (v.contains("@") || v.contains("://")) com.openminis.app.plus.SplitProxyCore.parse(v) else null
                if (parsed != null) {
                    host = parsed.host; port = parsed.port.toString()
                    if (parsed.user.isNotEmpty()) { puser = parsed.user; ppass = parsed.pass }
                    if (parsed.type != s.upstreamType) SplitProxy.update(ctx) { it.copy(upstreamType = parsed.type) }
                } else host = v.trim()
            }, label = { Text("地址（IP 或域名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(puser, { puser = it.trim() }, label = { Text("用户名（没有就空着）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ppass, { ppass = it }, label = { Text("密码") }, singleLine = true,
                visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { showPass = !showPass }) {
                        androidx.compose.material3.Icon(
                            if (showPass) androidx.compose.material.icons.Icons.Outlined.VisibilityOff else androidx.compose.material.icons.Icons.Outlined.Visibility,
                            contentDescription = if (showPass) "隐藏密码" else "显示密码")
                    }
                },
                enabled = puser.isNotEmpty() || ppass.isNotEmpty(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("保存", Modifier.weight(1f)) {
                    SplitProxy.update(ctx) { it.copy(upstreamHost = host, upstreamPort = port.toIntOrNull() ?: 0, upstreamUser = puser, upstreamPass = if (puser.isEmpty()) "" else ppass) }
                    com.openminis.app.plus.PlusVaultLess.setAutoPrimary(ctx, false); applyToSandbox()
                }
                PillButton("清空", Modifier.weight(1f), primary = false) {
                    host = ""; port = ""; puser = ""; ppass = ""
                    SplitProxy.update(ctx) { it.copy(upstreamHost = "", upstreamPort = 0, upstreamUser = "", upstreamPass = "") }; applyToSandbox()
                }
            }
        }
    }
    ClashSection()
    ProxyAutoSection()
    InfoLine("自动：国内直连，境外先试直连、不通再走代理；代理坏了时 Google 显示「代理不可用」，其它网站直连")
    PlusSection("没写规则的网站") {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitProxy.Mode.entries.forEach { m ->
                FilterChip(selected = s.defaultMode == m, onClick = { SplitProxy.update(ctx) { it.copy(defaultMode = m) } }, label = { Text(m.label) })
            }
        }
    }
    PlusSection("一键规则") {
        SplitProxy.presets.forEachIndexed { i, p ->
            ActionRow(Icons.Outlined.Add, p.name) { SplitProxy.addPreset(ctx, p) }
            if (i < SplitProxy.presets.lastIndex) PlusDivider()
        }
    }
    PlusSection(if (s.rules.isEmpty()) "规则" else "规则 · ${s.rules.size}", footer = "写域名即可，含子域名；点模式可以切换") {
        s.rules.sortedBy { it.pattern }.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.pattern, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val next = SplitProxy.Mode.entries[(r.mode.ordinal + 1) % SplitProxy.Mode.entries.size]
                    SplitProxy.update(ctx) { st -> st.copy(rules = st.rules.map { if (it.pattern == r.pattern) it.copy(mode = next) else it }) }
                }) { Text(r.mode.label) }
                TextButton(onClick = { SplitProxy.update(ctx) { st -> st.copy(rules = st.rules.filter { it.pattern != r.pattern }) } }) {
                    Text("删", color = Color(0xFFFF3B30))
                }
            }
        }
        if (s.rules.isNotEmpty()) PlusDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newRule, { newRule = it.trim().lowercase() }, label = { Text("新规则：域名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SplitProxy.Mode.entries.forEach { m -> FilterChip(selected = newMode == m, onClick = { newMode = m }, label = { Text(m.label) }) }
                Spacer(Modifier.weight(1f))
                PillButton("添加", enabled = newRule.isNotBlank() && !newRule.contains(' ')) {
                    val r = SplitProxy.Rule(newRule, newMode)
                    SplitProxy.update(ctx) { st -> st.copy(rules = st.rules.filter { it.pattern != r.pattern } + r) }
                    newRule = ""
                }
            }
        }
    }
    if (recent.isNotEmpty()) PlusSection("最近走的线路") {
        recent.take(12).forEachIndexed { i, h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(h.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(h.route, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (i < recent.take(12).lastIndex) PlusDivider()
        }
    }
}

/**
 * [Mines-proxy-auto] One-tap fill from proxies already running on the phone
 * (system proxy, Clash / v2rayNG / sing-box / NekoBox / Hiddify ports), backup
 * proxies with health checks every minute and automatic failover.
 */
@Composable
private fun ProxyAutoSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SplitProxy.settings.collectAsState()
    val health by SplitProxy.health.collectAsState()
    val current by SplitProxy.current.collectAsState()
    var found by remember { mutableStateOf<List<SplitProxy.Found>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun recheck() = scope.launch { busy = true; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SplitProxy.checkAll() }; busy = false }
    fun use(u: SplitProxy.Upstream) {
        SplitProxy.update(ctx) { st ->
            if (st.primary == null) st.copy(upstreamType = u.type, upstreamHost = u.host, upstreamPort = u.port)
            else if (st.all.any { it.key == u.key }) st
            else st.copy(backups = st.backups + u)
        }
        if (s.primary == null) com.openminis.app.plus.PlusVaultLess.setAutoPrimary(ctx, true)
        recheck()
    }
    PlusSection("找代理") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自动找手机上已经在跑的代理软件（Clash、v2rayNG 等）", fontSize = MT.Body, color = minesMuted())
            PillButton(if (busy) "正在检测…" else "一键检测本机代理", Modifier.fillMaxWidth(), enabled = !busy) {
                scope.launch {
                    busy = true
                    found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SplitProxy.detect(ctx) }
                    busy = false
                }
            }
            found?.let { list ->
                if (list.isEmpty()) Text("没找到正在运行的代理。先打开代理软件（并开启「允许局域网/本地代理」或 HTTP 代理端口），再点一次。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${f.source} · ${if (f.upstream.type == "socks5") "SOCKS5" else "HTTP"}", style = MaterialTheme.typography.bodyLarge)
                            Text("${f.upstream.host}:${f.upstream.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val added = s.all.any { it.key == f.upstream.key }
                        TextButton(enabled = !added, onClick = { use(f.upstream) }) { Text(if (added) "已填入" else "填入") }
                    }
                }
                if (list.size > 1 && list.any { f -> s.all.none { it.key == f.upstream.key } }) {
                    OutlinedButton(onClick = {
                        list.forEach { f ->
                            SplitProxy.update(ctx) { st ->
                                if (st.primary == null) st.copy(upstreamType = f.upstream.type, upstreamHost = f.upstream.host, upstreamPort = f.upstream.port)
                                else if (st.all.any { it.key == f.upstream.key }) st else st.copy(backups = st.backups + f.upstream)
                            }
                        }
                        recheck()
                    }, modifier = Modifier.fillMaxWidth()) { Text("全部填入（第一个当主代理）") }
                }
                if (s.rules.none { it.pattern == ".cn" }) {
                    TextButton(onClick = { SplitProxy.presets.firstOrNull { it.mode == SplitProxy.Mode.DIRECT }?.let { SplitProxy.addPreset(ctx, it) } }) {
                        Text("同时加上「国内网站直连」规则")
                    }
                }
            }
        }
    }
    // [Mines-design-33] working proxy first; plain reasons; dead auto-detected ones are dropped by SplitProxy
    if (s.all.isNotEmpty()) PlusSection("代理") {
        SwitchRow("坏了自动换", "每分钟检查一次，能用的排前面", s.autoSwitch) { on -> SplitProxy.update(ctx) { it.copy(autoSwitch = on) }; recheck() }
        val primaryKey = s.all.first().key
        s.all.sortedWith(compareBy({ if (it.key == current) 0 else 1 }, { if (health[it.key]?.ok == true) 0 else 1 })).forEach { u ->
            PlusDivider()
            val h = health[u.key]
            val name = u.label.ifBlank { if (u.key == primaryKey) "我的代理" else "备用代理" }
            PlusRow(Icons.Outlined.Shield, name + if (u.key == current) " · 正在用" else "",
                when { h == null -> "还没检查"; h.ok -> "响应 ${h.ms} 毫秒"; else -> plainProxyReason(h.error) },
                tint = if (h?.ok == true) Color(0xFF34C759) else if (h == null) minesAccent() else Color(0xFFFF6B5E), chevron = false,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(when { h == null -> "未查"; h.ok -> "能用"; else -> "连不上" }, h?.ok)
                        if (u.key != primaryKey) TextButton(onClick = { SplitProxy.update(ctx) { st -> st.copy(backups = st.backups.filter { it.key != u.key }) } }) {
                            Text("移除", color = minesMuted())
                        }
                    }
                })
        }
        PlusDivider()
        ActionRow(Icons.Outlined.Refresh, if (busy) "正在检查…" else "立即检查") { recheck() }
    }
}


/** [Mines-design-33] Raw socket errors → one plain reason. */
internal fun plainProxyReason(e: String?): String {
    val t = e.orEmpty().lowercase()
    // [Mines-38.9.10 i28] the real check already says what happened in Chinese (SplitProxyCore.probeReason): show that line
    if (e != null && e.isNotEmpty() && e[0].code >= 0x2E80) return e.substringBefore("（").take(60)
    return when {
        t.contains("refused") || t.contains("failed to connect") -> "代理软件没在运行"
        t.contains("timeout") || t.contains("timed out") -> "太慢，超时了"
        t.contains("407") || t.contains("auth") -> "需要账号密码"
        t.contains("reset") || t.contains("closed") -> "连上后被断开"
        t.contains("unknown host") || t.contains("unable to resolve") -> "地址不对"
        else -> "暂时连不上"
    }
}


/**
 * [Mines-38.9.6 i14] 内置 Clash（mihomo）: switch, status, import a subscription link or a Clash YAML (kept encrypted).
 * With the core on, the split proxy's upstream is the core (127.0.0.1:7898); it manages nodes itself.
 */
@Composable
private fun ClashSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st by com.openminis.app.plus.ClashCore.state.collectAsState()
    var on by remember { mutableStateOf(com.openminis.app.plus.ClashCore.enabled(ctx)) }
    var input by remember { mutableStateOf("") }
    // [Mines-38.9.8] the result line lives in ClashCore (survives leaving the page; shows 「正在下载订阅…」 while it runs)
    val note by com.openminis.app.plus.ClashCore.note.collectAsState()
    val bundled = remember { com.openminis.app.plus.ClashCore.bundled(ctx) }
    // [Mines-38.9.8] the subscription the user already has: a clash://install-config link / a share from the Clash app, or a
    // link on the clipboard (no root → Mines cannot read the Clash app's own files)
    var offer by remember { mutableStateOf(com.openminis.app.plus.ClashCore.pendingLink?.let { it to com.openminis.app.plus.ClashCore.pendingFrom.ifBlank { "Clash App" } }) }
    androidx.compose.runtime.LaunchedEffect(on) {
        if (offer == null && on) runCatching {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            com.openminis.app.plus.ClashSubCore.findLink(t)?.let { offer = it to "剪贴板" }
        }
    }
    var phone by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(on) {
        if (on) phone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.openminis.app.plus.ClashCore.phoneClash() }.getOrDefault("") }
    }
    PlusSection("内置 Clash 内核") {
        SwitchRow("开启 Clash（mihomo）", if (!bundled) "这个版本没有带内核" else if (on) st else "开启后，走代理的流量都交给 Clash 选节点", on) { v ->
            on = v; scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.ClashCore.setEnabled(ctx, v) }
        }
        if (on) {
            PlusDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("节点来源：" + remember(note) { com.openminis.app.plus.ClashCore.importedKind(ctx) }, fontSize = MT.Sub, color = mines().ink2)
                offer?.let { (link, from) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${from}里有订阅链接" }) {
                        Text("${from}里有订阅链接", fontSize = MT.Sub, color = mines().ink, modifier = Modifier.weight(1f))
                        PillButton("导入这个") {
                            offer = null; com.openminis.app.plus.ClashCore.pendingLink = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.ClashCore.import(ctx, link) }
                        }
                    }
                }
                OutlinedTextField(input, { input = it }, label = { Text("订阅链接或 Clash 配置（YAML）") }, maxLines = 4,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("导入", Modifier.weight(1f)) {
                        val t = input; scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.ClashCore.import(ctx, t) }; input = ""
                    }
                    PillButton("用自己填代理", Modifier.weight(1f), primary = false) {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.ClashCore.import(ctx, "") }
                    }
                }
                if (note.isNotBlank()) Text(note, fontSize = MT.Sub, color = mines().ink2)
                // [Mines-38.9.10 i28] it restarts itself (crash / kill / reboot): the last automatic start, one line
                val auto = remember(st) { com.openminis.app.plus.ClashCore.lastRestart(ctx) }
                if (auto.isNotBlank()) Text("上次自动启动：$auto", fontSize = MT.Caption, color = mines().ink2)
                if (phone.isNotBlank()) Text(phone, fontSize = MT.Caption, color = mines().ink2)
                Text(com.openminis.app.plus.ClashSubCore.NO_ROOT, fontSize = MT.Caption, color = mines().ink3)
            }
        }
    }
}

package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.AutoLogin
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.plus.AutoLoginCore
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.WorkPoolCore
import kotlinx.coroutines.delay

/** [Mines-38.9.12] routes of the pages added in this version */
object Routes3912 {
    const val APPS = "plus_apps_hub"
    const val AUTOLOGIN = "plus_auto_login"
    const val WORKPOOL = "plus_work_pool"
    const val REGISTER = "plus_register_ledger"   // [Mines-38.9.12 b] 注册台账 (ws2 ③)
    const val CONNECT = "plus_quick_connect"      // [Mines-38.9.12 b] 服务器快速连接 (ws2 ④)
}

private fun ic(n: String) = com.openminis.app.ui.brand.MinesIcons.named(n)!!

// ───────────────────────────── 应用 (design v3 ⑥) ─────────────────────────────

private data class HubTile(val label: String, val icon: String, val color: Color, val route: String)

@Composable
fun AppsHubScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val tiles = listOf(
        HubTile("知识库", "book-open", Color(0xFF35607F), PlusRoutes.KNOWLEDGE),   // [Mines-38.9.14 chat_home] 知识库 lives in 应用 (first)
        HubTile("浏览器", "globe", Color(0xFF4F7FB5), "__browser"),
        HubTile("账号台账", "key-round", Color(0xFFC0633F), PlusRoutes.KEYCHAIN),
        HubTile("服务器", "server", Color(0xFF3F8A5C), PlusRoutes.WORK),
        HubTile("市场", "store", Color(0xFF7E62B0), PlusRoutes.MARKET),
        HubTile("邮箱", "mail", Color(0xFFD0503F), PlusRoutes.CODESRC),
        HubTile("工作区", "layout-dashboard", Color(0xFF3E9C93), Routes3912.WORKPOOL),
        HubTile("技能", "sparkles", Color(0xFFC9932F), PlusRoutes.PLUGINS),
        HubTile("自动登录", "fingerprint", Color(0xFF6B5E58), Routes3912.AUTOLOGIN),
        HubTile("注册台账", "user", Color(0xFFB0574F), Routes3912.REGISTER),
        HubTile("快速连接", "terminal", Color(0xFF35607F), Routes3912.CONNECT),
        HubTile("伪人核心", "shield-check", Color(0xFF5E6B7E), PersonaRoutes.PAGE),   // [Mines-38.9.15 伪人核心]
    )
    PlusPage("应用", onBack) {
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { t ->
                        Column(Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            if (t.route == "__browser") PlusNav.web("https://www.google.com/") else go(t.route)
                        }.padding(vertical = 4.dp).semantics { contentDescription = t.label }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(t.color), contentAlignment = Alignment.Center) {
                                MinesIcon(ic(t.icon), null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Text(t.label, fontSize = MT.Meta, color = m.ink, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
        PlusSection("已连接", action = "管理 ›", onAction = { go(PlusRoutes.CONNECTIONS) }) {
            PlusRow(ic("users"), "账号与服务", "AI 服务、登录与密钥", onClick = { go(PlusRoutes.CONNECTIONS) })
            WarmDivider()
            val on = st.servers.count { it.enabled && it.lastLatencyMs >= 0 }
            PlusRow(ic("server"), "电脑与服务器", "${st.servers.size} 台 · $on 台在线", onClick = { go(PlusRoutes.WORK) })
            WarmDivider()
            PlusRow(ic("stethoscope"), "诊断", "测试与上报", onClick = { go(PlusRoutes.REPORT) })
        }
        // [Mines-38.9.14.1] 「提醒 · 任务完成来电」 section removed with the 来电 feature
        if (com.openminis.app.plus.ChatHome.enabled()) Spacer(Modifier.size(84.dp))   // [Mines-38.9.14 chat_home] room for the dock
    }
}

// ───────────────────────────── 账号自动调用 (design ws2 ②) ─────────────────────────────

@Composable
fun AutoLoginScreen(onBack: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    remember { AutoLogin.init(ctx); 0 }
    val pol by AutoLogin.policy.collectAsState()
    val log by AutoLogin.log.collectAsState()
    val live by AutoLogin.live.collectAsState()
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    PlusPage("账号自动调用", onBack) {
        live?.let { lv ->
            PlusSection("最近一次 · ${lv.site}") {
                lv.steps.forEachIndexed { i, (t, ok) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (fg, bg) = toneColors(if (ok) Tone.SUCCESS else Tone.WARNING)
                        Box(Modifier.size(20.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                            if (ok) MinesIcon(ic("check"), null, tint = fg, modifier = Modifier.size(13.dp))
                            else Text("${i + 1}", fontSize = MT.Tiny, color = fg)
                        }
                        Text(t, fontSize = MT.Sub, color = m.ink, modifier = Modifier.padding(start = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        PlusSection("调用策略", footer = "任务需要登录时，Mines 按顺序处理：复用已保存的登录会话 → 账号的第三方登录（如 GitHub 通过 Google）→ 台账密码自动填入（不显示明文）→ 仍不行才弹安全登录框。两步验证用邮件验证码或台账里的 TOTP。") {
            SwitchRow("任务需要登录时自动使用台账", "不再逐次询问", pol.autoUse) { AutoLogin.setAutoUse(ctx, it) }
            WarmDivider(16.dp)
            SwitchRow("验证码自动读取", "已关联邮箱与通知", pol.codeAutoRead) { AutoLogin.setCodeAutoRead(ctx, it) }
            WarmDivider(16.dp)
            PlusRow(null, "支付与转账", "始终询问", chevron = false, trailing = { StatusPill("询问", null) })
            WarmDivider(16.dp)
            SwitchRow("交易所账号只读调用", "只查看，不下单、不划转、不提现", pol.exchangeReadOnly) { AutoLogin.setExchangeReadOnly(ctx, it) }
        }
        PlusSection("最近调用") {
            if (log.isEmpty()) Text("还没有自动登录记录", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(16.dp))
            log.take(12).forEachIndexed { i, l ->
                if (i > 0) WarmDivider(16.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.format(java.util.Date(l.at)), fontSize = MT.Meta, color = m.ink2, fontFamily = FontFamily.Monospace)
                    Text(l.site + " · " + l.how + " · " + (if (l.auto) "自动" else "询问"), fontSize = MT.Sub, color = m.ink,
                        modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (l.ok) { true -> StatusPill("成功", true); false -> StatusPill("未成功", false); null -> {} }
                }
            }
        }
    }
}

// ───────────────────────────── 协作工作区 (design ws.jpg Ⓐ) ─────────────────────────────

object WorkPoolLive {
    val nodes = mutableStateMapOf<String, WorkPoolCore.Node>()
    @Volatile var reads: Int = 0
}

private const val POOL_PROBE = """
hostname; nproc; cat /proc/loadavg; uptime -p 2>/dev/null || uptime
grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
df -kP / | tail -1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
sleep 1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
"""

@Composable
fun WorkPoolScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val prefs = remember { ctx.applicationContext.getSharedPreferences("mines_work_pool", android.content.Context.MODE_PRIVATE) }
    var byRam by remember { mutableStateOf(prefs.getBoolean("by_ram", true)) }
    var migrate by remember { mutableStateOf(prefs.getBoolean("migrate", true)) }
    var burst by remember { mutableStateOf(prefs.getBoolean("burst", false)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val servers = st.servers.filter { it.enabled }
    // live CPU / RAM per node over the app's own server link (WS relay when set, keepalive + reconnect), every 6 s while shown
    servers.forEach { s -> androidx.compose.runtime.key(s.id) {
        LaunchedEffect(owner, s.id, s.relay, s.authorized) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val role = WorkPoolCore.roleOf(s.name, s.host)
                if (role == WorkPoolCore.Role.EXCLUDED) {
                    WorkPoolLive.nodes[s.id] = WorkPoolCore.Node(s.id, s.name.ifBlank { s.host }, s.host, role, true); return@repeatOnLifecycle
                }
                var fails = 0
                while (true) {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.openminis.app.plus.VpsSsh.run(ctx, s, POOL_PROBE, 20_000, 2) }.getOrNull()
                    }
                    val p = r?.first?.takeIf { it.exit == 0 }?.let { parseStats(it.output) }
                    WorkPoolLive.reads++
                    WorkPoolLive.nodes[s.id] = if (p != null) WorkPoolCore.Node(s.id, s.name.ifBlank { s.host }, s.host, role, true, p.cores, p.cpuPct,
                        p.memUsedMb, p.memTotalMb, System.currentTimeMillis()).also { fails = 0 }
                    else (WorkPoolLive.nodes[s.id]?.copy(online = false) ?: WorkPoolCore.Node(s.id, s.name.ifBlank { s.host }, s.host, role, false)).also { fails++ }
                    delay(if (fails == 0) 6000L else (6000L shl (fails - 1).coerceAtMost(2)).coerceAtMost(30_000L))
                }
            }
        }
    } }
    val nodes = servers.map { s -> WorkPoolLive.nodes[s.id] ?: WorkPoolCore.Node(s.id, s.name.ifBlank { s.host }, s.host, WorkPoolCore.roleOf(s.name, s.host), s.lastLatencyMs >= 0) }
    val members = nodes.filter { it.role != WorkPoolCore.Role.EXCLUDED }
    val pool = WorkPoolCore.pool(nodes)
    PlusPage("协作工作区", onBack, subtitle = "${pool.members} 台节点合并调度") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("算力池", "${pool.cores}", "核", "已用 ${pool.usedPct}%", Modifier.weight(1f))
            StatTile("内存池", WorkPoolCore.mem(pool.totalMb).removeSuffix("G").removeSuffix("M"), if (pool.totalMb >= 1024) "GB" else "MB",
                "可调度 " + WorkPoolCore.mem(pool.schedulableMb), Modifier.weight(1f))
        }
        WarmGroupLabel("节点", "添加节点 ›") { go(PlusRoutes.WORK) }
        if (servers.isEmpty()) InfoLine("还没有服务器。在「电脑与服务器」里添加后会自动加入工作区。")
        nodes.sortedBy { if (it.role == WorkPoolCore.Role.EXCLUDED) 1 else 0 }.forEach { n -> NodeCard(n) }
        // the trading VM is never scheduled — shown greyed so it is clear why it is not in the pool (it is not a Mines server)
        if (nodes.none { it.role == WorkPoolCore.Role.EXCLUDED }) NodeCard(WorkPoolCore.Node("hatch", "青怡 Hatch VM", "", WorkPoolCore.Role.EXCLUDED, true))
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            PlusRow(ic("plus"), "云端扩容", "内存不足时按需启动 · 空闲 15 分钟自动关机 · 不使用付费资源", chevron = false,
                trailing = { Switch(burst, onCheckedChange = { burst = it; prefs.edit().putBoolean("burst", it).apply() }) })
        }
        PlusSection("调度策略") {
            SwitchRow("按空闲内存分派", "重任务优先浏览器节点", byRam) { byRam = it; prefs.edit().putBoolean("by_ram", it).apply() }
            WarmDivider(16.dp)
            SwitchRow("内存超 ${WorkPoolCore.MIGRATE_PCT}% 自动迁移", "迁移前保存进度", migrate) { migrate = it; prefs.edit().putBoolean("migrate", it).apply() }
            WarmDivider(16.dp)
            PlusRow(null, "每节点保留内存", value = "${WorkPoolCore.RESERVE_MB} MB", chevron = false)
            WarmDivider(16.dp)
            PlusRow(null, "网关节点保留内存", value = "${WorkPoolCore.GATEWAY_RESERVE_MB} MB", chevron = false)
        }
        val hot = WorkPoolCore.overloaded(members)
        if (hot.isNotEmpty()) InfoLine("内存紧张：" + hot.joinToString("、") { it.name } + "，新分片不会派到这些节点。")
    }
}

@Composable
private fun StatTile(label: String, big: String, unit: String, sub: String, modifier: Modifier) {
    val m = mines()
    MinesCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, fontSize = MT.Sub, color = m.ink2)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text(big, fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink)
                Text(" $unit", fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(sub, fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
        }
    }
}

@Composable
private fun NodeCard(n: WorkPoolCore.Node) {
    val m = mines()
    val excluded = n.role == WorkPoolCore.Role.EXCLUDED
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(ic(if (excluded) "lock" else "server"), size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(n.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = if (excluded) m.ink2 else m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (excluded) "交易专用 · 不参与调度" else (if (n.cores > 0) "${n.cores}C · ${WorkPoolCore.mem(n.memTotalMb)} · " else "") + n.role.zh,
                        fontSize = MT.Sub, color = m.ink2, maxLines = 1)
                }
                val s = WorkPoolCore.status(n)
                StatusPill(s, when (s) { "在线" -> true; "离线", "内存紧张" -> false; else -> null })
            }
            if (!excluded && n.memTotalMb > 0) {
                Meter("CPU", n.cpuPct / 100f, "${n.cpuPct.toInt()}%")
                Meter("内存", n.memUsedMb.toFloat() / n.memTotalMb, WorkPoolCore.mem(n.memUsedMb).removeSuffix("G") + "/" + WorkPoolCore.mem(n.memTotalMb))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (n.role.tags + if (n.protected) listOf("保留 ${n.reserveMb} MB") else emptyList()).take(3).forEach { t ->
                        Text(t, fontSize = MT.Meta, color = m.primaryText, modifier = Modifier.clip(RoundedCornerShape(50)).background(m.container).padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
            } else if (!excluded) Text(if (n.online) "正在读取…" else "连不上，正在自动重连…", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Meter(label: String, f: Float, value: String) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = MT.Meta, color = m.ink2, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(m.hairline)) {
            val c = when { f > 0.88f -> Color(0xFFD0503F); f > 0.7f -> Color(0xFFD9962F); else -> Color(0xFF4E9A63) }
            Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
        Text(value, fontSize = MT.Meta, color = m.ink2, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 10.dp).width(78.dp), maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

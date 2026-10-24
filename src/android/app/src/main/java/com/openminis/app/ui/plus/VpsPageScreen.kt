package com.openminis.app.ui.plus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.PlusVps
import com.openminis.app.plus.VpsPlan
import com.openminis.app.plus.VpsPlanCore
import com.openminis.app.plus.VpsPlanCore.Machine
import com.openminis.app.plus.WorkspaceCore
import com.openminis.app.plus.WorkspaceStore
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import com.openminis.app.ui.glass.DropdownMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * [Mines-38.9.13 vps_page_v2] VPS / 电脑 (approved mockups vps-01…03, 2026-10-24): one card per machine (role, live status,
 * CPU / 内存 from the app's own SSH link, what it takes / never takes, 代理订阅, 暂停), then the routing rules — each rule's
 * machine is an inline dropdown (default = Hark's plan, changed rows say 已改 · 默认是 X), 恢复默认, + 加一条规则, the failover
 * order (long-press drag, or tap to move earlier), the stop line ±, and the merged subscription members. 梦云 is a normal
 * member now (模型网关 + 轻浏览器 + 香港代理出口). The old 工作位置 screen stays reachable for adding / authorising servers.
 */
private const val PROBE = """
hostname; nproc; cat /proc/loadavg; uptime -p 2>/dev/null || uptime
grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
df -kP / | tail -1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
sleep 1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
"""

private val G = Color(0xFF2E9A57); private val A = Color(0xFFD08A2E); private val R = Color(0xFFC8372D); private val N = Color(0xFF9A8E86)

/** Transparent frosted glass (mockup `.g`): white 34 % → 16 % gradient, 55 % white hairline — not the porcelain fill. */
@Composable
private fun Modifier.vpsGlass(radius: Int = 20): Modifier {
    val m = mines()
    val shape = RoundedCornerShape(radius.dp)
    val brush = if (m.dark) Brush.linearGradient(listOf(Color(0x5C1C1612), Color(0x3D1C1612)))
        else Brush.linearGradient(listOf(Color(0x57FFFFFF), Color(0x29FFFFFF)), start = Offset.Zero, end = Offset(600f, 900f))
    return this.clip(shape).background(brush).border(1.dp, if (m.dark) Color(0x33FFFFFF) else Color(0x8CFFFFFF), shape)
}

@Composable
private fun GlassBox(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).vpsGlass()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 11.dp), content = content)

@Composable
private fun SecHead(title: String, meta: String? = null, metaAccent: Boolean = false, onMeta: (() -> Unit)? = null) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = MT.Sub, fontWeight = FontWeight.Medium, color = m.ink2, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, fontSize = MT.Sub, fontWeight = FontWeight.Medium, color = if (metaAccent) m.primaryText else m.ink2,
            modifier = (if (onMeta != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onMeta) else Modifier).padding(4.dp))
    }
}

@Composable
private fun Chip(text: String, fg: Color, bg: Color) =
    Text(text, fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 8.dp, vertical = 1.dp))

@Composable
private fun SmallBtn(text: String, icon: String?, primary: Boolean, onClick: () -> Unit) {
    val m = mines()
    val fg = if (primary) Color.White else m.primaryText
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp)).background(if (primary) m.primary else m.primary.copy(alpha = 0.13f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MinesIcon(MinesIcons.named(icon)!!, null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = MT.Sub, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Bar(label: String, pct: Int, th: Int, modifier: Modifier) {
    val m = mines()
    val col = when { pct >= th -> Color(0xFFB3261E); pct >= 70 -> Color(0xFFC27A1A); else -> G }
    Column(modifier) {
        Row { Text(label, fontSize = MT.Meta, color = m.ink2, modifier = Modifier.weight(1f))
            Text("$pct%", fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = if (pct >= th) Color(0xFFB3261E) else m.ink) }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(7.dp)) {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(m.ink.copy(alpha = 0.12f)))
            Box(Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(col))
            // the stop line
            Box(Modifier.fillMaxWidth(th / 100f), contentAlignment = Alignment.CenterEnd) { Box(Modifier.width(2.dp).height(7.dp).background(m.ink.copy(alpha = 0.55f))) }
        }
    }
}

private fun short(m: Machine) = when (m.id) { VpsPlanCore.PHONE -> "手机"; VpsPlanCore.GHA -> "GitHub Actions"; else -> m.name.substringBefore(' ') }

private data class Live(val cpu: Int, val mem: Int, val cores: Int, val memTotalMb: Long)

@Composable
fun VpsPageScreen(onBack: () -> Unit, onOpenTerminal: (String) -> Unit, onOpenServer: (String) -> Unit) {
    var legacy by remember { mutableStateOf(false) }
    if (legacy) {
        BackHandler { legacy = false }
        WorkLocationScreen(onBack = { legacy = false }, onOpenTerminal = onOpenTerminal, onOpenServer = onOpenServer)
        return
    }
    val ctx = LocalContext.current
    val m = mines()
    PlusPrefs.init(ctx); WorkspaceStore.init(ctx); VpsPlan.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val metas by WorkspaceStore.metas.collectAsState()
    val o by VpsPlan.overrides.collectAsState()
    val now = System.currentTimeMillis()
    val bound = remember(st.servers, metas) { VpsPlan.bind(ctx) }
    val live = remember { mutableStateMapOf<String, Live>() }
    val health = VpsPlan.health(bound).let { h -> h.mapValues { (id, v) -> bound[id]?.server?.id?.let { sid -> live[sid]?.let { v.copy(memPct = it.mem) } } ?: v } }
    val th = VpsPlanCore.threshold(o)
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Machine?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun upd(f: (VpsPlanCore.Overrides) -> VpsPlanCore.Overrides) = VpsPlan.update(ctx, f)
    fun check() {
        if (checking) return
        checking = true
        scope.launch { try { PlusVps.probeAll(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {} finally { checking = false } }
    }
    LaunchedEffect(Unit) { if (st.servers.isNotEmpty()) check() }

    // live CPU / 内存 of each bound server over the app's own SSH link, every 10 s while shown (nothing is made up: no read → no bars)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    bound.values.mapNotNull { it.server }.filter { it.enabled }.distinctBy { it.id }.forEach { s -> key(s.id) {
        LaunchedEffect(owner, s.id, s.relay, s.authorized) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var fails = 0
                while (true) {
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.openminis.app.plus.VpsSsh.run(ctx, s, PROBE, 20_000, 2) }.getOrNull()?.first?.takeIf { it.exit == 0 }?.let { parseStats(it.output) }
                    }
                    if (p != null && p.memTotalMb > 0) {
                        val mem = (p.memUsedMb * 100 / p.memTotalMb).toInt()
                        live[s.id] = Live(p.cpuPct.roundToInt(), mem, p.cores, p.memTotalMb); VpsPlan.memPct[s.id] = mem; fails = 0
                    } else { live.remove(s.id); VpsPlan.memPct.remove(s.id); fails++ }
                    delay(if (fails == 0) 10_000L else (10_000L shl (fails - 1).coerceAtMost(2)))
                }
            }
        }
    } }
    // the phone's own memory (ActivityManager), refreshed with the page
    var phoneMem by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            phoneMem = runCatching {
                val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
            }.getOrDefault(-1)
            delay(10_000L)
        }
    }

    PlusPage("VPS / 电脑", onBack, actions = {
        IconButton(onClick = { check() }) {
            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else MinesIcon(MinesIcons.named("refresh-cw")!!, "刷新", tint = m.ink)
        }
    }) {
        // ── summary ──
        val online = VpsPlanCore.MACHINES.count { health[it.id]?.online == true }
        val pausedN = o.paused.size
        val subs = VpsPlanCore.MACHINES.filter { VpsPlanCore.inSub(o, it) }
        val nextExp = remember(metas, now / 3_600_000L) {
            // real end dates only: registered expiries of the bound boxes, plus 阿里云's credit date from the plan
            val ali = VpsPlanCore.ALIYUN to WorkspaceCore.parseExpiry("2026-12-21", now)
            (bound.mapNotNull { (id, b) -> (b.server?.id?.let { WorkspaceStore.meta(it) } ?: b.meta)?.expiresAt?.takeIf { it > now }?.let { id to it } } + ali)
                .filter { it.second > now }.minByOrNull { it.second }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).vpsGlass().padding(vertical = 10.dp, horizontal = 6.dp)) {
            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                Text("在线", fontSize = MT.Meta, color = m.ink2)
                Text("$online/${VpsPlanCore.MACHINES.size}", fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink)
                Text(if (pausedN > 0) "$pausedN 台已暂停" else "没有暂停", fontSize = MT.Meta, color = m.ink2)
            }
            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                Text("下个到期", fontSize = MT.Meta, color = m.ink2)
                Text(nextExp?.let { java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }.format(java.util.Date(it.second)) } ?: "—",
                    fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink)
                Text(nextExp?.let { (VpsPlanCore.machine(it.first)?.let(::short) ?: "") + if (it.first == VpsPlanCore.ALIYUN) " 额度" else "" } ?: "", fontSize = MT.Meta, color = m.ink2, maxLines = 1)
            }
            Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                Text("代理订阅", fontSize = MT.Meta, color = m.ink2)
                Text("${subs.size} 台", fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink)
                Text("在订阅", fontSize = MT.Meta, color = m.ink2)
            }
        }
        Row(Modifier.padding(start = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(2.dp).height(10.dp).background(m.ink.copy(alpha = 0.55f))); Spacer(Modifier.width(5.dp))
            Text("$th% 线：超过就不接新任务", fontSize = MT.Meta, color = m.ink2)
        }

        // ── machines ──
        SecHead("机器", "共 ${VpsPlanCore.MACHINES.size} 台", metaAccent = true)
        VpsPlanCore.MACHINES.forEach { mc ->
            val b = bound[mc.id]
            val h = health[mc.id] ?: VpsPlanCore.Health(false)
            val srv = b?.server
            val paused = mc.id in o.paused
            val lv = b?.server?.id?.let { live[it] }
            val (dot, stText, stColor) = when {
                mc.id == VpsPlanCore.PHONE -> Triple(G, "在线 · 本机", Color(0xFF1E6B40))
                mc.id == VpsPlanCore.GHA -> Triple(if (paused) N else G, if (paused) "已暂停" else "按需", Color(0xFF1E6B40))
                paused -> Triple(N, "已暂停", m.ink2)
                !h.configured -> Triple(N, "没接入工作位置", m.ink2)
                srv == null -> Triple(N, "已登记", m.ink2)
                h.online == false -> Triple(R, "连不上", Color(0xFFB3261E))
                h.online == null -> Triple(N, "检测中", m.ink2)
                (h.memPct ?: 0) >= th -> Triple(A, "在线 · 停接", Color(0xFF8A4E00))
                else -> Triple(G, "在线" + (srv.lastLatencyMs.takeIf { it >= 0 }?.let { " · $it ms" } ?: ""), Color(0xFF1E6B40))
            }
            GlassBox(onClick = { editing = mc }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(8.dp))
                    Text(mc.name, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Chip(mc.role, Color(0xFF7A2E14), Color(0xFFFBE6DC))
                    Spacer(Modifier.weight(1f))
                    Text(stText, fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = stColor, maxLines = 1)
                }
                val sub = listOfNotNull(b?.host, lv?.let { "${it.cores}核 / ${if (it.memTotalMb >= 1024) String.format(java.util.Locale.US, "%.1fG", it.memTotalMb / 1024.0) else "${it.memTotalMb}M"}" } ?: mc.sub)
                Text(sub.joinToString(" · "), fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 2.dp))
                // [Mines-38.9.13 integ] 内网穿透 status of this machine (null = flag off / not a known server → nothing)
                runCatching { com.openminis.app.plus.TunnelState.of(srv?.name?.ifBlank { null } ?: b?.host ?: mc.name) }.getOrNull()?.let { ts ->
                    Text("内网穿透：$ts", fontSize = MT.Meta, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp),
                        color = when (ts) { "穿透中" -> Color(0xFF1E6B40); "断开" -> Color(0xFFB3261E); else -> m.ink2 })
                }
                if (lv != null) Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Bar("CPU", lv.cpu, th, Modifier.weight(1f)); Bar("内存", lv.mem, th, Modifier.weight(1f))
                } else if (mc.id == VpsPlanCore.PHONE && phoneMem >= 0) Row(Modifier.padding(top = 7.dp)) {
                    Bar("内存", phoneMem, th, Modifier.weight(1f)); Spacer(Modifier.weight(1f))
                }
                if (mc.note.isNotBlank()) {
                    val warn = mc.id == VpsPlanCore.ALIYUN
                    Text(mc.note, fontSize = MT.Meta, color = if (warn) Color(0xFF8A4E00) else m.ink2, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(if (warn) Color(0xFFFCEFD9) else m.ink.copy(alpha = 0.07f)).padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Row(Modifier.padding(top = 5.dp)) { Text("接", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.width(36.dp)); Text(VpsPlanCore.accepts(o, mc), fontSize = MT.Sub, color = m.ink) }
                Row(Modifier.padding(top = 2.dp)) { Text("禁区", fontSize = MT.Sub, color = Color(0xFFB3261E), modifier = Modifier.width(36.dp)); Text(VpsPlanCore.forbids(o, mc), fontSize = MT.Sub, color = Color(0xFFB3261E)) }
                Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val exp = (b?.server?.id?.let { WorkspaceStore.meta(it) } ?: b?.meta)?.let { WorkspaceCore.expiryText(it, now) }?.ifBlank { null } ?: mc.expiry.ifBlank { "—" }
                    Text("到期 ", fontSize = MT.Meta, color = m.ink2); Text(exp, fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.ink)
                    Spacer(Modifier.weight(1f))
                    if (mc.canSub) {
                        Text("代理订阅", fontSize = MT.Meta, color = m.ink2); Spacer(Modifier.width(6.dp))
                        Switch(VpsPlanCore.inSub(o, mc), onCheckedChange = { on -> upd { it.copy(sub = it.sub + (mc.id to on)) } },
                            modifier = Modifier.semantics { contentDescription = "${mc.name} 代理订阅" })
                        Spacer(Modifier.width(8.dp))
                    }
                    when {
                        mc.id == VpsPlanCore.PHONE -> Chip("不会暂停", Color(0xFF1E6B40), Color(0xFFE1F2E7))
                        !mc.canPause -> Chip("按需", Color(0xFF1E6B40), Color(0xFFE1F2E7))
                        paused -> SmallBtn("恢复", "plus", primary = true) { upd { it.copy(paused = it.paused - mc.id) } }
                        else -> SmallBtn("暂停", "pause", primary = false) { upd { it.copy(paused = it.paused + mc.id) } }
                    }
                }
            }
        }

        // ── routing rules ──
        SecHead("路由规则", "恢复默认", metaAccent = true) { upd { VpsPlanCore.restoreRouting(it) } }
        Text("点右边的机器就能换，不动就用默认分工。", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 4.dp))
        GlassBox {
            VpsPlanCore.rules(o, now).forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
                val from = VpsPlanCore.changedFrom(r, now)
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                            if (from != null) { Spacer(Modifier.width(6.dp)); Chip("已改", Color(0xFF8A4E00), Color(0xFFFCEFD9)) }
                        }
                        val sub = when { from != null -> "默认是 " + (VpsPlanCore.machine(from)?.let(::short) ?: from); r.custom -> "你加的规则"; else -> r.note }
                        if (sub.isNotBlank()) Text(sub, fontSize = MT.Meta, color = m.ink2)
                    }
                    MinesIcon(MinesIcons.named("chevron-right")!!, null, tint = m.ink2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    TargetPicker(r, onPick = { t -> upd { VpsPlanCore.setTarget(it, r.id, t, now) } }, onRemove = if (r.custom) ({ upd { VpsPlanCore.removeRule(it, r.id) } }) else null)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
            Text("+ 加一条规则", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText,
                modifier = Modifier.fillMaxWidth().clickable { adding = true }.padding(vertical = 10.dp))
        }

        // ── failover ──
        SecHead("失败切换", "按住拖动排序")
        GlassBox {
            FailoverRow(VpsPlanCore.failover(o)) { list -> upd { it.copy(failover = list.takeIf { l -> l != VpsPlanCore.FAILOVER_DEFAULT }) } }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("默认顺序是 " + VpsPlanCore.FAILOVER_DEFAULT.joinToString(" → ") { VpsPlanCore.machine(it)?.let(::short) ?: it },
                    fontSize = MT.Meta, color = m.ink2)
                if (VpsPlanCore.failoverChanged(o)) { Spacer(Modifier.width(6.dp)); Chip("已改", Color(0xFF8A4E00), Color(0xFFFCEFD9)) }
            }
            Text("前一台不通或超过 $th%，就交给下一台", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        // ── stop line ──
        SecHead("停接线")
        GlassBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("内存超过就不接新任务", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
                RoundBtn("minus", "停接线减 1") { upd { VpsPlanCore.bumpThreshold(it, -1) } }
                Text("$th%", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 10.dp))
                RoundBtn("plus", "停接线加 1") { upd { VpsPlanCore.bumpThreshold(it, 1) } }
            }
        }

        // ── merged subscription ──
        SecHead("总订阅")
        GlassBox {
            Text("包含 ${subs.size} 台", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
            Text(subs.joinToString(" · ") { it.name }.ifBlank { "还没有机器在订阅里" }, fontSize = MT.Sub, color = m.ink2)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
            Text("机器卡片上的「代理订阅」开关决定谁进来", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 8.dp))
        }

        // ── the old screen for adding / authorising servers ──
        SecHead("服务器")
        GlassBox(onClick = { legacy = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("添加 / 授权 / 终端", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                    Text("${st.servers.size} 台在工作位置里", fontSize = MT.Sub, color = m.ink2)
                }
                MinesIcon(MinesIcons.named("chevron-right")!!, null, tint = m.ink2, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    editing?.let { mc -> MachineDialog(mc, o, bound[mc.id]?.server?.id, onOpenServer, onDismiss = { editing = null }) { f -> upd(f); editing = null } }
    if (adding) AddRuleDialog(onDismiss = { adding = false }) { label, target ->
        upd { VpsPlanCore.addRule(it, label, target, "c" + System.currentTimeMillis().toString(36)) }; adding = false
    }
}

@Composable
private fun RoundBtn(icon: String, desc: String, onClick: () -> Unit) {
    val m = mines()
    Box(Modifier.size(40.dp).clip(CircleShape).background(m.ink.copy(alpha = 0.08f)).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { MinesIcon(MinesIcons.named(icon)!!, null, tint = m.ink, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun TargetPicker(r: VpsPlanCore.Rule, onPick: (String) -> Unit, onRemove: (() -> Unit)?) {
    val m = mines()
    var open by remember { mutableStateOf(false) }
    val name = VpsPlanCore.machine(r.target)?.let(::short) ?: r.target
    Box {
        Row(Modifier.width(140.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (m.dark) Color(0x33FFFFFF) else Color(0x8CFFFFFF)).border(1.dp, m.ink.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .then(if (r.locked) Modifier else Modifier.clickable { open = true })
            .semantics { contentDescription = "${r.label} 交给 $name" + if (r.locked) "，锁定" else "，点一下换" }
            .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.locked) "$name（锁定）" else name, fontSize = MT.Sub, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            MinesIcon(MinesIcons.named(if (r.locked) "lock" else "chevron-down")!!, null, tint = m.ink2, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VpsPlanCore.TARGETS.forEach { id ->
                val mc = VpsPlanCore.machine(id) ?: return@forEach
                DropdownMenuItem(text = { Text((if (id == r.target) "✓ " else "") + mc.name, fontSize = MT.Body, color = m.ink) }, onClick = { open = false; onPick(id) })
            }
            if (onRemove != null) DropdownMenuItem(text = { Text("删除这条规则", fontSize = MT.Body, color = Color(0xFFB3261E)) }, onClick = { open = false; onRemove() })
        }
    }
}

/** Failover chips: long-press and drag to reorder; a tap moves a chip one place earlier (easier for low vision). */
@Composable
private fun FailoverRow(order: List<String>, onChange: (List<String>) -> Unit) {
    val m = mines()
    val centers = remember { mutableStateMapOf<String, Float>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        order.forEachIndexed { i, id ->
            if (i > 0) MinesIcon(MinesIcons.named("chevron-right")!!, null, tint = m.ink2, modifier = Modifier.padding(horizontal = 3.dp).size(14.dp))
            val mc = VpsPlanCore.machine(id)
            val isDrag = dragging == id
            Text("⋮⋮ " + (mc?.let(::short) ?: id), fontSize = MT.Sub, fontWeight = FontWeight.Medium, color = Color(0xFF7A2E14), maxLines = 1, softWrap = false,
                modifier = Modifier
                    .onGloballyPositioned { c -> if (!isDrag) centers[id] = c.boundsInParent().center.x }
                    .offset { IntOffset(if (isDrag) dx.roundToInt() else 0, 0) }
                    .clip(RoundedCornerShape(10.dp)).background(if (isDrag) Color(0xFFF6CDB8) else Color(0xFFFBE6DC))
                    .pointerInput(order) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = id; dx = 0f },
                            onDrag = { ch, d -> ch.consume(); dx += d.x },
                            onDragEnd = {
                                val at = (centers[id] ?: 0f) + dx
                                val to = order.filter { it != id }.count { (centers[it] ?: 0f) < at }
                                dragging = null; dx = 0f
                                val n = VpsPlanCore.move(order, order.indexOf(id), to)
                                if (n != order) onChange(n)
                            },
                            onDragCancel = { dragging = null; dx = 0f },
                        )
                    }
                    .clickable { if (i > 0) onChange(VpsPlanCore.move(order, i, i - 1)) }
                    .semantics { contentDescription = "失败切换第 ${i + 1} 台 ${mc?.name ?: id}，按住拖动排序，点一下往前挪" }
                    .padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun MachineDialog(mc: Machine, o: VpsPlanCore.Overrides, serverId: String?, onOpenServer: (String) -> Unit, onDismiss: () -> Unit,
                          onSave: ((VpsPlanCore.Overrides) -> VpsPlanCore.Overrides) -> Unit) {
    val m = mines()
    var acc by remember { mutableStateOf(VpsPlanCore.accepts(o, mc)) }
    var forb by remember { mutableStateOf(VpsPlanCore.forbids(o, mc)) }
    var idle by remember { mutableIntStateOf(VpsPlanCore.idleMin(o)) }
    MinesDialog(mc.name, onDismiss = onDismiss, confirm = "保存", onConfirm = {
        onSave { cur ->
            cur.copy(
                accepts = if (acc.trim() == mc.accepts || acc.isBlank()) cur.accepts - mc.id else cur.accepts + (mc.id to acc.trim()),
                forbids = if (forb.trim() == mc.forbids || forb.isBlank()) cur.forbids - mc.id else cur.forbids + (mc.id to forb.trim()),
                idleMin = if (mc.id == VpsPlanCore.INSTABOX) idle.takeIf { it != 10 } else cur.idleMin,
            )
        }
    }) {
        OutlinedTextField(acc, { acc = it }, label = { Text("接（岗位）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(forb, { forb = it }, label = { Text("禁区（不做的事）") }, modifier = Modifier.fillMaxWidth())
        if (mc.id == VpsPlanCore.INSTABOX) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("闲置多久自动暂停", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
            RoundBtn("minus", "少 5 分钟") { idle = (idle - 5).coerceIn(5, 120) }
            Text("$idle 分钟", fontSize = MT.Body, color = m.ink, modifier = Modifier.padding(horizontal = 8.dp))
            RoundBtn("plus", "多 5 分钟") { idle = (idle + 5).coerceIn(5, 120) }
        }
        Text("恢复默认", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText,
            modifier = Modifier.clickable { acc = mc.accepts; forb = mc.forbids; idle = 10 }.padding(vertical = 6.dp))
        if (serverId != null) Text("打开服务器详情", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText,
            modifier = Modifier.clickable { onDismiss(); onOpenServer(serverId) }.padding(vertical = 6.dp))
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val m = mines()
    var label by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(VpsPlanCore.AWS) }
    MinesDialog("加一条规则", onDismiss = onDismiss, confirm = "加上", confirmEnabled = label.isNotBlank(), onConfirm = { onAdd(label, target) }) {
        OutlinedTextField(label, { label = it }, label = { Text("什么任务（比如：视频转码）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("交给", fontSize = MT.Sub, color = m.ink2)
        VpsPlanCore.TARGETS.forEach { id ->
            val mc = VpsPlanCore.machine(id) ?: return@forEach
            Text((if (id == target) "● " else "○ ") + mc.name, fontSize = MT.Body, color = m.ink,
                modifier = Modifier.fillMaxWidth().clickable { target = id }.padding(vertical = 6.dp))
        }
    }
}

/** 设置 → 实验功能 row for this feature (on by default in 38.9.13). */
@Composable
fun VpsPageFlagRow() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(VpsPlan.flagOn(ctx)) }
    PlusRow(null, "新版 VPS / 电脑 页", "机器分工、路由规则、失败切换；关掉就回到旧的工作位置页", chevron = false,
        trailing = { Switch(on, onCheckedChange = { on = it; VpsPlan.setFlag(ctx, it) }) })
}

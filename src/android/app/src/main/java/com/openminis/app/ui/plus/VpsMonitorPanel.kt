package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.VpsMonitorCore
import com.openminis.app.plus.VpsMonitorCore.Lat
import com.openminis.app.plus.VpsServer
import com.openminis.app.plus.WorkspaceCore
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import kotlinx.coroutines.delay

/**
 * [Mines-38.9.13 vps_page_v2] Machine monitor, opened by tapping a machine card on VPS / 电脑 (reference cf-server-monitor.jpg):
 * CPU / 内存 / 磁盘 / 负载 as 20-cell bars, live and total up / down, days left + price, traffic used / allowance (∞ when none is
 * registered) + uptime, and 电信 / 联通 / 移动 latency + loss with a 20-sample history. Read with [VpsMonitorCore.PROBE] over the
 * app's own SSH / WS link every 10 s while the panel is open and the page is in front; a missing reading shows 「—」.
 */
private val CpuC = Color(0xFF2F6FD6); private val MemC = Color(0xFF7446D8); private val DiskC = Color(0xFFD9661F); private val LoadC = Color(0xFFC93A72)
private val Up = Color(0xFFB86E00); private val OkC = Color(0xFF1F9D4F); private val MidC = Color(0xFFC98A00); private val BadC = Color(0xFFC8372D)

private fun levelColor(l: Int, empty: Color) = when (l) { 0 -> OkC; 1 -> MidC; 2 -> BadC; else -> empty }

@Composable
fun VpsMonitorPanel(server: VpsServer, meta: WorkspaceCore.Meta?) {
    val ctx = LocalContext.current
    val m = mines()
    val empty = m.ink.copy(alpha = if (m.dark) 0.18f else 0.12f)
    var r by remember(server.id) { mutableStateOf<VpsMonitorCore.Reading?>(null) }
    var err by remember(server.id) { mutableStateOf(false) }
    var hist by remember(server.id) { mutableStateOf(VpsMonitorCore.CARRIERS.associate { it.key to emptyList<Lat?>() }) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner, server.id) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t0 = System.currentTimeMillis()
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.openminis.app.plus.VpsSsh.run(ctx, server, VpsMonitorCore.PROBE, 25_000, 1) }.getOrNull()?.first
                }
                if (out != null && out.output.isNotBlank()) {
                    val n = VpsMonitorCore.parse(out.output)
                    r = n; err = false
                    hist = hist.mapValues { (k, v) -> (v + n.lat[k]).takeLast(VpsMonitorCore.CELLS) }
                } else err = true
                delay((10_000L - (System.currentTimeMillis() - t0)).coerceAtLeast(1_000L))
            }
        }
    }
    val rd = r
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = "机器监控" }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
        if (rd == null) Text(if (err) "读不到这台机器（连不上）" else "正在读取…", fontSize = MT.Meta, color = if (err) BadC else m.ink2, modifier = Modifier.padding(top = 8.dp))
        // ── 4 gauges ──
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Gauge("cpu", "CPU", VpsMonitorCore.pct(rd?.cpuPct), rd?.cpuPct, CpuC, empty, Modifier.weight(1f))
            Gauge("server", "内存", VpsMonitorCore.pct(rd?.memPct), rd?.memPct, MemC, empty, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Gauge("hard-drive", "磁盘", VpsMonitorCore.pct(rd?.diskPct), rd?.diskPct, DiskC, empty, Modifier.weight(1f))
            Gauge("gauge", "负载", rd?.load1?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", rd?.loadPct, LoadC, empty, Modifier.weight(1f))
        }
        // ── 3 tiles ──
        val tileShape = RoundedCornerShape(14.dp)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(tileShape).background(m.ink.copy(alpha = if (m.dark) 0.10f else 0.05f))
            .border(1.dp, m.ink.copy(alpha = 0.12f), tileShape)) {
            Tile(Modifier.weight(1f), "↑", VpsMonitorCore.rate(rd?.txRate), "↓", VpsMonitorCore.rate(rd?.rxRate), Up)
            Box(Modifier.width(1.dp).fillMaxHeight().background(m.ink.copy(alpha = 0.12f)))
            Tile(Modifier.weight(1f), "↑", VpsMonitorCore.bytes(rd?.txTotal), "↓", VpsMonitorCore.bytes(rd?.rxTotal), m.ink)
            Box(Modifier.width(1.dp).fillMaxHeight().background(m.ink.copy(alpha = 0.12f)))
            val days = meta?.let { VpsMonitorCore.daysLeft(it.expiresAt, System.currentTimeMillis()) }
            val cost = meta?.cost?.trim().orEmpty()
            Tile(Modifier.weight(1f), "calendar", days?.let { "余 ${it}天" } ?: "余 —", "wallet", cost.ifBlank { "—" },
                if (days != null && days <= 7) BadC else OkC, icons = true, secondColor = m.ink)
        }
        // ── traffic ──
        val quota = meta?.let { VpsMonitorCore.quotaBytes(it.limits) }
        val used = rd?.trafficTotal
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(MinesIcons.named("chart-column")!!, null, tint = OkC, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("流量", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
            Text("在线：" + VpsMonitorCore.uptime(rd?.uptimeSec), fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = if (rd?.uptimeSec != null) OkC else m.ink2)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cells(if (quota != null && used != null) VpsMonitorCore.cells(used * 100.0 / quota) else 0, OkC, empty, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(VpsMonitorCore.bytes(used) + " / " + (quota?.let { VpsMonitorCore.bytes(it) } ?: "∞"), fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
        }
        // ── carriers ──
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(m.ink.copy(alpha = 0.09f)))
        VpsMonitorCore.CARRIERS.forEach { c ->
            val l = rd?.lat?.get(c.key)
            val h = hist[c.key].orEmpty()
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
                        Text(l?.ms?.let { "${Math.round(it)}" } ?: "—", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = levelColor(VpsMonitorCore.latLevel(l), m.ink2))
                        Text(" ms", fontSize = MT.Meta, color = m.ink2)
                    }
                    History(h.map { VpsMonitorCore.latLevel(it) }, empty)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text(l?.lossPct?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—", fontSize = MT.Body, fontWeight = FontWeight.SemiBold,
                            color = levelColor(VpsMonitorCore.lossLevel(l), m.ink2))
                        Text(" %", fontSize = MT.Meta, color = m.ink2)
                    }
                    History(h.map { VpsMonitorCore.lossLevel(it) }, empty)
                }
            }
        }
        Text("每 10 秒刷新 · 延迟测到北京三网", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Gauge(icon: String, label: String, value: String, pct: Double?, color: Color, empty: Color, modifier: Modifier) {
    val m = mines()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(MinesIcons.named(icon)!!, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text(label, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
            Text(value, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
        }
        Cells(VpsMonitorCore.cells(pct), color, empty, Modifier.padding(top = 6.dp).fillMaxWidth())
    }
}

@Composable
private fun Cells(filled: Int, color: Color, empty: Color, modifier: Modifier) =
    Row(modifier.height(9.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(VpsMonitorCore.CELLS) { i -> Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (i < filled) color else empty)) }
    }

/** newest sample on the right; older empty slots stay grey */
@Composable
private fun History(levels: List<Int>, empty: Color) {
    val pad = VpsMonitorCore.CELLS - levels.size
    Row(Modifier.padding(top = 6.dp).fillMaxWidth().height(9.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(VpsMonitorCore.CELLS) { i -> Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(levelColor(if (i < pad) -1 else levels[i - pad], empty))) }
    }
}

@Composable
private fun Tile(modifier: Modifier, a: String, av: String, b: String, bv: String, color: Color, icons: Boolean = false, secondColor: Color = color) {
    val m = mines()
    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple(a, av, color), Triple(b, bv, secondColor)).forEach { (k, v, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icons) MinesIcon(MinesIcons.named(k)!!, null, tint = m.ink2, modifier = Modifier.size(15.dp))
                else Text(k, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink2)
                Spacer(Modifier.width(4.dp))
                Text(v, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, softWrap = false, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

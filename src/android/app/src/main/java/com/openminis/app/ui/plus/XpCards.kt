package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.XpViewCore
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons

/**
 * [Mines-38.9.11 i32] shared pieces of the 工作流记录 / 经验 cards: tool chips with icons (浏览器 / 命令 / 读文件 / 看图 …),
 * small meta chips, the card row (title · one-line summary · chips · outcome) and the step timeline.
 */
internal fun xpIcon(name: String) = MinesIcons.named(name) ?: MinesIcons.named("wrench")!!

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolChips(tools: List<XpViewCore.Tool>, modifier: Modifier = Modifier) {
    if (tools.isEmpty()) return
    val m = mines()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools.forEach { t ->
            Row(Modifier.clip(RoundedCornerShape(50)).background(m.container.copy(alpha = if (m.dark) 0.55f else 0.75f))
                .padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(xpIcon(t.icon), null, tint = m.icon, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(t.zh, fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.onContainer, maxLines = 1)
            }
        }
    }
}

@Composable
fun MetaChip(text: String) {
    val m = mines()
    Text(text, fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (m.dark) Color(0x1FFFFFFF) else Color(0x0F1C1915)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/**
 * One card row. [leadIcon] in a tone bubble, title (1 line), summary (≤ 2 lines), tool chips, then meta chips;
 * the outcome pill + time on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun XpCardRow(
    leadIcon: String,
    tone: Tone,
    title: String,
    summary: String,
    tools: List<XpViewCore.Tool>,
    meta: List<String>,
    outcome: Pair<String, Boolean?>?,
    time: String?,
    onClick: () -> Unit,
) {
    val m = mines()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        ToneBubble(xpIcon(leadIcon), tone, 36.dp)
        Spacer(Modifier.width(MS.M))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = MT.Body, lineHeight = MT.BodyTightLH, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (time != null) { Spacer(Modifier.width(8.dp)); Text(time, fontSize = MT.Meta, color = m.ink2, maxLines = 1) }
            }
            if (summary.isNotBlank()) Text(summary, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tools.isNotEmpty() || meta.isNotEmpty() || outcome != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (outcome != null) StatusPill(outcome.first, outcome.second)
                ToolChips(tools)
                meta.forEach { MetaChip(it) }
            }
        }
    }
}

/** vertical timeline of steps: dot (✓ / ✗) + line + Chinese tool word + the step's words */
@Composable
fun StepTimeline(steps: List<Triple<String, String, Boolean>>, errors: List<String> = emptyList()) {
    val m = mines()
    Column(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, (tool, line, ok) ->
            val t = XpViewCore.tool(tool)
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(if (ok) m.container else toneColors(Tone.DANGER).second), contentAlignment = Alignment.Center) {
                        MinesIcon(xpIcon(t.icon), null, tint = if (ok) m.icon else toneColors(Tone.DANGER).first, modifier = Modifier.size(11.dp))
                    }
                    if (i < steps.size - 1) Box(Modifier.width(1.5.dp).height(28.dp).background(m.hairline))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                    Text((if (ok) "" else "没成功 · ") + t.zh, fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = if (ok) m.ink2 else toneColors(Tone.DANGER).first)
                    Text(line, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    errors.getOrNull(i)?.takeIf { it.isNotBlank() && !ok }?.let { Text(it, fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 2) }
                }
            }
        }
    }
}

/** search field + filter chips row used by both pages */
@Composable
fun XpFilterChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(o, fontSize = MT.Sub, color = if (on) m.onPrimary else m.ink, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) m.primary else minesGlassFill()).clickable { onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

// ---------------- [Mines-38.9.11 a] time words shared by 工作流记录 / 经验 ----------------
internal fun xpHm(at: Long) = if (at <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
internal fun xpDay(at: Long): String {
    if (at <= 0) return "更早"
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
    val d = f.format(java.util.Date(at)); val today = f.format(java.util.Date())
    val yest = f.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
    return when (d) { today -> "今天"; yest -> "昨天"; else -> d.substring(5).replace('-', '月') + "日" }
}
internal fun xpWhen(at: Long): String = if (at <= 0) "" else xpDay(at).let { if (it == "今天") xpHm(at) else it }
internal fun xpDur(ms: Long): String = when { ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} 秒"; ms < 3_600_000 -> "${ms / 60_000} 分钟"; else -> "${ms / 3_600_000} 小时" }
internal fun xpRate(ok: Int, n: Int): String = if (n <= 0) "" else "成功率 ${(ok * 100 + n / 2) / n}%"

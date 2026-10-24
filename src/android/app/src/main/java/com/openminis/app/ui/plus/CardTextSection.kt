package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.PlusPrefs

/**
 * [Mines-a11y-26] 卡片文字颜色: 自动 (white/black by the card's brightness, default),
 * 白, 黑, or one of a few high-visibility colours. Shows a live sample on the real
 * card colour and its contrast ratio, warning below 4.5:1.
 */
@Composable
fun CardTextSection() {
    val ctx = LocalContext.current
    PlusPrefs.init(ctx)
    val st by PlusPrefs.state.collectAsState()
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
    val bg = fill.compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    val fg = cardTextColor(fill)
    val ratio = contrastRatio(fg, bg)
    PlusSection("卡片文字颜色", footer = "首页「工作位置 / 服务器 / 账号」卡片和问候语的文字颜色。自动 = 按卡片明暗选白字或黑字，保证看得清。") {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "自动", "white" to "白", "black" to "黑", "custom" to "自选").forEach { (k, l) ->
                FilterChip(selected = st.cardTextMode == k, onClick = { PlusPrefs.update(ctx) { it.copy(cardTextMode = k) } },
                    label = { Text(l, fontSize = MT.Body) })
            }
        }
        if (st.cardTextMode == "custom") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0xFFFFFFFF, 0xFFF4F0E8, 0xFFFFD60A, 0xFF64D2FF, 0xFF30D158, 0xFFFF9F0A, 0xFF000000).forEach { c ->
                    val col = Color(c)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(col)
                        .border(if (st.cardTextColor == col.toArgb()) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { PlusPrefs.update(ctx) { it.copy(cardTextColor = col.toArgb()) } })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(fill).padding(12.dp)) {
            Text("服务器", color = readableSecondary(fg, bg), fontSize = MT.Caption)
            Text("2/2 在线", color = fg, fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "对比度 ${"%.1f".format(ratio)}:1  " + if (ratio >= 4.5) "✓ 清楚" else "⚠ 偏暗，建议用「自动」",
            color = if (ratio >= 4.5) MaterialTheme.colorScheme.onSurface else Color(0xFFFF453A),
            fontSize = MT.Body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    // [Mines-glass-26]
    PlusSection("毛玻璃卡片", footer = "首页卡片像 iOS 今日视图那样透出模糊的壁纸（安卓 12 起有模糊，旧系统是半透明）。文字下面总会自动加够遮罩，保证对比度不低于 4.5:1。") {
        SwitchRow("毛玻璃效果", null, st.glassOn) { on -> PlusPrefs.update(ctx) { it.copy(glassOn = on) } }
        if (st.glassOn) {
            Text("模糊强度 ${st.glassBlur} dp", fontSize = MT.Body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            androidx.compose.material3.Slider(st.glassBlur.toFloat(), { v -> PlusPrefs.update(ctx) { it.copy(glassBlur = v.toInt()) } },
                valueRange = 0f..40f, modifier = Modifier.padding(horizontal = 16.dp))
            Text("不透明度 ${(st.glassOpacity * 100).toInt()}%（越高越清楚）", fontSize = MT.Body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            androidx.compose.material3.Slider(st.glassOpacity, { v -> PlusPrefs.update(ctx) { it.copy(glassOpacity = (v * 100).toInt() / 100f) } },
                valueRange = 0.4f..0.9f, modifier = Modifier.padding(horizontal = 16.dp))
            GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    val f2 = cardTextColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                    val b2 = LocalCardSurface.current ?: bg
                    Text("预览", color = readableSecondary(f2, b2), fontSize = MT.Caption)
                    Text("2/2 在线", color = f2, fontSize = MT.Display, fontWeight = FontWeight.Bold)
                    Text("最差情况对比度 ${"%.1f".format(contrastRatio(f2, b2))}:1", color = readableSecondary(f2, b2), fontSize = MT.Caption)
                }
            }
        }
    }
}

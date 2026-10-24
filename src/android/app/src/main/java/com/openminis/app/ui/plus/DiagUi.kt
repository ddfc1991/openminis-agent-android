package com.openminis.app.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.Diag

/** [Mines-38.9.11.3] 设置 › 测试与上报 › 远程诊断: one row, normal text size, off by default. */
@Composable
fun DiagSettingsRow() {
    val ctx = LocalContext.current
    val on by Diag.on.collectAsState()
    val st by Diag.status.collectAsState()
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { Diag.setOn(ctx, !on) }.padding(horizontal = 16.dp, vertical = 10.dp)
        .semantics { contentDescription = "远程诊断，" + st },
        verticalAlignment = Alignment.CenterVertically) {
        IconBubble(Icons.Outlined.BugReport)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("远程诊断", fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink)
            Text(if (on) st else "默认关闭；打开后 24 小时自动关闭", fontSize = MT.Caption, lineHeight = MT.CaptionLH,
                color = if (on && st.startsWith("诊断已连接")) m.primaryText else m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MinesSwitch(on, "远程诊断") { Diag.setOn(ctx, it) }
    }
}

/** 「来自诊断：…」 banner (show_notice) and the per-time 「允许发送截图？」 question (request_screenshot_of_mines_only). */
@Composable
fun DiagHost() {
    val n by Diag.notice.collectAsState()
    val ask by Diag.shotAsk.collectAsState()
    val m = mines()
    n?.let { text ->
        LaunchedEffect(text) { kotlinx.coroutines.delay(10_000); if (Diag.notice.value == text) Diag.notice.value = null }
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
            GlassToast(Modifier.widthIn(max = 460.dp).padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("来自诊断：$text", fontSize = MT.Body, color = m.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("关闭", fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1,
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable { Diag.notice.value = null }.padding(horizontal = 10.dp, vertical = 12.dp))
                }
            }
        }
    }
    // [38.9.11.3 g2] every remote config change stays visible until undone (one tap) or 诊断 turns off
    val ch by Diag.changes.collectAsState()
    if (ch.isNotEmpty()) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 132.dp), contentAlignment = Alignment.BottomCenter) {
            GlassToast(Modifier.widthIn(max = 460.dp).padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    ch.forEach { c ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("诊断改了：${com.openminis.app.plus.DiagCore.CONFIG_LABELS[c.key] ?: c.key} → ${com.openminis.app.plus.DiagCore.configShown(c.key, c.to)}",
                                fontSize = MT.Body, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("撤销", fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1,
                                modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).clickable { Diag.revertConfig(c.key) }.padding(horizontal = 10.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
    ask?.let { id ->
        MinesDialog(
            title = "允许发送截图？",
            onDismiss = { Diag.answerShot(id, false) },
            confirm = "允许", onConfirm = { Diag.answerShot(id, true) },
            dismiss = "不允许",
        ) {
            Text("诊断想看一眼 Mines 现在的画面（只截 Mines 自己，不截别的应用）。这次允许只发这一张。",
                fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        }
    }
}

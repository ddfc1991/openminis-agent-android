package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.MinesSelfCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [Mines-report-31] 一键冒烟测试 + 上报, and 报告问题 (description + screenshot). */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var busy by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf<List<MinesSelfCheck.Check>>(emptyList()) }
    var result by remember { mutableStateOf<MinesSelfCheck.Filed?>(null) }
    var desc by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { shot = it }

    fun runAndReport() {
        if (busy != null) return
        scope.launch {
            busy = "正在检查…"
            checks = MinesSelfCheck.runAll(context) { busy = "正在检查：$it" }
            val fails = checks.filter { !it.ok && !it.warn }
            busy = "正在上报…"
            result = MinesSelfCheck.file(context,
                "[自检] build-${com.openminis.app.BuildConfig.MINES_BUILD} · " + (if (fails.isEmpty()) "全部通过" else "${fails.size} 项失败：" + fails.take(3).joinToString("、") { it.name }),
                checks)
            busy = null
        }
    }

    fun manual() {
        if (busy != null || desc.isBlank()) return
        scope.launch {
            busy = "正在上报…"
            val png = shot?.let { u -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() } }
            result = MinesSelfCheck.file(context, "[报告问题] " + desc.lines().first().take(60), checks, desc, png)
            busy = null
        }
    }

    PlusPage("测试与上报", onBack, subtitle = "一键查一遍哪里坏了，结果自动发送") {
        // [Mines-design-33] cards + short icon list instead of a paragraph
        PlusSection("会检查") {
            PlusRow(Icons.Outlined.Memory, "模型服务", "能不能正常回答", chevron = false)
            PlusDivider()
            PlusRow(Icons.Outlined.GraphicEq, "实时语音", "能不能连上", chevron = false)
            PlusDivider()
            PlusRow(Icons.Outlined.Key, "账号和密钥", "有没有失效", chevron = false)
            PlusDivider()
            PlusRow(Icons.Outlined.TouchApp, "手机控制权限", "无障碍、悬浮窗、Shizuku", chevron = false)
            PlusDivider()
            PlusRow(Icons.Outlined.Terminal, "沙箱和插件", "命令行、MCP", chevron = false)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                PillButton(busy ?: "开始检查并上报", Modifier.fillMaxWidth(), enabled = busy == null) { runAndReport() }
            }
        }
        InfoLine("密钥、Token 会自动隐去，邮箱打码；用你连接的 GitHub 账号发一条问题")
        result?.let { r ->
            PlusSection(null) {
                PlusRow(if (r.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, if (r.ok) "已发送" else "没发出去",
                    if (r.ok) null else r.message.take(60), tint = if (r.ok) Color(0xFF34C759) else Color(0xFFFF6B5E),
                    onClick = r.url?.let { u -> { uri.openUri(u) } }, value = if (r.url != null) "查看" else null)
            }
        }
        if (checks.isNotEmpty()) PlusSection("检查结果 · ${checks.count { it.ok }}/${checks.size} 通过") {
            checks.forEachIndexed { i, c ->
                PlusRow(if (c.ok) Icons.Outlined.CheckCircle else if (c.warn) Icons.Outlined.Info else Icons.Outlined.ErrorOutline, c.name, c.detail.take(80),
                    tint = if (c.ok) Color(0xFF34C759) else if (c.warn) Color(0xFFFFB020) else Color(0xFFFF6B5E), chevron = false)
                if (i < checks.lastIndex) PlusDivider()
            }
        }
        // [Mines-38.1] voice disconnect diary, one tap to copy
        PlusSection("语音断线记录", footer = "每次实时语音断开的时间、代码、线路和网络类型。") {
            val vctx = androidx.compose.ui.platform.LocalContext.current
            com.openminis.app.plus.VoiceDiag.init(vctx)
            val vt = androidx.compose.runtime.remember { com.openminis.app.plus.VoiceDiag.text(vctx, 60) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // [Mines-38.7] body-size text (the empty line is a sentence, not a log); the button is the page's outline style
                val empty = vt.startsWith("还没有")
                Text(vt.lines().takeLast(6).joinToString("\n"), fontSize = if (empty) MT.Body else MT.Caption, lineHeight = if (empty) MT.BodyLH else MT.CaptionLH, color = mines().ink2,
                    fontFamily = if (empty) null else androidx.compose.ui.text.font.FontFamily.Monospace)
                if (!empty) PillButton("复制断线记录", Modifier.padding(top = 8.dp), primary = false, onClick = {
                    vctx.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Mines 语音断线记录", com.openminis.app.plus.VoiceDiag.text(vctx, 200)))
                    FeedTasks.toast(vctx, "已复制")
                })
            }
        }
        PlusSection("报告问题") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(desc, { desc = it }, placeholder = { Text("写一句哪里不对就行") }, minLines = 3,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                PlusRow(Icons.Outlined.Image, if (shot != null) "已选截图" else "加一张截图", if (shot != null) "点这里换一张" else "可选",
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                PillButton("发送", Modifier.fillMaxWidth(), primary = desc.isNotBlank(), enabled = busy == null && desc.isNotBlank()) { manual() }
            }
        }
    }
}

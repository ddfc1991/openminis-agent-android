package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Speed
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.plus.MinesJobs
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.SandboxNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-net-26] 沙箱网络与开发工具: proxy toggle with the live decision,
 * current mirrors + re-pick, 网络体检 (mines-doctor, full report in a dialog),
 * and the one-tap 开发工具包 with a VPS fallback when the phone can't do it.
 */
@Composable
fun SandboxNetSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SandboxNet.init(ctx)
    val mode by SandboxNet.mode.collectAsState()
    val status by SandboxNet.status.collectAsState()
    val jobs by MinesJobs.jobs.collectAsState()
    var mirrors by remember { mutableStateOf("") }
    var doctorId by remember { mutableStateOf(-1L) }
    var devkitId by remember { mutableStateOf(-1L) }
    var report by remember { mutableStateOf<String?>(null) }
    var offerVps by remember { mutableStateOf(false) }
    var vpsResult by remember { mutableStateOf<String?>(null) }
    var vpsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(jobs) {
        mirrors = withContext(Dispatchers.IO) { SandboxNet.mirrorSummary(ctx) }
        jobs.firstOrNull { it.id == doctorId && !it.running }?.let { j ->
            report = j.lines.filter { it != "MINES_DOCTOR_OK" && it != "MINES_DOCTOR_PROBLEMS" }.joinToString("\n"); doctorId = -1
        }
        jobs.firstOrNull { it.id == devkitId && !it.running }?.let { j ->
            devkitId = -1
            if (!j.ok && PlusPrefs.state.value.servers.any { it.enabled }) offerVps = true
        }
    }

    PlusSection("沙箱网络", footer = status.ifBlank { null }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("沙箱代理", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            SandboxNet.ProxyMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { SandboxNet.setMode(ctx, m) }, label = { Text(m.label) })
            }
        }
        PlusDivider()
        // [38.9.11.2] 严格代理: no proxy that really gets out → pause the sandbox network instead of going direct
        var strictOn by remember { mutableStateOf(SandboxNet.strict) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("严格代理", style = MaterialTheme.typography.titleMedium)
                Text("没有能出网的代理时暂停沙箱网络，不用手机自己的网络直连", fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = strictOn, onCheckedChange = { strictOn = it; SandboxNet.setStrict(ctx, it) })
        }
        PlusDivider()
        ActionRow(Icons.Outlined.Speed, "软件源：" + mirrors.ifBlank { "还没选" } + "　· 重新测速") { SandboxNet.reselectMirrors(ctx) }
        PlusDivider()
        ActionRow(Icons.Outlined.HealthAndSafety, if (doctorId >= 0) "网络体检中…" else "网络体检（装不上东西时点这里）") {
            if (doctorId < 0) doctorId = SandboxNet.runDoctor(ctx)
        }
    }
    PlusSection("开发工具包", footer = "一键装 gcc / g++ / make、Python + pip、Node + npm、Git、Java 17（约 300–400 MB）。在后台装，装完弹窗告诉你；手机上装不了时可以改在 VPS 上装。") {
        ActionRow(Icons.Outlined.Build, if (devkitId >= 0) "正在安装开发工具包…" else "一键安装开发工具包") {
            if (devkitId < 0) devkitId = SandboxNet.installDevKit(ctx)
        }
    }

    report?.let { r ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("网络体检结果") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(r, fontFamily = FontFamily.Monospace, fontSize = MT.Caption)
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("好") } },
            dismissButton = { TextButton(onClick = { report = null; SandboxNet.reselectMirrors(ctx) }) { Text("重新选软件源") } },
        )
    }
    if (offerVps) {
        val server = PlusPrefs.state.value.servers.firstOrNull { it.enabled && it.lastLatencyMs >= 0 } ?: PlusPrefs.state.value.servers.firstOrNull { it.enabled }
        AlertDialog(
            onDismissRequest = { offerVps = false },
            title = { Text("手机上没装好") },
            text = { Text("要改在服务器「${server?.name ?: "VPS"}」上安装同样的工具吗？以后编译类任务可以在 工作位置 选 VPS 来跑。") },
            confirmButton = {
                TextButton(enabled = server != null && !vpsBusy, onClick = {
                    val srv = server ?: return@TextButton
                    offerVps = false; vpsBusy = true
                    scope.launch {
                        val (ok, out) = withContext(Dispatchers.IO) { SandboxNet.installDevKitOnVps(ctx, srv) }
                        vpsBusy = false
                        vpsResult = (if (ok) "✓ 已在 ${srv.name} 上装好\n\n" else "✗ 在 ${srv.name} 上也没装好\n\n") + out
                    }
                }) { Text("在 VPS 上装") }
            },
            dismissButton = { TextButton(onClick = { offerVps = false }) { Text("不用") } },
        )
    }
    if (vpsBusy) Text("正在服务器上安装开发工具包（可能要几分钟）…", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    vpsResult?.let { r ->
        AlertDialog(onDismissRequest = { vpsResult = null }, title = { Text("VPS 安装结果") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text(r, fontFamily = FontFamily.Monospace, fontSize = MT.Caption) } },
            confirmButton = { TextButton(onClick = { vpsResult = null }) { Text("好") } })
    }
}

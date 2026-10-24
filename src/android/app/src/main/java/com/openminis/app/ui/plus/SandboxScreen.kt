package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.SandboxCare
import com.openminis.app.plus.SandboxProc
import com.openminis.app.sandbox.PhantomProcessGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sandbox monitor: memory, running processes, one-tap cleanup, anti-kill. */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var procs by remember { mutableStateOf(emptyList<SandboxProc>()) }
    var availMb by remember { mutableStateOf(0L) }
    var totalMb by remember { mutableStateOf(1L) }
    var tick by remember { mutableIntStateOf(0) }
    val shizuku by ShizukuManager.snapshot.collectAsState()
    val owner = LocalLifecycleOwner.current

    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val (list, mem) = withContext(Dispatchers.IO) {
                    val am = context.getSystemService(ActivityManager::class.java)
                    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    SandboxCare.list() to mi
                }
                procs = list; availMb = mem.availMem / 1048576; totalMb = mem.totalMem / 1048576
                tick++
                delay(3000)
            }
        }
    }

    PlusPage("沙箱与防杀", onBack) {
        val sandboxMb = procs.sumOf { it.rssKb } / 1024
        val usedFrac = 1f - availMb.toFloat() / totalMb
        // ---- Memory ----
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        ) {
            Text("沙箱正在用 $sandboxMb MB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("手机还剩 ${availMb / 1024.0}".take(9) + " GB 可用 · ${procs.size} 个进程",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { usedFrac.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (usedFrac > 0.85f) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                scope.launch {
                    val n = withContext(Dispatchers.IO) { SandboxCare.cleanHeavy() }
                    Toast.makeText(context, if (n == 0) "没有要清理的" else "关掉了 $n 个占内存的进程", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                com.openminis.app.ui.brand.MinesIcon(Icons.Outlined.CleaningServices, null)
                Spacer(Modifier.width(8.dp))
                Text("一键清理")
            }
        }

        SandboxNetSection()

        PlusSection(null, footer = "浏览器、Node、Python 这类进程闲置 10 分钟（没用 CPU）就自动关掉。") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自动清理闲置进程", Modifier.weight(1f))
                Switch(st.autoClean, onCheckedChange = { on -> PlusPrefs.update(context) { it.copy(autoClean = on) } })
            }
        }

        // ---- Processes ----
        PlusSection("正在运行") {
            if (procs.isEmpty()) Text("沙箱现在很干净", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            procs.take(15).forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text("${p.rssKb / 1024} MB · pid ${p.pid}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { SandboxCare.kill(p.pid); procs = procs.filterNot { it.pid == p.pid } }) {
                        Text("关闭", color = Color(0xFFFF3B30))
                    }
                }
                if (i < minOf(procs.size, 15) - 1) PlusDivider()
            }
        }

        // ---- Anti-kill ----
        val pm = context.getSystemService(PowerManager::class.java)
        val batteryOk = remember(tick) { pm.isIgnoringBatteryOptimizations(context.packageName) }
        val killerOff = remember(tick) { PhantomProcessGuard.killerDisabled(context) }
        PlusSection("防止被安卓杀掉", footer = "安卓 12 起，系统会杀掉超过 32 个的子进程，沙箱命令跑到一半就断。关掉这个限制需要 Shizuku（免 root）。") {
            CheckRow("电池优化已关闭", batteryOk, "去关闭") {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PlusDivider()
            val label = when (shizuku.state) {
                ShizukuManager.State.NOT_INSTALLED -> "安装 Shizuku"
                ShizukuManager.State.NOT_RUNNING -> "打开 Shizuku"
                ShizukuManager.State.NEED_PERMISSION -> "授权"
                ShizukuManager.State.READY -> "一键关闭"
            }
            CheckRow("子进程限制已关闭", killerOff, label) {
                when (shizuku.state) {
                    ShizukuManager.State.NOT_INSTALLED -> ShizukuManager.openInstallPage(context)
                    ShizukuManager.State.NOT_RUNNING -> ShizukuManager.openShizukuApp(context)
                    ShizukuManager.State.NEED_PERMISSION -> { com.openminis.app.plus.ShizukuAsk.request(context) }
                    ShizukuManager.State.READY -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            PhantomProcessGuard.DISABLE_COMMANDS.map { cmd ->
                                ShizukuManager.runProcess(arrayOf("sh", "-c", cmd), timeoutMs = 8000).exitCode == 0
                            }.all { it }
                        }
                        PhantomProcessGuard.refresh(context)
                        Toast.makeText(context, if (ok) "已关闭，沙箱可以并行跑更多命令了" else "没完全成功，请重启 Shizuku 后再试", Toast.LENGTH_LONG).show()
                        tick++
                    }
                }
            }
            PlusDivider()
            // [Mines-shizuku-29] Shizuku state in plain words + one button.
            val shzLine = remember(tick, shizuku.state) { com.openminis.app.plus.ShizukuAsk.statusLine() }
            CheckRow(shzLine, shizuku.state == ShizukuManager.State.READY, when (shizuku.state) {
                ShizukuManager.State.NOT_INSTALLED -> "安装"
                ShizukuManager.State.NOT_RUNNING -> "打开 Shizuku"
                else -> "授权"
            }) {
                when (shizuku.state) {
                    ShizukuManager.State.NOT_INSTALLED -> ShizukuManager.openInstallPage(context)
                    ShizukuManager.State.NOT_RUNNING -> ShizukuManager.openShizukuApp(context)
                    else -> if (!com.openminis.app.plus.ShizukuAsk.request(context))
                        Toast.makeText(context, "已打开 Shizuku：在「授权应用」里打开 Mines", Toast.LENGTH_LONG).show()
                }
                tick++
            }
            PlusDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("同时最多跑 ${PhantomProcessGuard.budget} 个沙箱命令", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlusDivider()
            // [Mines-loop-30] Per-run tool-call cap: the agent pauses and asks when it is reached.
            var toolCap by remember { mutableIntStateOf(com.openminis.app.agent.RunLoopGuard.maxToolCalls(context)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("每次任务最多调用 $toolCap 次工具，到了就暂停问你", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                // [Mines-38.7] compact stepper instead of two big outlined buttons
                Stepper(onMinus = { toolCap = (toolCap - 20).coerceAtLeast(20); com.openminis.app.agent.RunLoopGuard.setMaxToolCalls(context, toolCap) },
                    onPlus = { toolCap = (toolCap + 20).coerceAtMost(500); com.openminis.app.agent.RunLoopGuard.setMaxToolCalls(context, toolCap) })
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, ok: Boolean, action: String, onAction: () -> Unit) {
    // [Mines-38.7] same shape as every row: status icon bubble + text + one small button on the right
    PlusRow(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Info, title,
        if (ok) "已完成" else null, tint = if (ok) Color(0xFF34C759) else Color(0xFFFF9500), chevron = false,
        trailing = if (ok) null else ({ PillButton(action, primary = false) { onAction() } }))
}

/** [Mines-38.7] compact − / + stepper (36 dp round buttons in one pill). */
@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    val m = mines()
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(m.container), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clickable(onClick = onMinus).semantics { contentDescription = "减少" }, contentAlignment = Alignment.Center) { Text("−", fontSize = MT.PageTitle, color = m.ink) }
        Box(Modifier.size(40.dp).clickable(onClick = onPlus).semantics { contentDescription = "增加" }, contentAlignment = Alignment.Center) { Text("+", fontSize = MT.PageTitle, color = m.ink) }
    }
}

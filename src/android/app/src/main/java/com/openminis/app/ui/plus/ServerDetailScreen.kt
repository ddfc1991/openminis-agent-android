package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.VpsServer
import com.openminis.app.sandbox.FreshProcessShell
import kotlinx.coroutines.delay

data class ServerStats(
    val host: String, val uptime: String, val cpuPct: Float, val cores: Int, val load: String,
    val memUsedMb: Long, val memTotalMb: Long, val diskUsedGb: Float, val diskTotalGb: Float,
    val rxKbs: Float, val txKbs: Float,
)

private const val PROBE = """
hostname; nproc; cat /proc/loadavg; uptime -p 2>/dev/null || uptime
grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
df -kP / | tail -1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
sleep 1
head -1 /proc/stat; awk 'NR>2{rx+=${'$'}2;tx+=${'$'}10}END{print "NET",rx,tx}' /proc/net/dev
"""

internal fun parseStats(out: String): ServerStats? = runCatching {
    val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val host = lines[0]
    val cores = lines[1].toInt()
    val load = lines[2].split(' ').take(3).joinToString(" ")
    val uptime = lines[3].removePrefix("up ")
    fun kb(key: String) = lines.first { it.startsWith(key) }.split(Regex("\\s+"))[1].toLong()
    val memTotal = kb("MemTotal:"); val memAvail = kb("MemAvailable:")
    val df = lines.first { it.endsWith(" /") }.split(Regex("\\s+"))
    val cpu = lines.filter { it.startsWith("cpu ") }
    val net = lines.filter { it.startsWith("NET") }
    fun cpuVals(l: String) = l.split(Regex("\\s+")).drop(1).map { it.toLong() }
    val a = cpuVals(cpu[0]); val b = cpuVals(cpu[1])
    val idleA = a[3] + a.getOrElse(4) { 0 }; val idleB = b[3] + b.getOrElse(4) { 0 }
    val dt = (b.sum() - a.sum()).coerceAtLeast(1)
    val cpuPct = 100f * (dt - (idleB - idleA)) / dt
    val n0 = net[0].split(' '); val n1 = net[1].split(' ')
    ServerStats(
        host, uptime, cpuPct.coerceIn(0f, 100f), cores, load,
        (memTotal - memAvail) / 1024, memTotal / 1024,
        df[2].toLong() / 1048576f, df[1].toLong() / 1048576f,
        (n1[1].toDouble() - n0[1].toDouble()).toFloat() / 1024f,
        (n1[2].toDouble() - n0[2].toDouble()).toFloat() / 1024f,
    )
}.getOrNull()

/** Live status of one server, ServerBox-style. Runs `minis-vps` in the sandbox. */
@Composable
fun ServerDetailScreen(serverId: String, onBack: () -> Unit, onOpenTerminal: (String) -> Unit) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    val server: VpsServer? = st.servers.firstOrNull { it.id == serverId }
    var stats by remember { mutableStateOf<ServerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current

    var authorizing by remember { mutableStateOf(false) }
    // [Mines-vps-link] Stats come over the app's own cached SSH session
    // (keepalive, IPv4 first, relay fallback, reconnect with backoff) instead
    // of spawning a new sandbox ssh every 5 s, which was slow and the first
    // thing to fail on a jittery link.
    if (server != null) LaunchedEffect(owner, serverId, server.relay, server.authorized) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var failures = 0
            while (true) {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.openminis.app.plus.VpsSsh.run(context, server, PROBE, 20_000, 2) }
                }
                res.onSuccess { (r, viaRelay) ->
                    val parsed = if (r.exit == 0) parseStats(r.output) else null
                    if (parsed != null) { stats = parsed; error = if (viaRelay) null else null; failures = 0 }
                    else error = r.output.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: "读取失败（${r.exit}）"
                }.onFailure { e ->
                    failures++
                    error = when (e) {
                        is com.openminis.app.plus.VpsSsh.NoKeyException -> "还没授权：点下面“授权”，输一次密码"
                        else -> {
                            val why = com.openminis.app.plus.VpsSsh.reason(e)
                            if (why.contains("钥匙")) "钥匙被拒绝：点“授权”重新输一次密码"
                            else "连不上：$why · 正在自动重连…"
                        }
                    }
                }
                loading = false
                // Back off while the link is down (5 → 10 → 20 → 30 s), 5 s when fine.
                delay(if (failures == 0) 5000L else (5000L shl (failures - 1).coerceAtMost(3)).coerceAtMost(30_000L))
            }
        }
    }

    PlusPage(server?.name?.ifBlank { server.host } ?: "服务器", onBack) {
        if (server == null) { Text("这台服务器已删除", Modifier.padding(20.dp)); return@PlusPage }
        Text("${server.target}:${server.port}", color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val s = stats
        if (loading && s == null) {
            Row(Modifier.padding(20.dp)) { CircularProgressIndicator(); Spacer(Modifier.padding(8.dp)); Text("正在读取…") }
        }
        error?.let { e ->
            Text(e, color = Color(0xFFFF3B30), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        if (s != null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Meter("CPU · ${s.cores} 核", "%.0f%%".format(s.cpuPct), s.cpuPct / 100f, "负载 ${s.load}")
                Meter("内存", "${s.memUsedMb} / ${s.memTotalMb} MB", s.memUsedMb.toFloat() / s.memTotalMb, null)
                Meter("硬盘", "%.1f / %.1f GB".format(s.diskUsedGb, s.diskTotalGb), s.diskUsedGb / s.diskTotalGb, null)
                Meter("网速", "↓ %s  ↑ %s".format(rate(s.rxKbs), rate(s.txKbs)), null, "已运行 ${s.uptime} · ${s.host}")
            }
        }
        LinkSection(server)
        // [Mines-38.9.5 i] 在跑什么 (read-only probe: ps / ss / docker ps / systemctl, ≤ every 30 min) + 到期 / 限制 / IP 归属地
        run {
            remember { com.openminis.app.plus.WorkspaceStore.init(context); 0 }
            val metas by com.openminis.app.plus.WorkspaceStore.metas.collectAsState()
            val meta = metas.firstOrNull { it.id == server.id }
            val now = remember(metas) { System.currentTimeMillis() }
            LaunchedEffect(server.id) { runCatching { com.openminis.app.plus.WorkspaceStore.probeRunningAsync(context, server) } }
            PlusSection("在跑什么", footer = "只读检测，不改动服务器上的任何程序。") {
                val lines = meta?.let { com.openminis.app.plus.WorkspaceCore.cardLines(it, now) }.orEmpty()
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
                val items = meta?.running.orEmpty()
                // [Mines-38.9.6 i17] never stuck on 「检测中…」: after 45 s say why nothing came back
                var waited by androidx.compose.runtime.remember(server.id) { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(server.id) { kotlinx.coroutines.delay(45_000); waited = true }
                if (items.isEmpty()) Text(when {
                    meta?.runningAt ?: 0L > 0L -> "没有发现在跑的服务"
                    !waited -> "检测中…"
                    else -> "没查到：" + server.lastError.ifBlank { "45 秒内服务器没回应（手机网络到不了或服务器没开）" }.take(120)
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                meta?.notes?.forEach { Text("备注：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                Spacer(Modifier.padding(4.dp))
            }
        }
        PlusSection("操作") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val entry = "${server.user}@${server.host}:${server.port}" +
                    (server.relay.trim().takeIf { it.isNotEmpty() }?.let { "|$it" } ?: "")
                Button(onClick = { onOpenTerminal("MINIS_VPS_SERVERS='$entry' minis-vps shell") },
                    modifier = Modifier.weight(1f)) { Text("终端") }
                OutlinedButton(onClick = { authorizing = true },
                    modifier = Modifier.weight(1f)) { Text(if (server.authorized) "重新授权" else "授权") }
            }
        }
    }
    AuthorizeHost(server, authorizing) { authorizing = false }
}

@Composable
private fun AuthorizeHost(server: VpsServer?, show: Boolean, onDone: () -> Unit) {
    if (show && server != null) AuthorizeDialog(server, onDismiss = onDone)
}

private fun rate(kbs: Float) = if (kbs >= 1024) "%.1f MB/s".format(kbs / 1024) else "%.0f KB/s".format(kbs)

@Composable
private fun Meter(title: String, value: String, frac: Float?, sub: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp),
    ) {
        Row {
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
        if (frac != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { frac.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (frac > 0.85f) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary,
            )
        }
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/**
 * [Mines-link-31] 快速线路: the HTTPS (wss) link to the server's mines-link worker.
 * Pairing is one tap when SSH works once (reads /etc/mines-link/phone.txt),
 * else one paste of the 连接码 the installer printed.
 */
@Composable
private fun LinkSection(server: VpsServer) {
    val context = LocalContext.current
    com.openminis.app.plus.MinesLink.init(context)
    val states by com.openminis.app.plus.MinesLink.states.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    // [Mines-link-32] true once a 10 s wait proved the worker is not answering and SSH says it is not installed
    var notInstalled by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val paired = com.openminis.app.plus.MinesLink.parse(server.linkUrl) != null
    LaunchedEffect(server.linkUrl) {
        if (!paired) return@LaunchedEffect
        com.openminis.app.plus.MinesLink.conn(server)
        // [Mines-link-32] never sit on 「正在连接…」 forever: 10 s, then find out why
        var waited = 0
        while (waited < 10_000 && !com.openminis.app.plus.MinesLink.isOnline(server.id)) { kotlinx.coroutines.delay(500); waited += 500 }
        if (com.openminis.app.plus.MinesLink.isOnline(server.id)) { msg = null; return@LaunchedEffect }
        msg = "10 秒没连上，正在检查服务器…"
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.openminis.app.plus.VpsSsh.run(context, server.copy(linkUrl = ""), "test -s /etc/mines-link/phone.txt && echo MINES_YES || echo MINES_NO", 15_000, 2).first.output }
        }
        val out = r.getOrNull().orEmpty()
        when {
            com.openminis.app.plus.MinesLink.isOnline(server.id) -> msg = null
            out.contains("MINES_NO") -> { notInstalled = true; msg = "服务器上还没装快速线路" }
            out.contains("MINES_YES") -> msg = "快速线路已装好，直连端口不通，正在改走 SSH 通道（不用开新端口）…"
            else -> msg = "快速线路和 SSH 现在都连不上：" + (r.exceptionOrNull()?.let { com.openminis.app.plus.VpsSsh.reason(it) } ?: "没有回应")
        }
    }
    fun save(code: String) {
        PlusPrefs.update(context) { st -> st.copy(servers = st.servers.map { if (it.id == server.id) it.copy(linkUrl = code.trim()) else it }) }
        notInstalled = false
        msg = "已保存连接码，正在连接…"
    }
    fun install() {
        if (installing) return
        installing = true
        msg = "正在通过 SSH 安装快速线路（约 1 分钟）…"
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bundle = context.assets.open("mines-link/install-bundle.sh").use { it.readBytes() }
                    val (sess, _) = com.openminis.app.plus.VpsSsh.session(context, server.copy(linkUrl = ""))
                    val sudo = if (server.user == "root") "" else "sudo -n "
                    com.openminis.app.plus.VpsSsh.exec(sess, "${sudo}sh -s -- --port 8443", 300_000, bundle).output
                }
            }
            installing = false
            val out = r.getOrNull().orEmpty()
            val code = out.lines().firstOrNull { it.startsWith("mines-link://") }
            if (code != null) save(code)
            else msg = if (r.isFailure) "SSH 连不上，没法安装：${com.openminis.app.plus.VpsSsh.reason(r.exceptionOrNull()!!)}"
                else "安装没成功：" + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
        }
    }
    // [Mines-38.7] connection code: 20 s limit, retries with back-off, one auto-configure, then a red line + notification
    var notReady by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    fun obtain() {
        if (reading) return
        reading = true; notReady = null
        scope.launch {
            when (val r = com.openminis.app.plus.LinkCodeReader.obtain(context, server) { msg = it }) {
                is com.openminis.app.plus.LinkCodeReader.Result.Code -> save(r.code)
                is com.openminis.app.plus.LinkCodeReader.Result.NotInstalled -> { notInstalled = true; msg = r.why }
                is com.openminis.app.plus.LinkCodeReader.Result.Failed -> { notReady = r.why; msg = null }
            }
            reading = false
        }
    }
    LaunchedEffect(Unit) { if (com.openminis.app.plus.LinkCodeReader.retryRequested) { com.openminis.app.plus.LinkCodeReader.retryRequested = false; obtain() } }
    PlusSection("快速线路（HTTPS，抗代理和断线）",
        footer = if (paired && !notInstalled) "任务在服务器上跑，手机断线不会中断；网络恢复后自动续上输出。端口 8443 没放行时，Mines 自动走已经能用的 SSH 通道，不用开新端口。" else
            "点「一键安装」：Mines 通过 SSH 在服务器上装一个小程序（开机自启）。想直连更快，可在云服务器安全组放行 TCP 8443；不放行也能用（走 SSH 通道）。") {
        val st = states[server.id]
        Text(
            // [Mines-38.7] no code yet → never 「已连接」
            when {
                notReady != null -> "快速线路未就绪：$notReady"
                reading -> "快速线路未就绪：正在读取连接码"
                !paired -> "未配置"
                notInstalled -> "服务器上还没装快速线路"
                st == null -> "正在连接…"
                else -> st.second
            },
            fontSize = MT.Body,
            color = if (notReady != null) Color(0xFFB3261E) else if (paired && st?.first == com.openminis.app.plus.MinesLink.State.ONLINE) Color(0xFF1F7A3F) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        msg?.takeIf { it != "服务器上还没装快速线路" || !notInstalled }?.let { Text(it, fontSize = MT.Body, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (!paired || notInstalled) Button(onClick = { install() }, enabled = !installing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 56.dp)) {
            Text(if (installing) "正在安装…" else "一键安装", fontSize = MT.PageTitle)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { obtain() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("自动配置") }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                val t = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                val code = Regex("""mines-link://\S+""").find(t)?.value
                if (code != null && com.openminis.app.plus.MinesLink.parse(code) != null) save(code) else msg = "剪贴板里没有连接码（以 mines-link:// 开头）"
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("粘贴连接码") }
        }
    }
}

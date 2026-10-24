package com.openminis.app.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.ui.glass.DropdownMenu
import com.openminis.app.plus.Tunnel
import com.openminis.app.plus.TunnelCore
import com.openminis.app.plus.VpsServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// [Mines-38.9.13 tunnel_v1] 内网穿透 UI: per-machine section (服务器详情) and the hub / phone-port section (工作位置).

private val Red = Color(0xFFB3261E)
private val Green = Color(0xFF1F7A3F)

private fun copy(c: android.content.Context, text: String) {
    c.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Mines", text))
    safeToast(c, "已复制")
}

@Composable
private fun TunnelSwitchRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 8.dp)
        .semantics { contentDescription = title + "，" + if (on) "已打开" else "已关闭" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink)
            if (sub != null) Text(sub, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
        }
        MinesSwitch(on, title, onChange)
    }
}

@Composable
private fun TunnelLine(text: String, color: Color? = null) {
    Text(text, fontSize = MT.Body, lineHeight = MT.BodyLH, color = color ?: mines().ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/** 服务器详情 → 「内网穿透（经 AWS）」. Hidden when the flag is off or this server is the hub. */
@Composable
fun TunnelServerSection(server: VpsServer) {
    val c = LocalContext.current
    Tunnel.init(c)
    val on by Tunnel.on.collectAsState()
    val entries by Tunnel.entries.collectAsState()
    val probe by Tunnel.probe.collectAsState()
    PlusPrefs.state.collectAsState().value
    if (!on) return
    val hub = Tunnel.hub()
    if (hub?.id == server.id) return
    val e = entries[server.id]
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf<String?>(null) }
    var webText by remember(server.id, e?.webLocal) { mutableStateOf(e?.webLocal?.takeIf { it > 0 }?.toString() ?: "") }
    LaunchedEffect(server.id, e != null) { while (e != null) { runCatching { Tunnel.probeAll(c) }; delay(15_000) } }
    fun act(label: String, block: suspend () -> Result<String>) {
        if (busy) return
        busy = true; msg = label
        scope.launch { val r = block(); busy = false; msg = r.fold({ it }, { it.message ?: "失败" }) }
    }

    PlusSection("内网穿透（经 AWS）") {
        if (hub == null) { TunnelLine("先在「工作位置 → 内网穿透」选 AWS 服务器", Red); return@PlusSection }
        TunnelSwitchRow("经 AWS 穿透", "这台机器没有公网端口时，通过 ${hub.name.ifBlank { hub.host }} 连接", e != null) { Tunnel.mark(c, server, it) }
        if (e == null) return@PlusSection
        val p = probe[server.id]
        val st = TunnelCore.status(true, p != null, p?.first == true)
        TunnelLine("状态：$st", when (st) { TunnelCore.ON -> Green; TunnelCore.DOWN -> Red; else -> null })
        msg?.let { TunnelLine(it) }
        Button(onClick = {
            act("正在这台机器上安装穿透…") {
                val r = Tunnel.install(c, server)
                if (r.isFailure) script = runCatching { Tunnel.script(c, server) }.getOrNull()
                r
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 52.dp)) {
            Text(if (busy) "请稍候…" else "开启穿透", fontSize = MT.Button)
        }
        script?.let { s ->
            TunnelLine("Mines 连不上这台机器，没法自动装。复制下面的命令，到这台机器的网页控制台里粘贴运行一次：", Red)
            OutlinedButton(onClick = { copy(c, s) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp)) { Text("复制安装命令") }
            OutlinedButton(onClick = {
                val t = (c.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(c) ?: "").toString()
                val k = TunnelCore.pubFrom(t)
                if (k != null) { Tunnel.setPubKey(c, server.id, k); script = null; msg = "已读到钥匙。下一步：「应用到 AWS」" }
                else msg = "剪贴板里没有 MINES_PUB 开头的那一行"
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp)) { Text("运行后，复制最后一行，点这里粘贴") }
        }
        Tunnel.keyLine(server.id)?.let { line ->
            TunnelLine("AWS 上要加的一行（只允许这台机器用端口 ${e.sshPort}${if (e.webPort > 0) " 和 ${e.webPort}" else ""}）：")
            Text(line, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = mines().ink2, maxLines = 3, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(c, line) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("复制这一行") }
                Button(onClick = { act("正在写入 AWS…") { Tunnel.applyToHub(c, server.id) } }, enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("应用到 AWS") }
            }
        }
        // web service of this machine (NAS admin page …)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(webText, { webText = it.filter(Char::isDigit).take(5) }, label = { Text("网页端口（如 5000）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                Tunnel.setWeb(c, server.id, webText.toIntOrNull() ?: 0, e.webPublic)
                msg = "已保存。再点一次「开启穿透」生效" + if (e.pubKey.isNotBlank()) "，然后「应用到 AWS」" else ""
            }, modifier = Modifier.heightIn(min = 52.dp)) { Text("保存") }
        }
        if (e.webPort > 0) {
            TunnelSwitchRow("网页公开到外网", null, e.webPublic) { pub ->
                Tunnel.setWeb(c, server.id, e.webLocal, pub)
                msg = "已改。再点「开启穿透」和「应用到 AWS」生效"
            }
            if (e.webPublic) {
                TunnelLine("注意：知道地址的任何人都能打开这个网页，请确认它有密码。", Red)
                Tunnel.publicWebUrl(server.id)?.let { u -> TunnelLine("外网地址：$u") }
            } else TunnelLine("只能在 Mines 里打开（别人访问不到）")
            Button(onClick = { act("正在接通网页…") { Tunnel.webUrl(c, server.id).map { u ->
                    // in-app tab over Mines (loopback forward on the phone; the page never goes public)
                    com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(c, u); "已打开 $u"
                } } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 52.dp)) { Text("打开网页") }
        }
        TextButton(onClick = { act("正在关闭…") { Tunnel.uninstall(c, server) } }, enabled = !busy,
            modifier = Modifier.padding(horizontal = 8.dp)) { Text("关闭穿透（从这台机器上移除）", color = Red) }
    }
}

/** 工作位置 → 「内网穿透（实验）」: feature switch, hub choice, 「把手机端口公开」. */
@Composable
fun TunnelHubSection() {
    val c = LocalContext.current
    Tunnel.init(c)
    val on by Tunnel.on.collectAsState()
    val hubId by Tunnel.hubId.collectAsState()
    val servers = PlusPrefs.state.collectAsState().value.servers
    val pub by Tunnel.PhonePort.state.collectAsState()
    val note by Tunnel.PhonePort.note.collectAsState()
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("8080") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    PlusSection("内网穿透（实验）") {
        TunnelSwitchRow("内网穿透", "没有公网 IP 的机器经 AWS 连接；手机端口可临时公开", on) { Tunnel.setOn(c, it) }
        if (!on) return@PlusSection
        val hub = Tunnel.hub()
        Box {
            TunnelLine("AWS 中转：" + (hub?.let { it.name.ifBlank { it.host } } ?: "未选择（点这里选）"), if (hub == null) Red else null)
            Box(Modifier.matchParentSize().clickable { pick = true }.semantics { contentDescription = "选择 AWS 中转服务器" })
            DropdownMenu(pick, { pick = false }) {
                servers.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name.ifBlank { s.host } + if (s.id == hubId) " ✓" else "") }, onClick = { Tunnel.setHub(c, s.id); pick = false })
                }
            }
        }
        // Part 2
        TunnelLine("把手机端口公开", null)
        val p = pub
        if (p == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("手机端口") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (busy) return@Button
                    busy = true; msg = "正在公开…"
                    scope.launch {
                        val r = Tunnel.PhonePort.start(c, portText.toIntOrNull() ?: 0)
                        busy = false; msg = r.exceptionOrNull()?.let { it.message ?: "失败" }
                    }
                }, enabled = !busy && hub != null, modifier = Modifier.heightIn(min = 52.dp)) { Text("公开") }
            }
            TunnelLine("注意：公开后，知道地址的任何人都能访问这个端口。用完请关掉；Mines 被关掉时也会自动停止。", Red)
        } else {
            TunnelLine("已公开：手机 ${p.localPort} → ${p.address}", Green)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(c, p.address) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("复制地址") }
                Button(onClick = { Tunnel.PhonePort.stop(null); msg = "已停止公开" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("停止公开") }
            }
            TunnelLine("任何人都能访问这个地址。", Red)
        }
        (note ?: msg)?.let { TunnelLine(it) }
    }
}

package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Key
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.plus.SessionStatus
import com.openminis.app.plus.PlusState
import com.openminis.app.plus.PlusVps
import com.openminis.app.plus.VpsServer
import kotlinx.coroutines.launch

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)

@Composable
fun WorkLocationScreen(onBack: () -> Unit, onOpenTerminal: (String) -> Unit, onOpenServer: (String) -> Unit = {}) {
    val context = LocalContext.current
    PlusPrefs.init(context)
    val st by PlusPrefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VpsServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf<VpsServer?>(null) }
    var testing by remember { mutableStateOf<VpsServer?>(null) }

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            try { PlusVps.probeAll(context) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (t: Throwable) { safeToast(context, "检测出错：" + (t.message ?: t.javaClass.simpleName)) }
            finally { checking = false }
        }
    }
    // [Mines-vps-28] Every tap on this screen goes through guard(): a failure
    // shows a message instead of taking the app down.
    fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            android.util.Log.w("WorkLocation", "$what failed", t)
            safeToast(context, "$what 出错：" + (t.message ?: t.javaClass.simpleName))
        }
    }
    LaunchedEffect(Unit) { if (st.servers.isNotEmpty()) check() }

    PlusPage("工作位置", onBack) {
        val effective = PlusVps.effectiveMode(st)
        val best = PlusVps.orderedTargets(st).firstOrNull()
        // -- Mode picker --
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeCard(
                Modifier.weight(1f), Icons.Outlined.PhoneAndroid, "本机", "在手机沙箱里干活",
                selected = !st.isVps,
            ) {
                guard("切换到本机") {
                    PlusPrefs.update(context) { it.copy(workMode = PlusState.MODE_LOCAL) }
                    PlusVps.syncToSandbox(context)
                }
            }
            // [Mines-server-card] "VPS" was jargon for what the section below
            // already calls 服务器, and the card said nothing about WHICH
            // server. Same name, plus the two facts you actually want before
            // handing work over: what OS it runs, and where traffic leaves.
            ModeCard(
                Modifier.weight(1f), Icons.Outlined.Cloud, "服务器", "重活交给服务器",
                selected = st.isVps,
                facts = buildList {
                    if (effective != PlusState.MODE_LOCAL) {
                        best?.let { b ->
                            // [Mines-38.9.6 i17] 「检测中」 only until the first check finished, then the real outcome
                            add("系统：${b.osLabel.ifBlank { if (b.lastCheckedAt > 0L) "没读到" else "检测中" }}")
                            add("出口：${b.egressIp.ifBlank { if (b.lastCheckedAt > 0L) "没读到" else "检测中" }}")
                        }
                    }
                },
            ) {
                guard("切换到服务器") {
                    PlusPrefs.update(context) { it.copy(workMode = PlusState.MODE_VPS) }
                    check()
                }
            }
        }
        Text(
            when {
                !st.isVps -> "现在：在本机运行"
                effective == PlusState.MODE_LOCAL -> "现在：服务器都连不上，已自动回到本机"
                best != null && best.lastLatencyMs >= 0 -> "现在：用 ${best.name.ifBlank { best.host }}（${best.lastLatencyMs}\u00A0ms）"
                else -> "现在：VPS 模式，正在检测服务器"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (st.isVps && effective == PlusState.MODE_LOCAL) Red else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )

        PlusSection(
            "服务器",
            footer = "可以加多台，自动用延迟最低的那台；断了会自动重连，连不上就换下一台，任务在服务器上继续跑不会丢。新服务器添加时输一次密码，Mines 自动装好钥匙，以后免密。直连不通时可以填“中转”。",
            footerStrong = true,
        ) {
            if (st.servers.isEmpty()) {
                Text(
                    "还没有服务器",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            st.servers.forEach { s ->
                ServerRow(
                    s,
                    onClick = { guard("打开服务器") { onOpenServer(s.id) } },
                    onEdit = { editing = s },
                    onToggle = { on ->
                        guard("切换服务器") {
                            PlusPrefs.update(context) { cur ->
                                cur.copy(servers = cur.servers.map { if (it.id == s.id) it.copy(enabled = on) else it })
                            }
                            PlusVps.syncToSandbox(context)
                        }
                    },
                    onAuthorize = { authorizing = s },
                    onTest = { testing = s },
                    onTrustKey = { trustHostKey(context, s, onNeedPassword = { authorizing = s }, done = { check() }) },
                )
                PlusDivider()
            }
            ActionRow(Icons.Outlined.Add, "添加服务器") { adding = true }
            PlusDivider()
            // [Mines-38.9.4] 立即检测 / 一键装钥匙 share the same icon row (PlusRow) so icons and text line up
            val checkSpin: (@Composable () -> Unit)? = if (checking) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null
            PlusRow(Icons.Outlined.Refresh, if (checking) "检测中…" else "立即检测", chevron = false,
                trailing = checkSpin,
                onClick = { check() })
            if (st.servers.size > 1) {
                PlusDivider()
                // [Mines-keys-32] Termix-style one tap: put the phone's key on every server
                BatchKeyRow(st.servers)
            }
        }

        // [Mines-38.9.5 i] cloud browsers / remote desktops the agent registered (workspace_register)
        WsOtherSection()

        PlusSection("连接", footer = "所有服务器都连不上时，自动回到本机继续，不会卡住。", footerStrong = true) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("断线自动回本机", Modifier.weight(1f))
                Switch(st.autoFallback, onCheckedChange = { on ->
                    guard("设置") {
                        PlusPrefs.update(context) { it.copy(autoFallback = on) }
                        PlusVps.syncToSandbox(context)
                    }
                })
            }
        }
    }

    authorizing?.let { srv ->
        AuthorizeDialog(srv, onDismiss = { authorizing = null; check() })
    }
    testing?.let { srv -> TestRoutesDialog(srv, onAuthorize = { testing = null; authorizing = srv }) { testing = null } }

    if (adding || editing != null) {
        ServerDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { srv, password -> guard("保存服务器") {
                PlusPrefs.update(context) { cur ->
                    val exists = cur.servers.any { it.id == srv.id }
                    cur.copy(servers = if (exists) cur.servers.map { if (it.id == srv.id) srv else it } else cur.servers + srv)
                }
                adding = false; editing = null
                com.openminis.app.plus.VpsSsh.drop(srv.id)
                if (password.isNotEmpty()) {
                    scope.launch {
                        val r = try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.openminis.app.plus.VpsSsh.authorize(context, srv, password)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) { Result.failure(t) }
                        safeToast(context, r.fold({ it }, { "授权失败：" + (it.message ?: "") }))
                        check()
                    }
                } else check()
            } },
            onDelete = { srv -> guard("删除服务器") {
                PlusPrefs.update(context) { cur -> cur.copy(servers = cur.servers.filterNot { it.id == srv.id }) }
                editing = null
                PlusVps.syncToSandbox(context)
            } },
        )
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier, icon: ImageVector, title: String, sub: String,
    selected: Boolean,
    // [Mines-server-card] Extra facts rendered under the subtitle — the VPS
    // card shows 系统 and 出口地址 so the user can see at a glance which box
    // they are about to hand work to, and where traffic leaves from.
    facts: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (selected) primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MinesIcon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A single-line card keeps a fixed height, so the facts are only
        // drawn when there is something to say — an empty VPS card would
        // otherwise grow a pair of blank lines.
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            facts.forEach { f ->
                Text(
                    f,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ServerRow(s: VpsServer, onClick: () -> Unit, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onAuthorize: () -> Unit, onTest: () -> Unit = {},
                      onTrustKey: () -> Unit = {}) {
    val rowCtx = LocalContext.current
    // [Mines-38.9.4] alignment: dot centred on the title line; 编辑 + switch centred on the title block
    // (name + address line); everything below (reason, 信任新指纹, 测试连接) starts at the title's left edge.
    val hostKeyChanged = s.lastError.contains("指纹变了")
    // [Mines-38.9.5 i] 工作位置 card: provider / account, 到期, 限制, 费用, IP 归属地, 凭据 (vault ref only), 在跑什么
    remember { com.openminis.app.plus.WorkspaceStore.init(rowCtx); 0 }
    val metas by com.openminis.app.plus.WorkspaceStore.metas.collectAsState()
    val meta = metas.firstOrNull { it.id == s.id }
    val now = remember(metas) { System.currentTimeMillis() }
    val isPrimary = remember(metas, s.id) { com.openminis.app.plus.WorkspaceStore.primaryId() == s.id }
    // [Mines-38.9.11 int] user 2026-10-20 11:23 (screenshot): cards collapsed by default — only dot, name, 主设备, address · latency,
    // 编辑, switch. Tap the card → details (provider, 限制, 费用, IP 归属地, 在跑, 备注) + 测试连接 / 设为主设备; tap again → collapse.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(s.id) { mutableStateOf(false) }
    val forcedOpen = WorkLocationUi.expandId
    LaunchedEffect(forcedOpen) { if (forcedOpen != null) expanded = forcedOpen == s.id }
    val chevronTurn by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f,
        androidx.compose.animation.core.tween(220), label = "server-chevron")
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收起详情" else "展开详情") { expanded = !expanded }
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val dot = when {
            !s.enabled -> Color.Gray
            hostKeyChanged -> Color(0xFFE0A100)     // amber: reachable, but the key needs the user's OK
            s.lastLatencyMs >= 0 -> Green
            s.lastLatencyMs == -2 -> Red
            else -> Color(0xFFFFCC00)
        }
        val titleLine = with(androidx.compose.ui.platform.LocalDensity.current) {
            (MaterialTheme.typography.bodyLarge.lineHeight.takeIf { it.isSp }?.toDp() ?: 24.dp)
        }
        Box(Modifier.height(titleLine).width(10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // [Mines-38.9.5 i] 主设备 tag next to the name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name.ifBlank { s.host }, style = MaterialTheme.typography.bodyLarge)
                        if (isPrimary) { Spacer(Modifier.width(6.dp)); WsTag("主设备", Tone.ACCENT) }
                        if (meta != null && com.openminis.app.plus.WorkspaceCore.expired(meta, now)) { Spacer(Modifier.width(6.dp)); WsTag("已到期", Tone.DANGER) }
                        Spacer(Modifier.width(4.dp))
                        Icon(androidx.compose.material.icons.Icons.Outlined.ExpandMore, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronTurn })
                    }
                    // [Mines-38.9.3] 「· 1 / ms ·」 wrapped mid-token: each part keeps its own spaces (NBSP), lines break only between parts;
                    // a 1–4 ms "direct" time while a VPN / TUN is on is the phone's tunnel → 经代理
                    val parts = listOf("${s.target}:${s.port}") + when {
                        !s.enabled -> listOf("已停用")
                        s.lastLatencyMs >= 0 -> listOfNotNull(
                            if (!s.viaRelay && SessionStatus.viaLocalTunnel(s.lastLatencyMs, com.openminis.app.plus.VpsSsh.vpnActive(rowCtx))) "经代理" else null,
                            SessionStatus.msText(s.lastLatencyMs),
                            if (s.viaRelay) "经中转" else null, if (!s.authorized) "未授权" else null)
                        s.lastLatencyMs == -2 -> listOf("连不上")
                        else -> listOf("未检测")
                    }
                    Text(
                        parts.joinToString(" · ") { it.replace(' ', '\u00A0') },
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                Switch(s.enabled, onCheckedChange = onToggle)
            }
            // problems stay visible while collapsed (the user must see why a server is down / needs a tap)
            if (s.enabled && hostKeyChanged) {
                // [Mines-38.9.3] a reinstalled server: say so, one button
                Text("服务器重装过，指纹变了", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFB07800),
                    modifier = Modifier.padding(top = 4.dp))
                Button(onClick = onTrustKey, modifier = Modifier.padding(top = 2.dp).heightIn(min = 40.dp)) { Text("信任新指纹") }
            } else if (s.enabled && s.lastError.isNotBlank() && (s.lastLatencyMs == -2 || !s.authorized)) {
                // [Mines-vps-link] Say why, not just 连不上. [Mines-vps-28] with the failure class first.
                val k = PlusVps.kind(s.lastError)
                Text("原因：" + (if (k == s.lastError || s.lastError.startsWith(k)) s.lastError else k + " · " + s.lastError), style = MaterialTheme.typography.bodySmall, color = Red,
                    maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(240), expandFrom = Alignment.Top) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 40)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200), shrinkTowards = Alignment.Top) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                Column {
                    if (meta != null) WsMetaLines(meta, now)
                    if (s.enabled) {
                        // one action line, all starting at the title's left edge and vertically centred together
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ServerLink("测试连接", onTest)
                            if (!s.authorized && s.lastLatencyMs >= 0 && !hostKeyChanged) ServerLink("授权（输一次密码）", onAuthorize)
                            if (!isPrimary) ServerLink("设为主设备") { com.openminis.app.plus.WorkspaceStore.setPrimary(s.id); com.openminis.app.plus.WorkspaceStore.touch() }
                            ServerLink("详情", onClick)
                        }
                    } else Row(Modifier.padding(top = 2.dp)) { ServerLink("详情", onClick) }
                }
            }
        }
    }
}

/** [Mines-38.9.11 int] smoke / 自检: open one server card on 工作位置 (null = leave the user's taps alone) */
object WorkLocationUi {
    var expandId by mutableStateOf<String?>(null)
}

/** Text action that starts exactly at its left edge (no TextButton min-width centring), 48 dp touch height. */
@Composable
private fun ServerLink(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, title: String, onClick: () -> Unit) =
    PlusRow(icon, title, onClick = onClick) // [Mines-design-33]

@Composable
internal fun PlusDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp).height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun ServerDialog(
    initial: VpsServer?, onDismiss: () -> Unit,
    onSave: (VpsServer, String) -> Unit, onDelete: (VpsServer) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "root") }
    var relay by remember { mutableStateOf(initial?.relay ?: "") }
    var altPort by remember { mutableStateOf(initial?.altPort?.takeIf { it > 0 }?.toString() ?: "") }
    var tsHost by remember { mutableStateOf(initial?.tailscaleHost ?: "") }
    var cfHost by remember { mutableStateOf(initial?.cfHost ?: "") }
    var password by remember { mutableStateOf("") }
    val valid = host.isNotBlank() && !host.contains(' ') && (port.toIntOrNull() ?: 0) in 1..65535 &&
        user.isNotBlank() && user.all { it.isLetterOrDigit() || it in "._-" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加服务器" else "编辑服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名字（随便起）") }, singleLine = true)
                OutlinedTextField(host, { host = it.trim() }, label = { Text("地址 / IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it.trim() }, label = { Text("用户") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") },
                        singleLine = true, modifier = Modifier.weight(0.7f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (initial == null || !initial.authorized) {
                    OutlinedTextField(password, { password = it }, label = { Text("密码（只用这一次，装好钥匙后不保存）") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                // [Mines-vps-31] backup routes: tried in order 端口 → 备用端口(默认 443) → Tailscale → 中转
                OutlinedTextField(cfHost, { cfHost = it.trim() }, label = { Text("Cloudflare 隧道域名（可不填，优先用）") }, singleLine = true,
                    placeholder = { Text("ssh-xx.example.com") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(altPort, { altPort = it.filter(Char::isDigit).take(5) }, label = { Text("备用端口（不填 = 443）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(tsHost, { tsHost = it.trim() }, label = { Text("Tailscale 地址（可不填）") }, singleLine = true,
                    placeholder = { Text("xxx.tailXXXX.ts.net") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(relay, { relay = it.trim() }, label = { Text("中转（可不填）") }, singleLine = true,
                    placeholder = { Text("ws://IP:8022/ 或 ssh://root@跳板:22") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val base = initial ?: VpsServer(name = "", host = "")
                val same = initial?.host == host && initial.port == port.toInt() && initial.user == user
                onSave(base.copy(name = name.trim(), host = host, port = port.toInt(), user = user, relay = relay,
                    altPort = altPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0, tailscaleHost = tsHost, cfHost = cfHost,
                    lastLatencyMs = if (same) base.lastLatencyMs else -1,
                    authorized = if (same) base.authorized else false), password)
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("删除", color = Red) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}


/**
 * [Mines-vps-link] First-time authorisation without a terminal: the password
 * is used once to log in, the app's key is appended to ~/.ssh/authorized_keys,
 * and every later login (the app's checks and the agent's minis-vps) uses the
 * key. The password is not stored.
 */
@Composable
internal fun AuthorizeDialog(server: VpsServer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("授权 ${server.name.ifBlank { server.host }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输一次 ${server.target} 的密码。Mines 会自动生成钥匙装到服务器上，以后免密登录；密码不保存。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(password, { password = it }, label = { Text("服务器密码") }, singleLine = true,
                    enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("正在登录并安装钥匙…")
                }
                result?.let { Text(it, color = if (it.startsWith("已授权")) Green else Red) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true; result = null
                scope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.openminis.app.plus.VpsSsh.authorize(context, server, password)
                    }
                    busy = false
                    result = r.fold({ it }, { "授权失败：" + (it.message ?: "") })
                    if (r.isSuccess) {
                        PlusPrefs.update(context) { st ->
                            st.copy(servers = st.servers.map { if (it.id == server.id) it.copy(authorized = true, lastError = "") else it })
                        }
                        password = ""
                        onDismiss()
                    }
                }
            }) { Text("授权") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}


internal fun safeToast(context: android.content.Context, text: String) {
    runCatching { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() }
}

/**
 * [Mines-vps-28] 测试连接: tries direct, every proxy / VPN-app port found on the
 * phone and the configured relay, lists each with its result, and keeps the
 * fastest route that works (a proxy route is saved as the server's relay).
 */
@Composable
internal fun TestRoutesDialog(server: VpsServer, onAuthorize: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tries by remember { mutableStateOf<List<PlusVps.RouteTry>?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(server.id) {
        val r = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val t = PlusVps.testRoutes(context, server)
                t to PlusVps.applyBestRoute(context, server, t)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            emptyList<PlusVps.RouteTry>() to ("测试出错：" + (t.message ?: t.javaClass.simpleName))
        }
        tries = r.first; summary = r.second
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("测试连接 · ${server.name.ifBlank { server.host }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val t = tries
                if (t == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("正在试直连、手机代理/VPN、中转…")
                } else {
                    t.forEach { r ->
                        Text(
                            // [Mines-38.9.3] the reason once (was 「这个端口不是 SSH（这个端口不是 SSH）」); 经代理 when the phone's tunnel answered
                            (if (r.ok) "✓ " else "✗ ") + r.label + "：" +
                                (if (r.ok) SessionStatus.msText(r.ms) else SessionStatus.reasonLine(PlusVps.kind(r.why), r.why)),
                            color = if (r.ok) Green else Red, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    summary?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                }
            }
        },
        // [Mines-38.9.3] reachable but not authorised yet → the dialog ends with 「去授权」 (opens the password prompt)
        confirmButton = {
            val needAuth = tries?.any { it.ok } == true && onAuthorize != null &&
                PlusPrefs.state.value.servers.firstOrNull { it.id == server.id }?.authorized == false
            if (needAuth) Button(onClick = { onAuthorize?.invoke() }) { Text("去授权") } else TextButton(onClick = onDismiss) { Text("好") }
        },
        dismissButton = {
            val needAuth = tries?.any { it.ok } == true && onAuthorize != null &&
                PlusPrefs.state.value.servers.firstOrNull { it.id == server.id }?.authorized == false
            if (needAuth) TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/**
 * [Mines-38.9.3] 「信任新指纹」 after a server reinstall: forget the old pin, log in again with the phone's key (pins the new
 * one); a fresh system no longer has the phone's key → straight to the password prompt that re-installs it.
 */
internal fun trustHostKey(context: android.content.Context, s: VpsServer, onNeedPassword: () -> Unit, done: () -> Unit) {
    com.openminis.app.plus.VpsSsh.trustNewHostKey(context, s)
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.openminis.app.plus.VpsSsh.run(context, s.copy(linkUrl = ""), "echo MINES_OK", 15_000, 1).first.output.contains("MINES_OK") }
        }
        if (ok.getOrDefault(false)) {
            PlusPrefs.update(context) { st -> st.copy(servers = st.servers.map { if (it.id == s.id) it.copy(authorized = true, lastError = "") else it }) }
            safeToast(context, "已信任新指纹，连上了")
            done()
        } else {
            PlusPrefs.update(context) { st -> st.copy(servers = st.servers.map { if (it.id == s.id) it.copy(authorized = false, lastError = "") else it }) }
            safeToast(context, "已信任新指纹。新系统里没有手机的钥匙，输一次密码装回去")
            onNeedPassword()
        }
    }
}


/**
 * [Mines-keys-32] 一键给所有服务器装钥匙: tries each server with the phone's key;
 * the ones that still need it get it with ONE password prompt (servers that
 * share a password need it typed once). The password is never stored.
 */
@Composable
private fun BatchKeyRow(servers: List<com.openminis.app.plus.VpsServer>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var need by remember { mutableStateOf<List<com.openminis.app.plus.VpsServer>>(emptyList()) }
    var pw by remember { mutableStateOf("") }
    val busySpin: (@Composable () -> Unit)? = if (busy) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null
    PlusRow(Icons.Outlined.Key, if (busy) "处理中…" else "一键给所有服务器装钥匙", sub = msg, chevron = false,
        trailing = busySpin,
        onClick = if (busy) null else ({
        busy = true; msg = "正在逐台检查钥匙…"
        scope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                servers.filter { it.enabled }.map { s ->
                    s to runCatching { com.openminis.app.plus.VpsSsh.run(context, s.copy(linkUrl = ""), "echo MINES_OK", 15_000, 1).first.output.contains("MINES_OK") }
                }
            }
            val ok = res.count { it.second.getOrDefault(false) }
            need = res.filter { !it.second.getOrDefault(false) }.map { it.first }
            busy = false
            msg = if (need.isEmpty()) "全部 $ok 台都已经装好钥匙" else "$ok 台已装好，${need.size} 台还需要输一次密码"
        }
    }))
    if (need.isNotEmpty() && !busy) AlertDialog(
        onDismissRequest = { need = emptyList() },
        title = { Text("输入服务器密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("要装钥匙的：" + need.joinToString("、") { it.name.ifBlank { it.host } } + "。密码一样只输一次；密码只用这一次，不保存。", fontSize = MT.Body)
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = { Button(enabled = pw.isNotEmpty(), onClick = {
            val list = need; val p = pw; need = emptyList(); pw = ""; busy = true; msg = "正在装钥匙…"
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    list.map { s -> s to com.openminis.app.plus.VpsSsh.authorize(context, s.copy(linkUrl = ""), p) }
                }
                busy = false
                val bad = r.filter { it.second.isFailure }
                if (bad.isEmpty()) msg = "已给 ${r.size} 台装好钥匙，以后免密"
                else { msg = "${r.size - bad.size} 台装好；没成功：" + bad.joinToString("、") { it.first.name.ifBlank { it.first.host } } + "（密码不同的话再点一次，输那台的密码）" }
                r.filter { it.second.isSuccess }.forEach { (s, _) ->
                    PlusPrefs.update(context) { st -> st.copy(servers = st.servers.map { if (it.id == s.id) it.copy(authorized = true) else it }) }
                }
            }
        }) { Text("装钥匙") } },
        dismissButton = { TextButton(onClick = { need = emptyList() }) { Text("取消") } },
    )
}


// ---------------- [Mines-38.9.5 i] 工作位置 new fields ----------------

@Composable
private fun WsTag(text: String, tone: Tone) {
    val (fg, bg) = toneColors(tone)
    Text(text, fontSize = MT.Meta, color = fg, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 7.dp, vertical = 1.dp))
}

@Composable
private fun WsMetaLines(m: com.openminis.app.plus.WorkspaceCore.Meta, now: Long) {
    val expired = com.openminis.app.plus.WorkspaceCore.expired(m, now)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (expired) 0.6f else 1f)
    com.openminis.app.plus.WorkspaceCore.cardLines(m, now).forEach { line ->
        Text(line, style = MaterialTheme.typography.bodySmall, color = if (line.startsWith("到期") && expired) Red else ink, modifier = Modifier.padding(top = 2.dp))
    }
    if (m.running.isNotEmpty()) Text("在跑：" + m.running.take(6).joinToString("、") + if (m.running.size > 6) " 等 ${m.running.size} 项" else "",
        style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    if (m.notes.isNotEmpty()) Text("备注：" + m.notes.takeLast(3).joinToString("；"), style = MaterialTheme.typography.bodySmall, color = ink,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun WsOtherSection() {
    val c = LocalContext.current
    remember { com.openminis.app.plus.WorkspaceStore.init(c); 0 }
    val metas by com.openminis.app.plus.WorkspaceStore.metas.collectAsState()
    val others = metas.filter { it.kind != com.openminis.app.plus.WorkspaceCore.Kind.SERVER }
    if (others.isEmpty()) return
    val now = remember(metas) { System.currentTimeMillis() }
    val primary = remember(metas) { com.openminis.app.plus.WorkspaceStore.primaryId() }
    PlusSection("电脑与云浏览器", footer = "Mines 注册或领到的会自动加到这里，到期前会提醒。") {
        others.forEachIndexed { i, m ->
            if (i > 0) PlusDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name.ifBlank { m.host }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp)); WsTag(m.kind.label, Tone.NEUTRAL)
                    if (primary == m.id) { Spacer(Modifier.width(6.dp)); WsTag("主设备", Tone.ACCENT) }
                    if (com.openminis.app.plus.WorkspaceCore.expired(m, now)) { Spacer(Modifier.width(6.dp)); WsTag("已到期", Tone.DANGER) }
                }
                if (m.host.isNotBlank() && m.name.isNotBlank()) Text(m.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                WsMetaLines(m, now)
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (primary != m.id) ServerLink("设为主设备") { com.openminis.app.plus.WorkspaceStore.setPrimary(m.id); com.openminis.app.plus.WorkspaceStore.touch() }
                    ServerLink("移除") { com.openminis.app.plus.WorkspaceStore.remove(m.id) }
                }
            }
        }
    }
}

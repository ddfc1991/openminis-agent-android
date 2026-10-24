package com.openminis.app.ui.plus

import androidx.compose.foundation.background
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
import com.openminis.app.ui.glass.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.Inbox3912Core
import com.openminis.app.plus.MailInbox
import com.openminis.app.plus.PlusPrefs
import com.openminis.app.ui.brand.MinesIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [Mines-38.9.12 b] design ws2 ① 验证码 tab + 摘要, ③ 注册台账, ④ 服务器快速连接 */
private fun ic2(n: String) = com.openminis.app.ui.brand.MinesIcons.named(n)!!

object Codes3912 {
    fun lite(mails: List<MailInbox.Mail>) = mails.map { Inbox3912Core.MailLite(it.id, it.from.ifBlank { it.fromAddr } + " <" + it.fromAddr + ">", it.subject, it.text, it.at, it.address) } +
        // [Mines-38.9.12.1] + mail to the user's own domain (自建域名)
        com.openminis.app.plus.AgentMailbox.selfMails.value.map { Inbox3912Core.MailLite(it.id, it.from, it.subject, it.text, it.at, com.openminis.app.plus.AgentMailbox.selfTo[it.id].orEmpty()) }
    fun all(mails: List<MailInbox.Mail>, now: Long = System.currentTimeMillis()) =
        Inbox3912Core.codes(lite(mails), now) { com.openminis.app.plus.MailParse.extractCode(it) }
    fun fresh(mails: List<MailInbox.Mail>) = all(mails).filter { it.fresh }
    /** self-test readout */
    fun state(): String = org.json.JSONObject().put("codes", all(MailInbox.mails.value).size).put("fresh", fresh(MailInbox.mails.value).size)
        .put("summary", summary().line).toString()
    fun summary(): Inbox3912Core.Summary {
        val ms = MailInbox.mails.value
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
        return Inbox3912Core.summary(lite(ms), MailInbox.unreadTotal(), all(ms).size, c.timeInMillis)
    }
}

private fun copy(ctx: android.content.Context, label: String, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText(label, text)
        // sensitive: hidden from the clipboard preview on Android 13+
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
    }
}

// ───────────────────────────── 消息 · 摘要 (ws2 ①) ─────────────────────────────

@Composable
fun MailSummaryCard() {
    val m = mines()
    val mails by MailInbox.mails.collectAsState()
    if (mails.isEmpty()) return
    val s = remember(mails) { Codes3912.summary() }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "邮件摘要" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("摘要 · 今日 ${s.today} 封", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.weight(1f))
                if (s.needAct.isNotEmpty()) StatusPill("需处理 ${s.needAct.size}", false)
            }
            Text(s.line, fontSize = MT.Sub, color = m.ink, modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────────── 消息 · 验证码 (ws2 ①) ─────────────────────────────

@Composable
fun CodeInboxPane() {
    val m = mines()
    val ctx = LocalContext.current
    val mails by MailInbox.mails.collectAsState()
    remember { com.openminis.app.plus.AutoLogin.init(ctx); 0 }
    val pol by com.openminis.app.plus.AutoLogin.policy.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000L); tick++ } }   // fresh → 已过期 without a reload
    val codes = remember(mails, tick) { Codes3912.all(mails) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    var copied by remember { mutableStateOf<String?>(null) }
    Column {
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            PlusRow(ic2("key-round"), "验证码自动填入", if (pol.codeAutoRead) "登录时自动从已关联邮箱读取并填入" else "已关闭，需要时手动复制", chevron = false,
                trailing = { Switch(pol.codeAutoRead, onCheckedChange = { com.openminis.app.plus.AutoLogin.setCodeAutoRead(ctx, it) }) })
        }
        if (codes.isEmpty()) InfoLine(if (mails.isEmpty()) "还没有关联邮箱。在「邮箱」里添加后，收到的验证码会出现在这里。" else "最近 24 小时的邮件里没有验证码。")
        codes.forEach { c ->
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = "验证码 ${c.site}" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF4F7FB5)), contentAlignment = Alignment.Center) {
                            Text(c.site.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = MT.Body)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.site + " 验证码", fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Inbox3912Core.maskMail(c.box) + " · " + (if (c.fresh) "30 分钟内有效" else "可能已过期"), fontSize = MT.Meta, color = m.ink2, maxLines = 1)
                        }
                        Text(fmt.format(java.util.Date(c.at)), fontSize = MT.Meta, color = m.ink2)
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Inbox3912Core.spaced(c.code), fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            color = if (c.fresh) Color(0xFFC0633F) else m.ink3, modifier = Modifier.weight(1f))
                        Text(if (copied == c.mailId) "已复制" else "复制", fontSize = MT.Sub, color = m.primaryText,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(m.container).clickable {
                                copy(ctx, "验证码", c.code); copied = c.mailId
                            }.padding(horizontal = 14.dp, vertical = 6.dp).semantics { contentDescription = "复制验证码" })
                    }
                }
            }
        }
        Text("只读取验证码本身，邮件正文不保存。复制的验证码在系统剪贴板里标记为敏感内容。", fontSize = MT.Meta, color = m.ink3,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

// ───────────────────────────── 注册台账 (ws2 ③) ─────────────────────────────

object Register3912 {
    @Volatile var lastRows: Int = -1
    @Volatile var lastTemp: Int = -1
    fun rows(c: android.content.Context): List<Inbox3912Core.RegRow> = runCatching {
        com.openminis.app.plus.SavedLogins.all(c).map { e ->
            val host = e.host.ifBlank { e.name }
            val note = runCatching { com.openminis.app.plus.Ledger.cands(c, host).firstOrNull { it.account.equals(e.user, true) }?.note }.getOrNull().orEmpty()
            Inbox3912Core.RegRow(host, e.user, note, Inbox3912Core.regStatus(note))
        }
    }.getOrDefault(emptyList())
}

@Composable
fun RegisterLedgerScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Inbox3912Core.RegRow>?>(null) }
    var boxes by remember { mutableStateOf(com.openminis.app.plus.AgentMailbox.boxes()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) { Register3912.rows(ctx) }
        Register3912.lastRows = rows?.size ?: 0; Register3912.lastTemp = boxes.size
    }
    val all = rows.orEmpty()
    val groups = remember(all) { Inbox3912Core.byMailbox(all) }
    val temp = com.openminis.app.plus.AgentMailbox.address("mailtm")
    // [Mines-38.9.12.1] the user's own domain (unlimited addresses) is the preferred source when connected
    val selfDom = remember { if (com.openminis.app.plus.AgentMailbox.ready("selfhost")) com.openminis.app.plus.AgentMailbox.selfDomain() else null }
    var useSelf by remember { mutableStateOf(selfDom != null) }
    PlusPage("注册台账", onBack, actions = {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { pick = true }.semantics { contentDescription = "一键注册" }, contentAlignment = Alignment.Center) {
            MinesIcon(ic2("plus"), null, tint = m.ink, modifier = Modifier.size(20.dp))
        }
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile3912("临时邮箱", if (selfDom != null) "∞" else "${boxes.size}", if (selfDom != null) "自建域名 @$selfDom" else if (temp != null) "Mail.tm 可用" else "未创建", Modifier.weight(1f))
            Tile3912("已注册平台", if (rows == null) "…" else "${all.map { it.host }.distinct().size}",
                "已验证 ${all.count { it.st == Inbox3912Core.RegSt.DONE }} · 未完成 ${all.count { it.st == Inbox3912Core.RegSt.PENDING }}", Modifier.weight(1f))
        }
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("一键注册", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
                    Text(busy ?: (if (temp == null && selfDom == null) "新建临时邮箱" else "开始注册"), fontSize = MT.Sub, color = Color(0xFFC0633F),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22C0633F)).clickable(enabled = busy == null) {
                            if (temp != null || selfDom != null) { pick = true; return@clickable }
                            busy = "正在创建…"
                            scope.launch {
                                val why = com.openminis.app.plus.AgentMailbox.openMailTm()
                                boxes = com.openminis.app.plus.AgentMailbox.boxes()
                                busy = null
                                if (why == null) pick = true else android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }.padding(horizontal = 12.dp, vertical = 5.dp))
                }
                Text("选择平台 → 生成邮箱与密码 → 自动收码 → 写入台账", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 6.dp))
                Text("遵守平台规则：一人一号，不批量注册；遇到人机验证、手机号或付款时停下来问你。", fontSize = MT.Meta, color = m.ink3, modifier = Modifier.padding(top = 4.dp))
            }
        }
        WarmGroupLabel("按邮箱", "全部 ›") { go(PlusRoutes.KEYCHAIN) }
        if (rows != null && all.isEmpty()) InfoLine("台账里还没有账号。一键注册完成的账号会自动出现在这里。")
        groups.take(8).forEach { (box, list) ->
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Inbox3912Core.maskMail(box), fontSize = MT.Sub, fontFamily = FontFamily.Monospace, color = m.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        val tempBox = box.endsWith("@" + (temp?.substringAfter('@') ?: "\u0000"))
                        StatusPill(if (tempBox) "临时" else "长期", null)
                    }
                    list.take(6).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFD9822F)), contentAlignment = Alignment.Center) {
                                Text(r.host.take(2).uppercase(), color = Color.White, fontSize = MT.Meta, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(r.host, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("密码 ••••••" + (r.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(r.st.zh, when (r.st) { Inbox3912Core.RegSt.DONE -> true; Inbox3912Core.RegSt.PENDING, Inbox3912Core.RegSt.ROTATE -> false; else -> null })
                        }
                    }
                }
            }
        }
    }
    if (pick) AlertDialog(onDismissRequest = { pick = false }, confirmButton = { TextButton({ pick = false }) { Text("取消") } },
        title = { Text("选择要注册的平台") },
        text = {
            Column {
                // [Mines-38.9.12.1] mailbox source: 自建域名 (a fresh address per platform) or the Mail.tm box
                if (selfDom != null) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "自建域名", false to (if (temp != null) "Mail.tm" else "台账邮箱")).forEach { (v, label) ->
                        Text(label, fontSize = MT.Sub, color = if (useSelf == v) Color.White else m.ink,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (useSelf == v) Color(0xFFC0633F) else Color(0x14000000))
                                .clickable { useSelf = v }.padding(horizontal = 12.dp, vertical = 5.dp).semantics { contentDescription = "邮箱来源 $label" })
                    }
                }
                Inbox3912Core.PLATFORMS.forEach { (name, url) ->
                    Text(name, fontSize = MT.Body, color = m.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        pick = false
                        if (selfDom != null && useSelf) scope.launch {
                            busy = "正在建邮箱…"
                            val addr = com.openminis.app.plus.AgentMailbox.selfNewAddress(com.openminis.app.plus.SelfMailCore.prefixFor(name, (System.currentTimeMillis() / 1000).toInt()))
                            busy = null
                            if (addr == null) android.widget.Toast.makeText(ctx, "自建域名没建出邮箱，先用台账邮箱", android.widget.Toast.LENGTH_LONG).show()
                            startDraft(ctx, if (addr != null) Inbox3912Core.registerTaskSelf(name, url, addr, emailOnlyReg(ctx)) else Inbox3912Core.registerTask(name, url, temp, emailOnlyReg(ctx)))
                        } else startDraft(ctx, Inbox3912Core.registerTask(name, url, temp, emailOnlyReg(ctx)))
                    }.padding(vertical = 10.dp, horizontal = 6.dp))
                }
            }
        })
}

/** [Mines-38.9.13 handoff_v2.oauth] the one-click sign-up task asks for an e-mail sign-up, never Google / Apple */
private fun emailOnlyReg(ctx: android.content.Context): Boolean { com.openminis.app.plus.HandoffFlags.init(ctx); return com.openminis.app.plus.HandoffFlags.on(com.openminis.app.plus.HandoffFlags.OAUTH) }

/** a new Mines conversation with [text] waiting in the input (same hand-off as 知识库 「在对话中引用」) */
internal fun startDraft(ctx: android.content.Context, text: String) {
    runCatching {
        com.openminis.app.share.SharedShareStore.savePendingShare(ctx, com.openminis.app.share.PendingShare(
            listOf(com.openminis.app.share.PendingShare.Item(com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT, text)), System.currentTimeMillis()))
        com.openminis.app.share.ShareCoordinator.processPendingShare(ctx)
    }
    PlusNav.go(com.openminis.app.ui.navigation.Routes.chat("__new__" + java.util.UUID.randomUUID()))
}

@Composable
private fun Tile3912(label: String, big: String, sub: String, modifier: Modifier) {
    val m = mines()
    MinesCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, fontSize = MT.Sub, color = m.ink2)
            Text(big, fontSize = MT.Display, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(top = 6.dp))
            Text(sub, fontSize = MT.Meta, color = m.ink2, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────────── 服务器快速连接 (ws2 ④) ─────────────────────────────

object Connect3912 {
    private fun p(c: android.content.Context) = c.applicationContext.getSharedPreferences("mines_connect", android.content.Context.MODE_PRIVATE)
    fun sshFallback(c: android.content.Context) = p(c).getBoolean("ssh_fallback", true)
    fun setSshFallback(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("ssh_fallback", v).apply()
    fun keepAlive(c: android.content.Context) = p(c).getBoolean("keepalive", true)
    fun setKeepAlive(c: android.content.Context, v: Boolean) = p(c).edit().putBoolean("keepalive", v).apply()
    @Volatile var lastConnect: String = ""

    /** one tap: WS link first (mines-link worker, 15 s heartbeat); no link / link down → SSH (only when the fallback is on) */
    suspend fun connect(c: android.content.Context, s: com.openminis.app.plus.VpsServer): String = withContext(Dispatchers.IO) {
        com.openminis.app.plus.MinesLink.init(c)
        val t0 = System.currentTimeMillis()
        val conn = runCatching { com.openminis.app.plus.MinesLink.conn(s) }.getOrNull()
        if (conn != null) {
            runCatching { conn.kick() }
            repeat(16) { if (com.openminis.app.plus.MinesLink.isOnline(s.id)) return@withContext "WS 已连接 · ${System.currentTimeMillis() - t0} ms".also { lastConnect = it }; kotlinx.coroutines.delay(250) }
        }
        if (!sshFallback(c)) return@withContext (if (conn == null) "这台还没开 WS 通道" else "WS 暂时连不上").also { lastConnect = it }
        val r = runCatching { com.openminis.app.plus.VpsSsh.run(c, s, "echo ok", 15_000, 1) }.getOrNull()
        (if (r?.first?.exit == 0) "SSH 已连接 · ${System.currentTimeMillis() - t0} ms" else "连不上：" + (r?.first?.output?.lines()?.lastOrNull { it.isNotBlank() }?.take(40) ?: "网络不通")).also { lastConnect = it }
    }
}

@Composable
fun QuickConnectScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    PlusPrefs.init(ctx)
    remember { com.openminis.app.plus.MinesLink.init(ctx); 0 }
    val st by PlusPrefs.state.collectAsState()
    val links by com.openminis.app.plus.MinesLink.states.collectAsState()
    var unlocked by remember { mutableStateOf(com.openminis.app.plus.PayCards.isUnlocked()) }
    var fallback by remember { mutableStateOf(Connect3912.sshFallback(ctx)) }
    var keep by remember { mutableStateOf(Connect3912.keepAlive(ctx)) }
    val status = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val servers = st.servers.filter { it.enabled }
    fun withKey(then: () -> Unit) {
        if (com.openminis.app.plus.PayCards.isUnlocked()) { unlocked = true; then(); return }
        com.openminis.app.plus.PayCards.unlock(ctx, "解锁钥匙服务") { ok, why -> unlocked = ok; if (ok) then() else why?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() } }
    }
    PlusPage("连接", onBack, actions = {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { go(PlusRoutes.WORK) }.semantics { contentDescription = "添加服务器" }, contentAlignment = Alignment.Center) {
            MinesIcon(ic2("plus"), null, tint = m.ink, modifier = Modifier.size(20.dp))
        }
    }) {
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), onClick = { withKey {} }) {
            PlusRow(ic2("key-round"), "钥匙服务", "手机硬件密钥 · 指纹解锁 · ${servers.count { it.authorized }} 台已授权", chevron = false,
                trailing = { StatusPill(if (unlocked) "已解锁" else "就绪", true) })
        }
        WarmGroupLabel("一键连接", if (keep) "默认 WS 通道 · 15 秒心跳" else "默认 WS 通道")
        if (servers.isEmpty()) InfoLine("还没有服务器。点右上角 ＋ 添加。")
        if (servers.isNotEmpty()) MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                servers.forEachIndexed { i, s ->
                    if (i > 0) WarmDivider(16.dp)
                    val link = links[s.id]?.first
                    val ws = s.linkToken.isNotBlank() || s.relay.startsWith("ws")
                    val route = when {
                        link == com.openminis.app.plus.MinesLink.State.ONLINE -> "wss 在线"
                        ws -> "wss" + (s.linkUrl.substringAfterLast(':', "").takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { " :$it" } ?: "")
                        else -> "ssh :${s.port}"
                    }
                    Row(Modifier.fillMaxWidth().clickable { status[s.id] = "连接中…"; scope.launch { status[s.id] = Connect3912.connect(ctx, s) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = "连接 ${s.name}" }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { s.host }, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(route + (if (s.lastLatencyMs >= 0) " · 延迟 ${s.lastLatencyMs} ms" else if (s.lastLatencyMs == -2) " · 不可达" else "") +
                                (status[s.id]?.let { " · $it" } ?: ""), fontSize = MT.Meta, color = m.ink2, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Chip3912("终端") {
                            withKey {
                                val entry = "${s.user}@${s.host}:${s.port}" + (s.relay.trim().takeIf { it.isNotEmpty() }?.let { "|$it" } ?: "")
                                go(com.openminis.app.ui.navigation.Routes.terminal("MINIS_VPS_SERVERS='$entry' minis-vps shell"))
                            }
                        }
                        Chip3912("详情") { go(PlusRoutes.server(s.id)) }
                    }
                }
            }
        }
        WarmGroupLabel("新服务器")
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("首次接入", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                Text("输入 IP 与 root 密码一次 → 下发钥匙 → 开启 WS 通道。之后一键连接走 WS，SSH 只用于初次接入和备用。", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFD06A3A)).clickable { go(PlusRoutes.WORK) }
                    .padding(vertical = 12.dp).semantics { contentDescription = "添加服务器" }, contentAlignment = Alignment.Center) {
                    Text("添加服务器", color = Color.White, fontSize = MT.Body, fontWeight = FontWeight.Medium)
                }
            }
        }
        PlusSection(null) {
            SwitchRow("断线自动重连", "WS 失效时回落 SSH", fallback) { fallback = it; Connect3912.setSshFallback(ctx, it) }
            WarmDivider(16.dp)
            SwitchRow("连接保活", "15 秒心跳", keep) { keep = it; Connect3912.setKeepAlive(ctx, it) }
        }
    }
}

@Composable
private fun Chip3912(label: String, onClick: () -> Unit) {
    Text(label, fontSize = MT.Meta, color = Color(0xFFC0633F), modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
        .background(Color(0x22C0633F)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp).semantics { contentDescription = label })
}

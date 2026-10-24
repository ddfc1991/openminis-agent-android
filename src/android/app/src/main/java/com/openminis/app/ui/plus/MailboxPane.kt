package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.MailInbox
import kotlinx.coroutines.launch

/** [Mines-38.8] 消息 → 「邮箱」 tab key. */
const val MAIL_TAB = "MAIL"

/**
 * [Mines-38.8] 消息 → 邮箱: every signed-in mailbox in one frosted-glass inbox (user 2026-10-15: Gmail + several 163,
 * 毛玻璃, no grid). Account chips → one grouped glass card per day group → detail sheet. Add = real IMAP login first.
 */
@Composable
fun MailboxPane(refreshTick: Int, onOpenWeb: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    val accounts by MailInbox.accounts.collectAsState()
    val mails by MailInbox.mails.collectAsState()
    val states by MailInbox.states.collectAsState()
    val busy by MailInbox.busy.collectAsState()
    var sel by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<MailInbox.Mail?>(null) }
    var adding by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<MailInbox.Account?>(null) }
    LaunchedEffect(refreshTick) { if (refreshTick == 0) MailInbox.migrateTypos(ctx); MailInbox.load(ctx); MailInbox.refresh(ctx, force = refreshTick > 0) }

    Column(Modifier.fillMaxWidth()) {
        if (accounts.isEmpty()) {
            EmptyMailbox(onAdd163 = { adding = true }, onGmail = { gmailLogin() })
        } else {
            // account chips: 全部 + one per mailbox (unread count; a red dot when the box needs attention)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MailChip("全部", MailInbox.unreadTotal(), sel == null, false) { sel = null }
                accounts.forEach { a ->
                    val st = states[a.id]
                    MailChip(shortAddr(a), st?.unread ?: 0, sel == a.id, st?.st == MailInbox.St.AUTH || st?.st == MailInbox.St.NET,
                        onLong = { manage = a }) { sel = if (sel == a.id) null else a.id }
                }
            }
            // [Mines-38.9.1] one-shot note after an automatic address fix (e.g. one extra digit typed on the phone)
            var note by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(accounts, busy) { com.openminis.app.plus.AddressFix.fixedNote?.let { note = it; com.openminis.app.plus.AddressFix.fixedNote = null } }
            note?.let { n -> GlassStrip(text = n, danger = !n.startsWith("已把"), action = "知道了") { note = null } }
            // problems: one glass strip per broken mailbox, tap = fix (never shown as connected)
            accounts.filter { a -> states[a.id]?.st == MailInbox.St.AUTH || states[a.id]?.st == MailInbox.St.NET }
                .filter { sel == null || it.id == sel }.forEach { a ->
                    val st = states[a.id]!!
                    GlassStrip(text = "${shortAddr(a)}：${st.msg}", danger = st.st == MailInbox.St.AUTH,
                        action = if (st.st == MailInbox.St.AUTH) (if (a.id == "gmail_web") "重新登录" else "重新填写") else "重试") {
                        when {
                            st.st != MailInbox.St.AUTH -> scope.launch { MailInbox.refresh(ctx, force = true) }
                            a.id == "gmail_web" -> gmailLogin()
                            else -> { manage = null; adding = true; MailAddPrefill.prefill = a.address }
                        }
                    }
                }
            val shown = mails.filter { sel == null || it.accountId == sel }
            val checking = states.values.any { it.st == MailInbox.St.CHECKING } || busy
            if (shown.isEmpty()) {
                Text(if (checking) "正在读收件箱…" else "收件箱里暂时没有邮件，下拉刷新", fontSize = MT.Caption, color = m.ink2,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
            }
            val now = System.currentTimeMillis()
            val startOfDay = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
            val groups = shown.take(80).groupBy { when { it.at >= startOfDay -> "今天"; now - it.at < 7 * 86_400_000L -> "本周"; else -> "更早" } }
            listOf("今天", "本周", "更早").forEach { g ->
                val list = groups[g] ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.weight(1f))
                    if (g == groups.keys.first()) Text(if (checking) "正在读…" else "只读 · 不会标成已读", fontSize = MT.Meta, color = m.ink3)
                }
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(MS.CardRadius), elevation = 3.dp) {
                    Column {
                        list.forEachIndexed { i, mail ->
                            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(m.hairline))
                            MailRow(mail, showAccount = sel == null && accounts.size > 1) { open = mail }
                        }
                    }
                }
            }
        }
        // 「＋ 添加邮箱」: faint row at the end, like 「＋ 添加来源」
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(14.dp))
            .clickable { MailAddPrefill.prefill = null; adding = true }.heightIn(min = 44.dp).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "添加邮箱"; role = Role.Button },
            verticalAlignment = Alignment.CenterVertically) {
            Text("＋ 添加邮箱", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.primaryText)
        }
    }

    open?.let { mail -> MailDetail(mail, onDismiss = { open = null }, onOpenWeb = onOpenWeb) }
    if (adding) MailAdd(onDismiss = { adding = false }, onOpenWeb = onOpenWeb, onAdded = { adding = false; scope.launch { MailInbox.refresh(ctx, force = true) } })
    manage?.let { a ->
        MinesSheet(onDismiss = { manage = null }) {
            Text(a.address, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text("${a.provider} · ${a.how}", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            GlassMenuRow("重新读一次", WarmIcons.of("refresh-cw")) { manage = null; scope.launch { MailInbox.refresh(ctx, force = true) } }
            if (a.id != "gmail_web") GlassMenuRow("换授权码", WarmIcons.of("key-round")) { manage = null; MailAddPrefill.prefill = a.address; adding = true }
            if (a.id != "gmail_web") GlassMenuRow("从邮箱里移除", WarmIcons.of("trash"), danger = true) { manage = null; MailInbox.remove(ctx, a.id); if (sel == a.id) sel = null }
        }
    }
}

private fun gmailLogin() {
    // the Gmail connection's own in-app sign-in page (own cookie store, Google's account page — never an external browser)
    PlusNav.openLogin = "gmail"; PlusNav.go(PlusRoutes.CONNECTIONS)
}

private fun shortAddr(a: MailInbox.Account): String {
    if (!a.address.contains('@')) return a.address
    val local = a.address.substringBefore('@')
    val dom = a.address.substringAfter('@')
    return (if (local.length > 12) local.take(11) + "…" else local) + "@" + dom.substringBefore('.')
}

private fun providerTint(p: String): Pair<Color, Color> = when (p) {   // (letter, tile) — warm family, no neon
    "Gmail" -> Color(0xFFB3362B) to Color(0x1FB3362B)
    "163", "126", "yeah" -> Color(0xFFB5532F) to Color(0x1FB5532F)
    "QQ" -> Color(0xFF2F6FB5) to Color(0x1F2F6FB5)
    else -> Color(0xFF6B5E55) to Color(0x1F6B5E55)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MailChip(label: String, count: Int, on: Boolean, alert: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    Row(Modifier.heightIn(min = 40.dp).clip(shape).background(if (on) m.ink else minesGlassFill(strong = true))
        .border(1.dp, if (on) Color.Transparent else m.glassBorder, shape)
        .combinedClickable(onClick = onClick, onLongClick = onLong)
        .semantics(mergeDescendants = true) { contentDescription = if (count > 0) "$label，$count 封未读" else label; role = Role.Tab }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (alert) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger)); Spacer(Modifier.width(5.dp)) }
        Text(label, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = if (on) m.bg else m.ink, maxLines = 1, softWrap = false)
        if (count > 0) { Spacer(Modifier.width(4.dp)); Text(count.toString(), fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = if (on) m.bg.copy(alpha = 0.85f) else m.ink2, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun GlassStrip(text: String, danger: Boolean, action: String, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(shape).background(glassVeil()).border(1.dp, m.glassBorder, shape)
        .clickable(onClick = onClick).heightIn(min = 48.dp).padding(start = 14.dp, end = 6.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$text，$action"; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (danger) (if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) else m.ink3))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(action, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun MailRow(mail: MailInbox.Mail, showAccount: Boolean, onClick: () -> Unit) {
    val m = mines()
    val (fg, tile) = providerTint(mail.provider)
    val name = mail.from.ifBlank { mail.fromAddr.ifBlank { "（未知发件人）" } }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 84.dp).padding(horizontal = 14.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) { contentDescription = (if (mail.unread) "未读，" else "") + "$name：${mail.subject}" },
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tile), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mail.unread) { Box(Modifier.size(8.dp).clip(CircleShape).background(m.primary)); Spacer(Modifier.width(6.dp)) }
                Text(name, fontSize = MT.Body, fontWeight = if (mail.unread) FontWeight.SemiBold else FontWeight.Medium, color = m.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(mailTime(mail.at), fontSize = MT.Meta, color = m.ink3, maxLines = 1, softWrap = false)
            }
            Text(mail.subject, fontSize = MT.Body, lineHeight = MT.BodyTightLH, color = if (mail.unread) m.ink else m.ink2,
                fontWeight = if (mail.unread) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val snip = mail.text.replace(Regex("\\s+"), " ").trim()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showAccount) {
                    Text(mail.provider, fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = fg, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tile).padding(horizontal = 5.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(snip.ifBlank { mail.address }, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun mailTime(t: Long): String {
    if (t <= 0) return ""
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    return when {
        c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) ->
            String.format(java.util.Locale.ROOT, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        System.currentTimeMillis() - t < 7 * 86_400_000L -> "周" + "日一二三四五六"[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        else -> "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日"
    }
}

@Composable
private fun EmptyMailbox(onAdd163: () -> Unit, onGmail: () -> Unit) {
    val m = mines()
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(MS.CardRadius), elevation = 3.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("把你的邮箱都放到这里", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text("Gmail 和几个 163 邮箱合成一个收件箱。Mines 只读收件箱：不发信、不删信、不会标成已读。",
                fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassPill("添加 163 / QQ 邮箱", onClick = onAdd163)
                GlassSecondaryPill("登录 Gmail", onClick = onGmail)
            }
        }
    }
}

@Composable
private fun MailDetail(mail: MailInbox.Mail, onDismiss: () -> Unit, onOpenWeb: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    MinesSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(mail.subject, fontSize = MT.PageTitle, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text(listOf(mail.from, mail.fromAddr.takeIf { it.isNotBlank() && it != mail.from }?.let { "<$it>" }).filterNotNull().joinToString(" "),
                fontSize = MT.Sub, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("收件：${mail.address} · ${mailTime(mail.at)}", fontSize = MT.Meta, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(m.hairline))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(mail.text.ifBlank { "（这封邮件没有文字内容）" }.take(6000), fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                mail.url?.let { u -> GlassPill("在 Mines 里打开网页版") { onDismiss(); onOpenWeb(u) } }
                GlassSecondaryPill("复制正文") {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("邮件", mail.subject + "\n\n" + mail.text))
                    android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** Add a mailbox: address + 授权码 → real IMAP login → saved only when it worked. */
object MailAddPrefill { @Volatile var prefill: String? = null }

@Composable
private fun MailAdd(onDismiss: () -> Unit, onOpenWeb: (String) -> Unit, onAdded: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // [Mines-38.9.1] prefill from the identity store (a mailbox the user has that is not added yet), never a fixed value;
    // a stored near-typo is replaced by the address it is a typo of
    var addr by remember { mutableStateOf(run {
        val known = MailInbox.knownAddresses(ctx)
        val p = MailAddPrefill.prefill.orEmpty()
        com.openminis.app.plus.AddressFix.candidates(p, known).firstOrNull() ?: p.ifBlank {
            val have = MailInbox.accounts.value.map { it.address.lowercase() }.toSet()
            known.firstOrNull { it.lowercase() !in have }.orEmpty()
        }
    }) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val prov = MailInbox.providerOf(addr)
    val isGmail = prov == "Gmail"
    MinesSheet(onDismiss = { if (!busy) onDismiss() }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("添加邮箱", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text(if (isGmail) "Gmail 最简单：在 Mines 内登录一次就行。也可以填 Google 的「应用专用密码」走 IMAP。"
                 else "163 / 126 / QQ 邮箱要用「授权码」登录（不是邮箱密码）：网页版 设置 → POP3/SMTP/IMAP → 开启 IMAP → 生成授权码。",
                fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
            // [Mines-38.9.11 b] one step at a time instead of a paragraph of instructions
            if (isGmail || addr.isBlank()) Text("一步步教我建应用专用密码 ›", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { openAppPasswordGuide(ctx, addr); onDismiss() }.padding(vertical = 6.dp))
            val fc = OutlinedTextFieldDefaults.colors(focusedBorderColor = m.primary, unfocusedBorderColor = m.glassBorder,
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = m.primary,
                focusedLabelColor = m.primaryText, unfocusedLabelColor = m.ink2, focusedTextColor = m.ink, unfocusedTextColor = m.ink)
            OutlinedTextField(addr, { addr = it.trim(); err = null }, label = { Text("邮箱地址") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(14.dp), colors = fc,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it; err = null }, label = { Text(if (isGmail) "应用专用密码（16 位）" else "授权码") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(14.dp), colors = fc, modifier = Modifier.fillMaxWidth())
            err?.let { Text(it, fontSize = MT.Sub, lineHeight = MT.SubLH, color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = m.primary)
                Spacer(Modifier.width(10.dp))
                Text("正在登录 ${com.openminis.app.plus.MailImap.hostFor(addr) ?: "邮箱"}…", fontSize = MT.Sub, color = m.ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassPill("验证并添加", enabled = !busy && addr.contains('@') && code.isNotBlank()) {
                    busy = true; err = null
                    scope.launch {
                        val r = MailInbox.verifyAndAdd(ctx, addr, code)
                        busy = false
                        if (r == null) { android.widget.Toast.makeText(ctx, "已连接 $addr", android.widget.Toast.LENGTH_SHORT).show(); MailAddPrefill.prefill = null; onAdded() }
                        else err = r
                    }
                }
                if (isGmail) GlassTextButton("在 Mines 内登录 Gmail") { onDismiss(); gmailLogin() }
                else MailInbox.webUrl(addr.ifBlank { "x@163.com" })?.let { u ->
                    GlassTextButton("去网页拿授权码") { onDismiss(); onOpenWeb(u) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

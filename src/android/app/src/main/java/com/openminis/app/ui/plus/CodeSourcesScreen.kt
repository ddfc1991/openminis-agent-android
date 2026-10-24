package com.openminis.app.ui.plus

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.browser.GoogleAuthRouter
import com.openminis.app.plus.AgentMailbox
import com.openminis.app.plus.CodeSources
import com.openminis.app.plus.CodeSourcesCore
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import kotlinx.coroutines.launch

/**
 * [Mines-38.9.5 i] 「验证码与 AI 邮箱」 (账号与 AI 服务 › 账号). user 19:34–19:36:
 *  - 验证码来源 (default): the user's own linked mailboxes, one switch each; Mines reads only recent mail of the site it
 *    is signing into and takes only the code / link (never stores the mail). The last codes are shown masked.
 *  - AI 邮箱: ready-made agent mailboxes with their free limits — AgentMail (key), AGmail (key), Mail.tm (临时 · 一次性,
 *    attribution link). No paid self-run mailbox.
 *  - 高级 · 可选: 自建域名 / Cloudflare, hidden until the user opens it; shows 未开通 (no fake data).
 * Small type, short written wording (user: 不要大字, 少大白话).
 */
@Composable
fun CodeSourcesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    val boxes = remember(rev) { runCatching { CodeSources.all(ctx) }.getOrDefault(emptyList()) }
    val seen by CodeSources.seen.collectAsState()
    var keyFor by remember { mutableStateOf<CodeSourcesCore.Provider?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var mailTmErr by remember { mutableStateOf<String?>(null) }
    var showAdv by remember { mutableStateOf(AgentMailbox.ready("selfhost")) }
    var selfOpen by remember { mutableStateOf(false) }   // [38.9.12.1] 自建域名 开通 dialog
    // [38.9.11.1] 打开官网 opens inside Mines (was a Custom Tab that failed silently on the user's phone)
    var webFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // name, url
    webFor?.let { (name, url) ->
        androidx.activity.compose.BackHandler { webFor = null }
        val kind = remember(url) { com.openminis.app.plus.ConnectionKind("aimail:" + name.lowercase(), name, "", "", 0, com.openminis.app.plus.ConnMethod.WEB, loginUrl = url) }
        WebLoginScreen(kind = kind, url = url, title = name, detect = false,
            onDone = { _, _ -> webFor = null; CodeSourcesCore.PROVIDERS.firstOrNull { it.name == name && it.needsKey && !it.down }?.let { keyFor = it } },
            onClose = { webFor = null; CodeSourcesCore.PROVIDERS.firstOrNull { it.name == name && it.needsKey && !it.down }?.let { keyFor = it } })
        return
    }

    PlusPage("验证码与 AI 邮箱", onBack) {
        PlusSection("验证码来源", footer = "登录需要验证码时，只读取该网站最近 10 分钟的邮件，只取验证码或登录链接，不保存邮件内容。") {
            val own = boxes.filter { !it.first.id.startsWith("ai:") }
            if (own.isEmpty()) PlusRow(MinesIcons.named("mail"), "暂无关联邮箱", "在「账号」中添加 Gmail（应用专用密码）或 163 / QQ（授权码）", chevron = false)
            // [Mines-38.9.11 b] Gmail 验证码 via a Google app password, guided one step at a time
            if (own.none { it.first.label.contains("Gmail", true) }) PlusRow(MinesIcons.named("mail"), "用 Gmail 收验证码", "一步步建 Google 应用专用密码（不需要 Cloudflare）",
                onClick = { openAppPasswordGuide(ctx) })
            own.forEachIndexed { i, (b, on) ->
                if (i > 0) CsDivider()
                PlusRow(MinesIcons.named("mail"), b.label, b.how, chevron = false, trailing = {
                    MinesSwitch(on, (if (on) "关闭 " else "打开 ") + b.label + " 作为验证码来源") { v -> CodeSources.setOn(b.id, v); rev++ }
                })
            }
        }
        if (seen.isNotEmpty()) PlusSection("最近读取") {
            seen.forEachIndexed { i, s ->
                if (i > 0) CsDivider()
                PlusRow(MinesIcons.named("key-round"), "${s.site} · ${s.masked}", "${s.source} · ${csAgo(s.at)}", chevron = false)
            }
        }

        PlusSection("AI 邮箱", footer = "供 Mines 注册网站时收验证码，均为免费档。在官网注册后填入 API Key，Key 加密存在本机。") {
            val list = CodeSourcesCore.PROVIDERS.filter { !it.advanced }
            list.forEachIndexed { i, p ->
                if (i > 0) CsDivider()
                CsProviderRow(p, rev, busy == p.id, if (p.id == "mailtm") mailTmErr else null,
                    onOpen = { webFor = p.name to p.url },
                    onKey = { keyFor = p },
                    onOpenMailTm = {
                        busy = p.id
                        scope.launch {
                            val why = AgentMailbox.openMailTm(); busy = null; rev++
                            mailTmErr = why
                            Toast.makeText(ctx, why?.let { "没开通：$it" } ?: "已开通：${AgentMailbox.address("mailtm")}", Toast.LENGTH_LONG).show()
                        }
                    },
                    onDisconnect = { AgentMailbox.disconnect(p.id); rev++ },
                )
            }
            Text("Mail.tm 临时邮箱由 mail.tm 提供", fontSize = MT.Meta, color = m.primaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { webFor = "Mail.tm" to "https://mail.tm" }
                    .semantics { contentDescription = "打开 mail.tm 官网" })
        }

        Row(Modifier.fillMaxWidth().clickable { showAdv = !showAdv }.padding(horizontal = 26.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("高级 · 可选", fontSize = MT.Meta, color = m.ink2, modifier = Modifier.weight(1f))
            MinesIcon(MinesIcons.named(if (showAdv) "chevron-up" else "chevron-down")!!, if (showAdv) "收起高级选项" else "展开高级选项", tint = m.ink3, modifier = Modifier.size(16.dp))
        }
        if (showAdv) PlusSection(null, footer = "需要自己的域名和 Cloudflare 账号（收件服务部署在你的 Cloudflare 上）；多数用户不需要。") {
            CodeSourcesCore.PROVIDERS.filter { it.advanced }.forEach { p ->
                // [Mines-38.9.12.1] the user's own inbox API on Cloudflare: 开通 (URL + token) / domain / 断开
                CsProviderRow(p, rev, busy == p.id, null, onOpen = { webFor = p.name to p.url }, onKey = { selfOpen = true },
                    onOpenMailTm = {}, onDisconnect = { AgentMailbox.disconnect(p.id); rev++ })
            }
        }
        Spacer(Modifier.size(24.dp))
    }

    if (selfOpen) {
        var url by remember { mutableStateOf(AgentMailbox.selfConfig()?.base.orEmpty()) }
        var token by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { selfOpen = false },
            title = { Text("开通自建域名收件", fontSize = MT.PageTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("填收件服务的地址和令牌（部署在你的 Cloudflare 上）。令牌只存进保险箱。", fontSize = MT.Sub, color = m.ink2)
                    OutlinedTextField(url, { url = it.trim(); err = null }, singleLine = true,
                        placeholder = { Text("https://…", fontSize = MT.Sub) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it.trim(); err = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("令牌", fontSize = MT.Sub) }, modifier = Modifier.fillMaxWidth())
                    Text("从剪贴板粘贴", fontSize = MT.Sub, color = m.primaryText, modifier = Modifier.clickable {
                        val clip = runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                        val cfg = com.openminis.app.plus.SelfMailCore.parseConfig(clip)
                        if (cfg != null) { url = cfg.base; token = cfg.token; err = null } else err = "剪贴板里没有 mines-mail:// 链接或「https 地址 + 令牌」"
                    }.padding(vertical = 6.dp).semantics { contentDescription = "从剪贴板粘贴" })
                    err?.let { Text(it, fontSize = MT.Sub, color = toneColors(Tone.DANGER).first) }
                }
            },
            confirmButton = {
                TextButton(enabled = busy == null, onClick = {
                    busy = "selfhost"
                    scope.launch {
                        val why = AgentMailbox.connectSelfHost(url, token); busy = null
                        if (why == null) { selfOpen = false; rev++; Toast.makeText(ctx, "已开通：@${AgentMailbox.selfDomain()}", Toast.LENGTH_SHORT).show() } else err = why
                    }
                }) { Text(if (busy == "selfhost") "验证中…" else "开通") }
            },
            dismissButton = { TextButton(onClick = { selfOpen = false }) { Text("取消") } },
        )
    }

    keyFor?.let { p ->
        var text by remember(p.id) { mutableStateOf("") }
        var err by remember(p.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { keyFor = null },
            title = { Text("${p.name} API Key", fontSize = MT.PageTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.note.ifBlank { "在官网控制台创建后粘贴" }, fontSize = MT.Sub, color = m.ink2)
                    OutlinedTextField(text, { text = it.trim(); err = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("粘贴 API Key", fontSize = MT.Sub) }, modifier = Modifier.fillMaxWidth())
                    err?.let { Text(it, fontSize = MT.Sub, color = toneColors(Tone.DANGER).first) }
                }
            },
            confirmButton = {
                TextButton(enabled = busy == null, onClick = {
                    if (!AgentMailbox.looksLikeKey(text)) { err = "格式不对"; return@TextButton }
                    if (p.id == "agentmail") {
                        busy = p.id
                        scope.launch {
                            val why = AgentMailbox.connectAgentMail(text); busy = null
                            if (why == null) { keyFor = null; rev++; Toast.makeText(ctx, "已开通：${AgentMailbox.address("agentmail")}", Toast.LENGTH_SHORT).show() } else err = why
                        }
                    } else { AgentMailbox.setKey(p.id, text); keyFor = null; rev++ }
                }) { Text(if (busy == p.id) "验证中…" else "保存") }
            },
            dismissButton = { TextButton(onClick = { keyFor = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CsProviderRow(
    p: CodeSourcesCore.Provider, rev: Int, busy: Boolean, err: String?,
    onOpen: () -> Unit, onKey: () -> Unit, onOpenMailTm: () -> Unit, onDisconnect: () -> Unit,
) {
    val m = mines()
    val ready = remember(rev) { AgentMailbox.ready(p.id) }
    val keyOnly = remember(rev) { !ready && AgentMailbox.key(p.id) != null }
    val addr = remember(rev) { AgentMailbox.address(p.id) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
            if (p.temporary) { Spacer(Modifier.width(6.dp)); CsTag("临时 · 一次性", Tone.WARNING) }
            Spacer(Modifier.weight(1f))
            when {
                p.down -> CsTag("暂不可用", Tone.WARNING)
                ready -> CsTag("已开通", Tone.SUCCESS)
                keyOnly -> CsTag("已存 Key", Tone.NEUTRAL)
                else -> CsTag("未开通", Tone.NEUTRAL)
            }
        }
        Text(p.limits, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 2.dp))
        if (ready && addr != null) Text(if (p.id == "selfhost") "@$addr · 地址不限量" else addr, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (keyOnly) Text("读码接口尚未接通（未验证）", fontSize = MT.Sub, color = m.ink3)
        if (p.down) { Text(p.note, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(top = 2.dp)); if (keyOnly) Row(Modifier.padding(top = 8.dp)) { SmallPill("断开") { onDisconnect() } }; return@Column }
        if (err != null && !ready) Text(err, fontSize = MT.Sub, color = toneColors(Tone.DANGER).first, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready || keyOnly) SmallPill("断开") { onDisconnect() }
            else if (p.id == "selfhost") SmallPill("开通") { onKey() }
            else if (p.needsKey) { SmallPill("打开官网") { onOpen() }; SmallPill("填 API Key") { onKey() } }
            else SmallPill(if (busy) "开通中…" else "开通临时邮箱") { if (!busy) onOpenMailTm() }
        }
    }
}

@Composable
private fun CsTag(text: String, tone: Tone) {
    val (fg, bg) = toneColors(tone)
    Text(text, fontSize = MT.Meta, color = fg, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun CsDivider() {
    val m = mines()
    androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(m.hairline))
}

private fun csAgo(at: Long): String {
    val s = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when { s < 60 -> "刚刚"; s < 3600 -> "${s / 60} 分钟前"; else -> "${s / 3600} 小时前" }
}

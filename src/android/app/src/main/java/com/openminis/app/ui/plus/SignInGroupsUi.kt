package com.openminis.app.ui.plus

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.CloudflareApi
import com.openminis.app.plus.KeychainCore
import com.openminis.app.plus.SignIn
import com.openminis.app.plus.SignInCore
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons

/**
 * [Mines-38.9.15] 账号台账 → 「按登录方式」: the saved accounts grouped by HOW they sign in (Google 登录 / 邮箱+密码 /
 * 手机号+验证码 / API Key …). Tapping a site sets which group + account Mines uses there (remembered; the automatic
 * sign-in tries it first). The API Key group holds the Cloudflare API token (DNS / tunnels without the dashboard).
 */
@Composable
fun SignInGroupsSection(rev: Int) {
    val ctx = LocalContext.current
    val m = mines()
    val links by SignIn.links.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val groups by produceState(emptyList<Pair<SignInCore.Group, List<SignIn.Item>>>(), rev, links, tick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { SignIn.grouped(ctx) }.getOrDefault(emptyList()) }
    }
    var open by remember { mutableStateOf(setOf<SignInCore.Group>()) }
    var pickSite by remember { mutableStateOf<String?>(null) }
    var cfToken by remember { mutableStateOf(false) }
    val hasCf = remember(tick) { CloudflareApi.token(ctx) != null }

    Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("按登录方式", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.weight(1f))
        Text(if (hasCf) "Cloudflare API 已存" else "存 Cloudflare API", fontSize = MT.Meta, color = m.primaryText,
            modifier = Modifier.clickable { cfToken = true }.padding(4.dp))
    }
    groups.forEach { (g, list) ->
        val shown = if (g in open) list else list.take(4)
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(MinesIcons.named(groupIcon(g))!!, null, tint = m.ink2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(g.zh, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink2, modifier = Modifier.weight(1f))
            Text("${list.size} 个", fontSize = MT.Meta, color = m.ink3)
        }
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
            shown.forEachIndexed { i, it ->
                val linked = links[SignInCore.linkKey(it.host)]
                val isLinked = linked != null && linked.group == g && linked.account.equals(it.account, true)
                Row(Modifier.fillMaxWidth().clickable { if (g == SignInCore.Group.API_KEY && it.host == "cloudflare.com") cfToken = true else pickSite = it.host }
                    .padding(horizontal = 16.dp, vertical = 10.dp).semantics { contentDescription = "${it.host} ${g.zh}" }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.host, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(KeychainCore.maskAccount(it.account), fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isLinked) StatusPill(if (linked!!.byUser) "你定的" else "记住了", true)
                }
                if (i < shown.size - 1) WarmDivider(16.dp)
            }
            if (list.size > shown.size) Text("还有 ${list.size - shown.size} 个", fontSize = MT.Meta, color = m.ink2,
                modifier = Modifier.fillMaxWidth().clickable { open = open + g }.padding(vertical = 12.dp, horizontal = 16.dp))
        }
    }
    pickSite?.let { site ->
        val ways = remember(site) { SignIn.waysFor(ctx, site) }
        MinesDialog("$site 用哪个方式登录", onDismiss = { pickSite = null }, dismiss = "取消", onDismissButton = { pickSite = null }) {
            Text("Mines 以后在这个网站先用你选的方式；失败两次就交给你。", fontSize = MT.Sub, color = m.ink2)
            ways.forEach { w ->
                PlusRow(null, w.group.zh, KeychainCore.maskAccount(w.account), chevron = false, onClick = {
                    SignIn.setLink(ctx, site, SignInCore.Link(w.group, w.account, byUser = true)); pickSite = null
                })
            }
            if (SignIn.link(site) != null) PlusRow(null, "不指定（下次用到时再问我）", null, chevron = false, onClick = { SignIn.setLink(ctx, site, null); pickSite = null })
        }
    }
    if (cfToken) {
        var v by remember { mutableStateOf("") }
        MinesDialog("Cloudflare API token", onDismiss = { cfToken = false }, confirm = "保存", onConfirm = {
            CloudflareApi.setToken(ctx, v); cfToken = false; tick++
        }, confirmEnabled = v.trim().length >= 20) {
            Text("在 Cloudflare 的 我的个人资料 → API 令牌 里建一个（DNS 编辑 + Tunnel 读）。存好后 Mines 改 DNS、看隧道都走 API，不再登录网页。", fontSize = MT.Sub, color = m.ink2)
            OutlinedTextField(v, { v = it.trim() }, singleLine = true, label = { Text("token") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            if (hasCf) PlusRow(null, "删除已存的 token", null, chevron = false, onClick = { CloudflareApi.setToken(ctx, null); cfToken = false; tick++ })
        }
    }
}

private fun groupIcon(g: SignInCore.Group): String = when (g) {
    SignInCore.Group.GOOGLE, SignInCore.Group.GITHUB -> "users"
    SignInCore.Group.EMAIL_PW, SignInCore.Group.EMAIL_CODE -> "mail"
    SignInCore.Group.PHONE_CODE -> "phone"
    SignInCore.Group.API_KEY -> "key-round"
    else -> "user"
}

/**
 * [Mines-38.9.15] 「选账号」 card (user 2026-10-25 12:59): several saved accounts for a site and none linked → the user taps
 * one (sign-in group + account). 「记住」 is on by default (the site's link, changeable in 账号台账 → 按登录方式).
 * Small type, no explanation paragraphs; the rows carry no secrets.
 */
@Composable
fun AccountPickHost() {
    val req by com.openminis.app.plus.AccountPick.pending.collectAsState()
    val r = req ?: return
    val m = mines()
    var keep by remember(r.id) { mutableStateOf(true) }
    MinesSheet(onDismiss = { com.openminis.app.plus.AccountPick.answer(null) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AcctLogo(r.site, r.site)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (r.task.isNotBlank()) Text(r.task, fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("选一个 ${r.site} 账号", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("有 ${r.ways.map { it.account.lowercase() }.distinct().size} 个账号，Mines 不替你挑", fontSize = MT.Caption, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            r.ways.forEachIndexed { i, w ->
                Row(Modifier.fillMaxWidth().clickable { com.openminis.app.plus.AccountPick.answer(com.openminis.app.plus.AccountPick.Answer(w, keep)) }
                    .padding(horizontal = 16.dp, vertical = 11.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "选 ${w.group.zh} ${KeychainCore.maskAccount(w.account)}" },
                    verticalAlignment = Alignment.CenterVertically) {
                    MinesIcon(MinesIcons.named(groupIcon(w.group))!!, null, tint = m.ink2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(KeychainCore.maskAccount(w.account), fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.group.zh, fontSize = MT.Sub, color = m.ink2, maxLines = 1)
                    }
                }
                if (i < r.ways.size - 1) WarmDivider(16.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 14.dp).clickable { keep = !keep }
            .semantics(mergeDescendants = true) { contentDescription = "记住这个选择" }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("记住", fontSize = MT.Body, color = m.ink)
                Text("以后 ${r.site} 都用它，可在账号台账里改", fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            androidx.compose.material3.Switch(keep, { keep = it }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = m.primary))
        }
    }
}

package com.openminis.app.ui.plus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.plus.Favicons
import com.openminis.app.plus.IdentityCore
import com.openminis.app.plus.Keychain
import com.openminis.app.plus.KeychainCore
import com.openminis.app.plus.KeychainCore.Status
import com.openminis.app.plus.MinesAutofillService
import com.openminis.app.plus.SavedLogins
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import kotlinx.coroutines.delay

/**
 * [Mines-38.9] 账号关联 keychain screens per mockups r389/mock 01 / 01d / 03 / 04 / 05: search, summary strip, groups by
 * phone / mailbox with 还有 N 个, rows with the site's real logo, 有密码 / 两步验证 icons and a status chip; the detail page
 * (masked password behind the screen lock, live TOTP ring, last login, source, 打开网站 / 改密码); the new-device sheet
 * (是我 / 不是我，帮我处理) and the 「已记下」 glass toast are hosted app-wide by [KeychainHost].
 */

private fun ic(n: String) = MinesIcons.named(n)!!

/** The site's real favicon on a white rounded tile (cached on disk); a coloured letter tile until / unless it loads. */
@Composable
fun SiteLogo(host: String, name: String, size: Dp = 40.dp, tint: Long? = null) {
    val ctx = LocalContext.current
    // [Mines-38.9.1 e] was produceState(initial, host): its state is remembered WITHOUT the key, so when a new row was
    // inserted above (「已记下 Hugging Face」) each slot kept the previous row's bitmap — logos shifted one row. Keyed now.
    val bmpState = remember(host) { mutableStateOf(Favicons.cached(host)) }
    LaunchedEffect(host) { if (bmpState.value == null && host.isNotBlank()) bmpState.value = Favicons.get(ctx, host) }
    val bmp = bmpState.value
    val shape = RoundedCornerShape(size * 0.27f)
    val b = bmp
    if (b != null) {
        Box(Modifier.size(size).clip(shape).background(Color.White).border(1.dp, Color(0x1F3C2814), shape), contentAlignment = Alignment.Center) {
            Image(b.asImageBitmap(), null, Modifier.size(size * 0.64f))
        }
    } else {
        val palette = listOf(0xFFB5532F, 0xFF2C6AB0, 0xFF1C7069, 0xFF6A4FB4, 0xFFA83C64, 0xFF3B7337, 0xFF8A5A14)
        val c = Color(tint ?: palette[(name.ifBlank { host }.hashCode() and 0x7fffffff) % palette.size])
        Box(Modifier.size(size).clip(shape).background(c), contentAlignment = Alignment.Center) {
            Text(name.ifBlank { host }.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** [Mines-38.9.3] the one pill vocabulary: 异常登录 → 异常, 要重新登录 → 要注意, 未检查 grey, 正常 green. */
fun pillOf(st: Status): com.openminis.app.plus.SessionStatus.Pill = when (st) {
    Status.OK -> com.openminis.app.plus.SessionStatus.Pill.OK
    Status.EXPIRED -> com.openminis.app.plus.SessionStatus.Pill.WARN
    Status.ABNORMAL -> com.openminis.app.plus.SessionStatus.Pill.BAD
    Status.UNCHECKED -> com.openminis.app.plus.SessionStatus.Pill.UNCHECKED
}

@Composable
fun KcChip(st: Status) = AcctPill(pillOf(st))

/** [Mines-38.9.1] small round badge for a login method: G (Google), X, GH (GitHub) …, ✉ / ☎ / 🔑 for the rest. */
@Composable
fun MethodBadge(method: IdentityCore.Method, provider: IdentityCore.IdType?, size: Dp = 18.dp) {
    val m = mines()
    val (txt, bg) = when {
        method == IdentityCore.Method.OAUTH && provider != null -> when (provider) {
            IdentityCore.IdType.GOOGLE -> "G" to Color(0xFF4285F4)
            IdentityCore.IdType.X -> "X" to Color(0xFF14171A)
            IdentityCore.IdType.GITHUB -> "GH" to Color(0xFF24292F)
            IdentityCore.IdType.APPLE -> "" to Color(0xFF111111)
            IdentityCore.IdType.MICROSOFT -> "M" to Color(0xFF2F6FBF)
            IdentityCore.IdType.WECHAT -> "微" to Color(0xFF07A35A)
            IdentityCore.IdType.QQ -> "Q" to Color(0xFF1E8FE1)
            IdentityCore.IdType.ALIPAY -> "支" to Color(0xFF1677FF)
            IdentityCore.IdType.TELEGRAM -> "T" to Color(0xFF2AA3DF)
            IdentityCore.IdType.WALLET -> "W" to Color(0xFFE2761B)
            else -> provider.label.take(1) to m.ink3
        }
        method == IdentityCore.Method.EMAIL_CODE || method == IdentityCore.Method.EMAIL_PW -> "@" to Color(0xFF8A6A50)
        method == IdentityCore.Method.PHONE_SMS || method == IdentityCore.Method.PHONE_PW -> "☎" to Color(0xFF6F7F59)
        method == IdentityCore.Method.USER_PW || method == IdentityCore.Method.PASSKEY -> "🔑" to Color(0xFF7D6A8E)
        else -> return
    }
    Box(Modifier.size(size).clip(CircleShape).background(bg).border(1.5.dp, if (m.dark) Color(0xFF221B1E) else Color.White, CircleShape)
        .semantics { contentDescription = if (provider != null) "通过 ${provider.label} 登录" else method.label }, contentAlignment = Alignment.Center) {
        Text(txt, fontSize = (size.value * (if (txt.length > 1) 0.42f else 0.55f)).sp, lineHeight = (size.value * 0.6f).sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
    }
}

private fun typeIcon(t: IdentityCore.IdType) = when (t) {
    IdentityCore.IdType.PHONE -> "phone"; IdentityCore.IdType.EMAIL -> "mail"; IdentityCore.IdType.WALLET -> "wallet"
    IdentityCore.IdType.PASSKEY -> "key-round"; IdentityCore.IdType.GOOGLE, IdentityCore.IdType.MICROSOFT, IdentityCore.IdType.APPLE -> "globe"
    IdentityCore.IdType.WECHAT, IdentityCore.IdType.QQ, IdentityCore.IdType.TELEGRAM -> "messages-square"
    IdentityCore.IdType.GITHUB -> "code"; else -> "user"
}

fun maskIdent(t: IdentityCore.IdType, v: String): String = when (t) {
    IdentityCore.IdType.PHONE -> KeychainCore.maskPhone(v)
    IdentityCore.IdType.WALLET -> if (v.length > 12) v.take(6) + "…" + v.takeLast(4) else v
    else -> v
}

/** [Mines-38.9.5 i] one equal-width identity-type tile: icon over a short label; selected = filled + check. */
@Composable
private fun IdentityTypeTile(t: IdentityCore.IdType, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.height(56.dp).clip(shape).background(if (on) m.primary else minesGlassFill(true)).border(if (on) 1.5.dp else 1.dp, if (on) m.primary else m.glassBorder, shape)
        .clickable(onClick = onClick).semantics { role = androidx.compose.ui.semantics.Role.RadioButton; selected = on; contentDescription = t.label }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            MinesIcon(ic(typeIcon(t)), null, tint = if (on) m.onPrimary else m.icon, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(t.label, fontSize = MT.Caption, lineHeight = MT.CaptionLH, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) m.onPrimary else m.ink,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (on) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(m.onPrimary),
            contentAlignment = Alignment.Center) { Text("✓", fontSize = MT.Tiny, lineHeight = MT.CaptionLH, color = m.primary, fontWeight = FontWeight.Bold) }
    }
}

/** [Mines-38.9.5 i] menu row: fixed 24 dp icon column, short title, one small line under it, never truncated. */
@Composable
private fun AccountMenuRow(title: String, sub: String?, icon: String, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button }
        .padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { MinesIcon(ic(icon), null, tint = m.icon, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = MT.Body, lineHeight = MT.BodyTightLH, color = m.ink, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
        }
    }
}

/** 「+ 添加身份」: any type, free-form (nothing is assumed about what the user has). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddIdentitySheet(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    var type by remember { mutableStateOf<IdentityCore.IdType?>(null) }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // [Mines-38.9.5 i] user 18:50 / 18:56 「对齐，不美观…很多大白话」: grouped (常用 / 社交 / 其他) equal-width grid, small type,
    // a clear selected state (filled + check), short written copy
    val groups = listOf(
        "常用" to listOf(IdentityCore.IdType.PHONE, IdentityCore.IdType.EMAIL, IdentityCore.IdType.GOOGLE, IdentityCore.IdType.APPLE,
            IdentityCore.IdType.MICROSOFT, IdentityCore.IdType.GITHUB),
        "社交" to listOf(IdentityCore.IdType.WECHAT, IdentityCore.IdType.QQ, IdentityCore.IdType.X, IdentityCore.IdType.TELEGRAM, IdentityCore.IdType.ALIPAY),
        "其他" to listOf(IdentityCore.IdType.WALLET, IdentityCore.IdType.USERNAME, IdentityCore.IdType.PASSKEY, IdentityCore.IdType.OTHER),
    )
    MinesSheet(onDismiss) {
        Text("添加身份", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(6.dp))
        groups.forEach { (gName, types) ->
            Text(gName, fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.ink3, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            types.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { t -> IdentityTypeTile(t, type == t, Modifier.weight(1f)) { type = t } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val t = type
        if (t != null) {
            OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(when (t) { IdentityCore.IdType.PHONE -> "手机号"; IdentityCore.IdType.EMAIL -> "邮箱地址"; IdentityCore.IdType.WALLET -> "钱包地址"
                    IdentityCore.IdType.PASSKEY -> "在哪儿（例如 手机 / 1Password）"; IdentityCore.IdType.USERNAME -> "用户名"; else -> "${t.label} 账号（邮箱、手机号或用户名）" }) })
            OutlinedTextField(note, { note = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                label = { Text("备注（可不填）") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassSecondaryPill("取消", Modifier.weight(0.4f)) { onDismiss() }
            GlassPill("添加", enabled = t != null && value.isNotBlank(), modifier = Modifier.weight(0.6f)) {
                val tt = type ?: return@GlassPill
                com.openminis.app.plus.IdentityStore.addIdent(ctx, IdentityCore.Ident(tt, value.trim(), note.trim(), "手动添加", at = System.currentTimeMillis()))
                onAdded("已添加 ${tt.label}")
            }
        }
    }
}

@Composable
fun KeychainScreen(onBack: () -> Unit, go: (String) -> Unit, onTab: ((Int) -> Unit)? = null) {
    val ctx = LocalContext.current
    val m = mines()
    val rev by Keychain.rev.collectAsState()
    val auto by com.openminis.app.plus.AutoAccounts.list.collectAsState()
    val idents by com.openminis.app.plus.IdentityStore.idents.collectAsState()
    var query by remember { mutableStateOf("") }
    var onlyBad by remember { mutableStateOf(false) }
    var openGroups by remember { mutableStateOf(setOf<String>()) }
    var addSheet by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var linkRev by remember { mutableStateOf(0) }
    val all by produceState(emptyList<Keychain.Acct>(), rev, auto) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Keychain.all(ctx) }.getOrDefault(emptyList()) }
    }
    val bad = all.count { it.status == Status.ABNORMAL }
    // [Mines-38.9.3] re-check before any 「要重新登录」 is shown: rows not checked in the last 10 min say 「正在确认」 (grey)
    val checkingSites by com.openminis.app.plus.Relogin.checking.collectAsState()
    var recheckTick by remember { mutableStateOf(0) }
    fun shownPill(a: Keychain.Acct): Pair<com.openminis.app.plus.SessionStatus.Pill, String?> = when {
        a.status == Status.EXPIRED && (a.host in checkingSites || !com.openminis.app.plus.Relogin.verified(a.host)) ->
            com.openminis.app.plus.SessionStatus.Pill.UNCHECKED to "正在确认"
        else -> pillOf(a.status) to null
    }
    LaunchedEffect(all) {
        val todo = all.filter { it.status == Status.EXPIRED && !com.openminis.app.plus.Relogin.verified(it.host) }.take(4)
        todo.forEach { com.openminis.app.plus.Relogin.recheckAsync(ctx, it.host) }
        if (todo.isNotEmpty()) { delay(25_000); recheckTick++ }
    }
    @Suppress("UNUSED_EXPRESSION") recheckTick
    val exp = all.count { it.status == Status.EXPIRED && it.host !in checkingSites && com.openminis.app.plus.Relogin.verified(it.host) }
    val shown = all.filter { a -> (!onlyBad || a.status == Status.ABNORMAL || a.status == Status.EXPIRED) &&
        (query.isBlank() || a.site.contains(query, true) || a.account.contains(query, true) || a.host.contains(query, true)) }
    var afOn by remember { mutableStateOf(MinesAutofillService.enabled(ctx)) }
    // [Mines-38.9.3] 账号 › 登录 (was 「账号关联」): same frame, search, rows and pills as 账号 › 服务
    AccountsPage(1, onTab, onBack, actions = {
        androidx.compose.material3.IconButton(onClick = { plusMenu = true }) { MinesIcon(ic("plus"), "添加：关联账号或添加身份", tint = m.ink) }
    }) {
        AcctSearch(query, "搜网站或账号", { query = it })
        // summary strip
        Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 10.dp).clip(RoundedCornerShape(50)).background(minesGlassFill())
            .border(1.dp, m.glassBorder, RoundedCornerShape(50)).clickable { onlyBad = !onlyBad }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(ic("shield-check"), null, tint = m.icon, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("${all.size} 个登录 · ${idents.size} 个身份", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.ink)
            if (bad > 0) Text(" · $bad 个异常", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger)
            if (exp > 0) Text(" · $exp 个要重新登录", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning)
            Spacer(Modifier.weight(1f))
            if (bad + exp > 0 || onlyBad) Text(if (onlyBad) "看全部 ›" else "看异常 ›", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.primaryText)
        }
        // autofill: one step
        if (!afOn) Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(minesGlassFill())
            .border(1.dp, m.glassBorder, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(ic("key-round"), null, tint = m.icon, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("在别的 App 里也能填密码", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink)
                Text("点「开启」，在弹出的列表里选 Mines", fontSize = MT.Sub, color = m.ink2)
            }
            PillButton("开启", primary = true) { MinesAutofillService.askEnable(ctx) }
        }
        LaunchedEffect(Unit) { while (true) { delay(2500); afOn = MinesAutofillService.enabled(ctx) } }
        msg?.let { Text(it, fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp)) }
        if (query.isBlank() && !onlyBad) SignInGroupsSection(rev)   // [Mines-38.9.15] 按登录方式 (Google / 邮箱+密码 / 手机号+验证码 / API Key)
        // [Mines-38.9.1] groups are made from the identities that exist — any type, no fixed 手机号 / 邮箱 slots
        val groups = remember(shown, idents) { runCatching { Keychain.idGroups(ctx, shown) }.getOrDefault(emptyList()) }
        if (all.isEmpty() && idents.isEmpty()) InfoLine("还没有记下的账号。点右上角 +：一键关联，或者登记你的手机号、邮箱、Google 等身份。在 Mines 里登录 / 注册一次也会自动记下。")
        groups.forEach { (g, list) ->
            // [Mines-38.9.3] header starts where the card content starts (36 dp), like every section on both tabs
            Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(ic(if (g.key == "unknown") "user" else typeIcon(g.type)), null, tint = m.ink2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(g.title, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2)
                if (g.value.isNotBlank()) Text("  " + maskIdent(g.type, g.value), fontSize = MT.Meta, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                g.badges.forEach { b -> Spacer(Modifier.width(4.dp)); MethodBadge(IdentityCore.Method.OAUTH, b, 15.dp) }
                Spacer(Modifier.weight(1f))
                Text("${list.size} 个", fontSize = MT.Meta, color = m.ink3)
            }
            val open = g.key in openGroups || query.isNotBlank() || onlyBad
            val head = if (open) list else list.take(3)
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                if (list.isEmpty()) Text("还没有用它登录的网站。一键关联时会用它。", fontSize = MT.Sub, color = m.ink3, modifier = Modifier.padding(16.dp))
                head.forEachIndexed { i, a ->
                    val (pl, lbl) = shownPill(a)
                    KcRow(a, pl, lbl) { go(PlusRoutes.keychainDetail(a.id)) }
                    if (i < head.size - 1 || list.size > head.size) AcctDivider()
                }
                if (list.size > head.size) Row(Modifier.fillMaxWidth().clickable { openGroups = openGroups + g.key }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("还有 ${list.size - head.size} 个", fontSize = MT.Meta, color = m.ink2)
                    MinesIcon(ic("chevron-down"), null, tint = m.ink2, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
        }
        // [Mines-38.9.1] user 2026-10-16 「这个应该收纳到账号关联」: 关联结果 and 已忽略的平台 live here now (no extra pages)
        LinkResultsInline(linkRev, go) { linkRev++ }
        Spacer(Modifier.height(20.dp))
    }
    if (plusMenu) MinesSheet({ plusMenu = false }) {
        // [Mines-38.9.5 i] user 18:50: items not truncated, icons aligned, copy 「关联账号」「添加身份」
        AccountMenuRow("关联账号", "自动登录尚未登录的网站", "refresh-cw") { plusMenu = false; go(LinkAll.ROUTE) }
        AccountMenuRow("添加身份", "手机号、邮箱、Google 等", "user") { plusMenu = false; addSheet = true }
        AccountMenuRow("关闭", null, "x") { plusMenu = false }
    }
    if (addSheet) AddIdentitySheet({ addSheet = false }) { addSheet = false; msg = it; Keychain.rev.value++ }
}

/** 关联结果 folded into 账号关联: one collapsed row per state (hidden when empty), 忽略 / 恢复 on each platform. */
@Composable
private fun LinkResultsInline(rev: Int, go: (String) -> Unit, changed: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    remember { com.openminis.app.plus.group.GroupPrefs.init(ctx); 0 }
    val all = remember(rev) { runCatching { LinkAll.platforms() }.getOrDefault(emptyList()) }
    val groups = remember(rev, all) { all.groupBy { LinkAll.shownState(it) } }   // [Mines-38.9.4] same rule as the rows
    var open by remember { mutableStateOf<LinkAll.St?>(if (LinkAll.openResults) LinkAll.St.USER else null) }
    remember { LinkAll.openResults = false; 0 }
    val order = listOf(LinkAll.St.USER, LinkAll.St.EXPIRED, LinkAll.St.UNKNOWN, LinkAll.St.NONE, LinkAll.St.IGNORED)
    if (order.all { groups[it].isNullOrEmpty() }) return
    Text("一键关联的结果", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.padding(start = 36.dp, top = 22.dp, bottom = 8.dp))
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
        order.forEach { st ->
            val l = groups[st].orEmpty()
            if (l.isEmpty()) return@forEach
            val isOpen = open == st
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = if (isOpen) null else st }.padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { contentDescription = "${st.label} ${l.size} 个" }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (st == LinkAll.St.IGNORED) "已忽略 ${l.size} 个" else "${st.label} ${l.size} 个", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f))
                MinesIcon(ic(if (isOpen) "chevron-up" else "chevron-down"), null, tint = m.ink3, modifier = Modifier.size(16.dp))
            }
            if (isOpen) l.forEach { id ->
                val sub = LinkAll.category(id) + " · " + when (st) {
                    LinkAll.St.USER -> LinkAll.userStepText(id)
                    LinkAll.St.EXPIRED -> "登录过期了，点一下重新登录"
                    LinkAll.St.UNKNOWN -> "还没确认登录状态，点一下检查"
                    LinkAll.St.NONE -> if (LinkAll.note(id) == "跳过") "上次跳过了，点一下登录" else "点一下登录"
                    LinkAll.St.IGNORED -> "不再提醒，也不算失败"
                    else -> ""
                }
                PlusRow(null, LinkAll.name(id), sub, trailing = {
                    if (st == LinkAll.St.IGNORED) SmallPill("恢复") { LinkAll.setIgnored(id, false); changed() }
                    else SmallPill("忽略") { LinkAll.setIgnored(id, true); changed() }
                }, onClick = if (st == LinkAll.St.IGNORED) null else ({ LinkAll.autoLogin = false; LinkAll.open(id, go, auto = false) }))
            }
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(m.hairline))
        }
        val notIgn = all.count { LinkAll.state(it) != LinkAll.St.LINKED && LinkAll.state(it) != LinkAll.St.IGNORED }
        if (notIgn > 0) Row(Modifier.fillMaxWidth().clickable { LinkAll.start(); go(LinkAll.ROUTE) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("再关联一遍没好的（$notIgn 个）", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText, modifier = Modifier.weight(1f))
            MinesIcon(ic("chevron-right"), null, tint = m.primaryText, modifier = Modifier.size(16.dp))
        }
    }
}

/** [Mines-38.9.3] the shared account row ([AcctRow]) + the sign-in method badge on the logo's corner. */
@Composable
private fun KcRow(a: Keychain.Acct, pill: com.openminis.app.plus.SessionStatus.Pill, pillLabel: String?, onClick: () -> Unit) {
    val m = mines()
    val line = listOf(KeychainCore.maskAccount(a.account), a.methodText).filter { it.isNotBlank() }.joinToString(" · ")
    AcctRow(logo = {
        Box {
            AcctLogo(a.host, a.site)
            Box(Modifier.align(Alignment.BottomEnd).padding(start = 27.dp, top = 27.dp)) { MethodBadge(a.method, a.provider, 16.dp) }
        }
    }, title = a.site, subtitle = line.ifBlank { a.host.ifBlank { a.source } }, pill = pill, pillLabel = pillLabel, onClick = onClick,
        description = "${a.site}，${a.methodText.ifBlank { "" }}，${pillLabel ?: pill.label}",
        extra = {
            if (a.hasPw) MinesIcon(ic("key-round"), "有密码", tint = m.ink3, modifier = Modifier.padding(start = 6.dp).size(15.dp))
            if (a.has2fa) MinesIcon(ic("shield-check"), "有两步验证", tint = m.ink3, modifier = Modifier.padding(start = 6.dp).size(15.dp))
        })
}

// ------------------------------------------------------------------ detail

@Composable
fun KeychainDetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val m = mines()
    val rev by Keychain.rev.collectAsState()
    val a by produceState<Keychain.Acct?>(null, rev, id) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Keychain.find(ctx, id) } }
    var showPw by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var addTotp by remember { mutableStateOf(false) }
    var addPw by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    fun copy(label: String, v: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText(label, v)
        if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm?.setPrimaryClip(clip); msg = "已复制$label"
    }
    PlusPage("账号详情", onBack, actions = {
        androidx.compose.material3.IconButton(onClick = { menu = true }) { MinesIcon(ic("ellipsis"), "更多", tint = m.ink) }
    }, bottomBar = {
        // [Mines-38.9.1] mockup kc-04: 打开网站 / 改密码 in a fixed frosted bar above the gesture bar
        val x = a
        if (x != null) Column(Modifier.fillMaxWidth().background(glassVeil()).border(1.dp, m.glassBorder.copy(alpha = 0.5f)).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSecondaryPill("打开网站", Modifier.weight(1f)) { if (x.host.isNotBlank()) PlusNav.web("https://" + x.host + "/") }
                GlassPill("改密码", enabled = x.host.isNotBlank(), modifier = Modifier.weight(1f)) { PlusNav.web(KeychainCore.securityUrl(x.host)) }
            }
            Text("改密码要在网站上由你自己改，Mines 只帮你打开安全设置页。", fontSize = MT.Caption, color = m.ink2, modifier = Modifier.padding(top = 6.dp))
        }
    }) {
        val x = a
        if (x == null) { InfoLine("找不到这个账号了"); return@PlusPage }
        val pw = remember(x.host, x.account, rev) { SavedLogins.find(ctx, x.host, x.account) }
        // hero
        // [Mines-38.9.3] the same row parts as the lists (logo 40 / 12 dp, title 16, subtitle 13, one pill)
        val checkingSites by com.openminis.app.plus.Relogin.checking.collectAsState()
        val busy = x.host in checkingSites
        var reTick by remember { mutableStateOf(0) }
        LaunchedEffect(x.id, x.status) {
            if ((x.status == Status.EXPIRED || x.status == Status.UNCHECKED) && !com.openminis.app.plus.Relogin.verified(x.host)) {
                com.openminis.app.plus.Relogin.recheckAsync(ctx, x.host); delay(25_000); reTick++
            }
        }
        @Suppress("UNUSED_EXPRESSION") reTick
        val heroPill = if (busy || (x.status == Status.EXPIRED && !com.openminis.app.plus.Relogin.verified(x.host))) com.openminis.app.plus.SessionStatus.Pill.UNCHECKED else pillOf(x.status)
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 6.dp)) {
            AcctRow({ AcctLogo(x.host, x.site) }, x.site, x.host.ifBlank { null }, heroPill,
                pillLabel = if (heroPill == com.openminis.app.plus.SessionStatus.Pill.UNCHECKED && (busy || x.status == Status.EXPIRED)) "正在确认" else null,
                chevron = Chevron.NONE, onClick = null)
        }
        // [Mines-38.9.3] 「一键重新登录」: reuses the saved password / recorded identity, no questions; 「重新检查」 = a real check now
        val canRelogin = remember(x.host) { runCatching { com.openminis.app.ui.plus.LinkAll.platformFor(x.host) != null }.getOrDefault(false) }
        if ((x.status == Status.EXPIRED || x.status == Status.UNCHECKED) && x.host.isNotBlank()) PlusSection(null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = AcctUi.SideH, vertical = 12.dp)) {
                Text(if (x.status == Status.EXPIRED && !busy) "这个网站的登录已退出" else if (busy) "正在确认登录状态…" else "还没确认这个网站的登录状态",
                    fontSize = AcctUi.Title, fontWeight = FontWeight.Medium, color = m.ink)
                Text(if (pw != null || x.methodText.isNotBlank()) "一键重新登录会用已存的密码 / 上次的登录方式，不用你再输入" else "一键重新登录会打开登录页，能自动填的都会自动填",
                    fontSize = AcctUi.Sub, lineHeight = AcctUi.SubLH, color = m.ink2, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassSecondaryPill(if (busy) "检查中…" else "重新检查", Modifier.weight(1f)) { if (!busy) com.openminis.app.plus.Relogin.recheckAsync(ctx, x.host, force = true) }
                    GlassPill("一键重新登录", modifier = Modifier.weight(1f)) {
                        if (!(canRelogin && com.openminis.app.plus.Relogin.silent(x.host) { PlusNav.go(it) })) PlusNav.web("https://" + x.host + "/")
                    }
                }
                // [Mines-38.9.5 i11] a background re-login that did not finish says why (需要验证码 / 网络超时 / 人机验证…) + 重试
                val autoTry = remember(rev, x.host) { com.openminis.app.plus.AccountsTool.lastResult.entries
                    .firstOrNull { (k, _) -> k == x.host || com.openminis.app.plus.AccountsCore.sameSite(k, x.host) }?.value }
                val autoBusy = remember(rev, x.host) { com.openminis.app.plus.AccountsTool.running.keys.any { it == x.host || com.openminis.app.plus.AccountsCore.sameSite(it, x.host) } }
                var retrying by remember(x.host) { mutableStateOf(false) }
                if ((autoTry != null && "已重新登录" !in autoTry) || autoBusy || retrying) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (autoBusy || retrying) "正在后台重新登录…（最多 ${com.openminis.app.plus.AccountsTool.HIDDEN_LOGIN_MS / 1000} 秒）" else autoTry.orEmpty(),
                            fontSize = AcctUi.Sub, lineHeight = AcctUi.SubLH, color = m.ink2, modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                        if (!autoBusy && !retrying) {
                            Spacer(Modifier.width(10.dp))
                            GlassSecondaryPill("重试") {
                                retrying = true
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    runCatching { com.openminis.app.plus.AccountsTool.hiddenLogin(ctx.applicationContext, x.host) }
                                    retrying = false; Keychain.rev.value++
                                }
                            }
                        }
                    }
                }
            }
        }
        Keychain.alertFor(x)?.let { al ->
            PlusSection("异常登录") {
                PlusRow(ic("shield-alert"), "${al.device.ifBlank { "新设备" }}${if (al.place.isNotBlank()) " · " + al.place else ""}",
                    "选「不是我」后：" + KeychainCore.guideFor(x.host).joinToString("；"), chevron = false)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("是我", Modifier.weight(1f), primary = false) { Keychain.confirmMe(ctx, al) }
                    PillButton("已处理好", Modifier.weight(1f), primary = false) { Keychain.handled(ctx, x) }
                }
            }
        }
        // fields
        PlusSection(null) {
            KcField("账号", KeychainCore.maskAccount(x.account).ifBlank { "没看到账号名" } /* [Mines-38.9.4] masked like the list; 复制 copies the full value */, if (x.account.isNotBlank()) listOf("copy" to { copy("账号", x.account) }) else emptyList())
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(m.hairline))
            if (pw != null) {
                KcField("密码", if (showPw) pw.pass else "••••••••••", listOf(
                    (if (showPw) "eye-off" else "eye") to { if (showPw) showPw = false else com.openminis.app.plus.Unlock.prompt(ctx, "显示 ${x.site} 的密码") { ok -> if (ok) showPw = true else msg = "没通过验证" } },
                    "copy" to { com.openminis.app.plus.Unlock.prompt(ctx, "复制 ${x.site} 的密码") { ok -> if (ok) copy("密码", pw.pass) else msg = "没通过验证" } }), mono = showPw)
            } else PlusRow(ic("key-round"), "没存密码", "存一个，以后在别的 App 里也能填", trailing = { PillButton("存密码", primary = false) { addPw = true } })
        }
        // 2FA
        val t = remember(x.id, rev) { Keychain.totpOf(x) }
        PlusSection(null) {
            if (t != null) TotpBlock(t) { code -> copy("验证码", code) }
            else PlusRow(ic("shield-check"), "两步验证码", "把网站给的密钥存进来，Mines 帮你算验证码", trailing = { PillButton("添加", primary = false) { addTotp = true } })
        }
        // meta
        PlusSection(null) {
            PlusRow(ic("history"), "上次登录", value = if (x.lastLogin > 0) fmtTime(x.lastLogin) else "还没看到", chevron = false)
            PlusRow(ic("sparkles"), "来源", value = x.source, chevron = false)
            if (x.methodText.isNotBlank()) PlusRow(ic("key-round"), "登录方式", value = x.methodText, chevron = false,
                trailing = { MethodBadge(x.method, x.provider, 20.dp) })
        }
        // [Mines-38.9.1] a provider account (Google / GitHub / X …) lists the sites signed in through it
        if (x.isProvider) {
            val via = remember(x.id, rev) { runCatching { Keychain.linkedVia(ctx, x) }.getOrDefault(emptyList()) }
            PlusSection("用它登录的网站（${via.size}）") {
                if (via.isEmpty()) Text("还没有通过 ${x.site} 登录的网站。一键关联遇到「用 ${x.site} 登录」会优先用它。", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.padding(16.dp))
                via.forEach { v -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteLogo(v.host, v.site, 32.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.site, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                        Text(v.host, fontSize = MT.Sub, color = m.ink3, maxLines = 1)
                    }
                    KcChip(v.status)
                } }
            }
        }
        val evs by Keychain.events.collectAsState()
        val mine = evs.filter { it.key.startsWith(Keychain.root(x.host).ifBlank { x.site.lowercase() } + "|") }.take(5)
        if (mine.isNotEmpty()) PlusSection("最近登录") {
            mine.forEach { e -> PlusRow(ic(if (e.device.startsWith("本机")) "smartphone" else "laptop"),
                listOf(e.device, e.place).filter { it.isNotBlank() }.joinToString(" · "), e.how, value = fmtTime(e.at), chevron = false) }
        }
        msg?.let { Text(it, fontSize = MT.Caption, color = m.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        Spacer(Modifier.height(90.dp))

        if (addTotp) {
            var v by remember { mutableStateOf("") }
            MinesDialog("添加两步验证", { addTotp = false }, confirm = "保存", onConfirm = {
                if (KeychainCore.parseTotp(v) != null) { Keychain.setTotp(x.id, v); addTotp = false; msg = "已存好，验证码每 30 秒换一次" } else msg = "看不懂这个密钥：粘贴网站给的一串字母数字，或 otpauth:// 链接"
            }, confirmEnabled = v.isNotBlank()) {
                Text("在网站开两步验证时，选「无法扫码 / 手动输入」，把那串密钥粘贴到这里。", fontSize = MT.Body, color = m.ink2)
                OutlinedTextField(v, { v = it.trim() }, singleLine = true, placeholder = { Text("例如 JBSW Y3DP EHPK 3PXP") })
            }
        }
        if (addPw) {
            var u by remember { mutableStateOf(x.account) }; var p by remember { mutableStateOf("") }
            MinesDialog("存密码", { addPw = false }, confirm = "保存", confirmEnabled = p.isNotBlank() && x.host.isNotBlank(), onConfirm = {
                SavedLogins.put(ctx, SavedLogins.Entry(x.site, "https://${x.host}/", u, p)); Keychain.rev.value++; addPw = false; msg = "密码已加密存在这台手机里"
            }) {
                OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("账号") })
                OutlinedTextField(p, { p = it }, singleLine = true, label = { Text("密码") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        }
        if (menu) MinesSheet({ menu = false }) {
            if (t != null) GlassMenuRow("删除两步验证", ic("trash"), danger = true) { Keychain.setTotp(x.id, null); menu = false }
            if (pw != null) GlassMenuRow("删除存的密码", ic("trash"), danger = true) {
                val left = SavedLogins.all(ctx).filterNot { it.host == pw.host && it.user == pw.user }
                SavedLogins.clear(ctx); left.forEach { SavedLogins.put(ctx, it) }; Keychain.rev.value++; menu = false
            }
            com.openminis.app.plus.AutoAccounts.list.value.firstOrNull { it.site.equals(x.site, true) && it.account.equals(x.account, true) }?.let { e ->
                GlassMenuRow("从自动记下的账号里删掉", ic("trash"), danger = true) { com.openminis.app.plus.AutoAccounts.remove(ctx, e); menu = false; onBack() }
            }
            // [Mines-38.9.11 b] 无痕-style: wipe this site's web data (shared store + the account's own profile + kept snapshot)
            if (x.host.isNotBlank()) GlassMenuRow("清除这个网站的网页数据", ic("trash"), danger = true) {
                msg = com.openminis.app.plus.WebIsolation.wipeSite(ctx, x.host, x.linkId); menu = false
            }
            GlassMenuRow("关闭", ic("x")) { menu = false }
        }
    }
}

private fun fmtTime(t: Long): String {
    val now = java.util.Calendar.getInstance(); val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val hm = String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    return if (now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) "今天 $hm"
        else "${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.DAY_OF_MONTH)}"
}

@Composable
private fun KcField(label: String, value: String, actions: List<Pair<String, () -> Unit>>, mono: Boolean = false) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = MT.Meta, color = m.ink3)
            Text(value, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (mono) FontFamily.Monospace else null)
        }
        actions.forEach { (n, f) ->
            Box(Modifier.padding(start = 8.dp).size(36.dp).clip(CircleShape).background(if (m.dark) Color(0x1FFFFFFF) else Color(0x121C1915)).clickable(onClick = f)
                .semantics { contentDescription = when (n) { "copy" -> "复制$label"; "eye" -> "显示$label"; else -> "隐藏$label" } }, contentAlignment = Alignment.Center) {
                MinesIcon(ic(n), null, tint = m.ink2, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun TotpBlock(t: KeychainCore.Totp, onCopy: (String) -> Unit) {
    val m = mines()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val code = KeychainCore.totpCode(t, now); val left = KeychainCore.secondsLeft(t, now)
    Column(Modifier.fillMaxWidth().clickable { onCopy(code) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MinesIcon(ic("shield-check"), null, tint = m.icon, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("两步验证码", fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = m.ink2, modifier = Modifier.weight(1f))
            Text("点一下复制", fontSize = MT.Meta, color = m.ink3)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(KeychainCore.spaced(code), fontSize = MT.Code, fontWeight = FontWeight.Medium, color = m.ink, modifier = Modifier.weight(1f)
                .semantics { contentDescription = "两步验证码 $code，$left 秒后换" })
            Text("秒后换", fontSize = MT.Caption, color = m.ink3); Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                val tr = m.hairline; val ac = m.primary
                Canvas(Modifier.size(36.dp)) {
                    val w = 3.dp.toPx(); val r = Size(size.width - w, size.height - w); val o = Offset(w / 2, w / 2)
                    drawArc(tr.copy(alpha = 0.6f), 0f, 360f, false, o, r, style = Stroke(w))
                    drawArc(ac, -90f, 360f * left / t.period, false, o, r, style = Stroke(w))
                }
                Text("$left", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.primaryText)
            }
        }
    }
}

// ------------------------------------------------------------------ app-wide: alert sheet + 已记下 toast

@Composable
fun KeychainHost() {
    val ctx = LocalContext.current
    androidx.compose.runtime.remember { Keychain.init(ctx); 0 }
    val alerts by Keychain.alerts.collectAsState()
    var guide by remember { mutableStateOf<KeychainCore.Alert?>(null) }
    val m = mines()
    // [38.9.15.1] no new-device pop-up sheet (user 2026-10-26: only payment-type confirmations pop): the alert stays in 「消息」
    guide?.let { g ->
        MinesDialog("${g.site}：按这几步来", { guide = null }, confirm = "打开安全设置", onConfirm = {
            PlusNav.web(KeychainCore.securityUrl(g.host)); guide = null
        }, dismiss = "稍后") {
            KeychainCore.guideFor(g.host).forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink) }
        }
    }
    // 「已记下 X 账号」
    var toast by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) { Keychain.toast.collect { toast = it } }
    LaunchedEffect(toast) { if (toast != null) { delay(4000); toast = null } }
    toast?.let { (site, sub) ->
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
            // [Mines-38.9.1] 38.9 review: the toast was see-through over the 一键关联 / 关联结果 buttons. Now a near-opaque frosted
            // pill (text never competes with what is behind it) with a soft shadow, kept above the bottom navigation / insets.
            // [Mines-38.9.6 i14] the shared glass toast (dense 暖瓷, halo outside only)
            GlassToast(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 104.dp).fillMaxWidth(), shape = RoundedCornerShape(30.dp)) {
            Row(Modifier.fillMaxWidth()
                .clickable { toast = null; PlusNav.go(PlusRoutes.KEYCHAIN) }.padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "已记下 $site 账号" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFDEF0E4)), contentAlignment = Alignment.Center) {
                    MinesIcon(ic("check"), null, tint = Color(0xFF28804E), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("已记下 $site 账号", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
                    if (sub.isNotBlank()) Text(sub, fontSize = MT.Sub, color = m.ink2, maxLines = 1)
                }
                Text("查看 ›", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText)
            } }
        }
    }
}

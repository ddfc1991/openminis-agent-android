package com.openminis.app.ui.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.plus.group.AiGroup
import com.openminis.app.plus.group.GroupMsg
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.GroupRunner
import com.openminis.app.plus.group.GroupStore
import com.openminis.app.plus.group.Partner
import com.openminis.app.plus.group.Partners
import com.openminis.app.plus.group.PhoneApps
import com.openminis.app.plus.group.PhoneTyping
import com.openminis.app.plus.group.Proto
import com.openminis.app.plus.group.Kind
import com.openminis.app.plus.group.WebChat
import com.openminis.app.plus.group.WebAccounts
import com.openminis.app.plus.group.AiCatalog
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons
import com.openminis.app.ui.plus.InfoLine
import com.openminis.app.ui.plus.MinesBackdrop
import com.openminis.app.ui.plus.MinesCard
import com.openminis.app.ui.plus.MinesDesign
import com.openminis.app.ui.plus.PetAvatar
import com.openminis.app.ui.plus.PillButton
import com.openminis.app.ui.plus.PlusNav
import com.openminis.app.ui.plus.PlusPage
import com.openminis.app.ui.plus.PlusRoutes
import com.openminis.app.ui.plus.PlusRow
import com.openminis.app.ui.plus.PlusSection
import com.openminis.app.ui.plus.StatusPill
import com.openminis.app.ui.plus.mines
import com.openminis.app.ui.plus.MT
import com.openminis.app.ui.plus.MS
import com.openminis.app.ui.plus.minesGlassFill
import kotlinx.coroutines.launch

/** [Mines-group-37] 「AI 群聊」 + 「AI 伙伴」 routes, mounted by AppNavigation with one line. */
object GroupRoutes {
    const val HOME = "plus_group"
    const val PARTNERS = "plus_group_partners"
    const val NEW = "plus_group_new"
    const val CHAT = "plus_group_chat/{id}"
    fun chat(id: String) = "plus_group_chat/$id"
    const val WEBLOGIN = "plus_group_weblogin/{id}"
    fun webLogin(id: String) = "plus_group_weblogin/" + android.net.Uri.encode(id)
}

fun NavGraphBuilder.groupRoutes(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable(GroupRoutes.HOME) { GroupHomeScreen(back, { nav.navigate(it) }) }
    composable(GroupRoutes.PARTNERS) { PartnersScreen(back) { nav.navigate(it) } }
    // [Mines-38.9.1] self-hosted services sign in on their real web page (Memoh …)
    composable(SiteLogin.ROUTE) { e -> SiteLoginScreen(e.arguments?.getString("svc") ?: "", back) }
    composable(GroupRoutes.WEBLOGIN, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        WebAppLoginScreen(e.arguments?.getString("id") ?: "", back)
    }
    // [Mines-38.7] 一键关联 + 关联结果 + 手机权限
    composable(com.openminis.app.ui.plus.LinkAll.ROUTE) { com.openminis.app.ui.plus.LinkAllScreen(back) { nav.navigate(it) } }
    // [Mines-38.9.1] 关联结果 / 已忽略的平台 are folded into 账号关联 (old links land there)
    composable(com.openminis.app.ui.plus.LinkAll.SUMMARY) { com.openminis.app.ui.plus.LinkAll.openResults = true; com.openminis.app.ui.plus.AccountsScreen(1, back) { nav.navigate(it) } }
    composable(com.openminis.app.ui.plus.LinkAll.IGNORED) { com.openminis.app.ui.plus.AccountsScreen(1, back) { nav.navigate(it) } }
    composable(com.openminis.app.ui.plus.PermGuide.ROUTE) { com.openminis.app.ui.plus.PermGuideScreen(back) }
    composable(GroupRoutes.NEW) {
        NewGroupScreen(back, go = { nav.navigate(it) }) { id -> nav.navigate(GroupRoutes.chat(id)) { popUpTo(GroupRoutes.NEW) { inclusive = true } } }
    }
    composable(GroupRoutes.CHAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        GroupChatScreen(e.arguments?.getString("id") ?: "", back) { nav.navigate(GroupRoutes.PARTNERS) }
    }
}

private fun icon(name: String) = MinesIcons.named(name)!!

// ── avatar ───────────────────────────────────────────────────────────────

@Composable
fun PartnerAvatar(p: Partner?, who: String, name: String, size: Dp = 36.dp) {
    val m = mines()
    if (who == GroupStore.HOST) {
        Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) { PetAvatar(size = size, animated = false) }
        return
    }
    val spec = p?.spec
    val tint = Color(spec?.color ?: 0xFF8A7F74)
    Box(
        Modifier.size(size).clip(CircleShape).background(if (spec != null && spec.icon != 0) Color.White else tint.copy(alpha = 0.16f))
            .border(1.dp, m.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (spec?.iconUrl != null) coil.compose.AsyncImage(spec.iconUrl, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (spec != null && spec.icon != 0) Image(painterResource(spec.icon), contentDescription = null, modifier = Modifier.size(size * 0.6f))
        else Text(GroupRunner.shortName(name).take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
private fun Tag(text: String, tone: Color) {
    Text(text, fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = tone,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp))
}

// ── 群聊 home ─────────────────────────────────────────────────────────────

@Composable
fun GroupHomeScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    GroupStore.init(ctx)
    val groups by GroupStore.groups.collectAsState()
    val partners by Partners.list.collectAsState()
    LaunchedEffect(Unit) { Partners.refresh(ctx) }
    val ready = partners.filter { it.ready }
    PlusPage("AI 群聊", onBack, subtitle = "智能体和 AI App 一起讨论，Mines 主持并总结") {
        PlusSection(null) {
            val readyMembers = ready.filter { it.spec.kind != Kind.MODEL }
            PlusRow(icon("sparkles"), "AI 伙伴", if (partners.isEmpty()) "正在识别…" else "${readyMembers.size} 个智能体和 App 能直接用") { go(GroupRoutes.PARTNERS) }
        }
        PlusSection("我的群") {
            if (groups.isEmpty()) InfoLine("还没有群。点下面「新建群聊」，选几个 AI 就能开聊")
            groups.forEach { g ->
                val names = g.members.mapNotNull { id -> partners.firstOrNull { it.id == id }?.spec?.name?.let(GroupRunner::shortName) }
                val all = (if (g.host) listOf("Mines") else emptyList<String>()).plus(names)
                PlusRow(icon("users"), g.name, if (all.isEmpty()) "没有成员" else all.take(4).joinToString("、") + if (all.size > 4) " 等 ${all.size} 位" else "") {
                    go(GroupRoutes.chat(g.id))
                }
            }
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        PillButton("新建群聊", Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = true) { go(GroupRoutes.NEW) }
    }
}

// ── 新建群 ───────────────────────────────────────────────────────────────

/**
 * [Mines-38.6] user 2026-10-15 「群聊我是要 app、AI agent 这些群聊，不是模型群聊」: members are picked from 智能体 and
 * App 账号; bare model APIs sit folded under 「高级：直接用模型」. Members that still need a sign-in are listed with
 * one tap to sign in.
 */
@Composable
fun NewGroupScreen(onBack: () -> Unit, go: (String) -> Unit = {}, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    GroupStore.init(ctx)
    val partners by Partners.list.collectAsState()
    LaunchedEffect(Unit) { Partners.refresh(ctx) }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var host by remember { mutableStateOf(true) }
    var seq by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    PlusPage("新建群聊", onBack) {
        OutlinedTextField(name, { name = it }, label = { Text("群名（可不填）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        val agents = partners.filter { it.spec.kind == Kind.AGENT }
        val apps = partners.filter { it.spec.kind == Kind.APP }
        val models = partners.filter { it.spec.kind == Kind.MODEL && it.spec.proto != Proto.PHONE && it.ready }
        val phone = partners.filter { it.spec.proto == Proto.PHONE && it.ready }
        fun toggle(id: String) { picked = if (id in picked) picked - id else picked + id }
        PlusSection("智能体", footer = "Manus 这类按任务做事的回得慢些，其他成员照常回复") {
            if (agents.isEmpty()) InfoLine("正在识别…")
            // [Mines-38.7] 「怎么接」 (Muse 中继 / WorkBuddy: no account to sign in) opens AI 伙伴 with that member's explanation
            agents.sortedBy { if (it.ready) 0 else 1 }.forEach { p -> PickRow(p, p.id in picked, { toggle(p.id) }) { fixPartner(p, go) { PartnersNav.open = p.id; go(GroupRoutes.PARTNERS) } } }
        }
        PlusSection("App 账号", footer = "用你自己的账号登录网页版，Mines 替你提问、读回复，不需要密钥") {
            apps.sortedBy { if (it.ready) 0 else 1 }.forEach { p -> PickRow(p, p.id in picked, { toggle(p.id) }) { fixPartner(p, go) } }
        }
        if (phone.isNotEmpty()) PlusSection("手机 App 代打字", footer = "很慢（每条 20–90 秒），会切到那个 App 里操作，可能中途断") {
            phone.forEach { p -> PickRow(p, p.id in picked, { toggle(p.id) }) {} }
        }
        if (models.isNotEmpty()) {
            FoldHeader("高级：直接用模型（${models.size}）", advanced) { advanced = !advanced }
            if (advanced) PlusSection(null, footer = "只调模型接口，没有 App 里的记忆和工具") {
                models.forEach { p -> PickRow(p, p.id in picked, { toggle(p.id) }) {} }
            }
        }
        PlusSection("群设置") {
            PlusRow(null, "Mines 当主持", "可以 @Mines，也能一键总结大家的观点", chevron = false,
                trailing = { Switch(host, { host = it }) })
            PlusRow(null, "回复方式", if (seq) "依次回复：后面的能看到前面的回答" else "同时回复：更快，互相看不到这一轮", chevron = false,
                trailing = { Switch(seq, { seq = it }) })
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        PillButton("创建（${picked.size} 位成员）", Modifier.fillMaxWidth().padding(horizontal = 20.dp), enabled = picked.isNotEmpty()) {
            val order = partners.map { it.id }.filter { it in picked }
            val g = GroupStore.create(name.trim().ifBlank { order.mapNotNull { id -> partners.firstOrNull { it.id == id }?.spec?.name?.let(GroupRunner::shortName) }.take(3).joinToString("、") + " 群" },
                order, host, seq)
            onCreated(g.id)
        }
    }
}

/** One selectable member: ready ones get a checkbox; ones needing sign-in get a 登录 / 连接 pill instead. */
@Composable
private fun PickRow(p: Partner, on: Boolean, onToggle: () -> Unit, onFix: () -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (p.ready) Modifier.clickable(onClick = onToggle) else Modifier)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PartnerAvatar(p, p.id, p.spec.name, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.spec.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = if (p.ready) m.ink else m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLine(p), fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p.ready) {
            if (p.spec.proto == Proto.PHONE || p.spec.proto == Proto.MANUS) { Tag("慢", MinesDesign.Warning); Spacer(Modifier.width(6.dp)) }
            Checkbox(on, { onToggle() })
        } else ActionPill(fixLabel(p), onFix)
    }
}

/** Plain one-line status: 已登录 / 需登录 / 能用 / the reason. */
private fun statusLine(p: Partner): String = when {
    p.spec.proto == Proto.WEBAPP -> if (p.ready) "已登录 · 网页版" else "需登录"
    p.spec.proto == Proto.MINES -> if (p.ready) "能用工具、查资料、读文件" else (p.reason ?: "还不能用")
    p.ready && p.spec.proto == Proto.MANUS -> "交给它一件事，它自己做完再回你（几十秒到几分钟）"
    p.ready && p.spec.proto == Proto.COZE -> "你在扣子的智能体"
    p.ready && p.spec.kind == Kind.AGENT -> p.spec.note.ifBlank { "能用" }
    p.ready -> listOf(p.model, p.via).filter { it.isNotBlank() }.joinToString(" · ")
    else -> p.reason ?: p.spec.note.ifBlank { "还没连接" }
}

private fun fixLabel(p: Partner): String = when {
    p.spec.proto == Proto.WEBAPP -> "登录"
    p.reason?.contains("重新登录") == true -> "重新登录"
    com.openminis.app.plus.group.PresetRegistry.fixLabel(p.id) != null -> com.openminis.app.plus.group.PresetRegistry.fixLabel(p.id)!!  // [Mines-38.8]
    p.spec.proto == Proto.MINES -> "去设置"
    p.spec.vaultKind != null -> "连接"
    else -> "怎么接"
}

/** The one tap that makes a member usable: web sign-in, 账号连接 (扣子 / Manus), or the explanation dialog. */
private fun fixPartner(p: Partner, go: (String) -> Unit, explain: (() -> Unit)? = null) {
    when {
        p.spec.proto == Proto.WEBAPP -> go(GroupRoutes.webLogin(p.id))
        p.spec.vaultKind != null -> connect(p.spec.vaultKind)
        else -> explain?.invoke()
    }
}

@Composable
private fun FoldHeader(text: String, open: Boolean, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp)
        .semantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.weight(1f))
        MinesIcon(icon(if (open) "chevron-up" else "chevron-down"), null, tint = m.ink2, modifier = Modifier.size(18.dp))
    }
}

// ── AI 伙伴 ──────────────────────────────────────────────────────────────

/** [Mines-38.7] a member whose explanation AI 伙伴 should open on arrival (from 新建群聊 「怎么接」). */
object PartnersNav { @Volatile var open: String? = null }

/** [Mines-38.5] one way to connect a service: the 账号连接 detail opens and 一键接入 starts at once (KeyAgent signs in, takes the key). */
private fun connect(kind: String) {
    PlusNav.openKind = kind; PlusNav.autoClaim = true; PlusNav.go(PlusRoutes.CONNECTIONS)
}

@Composable
fun PartnersScreen(onBack: () -> Unit, go: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    GroupStore.init(ctx)
    val partners by Partners.list.collectAsState()
    val apps by Partners.apps.collectAsState()
    val vault by com.openminis.app.plus.PlusVault.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Partner?>(null) }
    var info by remember { mutableStateOf<Partner?>(null) }
    var askPhone by remember { mutableStateOf<Partner?>(null) }
    var addCustom by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(vault.keys.joinToString(",") + vault.values.sumOf { it.updatedAt }) { Partners.refresh(ctx, rescanApps = busy); busy = false }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { Partners.refresh(ctx) } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val m = mines()
    fun reload() = scope.launch { Partners.refresh(ctx) }
    fun chat(p: Partner) { go(GroupRoutes.chat(GroupStore.direct(p.id, p.spec.name).id)) }
    // [Mines-38.7] arriving from 新建群聊 「怎么接」: no dialog on top of AI 伙伴 (testers read it as a "Muse dialog");
    // the member's row is tinted and its explanation opens inline under it
    var howto by remember { mutableStateOf<String?>(PartnersNav.open) }
    // [Mines-38.8] Muse 中继 / Memoh sign in with their own one-step-at-a-time dialog (also when arriving from 新建群聊)
    var presetLogin by remember { mutableStateOf(PartnersNav.open?.takeIf { com.openminis.app.plus.group.PresetRegistry.ownLogin(it) }) }
    LaunchedEffect(Unit) { PartnersNav.open = null }

    PlusPage("AI 伙伴", onBack, subtitle = "智能体和你登录过的 AI App；点「聊天」直接聊，也能拉进群",
        actions = { if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp) }) {
        val agents = partners.filter { it.spec.kind == Kind.AGENT }.sortedBy { if (it.ready) 0 else 1 }
        val webApps = partners.filter { it.spec.kind == Kind.APP }.sortedBy { if (it.ready) 0 else 1 }
        PlusSection("智能体 · ${agents.count { it.ready }} 个能用") {
            if (agents.isEmpty()) InfoLine("正在识别…")
            agents.forEachIndexed { i, p ->
                if (i > 0) com.openminis.app.ui.plus.WarmDivider(68.dp)
                val open = howto == p.id
                Column(if (open) Modifier.background(m.container.copy(alpha = 0.45f)) else Modifier) {
                    PartnerRow(p, onClick = { info = p }, trailing = {
                        if (p.ready) ActionPill("聊天") { chat(p) }
                        else ActionPill(fixLabel(p)) { if (usesSiteLogin(p.id)) go(SiteLogin.route(com.openminis.app.plus.group.PresetRegistry.MEMOH)) else if (com.openminis.app.plus.group.PresetRegistry.ownLogin(p.id)) presetLogin = p.id else fixPartner(p, go) { howto = if (open) null else p.id } }
                    })
                    if (open && !p.ready) HowToInline(p, onManual = { edit = p })
                }
            }
        }
        PlusSection("App 账号 · ${webApps.count { it.ready }} 个已登录", footer = "登录一次网页版就行；同一个 App 可以登录多个账号（点它 →「再登录一个账号」），各自分开") {
            webApps.forEachIndexed { i, p ->
                if (i > 0) com.openminis.app.ui.plus.WarmDivider(68.dp)
                PartnerRow(p, onClick = { info = p }, trailing = {
                    if (p.ready) ActionPill("聊天") { chat(p) } else ActionPill("登录") { go(GroupRoutes.webLogin(p.id)) }
                })
            }
        }
        // phone apps that can only be driven by 代打字 (or not at all)
        val readyIds = partners.filter { it.ready }.map { it.id }.toSet()
        val appRows = apps.filter { f -> f.known.verdict != PhoneApps.Verdict.API }.distinctBy { f -> f.known.partner ?: f.pkg }
        if (appRows.isNotEmpty()) PlusSection("手机上的 AI App", footer = "「代打字」= Mines 用无障碍替你在那个 App 里打字、读回复，慢、可能断，默认关闭。微信、QQ 不做：自动收发会触发风控封号。") {
            appRows.forEachIndexed { i, f ->
                if (i > 0) com.openminis.app.ui.plus.WarmDivider(16.dp)
                val v = f.known.verdict
                val phoneId = PhoneApps.idFor(f.pkg)
                val on = partners.firstOrNull { it.id == phoneId }?.ready == true
                val status = when (v) {
                    PhoneApps.Verdict.TYPING -> if (on) "已开代打字 · 慢、可能断" else "可以代打字（慢、可能断）"
                    PhoneApps.Verdict.NO -> "接不了：" + f.known.reason
                    else -> f.known.reason
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, fontSize = MT.Sub, lineHeight = MT.SubLH, color = if (v == PhoneApps.Verdict.NO) m.ink3 else m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(MS.M))
                    if (v == PhoneApps.Verdict.TYPING) Switch(on, { want ->
                        if (want) askPhone = partners.firstOrNull { it.id == phoneId }
                        else { GroupPrefs.setPhoneOn(phoneId, false); reload() }
                    })
                }
            }
        }
        // bare model APIs: folded, closed by default
        val api = partners.filter { it.spec.kind == Kind.MODEL && it.spec.proto != Proto.PHONE }
        FoldHeader("高级：直接用模型（${api.count { it.ready }} 个能用）", advanced) { advanced = !advanced }
        if (advanced) {
            val ready = api.filter { it.ready }
            val notYet = api.filter { !it.ready }
            PlusSection("能直接用 · ${ready.size}", footer = if (ready.isNotEmpty()) "点一个可以换模型" else null) {
                if (ready.isEmpty() && !busy) InfoLine("还没有。下面点「一键连接」")
                ready.forEachIndexed { i, p ->
                    if (i > 0) com.openminis.app.ui.plus.WarmDivider(68.dp)
                    PartnerRow(p, onClick = { edit = p })
                }
            }
            if (notYet.isNotEmpty()) PlusSection("还没连接 · ${notYet.size}") {
                notYet.forEachIndexed { i, p ->
                    if (i > 0) com.openminis.app.ui.plus.WarmDivider(68.dp)
                    PartnerRow(p, trailing = {
                        ActionPill(if (p.spec.vaultKind != null) "一键连接" else "怎么接") { if (p.spec.vaultKind != null) connect(p.spec.vaultKind) else edit = p }
                    })
                }
            }
            val apiApps = apps.filter { f -> f.known.verdict == PhoneApps.Verdict.API && f.known.partner != null && f.known.partner !in readyIds }
                .distinctBy { it.known.partner }
            if (apiApps.isNotEmpty()) PlusSection("手机上有、可用接口连接的") {
                apiApps.forEachIndexed { i, f ->
                    if (i > 0) com.openminis.app.ui.plus.WarmDivider(16.dp)
                    val kind = f.known.partner?.let { AiCatalog.spec(it)?.vaultKind }
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.known.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.known.reason, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(MS.M))
                        if (kind != null) ActionPill("一键连接") { connect(kind) }
                    }
                }
            }
            PlusSection("自定义") {
                PlusRow(icon("plus"), "添加自己的接口", "自建中继、公司网关") { addCustom = true }
            }
        }
        PresetExtrasSection(go)  // [Mines-38.8] Muse 网页版 switch, bridge addresses, Mines × Muse group
    }

    info?.let { p -> PartnerInfoDialog(p, onDismiss = { info = null }, onChat = { info = null; chat(p) },
        onFix = { info = null; if (usesSiteLogin(p.id)) go(SiteLogin.route(com.openminis.app.plus.group.PresetRegistry.MEMOH)) else if (com.openminis.app.plus.group.PresetRegistry.ownLogin(p.id)) presetLogin = p.id else fixPartner(p, go) { edit = p } },
        // [Mines-38.7] several accounts per web app, each isolated; sign one out without touching the others
        onAddAccount = if (p.spec.proto == Proto.WEBAPP) ({ info = null; go(GroupRoutes.webLogin(WebAccounts.add(WebAccounts.site(p.id)))); Unit }) else null,
        onSignOut = if (p.spec.proto == Proto.WEBAPP && p.ready) ({ info = null; scope.launch { WebChat.signOut(p.spec, p.id); Partners.refresh(ctx) }; Unit }) else null,
        onRemove = if (p.spec.proto == Proto.WEBAPP && p.id.contains('#')) ({ info = null; scope.launch { WebChat.signOut(p.spec, p.id); WebAccounts.drop(p.id); Partners.refresh(ctx) }; Unit }) else null) }
    edit?.let { p ->
        PartnerEditDialog(p, onDismiss = { edit = null }, onSaved = { edit = null; reload() })
    }
    presetLogin?.let { id -> partners.firstOrNull { it.id == id }?.let { p -> PresetLoginDialog(p, onDismiss = { presetLogin = null }, onDone = { presetLogin = null; reload() }) } }
    askPhone?.let { p ->
        com.openminis.app.ui.plus.MinesDialog("开启「${p.spec.name}」代打字？", onDismiss = { askPhone = null },
            confirm = "开启", onConfirm = { GroupPrefs.setPhoneOn(p.id, true); askPhone = null; reload() }, dismiss = "先不用") {
            Text("轮到它发言时，Mines 会打开 ${p.spec.name}，替你打字、发送，等它回完再读回来。每条 20–90 秒，App 改版或弹窗时可能断。",
                fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        }
    }
    if (addCustom) {
        var n by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        var k by remember { mutableStateOf("") }
        var md by remember { mutableStateOf("") }
        com.openminis.app.ui.plus.MinesDialog("添加接口", onDismiss = { addCustom = false }, confirm = "添加", confirmEnabled = b.startsWith("http"),
            onConfirm = { GroupPrefs.addCustom(n.ifBlank { "自定义" }, b, k, md); addCustom = false; reload() }) {
            OutlinedTextField(n, { n = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b, { b = it }, label = { Text("地址，如 https://x.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(k, { k = it }, label = { Text("密钥（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())  // [Mines-38.9.11 b] keys masked
            OutlinedTextField(md, { md = it }, label = { Text("模型（可空，自动选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * [Mines-38.6] What an agent / app is, in plain words, with 「直接聊」 / the one fix; Bot ID, address and the like only
 * under 「详情」 (folded).
 */
@Composable
private fun PartnerInfoDialog(p: Partner, onDismiss: () -> Unit, onChat: () -> Unit, onFix: () -> Unit,
                              onAddAccount: (() -> Unit)? = null, onSignOut: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val m = mines()
    var open by remember(p.id) { mutableStateOf(false) }
    com.openminis.app.ui.plus.MinesDialog(p.spec.name, onDismiss = onDismiss,
        confirm = if (p.ready) "直接聊" else fixLabel(p), onConfirm = { if (p.ready) onChat() else onFix() }, dismiss = "关闭") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartnerAvatar(p, p.id, p.spec.name, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(statusLine(p), fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        }
        if (p.spec.note.isNotBlank() && statusLine(p) != p.spec.note) Text(p.spec.note, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
        if (p.reason?.contains("重新登录") == true) com.openminis.app.ui.plus.GlassTextButton("重新登录") { onFix() }
        if (onAddAccount != null || onSignOut != null || onRemove != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            onAddAccount?.let { com.openminis.app.ui.plus.GlassTextButton("再登录一个账号") { it() } }
            onSignOut?.let { com.openminis.app.ui.plus.GlassTextButton("退出这个账号") { it() } }
            onRemove?.let { com.openminis.app.ui.plus.GlassTextButton("移除") { it() } }
        }
        if (onAddAccount != null) Text("每个账号单独登录、单独保存，退出一个不影响别的", fontSize = MT.Meta, color = m.ink3)
        com.openminis.app.ui.plus.GlassTextButton(if (open) "收起详情" else "详情") { open = !open }
        if (open) SelectionContainer {
            val lines = buildList {
                if (p.id.startsWith("coze:")) add("Bot ID：" + p.id.removePrefix("coze:"))
                if (p.base.isNotBlank()) add("地址：" + p.base)
                if (p.model.isNotBlank() && p.spec.kind == Kind.MODEL) add("模型：" + p.model)
                if (p.via.isNotBlank()) add("来源：" + p.via)
                if (p.key != null) add("令牌：••••" + p.key.takeLast(4))
            }
            Text(lines.joinToString("\n"), fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2)
        }
    }
}

/**
 * [Mines-38.6] Sign in to an AI app's web chat once: full-screen page in the shared cookie store; 「登录好了」 checks that
 * the chat box shows (no login wall) and marks the app 已登录.
 */
@Composable
fun WebAppLoginScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val spec = AiCatalog.spec(id)
    val m = mines()
    if (spec == null) { PlusPage("登录", onBack) { InfoLine("找不到这个 App") }; return }
    // [Mines-38.7] every account signs in inside its OWN store (profile, or the shared store swapped to it): the platform's
    // other accounts are never touched. The page is created only after the store is ready.
    val site = WebAccounts.site(id)
    val url = GroupPrefs.get("base_$site") ?: spec.webUrl
    val name = WebAccounts.display(AiCatalog.spec(site)?.name ?: spec.name, id)
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("登录后点右上角「登录好了」") }
    var reloadKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lock = remember(id) { com.openminis.app.plus.AccountJar.lock(id) }
    androidx.compose.runtime.DisposableEffect(id) {
        var held = false
        val job = scope.launch {
            lock.lock(); held = true
            com.openminis.app.plus.AccountJar.activate(id, WebChat.urls(spec, id))
            ready = true
        }
        onDispose {
            job.cancel()
            web?.let { w -> runCatching { w.evaluateJavascript(com.openminis.app.plus.AccountJar.DUMP_LOCAL_JS) { r ->
                com.openminis.app.plus.AccountJar.saveLocal(id, runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull())
                com.openminis.app.plus.AccountJar.rememberNow(id, WebChat.urls(spec, id))
                runCatching { w.destroy() }
            } } }
            if (held) runCatching { lock.unlock() }
        }
    }
    // [Mines-38.7] phone number + SMS code filled in for the user; in 一键关联 also 获取验证码 / 登录 pressed
    var gone by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { gone = true } }
    LaunchedEffect(web) {
        if (web == null) return@LaunchedEffect
        val auto = com.openminis.app.ui.plus.LinkAll.autoLogin
        if (auto) launch { com.openminis.app.ui.plus.LinkAll.watch(id, { web }, { gone }) { note = "已关联 ✓"; if (!gone) onBack() } }
        com.openminis.app.plus.LoginAssist.run(ctx, { web }, auto = auto, isDone = { gone },
            onStatus = { note = it }, needsUser = { st ->
                if (auto) com.openminis.app.ui.plus.LinkAll.setNote(id, st)
                note = if (st == "扫码") "请用手机上的 ${spec.name} App 扫一下页面上的二维码" else "页面要你过一下人机验证，按页面提示做一下" })
    }
    Box(Modifier.fillMaxSize()) {
        MinesBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = "返回" }, contentAlignment = Alignment.Center) {
                    MinesIcon(icon("arrow-left"), null, tint = m.ink)
                }
                Text("登录 $name", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ActionPill("登录好了") {
                    val w = web ?: return@ActionPill
                    w.evaluateJavascript(WebChat.probeJs(WebChat.site(id))) { r ->
                        val o = runCatching { org.json.JSONObject(org.json.JSONTokener(r ?: "\"\"").nextValue() as? String ?: "{}") }.getOrNull()
                        val st = o?.let { WebChat.classifyFor(spec, id, it) }
                        com.openminis.app.plus.LoginWeb.flush()
                        when {
                            st == WebChat.PageState.SIGNED_IN || WebChat.signedIn(spec, id) && st != WebChat.PageState.LOGIN -> {
                                WebChat.markSignedIn(id, true)
                                com.openminis.app.plus.AccountJar.rememberNow(id, WebChat.urls(spec, id))
                                scope.launch { Partners.refresh(ctx) }
                                onBack()
                            }
                            st == WebChat.PageState.VERIFY -> note = "页面在做人机验证：按页面提示完成，再登录"
                            st == WebChat.PageState.GUEST -> note = "页面上还有「登录」按钮，像是还没登录完。登录后再点一次"
                            else -> note = "还没看到聊天框，像是还没登录完。登录后再点一次"
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            // [Mines-38.8] note as one frosted-glass strip (no bare text on the fog band)
            Text(note, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(com.openminis.app.ui.plus.glassVeil())
                .border(1.dp, m.glassBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp)
                .semantics { contentDescription = note })
            // [Mines-38.7 v11d] smoke 38758822905: the sign-in screen still caught blank. The page is shown at its first frame or
            // 1.2 s at the latest, and a spinner sits on the backdrop until then: never an empty area.
            if (ready) Box(Modifier.fillMaxWidth().weight(1f)) { androidx.compose.runtime.key(reloadKey) { androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                android.webkit.WebView(c).also { w ->
                    // [Mines-38.7] fixed size: a WRAP_CONTENT WebView re-lays out as the page grows, which restarted the
                    // keyboard connection on each key (DeepSeek phone field: 123 came out 321, cursor back at the start)
                    w.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    w.isFocusable = true; w.isFocusableInTouchMode = true
                    // [Mines-38.7 v11] no white flash: smoke 38735737556 caught the whole screen white for seconds while
                    // 一键关联 opened a sign-in page. The page stays invisible on the Mines backdrop (spinner shown below)
                    // until it has painted its first frame.
                    w.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    w.visibility = android.view.View.INVISIBLE
                    com.openminis.app.plus.AccountJar.prepare(w, id)
                    com.openminis.app.plus.LoginWeb.configure(w)
                    w.postDelayed({ w.visibility = android.view.View.VISIBLE; shown = true }, 1200)   // a page that never reports a frame still shows
                    w.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageCommitVisible(view: android.webkit.WebView, u: String) { view.visibility = android.view.View.VISIBLE; shown = true }
                        override fun onPageFinished(view: android.webkit.WebView, u: String) { view.visibility = android.view.View.VISIBLE; shown = true; com.openminis.app.plus.LoginWeb.flush() }
                        override fun onReceivedError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                            if (req.isForMainFrame) com.openminis.app.plus.WebLoginDiag.note(id, "main: ${err.errorCode} ${err.description}")
                        }
                        override fun onReceivedHttpError(view: android.webkit.WebView, req: android.webkit.WebResourceRequest, r: android.webkit.WebResourceResponse) {
                            if (req.isForMainFrame) com.openminis.app.plus.WebLoginDiag.noteHttp(id, r.statusCode, r.reasonPhrase, r.responseHeaders)
                        }
                        // [Mines-38.7] a crashed page never takes the app down: drop it and open a fresh one
                        override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            com.openminis.app.ui.webview.WebViewRenderProcess.handle("webapp-login", detail)
                            note = "页面刚才崩了一下，已重新打开"
                            web = null
                            runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
                            runCatching { view.destroy() }
                            reloadKey++
                            return true
                        }
                        // [Mines-38.7] Google / Apple buttons are blocked inside apps: stay on the site's own sign-in
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                            val u = request.url.toString()
                            if (com.openminis.app.browser.GoogleAuthRouter.shouldRouteExternally(u)) {
                                note = "「用 Google / Apple 登录」在 App 里会被拦。请用这个网站自己的账号、手机号或扫码登录"; return true
                            }
                            return !u.startsWith("http")
                        }
                    }
                    // [Mines-38.9.7] white-screen diagnosis: main-frame / HTTP / console errors → WebLoginDiag (自检)
                    w.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(cm: android.webkit.ConsoleMessage): Boolean {
                            if (cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) com.openminis.app.plus.WebLoginDiag.note(id, "console: " + cm.message())
                            return false
                        }
                    }
                    w.loadUrl(url); web = w
                }
            }, modifier = Modifier.fillMaxSize()) }
                if (!shown) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = m.primary) }
                // [Mines-38.9.7] nothing visible after 12 s (or the main frame failed) → say so, never a bare white page
                var blank by remember(reloadKey) { mutableStateOf<String?>(null) }
                LaunchedEffect(reloadKey, web) {
                    val w = web ?: return@LaunchedEffect
                    kotlinx.coroutines.delay(com.openminis.app.plus.WebLoginDiag.BLANK_AFTER_MS)
                    w.evaluateJavascript(com.openminis.app.plus.LoginWeb.BLANK_JS) { r ->
                        val isBlank = (r ?: "").contains("blank")
                        val why = com.openminis.app.plus.WebLoginDiag.reasonFor(id)
                        // [Mines-38.9.11 b] a Cloudflare / Turnstile check on screen is never covered by the error card
                        if ((isBlank || why != null) && !com.openminis.app.plus.WebLoginDiag.challengeActive(id)) { blank = why ?: "页面一直是空白（网站没响应，或被网络拦了）"; com.openminis.app.plus.WebLoginDiag.note(id, "blank after 12s: " + (why ?: "no content")) }
                    }
                }
                blank?.let { why ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(20.dp)).background(com.openminis.app.ui.plus.glassVeil())
                        .border(1.dp, m.glassBorder, RoundedCornerShape(20.dp)).padding(18.dp)) {
                        Text("页面没打开", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
                        Text(why, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill("重新加载") { blank = null; web = null; shown = false; reloadKey++ }
                            ActionPill("用浏览器打开") { runCatching { com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(ctx, url) } }
                        }
                    }
                }
            }
            else Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = m.primary) }
        }
    }
}

/** Uniform trailing action in the partner lists: 96 dp min, 40 dp tall terracotta pill, never wraps. */
@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    // [Mines-38.9.1] 聊天 = filled accent; 登录 / 怎么接 / 去登录 = outlined tonal (different look, user 2026-10-16)
    com.openminis.app.ui.plus.ActionPill2(text, primary = text == "聊天", onClick = onClick)
}

/**
 * [Mines-38.5] Member settings as a choice, not a text field: the models this key can actually use (listed from the
 * provider, small/fast first) with 「自动」 on top; 扣子 lists your published bots. Free typing stays under 「手动填写」.
 */
@Composable
private fun PartnerEditDialog(p: Partner, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val m = mines()
    var model by remember(p.id) { mutableStateOf(GroupPrefs.model(p.id) ?: "") }
    var bot by remember(p.id) { mutableStateOf(GroupPrefs.bot(p.id) ?: "") }
    var manual by remember(p.id) { mutableStateOf(false) }
    val options by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, p.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (p.spec.proto) {
                Proto.COZE -> p.key?.let { com.openminis.app.plus.group.CozeBots.list(it) } ?: emptyList()
                Proto.OPENAI, Proto.ANTHROPIC -> if (p.ready) com.openminis.app.plus.group.AiClient.listModels(p).take(40).map { it to it } else emptyList()
                else -> emptyList()
            }
        }
    }
    val explain = when {
        p.spec.id == "nanomuse" -> "这是你自己部署的中继。填上它的地址并登录后，会出现在「能直接用」。"
        p.spec.id == "workbuddy" -> "打开手机上的 WorkBuddy，回到这里会自动接上。"
        else -> null
    }
    com.openminis.app.ui.plus.MinesDialog(p.spec.name, onDismiss = onDismiss,
        confirm = if (explain == null) "保存" else null,
        onConfirm = {
            if (p.spec.proto == Proto.COZE) GroupPrefs.setBot(p.id, bot.ifBlank { null }) else GroupPrefs.setModel(p.id, model.ifBlank { null })
            onSaved()
        }, dismiss = if (explain == null) "取消" else "知道了") {
        if (explain != null) { Text(explain, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink); return@MinesDialog }
        if (p.spec.note.isNotBlank()) Text(p.spec.note, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
        val isCoze = p.spec.proto == Proto.COZE
        val current = if (isCoze) bot else model
        Text(if (isCoze) "选一个智能体" else "用哪个模型", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2)
        if (!isCoze) ChoiceRow("自动（${p.model.ifBlank { "推荐" }}）", current.isBlank()) { model = "" }
        when (val o = options) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text("正在读取可用的" + if (isCoze) "智能体…" else "模型…", fontSize = MT.Sub, color = m.ink2)
            }
            else -> {
                if (o.isEmpty()) Text(if (isCoze) "没找到已发布的智能体：在扣子里发布一个，回来自动出现。" else "这个服务没给出模型列表，用自动就好。",
                    fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
                o.forEach { (id, label) -> ChoiceRow(if (label.isNotBlank() && label != id) "$label · $id" else id, current == id) { if (isCoze) bot = id else model = id } }
            }
        }
        if (p.spec.proto != Proto.PHONE) {
            if (!manual) com.openminis.app.ui.plus.GlassTextButton("手动填写") { manual = true }
            else OutlinedTextField(current, { if (isCoze) bot = it else model = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isCoze) "智能体 Bot ID" else if (p.spec.proto == Proto.MANUS) "档位（lite / standard / max）" else "模型 ID") })
        }
        if (p.id.startsWith("custom:")) com.openminis.app.ui.plus.GlassTextButton("删除这个接口", color = MinesDesign.Danger) { GroupPrefs.removeCustom(p.id); onSaved() }
    }
}

@Composable
private fun ChoiceRow(text: String, on: Boolean, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) m.container else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (on) MinesIcon(icon("check"), null, tint = m.icon, modifier = Modifier.size(18.dp))
    }
}

/** [Mines-38.7] 「怎么接」 explained in place (no dialog): what the member is, what it needs, one optional manual step. */
@Composable
private fun HowToInline(p: Partner, onManual: () -> Unit) {
    val m = mines()
    Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 12.dp)) {
        val why = p.reason?.takeIf { it.isNotBlank() }
        Text(listOfNotNull(p.spec.note.ifBlank { null }, why?.let { "现在：$it" }).joinToString("\n").ifBlank { "这个成员需要一个能访问的地址才能进群。" },
            fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
        Spacer(Modifier.height(8.dp))
        Text("它不用登录账号：地址能连上后会自动变成「能用」。也可以自己填地址。", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink3)
        Spacer(Modifier.height(8.dp))
        ActionPill("手动填写地址", onManual)
    }
}

@Composable
private fun PartnerRow(p: Partner, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PartnerAvatar(p, p.id, p.spec.name, 40.dp)
        Spacer(Modifier.width(MS.M))
        Column(Modifier.weight(1f)) {
            Text(p.spec.name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.state == Partner.State.READY && p.spec.kind != Kind.MODEL) {
                // [Mines-38.7] agents / apps: one plain line about what it is (Manus showed its tier "standard" as if it were a model)
                Text(statusLine(p), fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (p.state == Partner.State.READY) {
                // model on its own line, never broken mid-word (single line + ellipsis); balance shown once, in amber when low
                if (p.model.isNotBlank()) Text(p.model, fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                val bal = p.balance?.removePrefix("余额")?.trim()
                val meta = listOfNotNull(p.via.ifBlank { null }, bal?.let { "余额 $it" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, fontSize = MT.Meta, color = if (p.lowBalance) (if (m.dark) MinesDesign.WarningDark else MinesDesign.Warning) else m.ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(p.reason ?: p.spec.note.ifBlank { "还没连接" }, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(MS.S)); trailing() }
        else if (p.ready) { Spacer(Modifier.width(MS.S)); StatusPill(if (p.lowBalance) "余额低" else "能用", if (p.lowBalance) null else true) }
    }
}

// ── 群聊 ─────────────────────────────────────────────────────────────────

@Composable
fun GroupChatScreen(gid: String, onBack: () -> Unit, onPartners: () -> Unit) {
    val ctx = LocalContext.current
    GroupStore.init(ctx)
    val groups by GroupStore.groups.collectAsState()
    val g: AiGroup = groups.firstOrNull { it.id == gid } ?: run {
        PlusPage("群聊", onBack) { InfoLine("这个群已经不存在了") }
        return
    }
    val msgs by remember(gid) { GroupStore.messages(gid) }.collectAsState()
    val partners by Partners.list.collectAsState()
    val running by GroupRunner.running.collectAsState()
    val busy = gid in running
    val members = g.members.mapNotNull { id -> partners.firstOrNull { it.id == id } }
    // [Mines-38.7] 单聊 (one member, no host): no 「发给大家」, no member strip, no 总结 — it reads like a normal chat
    val direct = g.members.size == 1 && !g.host
    // leaving the window = this conversation ended for now → 知识库 organises it (3 s later)
    androidx.compose.runtime.DisposableEffect(gid) { onDispose { com.openminis.app.plus.ChatKnowledge.touch(ctx, "group:$gid") } }
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val m = mines()
    LaunchedEffect(gid) {
        PhoneTyping.returnRoute = GroupRoutes.chat(gid)
        if (Partners.list.value.isEmpty()) Partners.refresh(ctx)
        Partners.refreshBalances(g.members); Partners.refresh(ctx)
    }
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.status) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    Box(Modifier.fillMaxSize()) {
        MinesBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = "返回" }, contentAlignment = Alignment.Center) {
                    MinesIcon(icon("arrow-left"), null, tint = m.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontSize = MT.PageTitle, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val tok = GroupRunner.tokens(msgs)
                    Text((if (direct) (members.firstOrNull()?.let { statusLine(it) } ?: "单聊") else "${members.size + if (g.host) 1 else 0} 位成员") +
                        (if (tok > 0) " · ${fmtTok(tok)} tokens" else "") + if (busy) " · 正在回复" else "",
                        fontSize = MT.Meta, color = m.ink2, maxLines = 1)
                }
                if (g.host) Text("总结", fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = if (busy) m.ink3 else m.primaryText,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy && msgs.isNotEmpty()) { GroupRunner.summarize(ctx, gid) }
                        .padding(horizontal = 12.dp, vertical = 10.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).clickable { menu = true }.semantics { contentDescription = "更多" }, contentAlignment = Alignment.Center) {
                    MinesIcon(icon("ellipsis"), null, tint = m.ink)
                }
            }
            // member strip + balance warnings (group only)
            if (!direct) LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.host) item { MemberChip(null, GroupStore.HOST, "Mines", "主持") { input = mention(input, "Mines") } }
                items(members, key = { it.id }) { p ->
                    MemberChip(p, p.id, GroupRunner.shortName(p.spec.name), when {
                        p.state == Partner.State.NEED_LOGIN -> "需登录"; !p.ready -> "不可用"; p.lowBalance -> "余额低"; p.spec.proto == Proto.PHONE -> "代打字"; else -> null
                    }) { input = mention(input, GroupRunner.shortName(p.spec.name)) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (msgs.isEmpty()) item {
                    MinesCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            if (direct) {
                                Text("和 ${g.name} 单聊", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
                                Text("直接说就行，它会回你。聊完离开，要点会自动整理进知识库。", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
                            } else {
                                Text("说点什么，大家轮流回你", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
                                Text("用 @名字 只让某一位回答；点上面的头像可以快速 @。每轮最后 Mines 会收一下，右上「总结」出一份完整总结。", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
                            }
                        }
                    }
                }
                items(msgs, key = { it.id }) { msg ->
                    Bubble(msg, partners.firstOrNull { it.id == msg.who }, onRetry = { GroupRunner.retry(ctx, gid, msg.who) },
                        onFix = onPartners, canRetry = !busy)
                }
            }
            // input
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp)).background(com.openminis.app.ui.plus.dockFill()).border(1.dp, m.glassBorder, RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(input, { input = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = MT.Body, color = m.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(m.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 10.dp).semantics { contentDescription = if (direct) "单聊输入框" else "群聊输入框" },
                    maxLines = 5,
                    decorationBox = { inner -> Box { if (input.isEmpty()) Text(if (direct) "和 ${GroupRunner.shortName(g.name)} 说点什么" else "发给大家，或 @某个 AI",
                        fontSize = MT.Body, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
                val canSend = input.isNotBlank()
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (busy) MinesDesign.DangerFill else if (canSend) m.primary else m.ink3.copy(alpha = 0.3f))
                    .clickable(enabled = busy || canSend) {
                        if (busy) GroupRunner.stop(gid) else { GroupRunner.send(ctx, gid, input); input = "" }
                    }.semantics { contentDescription = if (busy) "停止" else "发送" }, contentAlignment = Alignment.Center) {
                    if (busy) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                    else MinesIcon(icon("send"), null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    if (menu) {
        AlertDialog(onDismissRequest = { menu = false }, title = { Text(g.name) },
            text = {
                Column {
                    if (!direct) {
                        TextButton({ GroupStore.update(g.copy(sequential = !g.sequential)); menu = false }) {
                            Text(if (g.sequential) "改成：同时回复（更快）" else "改成：依次回复（能看到前面的回答）")
                        }
                        TextButton({ GroupStore.update(g.copy(host = !g.host)); menu = false }) { Text(if (g.host) "不要 Mines 主持" else "让 Mines 主持") }
                    }
                    TextButton({ menu = false; onPartners() }) { Text("AI 伙伴与成员设置") }
                    TextButton({ GroupRunner.stop(gid); GroupStore.clear(gid); menu = false }) { Text("清空聊天记录") }
                    TextButton({ GroupRunner.stop(gid); GroupStore.delete(gid); menu = false; onBack() }) { Text(if (direct) "删除这个单聊" else "删除这个群", color = MinesDesign.Danger) }
                }
            },
            confirmButton = { TextButton({ menu = false }) { Text("关闭") } })
    }
}

private fun mention(input: String, name: String): String {
    val tag = "@$name "
    return if (input.contains(tag)) input else (if (input.isBlank() || input.endsWith(" ")) input else "$input ") + tag
}

private fun fmtTok(n: Int) = if (n >= 10_000) String.format("%.1f万", n / 10_000.0) else n.toString()

@Composable
private fun MemberChip(p: Partner?, who: String, name: String, badge: String?, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        .semantics { contentDescription = "@$name" }, verticalAlignment = Alignment.CenterVertically) {
        PartnerAvatar(p, who, name, 30.dp)
        Spacer(Modifier.width(6.dp))
        Text(name, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1)
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            Text(badge, fontSize = MT.Meta, color = if (badge == "不可用" || badge == "余额低") MinesDesign.Warning else m.ink3, maxLines = 1)
        }
    }
}

@Composable
private fun Bubble(msg: GroupMsg, p: Partner?, onRetry: () -> Unit, onFix: () -> Unit, canRetry: Boolean) {
    val m = mines()
    if (msg.who == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(msg.text, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.onPrimary,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(m.primary)
                        .padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        return
    }
    val isHost = msg.who == GroupStore.HOST
    // [Mines-38.5] quiet one-liners for 「已停止」 and skipped members: small grey text next to the avatar, no bubble
    if (msg.status == "stopped" || msg.status == "skip") {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PartnerAvatar(p, msg.who, msg.name, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(GroupRunner.shortName(msg.name) + if (msg.status == "skip") (if (msg.detail == "需登录") " · 需登录，这轮跳过" else " · 没连接，这轮跳过") else " · 已停止",
                fontSize = MT.Meta, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    // [Mines-38.6] the 讨论总结 bubble takes the full width (it read as a narrow column of short lines)
    val wide = msg.kind == "summary" && msg.status == "ok"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PartnerAvatar(p, msg.who, msg.name, 34.dp)
        Spacer(Modifier.width(MS.S))
        Column(if (wide) Modifier.weight(1f) else Modifier.weight(1f, fill = false).widthIn(max = 320.dp)) {
            // name (14 sp SemiBold ink) + model (12 sp ink3, one line) — same hierarchy as the member chips
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isHost) "Mines · 主持" else GroupRunner.shortName(msg.name), fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1)
                if (msg.model.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(msg.model, fontSize = MT.Meta, color = m.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                }
            }
            Spacer(Modifier.heightIn(min = 4.dp))
            val shape = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
            when (msg.status) {
                "pending" -> Row(Modifier.clip(shape).background(minesGlassFill(strong = true)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    // [Mines-38.6] slow agents: 「正在思考… 12 秒」, and after 40 s a calm note that nobody waits for it
                    val sec by androidx.compose.runtime.produceState(((System.currentTimeMillis() - msg.at) / 1000).toInt(), msg.id) {
                        while (true) { kotlinx.coroutines.delay(1000); value = ((System.currentTimeMillis() - msg.at) / 1000).toInt() }
                    }
                    val slow = p?.spec?.proto == Proto.MANUS || p?.spec?.proto == Proto.WEBAPP || p?.spec?.proto == Proto.MINES
                    Column {
                        Text((if (p?.spec?.proto == Proto.PHONE) "正在 ${p.spec.name} 里代打字…" else if (msg.kind == "summary") "正在总结…" else "正在思考…") +
                            if (sec >= 3) " $sec 秒" else "", fontSize = MT.Sub, color = m.ink2)
                        if (slow && sec >= 40) Text("${GroupRunner.shortName(msg.name)} 回得慢，别的成员不用等它", fontSize = MT.Meta, color = m.ink3)
                    }
                }
                "error" -> {
                    // compact one-line error: friendly Chinese, actions inline, the raw server text folded under 「详情」
                    var open by remember(msg.id) { mutableStateOf(false) }
                    val red = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(red.copy(alpha = 0.07f)).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MinesIcon(icon("info"), null, tint = red, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(friendly(msg.text), fontSize = MT.Sub, color = red, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (canRetry) SmallLink("重试", onRetry)
                            if (msg.detail.isNotBlank()) SmallLink(if (open) "收起" else "详情") { open = !open }
                        }
                        if (open) {
                            SelectionContainer { Text(msg.detail, fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2, modifier = Modifier.padding(bottom = 6.dp, end = 8.dp)) }
                            if (msg.text.contains("模型") || msg.text.contains("密钥") || msg.text.contains("余额")) SmallLink("去成员设置", onFix)
                        }
                    }
                }
                else -> {
                    val summary = msg.kind == "summary"
                    // [Mines-38.7] the 讨论总结 bubble itself fills the row (38.6 widened only its column, the bubble still hugged the text)
                    Column((if (summary) Modifier.fillMaxWidth() else Modifier).clip(shape).background(if (isHost && msg.kind.isNotEmpty()) m.container.copy(alpha = if (m.dark) 0.5f else 0.7f) else minesGlassFill(strong = true))
                        .border(1.dp, m.glassBorder, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (summary) {
                            Text("讨论总结", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.primaryText, modifier = Modifier.padding(bottom = 2.dp))
                            SelectionContainer { SummaryBody(msg.text) }
                        } else SelectionContainer { Text(msg.text, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink) }
                    }
                    val meta = listOfNotNull(msg.detail.ifBlank { null }, if (msg.ms > 0) "%.1f 秒".format(msg.ms / 1000.0) else null,
                        if (msg.inTok + msg.outTok > 0) "${msg.inTok + msg.outTok} tokens" else null).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, fontSize = MT.Meta, color = m.ink3, maxLines = 2, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    val m = mines()
    Text(text, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 9.dp))
}

/** Old messages kept the long English error; show only the friendly part (before the first colon / bracket). */
private fun friendly(t: String): String {
    val x = t.removePrefix("没回上：")
    return when {
        x.startsWith("模型不存在") || x.contains("does not exist") -> "模型不可用，可在成员设置换模型"
        x.contains("429") || x.contains("限流") -> "现在太忙（被限流），稍后重试"
        else -> x.substringBefore("：http").substringBefore("（HTTP").take(60)
    }
}

/**
 * [Mines-38.6] structured summary: each 「## 结论」/「【结论】」 heading becomes a small terracotta sub-title with its points as
 * body text under it (not one 【】 line per item).
 */
@Composable
private fun SummaryBody(t: String) {
    val m = mines()
    data class Sec(val title: String?, val body: MutableList<String> = mutableListOf())
    val secs = mutableListOf(Sec(null))
    for (raw in t.lines()) {
        val l = raw.trim()
        if (l.isEmpty()) continue
        val h = when {
            l.startsWith("#") -> l.trimStart('#').trim()
            l.startsWith("【") && l.indexOf('】') in 1..12 && l.substringAfter('】').isBlank() -> l.removePrefix("【").substringBefore('】')
            l.endsWith("：") && l.length <= 10 && !l.startsWith("-") -> l.removeSuffix("：")
            else -> null
        }
        if (h != null) secs += Sec(h.replace("**", "")) else secs.last().body += l.replace("**", "").removePrefix("- ").removePrefix("* ").let { if (it != l) "• $it" else it }
    }
    Column {
        secs.filter { it.title != null || it.body.isNotEmpty() }.forEachIndexed { i, sct ->
            if (sct.title != null) Text(sct.title, fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.primaryText,
                modifier = Modifier.padding(top = if (i == 0) 2.dp else 10.dp, bottom = 2.dp))
            if (sct.body.isNotEmpty()) Text(sct.body.joinToString("\n"), fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        }
    }
}

/** 「## 结论」 style headings → bold-ish 【结论】 lines for plain Text. */
private fun prettySummary(t: String): String = t.lines().joinToString("\n") { l ->
    val h = l.trim().removePrefix("###").removePrefix("##").removePrefix("#").trim()
    if (l.trim().startsWith("#")) "【$h】" else l.replace("**", "")
}.trim()

package com.openminis.app.ui.plus

import androidx.compose.foundation.combinedClickable

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import com.openminis.app.ui.brand.MinesIcon

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.browser.GoogleAuthRouter
import com.openminis.app.plus.ConnMethod
import com.openminis.app.plus.ConnectionKind
import com.openminis.app.plus.PermLevel
import com.openminis.app.plus.PlusConnect
import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)

/** Which full-screen web page the Connections flow is showing. */
private data class WebReq(val kindId: String, val url: String, val detect: Boolean, val title: String)

@Composable
fun ConnectionsScreen(onBack: () -> Unit, onTab: ((Int) -> Unit)? = null, title: String = "账号") {
    val context = LocalContext.current
    PlusVault.init(context)
    // [Mines-privacy-35.3] no screenshots / recents thumbnail of the account page
    SecureWindow()
    val entries by PlusVault.entries.collectAsState()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoStart by rememberSaveable { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebReq?>(null) }
    var addSite by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var balTick by remember { mutableIntStateOf(0) }
    // [Mines-38.7] 一键关联: once the platform it opened here is saved, go back so the run moves on to the next one
    LaunchedEffect(entries) {
        val w = LinkAll.waiting
        if (LinkAll.running && w != null && LinkAll.isConn(w)) {
            val base = w.removePrefix(LinkAll.CONN)
            if (PlusVault.accountsOf(base).any { (entries[it]?.updatedAt ?: 0L) >= LinkAll.waitingSince }) { delay(900); onBack() }
        }
    }

    fun startWeb(k: ConnectionKind) {
        val url = k.loginUrl ?: return
        PlusConnect.restoreCookies(k)
        // [Mines-login-32] the session is still there: connect without showing any login page
        if (com.openminis.app.plus.LoginWeb.signedIn(k)) {
            PlusVault.save(context, k, PlusVault.entries.value[k.id]?.account ?: "", null,
                share = PlusVault.entries.value[k.id]?.shareWithAi ?: false, via = "web")
            com.openminis.app.plus.LoginWeb.markSignedIn(k.id, null)
            Toast.makeText(context, "${k.name} 已经登录过了，直接连上", Toast.LENGTH_SHORT).show()
            detailId = k.id
            return
        }
        web = WebReq(k.id, url, detect = true, title = "登录 ${k.name}")
    }

    fun connect(k: ConnectionKind) {
        when (k.method) {
            ConnMethod.WEB -> startWeb(k)
            else -> { autoStart = true; detailId = k.id }
        }
    }

    /** Another account of an already connected service. */
    fun addAccount(base: ConnectionKind) {
        val k = PlusVault.kind(PlusVault.newAccountId(base.id)) ?: return
        if (k.method == ConnMethod.WEB) {
            // One cookie jar per site: park the current account's session, then sign in fresh.
            PlusVault.defaultAccount(base.id)?.let { cur ->
                PlusVault.kind(cur)?.let { ck ->
                    PlusConnect.snapshotCookies(ck, listOfNotNull(ck.loginUrl, ck.panelUrl))
                    PlusConnect.clearCookies(ck)
                }
            }
            web = WebReq(k.id, k.loginUrl ?: return, detect = true, title = "添加 ${base.name} 账号")
        } else connect(k)
    }

    val w = web
    if (w != null) {
        val k = PlusVault.kind(w.kindId)
        BackHandler { web = null }
        if (k != null) WebLoginScreen(
            kind = k, url = w.url, title = w.title, detect = w.detect,
            onDone = { account, urls ->
                PlusConnect.snapshotCookies(k, urls)
                if (w.detect) {
                    PlusVault.save(context, k, account.ifBlank { PlusVault.entries.value[k.id]?.account?.ifBlank { null } ?: if (k.id.startsWith("site:")) "网页登录" else "" }, null,
                        share = PlusVault.entries.value[k.id]?.shareWithAi ?: false,
                        via = if (PlusVault.entries.value[k.id]?.via == "oauth") "oauth" else if (PlusVault.extra(k.id, "login_external") == "1") "chrome" else "web")
                    // The shared cookie jar now holds this account, so it is the default one.
                    if (k.method == ConnMethod.WEB) PlusVault.setDefault(context, k.id, swapCookies = false)
                    Toast.makeText(context, "已连接 ${k.name}", Toast.LENGTH_SHORT).show()
                    com.openminis.app.plus.AutoSources.afterLogin(context)
                    detailId = k.id
                }
                web = null
            },
            onClose = { web = null },
        )
        return
    }

    val d = detailId?.let { PlusVault.kind(it) }
    if (d != null) {
        BackHandler { detailId = null; autoStart = false }
        ConnectionDetailScreen(
            kind = d,
            autoStart = autoStart,
            onBack = { detailId = null; autoStart = false },
            onWeb = { url, detect, title -> web = WebReq(d.id, url, detect, title) },
        )
        return
    }

    // [Mines-apps-32] keys already in the 模型组 count as connected; balances refresh quietly (plain HTTP)
    LaunchedEffect(Unit) {
        // [Mines-38.7] open a platform's in-app sign-in page directly (smoke / 一键关联)
        PlusNav.openLogin?.let { id -> PlusNav.openLogin = null
            PlusVault.kind(id)?.let { k -> k.loginUrl?.let { u -> web = WebReq(k.id, u, detect = true, title = "连接 ${k.name}") } } }
        PlusNav.openKind?.let { k -> PlusNav.openKind = null
            val claim = PlusNav.autoClaim; PlusNav.autoClaim = false
            if (PlusVault.kind(k) != null) { autoStart = claim; detailId = PlusVault.defaultAccount(k) ?: k }
            else Toast.makeText(context, "没找到「$k」这个应用", Toast.LENGTH_LONG).show() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.openminis.app.plus.ProviderKeys.adopt(context) }
            runCatching { com.openminis.app.plus.FreeOffers.load(context) }
            PlusVault.entries.value.keys.filter {
                com.openminis.app.plus.KeyBalance.hasApi(PlusVault.baseId(it)) &&
                    System.currentTimeMillis() - (PlusVault.extra(it, "balance_at")?.toLongOrNull() ?: 0L) > 3600_000L
            }.take(8).forEach { runCatching { com.openminis.app.plus.KeyBalance.refresh(it) } }
            balTick++
        }
    }
    // [Mines-warm-36] design36conn: 「账号连接」 — search, clipboard card, 需要处理 on top, connected services by category
    // [Mines-38.9.3] 账号 › 服务 (was 「账号连接」): same frame, search, rows and pills as 账号 › 登录
    AccountsPage(0, onTab, onBack, title = title) {
        AcctSearch(query, "搜索服务", { query = it })
        // [Mines-38.9.5 i] user 22:24: explanation line removed (was 「密钥和登录加密存在本机，列表里不显示任何账号信息」)
        // [Mines-warm-36] clipboard card: a key the user just copied is recognised here (on this phone only) → 连接
        var clipTick by remember { mutableIntStateOf(0) }
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) clipTick++ }
            lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
        }
        var clipKind by remember { mutableStateOf<ConnectionKind?>(null) }
        var clipSeen by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(clipTick, entries.size) {
            delay(400)  // Android 10+: the clipboard is readable only once the window has focus
            clipKind = PlusVault.kinds.filter { it.keyRegex != null && it.id != "gmail" && !it.id.contains('#') }.firstNotNullOfOrNull { k ->
                PlusConnect.keyOnClipboard(context, k)?.takeIf { v -> v.length >= 20 && v.hashCode().toString() != clipSeen &&
                    PlusVault.accountsOf(k.id).none { PlusVault.secret(it) == v } }?.let { k }
            }
        }
        clipKind?.let { ck ->
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), strong = true) {
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(com.openminis.app.ui.brand.MinesIcons.named("copy")!!, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${ck.name} 密钥", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = mines().ink, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("刚复制的 · 只在本机识别", fontSize = MT.Body, color = mines().ink2, maxLines = 1)
                    }
                    WarmPrimaryPill("连接", onClick = { clipKind = null; connect(PlusVault.kind(PlusVault.defaultAccount(ck.id) ?: ck.id) ?: ck) },
                        height = 44.dp, fontSize = 16, description = "连接 ${ck.name}")
                    androidx.compose.material3.IconButton(onClick = {
                        clipSeen = PlusConnect.keyOnClipboard(context, ck)?.hashCode()?.toString() ?: ""; clipKind = null
                    }, modifier = Modifier.size(48.dp)) { MinesIcon(Icons.Outlined.Close, contentDescription = "不用了", tint = mines().ink2) }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") balTick
        val q = query.trim().lowercase()
        fun hit(k: ConnectionKind) = q.isEmpty() || k.name.lowercase().contains(q) || k.desc.lowercase().contains(q) || k.id.contains(q) || k.group.contains(q)
        // [Mines-offers-32] free credits for services not connected yet (JSON list, no model)
        val offerList by com.openminis.app.plus.FreeOffers.offers.collectAsState()
        val offers = remember(offerList, entries) { com.openminis.app.plus.FreeOffers.relevant(context) }
        var offersOpen by rememberSaveable { mutableStateOf(false) }
        val bases = entries.keys.map { PlusVault.baseId(it) }.distinct().sorted().mapNotNull { PlusVault.kind(it) }
            .filter { k -> query.isBlank() || k.name.lowercase().contains(query.trim().lowercase()) || k.id.contains(query.trim().lowercase()) }
            .sortedBy { connCategoryOrder.indexOf(connCategory(it)) }
        val patrol by com.openminis.app.plus.AccountPatrol.status.collectAsState()
        val patrolRunning by com.openminis.app.plus.AccountPatrol.running.collectAsState()
        val checking by com.openminis.app.plus.AccountPatrol.checking.collectAsState()
        var fixId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { PlusNav.fixAccount?.let { f -> PlusNav.fixAccount = null
            // [Mines-feed-35.3] never silent: a fix that cannot open says why
            when {
                PlusVault.kind(f) == null -> Toast.makeText(context, "没找到要修的账号「$f」", Toast.LENGTH_LONG).show()
                com.openminis.app.plus.KeyAgent.recipe(f) == null -> { Toast.makeText(context, "这个账号不能自动换密钥，已打开它的页面，按提示重新登录或填写密钥", Toast.LENGTH_LONG).show(); detailId = f }
                else -> { Toast.makeText(context, "正在打开一键修复…", Toast.LENGTH_SHORT).show(); fixId = f }
            } } }
        fixId?.let { f -> PlusVault.kind(f)?.let { k -> KeyAgentDialog(k) { saved -> fixId = null
            if (saved) Toast.makeText(context, "${k.name} 已修复", Toast.LENGTH_SHORT).show() } } ?: run { fixId = null } }
        LaunchedEffect(Unit) { com.openminis.app.plus.AccountPatrol.load(context) }
        if (entries.isNotEmpty() && query.isBlank()) TextButton(onClick = { com.openminis.app.plus.AccountPatrol.runNow(context) }, enabled = !patrolRunning,
            modifier = Modifier.padding(horizontal = 12.dp)) { Text(if (patrolRunning) "正在巡查…" else "立即巡查所有账号", fontSize = MT.Body) }
        // [Mines-accounts-31] Fill in a missing account name once from the provider's "who am I" API.
        LaunchedEffect(entries.size) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                entries.values.filter { com.openminis.app.plus.AccountLabel.identity(it.kindId, it) == null && PlusVault.hasSecret(it.kindId) }
                    .take(6).forEach { en ->
                        val r = runCatching { com.openminis.app.plus.KeyTester.test(PlusVault.baseId(en.kindId), PlusVault.secret(en.kindId) ?: return@forEach) }.getOrNull()
                        r?.identity?.takeIf { it.isNotBlank() }?.let { PlusVault.setExtra(en.kindId, "identity", it) }
                    }
            }
        }
        // [Mines-warm-36] 需要处理: broken / expiring accounts on top, each with its one fixing action
        // [Mines-38.9.5 i] 忽略: an ignored service is not red, not in 需要处理, restorable from 「已忽略」 (硅基流动 starts ignored)
        var ignRev by remember { mutableStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") ignRev
        val problemsAll = entries.keys.filter { patrol[it]?.level == com.openminis.app.plus.AccountPatrol.Level.BAD || patrol[it]?.level == com.openminis.app.plus.AccountPatrol.Level.WARN }
        val ignoredSvc = problemsAll.filter { com.openminis.app.plus.ServiceIgnore.ignored(it) }.distinctBy { PlusVault.baseId(it) }
        val problems = problemsAll.filter { !com.openminis.app.plus.ServiceIgnore.ignored(it) }
            .sortedBy { if (patrol[it]?.level == com.openminis.app.plus.AccountPatrol.Level.BAD) 0 else 1 }
            // [Mines-38.9.1] user saw 「xAI Grok」 twice: one row per provider + key (the same key held under two account ids)
            .distinctBy { id -> PlusVault.baseId(id) + "#" + (PlusVault.secret(id)?.let { com.openminis.app.plus.KeyTombstone.mark(it) } ?: id) }
        if (problems.isNotEmpty() && query.isBlank()) {
            AcctHeader("需要处理", if (problems.size > 3) "还有 ${problems.size - 3} 个" else null)
            MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                problems.take(3).forEachIndexed { i, id ->
                    val k = PlusVault.kind(id) ?: return@forEachIndexed
                    val base = PlusVault.kind(PlusVault.baseId(id)) ?: k
                    val ps = patrol[id]
                    val bad = ps?.level == com.openminis.app.plus.AccountPatrol.Level.BAD
                    val (fg, _) = toneColors(if (bad) Tone.DANGER else Tone.WARNING)
                    val canFix = bad && com.openminis.app.plus.KeyAgent.recipe(id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW
                    val why = ps?.reason?.let { r -> com.openminis.app.plus.AccountLabel.showText(r) }?.takeIf { it.isNotBlank() } ?: if (bad) "连不上" else "要注意"
                    // [Mines-38.9.3] the same row as every account (logo 40 / 12, title 16, reason 13, one pill, chevron)
                    // [Mines-38.9.5 i] user 22:02 「太挤」: reason on ONE line, 一键修复 / 忽略 as small pills on a line under it,
                    // no 异常 chip next to the fix button (it said the same thing twice), name never squeezed to 「xAI Gr…」
                    AcctRow({ AcctKindLogo(base) }, base.name, why, if (canFix) null else if (bad) com.openminis.app.plus.SessionStatus.Pill.BAD else com.openminis.app.plus.SessionStatus.Pill.WARN,
                        pillLabel = com.openminis.app.plus.SessionStatus.keyPillLabel(ps?.level?.name, ps?.reason),
                        subtitleColor = fg, chevron = Chevron.RIGHT, onClick = { detailId = id },
                        description = "${base.name}：$why", subtitleLines = 1, below = {
                            if (canFix) SmallFixPill("一键修复", "一键修复 ${base.name}") { fixId = id }
                            SmallIgnorePill("忽略", "忽略 ${base.name}：不再报红，不计入汇总") { com.openminis.app.plus.ServiceIgnore.set(id, true); ignRev++ }
                        })
                    if (i < minOf(problems.size, 3) - 1) AcctDivider()
                }
            }
        }
        if (ignoredSvc.isNotEmpty() && query.isBlank()) {
            var showIgn by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH + 4.dp, vertical = 6.dp).clickable { showIgn = !showIgn }
                .semantics { contentDescription = "已忽略 ${ignoredSvc.size} 个服务，点开可以恢复" }, verticalAlignment = Alignment.CenterVertically) {
                Text("已忽略 ${ignoredSvc.size} 个", fontSize = MT.Caption, color = mines().ink2, modifier = Modifier.weight(1f))
                Text(if (showIgn) "收起" else "查看", fontSize = MT.Caption, color = mines().primaryText)
            }
            if (showIgn) MinesCard(Modifier.fillMaxWidth().padding(horizontal = MS.PageH)) {
                ignoredSvc.forEachIndexed { i, id ->
                    val base = PlusVault.kind(PlusVault.baseId(id)) ?: return@forEachIndexed
                    AcctRow({ AcctKindLogo(base) }, base.name, "已忽略：不报红，不计入汇总", com.openminis.app.plus.SessionStatus.Pill.UNCHECKED,
                        chevron = Chevron.NONE, onClick = { detailId = id }, description = "${base.name} 已忽略", extra = {
                            SmallIgnorePill("恢复", "恢复 ${base.name}") { com.openminis.app.plus.ServiceIgnore.set(id, false); ignRev++ }
                        })
                    if (i < ignoredSvc.size - 1) AcctDivider()
                }
            }
        }
        if (bases.isNotEmpty()) {
            // [Mines-accounts-35.3] user 2026-10-14 「我点开才展示，你这样又长又难看」: one line per service (icon, name,
            // 「N 个账号 · 全部正常」, status dot, chevron), no identity until it is opened; account rows are one line
            // (short label · masked identity, ellipsis), 全部检测 + 添加账号 only inside the opened service.
            var open by remember { mutableStateOf(setOf<String>()) }
            var delAsk by remember { mutableStateOf<String?>(null) }
            delAsk?.let { ConfirmDeleteAccount(it, onDone = { delAsk = null }) }
            val revealUntil by com.openminis.app.plus.Privacy.until.collectAsState()
            var revealTick by remember { mutableStateOf(0) }
            LaunchedEffect(revealUntil) {
                val left = revealUntil - System.currentTimeMillis()
                if (left > 0) { delay(left + 50); revealTick++ }
            }
            @Suppress("UNUSED_EXPRESSION") revealTick
            val revealed = com.openminis.app.plus.Privacy.isRevealed()
            PlusSection("已连接 · ${bases.size} 个服务", footer = "账号默认打码。点 👁 临时显示 30 秒（要验证指纹或锁屏）。点服务展开它的账号，☆ 设为默认。") {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (revealed) "正在显示账号，30 秒后自动打码" else "账号已打码", fontSize = MT.Body, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = {
                        if (revealed) com.openminis.app.plus.Privacy.hide()
                        else com.openminis.app.plus.PayCards.unlock(context, "显示账号") { ok, why ->
                            if (ok) com.openminis.app.plus.Privacy.reveal() else Toast.makeText(context, why ?: "没有通过验证", Toast.LENGTH_SHORT).show() }
                    }, modifier = Modifier.size(48.dp)) {
                        MinesIcon(if (revealed) androidx.compose.material.icons.Icons.Outlined.VisibilityOff else androidx.compose.material.icons.Icons.Outlined.Visibility,
                            contentDescription = if (revealed) "隐藏账号" else "显示账号 30 秒", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                bases.forEachIndexed { i, base ->
                    // [Mines-warm-36] connected services grouped by kind (AI 服务商 / 邮箱 / 开发与服务器 / …)
                    val cat = connCategory(base)
                    if (i == 0 || connCategory(bases[i - 1]) != cat) {
                        if (i > 0) PlusDivider()
                        Text(cat, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = mines().ink2, maxLines = 1,
                            modifier = Modifier.padding(start = AcctUi.SideH, end = AcctUi.SideH, top = 10.dp, bottom = 2.dp))
                    }
                    val ids = PlusVault.accountsOf(base.id)
                    val def = PlusVault.defaultAccount(base.id)
                    val isOpen = base.id in open
                    val bad = ids.count { patrol[it]?.level == com.openminis.app.plus.AccountPatrol.Level.BAD }
                    val warn = ids.count { patrol[it]?.level == com.openminis.app.plus.AccountPatrol.Level.WARN }
                    val nChecking = ids.count { it in checking }
                    val summary = (if (ids.size > 1) "${ids.size} 个账号 · " else "") + when {
                        nChecking > 0 -> "检测中…"
                        bad > 0 -> "$bad 个异常"
                        warn > 0 -> "$warn 个要注意"
                        ids.none { patrol[it] != null } && ids.any { PlusVault.hasSecret(it) } -> if (ids.size > 1) "还没检测" else "还没检测，点开可以检测"
                        else -> if (ids.size > 1) "全部正常" else "正常"
                    }
                    // [Mines-38.9.3] a key service is judged only by its key test: never tested → 未检查 (grey), not 正常 / 失效
                    val untested = ids.none { patrol[it] != null }
                    val pill = when {
                        nChecking > 0 -> com.openminis.app.plus.SessionStatus.Pill.UNCHECKED
                        bad > 0 -> com.openminis.app.plus.SessionStatus.Pill.BAD
                        warn > 0 -> com.openminis.app.plus.SessionStatus.Pill.WARN
                        untested && ids.any { PlusVault.hasSecret(it) } -> com.openminis.app.plus.SessionStatus.Pill.UNCHECKED
                        else -> com.openminis.app.plus.SessionStatus.Pill.OK
                    }
                    // a service that also has a web sign-in shows it as one more line (each site once: here, not in 登录)
                    val loginLine = remember(base.id, patrol) { runCatching {
                        val h = com.openminis.app.plus.Keychain.hostOf(base.loginUrl ?: base.site ?: base.panelUrl)
                        com.openminis.app.plus.IdentityStore.recFor(context, h)?.takeIf { it.method != com.openminis.app.plus.IdentityCore.Method.KEY }
                    }.getOrNull() }
                    AcctRow({ AcctKindLogo(base) }, base.name,
                        summary + (loginLine?.let { " · 有网站登录" } ?: ""),
                        pill, pillLabel = if (nChecking > 0) "检测中" else if (bad > 0 && ids.all { id -> patrol[id]?.level != com.openminis.app.plus.AccountPatrol.Level.BAD ||
                            com.openminis.app.plus.SessionStatus.keyRefused("BAD", patrol[id]?.reason) }) "key 无效" else null,   // [Mines-38.9.5 i]
                        subtitleColor = if (bad > 0) (if (mines().dark) MinesDesign.DangerDark else MinesDesign.Danger) else null,
                        chevron = if (isOpen) Chevron.DOWN else Chevron.RIGHT,
                        onClick = { open = if (isOpen) open - base.id else open + base.id },
                        description = "${base.name}：$summary，" + if (isOpen) "已展开" else "点开查看账号")
                    if (isOpen) {
                        // 全部检测: one small action for the whole service
                        if (ids.any { PlusVault.hasSecret(it) }) Row(Modifier.fillMaxWidth().padding(start = 58.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { ids.filter { PlusVault.hasSecret(it) }.forEach { com.openminis.app.plus.AccountPatrol.recheck(context, it) }
                                Toast.makeText(context, "正在检测 ${base.name} 的账号…", Toast.LENGTH_SHORT).show() },
                                enabled = nChecking == 0, modifier = Modifier.heightIn(min = 44.dp)) {
                                MinesIcon(androidx.compose.material.icons.Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (nChecking > 0) "检测中…" else "全部检测", fontSize = MT.Body, maxLines = 1)
                            }
                        }
                        ids.forEachIndexed { idx, id ->
                            val e = entries[id]
                            val isDef = id == def
                            val ps = patrol[id]
                            val kindOf = PlusVault.kind(id) ?: base
                            val label = PlusVault.extra(id, "note")?.takeIf { it.isNotBlank() } ?: if (isDef) "默认" else "账号 ${idx + 1}"
                            val dotColor = when (ps?.level) {
                                com.openminis.app.plus.AccountPatrol.Level.BAD -> Color(0xFFD93025)
                                com.openminis.app.plus.AccountPatrol.Level.WARN -> Color(0xFFE8A200)
                                else -> Green
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .combinedClickable(onClick = { detailId = id }, onLongClick = { delAsk = id }, onLongClickLabel = "删除这个账号")
                                    .padding(start = 44.dp, end = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.IconButton(onClick = { if (!isDef) PlusVault.setDefault(context, id) }, modifier = Modifier.size(44.dp)) {
                                    MinesIcon(if (isDef) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (isDef) "默认账号" else "设为默认",
                                        tint = if (isDef) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                                Text(label, fontSize = MT.Body, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                Text(" · " + com.openminis.app.plus.AccountLabel.short(id, kindOf, e) +
                                        (com.openminis.app.plus.KeyBalance.text(id)?.let { " · $it" } ?: ""),
                                    fontSize = MT.Body, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                @Suppress("UNUSED_VARIABLE") val unusedDot = dotColor
                                AcctPill(if (id in checking) com.openminis.app.plus.SessionStatus.Pill.UNCHECKED else com.openminis.app.plus.SessionStatus.keyPill(ps?.level?.name),
                                    if (id in checking) "检测中" else com.openminis.app.plus.SessionStatus.keyPillLabel(ps?.level?.name, ps?.reason))
                                Spacer(Modifier.width(4.dp))
                                MinesIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // problems only: one red line + the one action that fixes it
                            val canFix = ps?.level == com.openminis.app.plus.AccountPatrol.Level.BAD && com.openminis.app.plus.KeyAgent.recipe(id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW
                            val lowCredit = ps?.level == com.openminis.app.plus.AccountPatrol.Level.WARN && (ps?.reason?.contains("余额") == true || ps?.reason?.contains("充值") == true)
                            val prev = PlusVault.hasPrevKey(id)
                            if (ps != null && ps.level != com.openminis.app.plus.AccountPatrol.Level.OK || prev) Row(
                                Modifier.fillMaxWidth().padding(start = 88.dp, end = 10.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(ps?.reason?.takeIf { ps?.level != com.openminis.app.plus.AccountPatrol.Level.OK }?.let { com.openminis.app.plus.AccountLabel.showText(it) } ?: "",
                                    fontSize = MT.Body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = if (ps?.level == com.openminis.app.plus.AccountPatrol.Level.BAD) Color(0xFFD93025) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                if (canFix) Button(onClick = { fixId = id }, modifier = Modifier.heightIn(min = 40.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("一键修复", fontSize = MT.Body, maxLines = 1) }
                                // [Mines-38.9.1] a broken key can always be thrown away right here (with a confirm)
                                if (ps?.level == com.openminis.app.plus.AccountPatrol.Level.BAD) OutlinedButton(onClick = { delAsk = id }, modifier = Modifier.heightIn(min = 40.dp),
                                    border = BorderStroke(1.dp, Red), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                                    Text(if (PlusVault.hasSecret(id)) "删除这个密钥" else "删除", fontSize = MT.Body, maxLines = 1, color = Red) }
                                if (lowCredit) OutlinedButton(onClick = {
                                    val k = PlusVault.kind(id); val u = k?.panelUrl ?: k?.keyUrl ?: com.openminis.app.plus.KeyAgent.recipe(id)?.url
                                    if (u != null) com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(context, u)
                                    Toast.makeText(context, "充值要你自己在官网完成，Mines 不会替你付钱", Toast.LENGTH_LONG).show()
                                }, modifier = Modifier.heightIn(min = 40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("去充值", fontSize = MT.Body, maxLines = 1) }
                                if (prev) OutlinedButton(onClick = {
                                    if (PlusVault.restorePrevKey(context, id)) Toast.makeText(context, "已恢复上一个密钥，正在重新检测", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.heightIn(min = 40.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("恢复旧密钥", fontSize = MT.Body, maxLines = 1) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { addAccount(base) }.padding(start = 58.dp, end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MinesIcon(Icons.Outlined.Add, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("添加账号", fontSize = MT.Body, maxLines = 1, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (i < bases.lastIndex) AcctDivider()
                }
            }
        }
        // [Mines-38.9.4] g2 smoke: the offers filled the top and pushed the user's connected services below the fold.
        // Now: connected services first; offers in their own section below, collapsed to one row until opened.
        if (q.isEmpty() && offers.isNotEmpty()) PlusSection("免费额度 · 值得关注", footer = if (offersOpen) "清单每天从 Mines 仓库更新。Mines 不会付钱、不会选付费套餐；要短信验证码、实名或人机验证时会停下来请你做那一步。" else null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { offersOpen = !offersOpen }.padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { contentDescription = "免费额度 ${minOf(offers.size, 4)} 个，" + if (offersOpen) "点一下收起" else "点一下展开" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (offersOpen) "收起" else "${minOf(offers.size, 4)} 个可以免费领", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = mines().ink, modifier = Modifier.weight(1f))
                MinesIcon(com.openminis.app.ui.brand.MinesIcons.named(if (offersOpen) "chevron-up" else "chevron-down")!!, null, tint = mines().ink3, modifier = Modifier.size(16.dp))
            }
            if (offersOpen) PlusDivider()
            if (offersOpen) offers.take(4).forEachIndexed { i, o ->
                val k = PlusVault.kind(o.kind)
                Row(Modifier.fillMaxWidth().clickable { if (k != null) { autoStart = false; detailId = k.id } }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // [Mines-38.9.1] user 2026-10-17 「字体 UI 没对齐」: title 16 sp one line, detail 13 sp ≤ 2 lines (ellipsis at the end,
                    // not mid-sentence), icon / text / button share one centre line, 帮我领 = the same terracotta pill as 一键修复
                    if (k != null) BrandIcon(k, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.title, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = mines().ink, maxLines = 2,   // [Mines-38.9.4] 2 lines, no mid-title …
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(o.detail, fontSize = MT.Sub, lineHeight = MT.SubLH, color = mines().ink2, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    WarmPrimaryPill("帮我领", onClick = { if (k != null) { autoStart = true; detailId = k.id } }, height = 40.dp, fontSize = 14,
                        description = "帮我领 ${o.title}")
                }
                if (i < minOf(offers.size, 4) - 1) PlusDivider()
            }
        }
        PlusVault.groups.forEach { g ->
            val connectedBases = entries.keys.map { PlusVault.baseId(it) }.toSet()
            val list = PlusVault.kindsIn(g).filterNot { it.id in connectedBases }.filter { hit(it) }
            if (list.isEmpty()) return@forEach
            PlusSection(g) {
                list.forEachIndexed { i, k ->
                    ConnectionRow(k, sub = k.desc, connected = false, onConnect = {
                        if (k.appType == "MCP" && k.keyUrl == null) PlusNav.go(PlusRoutes.MARKET) else connect(k)
                    }) { detailId = k.id }
                    if (i < list.lastIndex) PlusDivider()
                }
            }
        }
        if (query.isBlank()) PlusSection(PlusVault.G_OTHER, footer = "任何网站都行，比如服务器面板：输入网址，在 Mines 浏览器里登录一次，AI 的浏览器也就能用这个登录。") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { addSite = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    MinesIcon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("添加网站登录", fontSize = MT.Body)
            }
        }
    }

    if (addSite) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addSite = false },
            title = { Text("其他网站") },
            text = {
                OutlinedTextField(url, { url = it }, label = { Text("网址，例如 panel.example.com") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(enabled = url.isNotBlank(), onClick = {
                    val full = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
                    val host = PlusConnect.hostOf(full)
                    if (host.isNotEmpty()) {
                        val k = PlusVault.siteKind(host, full)
                        PlusVault.setExtra(k.id, "url", full)
                        addSite = false
                        // No sign-in rule for an arbitrary site: the page's 我已登录 button saves it.
                        web = WebReq(k.id, full, detect = true, title = "登录 $host")
                    }
                }) { Text("打开登录页") }
            },
            dismissButton = { TextButton(onClick = { addSite = false }) { Text("取消") } },
        )
    }

}

/** [Mines-warm-36] display category of a connected service (storage groups unchanged). */
private val connCategoryOrder = listOf("AI 服务商", "邮箱", "开发与服务器", "社交与资讯", "金融与加密", "效率与办公", "其他")
private fun connCategory(k: ConnectionKind): String = when {
    k.id in setOf("gmail", "mail163", "qqmail", "outlook", "mail126", "icloudmail") -> "邮箱"
    k.group == PlusVault.G_AI -> "AI 服务商"
    k.group == PlusVault.G_DEV -> "开发与服务器"
    k.group == PlusVault.G_SOCIAL || k.id == "x" -> "社交与资讯"
    k.group == PlusVault.G_FIN -> "金融与加密"
    k.group == PlusVault.G_COMMON -> "效率与办公"
    else -> "其他"
}

@Composable
fun BrandIcon(kind: ConnectionKind, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(Color.White)
            .border(BorderStroke(1.dp, Color(0x1F000000)), RoundedCornerShape(size * 0.27f)),
        contentAlignment = Alignment.Center,
    ) {
        if (kind.icon != 0) Image(painterResource(kind.icon), contentDescription = kind.name, modifier = Modifier.size(size * 0.62f))
        else if (kind.id.startsWith("site:")) MinesIcon(Icons.Outlined.Language, contentDescription = kind.name, tint = Color(0xFF5B6B7F), modifier = Modifier.size(size * 0.62f))
        else {
            // [Mines-apps-32] no bundled logo: a calm letter tile (never waits on the network)
            val hue = (kind.id.hashCode() and 0x7fffffff) % 360
            Box(Modifier.size(size).background(Color.hsv(hue.toFloat(), 0.45f, 0.62f)), contentAlignment = Alignment.Center) {
                Text(kind.name.first().uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** [Mines-apps-32] catalog row: logo tile, bold name (+Beta), one gray line, round 「+」 on the right. */
@Composable
private fun ConnectionRow(k: ConnectionKind, sub: String, connected: Boolean, onConnect: () -> Unit, onClick: () -> Unit) {
    // [Mines-38.9.3] same sizes as every account row (logo 40 / 12 dp, title 16 sp, subtitle 13 sp, 16 dp sides)
    Row(
        Modifier.fillMaxWidth().heightIn(min = AcctUi.RowMin).clickable(onClick = onClick).padding(horizontal = AcctUi.SideH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AcctKindLogo(k)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.name, fontSize = AcctUi.Title, fontWeight = FontWeight.Medium, color = mines().ink, maxLines = 1)
                if (k.beta || k.appType == "MCP") {
                    Spacer(Modifier.width(6.dp))
                    Text(if (k.beta) "Beta" else "MCP", fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(sub, fontSize = AcctUi.Sub, color = if (connected) Green else mines().ink2, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (connected) {
            MinesIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            androidx.compose.material3.FilledTonalIconButton(onClick = onConnect, modifier = Modifier.size(44.dp)) {
                MinesIcon(Icons.Outlined.Add, contentDescription = "添加 ${k.name}", modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun openExternal(context: Context, url: String) = GoogleAuthRouter.openInCustomTab(context, url)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ConnectionDetailScreen(
    kind: ConnectionKind,
    autoStart: Boolean,
    onBack: () -> Unit,
    onWeb: (url: String, detect: Boolean, title: String) -> Unit,
) {
    val context = LocalContext.current
    val entries by PlusVault.entries.collectAsState()
    val permVersion by PlusVault.permVersion.collectAsState()
    val entry = entries[kind.id]
    val scope = rememberCoroutineScope()

    // Resume counter: drives the clipboard check and the accessibility check after returning from another app.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    PlusPage(kind.name, onBack) {
        // ---- header ([Mines-apps-32] 「应用」 detail: big logo + name, centered line, one pill button) ----
        var agentOpen by remember { mutableStateOf(false) }
        val isMcpOnly = kind.appType == "MCP" && kind.keyUrl == null
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandIcon(kind, 72.dp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.name, fontSize = MT.PageTitle, fontWeight = FontWeight.Bold)
                if (kind.beta || kind.appType == "MCP") {
                    Spacer(Modifier.width(8.dp))
                    Text(if (kind.beta) "Beta" else "MCP", fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // [Mines-38.9.5 i] a web sign-in shows what a REAL check says (WebSession), checked when the page opens —
            // hero, 网站登录 row and button now tell one story (38.9.4: 已连接 / 已连接 · 断开 / 未检查 at once)
            val webOnly = entry != null && !PlusVault.hasSecret(kind.id) && (entry.via == "web" || entry.via == "chrome" || kind.method == ConnMethod.WEB)
            val wsMap by com.openminis.app.plus.WebSession.state.collectAsState()
            val ws = wsMap[kind.id] ?: com.openminis.app.plus.WebSessionCore.St.UNCHECKED
            if (webOnly) LaunchedEffect(kind.id) { com.openminis.app.plus.WebSession.verify(context, kind) }
            if (webOnly) Text(com.openminis.app.plus.WebSessionCore.heroLine(ws, com.openminis.app.plus.AccountLabel.method(kind, entry!!)),
                fontSize = MT.Body, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,   // [i11] not thin / tiny
                color = when (ws) { com.openminis.app.plus.WebSessionCore.St.SIGNED_IN -> Green
                    com.openminis.app.plus.WebSessionCore.St.SIGNED_OUT -> if (mines().dark) MinesDesign.WarningDark else MinesDesign.Warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant }) else
            Text(
                // [Mines-accounts-31] full identity only here (rows show it masked)
                if (entry != null) "已连接 · " + com.openminis.app.plus.AccountLabel.method(kind, entry) +
                    (com.openminis.app.plus.AccountLabel.identity(kind.id, entry)?.let { " · " + com.openminis.app.plus.AccountLabel.show(it) } ?: "") +
                    (if (PlusVault.hasSecret(kind.id) && (entry.via == "key" || entry.via == "token" || entry.via == "model")) "\n" + kind.keyLabel + "：" + com.openminis.app.plus.AccountLabel.maskSecret(PlusVault.secret(kind.id) ?: "") else "") +
                    (com.openminis.app.plus.KeyBalance.text(kind.id)?.let { "\n$it" } ?: "")
                else kind.desc + (com.openminis.app.plus.KeyBalance.text(kind.id)?.let { "\n$it" } ?: ""), // [Mines-sources-33] balance read via a console session
                fontSize = MT.Body, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (entry != null) Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (entry != null) {
                var askDel by remember { mutableStateOf(false) }
                // [Mines-38.9.6 i13] user: Notion 未登录 still showed a red 断开 — a web-only account that a real check did not
                // see signed in gets 登录 (primary) + a quiet 移除; red 断开 only for a live session / a saved key
                if (webOnly && ws != com.openminis.app.plus.WebSessionCore.St.SIGNED_IN) {
                    Button(
                        onClick = { PlusConnect.restoreCookies(kind); onWeb(kind.loginUrl ?: "", true, "登录 ${kind.name}") },
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = mines().primary, contentColor = mines().onPrimary),
                        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 160.dp),
                    ) { Text("登录", fontSize = MT.Body, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { askDel = true }) { Text("移除这个账号", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = MT.Sub) }
                } else OutlinedButton(
                    onClick = { askDel = true },
                    shape = RoundedCornerShape(26.dp), border = BorderStroke(1.dp, Red),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (PlusVault.hasSecret(kind.id)) "删除这个${kind.keyLabel.ifBlank { "密钥" }}" else "断开", color = Red, fontSize = MT.Body) }
                if (askDel) ConfirmDeleteAccount(kind.id, onDone = { askDel = false })
            } else if (isMcpOnly || kind.method in setOf(ConnMethod.WEB, ConnMethod.KEY, ConnMethod.GOOGLE)) {
                Button(
                    onClick = {
                        when {
                            isMcpOnly -> PlusNav.go(PlusRoutes.MARKET)
                            kind.method == ConnMethod.WEB -> { PlusConnect.restoreCookies(kind); onWeb(kind.loginUrl ?: "", true, "登录 ${kind.name}") }
                            kind.method == ConnMethod.GOOGLE -> openExternal(context, kind.loginUrl ?: "https://accounts.google.com/")
                            // [Mines-sources-33] Gmail: sign in inside Mines so codes + mail are read automatically
                            kind.id == "gmail" && PlusVault.extra(kind.id, "google_refused") != "1" -> onWeb(kind.loginUrl ?: "", true, "登录 Gmail")
                            com.openminis.app.plus.KeyAgent.recipe(kind.id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW -> agentOpen = true
                            kind.keyUrl != null -> openExternal(context, kind.keyUrl)
                        }
                    },
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1A1A1A)),
                    border = BorderStroke(1.dp, Color(0x33000000)),
                    modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 160.dp),
                ) { Text(if (isMcpOnly) "去市场安装" else "+ 添加", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold) }
            }
            if (entry == null && kind.method == ConnMethod.KEY && !isMcpOnly) {
                Spacer(Modifier.height(10.dp))
                Text("ⓘ 需要额外配置：${kind.keyLabel}", fontSize = MT.Body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (agentOpen) KeyAgentDialog(kind) { saved -> agentOpen = false; if (saved) Toast.makeText(context, "已连接 ${kind.name}", Toast.LENGTH_SHORT).show() }

        // [Mines-38.9.3] status in the shared row style: a key is judged only by its key test; a site that ALSO has a web
        // sign-in shows it here as one 「网站登录」 line (each site once — it is not listed again under 登录)
        if (entry != null) {
            val patrolNow by com.openminis.app.plus.AccountPatrol.status.collectAsState()
            val keyed = PlusVault.hasSecret(kind.id) && entry.via in setOf("key", "token", "model", "oauth")
            val loginHost = com.openminis.app.plus.Keychain.hostOf(kind.loginUrl ?: kind.site ?: kind.panelUrl)
            val loginRec = remember(kind.id) { runCatching { com.openminis.app.plus.IdentityStore.recFor(context, loginHost) }.getOrNull()
                ?.takeIf { it.method != com.openminis.app.plus.IdentityCore.Method.KEY } }
            if (keyed || loginRec != null) PlusSection("状态") {
                if (keyed) {
                    val ps = patrolNow[kind.id]
                    AcctRow({ AcctKindLogo(kind) }, kind.keyLabel.ifBlank { "密钥" },
                        ps?.reason?.let { com.openminis.app.plus.AccountLabel.showText(it) }?.ifBlank { null } ?: "还没检测，点一下检测",
                        com.openminis.app.plus.SessionStatus.keyPill(ps?.level?.name), pillLabel = com.openminis.app.plus.SessionStatus.keyPillLabel(ps?.level?.name, ps?.reason), chevron = Chevron.NONE,
                        onClick = { com.openminis.app.plus.AccountPatrol.recheck(context, kind.id) })
                }
                if (loginRec != null) {
                    if (keyed) AcctDivider()
                    // [Mines-38.9.5 i] the row follows the live check (WebSession) when there is one, not a stale record
                    val wsNow by com.openminis.app.plus.WebSession.state.collectAsState()
                    val live = wsNow[kind.id]
                    val st = when (live) {
                        com.openminis.app.plus.WebSessionCore.St.SIGNED_IN -> com.openminis.app.plus.IdentityCore.LINKED
                        com.openminis.app.plus.WebSessionCore.St.SIGNED_OUT -> com.openminis.app.plus.IdentityCore.EXPIRED
                        else -> loginRec.status
                    }
                    val lp = com.openminis.app.plus.SessionStatus.loginPill(st, false,
                        live == com.openminis.app.plus.WebSessionCore.St.SIGNED_OUT || (st == com.openminis.app.plus.IdentityCore.EXPIRED && com.openminis.app.plus.IdentityStore.confirmedOut(loginRec.site)))
                    AcctRow({ AcctLogo(loginHost, kind.name) }, "网站登录", loginRec.identity.ifBlank { loginHost }, lp,
                        pillLabel = when (live) { com.openminis.app.plus.WebSessionCore.St.SIGNED_IN -> "已登录"; com.openminis.app.plus.WebSessionCore.St.SIGNED_OUT -> "未登录"
                            com.openminis.app.plus.WebSessionCore.St.CHECKING -> "检查中"; else -> null },
                        onClick = { PlusNav.go(PlusRoutes.keychainDetail(loginRec.key)) })
                }
            }
        }

        // [Mines-offers-32] a free offer for this service
        com.openminis.app.plus.FreeOffers.forKind(context, kind.id)?.takeIf { entry == null }?.let { o ->
            PlusSection("免费额度", footer = (com.openminis.app.plus.FreeOffers.needsText(o).ifBlank { "Mines 不会付钱，也不会选付费套餐。" })) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(o.title, fontSize = MT.Body, fontWeight = FontWeight.SemiBold)
                    Text(o.detail, fontSize = MT.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    // [Mines-offers-32] FlowRow: on narrow screens / big fonts 「不需要」 wrapped one character per line
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (com.openminis.app.plus.KeyAgent.recipe(kind.id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW) agentOpen = true
                            else openExternal(context, o.url)
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("帮我领", fontSize = MT.Body) }
                        TextButton(onClick = { openExternal(context, o.source) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("活动说明 ↗", fontSize = MT.Body) }
                        TextButton(onClick = { com.openminis.app.plus.FreeOffers.dismiss(context, o.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("不需要", fontSize = MT.Body) }
                    }
                }
            }
        }
        // auto-claim when opened from 「帮我领」 on the list
        LaunchedEffect(Unit) {
            if (autoStart && entry == null &&
                com.openminis.app.plus.KeyAgent.recipe(kind.id)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW) agentOpen = true
            // [Mines-feed-35.3] 帮我领 on something already connected: say so instead of doing nothing
            else if (autoStart && entry != null) Toast.makeText(context, "${kind.name} 已经接入了，不用再领", Toast.LENGTH_LONG).show()
        }

        val siblings = PlusVault.accountsOf(PlusVault.baseId(kind.id))
        if (entry != null && siblings.size > 1) {
            val isDef = PlusVault.defaultAccount(PlusVault.baseId(kind.id)) == kind.id
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(if (isDef) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                    tint = if (isDef) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(if (isDef) "默认账号：AI 不指定账号时用它" else "不是默认账号", fontSize = MT.Caption,
                    modifier = Modifier.weight(1f))
                if (!isDef) TextButton(onClick = { PlusVault.setDefault(context, kind.id) }) { Text("设为默认", fontSize = MT.Caption) }
            }
        }

        // ---- how to connect ----
        if (!isMcpOnly) when (kind.method) {
            // [Mines-38.9.5 i11] no lone ⓘ above the button (user rule: no explanation paragraphs); the button says 「登录」 until a
            // real check has seen this site signed in once — 「重新登录」 only makes sense after that
            ConnMethod.WEB -> PlusSection(null) {
                val wsLive by com.openminis.app.plus.WebSession.state.collectAsState()
                val everIn = wsLive[kind.id] == com.openminis.app.plus.WebSessionCore.St.SIGNED_IN ||
                    (wsLive[kind.id] == null && runCatching { com.openminis.app.plus.IdentityStore.recFor(context, com.openminis.app.plus.Keychain.hostOf(kind.loginUrl ?: kind.site ?: kind.panelUrl)) }
                        .getOrNull()?.status == com.openminis.app.plus.IdentityCore.LINKED)
                BigAction(if (entry == null) "连接（打开 ${kind.name} 登录页）" else if (everIn) "重新登录" else "登录") {
                    if (entry != null && PlusVault.defaultAccount(PlusVault.baseId(kind.id)) != kind.id) PlusVault.setDefault(context, kind.id)
                    PlusConnect.restoreCookies(kind)
                    onWeb(kind.loginUrl ?: "", true, "登录 ${kind.name}")
                }
                LaunchedEffect(Unit) { if (autoStart && entry == null) onWeb(kind.loginUrl ?: "", true, "登录 ${kind.name}") }
            }
            ConnMethod.GOOGLE -> PlusSection(null,
                footer = "Google 不允许在 App 内嵌网页里登录，所以这里用 Chrome 打开官方登录页。让 AI 读写邮件请连接「Gmail」，看日历请连接「Google 日历」。") {
                BigAction("在 Chrome 里登录 Google") { openExternal(context, kind.loginUrl ?: "https://accounts.google.com/") }
                if (entry == null) SecondaryAction("我已经登录好了") {
                    PlusVault.save(context, kind, "Chrome 已登录", null, share = false, via = "chrome")
                }
            }
            ConnMethod.GITHUB, ConnMethod.MICROSOFT -> DeviceFlowBlock(kind, autoStart && entry == null, onWeb)
            ConnMethod.KEY -> {
                if (kind.id == "gmail") PlusSection(null, footer = "登录后，网页要验证码时 Mines 会自己去 Gmail 里找并填好，重要邮件也会进「消息」。Google 不让在 App 里登录时，改用下面的应用专用密码，或打开通知使用权读 Gmail App 的通知。") {
                    BigAction(if (com.openminis.app.plus.MailSources.gmailWebSignedIn()) "Gmail 已登录 · 重新登录" else "在 Mines 里登录 Gmail（自动读验证码）") {
                        onWeb(kind.loginUrl ?: "", true, "登录 Gmail")
                    }
                }
                KeyBlock(kind, resumes, autoStart && entry == null && kind.id != "gmail")
            }
            ConnMethod.CALENDAR -> CalendarBlock(kind)
            ConnMethod.A11Y -> A11yBlock(kind, resumes)
        }
        // [Mines-keys-31] Services connected by login whose API token Mines can also fetch (GitHub, DigitalOcean).
        if (com.openminis.app.plus.KeyAgent.recipe(kind.id) != null && kind.method != ConnMethod.KEY && !kind.alsoKey) {
            PlusSection("让 Mines 拿 ${kind.keyLabel}", footer = "你只需要登录，Mines 会自己创建、读取、测试并加密保存。") { KeyAgentButton(kind) }
        }
        if (kind.alsoKey && kind.method == ConnMethod.WEB) {
            PlusSection("也可以只用 ${kind.keyLabel}", footer = "用 ${kind.keyLabel} 时，AI 在沙箱里通过 ${kind.envKey} 调 API，不需要邮箱。") {
                KeyBlockBody(kind, resumes, false)
            }
        }

        // ---- AI access ----
        val secretEnv = kind.envKey != null && PlusVault.hasSecret(kind.id)
        if (entry != null && secretEnv) {
            PlusSection(null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("交给 AI 使用", fontSize = MT.Body)
                        Text("以 ${kind.envKey} 提供给沙箱，聊天里自动打码", fontSize = MT.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(entry.shareWithAi, onCheckedChange = { PlusVault.setShare(context, kind, it) })
                }
            }
        }

        // ---- panel links + keep-alive ----
        if (kind.panelUrl != null && entry != null) {
            PlusSection("控制台") {
                BigAction("打开${if (kind.id == "wispbyte") "面板" else "控制台"}") { onWeb(kind.panelUrl, false, kind.name) }
                if (kind.id == "wispbyte") {
                    SecondaryAction("创建服务器") { onWeb("https://wispbyte.com/client/create", false, "创建服务器") }
                    var on by remember { mutableStateOf(PlusConnect.keepAliveOn(context, kind.id)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保活", fontSize = MT.Body)
                            val last = PlusVault.extra(kind.id, "keepalive_last")?.toLongOrNull()
                            Text(
                                "免费服务器两周不登录会被归档。打开后每 10 天在后台用已保存的登录打开一次面板；登录失效会发通知提醒你。" +
                                    (last?.let { "\n上次：" + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) } ?: ""),
                                fontSize = MT.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(on, onCheckedChange = {
                            on = it
                            PlusConnect.setKeepAlive(context, kind.id, it)
                            if (it) PlusConnect.runKeepAlive(context, kind.id) {}
                        })
                    }
                }
            }
        }

        // ---- permissions ----
        val caps = PlusVault.capabilities(kind.profile)
        listOf("读取权限", "写入和删除权限", "后台操作").forEachIndexed { sec, title ->
            val rows = caps.filter { it.section == sec }
            if (rows.isEmpty()) return@forEachIndexed
            PlusSection(title, footer = if (sec == 1) "「询问」会在 AI 动手前弹出确认卡片，你点同意它才做。支付和交易永远要问你。" else null) {
                rows.forEachIndexed { i, cap ->
                    @Suppress("UNUSED_EXPRESSION") permVersion
                    val cur = PlusVault.level(kind, cap)
                    // [Mines-compact] Name on the left, a compact 允许/询问/禁止
                    // segmented control on the right — one row per permission.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(cap.label + if (cap.locked) "（固定）" else "", fontSize = MT.Body, modifier = Modifier.weight(1f),
                            maxLines = 2)
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp)),
                        ) {
                            PermLevel.entries.forEachIndexed { li, lv ->
                                val sel = cur == lv
                                val color = when (lv) { PermLevel.ALLOW -> Green; PermLevel.ASK -> Color(0xFFFF9500); PermLevel.DENY -> Red }
                                if (li > 0) Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
                                Box(
                                    Modifier.width(46.dp).height(30.dp)
                                        .background(if (sel) color.copy(alpha = 0.22f) else Color.Transparent)
                                        .clickable(enabled = !cap.locked) { PlusVault.setLevel(kind, cap, lv) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(lv.label, fontSize = MT.Caption, maxLines = 1, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) color else if (cap.locked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                    if (i < rows.lastIndex) PlusDivider()
                }
            }
        }

        // [Mines-patrol-32] expiry reminder (trial end / credits run out), checked by 账号巡查
        if (entry != null) {
            var date by remember { mutableStateOf(com.openminis.app.plus.Expiry.date(kind.id) ?: "") }
            var note by remember { mutableStateOf(com.openminis.app.plus.Expiry.note(kind.id).takeIf { com.openminis.app.plus.Expiry.date(kind.id) != null } ?: "") }
            PlusSection("到期提醒", footer = "比如试用结束、赠送额度用完的日子。提前 7 天提醒你，到期当天再提醒一次。AI 也可以用 minis-connect remind 帮你设。") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 90, 180).forEach { d ->
                        FilledTonalButton(onClick = {
                            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + d * 86_400_000L))
                        }, modifier = Modifier.heightIn(min = 44.dp)) { Text("$d 天后", fontSize = MT.Body) }
                    }
                }
                OutlinedTextField(date, { date = it }, label = { Text("日期（年-月-日）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
                OutlinedTextField(note, { note = it }, label = { Text("提醒什么（例如：试用结束）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = date.matches(Regex("""\d{4}-\d{2}-\d{2}""")), onClick = {
                        com.openminis.app.plus.Expiry.set(kind.id, date, note)
                        Toast.makeText(context, "已设好：$date 提醒你", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存提醒", fontSize = MT.Body) }
                    if (com.openminis.app.plus.Expiry.date(kind.id) != null) TextButton(onClick = { com.openminis.app.plus.Expiry.clear(kind.id); date = ""; note = "" },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("取消提醒", fontSize = MT.Body) }
                }
            }
        }

        // ---- [Mines-apps-32] 详情: type, author, links (always in a Chrome Custom Tab) ----
        val site = kind.site ?: (kind.loginUrl ?: kind.keyUrl)?.let { "https://" + PlusConnect.hostOf(it).removePrefix("console.").removePrefix("platform.").removePrefix("account.").removePrefix("accounts.") }
        PlusSection("详情") {
            DetailRow("应用类型", when {
                kind.appType != null -> kind.appType
                kind.method == ConnMethod.KEY -> kind.keyLabel
                kind.method in setOf(ConnMethod.GITHUB, ConnMethod.MICROSOFT) -> "OAuth"
                kind.method == ConnMethod.GOOGLE -> "OAuth（Chrome）"
                kind.method == ConnMethod.WEB -> "登录态"
                kind.method == ConnMethod.CALENDAR -> "手机权限"
                else -> "无障碍"
            }, null)
            PlusDivider()
            DetailRow("作者", site?.let { PlusConnect.hostOf(it).removePrefix("www.") } ?: kind.name, null)
            site?.let { PlusDivider(); DetailRow("网站", "", it) }
            kind.docs?.let { PlusDivider(); DetailRow("文档", "", it) }
            kind.privacy?.let { PlusDivider(); DetailRow("隐私政策", "", it) }
            PlusDivider()
            DetailRow("提供反馈", "", "https://github.com/ddfc1991/openminis-agent-android/issues/new?labels=mines-bug&title=" +
                java.net.URLEncoder.encode("应用：${kind.name}", "UTF-8"))
        }
    }
}

/** [Mines-apps-32] one 详情 row; with [url] the whole row opens it in a Chrome Custom Tab (↗). */
@Composable
private fun DetailRow(label: String, value: String, url: String?) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .let { if (url != null) it.clickable { openExternal(context, url) } else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = MT.Body, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, fontSize = MT.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (url != null) Text("↗", fontSize = MT.PageTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BigAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontSize = MT.Button, fontWeight = FontWeight.SemiBold) }   // [i11] button type = 15 sp like every button
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp).heightIn(min = 40.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontSize = MT.Caption) }
}

/** GitHub / Microsoft: OAuth device flow with the code shown big, copied, and the official page opened. */
@Composable
private fun DeviceFlowBlock(kind: ConnectionKind, autoStart: Boolean, onWeb: (String, Boolean, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<PlusConnect.DeviceCode?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun start() {
        if (busy) return
        busy = true; status = "正在向 ${kind.name} 申请授权码…"
        scope.launch {
            val dc = withContext(Dispatchers.IO) { runCatching { PlusConnect.startDevice(kind.id) } }
            dc.onFailure { status = "申请失败：${it.message}"; busy = false }
            val c = dc.getOrNull() ?: return@launch
            code = c
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("授权码", c.userCode))
            status = "授权码已复制。在打开的页面粘贴它，点授权。"
            delay(1200)
            openExternal(context, c.verifyUrl)
            var interval = c.interval.coerceAtLeast(5)
            val until = System.currentTimeMillis() + c.expiresIn * 1000L
            while (System.currentTimeMillis() < until) {
                delay(interval * 1000L)
                when (val r = withContext(Dispatchers.IO) { PlusConnect.pollDevice(kind.id, c) }) {
                    is PlusConnect.PollResult.Done -> {
                        val who = withContext(Dispatchers.IO) { PlusConnect.whoAmI(kind.id, r.token) }
                        PlusConnect.saveOAuth(context, kind, r, who)
                        status = "已连接" + if (who.isNotBlank()) " · $who" else ""
                        code = null; busy = false
                        Toast.makeText(context, "已连接 ${kind.name}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    PlusConnect.PollResult.SlowDown -> interval += 5
                    PlusConnect.PollResult.Pending -> Unit
                    is PlusConnect.PollResult.Failed -> { status = "没有授权：${r.message}"; code = null; busy = false; return@launch }
                }
            }
            status = "授权码过期了，再点一次「一键授权」。"; code = null; busy = false
        }
    }
    LaunchedEffect(Unit) { if (autoStart) start() }

    PlusSection(null, footer = if (kind.method == ConnMethod.GITHUB)
        "授权后 AI 可以用 GITHUB_TOKEN 操作你的仓库（推送、删除等会先问你）。" else
        "授权后 AI 用 Microsoft Graph 读写 Outlook 邮件和日历。若公司账号被管理员禁止，可以改用下面的网页登录。") {
        val c = code
        if (c != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("你的授权码", fontSize = MT.Body)
                Spacer(Modifier.height(8.dp))
                Text(c.userCode, fontSize = MT.Code, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = MT.Caption)
            }
            SecondaryAction("再打开授权页") { openExternal(context, c.verifyUrl) }
        } else {
            BigAction(if (PlusVault.entries.value.containsKey(kind.id)) "重新授权" else "一键授权") { start() }
            if (status.isNotEmpty()) Text(status, fontSize = MT.Caption,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
        }
        if (kind.loginUrl != null) SecondaryAction(if (kind.method == ConnMethod.GITHUB) "也在 Mines 浏览器里登录 GitHub 网页" else "网页登录 Outlook") {
            onWeb(kind.loginUrl, true, "登录 ${kind.name}")
        }
    }
}

@Composable
private fun KeyBlock(kind: ConnectionKind, resumes: Int, autoStart: Boolean) {
    PlusSection(null, footer = if (com.openminis.app.plus.KeyAgent.recipe(kind.id) != null) "点「一键接入」或「去官网获取」：Mines 在自带浏览器里打开官方页面，你最多登录一次，密钥由 Mines 创建、填入、测试并加密保存。不会点任何付费按钮。"
        else "点「去官网获取」打开官方页面，复制密钥后回到这里，Mines 会自动识别、测试并保存。") {
        KeyBlockBody(kind, resumes, autoStart)
    }
}

@Composable
private fun KeyBlockBody(kind: ConnectionKind, resumes: Int, autoStart: Boolean) {
    val context = LocalContext.current
    // [Mines-38.9.1 e] observed (was `.value`, read once): KeyBlockBody's params never change, so it was skipped on recomposition
    val existing = PlusVault.entries.collectAsState().value[kind.id]
    var found by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(existing?.account ?: "") }
    var model by remember { mutableStateOf(PlusVault.extra(kind.id, "model") ?: com.openminis.app.plus.GeminiLive.DEFAULT_MODEL) }
    var voice by remember { mutableStateOf(PlusVault.extra(kind.id, "voice") ?: com.openminis.app.plus.GeminiLive.DEFAULT_VOICE) }
    val needsAccount = kind.id in setOf("gmail", "mail163", "qqmail")

    val mscope = rememberCoroutineScope()
    var mailMsg by remember { mutableStateOf<String?>(null) }
    fun save(secret: String) {
        // [Mines-38.9.1] mail codes are bound to their address: a real IMAP LOGIN + SELECT first, then saved under the entry for
        // THAT address (was: always the base "gmail" entry, so a second Gmail overwrote the first one's password)
        if (needsAccount) {
            val addr = com.openminis.app.plus.MailConfig.normalizeAddress(account.ifBlank { existing?.account ?: "" })
            mailMsg = "正在用 $addr 真登录一次邮箱…"
            mscope.launch {
                val why = com.openminis.app.plus.MailInbox.verifyAndAdd(context, addr, secret)
                mailMsg = why ?: "已接入 ✓ $addr（真登录过收件箱）"
                if (why == null) { found = null; manual = "" }
            }
            return
        }
        if (kind.id == "gemini") { PlusVault.setExtra(kind.id, "model", model); PlusVault.setExtra(kind.id, "voice", voice) }
        PlusVault.save(context, kind, account.ifBlank { existing?.account ?: "" }, secret,
            share = existing?.shareWithAi ?: (kind.envKey != null), via = "key")
        found = null; manual = ""
        Toast.makeText(context, "已连接 ${kind.name}", Toast.LENGTH_SHORT).show()
    }
    val hasRecipe = com.openminis.app.plus.KeyAgent.recipe(kind.id) != null
    // [Mines-keys-35.3] manual field only when the automatic flow can't do it (or the user asks for it)
    var manualOpen by remember { mutableStateOf(!hasRecipe) }
    var autoStatus by remember { mutableStateOf<String?>(null) }
    var autoBusy by remember { mutableStateOf(false) }
    val kscope = rememberCoroutineScope()
    /** clipboard / read-off key: test, then save without asking; a failed test leaves it prefilled with the reason */
    fun autoSave(k: String, from: String) {
        if (autoBusy) return
        if (needsAccount && account.isBlank()) { manual = k; manualOpen = true; autoStatus = "先填上你的邮箱地址，再点「保存并连接」"; return }
        if (needsAccount) { manual = com.openminis.app.plus.MailConfig.normalizeCode(k); save(manual); return }
        autoBusy = true; autoStatus = "$from 里有 ${kind.name} 的${kind.keyLabel}，正在测试…"
        kscope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.KeyTester.test(PlusVault.baseId(kind.id), k) }
            autoBusy = false
            if (r.ok) {
                save(k)
                r.identity?.let { PlusVault.setExtra(kind.id, "identity", it) }
                found = null
                autoStatus = "已接入 ✓ " + com.openminis.app.plus.AccountLabel.maskSecret(k) + (if (r.needsCredit) "\n" + r.message else "")
                runCatching { com.openminis.app.plus.AccountPatrol.recheck(context, kind.id) }
            } else { manual = k; manualOpen = true; found = null; autoStatus = "读到的${kind.keyLabel}测试没通过：${r.message}" }
        }
    }
    LaunchedEffect(resumes) {
        // Clipboard reads need window focus on Android 10+: try twice shortly after resuming.
        repeat(2) {
            delay(if (it == 0) 450 else 1200)
            val k = PlusConnect.keyOnClipboard(context, kind)
            if (k != null && k != PlusVault.secret(kind.id)) { autoSave(k, "剪贴板"); return@LaunchedEffect }
        }
    }
    LaunchedEffect(Unit) { if (autoStart && kind.keyUrl != null && kind.method == ConnMethod.KEY &&
        com.openminis.app.plus.KeyAgent.recipe(kind.id)?.mode != com.openminis.app.plus.KeyAgent.Mode.WEBVIEW) { delay(300); openExternal(context, kind.keyUrl) } }


    Column(Modifier.fillMaxWidth()) {
        // [Mines-keys-31] Mines creates the key itself; the user only logs in.
        // [Mines-keys-35.3] 「去官网获取」 runs the same automatic flow in Mines' own browser (was: Chrome + type it in)
        val onFallback: (String?) -> Unit = { cand ->
            manualOpen = true
            val k = cand?.let { c -> kind.keyRegex?.find(c)?.value ?: c } ?: PlusConnect.keyOnClipboard(context, kind)
            if (k != null && k != PlusVault.secret(kind.id)) { manual = k; autoSave(k, "页面") }
            else if (autoStatus == null) autoStatus = "没能自动拿到${kind.keyLabel}。在官网复制它，回到这里会自动填入；也可以粘贴到下面"
        }
        // [Mines-38.9.1] user 2026-10-16 「之前拿过的了，现在再拿一次」: this block always offered 一键接入 / 去官网获取, even with a
        // working saved key (••••dxhb), and never tested it. Now a saved credential is re-tested first; the get buttons
        // only show when nothing is saved, the test failed (its exact reason is shown), or the user asks for a new one.
        // [Mines-38.9.1 e] read through `existing` so this block recomposes when the key is deleted / added (smoke d: after
        // 删除这个API Key the stale 「保存的API Key没通过」 card + 「已接入 · 再生成」 stayed until the page was reopened)
        val saved = remember(existing) { PlusVault.secret(kind.id) }
        var savedTest by remember(saved) { mutableStateOf<com.openminis.app.plus.KeyTester.Result?>(null) }
        var wantNew by remember { mutableStateOf(false) }
        LaunchedEffect(saved) { if (saved != null) savedTest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            savedKeyTest(kind, account.ifBlank { existing?.account ?: "" }, saved) } }
        if (saved != null && !wantNew) {
            val t = savedTest
            val line = when {
                t == null -> "已保存 " + com.openminis.app.plus.AccountLabel.maskSecret(saved) + " · 正在测试…"
                t.ok -> "已接入 ✓ " + com.openminis.app.plus.AccountLabel.maskSecret(saved) + " · 刚测过，能用"
                t.message.startsWith("测试时连不上") -> "已保存 " + com.openminis.app.plus.AccountLabel.maskSecret(saved) + " · 这次没连上服务器，稍后再测（不用重新拿）"
                else -> "保存的${kind.keyLabel}没通过：" + t.message + if (needsAccount) "（先看看上面的邮箱地址对不对）" else ""
            }
            Text(line, fontSize = MT.Body, lineHeight = MT.BodyLH, color = if (t?.ok == true) Green else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { savedTest = null; kscope.launch { savedTest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    savedKeyTest(kind, account.ifBlank { existing?.account ?: "" }, saved) } } }) { Text("再测一次", fontSize = MT.Body) }
                TextButton(onClick = { wantNew = true }) { Text("换一个新的", fontSize = MT.Body) }
            }
        }
        val showGet = saved == null || wantNew || (savedTest?.let { !it.ok && !it.message.startsWith("测试时连不上") } ?: false)
        if (!showGet) Unit
        else if (hasRecipe) KeyAgentButton(kind, onFallback = onFallback, secondary = if (kind.keyUrl != null) "去官网获取" else null)
        else if (kind.keyUrl != null) SecondaryAction("去官网获取") { openExternal(context, kind.keyUrl); autoStatus = "复制好${kind.keyLabel}后回到这里，Mines 会自动识别、测试并保存" }
        mailMsg?.let { Text(it, fontSize = MT.Body, lineHeight = MT.BodyLH, color = if (it.startsWith("已接入")) Green else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        autoStatus?.let { Text(it, fontSize = MT.Body, lineHeight = MT.BodyLH,
            color = if (it.startsWith("已接入")) Green else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // [Mines-38.9.1] address check: a one-character typo in the address vs the Google account signed in inside Mines — one tap to fix
        if (needsAccount && account.contains('@')) {
            val known = remember { runCatching { com.openminis.app.plus.IdentityStore.init(context); com.openminis.app.plus.IdentityStore.current(context)
                .filter { it.value.contains('@') && (it.type == com.openminis.app.plus.IdentityCore.IdType.EMAIL || it.type == com.openminis.app.plus.IdentityCore.IdType.GOOGLE) }
                .map { it.value.trim().lowercase() }.distinct() }.getOrDefault(emptyList()) }
            val a0 = account.trim().lowercase()
            val near = known.firstOrNull { it != a0 && it.substringAfter('@') == a0.substringAfter('@') && editDistance(it.substringBefore('@'), a0.substringBefore('@')) in 1..2 }
            if (near != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("地址可能写错了：你在 Mines 里登录的是 $near", fontSize = MT.Body, color = Red, modifier = Modifier.weight(1f))
                TextButton(onClick = { account = near; existing?.let { e -> PlusVault.save(context, kind, near, null, share = e.shareWithAi, via = e.via) } }) { Text("改成它", fontSize = MT.Body) }
            }
        }
        if (needsAccount) OutlinedTextField(account, { account = it }, label = { Text(if (kind.id == "gmail") "Gmail 地址" else "邮箱地址") }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
        if (manualOpen || needsAccount) OutlinedTextField(
            manual, { manual = it },
            label = { Text(if (PlusVault.hasSecret(kind.id)) "或手动粘贴（已保存 ${PlusVault.preview(kind.id)}）" else "手动填写密钥") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        ) else TextButton(onClick = { manualOpen = true; PlusConnect.keyOnClipboard(context, kind)?.let { manual = it } },
            modifier = Modifier.padding(horizontal = 6.dp).heightIn(min = 44.dp)) { Text("手动填写密钥", fontSize = MT.Body) }
        if (kind.id == "gemini") {
            OutlinedTextField(model, { model = it }, label = { Text("实时语音模型") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.openminis.app.plus.GeminiLive.voices.forEach { v ->
                    androidx.compose.material3.FilterChip(selected = voice == v, onClick = {
                        voice = v; PlusVault.setExtra(kind.id, "voice", v); PlusVault.setExtra(kind.id, "voice_user", "1")
                    }, label = { Text(v + " " + (com.openminis.app.plus.GeminiLive.voiceLabels[v] ?: "")) })
                }
            }
        }
        if (manual.isNotBlank()) Button(
            onClick = { if (!needsAccount || account.isNotBlank()) autoSave(manual.trim(), "你填的内容") else Toast.makeText(context, "先填上你的 Gmail 地址", Toast.LENGTH_SHORT).show() },
            enabled = !autoBusy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 40.dp),
        ) { Text("保存并连接", fontSize = MT.Caption) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CalendarBlock(kind: ConnectionKind) {
    val context = LocalContext.current
    val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_CALENDAR] == true) {
            PlusVault.save(context, kind, "手机日历（含已同步的 Google 日历）", null, share = false, via = "app")
            Toast.makeText(context, "已连接 ${kind.name}", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(context, "没有日历权限", Toast.LENGTH_SHORT).show()
    }
    PlusSection(null, footer = "Google 日历会同步到手机日历。允许后 AI 用 android-calendar 查看和新建日程（新建、修改会先问你）。") {
        BigAction("允许读取日历") {
            if (perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                PlusVault.save(context, kind, "手机日历（含已同步的 Google 日历）", null, share = false, via = "app")
            } else launcher.launch(perms)
        }
    }
}

@Composable
private fun A11yBlock(kind: ConnectionKind, resumes: Int) {
    val context = LocalContext.current
    fun enabled(): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(context.packageName) == true
    }.getOrDefault(false)
    LaunchedEffect(resumes) {
        if (enabled() && !PlusVault.entries.value.containsKey(kind.id)) {
            PlusVault.save(context, kind, "通过无障碍操作", null, share = false, via = "app")
        }
    }
    PlusSection(null, footer = "微信没有手机网页版，只能扫码登录电脑版。Mines 改为通过无障碍服务帮你在微信里点按、读消息（发消息会先问你）。") {
        BigAction(if (enabled()) "无障碍已打开" else "打开 Mines 无障碍服务") {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}



/** [Mines-keys-31] One tap: Mines opens the key page, creates the key, tests it, saves it. */
@Composable
private fun KeyAgentButton(kind: ConnectionKind, onFallback: ((String?) -> Unit)? = null, secondary: String? = null) {
    val context = LocalContext.current
    val recipe = com.openminis.app.plus.KeyAgent.recipe(kind.id) ?: return
    var open by remember { mutableStateOf(false) }
    var chromeStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun start() {
        if (recipe.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW) open = true
        else {
            openExternal(context, recipe.url)
            chromeStatus = recipe.note
            scope.launch {
                val known = PlusVault.secret(kind.id)
                val key = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.openminis.app.plus.KeyAgent.runChromeA11y(context.applicationContext, recipe, known, { false }) { chromeStatus = it }
                }
                if (key != null) {
                    chromeStatus = "拿到了，正在测试…"
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.plus.KeyTester.test(PlusVault.baseId(kind.id), key) }
                    if (r.ok) {
                        PlusVault.save(context, kind, r.identity ?: "", key, share = kind.envKey != null, via = "key")
                        chromeStatus = "✓ 已接入：" + com.openminis.app.plus.AccountLabel.maskSecret(key)
                        runCatching { context.startActivity(android.content.Intent(context, com.openminis.app.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    } else { chromeStatus = "拿到的密钥测试没通过：${r.message}"; onFallback?.invoke(key) }
                } else onFallback?.invoke(null)
            }
        }
    }
    // [Mines-keys-32] a working key is already saved (or adopted from the 模型组): never ask again, only offer a new one
    BigAction(if (PlusVault.hasSecret(kind.id)) "已接入 · 再生成一个新密钥" else "一键接入") { start() }
    // [Mines-keys-35.3] 「去官网获取」 = the same automatic flow (Mines' own browser), not Chrome + typing
    if (secondary != null) SecondaryAction(secondary) { start() }
    chromeStatus?.let { Text(it, fontSize = MT.Body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    if (open) KeyAgentDialog(kind, onFallback = onFallback) { saved ->
        open = false
        if (saved) { Toast.makeText(context, "已接入 ${kind.name} ✓", Toast.LENGTH_SHORT).show(); chromeStatus = "已接入 ✓ " + PlusVault.preview(kind.id) }
    }
}


/** [Mines-privacy-35.3] FLAG_SECURE on the hosting window while this composable is shown (account pages, key dialogs). */
@Composable
fun SecureWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(view) {
        val w = (view as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()?.window
        // [Mines-38.7] user 2026-10-15: no screenshot block anywhere (they send screenshots to get help) — never set
        // FLAG_SECURE; clear it in case an older build left it on this window.
        w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}


/**
 * [Mines-38.9.1] delete one account / key of a service from anywhere (list long-press, 删除这个密钥, detail page), the last
 * one and the default included. Deleting the default makes the next account the default; a deleted key is tombstoned so
 * 模型组 adoption does not bring it back (it came back before: the root cause of 「删到 2 个就不行了」).
 */
@Composable
fun ConfirmDeleteAccount(id: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val kind = PlusVault.kind(id) ?: run { onDone(); return }
    val base = PlusVault.kind(PlusVault.baseId(id)) ?: kind
    val fromModel = PlusVault.extra(id, "from_model") != null || PlusVault.entries.value[id]?.via == "model"
    MinesDialog("删除这个${if (PlusVault.hasSecret(id)) kind.keyLabel.ifBlank { "密钥" } else "账号"}？", onDismiss = onDone, confirm = "删除", danger = true, onConfirm = {
        val wasDefault = PlusVault.defaultAccount(PlusVault.baseId(id)) == id
        if (kind.method == ConnMethod.WEB && wasDefault) PlusConnect.clearCookies(kind)
        PlusConnect.setKeepAlive(context, id, false)
        com.openminis.app.plus.LoginWeb.markSignedOut(id)
        com.openminis.app.plus.SessionKeeper.forget("conn:$id")   // [Mines-38.9.3]
        PlusVault.remove(context, kind)
        if (wasDefault) PlusVault.accountsOf(PlusVault.baseId(id)).firstOrNull()?.let { PlusVault.setDefault(context, it, swapCookies = false) }
        com.openminis.app.plus.AccountPatrol.recheck(context, id)
        Toast.makeText(context, "已删除 ${base.name} 的这个账号", Toast.LENGTH_SHORT).show()
        onDone()
    }) {
        Text(base.name + " · " + com.openminis.app.plus.AccountLabel.short(id, kind, PlusVault.entries.value[id]), fontSize = MT.Body, color = mines().ink)
        Text(if (fromModel) "这个密钥也在「模型组」里：这里删掉后不会再自动加回来；模型组里的那一份请到 设置 › 模型 里删。" else "删掉后 AI 就不会再用它。",
            fontSize = MT.Sub, lineHeight = MT.SubLH, color = mines().ink2)
    }
}


/** [Mines-38.9.1] small Levenshtein distance for the 「地址可能写错了」 check. */
fun editDistance(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) { var prev = d[0]; d[0] = i
        for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }
    return d[b.length]
}


/** [Mines-38.9.1] re-test a saved credential: mail app passwords by a real read-only IMAP login (address + password), keys by KeyTester. */
fun savedKeyTest(kind: ConnectionKind, account: String, secret: String): com.openminis.app.plus.KeyTester.Result? = runCatching {
    if (kind.id.substringBefore('#') in setOf("gmail", "mail163", "qqmail")) {
        val addr = com.openminis.app.plus.MailConfig.normalizeAddress(account)
        val cfg = com.openminis.app.plus.MailConfig.of(addr)
        if (cfg?.oauthOnly == true) return@runCatching com.openminis.app.plus.KeyTester.Result(false, com.openminis.app.plus.MailConfig.explain("", cfg, addr))
        val host = com.openminis.app.plus.MailImap.hostFor(addr) ?: return@runCatching com.openminis.app.plus.KeyTester.Result(false, "邮箱地址不对或不支持：$account")
        try { com.openminis.app.plus.MailImap.inbox(host, addr, com.openminis.app.plus.MailConfig.normalizeCode(secret), 1); com.openminis.app.plus.KeyTester.Result(true, "能登录") }
        catch (e: com.openminis.app.plus.MailImap.ImapError) {
            com.openminis.app.plus.KeyTester.Result(false, if (e.kind == com.openminis.app.plus.MailImap.Fail.NET) "测试时连不上服务器：${e.message}"
                else com.openminis.app.plus.MailConfig.explain(e.message ?: "", cfg, addr))
        }
    } else com.openminis.app.plus.KeyTester.test(PlusVault.baseId(kind.id), secret)
}.getOrNull()


/** [Mines-38.9.5 i] small outlined 「忽略」 / 「恢复」 pill (not red, not big). */
@Composable
private fun SmallFixPill(text: String, description: String, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(50)).background(m.primary).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description; role = androidx.compose.ui.semantics.Role.Button }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        com.openminis.app.ui.brand.MinesIcons.named("wrench")?.let { MinesIcon(it, null, tint = m.onPrimary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.onPrimary, maxLines = 1)
    }
}

@Composable
private fun SmallIgnorePill(text: String, description: String, onClick: () -> Unit) {
    val m = mines()
    Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).border(1.dp, m.glassBorder, RoundedCornerShape(50)).clickable(onClick = onClick)
        .semantics { contentDescription = description; role = androidx.compose.ui.semantics.Role.Button }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(text, fontSize = MT.Caption, color = m.ink2, maxLines = 1) }
}

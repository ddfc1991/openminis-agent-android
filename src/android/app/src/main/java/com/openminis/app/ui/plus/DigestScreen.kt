package com.openminis.app.ui.plus

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openminis.app.ui.glass.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.Digest
import com.openminis.app.plus.DigestX
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [Mines-feed-32] 消息: a feed in the home screen's visual language (same
 * wallpaper, frosted cards, chips), big readable text. Cards: icon circle,
 * 2-line takeaway headline, 3-4 line plain summary, action row
 * ❤ 更多这类 · 💬 讨论 · ✨ 行动起来 · ⓘ 为什么. Long-press = 置顶 / 静音 / 不感兴趣.
 * Sources that are off show as one-tap rows at the top instead of an empty list.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DigestScreen(onBack: () -> Unit) {
    var headMenu by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val items by Digest.items.collectAsState()
    val busy by Digest.busy.collectAsState()
    val vault by com.openminis.app.plus.PlusVault.entries.collectAsState()
    val scope = rememberCoroutineScope()
    // [Mines-feed-35.1] tab by name ("ALL" = 全部); null = not chosen yet → X when it has items
    var tabSel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(PlusNav.digestTab.also { PlusNav.digestTab = null }) }
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    var menuFor by remember { mutableStateOf<Digest.Item?>(null) }
    var whyFor by remember { mutableStateOf<Digest.Item?>(null) }
    var detailFor by remember { mutableStateOf<Digest.Item?>(null) }
    var editFollows by remember { mutableStateOf(false) }
    var xTick by remember { mutableStateOf(0) }
    var mailTick by remember { mutableStateOf(0) }   // [Mines-38.8] 邮箱 pull-to-refresh
    val mailBusy by com.openminis.app.plus.MailInbox.busy.collectAsState()
    val patrol by com.openminis.app.plus.AccountPatrol.status.collectAsState()   // [Mines-38.8] 已解决 cards
    var resumes by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // [Mines-38.9.2] the feed re-reads its sources whenever the page is opened or comes back (ON_RESUME also fires on the
    // first open), unless the last read is under 30 s old. Was: one cached refresh on first composition only.
    var resultLine by remember { mutableStateOf<String?>(null) }
    var pulled by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(resumes) {
        if (resumes == 0 || pulled) return@LaunchedEffect
        if (System.currentTimeMillis() - Digest.lastRefreshAt > 30_000L) {
            val o = Digest.refreshAsync(context, com.openminis.app.plus.FeedRefresh.Mode.OPEN).await()
            xTick++
            // on open, only say something when there is something to say
            if (o != null && (o.fresh.isNotEmpty() || o.fails.isNotEmpty()) && Digest.lastRefreshAt > 0) resultLine = com.openminis.app.plus.FeedRefresh.line(o)
        }
        runCatching { com.openminis.app.plus.MailInbox.refresh(context) }   // 邮箱: its own 30 s guard
    }
    LaunchedEffect(resultLine) { if (resultLine != null) { delay(10_000); resultLine = null } }   // [Mines-38.9.3] 6 s → 10 s (user reads slowly; smoke missed it too)
    // [Mines-sources-33] X is read off-screen by Digest.refresh (AutoSources.readX) whenever the X session exists

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val running by FeedTasks.running.collectAsState()
    val failed by FeedTasks.failed.collectAsState()
    // [Mines-feed-35.3] the card's main button does the work at once (was: only opened the detail sheet)
    fun act(it: Digest.Item) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        doAction(context, it, it.steps.ifEmpty { com.openminis.app.plus.DigestWriter.fallbackSteps(it) })
    }

    // [Mines-warm-36] 「我关注的」 is part of X (user rule): FOLLOW items show under X
    fun tabOf(it: Digest.Item) = if (it.tab == Digest.Tab.FOLLOW) Digest.Tab.X else it.tab
    // [Mines-38.5] 「为你推荐」 is the default tab: everything, ranked by the local interest model
    // [Mines-38.9.11 c] the 看板 opens on 每日简报 (Horizon-style briefing); 为你推荐 stays one tap away
    val curTab = tabSel ?: BRIEF_TAB
    // [Mines-warm-36] dark undo pill 「已忽略「…」 ↶ 撤销」 (5 s) instead of the stock snackbar
    var undo by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(5000); undo = null } }
    fun ignore(m: Digest.Item) {
        Digest.ignore(context, m)
        val (fh0, _) = com.openminis.app.plus.DigestWriter.fallback(m)
        undo = "已忽略「${com.openminis.app.plus.AccountLabel.maskText(m.headline ?: fh0).take(18)}」" to { Digest.unignore(context, m); Unit }
    }

    // [Mines-design-34] 「暖瓷 · 毛玻璃」 backdrop (warm fog, or the user's photo with the readability scrim)
    Box(Modifier.fillMaxSize()) {
        MinesBackdrop(Modifier.fillMaxSize())
        androidx.compose.material3.Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { androidx.compose.material3.SnackbarHost(snack) { d ->
                // [Mines-38.9.6 i14] glass toast (was the default dark Material snackbar); 撤销 keeps its a11y label
                GlassToast(Modifier.padding(12.dp).fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.visuals.message, fontSize = MT.Body, color = mines().ink, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { d.performAction() }, modifier = Modifier.semantics { contentDescription = "撤销忽略" }) {
                            Text("撤销", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = mines().primaryText) }
                    }
                }
            } },
            topBar = {
                // [Mines-warm-36] design36warm 03: back · face + name plate · 关注设置
                WarmHeader(
                    status = if (curTab == BRIEF_TAB) (if (com.openminis.app.plus.Horizon.busy.collectAsState().value) "正在替你写今天的简报" else "替你挑今天值得读的")
                        else if (curTab == MAIL_TAB) (if (mailBusy) "正在读你的邮箱" else "替你看着 ${com.openminis.app.plus.MailInbox.accounts.collectAsState().value.size.takeIf { it > 0 }?.let { "$it 个" } ?: ""}邮箱")
                        else if (busy) "正在替你看 X 和 GitHub" else "替你看着 X 和 GitHub",
                    modifier = Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(mines().bg.copy(alpha = 0.96f), mines().bg.copy(alpha = 0.90f), mines().bg.copy(alpha = 0f))))
                        .statusBarsPadding().padding(bottom = 2.dp),
                    leading = { WarmRoundButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onClick = onBack) },
                    trailing = { Box {
                        WarmRoundButton(Icons.Outlined.Tune, "关注设置和消息来源", onClick = { headMenu = true })
                        WarmMenu(headMenu, onDismiss = { headMenu = false }) {
                            WarmMenuItem("关注设置", WarmIcons.of("users")) { headMenu = false; editFollows = true }
                            WarmMenuItem("消息来源", WarmIcons.of("plus")) { headMenu = false; sourcesOpen = true }
                        }
                    } },
                    face = 56.dp,
                )
            },
            bottomBar = {
                undo?.let { (t, u) -> UndoBar(t, onAction = { u(); undo = null }, modifier = Modifier.navigationBarsPadding()) }
            },
        ) { pad ->
          // [Mines-feed-32] pull down to refresh (no big 刷新 button)
          // [Mines-38.9.2] user 2026-10-17 「下拉不更新」: the pull now really re-reads every source with no cache (waiting for a
          // read that is already running instead of being dropped), the circle stays exactly as long as that read (cap 60 s),
          // new cards go to the top, and one line says what happened: 「新到 3 条」/「没有新消息」/「163 授权码失效」.
          // Background refreshes never show the circle (38.8 rule) — only the plate line under the face.
          fun pull() {
              if (pulled) return
              pulled = true
              haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
              val tab = curTab
              scope.launch {
                  val line = try {
                      kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                          if (tab == BRIEF_TAB) com.openminis.app.plus.Horizon.runAsync(context).await()?.let { b -> "简报已更新：${b.items.size} 条" } ?: ("没生成出来：" + com.openminis.app.plus.Horizon.lastError.ifBlank { "稍后再试" })
                          else if (tab == MAIL_TAB || tab == CODE_TAB) com.openminis.app.plus.FeedRefresh.line(com.openminis.app.plus.MailInbox.refresh(context, force = true))
                          else com.openminis.app.plus.FeedRefresh.line(Digest.refreshAsync(context, com.openminis.app.plus.FeedRefresh.Mode.PULL).await())
                      } ?: "还在读，读完会自动更新"
                  } finally { pulled = false }
                  xTick++
                  resultLine = line
                  runCatching { listState.animateScrollToItem(0) }
              }
          }
          androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = pulled, onRefresh = { pull() },
            modifier = Modifier.fillMaxSize().padding(pad),
          ) {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
            ) {
                // [Mines-38.1] 「接入更多来源」 card removed: sources live behind ⚙ → 消息来源 and a faint 「＋ 添加来源」 row at the end
                // ---- [Mines-warm-36] 「N 件要处理」 strip + 4 tabs: 账号异常 / X / GitHub / 值得关注 (其他 only when it has items) ----
                item(key = "chips") {
                    val todo = items.filter { it.tab == Digest.Tab.ACCOUNT && !isResolved(it, patrol) }   // [Mines-38.8] resolved cards don't count
                    if (todo.isNotEmpty() && curTab != Digest.Tab.ACCOUNT.name) TodoSummaryBar(
                        todo.size,
                        todo.take(3).joinToString(" · ") { t -> feedClean(t.headline) ?: feedClean(t.title) ?: t.source },
                        onClick = { tabSel = Digest.Tab.ACCOUNT.name },
                    )
                    // [Mines-38.8] 「邮箱」 right after 为你推荐: every signed-in mailbox in one inbox
                    val mailAccts by com.openminis.app.plus.MailInbox.accounts.collectAsState()
                    @Suppress("UNUSED_VARIABLE") val mailStates by com.openminis.app.plus.MailInbox.states.collectAsState()
                    val tabs = listOf(WarmTab(BRIEF_TAB, "每日简报", 0), WarmTab(FOR_YOU, "为你推荐", 0), WarmTab(MAIL_TAB, "邮箱", if (mailAccts.isEmpty()) 0 else com.openminis.app.plus.MailInbox.unreadTotal()), WarmTab(CODE_TAB, "验证码", Codes3912.fresh(com.openminis.app.plus.MailInbox.mails.collectAsState().value).size)) + listOf(Digest.Tab.ACCOUNT, Digest.Tab.X, Digest.Tab.GITHUB, Digest.Tab.DEAL).map { t ->
                        WarmTab(t.name, t.label, items.count { tabOf(it) == t && !(t == Digest.Tab.ACCOUNT && isResolved(it, patrol)) }, danger = t == Digest.Tab.ACCOUNT)
                    } + (items.count { it.tab == Digest.Tab.OTHER }.takeIf { it > 0 }?.let { listOf(WarmTab(Digest.Tab.OTHER.name, "其他", it)) } ?: emptyList())
                    WarmTabs(tabs, curTab, onSelect = { k -> tabSel = k })
                    val rl = resultLine
                    if (rl != null) RefreshResultLine(rl)
                    else if (curTab != MAIL_TAB && curTab != BRIEF_TAB && curTab != CODE_TAB) feedClean(Digest.lastError)?.let { Text(it, fontSize = MT.Caption, color = mines().ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp)) }
                }
                if (curTab == BRIEF_TAB) item(key = "brief") {
                    HorizonBriefPane(onOpenWeb = { u -> onBack(); PlusNav.openBrowser.value = u })
                }
                if (curTab == CODE_TAB) item(key = "codes") { CodeInboxPane() }   // [Mines-38.9.12 b] 验证码 tab (design ws2 ①)
                if (curTab == MAIL_TAB) item(key = "mailbox") {
                    androidx.compose.foundation.layout.Column {
                    MailSummaryCard()   // [Mines-38.9.12 b] 摘要 card on top of the mailbox
                    MailboxPane(mailTick, onOpenWeb = { u -> onBack(); PlusNav.openBrowser.value = u })
                    }
                }
                val shown = if (curTab == MAIL_TAB || curTab == BRIEF_TAB || curTab == CODE_TAB) emptyList() else (if (curTab == "ALL" || curTab == FOR_YOU) items else items.filter { tabOf(it).name == curTab }).take(80)
                if (shown.isEmpty() && !busy && curTab != MAIL_TAB && curTab != BRIEF_TAB && curTab != CODE_TAB) item(key = "empty") {
                    Text(when (curTab) {
                        Digest.Tab.X.name -> "X 上暂时没有新消息。右上角「关注设置」可以加要关注的人和话题"
                        Digest.Tab.ACCOUNT.name -> "账号都正常，没有要你处理的异常"
                        else -> "这里暂时没有消息，下拉刷新"
                    }, fontSize = MT.Body, lineHeight = MT.BodyLH, color = mines().ink2, modifier = Modifier.padding(24.dp))
                }
                val now = System.currentTimeMillis()
                val startOfDay = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
                val groups = if (curTab == FOR_YOU) shown.groupBy { if (it.pinned) "置顶" else "按你的兴趣排序" }
                    else shown.groupBy { when { it.pinned -> "置顶"; it.at >= startOfDay -> "今天"; now - it.at < 7 * 86_400_000L -> "本周"; else -> "更早" } }
                listOf("置顶", "按你的兴趣排序", "今天", "本周", "更早").forEach { g ->
                    val list = groups[g] ?: return@forEach
                    item(key = "h_$g") { Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp)) {
                        Text(g, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = mines().ink2, modifier = Modifier.weight(1f))
                        if (g != "置顶" && g == groups.keys.firstOrNull { it != "置顶" }) Text("下拉刷新", fontSize = MT.Meta, color = mines().ink3)
                    } }
                    list.forEach { m ->
                        item(key = m.id) {
                            if (isResolved(m, patrol)) ResolvedCard(m, patrol, onOpen = { Digest.opened(context, m); detailFor = m }, onFold = { ignore(m) })
                            else FeedCard(m,
                                onOpen = { Digest.opened(context, m); detailFor = m },
                                onLong = { menuFor = m },
                                onLike = { Digest.like(context, m); android.widget.Toast.makeText(context, "好的，以后多推这类", android.widget.Toast.LENGTH_SHORT).show() },
                                onDiscuss = { discuss(context, m) },
                                onAct = { act(m) },
                                busy = m.id in running,
                                error = failed[m.id],
                                onShare = { shareItem(context, m) },
                                onMore = { menuFor = m },
                                onIgnore = { ignore(m) },
                            )
                        }
                    }
                }
                if (curTab != MAIL_TAB && curTab != BRIEF_TAB && curTab != CODE_TAB) item(key = "add_sources") {
                    @Suppress("UNUSED_EXPRESSION") resumes
                    @Suppress("UNUSED_EXPRESSION") vault
                    val rows = com.openminis.app.plus.AutoSources.missing(context)
                    if (rows.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
                        .clickable { sourcesOpen = true }.heightIn(min = 44.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("＋ 添加来源", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = mines().primaryText)
                        Spacer(Modifier.width(8.dp))
                        Text(rows.joinToString("、") { it.first }, fontSize = MT.Caption, color = mines().ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
          }
        }
    }
    detailFor?.let { m -> DetailSheet(m, onDismiss = { detailFor = null },
        onStart = { chosen -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            detailFor = null; doAction(context, m, chosen) },
        busy = m.id in running, error = failed[m.id],
        onMore = { menuFor = m }, onShare = { shareItem(context, m) }, onWhy = { whyFor = m }) }

    menuFor?.let { m ->
        // [Mines-38.5] long-press / ⋯ menu: frosted sheet, source avatar in the title, line icons, scrolls above the gesture bar
        MinesSheet(onDismiss = { menuFor = null }) {
            val wm = mines()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TopicIcon(m, toneColors(Tone.ACCENT).first, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(feedClean(com.openminis.app.plus.AccountLabel.maskText(m.sender))?.takeIf { it != m.source } ?: m.source, fontSize = MT.Body, fontWeight = FontWeight.SemiBold,
                        color = wm.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.source + " · " + relTime(m.at), fontSize = MT.Sub, color = wm.ink2, maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(1.dp).background(wm.hairline))
            fun ic(n: String) = WarmIcons.of(n)
            GlassMenuRow("喜欢，多推这类", ic("heart")) { Digest.like(context, m); FeedTasks.toast(context, "好的，以后多推这类"); menuFor = null }
            GlassMenuRow("在对话里讨论", ic("messages-square")) { menuFor = null; detailFor = null; discuss(context, m) }
            GlassMenuRow(if (m.pinned) "取消置顶" else "置顶", ic("pin")) { val was = m.pinned; Digest.pin(context, m); FeedTasks.toast(context, if (was) "已取消置顶" else "已置顶"); menuFor = null }
            GlassMenuRow("分享", ic("share")) { shareItem(context, m); menuFor = null }
            GlassMenuRow("分享为图片", ic("image")) { shareImage(context, m); menuFor = null }
            if (m.url != null || m.pkg != null) GlassMenuRow("查看原文", ic("globe")) { openOriginal(context, m); menuFor = null }
            GlassMenuRow("为什么推给我", ic("info")) { whyFor = m; menuFor = null }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(1.dp).background(wm.hairline))
            GlassMenuRow("静音此来源", ic("volume-2")) { Digest.mute(context, m); menuFor = null; detailFor = null
                undo = "已静音「${m.source}」" to { Digest.mute(context, m); Unit } }
            GlassMenuRow("不感兴趣", ic("eye-off"), danger = true) { Digest.notInterested(context, m); menuFor = null; detailFor = null
                undo = "以后少推这类" to { Digest.notInterested(context, m); Unit } }
        }
    }
    whyFor?.let { m ->
        MinesDialog("为什么排在这里", onDismiss = { whyFor = null }, confirm = "知道了", onConfirm = { whyFor = null }, dismiss = null) {
            Text(m.why.ifBlank { "按时间排序" }, fontSize = MT.Body, lineHeight = MT.BodyLH, color = mines().ink)
            m.url?.let { Text(it, fontSize = MT.Meta, color = mines().ink3, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (sourcesOpen) {
        // [Mines-38.5] 消息来源 = your accounts: every signed-in account with its status, 添加 (several per platform), 移除
        val wm = mines()
        var tick by remember { mutableStateOf(0) }
        val rows = remember(tick, vault, resumes) { com.openminis.app.plus.SourceAccounts.rows(context) }
        var removeFor by remember { mutableStateOf<com.openminis.app.plus.SourceAccounts.Row?>(null) }
        MinesSheet(onDismiss = { sourcesOpen = false }) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("消息来源", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = wm.ink)
                }
                GlassTextButton("关闭") { sourcesOpen = false }
            }
            Text("已接入 · ${rows.size}", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = wm.ink2, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
            if (rows.isEmpty()) Text("还没有。点下面的平台登录一次，Mines 就会替你看着。", fontSize = MT.Body, color = wm.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val notif = com.openminis.app.offload.MinisNotificationListenerService.isEnabled(context)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(WarmIcons.of(r.platform.icon) ?: WarmIcons.of("globe")!!, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title + " · " + r.account, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = wm.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(when (r.ok) {
                                true -> if (wm.dark) MinesDesign.SuccessDark else MinesDesign.Success; false -> MinesDesign.DangerFill; null -> wm.ink3 }))
                            Spacer(Modifier.width(6.dp))
                            Text(r.status, fontSize = MT.Sub, color = wm.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (r.ok == false) GlassTextButton("重新登录") { sourcesOpen = false; com.openminis.app.plus.SourceAccounts.add(r.platform) }
                    else if (r.entryId != null) GlassTextButton("移除", color = wm.ink2) { removeFor = r }
                }
            }
            Text("添加账号", fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = wm.ink2, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
            com.openminis.app.plus.SourceAccounts.platforms.forEach { p ->
                val has = rows.count { it.platform.kind == p.kind }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = p.kind != "wechat_mp") { sourcesOpen = false; com.openminis.app.plus.SourceAccounts.add(p) }
                    .padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = MT.Body, color = wm.ink, maxLines = 1)
                        Text(p.hint, fontSize = MT.Sub, color = wm.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (p.kind != "wechat_mp") Text(if (has > 0) "＋ 再加一个" else "＋ 添加", fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = wm.primaryText, maxLines = 1, softWrap = false)
                }
            }
            if (!notif) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                sourcesOpen = false; runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手机通知", fontSize = MT.Body, color = wm.ink)
                    Text("打开「通知使用权」，微信、短信验证码等才能读到", fontSize = MT.Sub, color = wm.ink2, maxLines = 2)
                }
                Text("去打开", fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = wm.primaryText)
            }
        }
        removeFor?.let { r ->
            MinesDialog("移除 ${r.title} · ${r.account}？", onDismiss = { removeFor = null }, confirm = "移除", danger = true,
                onConfirm = { r.entryId?.let { com.openminis.app.plus.SourceAccounts.remove(context, it) }; removeFor = null; tick++ }) {
                Text("会退出这个账号，它的消息不再出现在这里。之后可以随时再加回来。", fontSize = MT.Body, lineHeight = MT.BodyLH, color = wm.ink)
            }
        }
    }
    if (editFollows) {
        var text by remember { mutableStateOf(Digest.follows(context).joinToString("\n")) }
        var who by remember { mutableStateOf(Digest.senders(context).joinToString("\n")) }
        var langs by remember { mutableStateOf(Digest.languages(context)) }
        val wm = mines()
        MinesDialog("我关注的", onDismiss = { editFollows = false }, confirm = "保存", onConfirm = {
            Digest.setFollows(context, text.lines()); Digest.setSenders(context, who.lines()); Digest.setLanguages(context, langs); editFollows = false
            scope.launch { Digest.refreshAsync(context, com.openminis.app.plus.FeedRefresh.Mode.OPEN).await(); xTick++ }
        }) {
            Text("话题：一行一个关键词、币种或项目，命中的排前面", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = wm.ink2)
            OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：\nClaude\nBTC\n免费额度", fontSize = MT.Body, color = wm.ink3) })
            Text("重要的人：他们的消息总排最前", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = wm.ink2)
            OutlinedTextField(who, { who = it }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：\n@elonmusk\nboss@company.com\nopenai/codex", fontSize = MT.Body, color = wm.ink3) })
            Text("GitHub Trending 语言", fontSize = MT.Caption, color = wm.ink2)
            OutlinedTextField(langs, { langs = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("如 python, kotlin", fontSize = MT.Body, color = wm.ink3, maxLines = 1) })
        }
    }
}

private const val FOR_YOU = "FOR_YOU"
private const val CODE_TAB = "CODE"

/**
 * 💬 讨论: a new Mines chat about this card, sent at once (the old share hand-off was never consumed on
 * the 消息 page — see FeedTasks).
 */
private fun discuss(context: android.content.Context, m: Digest.Item) {
    val (fh, _) = com.openminis.app.plus.DigestWriter.fallback(m)
    FeedTasks.startAgent(context, m.id, "聊聊：" + (m.headline ?: fh), FeedTasks.promptFor(m, emptyList(), "discuss"))
}

/**
 * [Mines-feed-35.3] What 帮我处理 / 帮我领 / 帮我看看 / 学一下 / 一键修复 / 开始吧 do:
 *  - fix:<account>  → 应用 opens and 一键修复 (KeyAgentDialog) starts for that account
 *  - kind:<id> from 免费额度 with an in-app key recipe → 应用 → that service, 帮我领 (一键接入) starts at once
 *  - kind:<id> (account problem) → that service's page opens and it is re-checked now
 *  - route:<r> → that Mines page
 *  - anything else (and offers without a recipe) → a new Mines chat with the card's context, sent and running
 */
internal fun doAction(context: android.content.Context, m: Digest.Item, chosen: List<Pair<String, String>>) {
    val a = m.act
    val (fh, _) = com.openminis.app.plus.DigestWriter.fallback(m)
    val head = m.headline ?: fh
    when {
        a?.startsWith("fix:") == true && com.openminis.app.plus.PlusVault.kind(a.removePrefix("fix:")) != null -> {
            FeedTasks.toast(context, "正在打开一键修复…")
            PlusNav.fixAccount = a.removePrefix("fix:"); FeedTasks.go(PlusRoutes.CONNECTIONS)
        }
        a?.startsWith("kind:") == true && m.source == "免费额度" -> {
            val k = a.removePrefix("kind:")
            if (com.openminis.app.plus.KeyAgent.recipe(k)?.mode == com.openminis.app.plus.KeyAgent.Mode.WEBVIEW) {
                FeedTasks.toast(context, "正在打开 ${com.openminis.app.plus.PlusVault.kind(k)?.name ?: k}，开始帮你领…")
                PlusNav.openKind = k; PlusNav.autoClaim = true; FeedTasks.go(PlusRoutes.CONNECTIONS)
            } else FeedTasks.startAgent(context, m.id, "领取：$head", FeedTasks.promptFor(m, chosen, "claim"))
        }
        a?.startsWith("kind:") == true && com.openminis.app.plus.PlusVault.kind(a.removePrefix("kind:")) != null -> {
            val k = a.removePrefix("kind:")
            com.openminis.app.plus.PlusVault.accountsOf(k).forEach { id -> runCatching { com.openminis.app.plus.AccountPatrol.recheck(context, id) } }
            FeedTasks.toast(context, "正在重新检测 ${com.openminis.app.plus.PlusVault.kind(k)?.name ?: k}，并打开它的页面", long = true)
            PlusNav.openKind = k; FeedTasks.go(PlusRoutes.CONNECTIONS)
        }
        a?.startsWith("route:") == true -> { FeedTasks.toast(context, "正在打开…"); FeedTasks.go(a.removePrefix("route:")) }
        else -> {
            val mode = when { m.cat == Digest.Cat.DEAL -> "claim"; m.cat == Digest.Cat.ACT || m.tab == Digest.Tab.ACCOUNT -> "look"; else -> "do" }
            FeedTasks.startAgent(context, m.id, (if (mode == "claim") "领取：" else "处理：") + head, FeedTasks.promptFor(m, chosen, mode))
        }
    }
}

@Composable
private fun FeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // [Mines-design-34] selected = ink pill, others = strong glass + hairline (design33 02-feed)
    val m = mines()
    val shape = RoundedCornerShape(50)
    Text(label, fontSize = MT.Body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) m.surface else m.ink, maxLines = 1,
        modifier = Modifier.heightIn(min = 44.dp).clip(shape).background(if (selected) m.ink else m.glassStrong)
            .border(1.dp, if (selected) Color.Transparent else m.hairline, shape).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = label + if (selected) "（已选）" else "" })
}

/** [Mines-warm-36] drop empty / placeholder values ("null", "undefined", a dangling colon) — never shown on a card. */
internal fun feedClean(s: String?): String? = s?.replace(Regex("(?i)(?<![A-Za-z])(null|undefined)(?![A-Za-z])"), "")
    ?.replace(Regex("[：:·\\s]+$"), "")?.replace(Regex("^[：:·\\s]+"), "")?.trim()?.takeIf { it.isNotBlank() && it != "—" }

private val repoRe = Regex("github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
/** owner/name of a GitHub repo an X post links to (null when it links none). */
private fun repoOf(it: Digest.Item): String? = listOfNotNull(it.url, it.title, it.summary, it.brief).firstNotNullOfOrNull { t ->
    repoRe.find(t)?.groupValues?.get(1)?.removeSuffix(".git")?.trimEnd('.', ')')
}?.takeIf { r -> r.split('/').let { p -> p.size == 2 && p[0] !in setOf("orgs", "topics", "trending", "settings", "sponsors") } }

/**
 * [Mines-38.8] an 账号异常 card whose problem is gone: the account's latest patrol check is OK again, or the card's own
 * words say it was solved (「权限问题已解决：异常已排除」). Such a card shows as the green 「已解决」 card (check, 收起)
 * instead of 「已失效」 + 「一键修复」, and it no longer counts in 「N 件要处理」 / the 账号异常 tab.
 */
private val resolvedRx = Regex("已解决|已排除|已恢复|恢复正常|已修复|不用再处理|(?i)\\bresolved\\b")
private fun patrolKey(it: Digest.Item): String? = it.id.takeIf { i -> i.startsWith("pt:") }?.removePrefix("pt:")?.substringBeforeLast(':')?.takeIf { k -> k.isNotBlank() }
internal fun isResolved(it: Digest.Item, patrol: Map<String, com.openminis.app.plus.AccountPatrol.Status>): Boolean {
    if (!(it.tab == Digest.Tab.ACCOUNT || it.act?.startsWith("fix:") == true)) return false
    val k = patrolKey(it)
    if (k != null && patrol[k]?.level == com.openminis.app.plus.AccountPatrol.Level.OK && (patrol[k]?.at ?: 0L) >= it.at) return true
    return resolvedRx.containsMatchIn(listOfNotNull(it.headline, it.brief).joinToString(" "))
}

@Composable
private fun ResolvedCard(it: Digest.Item, patrol: Map<String, com.openminis.app.plus.AccountPatrol.Status>, onOpen: () -> Unit, onFold: () -> Unit) {
    val m = mines()
    val green = if (m.dark) MinesDesign.SuccessDark else MinesDesign.Success
    val greenBg = green.copy(alpha = if (m.dark) 0.20f else 0.13f)
    val own = feedClean(it.headline)?.let { h -> com.openminis.app.plus.AccountLabel.maskText(h) }
    val headline = own?.takeIf { h -> resolvedRx.containsMatchIn(h) } ?: "权限问题已解决：异常已排除"
    val okReason = patrolKey(it)?.let { k -> patrol[k] }?.takeIf { s -> s.level == com.openminis.app.plus.AccountPatrol.Level.OK }?.reason
    val brief = feedClean(it.brief)?.let { b -> com.openminis.app.plus.AccountLabel.maskText(b) }?.takeIf { b -> resolvedRx.containsMatchIn(b) && b != headline }
        ?: if (okReason == null || okReason == "密钥有效") "现在密钥有效、权限正常，不用再处理" else "现在$okReason，不用再处理"
    val sender = feedClean(com.openminis.app.plus.AccountLabel.maskText(it.sender))?.takeIf { s -> s != it.source } ?: it.source
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = "已解决：$headline" }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(greenBg), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = green, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Text("已解决", fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = green, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(greenBg).padding(horizontal = 7.dp, vertical = 1.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(listOfNotNull(sender.takeIf { s -> s.isNotBlank() }, relTime(it.at)).joinToString(" · "),
                        fontSize = MT.Caption, color = m.ink2, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text(headline, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(brief, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("收起", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.primaryText, maxLines = 1, softWrap = false,
                        modifier = Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onFold)
                            .semantics { contentDescription = "收起这条已解决" }.padding(horizontal = 10.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** What the card's / sheet's main button says (follows the action, not a generic 开始吧). */
private val warnRx = Regex("(?i)警告|警惕|小心|注意|风险|骗|钓鱼|假冒|盗号|被盗|漏洞|scam|phishing|warning|beware|exploit|hack")
private val howRx = Regex("(?i)教程|指南|手把手|入门|步骤|怎么|如何|方法|技巧|攻略|tutorial|guide|how to|tips")
// [Mines-38.5] the verb follows what the post IS: only a real claimable offer says 帮我领 (user: 警告 / 教程帖不要叫帮我领)
private fun claimable(it: Digest.Item) = it.act?.startsWith("kind:") == true && (it.source == "免费额度" || it.cat == Digest.Cat.DEAL)
private fun actLabelOf(it: Digest.Item): String {
    val t = it.title + " " + (it.headline ?: "") + " " + it.summary
    return when {
        it.act?.startsWith("fix:") == true -> "一键修复"
        claimable(it) -> "帮我领"
        it.act?.startsWith("kind:") == true || it.tab == Digest.Tab.ACCOUNT -> "去处理"
        warnRx.containsMatchIn(t) -> "帮我查查"
        it.cat == Digest.Cat.LEARN || howRx.containsMatchIn(t) -> "试试"
        it.cat == Digest.Cat.DEAL -> "看看"
        else -> "开始吧"
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FeedCard(it: Digest.Item, onOpen: () -> Unit, onLong: () -> Unit, onLike: () -> Unit, onDiscuss: () -> Unit, onAct: () -> Unit, onShare: () -> Unit, onMore: () -> Unit, onIgnore: () -> Unit = {},
                     busy: Boolean = false, error: String? = null) {
    // [Mines-warm-36] design36warm 03: lead face/icon · meta + source mark · bold Chinese headline · one plain line ·
    // (X: GitHub mini card / cover picture) · ♡ 讨论 忽略 ⋯ — account problems get the terracotta 一键修复 pill instead.
    val m = mines()
    val tone = when (it.cat) {
        Digest.Cat.ACT -> Tone.DANGER; Digest.Cat.DEAL -> Tone.SUCCESS; Digest.Cat.LEARN -> Tone.INFO
        Digest.Cat.HOT -> Tone.WARNING; Digest.Cat.OTHER -> Tone.NEUTRAL
    }
    val (toneFg, toneBg) = toneColors(tone)
    val (fh, fs) = com.openminis.app.plus.DigestWriter.fallback(it)
    val headline = glueNumbers(com.openminis.app.plus.AccountLabel.maskText(feedClean(it.headline) ?: feedClean(fh) ?: it.source))
    val brief = feedClean(it.brief ?: fs)?.let { b -> com.openminis.app.plus.AccountLabel.maskText(b) }?.takeIf { b -> b != headline }
    val isX = it.source == "X"
    val isGh = it.tab == Digest.Tab.GITHUB
    val isAcct = it.tab == Digest.Tab.ACCOUNT || it.act?.startsWith("fix:") == true
    val actLabel = actLabelOf(it)
    var liked by remember(it.id) { mutableStateOf(false) }
    val sender = feedClean(com.openminis.app.plus.AccountLabel.maskText(it.sender))?.takeIf { s -> s != it.source }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLong).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top) {
            if (isX && sender != null) {
                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFE08A6B), Color(0xFFB5532F)))),
                    contentAlignment = Alignment.Center) {
                    Text(sender.trimStart('@').take(1).uppercase(), fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            } else TopicIcon(it, toneFg, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    if (isAcct) {
                        Text(if (it.act?.startsWith("fix:") == true) "已失效" else "要处理", fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = toneColors(Tone.DANGER).first,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(toneColors(Tone.DANGER).second).padding(horizontal = 7.dp, vertical = 1.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text((if (it.pinned) "置顶 · " else "") + listOfNotNull(sender ?: it.source.takeIf { s -> s.isNotBlank() }, relTime(it.at)).joinToString(" · "),
                        fontSize = MT.Caption, color = m.ink2, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))   // [38.1b] was weight(fill=false)+Spacer(weight): text got half the row and cut 「1 小时前」
                    val mark = when { isX -> com.openminis.app.R.drawable.ic_brand_x; isGh -> com.openminis.app.R.drawable.ic_brand_github; else -> 0 }
                    if (mark != 0) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(mark), contentDescription = it.source,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
                Text(headline, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp))
                // GitHub: Chinese on top (big), the English name/description underneath in small print
                if (isGh) feedClean(it.title)?.takeIf { t -> t != headline }?.let { en ->
                    Text(en, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                }
                brief?.let { b -> Text(b, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) }
                if (isGh) feedClean(it.stars)?.let { st -> Text("★ $st", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = toneFg, maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(toneBg).padding(horizontal = 8.dp, vertical = 2.dp)) }
                if (isX) repoOf(it)?.let { r -> RepoMiniCard(r, null, null) }
                if (isX) it.image?.let { img ->
                    // [Mines-38.5] no fixed 150 dp box: a picture that never loads left a big blank in the card (user list C)
                    coil.compose.AsyncImage(model = img, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp).fillMaxWidth().heightIn(max = 170.dp).clip(RoundedCornerShape(14.dp)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isAcct || claimable(it)) {
                        WarmPrimaryPill(if (busy) "处理中…" else actLabel, onClick = onAct, enabled = !busy, height = 40.dp, fontSize = 14,
                            icon = if (actLabel == "一键修复") com.openminis.app.ui.brand.MinesIcons.named("wrench") else null,
                            description = "$actLabel：$headline")
                        Spacer(Modifier.width(4.dp))
                    } else {
                        androidx.compose.material3.IconButton(onClick = { liked = true; onLike() }, modifier = Modifier.size(40.dp).semantics { contentDescription = "喜欢，多推这类" }) {
                            com.openminis.app.ui.brand.MinesIcon(if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null,
                                tint = if (liked) m.icon else m.ink2, modifier = Modifier.size(19.dp))
                        }
                        TextAct(Icons.Outlined.ChatBubbleOutline, "讨论", "在对话里讨论这条", onDiscuss)
                    }
                    // [Mines-feed-35.1] 「忽略」 always visible on every card
                    TextAct(Icons.Outlined.VisibilityOff, "忽略", "忽略这条", onIgnore)
                    Spacer(Modifier.weight(1f))
                    ActIcon("⋯", "更多", m.ink2, onMore)
                }
                error?.let { e -> Text("没能开始：" + (if (e.any { it in '\u4e00'..'\u9fff' }) e else com.openminis.app.plus.ErrZhCore.source("这个来源", e).removePrefix("这个来源 ")) /* [38.9.11.3] Chinese only */, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = toneColors(Tone.DANGER).first, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp, end = 8.dp)) }
            }
        }
    }
}

/** Icon + word action (48 dp tall), e.g. 讨论 / 忽略. */
@Composable
private fun TextAct(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, description: String, onClick: () -> Unit) {
    val m = mines()
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        com.openminis.app.ui.brand.MinesIcon(icon, null, tint = m.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.ink2, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ActIcon(glyph: String, label: String, color: Color, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Text(glyph, fontSize = MT.PageTitle, color = color)
    }
}

@Composable
private fun SheetRow(text: String, onClick: () -> Unit) {
    Text(text, fontSize = MT.Body, color = mines().ink, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 13.dp))
}

/** Brand logo of the source when there is one, else a soft 3D-looking gradient orb with an emoji. */
@Composable
private fun TopicIcon(it: Digest.Item, catColor: Color, size: androidx.compose.ui.unit.Dp) {
    val brand = when {
        it.source == "GitHub" || it.source == "Trending" -> com.openminis.app.R.drawable.ic_brand_github
        it.source == "X" -> com.openminis.app.R.drawable.ic_brand_x
        it.source == "微信" -> com.openminis.app.R.drawable.ic_brand_wechat
        it.source == "Gmail" -> com.openminis.app.R.drawable.ic_brand_gmail
        it.act?.startsWith("kind:") == true -> com.openminis.app.plus.PlusVault.kind(it.act!!.removePrefix("kind:"))?.icon ?: 0
        else -> 0
    }
    if (brand != 0) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Color.White), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(brand), contentDescription = it.source, modifier = Modifier.size(size * 0.62f))
        }
        return
    }
    val emoji = when (it.cat) { Digest.Cat.ACT -> "⚠️"; Digest.Cat.DEAL -> "🎁"; Digest.Cat.LEARN -> "📘"; Digest.Cat.HOT -> "🔥"; else -> if (it.source == "账号巡查") "🛡️" else "💬" }
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
        .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), catColor.copy(alpha = 0.85f), catColor),
            center = androidx.compose.ui.geometry.Offset(size.value * 0.9f, size.value * 0.7f))),
        contentAlignment = Alignment.Center) { Text(emoji, fontSize = (size.value * 0.46f).sp) }
}

/**
 * [Mines-feed-32] Cue-style detail sheet, done better: big bold headline, a plain paragraph,
 * 「包含的内容」 (what Mines will do; each row can be switched off), source preview,
 * bottom bar ⋯ + wide 「✨ 开始吧」 that really starts the work in Mines; share and 原文 too.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailSheet(m: Digest.Item, onDismiss: () -> Unit, onStart: (List<Pair<String, String>>) -> Unit, onMore: () -> Unit, onShare: () -> Unit, onWhy: () -> Unit,
                        busy: Boolean = false, error: String? = null) {
    // [Mines-warm-36] design36warm 04: source row + ✕, big headline, plain paragraph, small facts, 「包含的内容 N 项」
    // (icon · bold title · one line · terracotta tick, each can be switched off), bottom bar: round ⋯ + terracotta pill
    // whose word follows the action (一键修复 / 去处理 / 帮我领 / 开始吧).
    val context = LocalContext.current
    val steps = remember(m.id) { m.steps.ifEmpty { com.openminis.app.plus.DigestWriter.fallbackSteps(m) } }
    val on = remember(m.id) { androidx.compose.runtime.mutableStateListOf<Boolean>().apply { repeat(steps.size) { add(true) } } }
    val (fh, fs) = com.openminis.app.plus.DigestWriter.fallback(m)
    val tone = when (m.cat) {
        Digest.Cat.ACT -> Tone.DANGER; Digest.Cat.DEAL -> Tone.SUCCESS; Digest.Cat.LEARN -> Tone.INFO
        Digest.Cat.HOT -> Tone.WARNING; Digest.Cat.OTHER -> Tone.NEUTRAL
    }
    val (toneFg, toneBg) = toneColors(if (m.tab == Digest.Tab.ACCOUNT) Tone.DANGER else tone)
    val isFix = m.act?.startsWith("fix:") == true
    val label = actLabelOf(m)
    val mc = mines()
    val headline = glueNumbers(com.openminis.app.plus.AccountLabel.maskText(feedClean(m.headline) ?: feedClean(fh) ?: m.source))
    // [Mines-38.5] sheet stops below the status bar, frosted glass, read time teaches 为你推荐
    val opened = remember(m.id) { System.currentTimeMillis() }
    androidx.compose.runtime.DisposableEffect(m.id) { onDispose { Digest.dwelled(context, m, System.currentTimeMillis() - opened) } }
    com.openminis.app.ui.glass.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding().padding(top = 8.dp),
        containerColor = glassVeil(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        GlassWindowEffect()
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopicIcon(m, toneFg, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull(Digest.Tab.values().firstOrNull { it == m.tab }?.let { t -> if (t == Digest.Tab.FOLLOW) "X" else t.label }, m.source.takeIf { it.isNotBlank() })
                        .distinct().joinToString(" · "), fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = mc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relTime(m.at), fontSize = MT.Caption, color = mc.ink2, maxLines = 1)
                }
                androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(mc.surface2).semantics { contentDescription = "关闭" }) {
                    com.openminis.app.ui.brand.MinesIcon(Icons.Outlined.Close, null, tint = mc.ink, modifier = Modifier.size(22.dp))
                }
            }
            Text(headline, fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, lineHeight = MT.TitleLH, color = mc.ink, modifier = Modifier.padding(top = 12.dp),
                style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
            if (m.tab == Digest.Tab.GITHUB) feedClean(m.title)?.takeIf { it != headline }?.let { en ->
                Text(en, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = mc.ink3, modifier = Modifier.padding(top = 4.dp)) }
            feedClean(m.brief ?: fs)?.let { b -> Text(com.openminis.app.plus.AccountLabel.maskText(b), fontSize = MT.Body, lineHeight = MT.BodyLH, color = mc.ink2, modifier = Modifier.padding(top = 8.dp)) }
            if (isFix) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("不花钱", "旧密钥留 7 天，随时可撤").forEach { f ->
                    Text(f, fontSize = MT.Caption, color = mc.ink2, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50)).background(mc.surface2).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            m.image?.let { img ->
                // whole picture (was cropped to a 170 dp strip)
                coil.compose.AsyncImage(model = img, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)))
            }
            if (steps.isNotEmpty()) {
                Row(Modifier.padding(top = 22.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("包含的内容", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = mc.ink, modifier = Modifier.weight(1f))
                    Text("${on.count { it }} 项", fontSize = MT.Caption, color = mc.ink2)
                }
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(mc.surface2.copy(alpha = if (mc.dark) 1f else 0.7f))) {
                    steps.forEachIndexed { i, (t, sub) ->
                        Row(Modifier.fillMaxWidth().clickable { on[i] = !on[i] }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics(mergeDescendants = true) { contentDescription = t + (if (sub.isNotBlank()) "，$sub" else "") + if (on[i]) "，已选" else "，未选" },
                            verticalAlignment = Alignment.CenterVertically) {
                            // [Mines-38.5] one marker per row (the left icon duplicated the tick on the right)
                            Column(Modifier.weight(1f)) {
                                Text(t, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = mc.ink)
                                if (sub.isNotBlank()) Text(sub, fontSize = MT.Sub, lineHeight = MT.SubLH, color = mc.ink2, modifier = Modifier.padding(top = 2.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (on[i]) mc.primary else Color.Transparent)
                                .border(2.dp, if (on[i]) mc.primary else mc.ink3, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center) { if (on[i]) com.openminis.app.ui.brand.MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("check")!!, null, tint = mc.onPrimary, modifier = Modifier.size(15.dp)) }
                        }
                        if (i < steps.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(mc.hairline))
                    }
                }
            }
            if (m.url != null || m.pkg != null) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(mc.surface2.copy(alpha = if (mc.dark) 1f else 0.7f))
                    .clickable { openOriginal(context, m) }.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("原文" + (feedClean(com.openminis.app.plus.AccountLabel.maskText(m.sender))?.let { " · $it" } ?: ""), fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = mc.ink, maxLines = 1)
                        feedClean(m.title.ifBlank { m.summary })?.let { Text(it, fontSize = MT.Caption, color = mc.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    Text("↗", fontSize = MT.PageTitle, color = mc.ink2)
                }
            }
            error?.let { e -> Text("没能开始：" + (if (e.any { it in '\u4e00'..'\u9fff' }) e else com.openminis.app.plus.ErrZhCore.source("这个来源", e).removePrefix("这个来源 ")) /* [38.9.11.3] Chinese only */, fontSize = MT.Caption, color = toneColors(Tone.DANGER).first, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(mc.hairline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.IconButton(onClick = onMore, modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(mc.surface2)
                .semantics { contentDescription = "更多：分享、置顶、静音、为什么推给我" }) {
                com.openminis.app.ui.brand.MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("ellipsis")!!, null, tint = mc.ink, modifier = Modifier.size(24.dp))
            }
            WarmPrimaryPill(if (busy) "处理中…" else label, onClick = { onStart(steps.filterIndexed { i, _ -> on[i] }) },
                enabled = (steps.isEmpty() || on.any { it }) && !busy, modifier = Modifier.weight(1f), height = 48.dp,
                icon = com.openminis.app.ui.brand.MinesIcons.named(if (isFix) "wrench" else "sparkles"), description = "$label：$headline")
        }
    }
}

/** A line icon for a 「包含的内容」 row, picked from its words. */
private fun stepIcon(t: String): String = when {
    Regex("密钥|令牌|key|token|Key|Token").containsMatchIn(t) -> "key-round"
    Regex("测试|检测|检查|验证").containsMatchIn(t) -> "shield-check"
    Regex("找|看|读|查").containsMatchIn(t) -> "search"
    Regex("领|注册|登录").containsMatchIn(t) -> "wallet"
    Regex("删|清理").containsMatchIn(t) -> "trash"
    Regex("提醒|通知").containsMatchIn(t) -> "clock"
    Regex("聊|讨论|问").containsMatchIn(t) -> "messages-square"
    else -> "check"
}

private fun openOriginal(context: android.content.Context, m: Digest.Item) {
    val u = m.url
    // [Mines-feed-35.3] fall back to any browser, and say so when nothing can open it (was swallowed)
    val ok = runCatching {
        if (u != null) {
            runCatching { com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(context, u) }.getOrElse {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } else m.pkg?.let { p -> context.packageManager.getLaunchIntentForPackage(p)?.let { li -> context.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } } ?: false
    }.getOrDefault(false)
    if (!ok) FeedTasks.toast(context, if (u == null && m.pkg != null) "没找到能打开的应用" else "打不开原文链接", long = true)
}

private fun shareText(m: Digest.Item): String {
    val (fh, fs) = com.openminis.app.plus.DigestWriter.fallback(m)
    return (m.headline ?: fh) + "\n\n" + (m.brief ?: fs) + (m.url?.let { "\n\n$it" } ?: "") + "\n—— 来自 Mines 消息汇总"
}

private fun shareItem(context: android.content.Context, m: Digest.Item) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(m)), "分享")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { FeedTasks.toast(context, "分享失败：${it.message}") }
}

/** 分享为图片: the card drawn as a picture (big text, high contrast). */
private fun shareImage(context: android.content.Context, m: Digest.Item) {
    runCatching {
        val (fh, fs) = com.openminis.app.plus.DigestWriter.fallback(m)
        val w = 1080
        val title = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 64f; isFakeBoldText = true }
        val body = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#E6E6EA"); textSize = 44f }
        val foot = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#9EA3AE"); textSize = 34f }
        fun layout(t: String, p: android.text.TextPaint) = android.text.StaticLayout.Builder.obtain(t, 0, t.length, p, w - 160).setLineSpacing(0f, 1.25f).build()
        val l1 = layout(m.headline ?: fh, title); val l2 = layout(m.brief ?: fs, body); val l3 = layout("Mines 消息汇总 · " + m.source, foot)
        val h = 120 + l1.height + 50 + l2.height + 70 + l3.height + 100
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.parseColor("#17181C"))
        c.translate(80f, 120f); l1.draw(c); c.translate(0f, l1.height + 50f); l2.draw(c); c.translate(0f, l2.height + 70f); l3.draw(c)
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val f = java.io.File(dir, "mines-card.png"); f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, shareText(m)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享为图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.widget.Toast.makeText(context, "分享失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun relTime(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 1440 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前" }
}

private fun sourceGlyph(s: String) = when (s) {
    "微信" -> "微"; "GitHub" -> "GH"; "Trending" -> "★"; "X" -> "X"; "Gmail" -> "M"; "账号巡查" -> "!"; else -> s.take(1)
}

/** Loads x.com searches for the first followed keywords in a hidden WebView (user's own cookies), extracts posts. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XReader(tick: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(tick) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.userAgentString = com.openminis.app.plus.MINES_MOBILE_UA
            com.openminis.app.browser.WebFit.apply(this)  // [Mines-webfit-38.3]
            webViewClient = WebViewClient()
        }
        suspend fun eval(js: String): String = suspendCancellableCoroutine { c ->
            wv.evaluateJavascript(js) { r -> if (c.isActive) c.resume(runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull() ?: "") }
        }
        val out = ArrayList<Digest.Item>()
        val qs = Digest.follows(context).take(4)
        try {
            for (q in qs) {
                wv.loadUrl("https://x.com/search?q=" + Uri.encode(q) + "&f=live")
                var got = ""
                repeat(8) { if (got.length < 10) { delay(1500); got = eval(DigestX.EXTRACT_JS) } }
                out += DigestX.parse(got, q)
            }
            wv.loadUrl("https://x.com/i/bookmarks"); delay(5000)
            out += DigestX.parse(eval(DigestX.EXTRACT_JS), "书签")
        } finally { runCatching { wv.destroy() } }
        if (out.isNotEmpty()) { DigestX.store(out.distinctBy { it.id }); onDone() }
    }
}


/**
 * [Mines-38.6] balanced headline breaks must not split a number from its unit (「2000 / 万 tokens」): the number, a
 * CJK unit right after it and a following unit word are glued with no-break characters (U+00A0 for a space,
 * U+2060 WORD JOINER where there was none), so the line breaker moves them as one piece.
 */
private val numUnit = Regex("""(\d[\d.,]*)( ?)(万亿|亿|万|千|百|[kKMBG]B?|%|％|元|美元|块|个|次|倍|小时|分钟|秒|天|年|月|人|条|篇|张|位|项|款|tokens?|Tokens?|stars?|星)(?:( )(tokens?|Tokens?|stars?|美元|元|次|人))?""")

fun glueNumbers(s: String): String = numUnit.replace(s) { m ->
    val (num, sp, unit) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    val tail = m.groupValues[5]
    num + (if (sp.isNotEmpty()) "\u00A0" else "\u2060") + unit + (if (tail.isNotEmpty()) "\u00A0" + tail else "")
}

/**
 * [Mines-38.9.2] the one line after a pull: 「新到 3 条」 / 「没有新消息」 / 「没有新消息 · 163 授权码失效」.
 * Readable (15 sp, contrast-checked ink / danger colour, wraps to 2 lines), and read out by TalkBack (polite live region).
 */
@Composable
internal fun RefreshResultLine(text: String) {
    val failed = text.contains(" · ")
    val (fg, bgc) = toneColors(if (failed) Tone.WARNING else if (text.startsWith("新到")) Tone.SUCCESS else Tone.NEUTRAL)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, fontSize = MT.Body, lineHeight = MT.BodyTightLH, fontWeight = FontWeight.Medium, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bgc).padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite; contentDescription = "刷新结果：$text" })
    }
}

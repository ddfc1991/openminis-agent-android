package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.plus.ChatHome
import com.openminis.app.plus.ChatHomeCore
import com.openminis.app.ui.brand.MinesIcon

/**
 * [Mines-38.9.14 chat_home] mockup chat3b: the 4-tab frosted glass dock (聊天 / 任务 / 消息 / 应用), the 「已建任务」 chip and
 * task notices inside 聊天, the 任务 page groups (等你处理 / 进行中 / 已完成), the 实验功能 switch. Normal type (MT), glass fill.
 */
object ChatHomeNav {
    /** set by AppNavigation: back to the old conversation list (任务 → 全部对话 / 历史) */
    @Volatile var history: (() -> Unit)? = null
    /** [38.9.14.2] the launch resolver has run (the SESSION_LIST gate waits for it, so the two never both navigate) */
    val launchResolved = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** [38.9.14.2] the list was opened on purpose (全部对话 / in-app browser); cleared when it leaves the screen */
    @Volatile var listAllowed = false
    /** [38.9.14.2] no main conversation could be opened (no session, no provider): show the old list as before */
    val gateFailed = kotlinx.coroutines.flow.MutableStateFlow(false)
}

private fun icH(n: String) = WarmIcons.of(n)!!

@Composable
private fun toneColor(t: ChatHomeCore.Tone): Color {
    val d = mines().dark
    return when (t) {
        ChatHomeCore.Tone.WAIT -> if (d) Color(0xFFF2C14E) else Color(0xFF7A5200)
        ChatHomeCore.Tone.RUN -> if (d) Color(0xFF8DB4FF) else Color(0xFF1F55B5)
        ChatHomeCore.Tone.ERR -> if (d) Color(0xFFFF8A80) else Color(0xFFB3261E)
        ChatHomeCore.Tone.DONE -> if (d) Color(0xFF81C995) else Color(0xFF256B2C)
        ChatHomeCore.Tone.IDLE -> mines().ink2
    }
}

private fun hm(at: Long): String = if (at <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

// ───────────────────────────── 聊天: chip + notices ─────────────────────────────

/** one task row written into 聊天: CREATED = chip + 「已创建任务」 line; 等你 / 出错 / 完成 = a card naming the task */
@Composable
fun MinesTaskNotice(tag: ChatHomeCore.Tag, fold: ChatHomeCore.Fold? = null) {
    if (fold?.show == false) return   // [38.9.14.1] folded into this task's newest card
    val m = mines()
    val tasks by ChatHome.tasks.collectAsState()
    val t = tasks.firstOrNull { it.id == tag.taskId }
    val open: () -> Unit = { t?.let { PlusNav.go(TaskRoutes.chat(it.sid)) } }
    // [Mines-38.9.14.2] user 2026-10-25 20:16 「首页这样多少有点乱」: every task notice (已建 / 等你 / 出错 / 完成) is ONE small
    // chip line 「Zeabur 注册 · 完成 ›」 that opens the task; the body text and the retry history live in the task itself.
    // 已建: the task's live state while it runs; once it has finished / stopped, the later notice chip carries the state.
    val name = t?.name ?: "任务"
    val (state, tone) = when (tag.kind) {
        ChatHomeCore.Kind.CREATED -> {
            val chip = t?.let { ChatHomeCore.chip(it) }
            val live = t != null && (t.st == ChatHomeCore.St.RUNNING || t.st == ChatHomeCore.St.QUEUED)
            if (live && chip != null) (chip.state + (if (chip.progress.isNotEmpty()) " " + chip.progress else "")) to chip.tone
            else "已建任务" to ChatHomeCore.Tone.IDLE
        }
        ChatHomeCore.Kind.ASK -> "等你处理" to ChatHomeCore.Tone.WAIT
        ChatHomeCore.Kind.ERROR -> "出错" to ChatHomeCore.Tone.ERR
        ChatHomeCore.Kind.DONE -> "完成" to ChatHomeCore.Tone.DONE
    }
    val c = toneColor(tone)
    val retry = fold?.retryLine.orEmpty()
    val a11y = (if (tag.kind == ChatHomeCore.Kind.CREATED) "已建任务 " else "") + "$name，$state" +
        (if (tag.body.isNotBlank() && tag.kind != ChatHomeCore.Kind.CREATED) "：" + tag.body.trimEnd('。') else "") +
        (if (retry.isNotEmpty()) "，$retry" else "") + "。点按打开任务"
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(50)).background(minesGlassFill()).border(1.dp, m.glassBorder, RoundedCornerShape(50))
            .clickable(enabled = t != null, onClick = open).heightIn(min = 34.dp).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y; role = Role.Button },
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(8.dp))
            Text("$name · ", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(state, fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, softWrap = false)
            MinesIcon(icH("chevron-right"), null, tint = m.ink2, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        }
    }
}

/** 「正在安排…」 while the router decides (above the composer of 聊天) */
@Composable
fun ChatHomeRoutingPill(modifier: Modifier = Modifier) {
    val r by ChatHome.routing.collectAsState()
    if (r == null) return
    val m = mines()
    Row(modifier.clip(RoundedCornerShape(50)).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, RoundedCornerShape(50))
        .heightIn(min = 32.dp).padding(horizontal = 14.dp).semantics { contentDescription = "正在判断：直接回答还是建任务" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(toneColor(ChatHomeCore.Tone.RUN)))
        Spacer(Modifier.width(8.dp))
        Text("正在安排…", fontSize = MT.Caption, color = m.ink2, maxLines = 1)
    }
}

// ───────────────────────────── dock ─────────────────────────────

/** the 4-tab dock: transparent frosted glass capsule (sheen + hairline rim), normal-size labels */
@Composable
fun ChatHomeDock(current: Int, onChat: () -> Unit, onTasks: () -> Unit, onMessages: () -> Unit, onApps: () -> Unit, modifier: Modifier = Modifier) {
    val m = mines()
    val tasks by ChatHome.tasks.collectAsState()
    val waiting = tasks.count { it.st == ChatHomeCore.St.WAITING || it.st == ChatHomeCore.St.ERROR }
    val shape = RoundedCornerShape(30.dp)
    val items = listOf(
        WarmNavItem("聊天", icH("messages-square"), onClick = onChat),
        WarmNavItem("任务", icH("list-checks"), badge = waiting, onClick = onTasks),
        WarmNavItem("消息", icH("mail"), onClick = onMessages),
        WarmNavItem("应用", icH("layout-grid"), onClick = onApps),
    )
    // [Mines-38.9.14.1] real frosted glass: the page behind (recorded by [dockBlurSource]) is drawn here blurred (RenderEffect,
    // API 31+) under a 76 % tint; without blur (API < 31 / no source yet) the tint is 92 % so list text never reads through.
    val src = DockBlur.layer
    val blur = src != null && android.os.Build.VERSION.SDK_INT >= 31
    var dockPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    Box(
        modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .glassOuterShadow(shape, dark = m.dark).clip(shape)
            .onGloballyPositioned { dockPos = it.positionInRoot() },
    ) {
        if (blur && src != null) Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    renderEffect = androidx.compose.ui.graphics.BlurEffect(radiusPx, radiusPx, androidx.compose.ui.graphics.TileMode.Clamp)
                    clip = true   // the outer Box already clips to the dock's rounded shape
                }
                .drawBehind {
                    val o = DockBlur.origin
                    translate(o.x - dockPos.x, o.y - dockPos.y) { drawLayer(src) }
                },
        )
        Box(Modifier.matchParentSize().background(GlassTint.of(m.dark).copy(alpha = if (blur) DockBlur.TINT_BLUR else DockBlur.TINT_SOLID)))
    Row(
        Modifier.fillMaxWidth().glassSheen(shape, m.dark).border(1.dp, m.glassBorder, shape)
            .heightIn(min = 58.dp).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, it ->
            val on = i == current
            val fg = if (on) m.primaryText else m.ink2
            Column(
                Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = it.onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = it.label + (if (it.badge > 0) "，${it.badge} 件等你处理" else ""); this.selected = on; role = Role.Tab
                    },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    MinesIcon(it.icon, null, tint = fg, modifier = Modifier.padding(top = 2.dp).size(21.dp))
                    if (it.badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp).heightIn(min = 16.dp).widthIn(min = 16.dp)
                        .clip(RoundedCornerShape(50)).background(MinesDesign.DangerFill).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(if (it.badge > 99) "99+" else it.badge.toString(), fontSize = MT.Tiny, lineHeight = MT.CaptionLH, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
                Text(it.label, fontSize = MT.Nav, lineHeight = MT.CaptionLH, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = fg,
                    maxLines = 1, softWrap = false)
            }
        }
    }
    }   // glass Box
}

// ───────────────────────────── 任务 page ─────────────────────────────

/** top of 任务 when 聊天主页 is on: 全部 / 等你 / 定时 › tabs, then 等你处理 / 进行中 / 已完成 and 全部对话 / 历史 */
@Composable
fun AutoTasksSection(go: (String) -> Unit, inProjects: Set<String> = emptySet()) {
    if (!ChatHome.enabled()) return
    // [Mines-38.9.14.1] a finished task that became a 项目 attempt is shown once, on its 项目 card (not again under 已完成)
    val all by ChatHome.tasks.collectAsState()
    val tasks = all.filter { !(it.sid in inProjects && it.st == ChatHomeCore.St.DONE) }
    var onlyWaiting by remember { mutableStateOf(false) }
    val (wait, run, done) = ChatHomeCore.groups(tasks)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TabPill("全部 ${tasks.size}", !onlyWaiting) { onlyWaiting = false }
        TabPill("等你 ${wait.size}", onlyWaiting) { onlyWaiting = true }
        TabPill("定时 ›", false) { go("scheduled_tasks") }
    }
    WarmGroupLabel("等你处理")
    if (wait.isEmpty()) InfoLine("没有等你处理的任务。")
    else TaskGroup(wait, go)
    if (onlyWaiting) return
    WarmGroupLabel("进行中")
    if (run.isEmpty()) InfoLine("在「聊天」里说要做的事，Mines 会自动建任务在后台运行。")
    else TaskGroup(run, go)
    WarmGroupLabel("已完成", "全部对话 ›") { ChatHomeNav.history?.invoke() }
    if (done.isEmpty()) InfoLine(if (all.size > tasks.size) "完成的任务在下面它的项目里。" else "还没有完成的任务。")
    else TaskGroup(done.take(10), go)
}

@Composable
private fun TabPill(text: String, on: Boolean, onClick: () -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(50)
    Text(text, fontSize = MT.Caption, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) m.ink else m.ink2, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(shape).background(if (on) minesGlassFill(strong = true) else Color.Transparent).border(1.dp, if (on) m.glassBorder else Color.Transparent, shape)
            .clickable(onClick = onClick).semantics { role = Role.Tab; selected = on }.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun TaskGroup(list: List<ChatHomeCore.AutoTask>, go: (String) -> Unit) {
    val m = mines()
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        list.forEachIndexed { i, t ->
            if (i > 0) WarmDivider(start = 16.dp)
            val chip = ChatHomeCore.chip(t)
            val tone = toneColor(chip.tone)
            Row(Modifier.fillMaxWidth().clickable { go(TaskRoutes.chat(t.sid)) }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${t.name}，${chip.state}。${t.line.ifBlank { t.goal }}" },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.line.ifBlank { t.goal }, fontSize = MT.Caption, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(chip.state + (if (chip.progress.isNotEmpty()) " " + chip.progress else ""), fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = tone,
                        maxLines = 1, softWrap = false, modifier = Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Text(hm(t.updatedAt.coerceAtLeast(t.createdAt)), fontSize = MT.Meta, color = m.ink2, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

// ───────────────────────────── 实验功能 ─────────────────────────────

@Composable
fun ChatHomeFlagRow() {
    val ctx = LocalContext.current
    ChatHome.init(ctx)
    val on by ChatHome.on.collectAsState()
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { ChatHome.setOn(ctx, !on) }.padding(horizontal = 16.dp, vertical = 10.dp)
        .semantics { contentDescription = "聊天主页，" + if (on) "已打开" else "已关闭" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("聊天主页", fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink)
            Text("打开即进入一个主对话，要做的事自动建任务在后台运行；关闭后回到对话列表首页", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MinesSwitch(on, "聊天主页") { ChatHome.setOn(ctx, it) }
    }
}


/** [Mines-38.9.14.1] what the dock blurs: the NavHost content, recorded each frame into one GraphicsLayer (Haze-style). */
object DockBlur {
    const val TINT_BLUR = 0.76f
    const val TINT_SOLID = 0.92f
    /** dock height without the navigation bar inset: 6 + 3 + 58 + 3 + 6 dp */
    val DOCK_H = 76.dp
    var layer by mutableStateOf<androidx.compose.ui.graphics.layer.GraphicsLayer?>(null)
    @Volatile var origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero
    @Volatile var warm = false
}

/** records the content for [ChatHomeDock]'s blur (only while 聊天主页 is on and blur exists, API 31+) */
@Composable
fun Modifier.dockBlurSource(): Modifier {
    val on by ChatHome.on.collectAsState()
    if (!on || android.os.Build.VERSION.SDK_INT < 31) return this
    // [Mines-38.9.14.2] not during cold start: recording the whole NavHost and blurring it on the very first frames put a
    // 3 s nSyncAndDrawFrame stall on the main thread (smoke 40185488340, screen chat/{sessionId}) → hang breaker → old
    // home. The dock uses its solid tint for the first 2.5 s, then the blur starts.
    var warm by androidx.compose.runtime.remember { mutableStateOf(DockBlur.warm) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!warm) { kotlinx.coroutines.delay(2_500); DockBlur.warm = true; warm = true }
    }
    if (!warm) return this
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    androidx.compose.runtime.DisposableEffect(layer) {
        DockBlur.layer = layer
        onDispose { if (DockBlur.layer === layer) DockBlur.layer = null }
    }
    return this.onGloballyPositioned { DockBlur.origin = it.positionInRoot() }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** [Mines-38.9.14.1] bottom room a tab page's list needs so its last item scrolls above the dock (0 when the dock is off) */
@Composable
fun chatHomeDockPad(): androidx.compose.ui.unit.Dp {
    if (!ChatHome.enabled()) return 0.dp
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars
    val navPad = with(androidx.compose.ui.platform.LocalDensity.current) { nav.getBottom(this).toDp() }
    return DockBlur.DOCK_H + navPad + 12.dp
}

// ───────────────────────────── background keep (net_drop) ─────────────────────────────

/**
 * [Mines-38.9.14.1 net_drop] one-tap guide so a running task is not frozen / cut off in the background (user 2026-10-25:
 * tasks died with 「Software caused connection abort」). Step 1: system dialog 「允许在后台运行」 (one tap 允许).
 * Step 2 (realme / OPPO / OnePlus only): the app page where 「耗电管理 → 允许后台行为 / 自启动」 lives. ✕ hides it for good.
 * Re-checked every time the app comes back.
 */
object NetKeep {
    private const val P = "mines_netkeep"
    fun oem(): Boolean = android.os.Build.MANUFACTURER.lowercase().let { it.contains("realme") || it.contains("oppo") || it.contains("oneplus") }
    fun exempt(c: android.content.Context): Boolean = runCatching {
        c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(true)
    fun flag(c: android.content.Context, k: String) = c.getSharedPreferences(P, 0).getBoolean(k, false)
    fun set(c: android.content.Context, k: String) { c.getSharedPreferences(P, 0).edit().putBoolean(k, true).apply() }
    /** 0 = nothing to show, 1 = battery step, 2 = OEM background step */
    fun step(c: android.content.Context): Int = when {
        flag(c, "closed") -> 0
        !exempt(c) -> 1
        oem() && !flag(c, "oem_done") -> 2
        else -> 0
    }
}

@Composable
fun NetKeepCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val step = remember(tick) { NetKeep.step(ctx) }
    if (step == 0) return
    val m = mines()
    val text = if (step == 1) "任务在后台不断网：点这里，系统问时选「允许」" else "还差一步：点这里，进「耗电管理」打开「允许后台行为」"
    Row(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(minesGlassFill(strong = true))
        .border(1.dp, m.glassBorder, RoundedCornerShape(16.dp))
        .clickable {
            val pkg = android.net.Uri.parse("package:" + ctx.packageName)
            val i = if (step == 1) android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).also { NetKeep.set(ctx, "oem_done") }
            runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            tick++
        }
        .semantics { contentDescription = text }
        .heightIn(min = 44.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(toneColor(ChatHomeCore.Tone.WAIT)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = MT.Caption, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { NetKeep.set(ctx, "closed"); tick++ }.semantics { contentDescription = "不再提示" },
            contentAlignment = Alignment.Center) { Text("✕", fontSize = MT.Caption, color = m.ink2) }
    }
}

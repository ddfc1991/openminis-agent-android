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
fun MinesTaskNotice(tag: ChatHomeCore.Tag) {
    val m = mines()
    val tasks by ChatHome.tasks.collectAsState()
    val t = tasks.firstOrNull { it.id == tag.taskId }
    val open: () -> Unit = { t?.let { PlusNav.go(TaskRoutes.chat(it.sid)) } }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        if (tag.kind == ChatHomeCore.Kind.CREATED) {
            val chip = t?.let { ChatHomeCore.chip(it) }
            val tone = toneColor(chip?.tone ?: ChatHomeCore.Tone.IDLE)
            Row(Modifier.clip(RoundedCornerShape(50)).background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, RoundedCornerShape(50))
                .clickable(enabled = t != null, onClick = open).heightIn(min = 36.dp).padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = (chip?.a11y ?: "已建任务") + "。点按打开任务"; role = Role.Button },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(8.dp))
                Text("已建任务 · " + (t?.name ?: "任务"), fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = m.primaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                if (chip != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(chip.state + (if (chip.progress.isNotEmpty()) " " + chip.progress else ""), fontSize = MT.Meta, fontWeight = FontWeight.SemiBold,
                        color = tone, maxLines = 1, softWrap = false)
                }
                MinesIcon(icH("chevron-right"), null, tint = m.ink2, modifier = Modifier.padding(start = 2.dp).size(14.dp))
            }
            Spacer(Modifier.size(6.dp))
            Box(Modifier.fillMaxWidth(0.86f).clip(shape).background(minesGlassFill()).border(1.dp, m.glassBorder, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(tag.body, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
            }
        } else {
            val tone = toneColor(when (tag.kind) { ChatHomeCore.Kind.ASK -> ChatHomeCore.Tone.WAIT; ChatHomeCore.Kind.ERROR -> ChatHomeCore.Tone.ERR; else -> ChatHomeCore.Tone.DONE })
            val word = when (tag.kind) { ChatHomeCore.Kind.ASK -> "等你"; ChatHomeCore.Kind.ERROR -> "出错"; else -> "完成" }
            Column(Modifier.fillMaxWidth(0.86f).clip(shape).background(minesGlassFill()).border(1.dp, m.glassBorder, shape)
                .clickable(enabled = t != null, onClick = open)
                .semantics(mergeDescendants = true) { contentDescription = "${t?.name ?: "任务"} $word：${tag.body.trimEnd('。')}。点按打开任务" }
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((t?.name ?: "任务") + (if (t != null) " · 回复你 ${hm(t.createdAt)} 的消息" else ""), fontSize = MT.Meta, color = m.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(word, fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = tone, maxLines = 1, softWrap = false,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Text(tag.body, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink, modifier = Modifier.padding(top = 4.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
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
    Row(
        modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .glassOuterShadow(shape, dark = m.dark).clip(shape).background(minesGlassFill()).glassSheen(shape, m.dark).border(1.dp, m.glassBorder, shape)
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
}

// ───────────────────────────── 任务 page ─────────────────────────────

/** top of 任务 when 聊天主页 is on: 全部 / 等你 / 定时 › tabs, then 等你处理 / 进行中 / 已完成 and 全部对话 / 历史 */
@Composable
fun AutoTasksSection(go: (String) -> Unit) {
    if (!ChatHome.enabled()) return
    val tasks by ChatHome.tasks.collectAsState()
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
    if (done.isEmpty()) InfoLine("还没有完成的任务。")
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

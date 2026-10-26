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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.plus.ChatOrganizer
import com.openminis.app.plus.Inbox3912Core
import com.openminis.app.plus.TaskProgress
import com.openminis.app.plus.TaskProgressCore
import com.openminis.app.plus.TaskProjects
import com.openminis.app.plus.TaskProjectsCore
import com.openminis.app.ui.brand.MinesIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.13 task_projects] 任务 page (mockup 1-任务列表), 项目详情 (3-项目详情) and the 立项登记 sheet (2-立项登记).
 * Frosted transparent glass (MinesCard), normal type (MT), high-contrast status words. No password field anywhere.
 */
object TaskRoutes {
    const val TASKS = "plus_task_projects"
    const val PROJECT = "plus_task_project/{id}"
    fun project(id: String) = "plus_task_project/" + android.net.Uri.encode(id)
    // [Mines-38.9.15 register_auto] a 一键注册 task opens 注册台账 (its progress card), not a conversation
    fun chat(sid: String) = if (sid.startsWith(com.openminis.app.plus.RegisterAuto.SID_PREFIX)) Routes3912.REGISTER else "chat/$sid"
}

private fun icT(n: String) = com.openminis.app.ui.brand.MinesIcons.named(n)!!

private val AVATAR = listOf(Color(0xFF6E45B8), Color(0xFF17706B), Color(0xFF2F62C2), Color(0xFFB0574F), Color(0xFF8A5A00))
private fun avatarColor(key: String) = AVATAR[(key.hashCode() and 0x7fffffff) % AVATAR.size]

/** status word colour, readable on frosted glass in light and dark */
@Composable
private fun stColor(st: TaskProgressCore.St?): Color {
    val d = mines().dark
    return when (st) {
        TaskProgressCore.St.WAITING -> if (d) Color(0xFFF2C14E) else Color(0xFF7A5200)
        TaskProgressCore.St.RUNNING -> if (d) Color(0xFF8DB4FF) else Color(0xFF1F55B5)
        TaskProgressCore.St.ERROR -> if (d) Color(0xFFFF8A80) else Color(0xFFB3261E)
        TaskProgressCore.St.DONE -> if (d) Color(0xFF81C995) else Color(0xFF256B2C)
        else -> mines().ink2
    }
}

private fun stWord(st: TaskProgressCore.St?) = when (st) { TaskProgressCore.St.ERROR -> "没成"; null -> "已停止"; else -> st.label }

@Composable
private fun WordPill(text: String, color: Color) {
    Text(text, fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun Avatar(letter: String, key: String, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(11.dp)).background(avatarColor(key)), contentAlignment = Alignment.Center) {
        Text(letter.take(1).uppercase(), color = Color.White, fontSize = MT.Body, fontWeight = FontWeight.Bold)
    }
}

private fun whenText(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return ""
    val min = (now - at) / 60_000
    if (min in 0..59) return if (min < 1) "刚刚" else "$min 分钟前"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val start = cal.timeInMillis
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
    return when {
        at >= start -> "今天 $hm"
        at >= start - ArchiveDay -> "昨天 $hm"
        else -> java.text.SimpleDateFormat("M/d", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}
private const val ArchiveDay = 86_400_000L

@Composable
private fun sessionsState(): List<ChatSessionEntity> {
    val ctx = LocalContext.current
    val flow = remember { com.openminis.app.data.db.AppDatabase.getInstance(ctx.applicationContext).chatDao().observeSessions() }
    val all by flow.collectAsState(initial = emptyList())
    return all.filter { !it.isChild }
}

// ───────────────────────────── 任务 ─────────────────────────────

@Composable
fun TasksScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    ChatOrganizer.init(ctx)
    val projects by TaskProjects.projects.collectAsState()
    val tasks by TaskProgress.tasks.collectAsState()
    ChatOrganizer.manual.collectAsState(); ChatOrganizer.restored.collectAsState()   // recompose on archive / restore
    val sessions = sessionsState()
    val byId = remember(sessions) { sessions.associateBy { it.id } }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var openArchive by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val projSids = projects.flatMap { p -> p.attempts.map { it.sid } }.toSet()
    // [Mines-38.9.14 chat_home] 聊天 itself and its auto tasks are not 小任务 (auto tasks are listed in AutoTasksSection)
    val chatHome = com.openminis.app.plus.ChatHome.enabled()
    val autoSids = if (chatHome) com.openminis.app.plus.ChatHome.tasks.collectAsState().value.map { it.sid }.toSet() else emptySet()
    val small = sessions.filter { it.id !in projSids && (!chatHome || (it.id != com.openminis.app.plus.ChatHome.mainSid && it.id !in autoSids)) }
    val (archived, live) = small.partition { ChatOrganizer.isArchived(it, now) }

    PlusPage("任务", onBack, actions = {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { searching = !searching; if (!searching) q = "" }
            .semantics { contentDescription = if (searching) "关闭搜索" else "搜索任务" }, contentAlignment = Alignment.Center) {
            MinesIcon(icT(if (searching) "x" else "search"), null, tint = m.ink, modifier = Modifier.size(22.dp))
        }
    }) {
        if (searching) {
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 48.dp).clip(shape)
                .background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, shape).padding(horizontal = 16.dp, vertical = 13.dp)) {
                if (q.isEmpty()) Text("搜任务和已归档（标题、内容）", fontSize = MT.Body, color = m.ink3)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink), cursorBrush = SolidColor(m.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索任务" })
            }
            val hits = sessions.filter { s -> q.isNotBlank() && TaskProjectsCore.matches(q, s.title, s.lastMessage, TaskProjects.projectOf(s.id)?.name) }.take(40)
            if (q.isNotBlank() && hits.isEmpty()) InfoLine("没找到。换个词试试。")
            if (hits.isNotEmpty()) MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                hits.forEachIndexed { i, s ->
                    if (i > 0) WarmDivider(start = 16.dp)
                    SmallRow(s, ChatOrganizer.isArchived(s, now), now, go)
                }
            }
            return@PlusPage
        }

        AutoTasksSection(go, projSids)   // [Mines-38.9.14 chat_home] 等你处理 / 进行中 / 已完成 (nothing while the flag is off)
        WarmGroupLabel("项目  ${projects.size} 个 · 不自动归档", "注册台账 ›") { go(Routes3912.REGISTER) }
        if (projects.isEmpty()) InfoLine("还没有项目。小任务变成注册 / 开通账号时，会问你要不要立项。")
        projects.sortedByDescending { p -> p.attempts.maxOfOrNull { tasks[it.sid]?.updatedAt ?: it.at } ?: p.createdAt }.forEach { p ->
            ProjectCard(p, tasks, go)
        }

        WarmGroupLabel("小任务  ${TaskProjectsCore.SMALL_DAYS} 天没聊自动归档")
        if (live.isEmpty()) InfoLine("没有进行中的小任务。")
        if (live.isNotEmpty()) MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            live.take(30).forEachIndexed { i, s ->
                if (i > 0) WarmDivider(start = 16.dp)
                SmallRow(s, false, now, go)
            }
        }

        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), onClick = { openArchive = !openArchive }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {
                contentDescription = "已归档 ${archived.size} 个，" + if (openArchive) "点一下收起" else "点一下展开" }, verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(icT("archive"), null, tint = m.ink2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("已归档 (${archived.size})", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink)
                Spacer(Modifier.width(10.dp))
                Text("可搜回、可恢复", fontSize = MT.Sub, color = m.ink2, modifier = Modifier.weight(1f), maxLines = 1)
                MinesIcon(icT(if (openArchive) "chevron-up" else "chevron-down"), null, tint = m.ink2, modifier = Modifier.size(20.dp))
            }
            if (openArchive) archived.take(60).forEach { s ->
                WarmDivider(start = 16.dp)
                SmallRow(s, true, now, go)
            }
        }

        // the old 任务 tab target (定时任务) stays one tap away
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), onClick = { go("scheduled_tasks") }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(icT("clock"), null, tint = m.ink2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("定时任务", fontSize = MT.Body, color = m.ink, modifier = Modifier.weight(1f))
                MinesIcon(icT("chevron-right"), null, tint = m.ink2, modifier = Modifier.size(18.dp))
            }
        }
        // [38.9.14.1] room for the dock: PlusPage's bottom padding (chatHomeDockPad) now covers it
    }
}

@Composable
private fun ProjectCard(p: TaskProjectsCore.Project, tasks: Map<String, TaskProgressCore.Task>, go: (String) -> Unit) {
    val m = mines()
    val latest = p.attempts.maxByOrNull { it.n }
    val st = latest?.let { tasks[it.sid]?.st }
    MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), onClick = { go(TaskRoutes.project(p.id)) }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(TaskProjectsCore.brand(p.site), p.site)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp)); WordPill("项目", m.primaryText)
                }
                val chip = when (p.accounts.size) { 0 -> "未关联账号"; 1 -> Inbox3912Core.maskMail(p.accounts[0].contact.ifBlank { p.accounts[0].name }); else -> "${p.accounts.size} 个账号" }
                Text("$chip · 共 ${p.attempts.size} 次", fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (latest != null) WordPill(stWord(st), stColor(st))
        }
        p.attempts.sortedByDescending { it.n }.take(3).forEach { a ->
            WarmDivider(start = 16.dp)
            val ctxA = androidx.compose.ui.platform.LocalContext.current
            // [38.9.14.3] a 聊天 task opens its own conversation (original message + record); other attempts their run as before
            AttemptRow(p, a, tasks[a.sid], compact = true) { com.openminis.app.plus.ChatHome.taskOf(a.sid)?.let { openTaskTalk(ctxA, it.id) } ?: go(TaskRoutes.chat(a.sid)) }
        }
    }
}

@Composable
private fun AttemptRow(p: TaskProjectsCore.Project, a: TaskProjectsCore.Attempt, t: TaskProgressCore.Task?, compact: Boolean, onClick: () -> Unit) {
    val m = mines()
    val line = t?.let { if (it.st == TaskProgressCore.St.ERROR && it.reason.isNotBlank()) "没成：" + it.reason else TaskProgressCore.oneLine(it) }.orEmpty().ifBlank { "点开继续" }
    val acct = if (p.accounts.size > 1 && a.acct > 0) "账号${a.acct} · " else ""
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { contentDescription = "第${a.n}次，$acct$line" }, verticalAlignment = Alignment.CenterVertically) {
        Text("第${a.n}次", fontSize = MT.Sub, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.width(52.dp))
        if (compact) { Box(Modifier.size(8.dp).clip(CircleShape).background(stColor(t?.st))); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(acct + line, fontSize = MT.Sub, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text(whenText(t?.updatedAt ?: a.at), fontSize = MT.Meta, color = m.ink2)
        }
        if (compact) Text(whenText(t?.updatedAt ?: a.at), fontSize = MT.Meta, color = m.ink2, maxLines = 1)
        else WordPill(stWord(t?.st), stColor(t?.st))
    }
}

@Composable
private fun SmallRow(s: ChatSessionEntity, archived: Boolean, now: Long, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val proj = TaskProjects.projectOf(s.id)
    val kind = TaskProjectsCore.kindOf(s.title.orEmpty(), s.lastMessage.orEmpty())
    val title = TaskProjectsCore.titleFor(TaskProjects.projects.value, s.id)
        ?: TaskProjectsCore.smallTitle(s.title.orEmpty().ifBlank { "新对话" }, kind)
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable {
            if (archived) ChatOrganizer.restore(ctx, s.id)
            go(TaskRoutes.chat(s.id))
        }.padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically) {
        MinesIcon(icT(when { proj != null -> "user"; kind == TaskProjectsCore.Kind.ARTICLE -> "file-text"; kind == TaskProjectsCore.Kind.QUESTION -> "info"; else -> "messages-square" }),
            null, tint = m.ink2, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText(s.updatedAt, now), fontSize = MT.Sub, color = m.ink2, maxLines = 1)
        }
        when {
            archived -> SmallPill("恢复") { ChatOrganizer.restore(ctx, s.id) }
            proj == null -> TaskProjectsCore.archiveHint(maxOf(s.updatedAt, ChatOrganizer.restored.value[s.id] ?: 0L), now,
                TaskProjectsCore.archiveDays(false, ChatOrganizer.settings.value.days) ?: TaskProjectsCore.SMALL_DAYS)
                ?.takeIf { s.pinnedAt == null }?.let { WordPill(it, m.ink2) }
        }
    }
}

// ───────────────────────────── 项目详情 ─────────────────────────────

@Composable
fun TaskProjectScreen(id: String, onBack: () -> Unit, go: (String) -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    TaskProjects.init(ctx)
    val projects by TaskProjects.projects.collectAsState()
    val tasks by TaskProgress.tasks.collectAsState()
    val p = projects.firstOrNull { it.id == id }
    if (p == null) { PlusPage("项目", onBack) { InfoLine("这个项目不在了。") }; return }
    var notes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(p.accounts.size) {
        notes = withContext(Dispatchers.IO) {
            runCatching { com.openminis.app.plus.Ledger.cands(ctx, p.site).associate { it.account.lowercase() to it.note } }.getOrDefault(emptyMap())
        }
    }
    val latest = p.attempts.maxByOrNull { it.n }
    val st = latest?.let { tasks[it.sid]?.st }
    val day = java.text.SimpleDateFormat("M/d", java.util.Locale.CHINA).format(java.util.Date(p.createdAt))
    PlusPage(p.name, onBack) {
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(TaskProjectsCore.brand(p.site), p.site, 44)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.site.ifBlank { p.name }, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp)); WordPill("项目", m.primaryText)
                    }
                    Text("立项 $day · ${p.accounts.size} 个账号 · ${p.attempts.size} 次尝试", fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
                if (latest != null) WordPill(stWord(st), stColor(st))
            }
            WarmDivider(start = 16.dp)
            Text("ⓘ  项目不自动归档。账号已写进注册台账。", fontSize = MT.Caption, color = m.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }

        WarmGroupLabel("关联账号  ${p.accounts.size}", "注册台账 ›") { go(Routes3912.REGISTER) }
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            p.accounts.forEach { a ->
                val note = notes[a.contact.lowercase()]
                val word = note?.let { Inbox3912Core.regStatus(it).zh } ?: "已登记"
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "账号${a.idx}，${a.contact}，$word" }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(avatarColor("a${a.idx}")), contentAlignment = Alignment.Center) {
                        Text("${a.idx}", color = Color.White, fontSize = MT.Body, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(Inbox3912Core.maskMail(a.contact.ifBlank { a.name }), fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(a.name.takeIf { it.isNotBlank() }?.let { "账号名 $it" }, a.date.takeIf { it.isNotBlank() }).joinToString(" · "),
                            fontSize = MT.Sub, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    WordPill(word, if (note != null && Inbox3912Core.regStatus(note) == Inbox3912Core.RegSt.DONE) stColor(TaskProgressCore.St.DONE) else m.ink2)
                }
                WarmDivider(start = 16.dp)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { adding = true }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(icT("plus"), null, tint = m.primaryText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("关联另一个账号", fontSize = MT.Body, fontWeight = FontWeight.Medium, color = m.primaryText)
            }
        }

        WarmGroupLabel("尝试记录")
        MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            p.attempts.sortedByDescending { it.n }.forEachIndexed { i, a ->
                if (i > 0) WarmDivider(start = 16.dp)
                AttemptRow(p, a, tasks[a.sid], compact = false) { go(TaskRoutes.chat(a.sid)) }
            }
        }

        if (latest != null) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("继续第${latest.n}次", Modifier.weight(1f)) { go(TaskRoutes.chat(latest.sid)) }
            PillButton("进入对话", Modifier.weight(1f), primary = false) { go(TaskRoutes.chat(latest.sid)) }
        }
    }
    if (adding) RegisterSheet(
        heading = "关联另一个账号", sub = "它会作为「账号 ${p.accounts.size + 1}」挂在 ${p.name} 下面。",
        site0 = p.site, siteEditable = false, contact0 = "", confirmText = "关联账号",
        onConfirm = { _, name, contact, date -> TaskProjects.addAccount(ctx, p.id, name, contact, date); adding = false },
        onCancel = { adding = false }, cancelText = "取消")
}

// ───────────────────────────── 立项登记 sheet ─────────────────────────────

/** 「这个任务已变成注册 → 立项」 (MainActivity root, next to ArchiveUndoHost) */
@Composable
fun TaskProjectSheetHost() {
    val pr by TaskProjects.proposal.collectAsState()
    val on by TaskProjects.on.collectAsState()
    val r = pr ?: return
    if (!on) return
    val ctx = LocalContext.current
    val projects by TaskProjects.projects.collectAsState()
    RegisterSheet(
        heading = "这个任务已变成${r.verb} → 立项", sub = null, verb = r.verb, projects = projects, sid = r.sid,
        site0 = r.site, siteEditable = true, contact0 = r.contact, confirmText = "登记并关联账号",
        onConfirm = { site, name, contact, date -> TaskProjects.confirm(ctx, r.sid, site, r.verb, name, contact, date) },
        onCancel = { TaskProjects.decline(r.sid) }, cancelText = "先不立项", chips = true,
        onDismiss = { TaskProjects.dismiss() })
}

@Composable
private fun RegisterSheet(
    heading: String, sub: String?, site0: String, siteEditable: Boolean, contact0: String, confirmText: String,
    onConfirm: (site: String, name: String, contact: String, date: String) -> Unit, onCancel: () -> Unit, cancelText: String,
    verb: String = "注册", projects: List<TaskProjectsCore.Project> = emptyList(), sid: String = "", chips: Boolean = false,
    onDismiss: () -> Unit = onCancel,
) {
    val m = mines()
    val ctx = LocalContext.current
    var site by remember(site0) { mutableStateOf(site0) }
    var name by remember { mutableStateOf("") }
    var contact by remember(contact0) { mutableStateOf(contact0) }
    var date by remember { mutableStateOf(TaskProjects.today()) }
    var pick by remember { mutableStateOf(false) }
    val known = remember {
        runCatching { com.openminis.app.plus.IdentityStore.current(ctx).filter { it.type == com.openminis.app.plus.IdentityCore.IdType.EMAIL || it.type == com.openminis.app.plus.IdentityCore.IdType.PHONE }.map { it.value }.distinct() }.getOrDefault(emptyList())
    }
    MinesSheet(onDismiss = onDismiss) {
        if (chips) Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WordPill("小任务", m.ink2); Text("  →  ", fontSize = MT.Body, color = m.ink); WordPill("项目", m.primaryText)
        }
        Text(heading, fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        val future = if (site.isBlank()) null else {
            val p = TaskProjectsCore.forSite(projects, site)
            val name0 = p?.name ?: TaskProjectsCore.projectName(site, verb)
            if (p != null && p.attempts.none { it.sid == sid }) name0 + " · 第" + ((p.attempts.maxOfOrNull { it.n } ?: 0) + 1) + "次" else name0
        }
        Text(sub ?: (if (future != null) "登记后它会叫「$future」，挂在项目里，不再自动归档。" else "填上站点，它就会用项目名命名，不再自动归档。"),
            fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        Field("globe", "站点", site, siteEditable) { site = it }
        Field("user", "账号名", name, true) { name = it }
        Field("mail", "手机/邮箱", contact, true, trailing = if (known.isEmpty()) null else ({ pick = !pick })) { contact = it }
        if (pick) MinesCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)) {
            known.take(8).forEach { v ->
                Text(v, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { contact = v; pick = false }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        Field("calendar", "日期", date, true) { date = it }
        val shape = RoundedCornerShape(14.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(shape).background(minesGlassFill()).padding(14.dp), verticalAlignment = Alignment.Top) {
            MinesIcon(icT("lock"), null, tint = m.ink2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("不在这里填密码。密码只存在你手机上（自动填充），这里只记账号。", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(confirmText, Modifier.fillMaxWidth(), enabled = site.isNotBlank()) { onConfirm(site, name, contact, date) }
            PillButton(cancelText, Modifier.fillMaxWidth(), primary = false) { onCancel() }
        }
    }
}

@Composable
private fun Field(icon: String, label: String, value: String, editable: Boolean, trailing: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val m = mines()
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = 52.dp).clip(shape)
        .background(minesGlassFill(strong = true)).border(1.dp, m.glassBorder, shape).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MinesIcon(icT(icon), null, tint = m.ink2, modifier = Modifier.size(20.dp))
        Text(label, fontSize = MT.Caption, color = m.ink2, maxLines = 1, modifier = Modifier.padding(start = 10.dp).width(72.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (editable) BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink),
                cursorBrush = SolidColor(m.primary), modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
            else Text(value, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = trailing).semantics { contentDescription = "选一个$label" },
            contentAlignment = Alignment.Center) { MinesIcon(icT("chevron-down"), null, tint = m.ink2, modifier = Modifier.size(18.dp)) }
    }
}

// ───────────────────────────── 设置 → 实验功能 ─────────────────────────────

@Composable
fun TaskProjectsFlagRow() {
    val ctx = LocalContext.current
    TaskProjects.init(ctx)
    val on by TaskProjects.on.collectAsState()
    val m = mines()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { TaskProjects.setOn(ctx, !on) }.padding(horizontal = 16.dp, vertical = 10.dp)
        .semantics { contentDescription = "任务项目，" + if (on) "已打开" else "已关闭" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("任务项目", fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.Medium, color = m.ink)
            Text("任务按项目命名（第 2 次 / 账号 2），小任务 2 天自动归档，注册时立项", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MinesSwitch(on, "任务项目") { TaskProjects.setOn(ctx, it) }
    }
}

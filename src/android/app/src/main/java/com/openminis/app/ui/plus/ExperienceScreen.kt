package com.openminis.app.ui.plus

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.ExperienceCore
import com.openminis.app.plus.Experience
import com.openminis.app.plus.WorkflowLog
import com.openminis.app.plus.WorkflowLogCore
import com.openminis.app.plus.XpTitles
import com.openminis.app.plus.XpViewCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9] 设置 › 经验; [Mines-38.9.11 a] rebuilt as readable cards (user 2026-10-20 08:14): three sections with counts
 * — 做法 / 教训 / 常用工作流 (workflows done ≥ 2 times or saved as a skill; every scheduled task is one entry) — search,
 * filter chips, card = Chinese title · one line · 成功/没成功 · tool chips · 用过 N 次 / 成功率 / 版本. Tap → detail page
 * (step timeline, 要注意, 编辑 / 删除). Stored entries are cleaned once (codes removed, duplicates merged) on first open.
 */
@Composable
fun ExperienceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Experience.init(ctx); WorkflowLog.init(ctx); XpTitles.init(ctx); 0 }
    val list by Experience.list.collectAsState()
    val runs by WorkflowLog.runs.collectAsState()
    val titles by XpTitles.cache.collectAsState()
    var migrated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { XpTitles.migrate(ctx) } }
        migrated = true
    }
    LaunchedEffect(migrated, list.size) { if (migrated) XpTitles.summarizeSome(ctx, emptyList(), list) }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var openXp by remember { mutableStateOf<String?>(null) }
    var openFlow by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ExperienceCore.Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<ExperienceCore.Entry?>(null) }
    var toast by remember { mutableStateOf("") }

    val cards = remember(list, titles) { list.associate { it.id to XpTitles.card(it) } }
    // 常用工作流: grouping compares every title pair → off the main thread
    var common by remember { mutableStateOf<List<FlowGroup>>(emptyList()) }
    LaunchedEffect(runs, titles) { common = withContext(Dispatchers.Default) { commonFlows(runs) } }

    // ---------- detail pages ----------
    val flow = openFlow?.let { k -> common.firstOrNull { it.key == k } }
    if (flow != null) {
        BackHandler { openFlow = null }
        val r = flow.example
        WorkflowRunDetail(r, XpTitles.card(r).copy(title = flow.title), onBack = { openFlow = null }, onOpenChat = null,
            onSaveSkill = { toast = WorkflowLog.saveAsSkill(ctx, r.id) }, onDelete = null, toast = toast,
            stats = listOfNotNull("用过 ${flow.runs.size} 次", xpRate(flow.ok, flow.runs.size).ifBlank { null }, "最近 " + xpWhen(flow.last)))
        return
    }
    val xp = openXp?.let { id -> list.firstOrNull { it.id == id } }
    if (xp != null && editing == null && confirmDel == null) {
        BackHandler { openXp = null }
        ExperienceDetail(xp, cards[xp.id] ?: XpTitles.card(xp), onBack = { openXp = null }, onEdit = { editing = xp }, onDelete = { confirmDel = xp })
        return
    }

    // ---------- list ----------
    val q = query.trim()
    fun hit(e: ExperienceCore.Entry): Boolean {
        if (q.isEmpty()) return true
        val c = cards[e.id] ?: return false
        return (c.title + " " + c.summary + " " + XpViewCore.clean(e.pattern) + " " + XpViewCore.scrub(e.steps) + " " + e.tools.joinToString(" ") { XpViewCore.tool(it).zh })
            .contains(q, ignoreCase = true)
    }
    val howto = list.filter { it.kind == ExperienceCore.Kind.HOWTO && hit(it) }
    val lessons = list.filter { it.kind == ExperienceCore.Kind.LESSON && hit(it) }
    val flows = common.filter { q.isEmpty() || (it.title + " " + it.summary).contains(q, ignoreCase = true) }

    PlusPage("经验", onBack, subtitle = "Mines 自己记下的做法和教训；换哪个模型都会先看这里") {
        AcctSearch(query, "搜索经验") { query = it }
        Spacer(Modifier.height(6.dp))
        XpFilterChips(listOf("全部", "做法", "教训", "常用工作流"), filter) { filter = it }
        if (toast.isNotBlank()) InfoLine(toast)
        if (list.isEmpty() && common.isEmpty()) InfoLine("还没有经验。Mines 做完一件多步骤的事、或者同一个错误碰到两次，会自己记一条。")
        else if (howto.isEmpty() && lessons.isEmpty() && flows.isEmpty() && q.isNotEmpty()) InfoLine("没找到「$q」")

        if ((filter == "全部" || filter == "做法") && howto.isNotEmpty()) PlusSection("做法（${howto.size}）") {
            howto.forEach { e -> XpEntryRow(e, cards[e.id] ?: XpTitles.card(e)) { toast = ""; openXp = e.id } }
        }
        if ((filter == "全部" || filter == "教训") && lessons.isNotEmpty()) PlusSection("教训（${lessons.size}）") {
            lessons.forEach { e -> XpEntryRow(e, cards[e.id] ?: XpTitles.card(e)) { toast = ""; openXp = e.id } }
        }
        if ((filter == "全部" || filter == "常用工作流") && flows.isNotEmpty()) PlusSection("常用工作流（${flows.size}）") {
            flows.forEach { g ->
                XpCardRow(
                    leadIcon = if (g.scheduled) "clock" else "history", tone = Tone.TEAL,
                    title = g.title, summary = g.summary, tools = g.tools,
                    meta = listOfNotNull("用过 ${g.runs.size} 次", xpRate(g.ok, g.runs.size).ifBlank { null }, if (g.skill) "已存成技能" else null),
                    outcome = outcomeOf(g.runs.first()), time = xpWhen(g.last),
                    onClick = { toast = ""; openFlow = g.key },
                )
            }
        }
        if (filter != "全部" && when (filter) { "做法" -> howto.isEmpty(); "教训" -> lessons.isEmpty(); else -> flows.isEmpty() }) InfoLine("这里还没有")
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            PillButton("新加一条", Modifier.fillMaxWidth(), primary = false) { adding = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    val e = editing
    if (e != null || adding) {
        var pat by remember(e) { mutableStateOf(e?.let { XpViewCore.scrub(XpViewCore.clean(it.pattern)).take(60) }.orEmpty()) }
        var steps by remember(e) { mutableStateOf(e?.steps?.let { XpViewCore.scrub(it) }.orEmpty()) }
        var pit by remember(e) { mutableStateOf(e?.pitfalls?.let { XpViewCore.scrub(it) }.orEmpty()) }
        var lesson by remember(e) { mutableStateOf(e?.kind == ExperienceCore.Kind.LESSON) }
        MinesDialog(if (e == null) "新加一条经验" else "改${e.kind.label} · 第 ${e.version} 版", { editing = null; adding = false }, confirm = "保存",
            confirmEnabled = pat.isNotBlank() && steps.isNotBlank(), onConfirm = {
                if (e == null) Experience.add(ctx, if (lesson) ExperienceCore.Kind.LESSON else ExperienceCore.Kind.HOWTO, pat, steps, pit)
                else Experience.edit(ctx, e.id, pat, steps, pit)
                editing = null; adding = false; toast = "已保存"
            }, dismiss = "取消", onDismissButton = { editing = null; adding = false }) {
            if (e == null) Row {
                PillButton("做法", primary = !lesson) { lesson = false }
                Spacer(Modifier.padding(4.dp))
                PillButton("教训", primary = lesson) { lesson = true }
            }
            OutlinedTextField(pat, { pat = it.take(60) }, label = { Text("什么事") }, singleLine = true)
            OutlinedTextField(steps, { steps = it.take(1200) }, label = { Text(if (lesson) "下次改成这样做" else "怎么做（一行一步）") }, minLines = 3)
            OutlinedTextField(pit, { pit = it.take(400) }, label = { Text(if (lesson) "哪里失败了、为什么" else "要注意的坑（可不填）") }, minLines = 2)
        }
    }
    confirmDel?.let { d ->
        MinesDialog("删掉这条经验？", { confirmDel = null }, confirm = "删除", danger = true, onConfirm = {
            Experience.delete(ctx, d.id); confirmDel = null; openXp = null; toast = "已删除"
        }) {
            Text((cards[d.id] ?: XpTitles.card(d)).title, fontSize = MT.Body, color = mines().ink)
        }
    }
}

@Composable
private fun XpEntryRow(e: ExperienceCore.Entry, c: XpTitles.Card, onClick: () -> Unit) {
    val lesson = e.kind == ExperienceCore.Kind.LESSON
    XpCardRow(
        leadIcon = if (lesson) "shield-alert" else "list-checks", tone = if (lesson) Tone.WARNING else Tone.SUCCESS,
        title = c.title, summary = c.summary,
        tools = XpViewCore.chips(e.tools.ifEmpty { XpTitles.stepsOf(e).map { it.first } }),
        meta = listOfNotNull(if (e.uses > 0) "用过 ${e.uses} 次" else null, "第 ${e.version} 版", if (e.source == "手动") "自己加的" else null),
        outcome = if (lesson) "失败" to false else "成功" to true,
        time = xpWhen(e.updatedAt),
        onClick = onClick,
    )
}

@Composable
private fun ExperienceDetail(e: ExperienceCore.Entry, c: XpTitles.Card, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val m = mines()
    val lesson = e.kind == ExperienceCore.Kind.LESSON
    PlusPage(c.title, onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (lesson) "教训" else "做法", !lesson)
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull("第 ${e.version} 版", if (e.uses > 0) "用过 ${e.uses} 次" else null, xpWhen(e.updatedAt).ifBlank { null }).joinToString(" · "),
                fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 1)
        }
        if (c.summary.isNotBlank()) Text(c.summary, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp))
        val steps = XpTitles.stepsOf(e)
        if (steps.isNotEmpty()) PlusSection(if (lesson) "下次这样做" else "怎么做（${steps.size} 步）") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StepTimeline(steps.map { (t, l) -> Triple(t, XpViewCore.stepLine(t, l), true) })
            }
        }
        val pits = e.pitfalls.lines().filter { it.isNotBlank() }.map { l ->
            val t = l.substringBefore(':', "").trim().takeIf { it.matches(Regex("[a-z_\\-]{3,40}")) }.orEmpty()
            t to l
        }
        if (pits.isNotEmpty()) PlusSection(if (lesson) "哪里没成功" else "要注意") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StepTimeline(pits.map { (t, l) -> Triple(t, XpViewCore.stepLine(t, l), false) })
            }
        }
        if (e.tools.isNotEmpty()) PlusSection("用到的工具") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { ToolChips(XpViewCore.chips(e.tools, 8)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("编辑", Modifier.weight(1f), primary = false) { onEdit() }
            PillButton("删除", Modifier.weight(1f), primary = false) { onDelete() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** a workflow done more than once: one scheduled task, or near-duplicate runs by title */
internal data class FlowGroup(
    val key: String, val title: String, val summary: String, val runs: List<WorkflowLogCore.Run>, val ok: Int,
    val last: Long, val tools: List<XpViewCore.Tool>, val skill: Boolean, val scheduled: Boolean, val example: WorkflowLogCore.Run,
)

internal fun commonFlows(runs: List<WorkflowLogCore.Run>): List<FlowGroup> {
    val (sch, other) = runs.partition { XpViewCore.scheduled(it.task) != null }
    val g1 = sch.groupBy { XpViewCore.scheduled(it.task)!!.id }.values.map { true to it }
    val titled = other.map { it to XpTitles.card(it) }
    val g2 = XpViewCore.groupSimilar(titled) { it.second.title }.map { g -> false to g.map { it.first } }
    return (g1 + g2).filter { (_, l) -> l.size >= 2 || l.any { it.skillId.isNotBlank() } }.map { (isSch, l0) ->
        val l = l0.sortedByDescending { it.endedAt }
        val ex = l.firstOrNull { it.ok } ?: l.first()
        val c = XpTitles.card(ex)
        val title = if (isSch) "定时任务：" + XpViewCore.scheduled(ex.task)!!.label.take(14) else c.title
        FlowGroup(if (isSch) "s:" + XpViewCore.scheduled(ex.task)!!.id else "w:" + l.last().id, title, c.summary, l, l.count { it.ok }, l.first().endedAt,
            XpViewCore.chips(l.flatMap { r -> r.steps.map { it.tool } }), l.any { it.skillId.isNotBlank() }, isSch, ex)
    }.sortedWith(compareByDescending<FlowGroup> { it.runs.size }.thenByDescending { it.last }).take(40)
}

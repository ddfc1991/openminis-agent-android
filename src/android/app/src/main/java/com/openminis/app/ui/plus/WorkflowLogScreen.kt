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
import com.openminis.app.plus.WorkflowLog
import com.openminis.app.plus.WorkflowLogCore
import com.openminis.app.plus.XpTitles
import com.openminis.app.plus.XpViewCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.8] 设置 › 工作流记录; [Mines-38.9.11 a] rebuilt as readable cards (user 2026-10-20 08:14 screenshots: raw
 * user messages / `<scheduled_task …>` / `<system-reminder>` as titles, 「shell_execute: …」 as subtitles, codes kept):
 * search + filter chips (全部 / 成功 / 失败 / 定时任务); card = Chinese title · one line · 成功/失败 · tool chips with
 * icons · 步数 / 时长; every scheduled task is ONE collapsible row (runs inside); tap → detail page with a step timeline.
 * Titles: heuristic at once, the user's own model writes better ones in the background ([XpTitles]). The stored records
 * are cleaned once on first open (codes / keys masked, trivial / duplicate experiences removed).
 */
@Composable
fun WorkflowLogScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    remember { WorkflowLog.init(ctx); XpTitles.init(ctx); 0 }
    val runs by WorkflowLog.runs.collectAsState()
    val titles by XpTitles.cache.collectAsState()
    var migrated by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { XpTitles.migrate(ctx) } }
        migrated = true
    }
    LaunchedEffect(migrated, runs.size) {
        // scheduled runs share their task's label → no model call per run
        if (migrated) XpTitles.summarizeSome(ctx, runs.filter { XpViewCore.scheduled(it.task) == null }, emptyList())
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var open by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var toast by remember { mutableStateOf("") }

    val items = remember(runs, titles) { runs.map { r -> WfItem(r, XpTitles.card(r), XpViewCore.scheduled(r.task)) } }

    val detail = open?.let { id -> items.firstOrNull { it.run.id == id } }
    if (detail != null) {
        BackHandler { open = null }
        WorkflowRunDetail(detail.run, detail.card, onBack = { open = null },
            onOpenChat = { sid -> open = null; onOpenChat(sid) },
            onSaveSkill = { toast = WorkflowLog.saveAsSkill(ctx, detail.run.id) },
            onDelete = { WorkflowLog.delete(ctx, detail.run.id); open = null; toast = "已删除" },
            toast = toast)
        return
    }

    val q = query.trim()
    val shown = items.filter { it.matches(q) }.filter {
        when (filter) { "成功" -> it.run.ok; "失败" -> !it.run.ok; else -> true }
    }
    val schedGroups = shown.filter { it.sched != null }.groupBy { it.sched!!.id }.values.toList()
        .sortedByDescending { g -> g.maxOf { it.run.endedAt } }
    val plain = shown.filter { it.sched == null }

    PlusPage("工作流记录", onBack, subtitle = "Mines 用工具做完的每件事；点开看每一步") {
        AcctSearch(query, "搜索做过的事") { query = it }
        Spacer(Modifier.height(6.dp))
        XpFilterChips(listOf("全部", "成功", "失败", "定时任务"), filter) { filter = it }
        if (toast.isNotBlank()) InfoLine(toast)
        if (runs.isEmpty()) InfoLine("还没有记录。Mines 用工具做完一件事，就会记一条。")
        else if (schedGroups.isEmpty() && (plain.isEmpty() || filter == "定时任务")) InfoLine(if (q.isNotEmpty()) "没找到「$q」" else "这里没有记录")

        if (schedGroups.isNotEmpty()) PlusSection("定时任务（${schedGroups.size}）") {
            schedGroups.forEach { g ->
                val newest = g.maxByOrNull { it.run.endedAt }!!
                val sch = newest.sched!!
                val key = sch.id
                val ok = g.count { it.run.ok }
                val isOpen = key in expanded
                XpCardRow(
                    leadIcon = "clock", tone = Tone.INFO,
                    title = "定时任务：" + sch.label.take(16),
                    summary = "共 ${g.size} 次 · 最近一次：" + newest.card.summary,
                    tools = XpViewCore.chips(g.flatMap { i -> i.run.steps.map { it.tool } }),
                    meta = listOfNotNull(xpRate(ok, g.size).ifBlank { null }, if (isOpen) "收起" else "展开 ${g.size} 次"),
                    outcome = outcomeOf(newest.run), time = xpWhen(newest.run.endedAt),
                    onClick = { expanded = if (isOpen) expanded - key else expanded + key },
                )
                if (isOpen) g.sortedByDescending { it.run.endedAt }.take(30).forEach { i ->
                    Box(Modifier.padding(start = 36.dp)) {
                        PlusRow(xpIcon(if (i.run.ok) "circle-check" else "shield-alert"),
                            xpDay(i.run.endedAt) + " " + xpHm(i.run.endedAt) + " · " + (outcomeOf(i.run).first),
                            i.card.summary, value = xpDur(i.run.durationMs), onClick = { open = i.run.id })
                    }
                }
            }
        }

        if (filter != "定时任务") plain.groupBy { xpDay(it.run.endedAt) }.forEach { (d, l) ->
            PlusSection(d + "（${l.size}）") {
                l.forEach { i ->
                    XpCardRow(
                        leadIcon = if (i.run.ok) "list-checks" else "shield-alert",
                        tone = if (i.run.ok) Tone.SUCCESS else if (i.run.status == "stopped") Tone.NEUTRAL else Tone.DANGER,
                        title = i.card.title, summary = i.card.summary,
                        tools = XpViewCore.chips(i.run.steps.map { it.tool }),
                        meta = listOfNotNull("${i.run.steps.size} 步", xpDur(i.run.durationMs), if (i.run.skillId.isNotBlank()) "已存成技能" else null),
                        outcome = outcomeOf(i.run), time = xpHm(i.run.endedAt),
                        onClick = { toast = ""; open = i.run.id },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal data class WfItem(val run: WorkflowLogCore.Run, val card: XpTitles.Card, val sched: XpViewCore.Sched?) {
    fun matches(q: String): Boolean {
        if (q.isEmpty()) return true
        val hay = card.title + " " + card.summary + " " + XpViewCore.clean(run.task) + " " +
            run.steps.joinToString(" ") { XpViewCore.tool(it.tool).zh + " " + XpViewCore.stepLine(it.tool, it.brief) }
        return hay.contains(q, ignoreCase = true)
    }
}

internal fun outcomeOf(r: WorkflowLogCore.Run): Pair<String, Boolean?> = when (r.status) {
    "done" -> "成功" to true
    "stopped" -> "已停止" to null
    else -> "失败" to false
}

/** One run as a page: outcome · time · summary, the step timeline, skills used, result, actions. Shared with 经验 › 常用工作流. */
@Composable
internal fun WorkflowRunDetail(
    r: WorkflowLogCore.Run,
    card: XpTitles.Card,
    onBack: () -> Unit,
    onOpenChat: ((String) -> Unit)?,
    onSaveSkill: () -> Unit,
    onDelete: (() -> Unit)?,
    toast: String = "",
    stats: List<String> = emptyList(),
) {
    val m = mines()
    var confirmDel by remember { mutableStateOf(false) }
    PlusPage(card.title, onBack) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val o = outcomeOf(r)
            StatusPill(o.first, o.second)
            Spacer(Modifier.width(8.dp))
            Text(xpDay(r.endedAt) + " " + xpHm(r.startedAt) + "–" + xpHm(r.endedAt) + " · " + xpDur(r.durationMs) + " · ${r.steps.size} 步",
                fontSize = MT.Meta, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 1)
        }
        if (card.summary.isNotBlank()) Text(card.summary, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp))
        if (stats.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.forEach { MetaChip(it) }
        }
        if (toast.isNotBlank()) InfoLine(toast)
        if (r.steps.isNotEmpty()) PlusSection("每一步（${r.steps.size}）") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StepTimeline(r.steps.map { Triple(it.tool, XpViewCore.stepLine(it.tool, it.brief), it.ok) },
                    r.steps.map { XpViewCore.scrub(XpViewCore.clean(it.error)).take(120) })
            }
        }
        val chain = WorkflowLogCore.skillChain(r.steps)
        if (chain.isNotEmpty()) PlusSection("用到的技能") {
            Text(chain.joinToString(" → "), fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        val result = XpViewCore.scrub(XpViewCore.clean(r.result))
        if (result.isNotBlank()) PlusSection("结果") {
            Text(result.take(600), fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.skillId.isBlank()) PillButton("存成技能", Modifier.fillMaxWidth()) { onSaveSkill() }
            else InfoLine("已存成技能，类似的事 Mines 会直接照着做")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (onOpenChat != null && r.sid.isNotBlank()) PillButton("打开对话", Modifier.weight(1f), primary = false) { onOpenChat(r.sid) }
                if (onDelete != null) PillButton("删除", Modifier.weight(1f), primary = false) { confirmDel = true }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDel && onDelete != null) MinesDialog("删掉这条记录？", { confirmDel = false }, confirm = "删除", danger = true,
        onConfirm = { confirmDel = false; onDelete() }) {
        Text(card.title, fontSize = MT.Body, color = m.ink)
    }
}

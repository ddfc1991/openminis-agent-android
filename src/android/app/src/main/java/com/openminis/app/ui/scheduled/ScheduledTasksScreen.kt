package com.openminis.app.ui.scheduled

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.theme.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.openminis.app.ui.theme.TopAppBar
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.SettingsSwitch
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-android-scheduled-tasks-design / T-android-scheduled-tasks-run-records]
 * List + manage scheduled tasks. Entry point from SessionListScreen's
 * TopAppBar; tap a row to edit, FAB to create, switch to enable/disable.
 * Long-press opens a menu: Edit / Run records / Delete (with confirm).
 * The row no longer shows a result preview — execution history lives in
 * the Run records screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditTask: (taskId: String?) -> Unit,
    onViewRuns: (taskId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScheduledTasksViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ScheduledTasksViewModel.factory(context),
    )
    val tasks by vm.tasks.collectAsState()
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.scheduled_tasks_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = com.openminis.app.ui.plus.MT.PageTitle,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditTask(null) }, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scheduled_task_new))
            }
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(padding)
            return@Scaffold
        }
        // [Mines-38.1] tasks in one solid porcelain card; finished one-shots greyed in a collapsible 「已完成」 group
        val active = tasks.filter { !isFinished(it) }
        val done = tasks.filter { isFinished(it) }
        var showDone by remember { mutableStateOf(active.size < 5) }  // [Mines-38.5] few tasks: show the finished ones instead of a blank page
        val mc = com.openminis.app.ui.plus.mines()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            if (active.isNotEmpty()) item(key = "card_active") {
                com.openminis.app.ui.plus.MinesCard(Modifier.fillMaxWidth()) {
                    active.forEachIndexed { i, task ->
                        ScheduledTaskRow(task = task, onClick = { onEditTask(task.id) }, onToggle = { vm.setEnabled(task.id, it) },
                            onEdit = { onEditTask(task.id) }, onViewRuns = { onViewRuns(task.id) }, onDelete = { pendingDelete = task })
                        if (i < active.size - 1) com.openminis.app.ui.plus.WarmDivider(start = 62.dp)
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "done_header") {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(14.dp)).clickable { showDone = !showDone }
                        .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已完成 ${done.size}", fontSize = com.openminis.app.ui.plus.MT.Section, fontWeight = FontWeight.SemiBold, color = mc.ink2, modifier = Modifier.weight(1f))
                        Text(if (showDone) "收起" else "展开", fontSize = com.openminis.app.ui.plus.MT.Sub, color = mc.primaryText)
                    }
                }
                if (showDone) item(key = "card_done") {
                    com.openminis.app.ui.plus.MinesCard(Modifier.fillMaxWidth().alphaCompat(0.62f)) {
                        done.forEachIndexed { i, task ->
                            ScheduledTaskRow(task = task, onClick = { onViewRuns(task.id) }, onToggle = { vm.setEnabled(task.id, it) },
                                onEdit = { onEditTask(task.id) }, onViewRuns = { onViewRuns(task.id) }, onDelete = { pendingDelete = task }, finished = true)
                            if (i < done.size - 1) com.openminis.app.ui.plus.WarmDivider(start = 62.dp)
                        }
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        com.openminis.app.ui.glass.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_task_delete_body, toDelete.label)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.delete(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.scheduled_tasks_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduledTaskRow(
    task: ScheduledTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onViewRuns: () -> Unit,
    onDelete: () -> Unit,
    finished: Boolean = false,
) {
    var menuExpanded by remember(task.id) { mutableStateOf(false) }
    val mc = com.openminis.app.ui.plus.mines()
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (task.enabled && !finished) mc.container else mc.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled && !finished) mc.icon else mc.ink3,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = zhTaskName(task.label).ifBlank { task.prompt.take(40) },
                    fontWeight = FontWeight.Medium,
                    fontSize = com.openminis.app.ui.plus.MT.Body,
                    color = mc.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // [Mines-38.5] two lines instead of a mid-sentence 「…」
                    text = formatScheduleSummary(task).trim(),
                    color = mc.ink2,
                    fontSize = com.openminis.app.ui.plus.MT.Sub,
                    lineHeight = com.openminis.app.ui.plus.MT.SubLH,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (!finished) SettingsSwitch(checked = task.enabled, onCheckedChange = onToggle)
            else Text("已结束", fontSize = com.openminis.app.ui.plus.MT.Meta, color = mc.ink3)
        }

        // [T-android-scheduled-tasks-run-records] Long-press menu: Edit / Run
        // records / Delete (delete is confirmed by the caller's dialog).
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_edit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menuExpanded = false; onEdit() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.scheduled_task_menu_runs)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = { menuExpanded = false; onViewRuns() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.scheduled_task_menu_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}

/** [Mines-38.1] 「3 分钟」 / 「1 小时 20 分」 */
internal fun zhDuration(sec: Long): String {
    if (sec <= 0) return "0 秒"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return buildString {
        if (h > 0) append("$h 小时")
        if (m > 0) { if (isNotEmpty()) append(' '); append("$m 分钟") }
        if (s > 0 && h == 0L) { if (isNotEmpty()) append(' '); append("$s 秒") }
    }
}

/** [Mines-38.1] 「今天 07:30」 / 「明天 07:30」 / 「10月17日 07:30」 */
internal fun zhWhen(ms: Long): String {
    val now = java.util.Calendar.getInstance(); val t = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ms))
    val dayDiff = run {
        val a = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val b = (t.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }
    return when (dayDiff) {
        0 -> "今天 $hm"; 1 -> "明天 $hm"; 2 -> "后天 $hm"; -1 -> "昨天 $hm"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))
    }
}

/** [Mines-38.1] internal ids like daily-briefing → 每日简报 */
internal fun zhTaskName(raw: String): String {
    val t = raw.trim()
    if (!Regex("^[A-Za-z0-9_\\- ]+$").matches(t) || t.isBlank()) return t
    val known = mapOf("daily-briefing" to "每日简报", "morning-briefing" to "早间简报", "weekly-report" to "每周报告", "daily-digest" to "每日摘要",
        "key-expiry" to "密钥到期提醒", "account-patrol" to "账号巡检", "backup" to "备份")
    known[t.lowercase()]?.let { return it }
    val words = mapOf("daily" to "每日", "weekly" to "每周", "monthly" to "每月", "morning" to "早间", "evening" to "晚间", "night" to "夜间",
        "briefing" to "简报", "brief" to "简报", "report" to "报告", "digest" to "摘要", "news" to "新闻", "check" to "检查", "reminder" to "提醒",
        "remind" to "提醒", "backup" to "备份", "sync" to "同步", "price" to "价格", "crypto" to "币价", "market" to "行情", "summary" to "总结",
        "task" to "任务", "update" to "更新", "patrol" to "巡检", "server" to "服务器", "health" to "健康检查", "email" to "邮件", "mail" to "邮件")
    val parts = t.lowercase().split('-', '_', ' ').filter { it.isNotBlank() }
    return if (parts.all { it in words }) parts.joinToString("") { words.getValue(it) } else t
}

/** [Mines-38.1] a one-shot that already ran (or whose time passed): shown greyed under 「已完成」 */
internal fun isFinished(task: ScheduledTask): Boolean {
    val oneShot = task.repeatMode == ScheduledRepeatMode.ONCE || task.triggerKind == com.openminis.app.scheduled.ScheduledTriggerKind.AFTER ||
        (task.triggerKind == com.openminis.app.scheduled.ScheduledTriggerKind.INTERVAL && task.remainingFires == 0)
    return oneShot && task.nextTriggerMs() == null && (!task.enabled || task.lastFiredAt != null)
}

internal fun formatScheduleSummary(task: ScheduledTask): String {
    // [Mines-38.1] Chinese: 「3 分钟后一次」「每天 07:30 · 下次 明天 07:30」「一次 · 明天 09:00」
    val next = task.nextTriggerMs()
    val nextSuffix = next?.let { " · 下次 ${zhWhen(it)}" } ?: ""
    when (task.triggerKind) {
        com.openminis.app.scheduled.ScheduledTriggerKind.AFTER ->
            return if (next != null) "${zhDuration(task.delaySec ?: 0L)}后一次 · ${zhWhen(next)}" else "${zhDuration(task.delaySec ?: 0L)}后一次" +
                (task.lastFiredAt?.let { " · ${zhWhen(it)} 已运行" } ?: "")
        com.openminis.app.scheduled.ScheduledTriggerKind.INTERVAL -> {
            val every = zhDuration(task.intervalSec ?: 0L)
            val count = task.maxFires?.let { " · 共 $it 次（还剩 ${task.remainingFires}）" } ?: ""
            return "每 $every$count$nextSuffix"
        }
        com.openminis.app.scheduled.ScheduledTriggerKind.ON_COMPLETION ->
            return "等任务 ${task.onCompletionOf.orEmpty().take(8)} 完成后"
        com.openminis.app.scheduled.ScheduledTriggerKind.CALENDAR -> Unit
    }
    val time = "%02d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
    return when (task.repeatMode) {
        ScheduledRepeatMode.ONCE -> if (next != null) "一次 · ${zhWhen(next)}" else "一次 · $time" + (task.lastFiredAt?.let { " · ${zhWhen(it)} 已运行" } ?: "")
        ScheduledRepeatMode.DAILY -> "每天 $time$nextSuffix"
        ScheduledRepeatMode.WEEKDAYS -> "工作日 $time$nextSuffix"
        ScheduledRepeatMode.CUSTOM -> {
            val days = task.customDays.sorted().joinToString("、") { dowShort(it) }
            (if (days.isBlank()) "自定义 $time" else "每$days $time") + nextSuffix
        }
    }
}

private fun dowShort(dow: Int): String = when (dow) {
    java.util.Calendar.SUNDAY -> "周日"
    java.util.Calendar.MONDAY -> "周一"
    java.util.Calendar.TUESDAY -> "周二"
    java.util.Calendar.WEDNESDAY -> "周三"
    java.util.Calendar.THURSDAY -> "周四"
    java.util.Calendar.FRIDAY -> "周五"
    java.util.Calendar.SATURDAY -> "周六"
    else -> ""
}

private fun Modifier.alphaCompat(a: Float): Modifier = this.then(Modifier.graphicsLayer { alpha = a })

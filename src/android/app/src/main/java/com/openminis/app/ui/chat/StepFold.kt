package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.components.DecorativeSpinner

/**
 * [Mines-38.9.14.2] user 2026-10-25 20:57 「最主要是这个对话要会伸缩进一个任务项，就是聚合」: all 深度思考 + tool steps of one reply
 * fold into ONE task row (spinner / ✓ + the current step + 「N 步 · 用时」 + ›). Collapsed by default, only this row updates
 * while it runs, tap opens the full step list, tap again closes it, it closes by itself when the reply finishes. The
 * final answer text stays outside. Every chat (聊天主页 and task conversations). Rows are filtered at render time only:
 * the flattened list, its keys and its builder are unchanged.
 */
internal object StepFold {
    /** a reply with fewer steps than this keeps its rows as they are */
    const val MIN_STEPS = 2

    data class Group(
        val id: String,             // the assistant message id
        val anchorKey: String,      // the first step row: the task row is drawn in its place
        val memberKeys: Set<String>,
        val blocks: List<AssistantBlock>,
        val running: Boolean,
    ) {
        val blockIds: Set<String> get() = blocks.mapTo(HashSet()) { it.id }
    }

    private fun active(s: ToolBlockStatus?) = s == ToolBlockStatus.RUNNING || s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING

    /** anchor key → group, for every reply with ≥ [MIN_STEPS] thinking / tool rows */
    fun groups(items: List<FlatChatItem>): Map<String, Group> {
        val byMsg = LinkedHashMap<String, MutableList<FlatChatItem>>()
        for (it in items) {
            val mid = when (it) {
                is FlatChatItem.AssistantThinking -> it.messageId
                is FlatChatItem.AssistantToolUse -> it.messageId
                else -> null
            } ?: continue
            byMsg.getOrPut(mid) { ArrayList() } += it
        }
        val out = LinkedHashMap<String, Group>()
        for ((mid, rows) in byMsg) {
            if (rows.size < MIN_STEPS) continue
            val blocks = rows.map { r -> if (r is FlatChatItem.AssistantThinking) r.block else (r as FlatChatItem.AssistantToolUse).block }
            val running = rows.any { r ->
                when (r) {
                    is FlatChatItem.AssistantThinking -> r.messageIsStreaming && r.isLastBlockOverall
                    is FlatChatItem.AssistantToolUse -> active(r.block.toolStatus)
                    else -> false
                }
            }
            out[rows.first().key] = Group(mid, rows.first().key, rows.mapTo(LinkedHashSet()) { it.key }, blocks, running)
        }
        return out
    }

    /** the rows to render: members of a collapsed group (except its anchor) are left out */
    fun visible(items: List<FlatChatItem>, groups: Map<String, Group>, open: (String) -> Boolean): List<FlatChatItem> {
        if (groups.isEmpty()) return items
        val hidden = HashSet<String>()
        for (g in groups.values) if (!open(g.id)) hidden += g.memberKeys - g.anchorKey
        return if (hidden.isEmpty()) items else items.filter { it.key !in hidden }
    }

    fun stepTitle(b: AssistantBlock): String =
        if (b.kind == "thinking") "深度思考" else com.openminis.app.plus.ToolZh.title(b.toolTitle, b.toolName)

    fun durationText(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms + 500) / 1000
        return when {
            s < 60 -> "${s.coerceAtLeast(1)} 秒"
            s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "${s / 3600} 小时 ${(s % 3600) / 60} 分"
        }
    }

    /** start = earliest known step start; end = now while running, else the latest known step end */
    fun elapsed(g: Group, now: Long): Long {
        val starts = g.blocks.map { it.startTimeMs }.filter { it > 0 }
        val start = starts.minOrNull() ?: return 0
        val end = if (g.running) now else g.blocks.filter { it.startTimeMs > 0 }.maxOfOrNull { it.startTimeMs + it.durationMs } ?: return 0
        return (end - start).coerceAtLeast(0)
    }
}

@Composable
internal fun StepFoldRow(g: StepFold.Group, open: Boolean, onToggle: () -> Unit) {
    val m = com.openminis.app.ui.plus.mines()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (g.running) LaunchedEffect(g.id) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val cur = g.blocks.lastOrNull { it.kind != "thinking" && it.toolStatus != null && (it.toolStatus == ToolBlockStatus.RUNNING || it.toolStatus == ToolBlockStatus.STREAMING || it.toolStatus == ToolBlockStatus.PENDING) }
        ?: g.blocks.last()
    val failed = !g.running && g.blocks.lastOrNull { it.kind != "thinking" }?.let { it.toolStatus == ToolBlockStatus.FAILED || it.toolStatus == ToolBlockStatus.TIMEOUT } == true
    val title = StepFold.stepTitle(cur)
    val took = StepFold.durationText(StepFold.elapsed(g, now))
    val meta = "${g.blocks.size} 步" + if (took.isNotEmpty()) " · $took" else ""
    val shape = RoundedCornerShape(14.dp)
    val a11y = "步骤：${if (g.running) "正在" else "已完成"}$title，$meta。点按" + if (open) "收起" else "展开全部步骤"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(shape).background(com.openminis.app.ui.plus.minesGlassFill()).border(1.dp, m.glassBorder, shape)
            .clickable(onClick = onToggle).heightIn(min = 40.dp).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        when {
            g.running -> DecorativeSpinner(color = ChatColors.thinking, modifier = Modifier.size(15.dp), strokeWidth = 1.5.dp)
            failed -> Icon(Icons.Default.Error, contentDescription = null, tint = ToolErrorColor, modifier = Modifier.size(16.dp))
            else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ToolCheckColor, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(meta, fontSize = 12.sp, color = m.ink2, maxLines = 1, softWrap = false)
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight, contentDescription = null,
            tint = m.ink2, modifier = Modifier.padding(start = 2.dp).size(18.dp))
    }
}

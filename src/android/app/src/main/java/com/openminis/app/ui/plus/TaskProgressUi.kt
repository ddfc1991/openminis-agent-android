package com.openminis.app.ui.plus

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openminis.app.plus.TaskProgress
import com.openminis.app.plus.TaskProgressCore
import com.openminis.app.plus.TaskProgressCore.St
import com.openminis.app.plus.TaskProgressCore.StepSt

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:17–19:30 (mock r3891/mock/goal_mock4.png): the home layout stays as it is; the
 * existing 36 dp icon gets a progress arc (length = steps done) and a small state dot (进行中 orange / 等你 yellow / 出错 red /
 * 已停止 grey / 完成 green); a 「⋯」 pill under each row's time (its dots turn red / yellow for 出错 / 等你) fades in the
 * progress card over a blurred backdrop; the rest of the row still opens the chat. Small type (user: 不要大字).
 */
object ProgressColors {
    fun of(st: St, dark: Boolean): Color = when (st) {
        St.RUNNING -> if (dark) Color(0xFFF2A066) else Color(0xFFD9772E)
        St.WAITING -> if (dark) Color(0xFFF2D25C) else Color(0xFFC99A00)
        St.ERROR -> if (dark) Color(0xFFFF8A80) else Color(0xFFD33A2C)
        St.STOPPED -> if (dark) Color(0xFF8E8782) else Color(0xFF9A928C)
        St.DONE -> if (dark) Color(0xFF7FD3A0) else Color(0xFF2E9B5C)
    }
}

/** Arc (progress) + bottom-right dot (state) drawn OVER the existing 36 dp icon; nothing when the chat has no task yet. */
@Composable
fun ProgressRingOverlay(sid: String, modifier: Modifier = Modifier, showArc: Boolean = true) {
    val all by TaskProgress.tasks.collectAsState()
    val t = all[sid] ?: return
    // [Mines-38.9.6] a table rebuilt from an old chat's history adds no dot / arc to the home icon (layout unchanged)
    if (t.origin == "history" && (t.st == St.DONE || t.st == St.STOPPED)) return
    val m = mines()
    val c = ProgressColors.of(t.st, m.dark)
    val track = if (m.dark) Color(0x33FFFFFF) else Color(0x1F1C1915)
    val ring = if (m.dark) Color(0xFF1E1A18) else Color.White
    Box(modifier.size(36.dp).semantics { contentDescription = TaskProgressCore.spoken(t) }) {
        if (showArc) Canvas(Modifier.size(36.dp)) {
            val w = 2.4.dp.toPx(); val inset = w / 2
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            drawArc(c, -90f, 360f * t.fraction, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        }
    }
}

/** The 「⋯」 pill under the time: neutral dots, red for 出错, yellow for 等你. Its own tap target (≥ 26 dp tall, 40 dp wide: keeps the row height close to before). */
@Composable
fun ProgressMorePill(sid: String, onClick: () -> Unit) {
    // [Mines-38.9.6 i12] on EVERY row (user: idle, finished, stopped and old chats too); a chat without a table yet gets one
    // from its history when the card opens
    val all by TaskProgress.tasks.collectAsState()
    val t = all[sid]
    val m = mines()
    val st = t?.st
    val dot = when (st) { St.ERROR, St.WAITING -> ProgressColors.of(st, m.dark); else -> m.ink2 }
    val bg = when (st) { St.ERROR, St.WAITING -> ProgressColors.of(st, m.dark).copy(alpha = 0.16f); else -> if (m.dark) Color(0x22FFFFFF) else Color(0x141C1915) }
    Box(Modifier.heightIn(min = 26.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)
        .semantics { role = Role.Button; contentDescription = "进度：" + (t?.let { TaskProgressCore.spoken(it) } ?: "看步骤和摘要") + "。点开看步骤" },
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(Modifier.size(4.dp).clip(CircleShape).background(dot)) }
        }
    }
}

private fun hm(at: Long): String = if (at <= 0L) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

/**
 * The progress card: fades in from the bottom over a blurred, dimmed backdrop; a tap outside fades it out. Goal, steps
 * (check + time), the reason of an error, retries, the account used, and 重试 / 停止 / 进入对话.
 */
@Composable
fun ProgressSheet(sid: String, title: String, onDismiss: () -> Unit, onOpenChat: () -> Unit,
                  /** [Mines-38.9.6 i14] the chat is archived by the 3-day rule (caller knows the session) */ autoArchived: Boolean = false) {
    val all by TaskProgress.tasks.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // [Mines-38.9.6 i12] every chat has a table: an old / idle chat's is rebuilt from its saved messages here (once, cached)
    LaunchedEffect(sid) { runCatching { TaskProgress.ensure(ctx, sid, title) } }
    val t0 = all[sid]
    val m = mines()
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val close: () -> Unit = { closing = true; shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.targetState) { if (closing && !shown.targetState && !shown.currentState) onDismiss() }
    val manual by com.openminis.app.plus.ChatOrganizer.manual.collectAsState()
    val restored by com.openminis.app.plus.ChatOrganizer.restored.collectAsState()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // [Mines-38.9.6 i12] the shared glass: blurred page copy (cached, one capture) + 暖瓷 tint at the global glass alpha.
        // 38.9.5 drew a 97 % off-white card here (user shot 1000129912: 「白底」).
        GlassWindowEffect()
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = close), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(shown, enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }, exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 8 }) {
                val shape = RoundedCornerShape(24.dp)
                GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp).navigationBarsPadding(), shape = shape, halo = false) {
                  Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(m.ink3.copy(alpha = 0.5f)))
                    Spacer(Modifier.height(10.dp))
                    // [Mines-38.9.6 i14] smoke 39096255503: an auto-archived chat showed 归档 (only manual counted) → 取消归档 for both
                    val archived = androidx.compose.runtime.remember(manual, restored, sid) { com.openminis.app.plus.ChatOrganizer.isManual(sid) || (autoArchived && !restored.containsKey(sid)) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title.ifBlank { "这个对话" }, fontSize = MT.Body, lineHeight = MT.BodyLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                        Spacer(Modifier.width(8.dp))
                        // [Mines-38.9.6 i12] one-tap archive (撤销 toast) / 取消归档
                        Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).background(if (m.dark) Color(0x1FFFFFFF) else Color(0x121C1915))
                            .clickable {
                                // [Mines-38.9.6 i14] dismiss at once (no fade): the row this card belongs to leaves the list, and the
                                // card must not outlive it (smoke: another chat's card was left open and hid the 撤销 toast)
                                onDismiss()
                                if (archived) com.openminis.app.plus.ChatOrganizer.restore(ctx, sid) else com.openminis.app.plus.ChatOrganizer.archive(ctx, sid)
                            }.semantics { role = Role.Button; contentDescription = if (archived) "取消归档" else "归档这个对话" }
                            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(if (archived) "取消归档" else "归档", fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, softWrap = false)
                        }
                    }
                    val t = t0
                    if (t == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在整理这个对话的步骤…", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
                    } else {
                        val c = ProgressColors.of(t.st, m.dark)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 3.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(5.dp))
                            Text(t.st.label + (hm(t.stateAt).takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = MT.Caption, color = if (m.dark) c else c.copy(alpha = 1f), fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        // one line that always says something (never 「没有步骤表」)
                        Text(TaskProgressCore.oneLine(t), fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (t.goal.isNotBlank()) Text("目标：${t.goal}", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (t.account.isNotBlank()) Text("用的账号：${t.account}", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink2)
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            t.steps.forEach { s -> StepRow(s) }
                        }
                        if ((t.st == St.ERROR || t.st == St.WAITING || t.st == St.STOPPED) && (t.reason.isNotBlank() || t.retries > 0)) {
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (m.dark) Color(0x14FFFFFF) else Color(0x0D1C1915)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (t.reason.isNotBlank()) Text("原因：${t.reason}", fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.ink)
                                TaskProgressCore.retryLine(t).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = MT.Caption, color = m.ink2) }
                            }
                        }
                    }
                    // [Mines-38.9.6 i14] 重试 is always enabled (user 1000130013 「点了不能继续重试」): a running / stuck turn is stopped
                    // and started again, as often as tapped; the card stays open and says so. A chat that is not open yet opens
                    // and retries there.
                    var retryNote by remember(sid) { androidx.compose.runtime.mutableStateOf("") }
                    if (retryNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(retryNote, fontSize = MT.Sub, lineHeight = MT.SubLH, color = m.primaryText, fontWeight = FontWeight.Medium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val st = t0?.st
                        SheetButton("重试", primary = true, enabled = true, modifier = Modifier.weight(1f)) {
                            val running = st == St.RUNNING || st == St.WAITING
                            val n = TaskProgress.retryNow(sid)
                            if (n > 0) retryNote = (if (running) "已停下这一轮并重新开始" else "已重新开始") + (if (n > 1) "（第 $n 次）" else "")
                            else { close(); onOpenChat() }
                        }
                        SheetButton("停止", primary = false, enabled = st == St.RUNNING || st == St.WAITING, modifier = Modifier.weight(1f)) { TaskProgress.stop(sid); close() }
                        SheetButton("进入对话", primary = false, enabled = true, modifier = Modifier.weight(1f)) { close(); onOpenChat() }
                    }
                  }
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: TaskProgressCore.Step) {
    val m = mines()
    val (glyph, col) = when (s.st) {
        StepSt.DONE -> "✓" to ProgressColors.of(St.DONE, m.dark)
        StepSt.FAILED -> "!" to ProgressColors.of(St.ERROR, m.dark)
        StepSt.WAITING -> "…" to ProgressColors.of(St.WAITING, m.dark)
        StepSt.DOING -> "•" to ProgressColors.of(St.RUNNING, m.dark)
        StepSt.TODO -> "" to m.ink3
    }
    val stateWord = when (s.st) { StepSt.DONE -> "已完成"; StepSt.FAILED -> "出错"; StepSt.WAITING -> "等你"; StepSt.DOING -> "进行中"; StepSt.TODO -> "还没开始" }
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).semantics(mergeDescendants = true) { contentDescription = "${s.title}，$stateWord" + (s.note.takeIf { it.isNotBlank() }?.let { "，$it" } ?: "") },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).then(if (s.st == StepSt.TODO) Modifier.border(1.4.dp, col, CircleShape) else Modifier.background(col)),
            contentAlignment = Alignment.Center) { if (glyph.isNotBlank()) Text(glyph, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(s.title + (s.note.takeIf { it.isNotBlank() && (s.st == StepSt.FAILED || s.st == StepSt.WAITING) }?.let { "：$it" } ?: ""), fontSize = MT.Body, lineHeight = MT.BodyTightLH,
            color = if (s.st == StepSt.TODO) m.ink2 else m.ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(hm(s.at).takeIf { s.st != StepSt.TODO }.orEmpty(), fontSize = MT.Caption, color = m.ink3)
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = mines()
    // [Mines-38.9.6] disabled = quiet neutral pill + ink2 + hairline (was white on 35 % salmon: unreadable), like GlassPill
    val bg = when { primary && enabled -> m.primary; !enabled -> if (m.dark) Color(0x14FFFFFF) else Color(0x0D1C1915); else -> if (m.dark) Color(0x1FFFFFFF) else Color(0x121C1915) }
    val fg = when { primary && enabled -> m.onPrimary; enabled -> m.ink; else -> m.ink2 }
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(bg)
        .then(if (enabled) Modifier else Modifier.border(1.dp, if (m.dark) Color(0x29FFFFFF) else Color(0x241C1915), RoundedCornerShape(50)))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; if (!enabled) disabled() }, contentAlignment = Alignment.Center) {
        Text(text, fontSize = MT.Body, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** 「进行中 2 · 等你 1 · 出错 1 · 完成 2」 with a coloured dot per count (replaces 「都在正常跑」 when there are tasks). */
@Composable
fun TaskSummaryLine(modifier: Modifier = Modifier): Boolean {
    val all by TaskProgress.tasks.collectAsState()
    val vis by TaskProgress.visible.collectAsState()
    // [Mines-38.9.5 i11] only conversations that are on the list (a deleted / never-saved chat's row is not 「进行中」)
    val s = remember(all, vis) { TaskProgressCore.summary(all.values.filter { t -> vis?.contains(t.sid) ?: true }, System.currentTimeMillis()) }
    if (s.total == 0) return false
    val m = mines()
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "对话进度：" + s.line }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(St.RUNNING to s.running, St.WAITING to s.waiting, St.ERROR to s.error, St.DONE to s.done).filter { it.second > 0 }.forEach { (st, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(ProgressColors.of(st, m.dark))); Spacer(Modifier.width(4.dp))
                Text("${st.label} $n", fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 1, softWrap = false)
            }
        }
    }
    return true
}

/**
 * In the chat, right above the input: 「— 已停止 · 原因 · 19:12 —」 and one status line when the agent stopped / failed /
 * waits for the user (read aloud by TalkBack as a polite live region). Nothing while it works or after a normal finish.
 */
@Composable
fun ChatProgressStatus(sid: String, streaming: Boolean) {
    if (sid.isBlank()) return
    // [Mines-38.9.8] which skill / saved workflow the app picked for this request (shown while it works and after)
    val chips by com.openminis.app.plus.SkillMatch.chips.collectAsState()
    chips[sid]?.let { SkillChipLine(it) }
    if (streaming) return
    // [38.9.15.1] no 「在等你：等你回复」 / status line over the composer on 聊天 (what tasks need is the 「需要你 N」 chip)
    if (com.openminis.app.plus.ChatHome.isMain(sid)) return
    val all by TaskProgress.tasks.collectAsState()
    val t = all[sid] ?: return
    val div = TaskProgressCore.divider(t)
    val line = TaskProgressCore.inputStatus(t)
    if (div == null && line == null) return
    val m = mines()
    val c = ProgressColors.of(t.st, m.dark)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally) {
        div?.let { Text(it, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = c, textAlign = TextAlign.Center, maxLines = 2) }
        line?.let {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                Text(it, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** [Mines-38.9.8] 「用技能：xxx」 / 「用工作流：xxx」 — one small line above the input. */
@Composable
private fun SkillChipLine(text: String) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(m.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(text, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [Mines-38.9.6 i12] 「已归档 · 撤销」 for 4 s at the bottom after a one-tap archive (any screen). */
@Composable
fun ArchiveUndoHost() {
    val o by com.openminis.app.plus.ArchiveUndo.offer.collectAsState()
    val cur = o ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(cur) { kotlinx.coroutines.delay(if (cur.undo != null) 6000 else 4000); com.openminis.app.plus.ArchiveUndo.clear(cur) }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
        UndoBar(cur.label, onAction = {
            cur.undo?.invoke() ?: com.openminis.app.plus.ChatOrganizer.restore(ctx, cur.id); com.openminis.app.plus.ArchiveUndo.clear(cur)
        })
    }
}

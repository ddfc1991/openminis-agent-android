package com.openminis.app.ui.plus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.plus.MinesJobs
import kotlinx.coroutines.delay

/**
 * [Mines-jobs-25] App-wide host for background jobs: a slim pill at the top
 * while something runs (tap for the last output line), and a short popup at
 * the bottom when a job ends — "已安装 X" on success (3 s), or the reason with
 * 重试 / 详情 on failure (stays 10 s). Never blocks the screen: both are
 * non-focusable popups, touches outside go to the app.
 */
@Composable
fun MinesJobsHost() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) MinesJobs.appInForeground = true
            if (e == Lifecycle.Event.ON_STOP) MinesJobs.appInForeground = false
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val jobs by MinesJobs.jobs.collectAsState()
    val running = jobs.filter { it.running && !it.silent }
    var expanded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<MinesJobs.Job?>(null) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MinesJobs.finished.collect { if (!it.silent) { toast = it; details = false } } }
    LaunchedEffect(toast, details) {
        val t = toast ?: return@LaunchedEffect
        if (details) return@LaunchedEffect
        delay(if (t.ok) 3000 else 10_000)
        if (toast?.id == t.id) toast = null
    }

    if (running.isNotEmpty()) {
        Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
            // [Mines-38.9.6 i14] glass banner (was a solid dark chip)
            val gm = mines()
            GlassToast(Modifier.statusBarsPadding().padding(top = 6.dp).widthIn(max = 340.dp)) { Column(
                Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = gm.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running.size == 1) running[0].title + "…" else "${running.size} 个任务在后台进行",
                        color = gm.ink, fontSize = MT.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded) running.forEach { j ->
                    Text(j.lastLine.ifBlank { "准备中…" }, color = gm.ink2, fontSize = MT.Caption, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            } }
        }
    }

    val t = toast
    if (t != null) {
        Popup(alignment = Alignment.BottomCenter, properties = PopupProperties(focusable = false)) {
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                val gm = mines()
                GlassToast(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 24.dp)
                        .widthIn(max = 420.dp).fillMaxWidth(), solid = !t.ok) { Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (t.ok) "✓ " + t.successText else "✗ " + t.title + " 没成功",
                        color = if (t.ok) Color(if (gm.dark) 0xFF8FD3A8 else 0xFF1F6B3F) else (if (gm.dark) MinesDesign.DangerDark else MinesDesign.Danger), fontSize = MT.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    if (!t.ok) Text(t.reason, color = gm.ink, fontSize = MT.Body)
                    if (details) Box(Modifier.fillMaxWidth().then(Modifier.heightIn(max = 260.dp)).clip(RoundedCornerShape(8.dp)).background(gm.ink.copy(alpha = 0.06f))
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp)) {
                        Text(t.lines.takeLast(8).joinToString("\n"), color = gm.ink2, fontSize = MT.Caption)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!t.ok) {
                            TextButton(onClick = { details = !details }) { Text(if (details) "收起" else "详情", color = gm.ink) }
                            TextButton(onClick = { toast = null; MinesJobs.retry(context, t) }) { Text("重试", color = gm.primaryText) }
                        }
                        TextButton(onClick = { toast = null }) { Text("好", color = gm.primaryText) }
                    }
                } }
            }
        }
    }
}

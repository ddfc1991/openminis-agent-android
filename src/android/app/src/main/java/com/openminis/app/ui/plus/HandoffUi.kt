package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.plus.HandoffBroker
import com.openminis.app.plus.HandoffCore
import com.openminis.app.plus.HandoffCore.Handoff
import com.openminis.app.plus.HandoffCore.Kind
import com.openminis.app.plus.HandoffFlags
import com.openminis.app.plus.HandoffLabel
import com.openminis.app.plus.LoginCard

/**
 * [Mines-38.9.13 handoff_v2] what the user sees while a browser step waits for them (any screen, any conversation):
 * a popup naming the task, the site (logo + domain) and one action (「去那个页面」 opens that conversation's browser on the
 * recorded tab), then a small glass bar that stays until it is done — with 「继续接管」 always there. Normal type size.
 */
@Composable
fun HandoffHost() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HandoffBroker.init(ctx) }
    // back from Chrome (Google / Apple hand-off)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) HandoffBroker.onAppResumed() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // notification tapped before that conversation was loaded: open its browser once it is
    val focus by HandoffBroker.pendingFocus.collectAsState()
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        repeat(20) { if (HandoffBroker.applyPendingFocus()) return@LaunchedEffect; kotlinx.coroutines.delay(500) }
        HandoffBroker.pendingFocus.value = null
    }
    val list by HandoffBroker.list.collectAsState()
    val card by LoginCard.pending.collectAsState()
    val h = list.filter { it.open || it.state == HandoffCore.State.EXPIRED }.maxByOrNull { it.createdAt } ?: return
    var seenSeq by remember(h.id) { mutableIntStateOf(0) }
    // the login card is its own sheet; a LOGIN popup only when the sign-in was not confirmed (hint)
    val popup = h.open && seenSeq < h.showSeq && (h.kind != Kind.LOGIN || h.hint.isNotBlank())
    if (popup) HandoffDialog(h) { seenSeq = h.showSeq }
    else if (card == null) HandoffBar(h)
}

private fun site(h: Handoff) = HandoffCore.site(h.host).ifBlank { h.host.ifBlank { "网页" } }
private fun name(h: Handoff) = runCatching { com.openminis.app.plus.Keychain.siteName(h.host) }.getOrNull()?.ifBlank { null } ?: HandoffLabel.siteWord(h.host)
private fun labelled() = HandoffFlags.on(HandoffFlags.LABEL)

private fun how(h: Handoff): String = when (h.kind) {
    Kind.CAPTCHA -> "打开这个网页，按提示点一下或拖一下滑块。"
    Kind.OTP -> "打开这个网页，把收到的验证码填进去。"
    Kind.LOGIN -> "打开这个网页登录。"
    Kind.CONFIRM -> "打开这个网页，点一下确认。"
    Kind.OAUTH_EXTERNAL -> "这个网站要用 Google / Apple 登录，只能在 Chrome 里完成，登录会留在 Chrome。回到 Mines 后会告诉你下一步。"
} + if (h.kind == Kind.OAUTH_EXTERNAL) "" else "完成后 Mines 自动继续。"

@Composable
private fun Head(h: Handoff) {
    val m = mines()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AcctLogo(h.host, name(h))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (labelled() && h.taskLabel.isNotBlank()) Text(h.taskLabel, fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(site(h), fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HandoffDialog(h: Handoff, onLater: () -> Unit) {
    val m = mines()
    val ctx = LocalContext.current
    val oauth = h.kind == Kind.OAUTH_EXTERNAL
    MinesDialog(
        title = "需要你" + HandoffBroker.verb(h),
        onDismiss = onLater,
        confirm = if (oauth) "在 Chrome 里打开" else "去那个页面",
        onConfirm = { onLater(); if (oauth) HandoffBroker.openChrome(ctx, h.id) else HandoffBroker.goTo(h.id) },
        dismiss = "稍后",
    ) {
        Head(h)
        Text(how(h), fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink)
        if (h.hint.isNotBlank()) Text(h.hint, fontSize = MT.Body, color = if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger)
    }
}

/** the bar that stays until the step is done: 去那个页面 · 继续接管 · 放弃 */
@Composable
private fun HandoffBar(h: Handoff) {
    val m = mines()
    val ctx = LocalContext.current
    val paused = h.state == HandoffCore.State.EXPIRED
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        GlassToast(Modifier.widthIn(max = 460.dp).padding(horizontal = 12.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Head(h)
                Text(
                    when {
                        paused -> "已暂停，等你在 ${site(h)} ${HandoffBroker.verb(h)}"
                        h.stage >= 2 -> "还在等你在 ${site(h)} ${HandoffBroker.verb(h)}"
                        else -> "需要你" + HandoffBroker.verb(h)
                    },
                    fontSize = MT.Body, color = m.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (h.kind == Kind.OAUTH_EXTERNAL) GlassSecondaryPill("在 Chrome 里打开") { HandoffBroker.openChrome(ctx, h.id) }
                    else GlassSecondaryPill("去那个页面") { HandoffBroker.goTo(h.id) }
                    GlassPill("继续接管") { HandoffBroker.takeOver(h.id) }
                    GlassTextButton("放弃") { HandoffBroker.cancel(h.id, "用户点了「放弃这一步」") }
                }
            }
        }
    }
}

/** inside the browser sheet: the step this conversation waits on + 「继续接管」 (the sheet covers the global bar) */
@Composable
fun HandoffSheetBar(h: Handoff, modifier: Modifier = Modifier) {
    val m = mines()
    val ctx = LocalContext.current
    if (!h.open && h.state != HandoffCore.State.EXPIRED) return
    GlassToast(modifier.widthIn(max = 460.dp).padding(12.dp), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (labelled() && h.taskLabel.isNotBlank()) Text(h.taskLabel, fontSize = MT.Meta, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("需要你" + HandoffBroker.verb(h) + if (h.hint.isNotBlank()) " · " + h.hint else "", fontSize = MT.Body, color = m.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (h.kind == Kind.OAUTH_EXTERNAL) GlassTextButton("在 Chrome 里打开") { HandoffBroker.openChrome(ctx, h.id) }
            GlassPill("继续接管") { HandoffBroker.takeOver(h.id) }
        }
    }
}

/** 设置 → 浏览器验证: the handoff_v2 switches (实验功能, default on) */
@Composable
fun HandoffFlagsSection() {
    PlusSection("交接 v2（实验功能）", footer = "验证、登录弹窗关掉后 Mines 马上继续；回到你操作的那个页面；弹窗写明是哪个项目、哪个网站。关掉总开关 = 回到旧版做法。") {
        HandoffFlagRows()
    }
}

/** [Mines-38.9.13 integ] the handoff_v2 switches; also listed in 设置 → 实验功能. */
@Composable
fun HandoffFlagRows() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HandoffFlags.init(ctx) }
    val rev by HandoffFlags.rev.collectAsState()
    val keys = listOf(HandoffFlags.MASTER) + HandoffFlags.SUBS
    keys.forEachIndexed { i, k ->
        if (i > 0) PlusDivider()
        val on = remember(rev) { HandoffFlags.userValue(k) }
        SwitchRow(HandoffFlags.ZH[k] ?: k, null, on) { v -> HandoffFlags.set(ctx, k, v) }
    }
}

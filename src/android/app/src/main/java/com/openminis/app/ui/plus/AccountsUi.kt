package com.openminis.app.ui.plus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.plus.ConnectionKind
import com.openminis.app.plus.Favicons
import com.openminis.app.plus.SessionStatus
import com.openminis.app.ui.brand.MinesIcon
import com.openminis.app.ui.brand.MinesIcons

/**
 * [Mines-38.9.3] user 2026-10-17 「ui 都不一样，没对齐」 + 「账号关联和账号连接是不是重复了」: ONE page 「账号」 with two tabs —
 * 服务 (API keys / models / mail / GitHub, was 账号连接) and 登录 (site sign-ins grouped by identity, was 账号关联) — and ONE set
 * of row parts both tabs and both detail pages use:
 *  - [AcctLogo]: 40 dp tile, 12 dp corners, real favicon / bundled brand logo on white with a hairline border, letter tile
 *    fallback of the same size and corners;
 *  - [AcctRow]: ≥ 64 dp, 16 dp side padding, title 16 sp one line, subtitle 13 sp one line, ONE status pill, chevron;
 *  - [AcctPill]: 正常 green tonal / 要注意 amber / 异常 red / 未检查 grey, same shape everywhere;
 *  - [AcctHeader]: section label starting where the card content starts (20 dp card inset + 16 dp row inset).
 * Sizes stay as they are (the user is partially sighted but does NOT want large type) — consistent and high-contrast.
 */

object AcctUi {
    val Logo = 40.dp
    val LogoRadius = 12.dp
    val RowMin = 64.dp
    val SideH = 16.dp
    val Title = MT.Body
    val TitleLH = MT.BodyTightLH
    val Sub = MT.Caption
    val SubLH = MT.CaptionLH
    /** divider start = row padding + logo + gap */
    val DividerStart = 68.dp
}

@Composable
fun AcctPill(p: SessionStatus.Pill, label: String = p.label) {
    val m = mines()
    val (fg, bg) = when (p) {
        SessionStatus.Pill.OK -> if (m.dark) MinesDesign.SuccessDark to MinesDesign.SuccessCDark else MinesDesign.Success to MinesDesign.SuccessC
        SessionStatus.Pill.WARN -> if (m.dark) MinesDesign.WarningDark to MinesDesign.WarningCDark else MinesDesign.Warning to MinesDesign.WarningC
        SessionStatus.Pill.BAD -> if (m.dark) MinesDesign.DangerDark to MinesDesign.DangerCDark else MinesDesign.Danger to MinesDesign.DangerC
        SessionStatus.Pill.UNCHECKED -> if (m.dark) Color(0xFFCFC6C0) to Color(0xFF3A3431) else Color(0xFF5E5651) to Color(0xFFECE7E3)
    }
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)
        .semantics { contentDescription = "状态：$label" }) {
        Text(label, fontSize = MT.Caption, lineHeight = MT.CaptionLH, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false)
    }
}

/** Real favicon (cached) on white, or a letter tile — 40 dp, 12 dp corners either way. */
@Composable
fun AcctLogo(host: String, name: String) {
    val ctx = LocalContext.current
    val bmpState = remember(host) { mutableStateOf(Favicons.cached(host)) }
    LaunchedEffect(host) { if (bmpState.value == null && host.isNotBlank()) bmpState.value = Favicons.get(ctx, host) }
    val b = bmpState.value
    val shape = RoundedCornerShape(AcctUi.LogoRadius)
    if (b != null) {
        Box(Modifier.size(AcctUi.Logo).clip(shape).background(Color.White).border(1.dp, Color(0x1F3C2814), shape), contentAlignment = Alignment.Center) {
            Image(b.asImageBitmap(), null, Modifier.size(26.dp))
        }
    } else AcctLetter(name.ifBlank { host })
}

@Composable
fun AcctLetter(name: String) {
    val palette = listOf(0xFFB5532F, 0xFF2C6AB0, 0xFF1C7069, 0xFF6A4FB4, 0xFFA83C64, 0xFF3B7337, 0xFF8A5A14)
    val c = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(AcctUi.Logo).clip(RoundedCornerShape(AcctUi.LogoRadius)).background(c), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontSize = MT.Body, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** A 账号连接 service: its bundled brand logo on white (same tile), else its favicon, else the letter tile. */
@Composable
fun AcctKindLogo(kind: ConnectionKind) {
    val shape = RoundedCornerShape(AcctUi.LogoRadius)
    if (kind.icon != 0) Box(Modifier.size(AcctUi.Logo).clip(shape).background(Color.White).border(1.dp, Color(0x1F3C2814), shape), contentAlignment = Alignment.Center) {
        Image(painterResource(kind.icon), contentDescription = null, modifier = Modifier.size(26.dp))
    } else {
        val host = com.openminis.app.plus.Keychain.hostOf(kind.site ?: kind.loginUrl ?: kind.panelUrl ?: kind.keyUrl)
        AcctLogo(host, kind.name)
    }
}

enum class Chevron { RIGHT, DOWN, UP, NONE }

/** The one account row (服务 and 登录 tabs, provider lists, detail heroes). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AcctRow(
    logo: @Composable () -> Unit, title: String, subtitle: String?, pill: SessionStatus.Pill?, pillLabel: String? = null,
    subtitleColor: Color? = null, chevron: Chevron = Chevron.RIGHT, onClick: (() -> Unit)?, onLongClick: (() -> Unit)? = null,
    description: String? = null, extra: (@Composable () -> Unit)? = null, subtitleLines: Int = 1,
    /** [Mines-38.9.5 i] small action pills on a line UNDER the reason (一键修复 / 忽略), so title + reason keep the width */
    below: (@Composable () -> Unit)? = null,
) {
    val m = mines()
    val click = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "更多")
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().heightIn(min = AcctUi.RowMin).then(click).padding(horizontal = AcctUi.SideH, vertical = 10.dp)
        .semantics(mergeDescendants = true) { contentDescription = description ?: listOfNotNull(title, subtitle, pillLabel ?: pill?.label).joinToString("，") },
        verticalAlignment = Alignment.CenterVertically) {
        logo()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = AcctUi.Title, lineHeight = AcctUi.TitleLH, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = AcctUi.Sub, lineHeight = AcctUi.SubLH, color = subtitleColor ?: m.ink2, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
            if (below != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) { below() }
        }
        extra?.invoke()
        if (pill != null) { Spacer(Modifier.width(8.dp)); AcctPill(pill, pillLabel ?: pill.label) }
        if (chevron != Chevron.NONE) {
            Spacer(Modifier.width(6.dp))
            MinesIcon(MinesIcons.named("chevron-right")!!, null, tint = m.ink3, modifier = Modifier.size(18.dp)
                .rotate(when (chevron) { Chevron.DOWN -> 90f; Chevron.UP -> -90f; else -> 0f }))
        }
    }
}

@Composable
fun AcctDivider() {
    val m = mines()
    Box(Modifier.padding(start = AcctUi.DividerStart, end = AcctUi.SideH).fillMaxWidth().height(1.dp).background(m.hairline))
}

/** Section label aligned with the card content (20 dp card inset + 16 dp row inset), optional count / action on the right. */
@Composable
fun AcctHeader(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null, icon: String? = null) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MinesIcon(MinesIcons.named(icon) ?: MinesIcons.named("user")!!, null, tint = m.ink2, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = MT.Meta, fontWeight = FontWeight.Medium, color = if (onTrailing != null) m.primaryText else m.ink3,
            modifier = if (onTrailing != null) Modifier.clickable(onClick = onTrailing).padding(4.dp) else Modifier)
    }
}

/** 48 dp search pill (both tabs). */
@Composable
fun AcctSearch(query: String, hint: String, onChange: (String) -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 6.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
        .background(minesGlassFill(true)).border(1.dp, m.glassBorder, RoundedCornerShape(50)).padding(horizontal = AcctUi.SideH),
        verticalAlignment = Alignment.CenterVertically) {
        MinesIcon(MinesIcons.named("search")!!, null, tint = m.ink2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, fontSize = MT.Body, color = m.ink2)
            BasicTextField(query, onChange, singleLine = true, textStyle = TextStyle(fontSize = MT.Body, color = m.ink), cursorBrush = SolidColor(m.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
        }
        if (query.isNotEmpty()) Text("取消", fontSize = MT.Body, color = m.primaryText, modifier = Modifier.clickable { onChange("") }.padding(4.dp))
    }
}

/** 服务 | 登录 segmented tabs under the page title (48 dp, one frosted track, the selected half filled). */
@Composable
fun AccountsTabs(tab: Int, onTab: (Int) -> Unit) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(horizontal = MS.PageH).padding(top = 2.dp, bottom = 4.dp).height(44.dp).clip(RoundedCornerShape(50))
        .background(minesGlassFill(true)).border(1.dp, m.glassBorder, RoundedCornerShape(50)).padding(4.dp)) {
        listOf("服务", "登录").forEachIndexed { i, t ->
            val on = i == tab
            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(50)).background(if (on) m.primary else Color.Transparent)
                .clickable { if (!on) onTab(i) }.semantics { role = Role.Tab; selected = on; contentDescription = "$t 标签" + if (on) "，已选中" else "" },
                contentAlignment = Alignment.Center) {
                Text(t, fontSize = MT.Body, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) Color.White else m.ink,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/** The merged page frame: title 「账号」, the two tabs, then the tab's content. */
@Composable
fun AccountsPage(tab: Int, onTab: ((Int) -> Unit)?, onBack: () -> Unit, actions: (@Composable () -> Unit)? = null, title: String = "账号",
                 content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    PlusPage(title, onBack, actions = actions) {
        if (onTab != null) AccountsTabs(tab, onTab)
        content()
    }
}

/**
 * [Mines-38.9.3] 「账号」: one destination for every old entry. [initial] 0 = 服务 (route plus_connections: settings,
 * bottom bar 应用, deep links into a service), 1 = 登录 (route plus_keychain: 消息 alerts, 一键关联 results).
 */
@Composable
fun AccountsScreen(initial: Int, onBack: () -> Unit, go: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initial) }
    if (tab == 0) ConnectionsScreen(onBack = onBack, onTab = { tab = it })
    else KeychainScreen(onBack = onBack, go = go, onTab = { tab = it })
}

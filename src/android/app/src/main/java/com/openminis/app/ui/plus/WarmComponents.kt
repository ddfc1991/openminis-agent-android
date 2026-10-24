package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.brand.MinesIcon

/**
 * [Mines-warm-36] build-36 「Muse 结构 × 暖瓷毛玻璃」 components (design36warm, user-approved
 * 2026-10-14): 90 % opaque porcelain glass, line icons #B5532F (dark #E08A6B), primary pills
 * #B5532F + white. Sizes follow the user's yardstick (rows ≥ 64 dp, text ≥ 14 sp, touch ≥ 48 dp);
 * key labels are single-line and never cut at font scale 1.3 (short labels, FlowRow where needed).
 */
object WarmIcons {
    fun of(name: String): ImageVector? = com.openminis.app.ui.brand.MinesIcons.named(name)
}

/** 48 dp frosted round button (page bars, header corners). Optional red dot. */
@Composable
fun WarmRoundButton(icon: ImageVector, label: String, size: Dp = 42.dp,  /* [Mines-38.2] board 48 px x 360/412 */ dot: Boolean = false, onClick: () -> Unit) {
    val m = mines()
    // [Mines-38.9.5 i11] the whole square is the tap target (the circle clip used to drop taps on its corners — the smoke's
    // ☰ tap at the bounds' corner never opened the menu; a partially sighted thumb lands there too); ripple stays round
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.size(size).clickable(interactionSource = press, indication = null, onClick = onClick)
        .semantics { contentDescription = label; role = Role.Button }) {
        // [Mines-38.8] user 2026-10-16 13:45: real frosted glass, not a solid white disc — translucent porcelain fill with a
        // top-light sheen, a thin bright rim (light at the top, fading down), and a soft warm drop shadow drawn by hand
        // (an elevation shadow would show through the translucent fill as a grey core).
        Box(Modifier.size(size)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(Brush.radialGradient(listOf(Color(0x2A3C2814), Color(0x003C2814)), center = center.copy(y = center.y + 3.dp.toPx()), radius = r + 6.dp.toPx()),
                    radius = r + 6.dp.toPx(), center = center.copy(y = center.y + 3.dp.toPx()))
            }
            .clip(CircleShape)
            .background(Brush.verticalGradient(if (m.dark) listOf(Color(0x40FFFFFF), Color(0x17FFFFFF)) else listOf(Color(0x59FFE4D6), Color(0x4DFFE4D6))))   // [Mines-38.8 v5d] light: peach glass 30–35 %, no white sheen
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = if (m.dark) 0.10f else 0.38f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset.Unspecified, radius = Float.POSITIVE_INFINITY))
            .border(1.dp, Brush.verticalGradient(if (m.dark) listOf(Color(0x59FFFFFF), Color(0x14FFFFFF)) else listOf(Color(0xF2FFFFFF), Color(0x40FFFFFF))), CircleShape)
            .indication(press, androidx.compose.foundation.LocalIndication.current),
            contentAlignment = Alignment.Center) {
            MinesIcon(icon, null, tint = m.ink, modifier = Modifier.size(size * 0.45f))
        }
        if (dot) Box(Modifier.align(Alignment.TopEnd).size(11.dp).clip(CircleShape).background(MinesDesign.DangerFill)
            .border(2.dp, m.bg, CircleShape))
    }
}

/**
 * Page header of the Muse structure in the warm skin: round buttons in both top corners, the
 * character's face in the middle with a glass name plate pressed under its chin
 * (name + coloured status line).
 */
@Composable
fun WarmHeader(
    status: String,
    modifier: Modifier = Modifier,
    face: Dp = 64.dp,
    onFace: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val m = mines()
    val name = com.openminis.app.plus.Avatars.name(currentCharacter())
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(face).clip(CircleShape)
                .then(if (onFace != null) Modifier.clickable(onClick = onFace).semantics { contentDescription = "换角色" } else Modifier),
                contentAlignment = Alignment.Center) { PetAvatar(size = face) }
            Column(
                Modifier.offset(y = (-10).dp).widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(minesGlassFill(strong = true))
                    .border(1.dp, m.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 3.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, fontSize = MT.Body, lineHeight = MT.BodyTightLH, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(m.icon))
                    Spacer(Modifier.width(5.dp))
                    Text(status, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = m.primaryText, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One entry of the bottom bar. */
data class WarmNavItem(val label: String, val icon: ImageVector, val badge: Int = 0, val onClick: () -> Unit)

/** Bottom bar: 对话 · 消息 · 灵感 · 任务 · 应用, a frosted pill; 消息 carries the red count of things to handle. */
@Composable
fun WarmNavBar(items: List<WarmNavItem>, current: Int, modifier: Modifier = Modifier) {
    val m = mines()
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(0.dp, shape, clip = false, ambientColor = Color(0x333C2814), spotColor = Color(0x333C2814))
            .clip(shape).background(dockFill()).border(1.dp, m.glassBorder, shape)
            .heightIn(min = 62.dp).padding(horizontal = 4.dp, vertical = 3.dp),  // [Mines-38.5] opaque: list text never shows through
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, it ->
            val on = i == current
            val fg = if (on) m.primaryText else m.ink2
            Column(
                Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = it.onClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = it.label + (if (it.badge > 0) "，${it.badge} 件要处理" else ""); this.selected = on; role = Role.Tab
                    },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    MinesIcon(it.icon, null, tint = fg, modifier = Modifier.padding(top = 2.dp).size(21.dp))
                    if (it.badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp).heightIn(min = 16.dp).widthIn(min = 16.dp)
                        .clip(RoundedCornerShape(50)).background(MinesDesign.DangerFill).border(1.5.dp, m.bg, RoundedCornerShape(50))
                        .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(if (it.badge > 99) "99+" else it.badge.toString(), fontSize = MT.Tiny, lineHeight = MT.CaptionLH, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                }
                Text(it.label, fontSize = MT.Nav, lineHeight = MT.CaptionLH, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = fg,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

/** [Mines-38.1] The 「N 件要处理」 summary as ONE thin line (board 03): shield, red count, what they are, 去看 ›. */
@Composable
fun TodoSummaryBar(count: Int, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = mines()
    val (fg, bg) = toneColors(Tone.DANGER)
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(shape).background(minesGlassFill(strong = true))
            .border(1.dp, m.glassBorder, shape).pressClickable(onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$count 件要处理：$summary。点按去看" }
            .heightIn(min = 44.dp).padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            MinesIcon(WarmIcons.of("shield")!!, null, tint = fg, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("$count 件要处理", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(8.dp))
        Text(summary, fontSize = MT.Caption, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Row(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).background(m.surface2).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("去看", fontSize = MT.Caption, fontWeight = FontWeight.SemiBold, color = m.ink, maxLines = 1, softWrap = false)
            MinesIcon(WarmIcons.of("chevron-right")!!, null, tint = m.ink, modifier = Modifier.size(14.dp))
        }
    }
}

/** A category tab: label + count; selected = ink pill. Count of 账号异常 in red. */
data class WarmTab(val key: String, val label: String, val count: Int, val danger: Boolean = false)

/** [Mines-38.1] Category tabs: ONE row of small capsules (board 03), scrolls sideways at large font sizes, never wraps. */
@Composable
fun WarmTabs(tabs: List<WarmTab>, current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val m = mines()
    Row(modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { t ->
            val on = t.key == current
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.heightIn(min = 40.dp).clip(shape).background(if (on) m.ink else minesGlassFill(strong = true))
                    .border(1.dp, if (on) Color.Transparent else m.glassBorder, shape).clickable { onSelect(t.key) }
                    .semantics(mergeDescendants = true) { contentDescription = if (t.count > 0) "${t.label}，${t.count} 条" else t.label; this.selected = on; role = Role.Tab }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.label, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = if (on) m.bg else m.ink, maxLines = 1, softWrap = false)
                if (t.count > 0) {  // [Mines-38.5] 「账号异常 0」: zero counts hidden
                    Spacer(Modifier.width(4.dp))
                    Text(t.count.toString(), fontSize = MT.Caption, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                        color = when { t.danger && t.count > 0 && !on -> MinesDesign.Danger.takeIf { !m.dark } ?: MinesDesign.DangerDark; on -> m.bg.copy(alpha = 0.85f); else -> m.ink2 })
                }
            }
        }
    }
}

/** Dark undo pill: 「已忽略「…」 ↶ 撤销」 floating over the bottom. */
@Composable
fun UndoBar(text: String, action: String = "撤销", onAction: () -> Unit, modifier: Modifier = Modifier) {
    // [Mines-38.9.6 i14] the shared glass toast (was a solid dark #2A2420 bar)
    val m = mines()
    GlassToast(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = RoundedCornerShape(26.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = MT.Body, color = m.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onAction).semantics { contentDescription = action }
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MinesIcon(WarmIcons.of("rotate-ccw")!!, null, tint = m.primaryText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(action, fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = m.primaryText, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Terracotta primary pill (#B5532F + white, 4.95:1). */
@Composable
fun WarmPrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
                    height: Dp = 48.dp, fontSize: Int = 15, description: String? = null) {
    val m = mines()
    Row(
        modifier.heightIn(min = height).clip(RoundedCornerShape(50))
            .background(if (enabled) m.primary else m.primary.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description ?: text; role = Role.Button }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { MinesIcon(icon, null, tint = m.onPrimary, modifier = Modifier.size((fontSize + 2).dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = MT.Button, fontWeight = FontWeight.SemiBold, color = m.onPrimary, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

/** Small GitHub card inside an X post: Chinese one-liner big, repo path small, ★ stars. */
@Composable
fun RepoMiniCard(repo: String, zh: String?, stars: String?, onClick: (() -> Unit)? = null) {
    val m = mines()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(m.surface2)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.openminis.app.R.drawable.ic_brand_github), contentDescription = "GitHub",
            modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (!zh.isNullOrBlank()) Text(zh, fontSize = MT.Caption, lineHeight = MT.BodyTightLH, fontWeight = FontWeight.Medium, color = m.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = if (zh.isNullOrBlank()) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(repo, fontSize = MT.Caption, fontFamily = FontFamily.Monospace, color = m.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!stars.isNullOrBlank()) { Spacer(Modifier.width(10.dp)); Text("★ $stars", fontSize = MT.Caption, color = m.ink2, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** Section label above a group card (16 sp SemiBold ink2) with an optional right-hand note/link. */
@Composable
fun WarmGroupLabel(title: String, meta: String? = null, onMeta: (() -> Unit)? = null) {
    val m = mines()
    Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = MT.Section, fontWeight = FontWeight.SemiBold, color = m.ink2, modifier = Modifier.weight(1f), maxLines = 1)
        if (meta != null) Text(meta, fontSize = MT.Sub, color = if (onMeta != null) m.primaryText else m.ink2, maxLines = 1, softWrap = false,
            modifier = (if (onMeta != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMeta).padding(horizontal = 6.dp, vertical = 8.dp) else Modifier))
    }
}

/** Thin divider inside a glass group, indented past the icon. */
@Composable
fun WarmDivider(start: Dp = 72.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = 16.dp).height(1.dp).background(mines().hairline))
}

/** [Mines-warm-36] Home bottom bar: 对话 (here) · 消息 (red count = 账号异常) · 灵感 (知识库) · 任务 (定时任务) · 应用 (账号连接). */
@Composable
fun WarmHomeNav(modifier: Modifier = Modifier, onTasks: () -> Unit, onTop: () -> Unit = {}) {
    val feed by com.openminis.app.plus.Digest.items.collectAsState()
    val patrol by com.openminis.app.plus.AccountPatrol.status.collectAsState()
    val todo = feed.count { it.tab == com.openminis.app.plus.Digest.Tab.ACCOUNT && !isResolved(it, patrol) }   // [Mines-38.8] 已解决 not counted
    fun icon(n: String) = WarmIcons.of(n)!!
    WarmNavBar(
        listOf(
            WarmNavItem("对话", icon("messages-square"), onClick = onTop),
            WarmNavItem("消息", icon("mail"), badge = todo) { PlusNav.go(PlusRoutes.DIGEST) },
            WarmNavItem("知识库", icon("book-open")) { PlusNav.go(PlusRoutes.KNOWLEDGE) },
            WarmNavItem("任务", icon("list-checks"), onClick = onTasks),
            WarmNavItem("应用", icon("layout-grid")) { PlusNav.go(Routes3912.APPS) },   // [Mines-38.9.12] 应用 hub (design v3 ⑥)
        ),
        current = 0, modifier = modifier,
    )
}

// ───────────────────────── [Mines-38.1] board-accurate home pieces ─────────────────────────

/** The user's own name for the greeting (「下午好，杨arling」); set from 设置 → personal card. */
object MinesProfile {
    private fun p(c: android.content.Context) = c.applicationContext.getSharedPreferences("mines_profile", android.content.Context.MODE_PRIVATE)
    fun name(c: android.content.Context): String = p(c).getString("name", "").orEmpty()
    fun setName(c: android.content.Context, v: String) { p(c).edit().putString("name", v.trim().take(20)).apply() }
}

/** 「全部 ›」 on the right of 最近对话: the topic filters that used to be a chip row (which overlapped the list). */
@Composable
fun ChatFilterMenu(all: List<com.openminis.app.data.db.ChatSessionEntity>) {
    val m = mines()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    com.openminis.app.plus.ChatOrganizer.init(ctx)
    val settings by com.openminis.app.plus.ChatOrganizer.settings.collectAsState()
    val filter by com.openminis.app.plus.ChatOrganizer.filter.collectAsState()
    val restored by com.openminis.app.plus.ChatOrganizer.restored.collectAsState()
    val manual by com.openminis.app.plus.ChatOrganizer.manual.collectAsState()
    val chips = androidx.compose.runtime.remember(all, settings, restored, manual) {
        if (settings.topicChips) com.openminis.app.plus.ChatOrganizer.chipCounts(all) else emptyList()
    }
    val archived = androidx.compose.runtime.remember(all, settings, restored, manual) { com.openminis.app.plus.ChatOrganizer.archivedCount(all) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val label = when (filter) {
        com.openminis.app.plus.ChatOrganizer.FILTER_ALL -> "全部"
        com.openminis.app.plus.ChatOrganizer.FILTER_ARCHIVED -> "已归档"
        else -> filter
    }
    Box {
        Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).clickable { open = true }
            .semantics { contentDescription = "筛选对话：$label" }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = MT.Caption, fontWeight = FontWeight.Medium, color = m.primaryText, maxLines = 1, softWrap = false)
            MinesIcon(WarmIcons.of("chevron-right")!!, null, tint = m.primaryText, modifier = Modifier.size(14.dp))
        }
        WarmMenu(open, onDismiss = { open = false }) {
            WarmMenuItem("全部", WarmIcons.of("messages-square"), selected = filter == com.openminis.app.plus.ChatOrganizer.FILTER_ALL) {
                open = false; com.openminis.app.plus.ChatOrganizer.setFilter(com.openminis.app.plus.ChatOrganizer.FILTER_ALL)
            }
            chips.forEach { (name, n) ->
                WarmMenuItem("$name  $n", WarmIcons.of("folder"), selected = filter == name) {
                    open = false; com.openminis.app.plus.ChatOrganizer.setFilter(name)
                }
            }
            if (archived > 0) WarmMenuItem("已归档  $archived", WarmIcons.of("archive"), selected = filter == com.openminis.app.plus.ChatOrganizer.FILTER_ARCHIVED) {
                open = false; com.openminis.app.plus.ChatOrganizer.setFilter(com.openminis.app.plus.ChatOrganizer.FILTER_ARCHIVED)
            }
        }
    }
}

/**
 * Warm popup menu (☰, 全部 ›, digest): [Mines-38.9.6 i12] the shared MinisMenu glass — ONE rounded surface. Before, a
 * Material DropdownMenu (shadowElevation 8 dp under a 45 % tint) + an inner gradient Column: the shadow's umbra showed
 * through as a square near-white block inside the rounded card (user shot 1000129911).
 */
@Composable
fun WarmMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    com.openminis.app.ui.components.MinisMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp), minWidth = 200.dp,
        content = content,
    )
}

@Composable
fun WarmMenuItem(text: String, icon: ImageVector?, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val m = mines()
    val fg = if (danger) (if (m.dark) MinesDesign.DangerDark else MinesDesign.Danger) else m.ink
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, fontSize = MT.Body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1) },
        leadingIcon = if (icon != null) { { MinesIcon(icon, null, tint = if (danger) fg else m.icon, modifier = Modifier.size(20.dp)) } } else null,
        trailingIcon = if (selected) { { MinesIcon(WarmIcons.of("check")!!, null, tint = m.icon, modifier = Modifier.size(18.dp)) } } else null,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
    )
}

@Composable
fun WarmMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(mines().hairline))
}

/**
 * Board 01 bottom input bar: 「＋ 开个新对话…」, a mic, and the terracotta live-voice button.
 * Tapping the bar or ＋ starts a new chat; the mic starts a new chat too (dictate there);
 * the terracotta button opens 实时语音.
 */
@Composable
fun WarmHomeInputBar(onNewChat: () -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier) {
    val m = mines()
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(0.dp, shape, clip = false, ambientColor = Color(0x223C2814), spotColor = Color(0x223C2814))
            // [Mines-38.9.6 i12] the shared input glass: tint ≥ 80 %, soft visible rim (≥ 3:1), sheen, outside-only halo
            .glassInput(shape, glassInputFill(), m.dark)
            .pressClickable(onNewChat)
            .heightIn(min = 51.dp).padding(start = 14.dp, end = 5.dp),  // [Mines-38.2] board 58 px x 0.874
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinesIcon(WarmIcons.of("plus")!!, "新对话", tint = m.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("开个新对话…", fontSize = MT.Body, color = m.ink3, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNewChat).semantics { contentDescription = "语音输入" },
            contentAlignment = Alignment.Center) {
            MinesIcon(WarmIcons.of("mic")!!, null, tint = m.ink2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(42.dp).clip(CircleShape).background(m.primary).clickable(onClick = onVoice)
            .semantics { contentDescription = "实时语音"; role = Role.Button }, contentAlignment = Alignment.Center) {
            MinesIcon(WarmIcons.of("audio-lines") ?: WarmIcons.of("mic")!!, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
    }
}

/** Light press feedback: clickable that scales to 0.97 while pressed (premium motion standard, user 16:07 #8). */
fun Modifier.pressClickable(onClick: () -> Unit): Modifier = this.composed {
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
}


/** [Mines-38.5] Opaque porcelain for the home input bar and bottom bar (user: 下面的列表不能透出来). */
@Composable
fun dockFill(): Color = minesGlassFill(strong = true)   // [Mines-38.8] input bar + nav are frosted glass over the fog (was solid porcelain)

/**
 * [Mines-38.5] Solid porcelain band behind the input bar + bottom bar, with a 28 dp fade on top, so
 * conversation rows scroll away under it instead of showing between / through the two bars.
 */
@Composable
fun WarmBottomDock(modifier: Modifier = Modifier, height: Dp = 168.dp) {
    val m = mines()
    val c = if (m.dark) MinesDesign.BgDark else Color(0xFFF6F0E9)
    // [Mines-38.9.1] user 2026-10-16: rows must not hard-cut behind the (now clearer) input bar — a soft fade of the
    // page colour from transparent to ~70 % over the band the bars sit on, so the list dissolves into the fog
    Box(modifier.fillMaxWidth().height(height).background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0f to Color.Transparent, 0.35f to c.copy(alpha = 0.35f), 1f to c.copy(alpha = 0.70f))))
}

/**
 * [Mines-38.9.1] user 2026-10-16 「新建对话框那里透度不够」: the home input bar is clear frosted glass (fog shows through),
 * following 外观调节 › 玻璃透明度; dark ~40 %, light peach ~26 %, plus a thin top highlight (see WarmHomeInputBar).
 */
@Composable
fun inputGlass(): Color {
    if (true) return glassInputFill()   // [Mines-38.9.6 i12] one input glass (floor + global slider)
    val m = mines()
    if (wallpaperOn(home = true)) return minesGlassFill(strong = true)   // over a photo: stay readable
    val a = (if (m.dark) 0.40f else 0.26f) * MinesLook.glassScale(m.dark)
    return GlassTint.of(m.dark).copy(alpha = a.coerceIn(0.10f, 0.95f))
}

package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One revealed action behind a swipeable row. */
data class SwipeRowAction(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)


/**
 * [T-android-swipe-row-actions] Swipe-LEFT-to-reveal action buttons behind a
 * list row, for lists that ALSO support long-press drag-to-reorder.
 *
 * Why not SwipeToDismissBox: the two existing usages in this app
 * (ModelGroupsScreen, MountedFoldersScreen) wire `currentValue == EndToStart`
 * straight to a delete call, so the row is destroyed the moment the swipe
 * passes its threshold — no confirmation, no undo, and no way to offer a
 * second (non-destructive) action like Edit. A reveal model keeps the row
 * alive, shows real buttons the user must then tap, and lets the caller run
 * its existing confirmation dialog.
 *
 * Gesture coexistence with drag-to-reorder is the delicate part. Both
 * gestures start as a press on the same row, so they are separated by AXIS,
 * not by priority:
 *  - `anchoredDraggable(orientation = Horizontal)` only claims pointer events
 *    once movement crosses the touch slop HORIZONTALLY. A vertical drag never
 *    reaches that condition, so it falls through to the reorderable library's
 *    long-press handle untouched.
 *  - The reorder handle in these lists is engaged by LONG PRESS (or by a
 *    dedicated handle in ModelGroupsScreen), which is a time-based trigger
 *    rather than a horizontal-movement one, so neither gesture starves the
 *    other.
 * The net effect: horizontal = reveal, vertical/long-press = reorder, and the
 * caller keeps its existing reorder wiring unchanged.
 *
 * [actions] renders right-to-left in the order given (first action sits
 * closest to the row's trailing edge). Pass an empty list to make the row
 * non-swipeable — used for entries the caller has decided must not expose
 * destructive actions, so there is no fake affordance to discover.
 */
@Composable
fun SwipeRowActions(
    actions: List<SwipeRowAction>,
    modifier: Modifier = Modifier,
    actionWidth: androidx.compose.ui.unit.Dp = 88.dp,
    /** Opaque fill for the sliding row — must match the surface the row sits on,
     *  or the action buttons behind it show through at rest. */
    rowColor: Color = SectionDesign.cardColor(),
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { (actionWidth * actions.size).toPx() }
    val flingPx = with(density) { 400.dp.toPx() }

    // [Mines-38.9.9 i27] ONE gesture owner for every swipe row (服务商 / 模型组 / 备份): the OUTER, non-moving Box watches
    // the pointer in the Initial pass (before any child) and only consumes once the finger has moved past touch slop
    // horizontally. So: children (row click, long-press reorder handle, nested clickables) can no longer steal the swipe
    // (smoke 39197873667 服务商: drag ended with off=0), and a tap that never moves is NOT consumed, so the 编辑 / 删除
    // buttons uncovered by the slid row get their own clicks (the old detector sat before .offset on the foreground,
    // whose un-moved bounds covered the buttons and swallowed the 删除 tap → no dialog).
    val offset = remember(revealPx) { Animatable(0f) }
    fun settle(open: Boolean) = scope.launch {
        offset.animateTo(if (open) -revealPx else 0f)
        if (open) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var rowHeightPx by remember { mutableIntStateOf(0) }
    val rowHeight = with(density) { rowHeightPx.toDp() }

    // Opaque slid row: the card colour is translucent glass, so it is laid over the theme's opaque reading paper
    // (light #FFFCF9 / dark #24201F). [i27] (was laid over a fixed page tint / Transparent surface → greyish in light.)
    val m = com.openminis.app.ui.plus.mines()
    val paper = com.openminis.app.ui.plus.minesReadingSurface()
    val solidRow = SwipeRevealCore.solidRowColor(rowColor, paper, m.dark)
    val off = offset.value
    val open = SwipeRevealCore.showActions(off)
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(revealPx) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val startOff = offset.value
                    val onActions = SwipeRevealCore.hitsActions(down.position.x, size.width.toFloat(), startOff)
                    val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var dx = 0f; var dy = 0f; var dragging = false; var cur = startOff
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (dragging) {
                                ch.consume()
                                val v = vt.calculateVelocity().x
                                val o = SwipeRevealCore.settleOpen(cur, v, revealPx, flingPx)
                                SwipeRevealCore.probe = "end off=${cur.roundToInt()} v=${v.roundToInt()} reveal=${revealPx.roundToInt()} open=$o"
                                settle(o)
                            } else if (SwipeRevealCore.showActions(startOff) && !onActions) {
                                // tap on the slid row while open = just close it (do not also open the row)
                                ch.consume(); SwipeRevealCore.probe = "tapclose off=${startOff.roundToInt()}"; settle(false)
                            } else if (onActions) {
                                SwipeRevealCore.probe = "actiontap x=${down.position.x.roundToInt()} off=${startOff.roundToInt()}"
                            }
                            break
                        }
                        val d = ch.positionChangeIgnoreConsumed()
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        if (!dragging) {
                            dx += d.x; dy += d.y
                            when (SwipeRevealCore.claim(dx, dy, slop, ch.uptimeMillis - down.uptimeMillis, longPressMs)) {
                                SwipeRevealCore.Claim.VERTICAL, SwipeRevealCore.Claim.LONG_PRESS -> break   // scroll / reorder own it
                                SwipeRevealCore.Claim.HORIZONTAL -> {
                                    dragging = true; ch.consume()
                                    cur = (startOff + dx).coerceIn(-revealPx, 0f)
                                    SwipeRevealCore.probe = "start off=${startOff.roundToInt()}"
                                    val t = cur; scope.launch { offset.snapTo(t) }
                                }
                                SwipeRevealCore.Claim.UNDECIDED -> Unit
                            }
                        } else {
                            ch.consume()
                            cur = (cur + d.x).coerceIn(-revealPx, 0f)
                            val t = cur; scope.launch { offset.snapTo(t) }
                        }
                    }
                }
            },
    ) {
        if (open) Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .alpha(SwipeRevealCore.actionAlpha(off, revealPx))
                .then(if (rowHeightPx > 0) Modifier.height(rowHeight) else Modifier),
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier
                        .width(actionWidth)
                        .fillMaxHeight()
                        .background(action.containerColor)
                        .clickable {
                            // Run the action first (e.g. show the delete confirmation), then close the row.
                            SwipeRevealCore.probe = "action ${action.label}"
                            action.onClick()
                            settle(false)
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(action.icon, contentDescription = action.label, tint = action.contentColor)
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.contentColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // Foreground: no gesture code of its own. [Mines-38.9.10 i28] it NARROWS by the revealed width instead of sliding:
        // smoke 39206653353 (cmp-01/02) showed the slid row as blank + chevron — the content moved left by 2 × 88 dp, which
        // is wider than the title's left inset, so the whole title went off the clipped edge. Now the row is re-measured at
        // (width − revealed): title / subtitle stay at their place and ellipsize, the chevron sits next to the buttons, and
        // the uncovered buttons are outside the measured row, so they are hit-tested directly.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeightPx = it.height }
                .layout { measurable, constraints ->
                    val full = constraints.maxWidth
                    val w = SwipeRevealCore.narrowWidth(full, offset.value)
                    val pl = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                    layout(full, pl.height) { pl.place(0, 0) }
                }
                .background(if (open) solidRow else rowColor),
        ) {
            content()
        }
    }
}


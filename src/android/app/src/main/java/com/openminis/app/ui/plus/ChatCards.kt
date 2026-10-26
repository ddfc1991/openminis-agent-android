package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * [Mines-38.6] item E: an AI reply drawn as one design card although the chat list holds it as several items (one per
 * markdown block). [seg] bit 1 = first slice (rounded top), bit 2 = last slice (rounded bottom); a middle slice has
 * straight edges. Each slice paints only its own background (reaching 1 dp past its edge to bridge the list's 2 dp
 * item spacing), so text selection, its bounds registry and the list's per-item measuring are unchanged; the only
 * layout change is a fixed inner padding (12 dp sides, 10 dp at the card's top / bottom).
 */
@Composable
fun ReplyCardSlice(seg: Int, soft: Boolean = false, content: @Composable () -> Unit) {
    val m = mines()
    val first = seg and 1 != 0
    val last = seg and 2 != 0
    // [Mines-38.9.14.2] soft (聊天主页): the plain card glass (the most transparent tint that keeps ink2 ≥ 4.5:1 when 自动),
    // no hairline — a faint frosted panel instead of a framed box under every reply
    val fill = minesGlassFill(strong = !soft)
    val line = if (soft) androidx.compose.ui.graphics.Color.Transparent else m.glassBorder
    Box(
        Modifier.fillMaxWidth()
            .drawBehind {
                // [38.9.11 i32] a slice shorter than its corners drew crossed arcs (「‹ ›」 artifacts, user 08:54): clamp
                val r = minOf(18.dp.toPx(), size.height / 2f, size.width / 2f).coerceAtLeast(0f)
                if (size.height < 2f) return@drawBehind
                val bridge = 1.dp.toPx()
                val top = if (first) 0f else -bridge
                val bottom = if (last) size.height else size.height + bridge
                val rr = RoundRect(0f, top, size.width, bottom,
                    topLeftCornerRadius = if (first) CornerRadius(r) else CornerRadius.Zero,
                    topRightCornerRadius = if (first) CornerRadius(r) else CornerRadius.Zero,
                    bottomRightCornerRadius = if (last) CornerRadius(r) else CornerRadius.Zero,
                    bottomLeftCornerRadius = if (last) CornerRadius(r) else CornerRadius.Zero)
                val path = Path().apply { addRoundRect(rr) }
                drawPath(path, fill)
                // hairline on the outer edges only (sides always, top on the first slice, bottom on the last)
                val w = 1.dp.toPx()
                val sw = Stroke(w)
                drawLine(line, androidx.compose.ui.geometry.Offset(w / 2, if (first) r else top), androidx.compose.ui.geometry.Offset(w / 2, if (last) size.height - r else bottom), w)
                drawLine(line, androidx.compose.ui.geometry.Offset(size.width - w / 2, if (first) r else top), androidx.compose.ui.geometry.Offset(size.width - w / 2, if (last) size.height - r else bottom), w)
                if (first) {
                    val p = Path().apply {
                        moveTo(w / 2, r); arcTo(androidx.compose.ui.geometry.Rect(w / 2, w / 2, 2 * r, 2 * r), 180f, 90f, false)
                        lineTo(size.width - r, w / 2); arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r, w / 2, size.width - w / 2, 2 * r), 270f, 90f, false)
                    }
                    drawPath(p, line, style = sw)
                }
                if (last) {
                    val h = size.height
                    val p = Path().apply {
                        moveTo(w / 2, h - r); arcTo(androidx.compose.ui.geometry.Rect(w / 2, h - 2 * r, 2 * r, h - w / 2), 180f, -90f, false)
                        lineTo(size.width - r, h - w / 2); arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r, h - 2 * r, size.width - w / 2, h - w / 2), 90f, -90f, false)
                    }
                    drawPath(p, line, style = sw)
                }
            }
            .padding(start = 12.dp, end = 12.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 10.dp else 0.dp),
    ) { content() }
}

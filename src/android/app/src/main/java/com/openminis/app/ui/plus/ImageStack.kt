package com.openminis.app.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.openminis.app.plus.ImageStackCore

/**
 * [Mines-38.9.11 b] Hark-style picture stack (user 2026-10-20 08:55, ref Hark chat): several images = a fan of rounded
 * cards, the first one in front and straight, the others behind it tilted a few degrees left / right with a soft shadow;
 * one image = one straight card. Tap any card → full-screen pager ([onOpen] with its index). Used in the user's chat
 * bubble and in the input bar before sending ([onRemoveLast] adds the small × there). Geometry in [ImageStackCore].
 */
@Composable
fun FannedImageStack(
    models: List<Any>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 104.dp,
    onRemoveLast: (() -> Unit)? = null,
) {
    if (models.isEmpty()) return
    val m = mines()
    val aspects = remember(models) { mutableStateMapOf<Int, Float>() }   // width / height per card, from the loaded image
    val n = models.size
    val shown = minOf(n, ImageStackCore.MAX_SHOWN)
    val w = cardWidth.value
    val layout = ImageStackCore.layout(shown, w, (0 until shown).map { aspects[it] ?: ImageStackCore.DEFAULT_ASPECT })
    val radius = RoundedCornerShape(if (w < 70f) 10.dp else 16.dp)
    Box(modifier.size(layout.width.dp, layout.height.dp).semantics(mergeDescendants = true) {
        contentDescription = if (n == 1) "图片，点开看大图" else "$n 张图片，点开看大图"
    }) {
        // back to front: the first image is drawn last (on top)
        for (slot in layout.cards.sortedBy { it.z }) {
            val i = slot.index
            Box(Modifier.zIndex(slot.z.toFloat()).offset(slot.x.dp, slot.y.dp).size(slot.w.dp, slot.h.dp).rotate(slot.rotation)
                .shadow(if (w < 70f) 4.dp else 10.dp, radius, clip = false).clip(radius).background(m.surface2)
                .border(0.5.dp, Color.White.copy(alpha = if (m.dark) 0.18f else 0.55f), radius)
                .clickable { onOpen(i) }) {
                AsyncImage(models[i], contentDescription = null, contentScale = ContentScale.Crop,
                    onSuccess = { st ->
                        val d = st.painter.intrinsicSize
                        if (d.width > 0f && d.height > 0f) aspects[i] = ImageStackCore.clampAspect(d.width / d.height)
                    },
                    modifier = Modifier.size(slot.w.dp, slot.h.dp))
                if (i == 0 && n > shown) Text("+${n - shown}", fontSize = MT.Meta, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
        if (onRemoveLast != null) Box(Modifier.align(Alignment.TopEnd).zIndex(99f).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
            .clickable { onRemoveLast() }.semantics { contentDescription = "移除最后一张图片" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * [Mines-38.9.11.1] Hark-style images in a sent message: 1 = one larger rounded picture, 2–4 = 2-column grid, 5+ = 3-column
 * grid (≤ 6 tiles, the last says +N). Tap → full-screen viewer at that index. Geometry in [com.openminis.app.plus.ImageGridCore].
 */
@Composable
fun ImageGrid(models: List<Any>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (models.isEmpty()) return
    val m = mines()
    val G = com.openminis.app.plus.ImageGridCore
    val n = models.size
    val aspect = remember(models) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val border = Color.White.copy(alpha = if (m.dark) 0.14f else 0.5f)
    if (n == 1) {
        val shape = RoundedCornerShape(16.dp)
        Box(modifier.size(G.SINGLE_W_DP.dp, G.singleHeightDp(aspect.value).dp).clip(shape).background(m.surface2).border(0.5.dp, border, shape)
            .clickable { onOpen(0) }.semantics { contentDescription = "图片，点开看大图" }) {
            AsyncImage(models[0], contentDescription = null, contentScale = ContentScale.Crop,
                onSuccess = { st -> val d = st.painter.intrinsicSize; if (d.width > 0f && d.height > 0f) aspect.value = d.width / d.height },
                modifier = Modifier.matchParentSize())
        }
        return
    }
    val cols = G.columns(n); val tile = G.tileDp(n).dp; val gap = G.GAP_DP.dp
    val shown = G.shown(n); val more = G.more(n)
    val shape = RoundedCornerShape(if (cols == 3) 10.dp else 12.dp)
    androidx.compose.foundation.layout.Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$n 张图片，点开看大图" },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
        for (r in 0 until G.rows(n)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    if (i >= shown) break
                    Box(Modifier.size(tile).clip(shape).background(m.surface2).border(0.5.dp, border, shape).clickable { onOpen(i) }) {
                        AsyncImage(models[i], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        if (i == shown - 1 && more > 0) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Text("+$more", fontSize = MT.Body, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * [Mines-38.9.11.1] Hark-style composer row: small rounded-square thumbnails above the input, each with its own small ×.
 */
@Composable
fun ComposerImageRow(models: List<Any>, onOpen: (Int) -> Unit, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (models.isEmpty()) return
    val m = mines()
    val G = com.openminis.app.plus.ImageGridCore
    val shape = RoundedCornerShape(10.dp)
    val border = Color.White.copy(alpha = if (m.dark) 0.14f else 0.5f)
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp)) {
        items(models.size) { i ->
            Box(Modifier.size((G.COMPOSER_TILE_DP + 6f).dp)) {
                Box(Modifier.align(Alignment.BottomStart).size(G.COMPOSER_TILE_DP.dp).clip(shape).background(m.surface2).border(0.5.dp, border, shape)
                    .clickable { onOpen(i) }.semantics { contentDescription = "第 ${i + 1} 张图片，点开看大图" }) {
                    AsyncImage(models[i], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
                Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(if (m.dark) Color(0xE6302A2E) else Color(0xCC1F1A1C))
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable { onRemove(i) }.semantics { contentDescription = "移除第 ${i + 1} 张图片" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

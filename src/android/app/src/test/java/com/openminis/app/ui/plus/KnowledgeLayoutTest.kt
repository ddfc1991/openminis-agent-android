package com.openminis.app.ui.plus

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.8 v5d] 知识库 脑图: an open topic's sub-chips never overlap its pill, the face or other topics, and stay on screen. */
class KnowledgeLayoutTest {
    private fun rectAt(c: Offset, s: Size) = Rect(Offset(c.x - s.width / 2f, c.y - s.height / 2f), s)

    @Test fun rightTopicChipsDoNotOverlapAndStayOnScreen() {
        // 360 dp x 600 dp map at density 3, zoom 0.9 → world half size
        val d = 3f
        val half = Size(540f / 0.9f, (900f - 78f) / 0.9f)
        val bp = Offset(118f * d, 0f)
        val parent = rectAt(bp, Size(80f * d, 32f * d))
        val face = Rect(Offset(-46f * d, -46f * d), Size(92f * d, 92f * d))
        val others = listOf(rectAt(Offset(0f, -210f * d), Size(110f * d, 32f * d)), rectAt(Offset(-118f * d, 0f), Size(110f * d, 32f * d)),
            rectAt(Offset(0f, 210f * d), Size(70f * d, 32f * d)))
        val sizes = listOf(Size(190f * d, 26f * d), Size(170f * d, 26f * d), Size(120f * d, 26f * d), Size(150f * d, 26f * d))
        val obstacles = others + face
        val placed = KnowledgeLayout.placeChips(bp, Offset(1f, 0f), parent, sizes, obstacles, half, 10f * d, 8f * d)
        assertEquals(sizes.size, placed.size)
        val rects = placed.mapIndexed { i, c -> rectAt(c, sizes[i]) }
        rects.forEachIndexed { i, r ->
            assertTrue("chip $i on screen $r", r.left >= -half.width && r.right <= half.width && r.top >= -half.height && r.bottom <= half.height)
            assertEquals("chip $i vs parent", 0f, KnowledgeLayout.overlap(parent, r))
            (obstacles).forEach { o -> assertEquals("chip $i vs $o", 0f, KnowledgeLayout.overlap(o, r)) }
            rects.forEachIndexed { j, r2 -> if (j != i) assertEquals("chip $i vs chip $j", 0f, KnowledgeLayout.overlap(r, r2)) }
        }
    }

    @Test fun topTopicChipsGoOutward() {
        val half = Size(600f, 900f)
        val bp = Offset(0f, -500f)
        val parent = rectAt(bp, Size(240f, 96f))
        val sizes = listOf(Size(300f, 78f), Size(300f, 78f))
        val placed = KnowledgeLayout.placeChips(bp, Offset(0f, -1f), parent, sizes, emptyList(), half, 30f, 24f)
        placed.forEach { assertTrue("chip above the topic, not toward the centre: $it", it.y <= bp.y + 1f) }
    }

    @Test fun clampKeepsChipInside() {
        val c = KnowledgeLayout.clampCentre(Offset(1000f, -1000f), Size(200f, 50f), Size(300f, 400f), 10f)
        assertEquals(190f, c.x, 0.01f); assertEquals(-365f, c.y, 0.01f)
    }
}

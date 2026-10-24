package com.openminis.app.plus

import com.openminis.app.ui.components.SwipeRevealCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.9] swipe rows: actions never drawn at rest (no bleed-through under glass), fade in while revealing. */
class SwipeRevealCoreTest {
    @Test fun hiddenAtRest() {
        assertFalse(SwipeRevealCore.showActions(0f))
        assertFalse(SwipeRevealCore.showActions(-0.5f))
        assertFalse(SwipeRevealCore.showActions(5f))
        assertFalse(SwipeRevealCore.showActions(Float.NaN))
        assertEquals(0f, SwipeRevealCore.actionAlpha(0f, 200f), 0f)
    }
    @Test fun shownWhenPulled() {
        assertTrue(SwipeRevealCore.showActions(-2f))
        assertEquals(0.5f, SwipeRevealCore.actionAlpha(-50f, 200f), 0.001f)
        assertEquals(1f, SwipeRevealCore.actionAlpha(-150f, 200f), 0f)
        assertEquals(0f, SwipeRevealCore.actionAlpha(-50f, 0f), 0f)
    }
    @Test fun settleRule() {
        // smoke 39190432130: ~340 px slow swipe on a 528 px reveal must open
        assertTrue(SwipeRevealCore.settleOpen(-320f, -300f, 528f, 1200f))
        assertTrue(SwipeRevealCore.settleOpen(-190f, 0f, 528f, 1200f))
        assertFalse(SwipeRevealCore.settleOpen(-150f, 0f, 528f, 1200f))
        assertTrue(SwipeRevealCore.settleOpen(-60f, -1500f, 528f, 1200f))     // quick left fling
        assertFalse(SwipeRevealCore.settleOpen(-500f, 1500f, 528f, 1200f))    // right fling closes
        assertFalse(SwipeRevealCore.settleOpen(-500f, 0f, 0f, 1200f))
    }
    @Test fun claimAxis() {
        assertEquals(SwipeRevealCore.Claim.UNDECIDED, SwipeRevealCore.claim(3f, 2f, 8f, 50, 400))
        assertEquals(SwipeRevealCore.Claim.HORIZONTAL, SwipeRevealCore.claim(-12f, 3f, 8f, 50, 400))
        assertEquals(SwipeRevealCore.Claim.VERTICAL, SwipeRevealCore.claim(4f, 20f, 8f, 50, 400))
        assertEquals(SwipeRevealCore.Claim.LONG_PRESS, SwipeRevealCore.claim(1f, 1f, 8f, 450, 400))
    }
    @Test fun actionHit() {
        assertFalse(SwipeRevealCore.hitsActions(1000f, 1000f, 0f))          // closed: nothing under the finger but the row
        assertTrue(SwipeRevealCore.hitsActions(900f, 1000f, -475f))
        assertFalse(SwipeRevealCore.hitsActions(400f, 1000f, -475f))
    }
    @Test fun lightRowNotGrey() {
        val paper = androidx.compose.ui.graphics.Color(0xFFFFFCF9)
        val grey = androidx.compose.ui.graphics.Color(0x8C808080)
        val c = SwipeRevealCore.solidRowColor(grey, paper, false)
        assertEquals(1f, c.alpha, 0f)
        assertTrue(0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue > 0.85f)
    }
    @Test fun slidRowNarrowsSoTitleStays() {
        // [38.9.10] 1080 px row, 2 actions × 88 dp @3x = 528 px revealed: the foreground keeps 552 px (title visible), not 0
        assertEquals(552, SwipeRevealCore.narrowWidth(1080, -528f))
        assertEquals(1080, SwipeRevealCore.narrowWidth(1080, 0f))
        assertEquals(0, SwipeRevealCore.narrowWidth(100, -528f))
        assertEquals(1080, SwipeRevealCore.narrowWidth(1080, 30f))
    }
}

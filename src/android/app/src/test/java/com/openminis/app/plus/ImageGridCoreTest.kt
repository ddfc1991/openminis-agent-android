package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGridCoreTest {
    @Test fun columnsByCount() {
        assertEquals(1, ImageGridCore.columns(1))
        for (n in 2..4) assertEquals(2, ImageGridCore.columns(n))
        for (n in 5..12) assertEquals(3, ImageGridCore.columns(n))
    }
    @Test fun moreBadge() {
        assertEquals(0, ImageGridCore.more(6)); assertEquals(1, ImageGridCore.more(7)); assertEquals(4, ImageGridCore.more(10))
        assertEquals(6, ImageGridCore.shown(10)); assertEquals(3, ImageGridCore.shown(3))
    }
    @Test fun rows() {
        assertEquals(1, ImageGridCore.rows(2)); assertEquals(2, ImageGridCore.rows(3)); assertEquals(2, ImageGridCore.rows(4))
        assertEquals(2, ImageGridCore.rows(5)); assertEquals(2, ImageGridCore.rows(9)); assertEquals(0, ImageGridCore.rows(0))
    }
    @Test fun sizes() {
        assertEquals(200f, ImageGridCore.gridWidthDp(1), 0.01f)
        assertEquals(2 * 104f + 4f, ImageGridCore.gridWidthDp(2), 0.01f)
        assertEquals(3 * 76f + 8f, ImageGridCore.gridWidthDp(7), 0.01f)
        assertEquals(2 * 104f + 4f, ImageGridCore.gridHeightDp(4), 0.01f)
        // single: tall photo clamped to 3:4, wide to 4:3
        assertEquals(200f / 0.75f, ImageGridCore.singleHeightDp(0.3f), 0.01f)
        assertEquals(150f, ImageGridCore.singleHeightDp(2f), 0.01f)
        assertTrue(ImageGridCore.gridWidthDp(9) < 260f)   // fits a phone bubble
    }
}

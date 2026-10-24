package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOnDemandCoreTest {
    @Test fun fit() {
        assertNull(ImageOnDemandCore.fit(1200, 800))
        assertEquals(1568 to 1176, ImageOnDemandCore.fit(4000, 3000))
        assertEquals(87 to 1568, ImageOnDemandCore.fit(1264, 22781))
        assertNull(ImageOnDemandCore.fit(0, 10))
    }
    @Test fun names() {
        assertEquals("IMG_1.1568.jpg", ImageOnDemandCore.viewName("IMG_1.png"))
        assertEquals("photo.1568.jpg", ImageOnDemandCore.viewName("photo"))
        assertEquals("2.4 MB", ImageOnDemandCore.sizeText(2_500_000))
        assertEquals("98 KB", ImageOnDemandCore.sizeText(100_000))
    }
    @Test fun markers() {
        val v = ImageOnDemandCore.marker("a.png", "/var/minis/attachments/uploads/a.png", "/var/minis/attachments/uploads/a.1568.jpg", "image/png", 3_000_000, 4000, 3000, true)
        assertTrue(v.contains("read_image /var/minis/attachments/uploads/a.1568.jpg")); assertTrue(v.contains("原图 /var/minis/attachments/uploads/a.png"))
        assertTrue(v.length < 200)
        val t = ImageOnDemandCore.marker("a.png", "/p/a.png", "/p/a.png", "image/png", 1000, 0, 0, false)
        assertFalse(t.contains("read_image")); assertTrue(t.contains("不能看图片")); assertFalse(t.contains("原图"))
    }
}

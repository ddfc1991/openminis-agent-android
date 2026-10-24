package com.openminis.app.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.4] issue #2 Bug 4 wording. */
class A11yCheckTest {
    @Test fun onAndRunning() { val r = A11yCheck.verdict(true, true, false); assertTrue(r.ok); assertTrue(r.detail.contains("在运行")) }
    @Test fun switchOnButRestricted() {
        val r = A11yCheck.verdict(true, false, true)
        assertFalse(r.ok); assertTrue(r.detail.contains("受限设置")); assertTrue(r.detail.contains("允许受限制的设置"))
    }
    @Test fun switchOnNotRunning() { val r = A11yCheck.verdict(true, false, false); assertFalse(r.ok); assertFalse(r.detail.contains("已开启")) }
    @Test fun offRestricted() { assertTrue(A11yCheck.verdict(false, false, true).detail.contains("允许受限制的设置")) }
    @Test fun off() { assertTrue(A11yCheck.verdict(false, false, false).detail.startsWith("未开启")) }
}

package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.14.1 net_drop] connection-drop classification, resume plan, backoff, cause from the device state */
class NetDropCoreTest {
    private fun snap(screenOn: Boolean = true, fg: Boolean = true, net: Long = -1, vpn: Long = -1, idle: Long = 1_000,
                     exempt: Boolean = true, fgs: Boolean = true, restricted: Boolean = false, blocked: Boolean = false) =
        NetDropCore.Snap(screenOn, fg, net, vpn, idle, true, "wifi", exempt, fgs, restricted, blocked)

    @Test fun phoneErrorIsADrop() {
        assertTrue(NetDropCore.isDrop(java.net.SocketException("Software caused connection abort")))
        assertTrue(NetDropCore.isDrop(java.io.IOException("read failed: ECONNABORTED (Software caused connection abort)")))
        assertTrue(NetDropCore.isDrop(java.net.SocketException("Connection reset")))
        assertTrue(NetDropCore.isDrop(java.io.IOException("unexpected end of stream on https://api.example.com/...")))
        assertTrue(NetDropCore.isDrop(java.net.SocketException("Broken pipe")))
        assertTrue(NetDropCore.isDrop(java.net.UnknownHostException("Unable to resolve host \"api.sensenova.cn\": No address associated with hostname")))
    }

    @Test fun wrappedCauseIsFound() {
        val e = com.openminis.app.data.model.LLMError.NetworkError(java.net.SocketException("Software caused connection abort"))
        assertTrue(NetDropCore.isDrop(e))
        assertTrue(NetDropCore.isDrop(RuntimeException("outer", java.io.IOException("x", java.net.SocketException("Connection reset by peer")))))
    }

    @Test fun otherErrorsAreNotDrops() {
        assertFalse(NetDropCore.isDrop(java.io.IOException("HTTP 401 Unauthorized")))
        assertFalse(NetDropCore.isDrop(RuntimeException("Rate limited: too many requests")))
        assertFalse(NetDropCore.isDrop(java.net.SocketTimeoutException("timeout")))
        assertFalse(NetDropCore.isDrop(null))
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(2_000L, NetDropCore.backoffMs(0))
        assertEquals(4_000L, NetDropCore.backoffMs(1))
        assertEquals(8_000L, NetDropCore.backoffMs(2))
        assertEquals(16_000L, NetDropCore.backoffMs(3))
        assertEquals(30_000L, NetDropCore.backoffMs(4))
        assertEquals(30_000L, NetDropCore.backoffMs(20))
    }

    @Test fun planResumesThenSwitchesOrGivesUp() {
        assertEquals(NetDropCore.Plan.RESUME, NetDropCore.plan(0, 0))
        assertEquals(NetDropCore.Plan.RESUME, NetDropCore.plan(2, 2))
        // three drops while the network never went away: this provider / path is the problem
        assertEquals(NetDropCore.Plan.SWITCH, NetDropCore.plan(3, 3))
        // the network kept going away: keep waiting up to MAX_RETRIES, then give up (switching cannot help)
        assertEquals(NetDropCore.Plan.RESUME, NetDropCore.plan(7, 0))
        assertEquals(NetDropCore.Plan.GIVE_UP, NetDropCore.plan(NetDropCore.MAX_RETRIES, 1))
    }

    @Test fun causeFromDeviceState() {
        assertEquals(NetDropCore.CAUSE_VPN, NetDropCore.cause(snap(vpn = 3_000)))
        assertEquals(NetDropCore.CAUSE_NET, NetDropCore.cause(snap(net = 5_000)))
        assertEquals(NetDropCore.CAUSE_FREEZE, NetDropCore.cause(snap(blocked = true)))
        assertEquals(NetDropCore.CAUSE_FREEZE, NetDropCore.cause(snap(restricted = true)))
        assertEquals(NetDropCore.CAUSE_FREEZE, NetDropCore.cause(snap(screenOn = false, fg = false, exempt = false)))
        assertEquals(NetDropCore.CAUSE_IDLE, NetDropCore.cause(snap(idle = 60_000)))
        assertEquals(NetDropCore.CAUSE_UNKNOWN, NetDropCore.cause(snap()))
        // an old network change (2 min ago) does not count
        assertEquals(NetDropCore.CAUSE_UNKNOWN, NetDropCore.cause(snap(net = 120_000)))
        assertTrue(NetDropCore.describe(snap(screenOn = false, fg = false)).contains("screen=off app=bg"))
    }

    @Test fun statusLine() { assertEquals("网络断了，等网络恢复后继续", NetDropCore.LINE) }
}

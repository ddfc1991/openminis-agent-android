package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.6 i17] one failed path never says 「服务器挂了」. */
class ReachCoreTest {
    private fun v(d: Boolean?, p: Boolean?, ok: Int = 0, tot: Int = 0) = ReachCore.verdict(ReachCore.Paths(d, p, ok, tot, "连接超时", "代理拒绝"))

    @Test fun directOk() = assertEquals(ReachCore.Kind.OK_DIRECT, v(true, null).kind)
    @Test fun proxyOk() { val x = v(false, true); assertEquals(ReachCore.Kind.OK_PROXY, x.kind); assertTrue(x.reachable) }
    @Test fun phonePath() {
        val x = v(false, false, 3, 3)
        assertEquals(ReachCore.Kind.PHONE_PATH, x.kind); assertFalse(x.serverDown); assertTrue(x.text.startsWith("手机网络到不了"))
    }
    @Test fun downOnlyWhenOutsideAgrees() {
        assertTrue(v(false, false, 0, 3).serverDown)
        assertTrue(v(false, null, 0, 3).serverDown)
    }
    @Test fun singlePathNeverDown() {
        assertFalse(v(false, null).serverDown)               // no outside result
        assertFalse(v(false, false, 0, 1).serverDown)        // one node is not enough
        assertEquals(ReachCore.Kind.UNKNOWN, v(false, false).kind)
        assertTrue(v(false, false).text.contains("不能说服务器挂了"))
    }
    @Test fun parse() {
        val j = """{"us1.node.check-host.net":[{"time":0.12,"address":"1.2.3.4"}],"de1.node.check-host.net":[{"error":"Connection timed out"}],"jp1.node.check-host.net":null}"""
        assertEquals(Triple(1, 2, 1), ReachCore.parseCheckHost(j))
        assertEquals(Triple(0, 0, 0), ReachCore.parseCheckHost("nope"))
    }
}

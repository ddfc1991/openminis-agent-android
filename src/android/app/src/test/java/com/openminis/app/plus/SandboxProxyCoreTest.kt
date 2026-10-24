package com.openminis.app.plus

import com.openminis.app.plus.SandboxProxyCore.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.2] the passing local proxy reaches the sandbox always; 严格代理 blocks instead of going direct. */
class SandboxProxyCoreTest {
    @Test fun order() {
        assertEquals(Way.SPLIT, SandboxProxyCore.choose(true, 7890, true, true, true))
        assertEquals(Way.LOCAL, SandboxProxyCore.choose(false, 7890, false, false, false))   // 分流代理 off: still the local proxy
        assertEquals(Way.LOCAL, SandboxProxyCore.choose(false, 7898, true, true, true))
        assertEquals(Way.SYSTEM, SandboxProxyCore.choose(false, null, true, true, true))
        assertEquals(Way.BLOCK, SandboxProxyCore.choose(false, null, true, false, true))
        assertEquals(Way.BLOCK, SandboxProxyCore.choose(false, null, false, false, true))
        assertEquals(Way.DIRECT, SandboxProxyCore.choose(false, null, false, false, false))
        assertTrue(SandboxProxyCore.BLOCK_MSG.contains("严格代理"))
    }

    @Test fun probeIsRealOutboundAndWired() {
        fun src(rel: String) = listOf(File("src/main/java/com/openminis/app/$rel"), File("app/src/main/java/com/openminis/app/$rel")).first { it.exists() }.readText()
        // a port that only listens never becomes "best": the probe fetches generate_204 THROUGH the port
        assertTrue(src("plus/LocalProxy.kt").contains("generate_204\", 204"))
        val net = src("plus/SandboxNet.kt")
        assertTrue(net.contains("SandboxProxyCore.choose(") && net.contains("SandboxProxyCore.BLOCK_URL") && net.contains("strict_proxy"))
    }
}

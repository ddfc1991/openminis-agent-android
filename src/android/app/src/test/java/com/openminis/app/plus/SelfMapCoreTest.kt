package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

/** [Mines-38.9.8] 自我地图: ports from the server's own notes, compact prompt, tool detail; demo addresses only (public repo). */
class SelfMapCoreTest {
    @Test fun portsFromRunningAndNotes() {
        val p = SelfMapCore.portsFor(22, listOf("nginx 0.0.0.0:8090", "docker memoh :8794"), listOf("：7860 面板（故意关）", "端口 123456 不是端口"), listOf(443))
        assertEquals(listOf(22, 443, 7860, 8090, 8794), p)
        assertEquals("面板（故意关）", SelfMapCore.labelFor(7860, emptyList(), listOf("：7860 面板（故意关）")))
        assertEquals("nginx", SelfMapCore.labelFor(8090, listOf("nginx 0.0.0.0:8090"), emptyList()))
    }

    @Test fun promptIsCompactAndTruthful() {
        val now = 10_000_000L
        val s = SelfMapCore.Snapshot("38.9.8", listOf("聊天", "模型组", "工作位置"),
            listOf(SelfMapCore.Provider("百炼", "openAI", true, 12), SelfMapCore.Provider("旧的", "openAI", false, 3)),
            listOf(SelfMapCore.Group("主力", listOf("qwen", "glm"), isDefault = true, agentLoop = true)),
            listOf(SelfMapCore.Server("新加坡", "203.0.113.7", 22, listOf(SelfMapCore.Port(22, true), SelfMapCore.Port(8794, true, label = "Memoh API"), SelfMapCore.Port(7860, false)), now - 5 * 60_000L)),
            skills = 4, workflows = 9)
        val b = SelfMapCore.promptBlock(s, now)
        assertTrue(b, b.contains("百炼·12个模型")); assertFalse(b.contains("旧的"))
        assertTrue(b.contains("主力(默认)(agent loop)[2]"))
        assertTrue(b, b.contains("203.0.113.7: 22 通，8794 通（Memoh API），7860 不通（手机上5 分钟前测）"))
        assertTrue(b.length < 1500)
        val d = SelfMapCore.detail(s, "servers", now)
        assertTrue(d.contains("8794 通")); assertFalse(d.contains("百炼"))
        assertTrue(SelfMapCore.detail(s, "nope", now).startsWith("没有这一项"))
    }

    @Test fun appSelfPortProbeIsRawTcpAndDoesNotClassifyHttp400AsClosed() {
        ServerSocket(0).use { listener ->
            val server = Executors.newSingleThreadExecutor()
            try {
                val response = server.submit {
                    listener.accept().use { peer ->
                        peer.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                        peer.getOutputStream().flush()
                    }
                }
                assertTrue(SelfMapCore.portReachable("127.0.0.1", listener.localPort, 1000))
                response.get(2, TimeUnit.SECONDS)
            } finally {
                server.shutdownNow()
            }
        }
        assertFalse(SelfMapCore.portReachable("127.0.0.1", 0, 100))
    }

    @Test fun neverProbed() {
        val s = SelfMapCore.Snapshot("x", emptyList(), emptyList(), emptyList(), listOf(SelfMapCore.Server("a", "198.51.100.2", 22, emptyList(), 0)))
        assertTrue(SelfMapCore.promptBlock(s, 1).contains("端口没测过"))
        assertEquals("没测过", SelfMapCore.ago(5, 0))
    }
}

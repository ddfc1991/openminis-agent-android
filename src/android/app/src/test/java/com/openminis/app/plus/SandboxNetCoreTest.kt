package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxNetCoreTest {
    @Test fun proxyDiagnosticsShowRouteAndFallbackWithoutCredentials() {
        val line = SandboxNetCore.splitStatus(
            7899, true,
            SplitProxyCore.endpoint("http", "user:password@proxy.example", 8443),
            "最近探测通过",
            listOf(SplitProxyCore.endpoint("socks5", "backup.example", 1080)),
        )
        assertTrue(line.contains("127.0.0.1:7899"))
        assertTrue(line.contains("proxy.example:8443"))
        assertTrue(line.contains("backup.example:1080"))
        assertTrue(line.contains("最近探测通过"))
        assertTrue(!line.contains("password"))
    }

    @Test fun fallbackAndDirectDiagnosticsSayWhatHappened() {
        val failed = SandboxNetCore.splitStatus(7899, true, null, null, listOf("http://fallback.example:8000"))
        assertTrue(failed.contains("目前没有可用上游"))
        assertTrue(failed.contains("fallback.example:8000"))
        assertTrue(failed.contains("Google"))
        val direct = SandboxNetCore.directStatus("系统代理 proxy.example:8000 连不上，已改为直连", "7890 ✗")
        assertTrue(direct.contains("proxy.example:8000"))
        assertTrue(direct.contains("7890 ✗"))
        val fallback = SandboxNetCore.splitFallbackStatus(7899, listOf("http://proxy.example:8443（当前，检测失败）", "socks5://backup.example:1080（候选，未检测）"))
        assertTrue(fallback.contains("127.0.0.1:7899"))
        assertTrue(fallback.contains("proxy.example:8443"))
        assertTrue(fallback.contains("backup.example:1080"))
    }

    @Test fun systemAuthorityDropsCredentialsAndQuery() {
        assertEquals("proxy.example:8080", SandboxNetCore.safeAuthority("http://user:password@proxy.example:8080/path?token=secret"))
    }
}

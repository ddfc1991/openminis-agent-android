package com.openminis.app.plus

import com.openminis.app.plus.WorkspaceCore.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] 工作位置 resources: expiry, reminders, 主设备, IP 归属地, what runs (read-only probe parse). */
class WorkspaceCoreTest {
    private val C = WorkspaceCore
    private val DAY = 86_400_000L
    @Test fun expiry() {
        val now = 1_800_000_000_000L
        assertEquals("还剩 3 天", C.expiryText(Meta("a", expiresAt = now + 3 * DAY - 1000), now))
        assertEquals("还剩 5 小时", C.expiryText(Meta("a", expiresAt = now + 5 * 3_600_000L + 10), now))
        assertEquals("已过期 2 天", C.expiryText(Meta("a", expiresAt = now - 2 * DAY - 5), now))
        assertTrue(C.expired(Meta("a", expiresAt = now - 1), now)); assertFalse(C.expired(Meta("a"), now))
        assertEquals("", C.expiryText(Meta("a"), now))
        assertEquals("1d", C.reminderDue(Meta("a", expiresAt = now + DAY / 2), now, setOf("3d")))
        assertEquals(null, C.reminderDue(Meta("a", expiresAt = now + 10 * DAY), now, emptySet()))
        assertEquals(now + 7 * DAY, C.parseExpiry("7d", now))
        assertTrue(C.parseExpiry("2026-11-01", now) > 0)
    }
    @Test fun primaryIsExactlyOne() {
        assertEquals("b", C.primary(listOf("a", "b"), "b"))
        assertEquals("a", C.primary(listOf("a", "b"), "gone"))
        assertEquals("b", C.primary(listOf("a", "b"), "a", chosen = "b"))
        assertEquals(null, C.primary(emptyList(), "a"))
    }
    @Test fun geo() {
        assertEquals("Japan Tokyo · AS9370 SAKURA Internet Inc.", C.geoLine("""{"status":"success","country":"Japan","regionName":"Tokyo","city":"Tokyo","as":"AS9370 SAKURA Internet Inc."}"""))
        assertEquals("", C.geoLine("""{"status":"fail"}"""))
    }
    @Test fun running() {
        val out = """
LISTEN 0 5 0.0.0.0:8090 0.0.0.0:* users:(("python3",pid=811,fd=3))
LISTEN 0 128 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=1,fd=3))
LISTEN 0 128 127.0.0.1:6379 0.0.0.0:* users:(("redis-server",pid=9,fd=6))
---D
mines-link|nginx:alpine|0.0.0.0:443->443/tcp
---S
hermes.service loaded active running Hermes trader
ssh.service loaded active running OpenBSD Secure Shell server
systemd-journald.service loaded active running Journal
---P
python3
"""
        val r = C.parseRunning(out)
        assertEquals(listOf("端口 8090 · python3", "容器 mines-link（nginx:alpine）", "服务 hermes"), r)
        assertFalse(C.RUNNING_PROBE.contains("kill") || C.RUNNING_PROBE.contains("restart") || C.RUNNING_PROBE.contains("rm "))
    }
    @Test fun cardAndJson() {
        val m = Meta("x", WorkspaceCore.Kind.CLOUD_BROWSER, "云浏览器", "https://b.example", "Katabump", "13800138000", 2_000_000_000_000L, "2 GB 内存", "免费", "vault:kb1", geo = "德国 · AS24940")
        val lines = C.cardLines(m, 1_999_000_000_000L)
        assertTrue(lines.first().contains("138****8000")); assertTrue(lines.any { it.startsWith("IP 归属地") }); assertTrue(lines.any { it.contains("保险箱") })
        assertEquals(listOf(m), C.listFromJson(C.listToJson(listOf(m))))
    }
}

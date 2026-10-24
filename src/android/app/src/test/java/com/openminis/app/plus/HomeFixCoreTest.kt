package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.7] greeting list = count, weather never blank, sky reason, density cap (phone 21:15/21:16). */
class HomeFixCoreTest {
    private fun t(sid: String, st: TaskProgressCore.St, at: Long, goal: String = "g$sid") = TaskProgressCore.Task(sid = sid, goal = goal, st = st, updatedAt = at, reason = "要你选")

    @Test fun waitingMatchesCount() {
        val now = 100_000_000L
        val all = listOf(t("a", TaskProgressCore.St.WAITING, now - 10), t("b", TaskProgressCore.St.WAITING, now - 5), t("c", TaskProgressCore.St.RUNNING, now),
            t("d", TaskProgressCore.St.WAITING, now - 25 * 3_600_000L), t("gone", TaskProgressCore.St.WAITING, now))
        val w = HomeFixCore.waiting(all, setOf("a", "b", "c", "d"), now, mapOf("b" to "项目地址"))
        assertEquals(listOf("b", "a"), w.map { it.sid })
        assertEquals("项目地址", w[0].title); assertEquals("ga", w[1].title)
        // the greeting's number is this list's size
        val s = TaskProgressCore.summary(all.filter { it.sid in setOf("a", "b", "c", "d") }, now)
        assertEquals(s.waiting, w.size)
    }

    @Test fun weatherAlwaysSaysSomething() {
        assertEquals(HomeFixCore.WxWhy.NO_PERMISSION, HomeFixCore.weatherWhy(false, null, false, false, null))
        assertEquals("设置城市", HomeFixCore.weatherChip(HomeFixCore.WxWhy.NO_PERMISSION))
        assertEquals(HomeFixCore.WxWhy.STALE, HomeFixCore.weatherWhy(true, 2 * 3_600_000L, true, false, null))
        assertEquals(HomeFixCore.WxWhy.OK, HomeFixCore.weatherWhy(true, 60_000L, true, false, null))
        HomeFixCore.WxWhy.values().forEach { assertTrue(HomeFixCore.weatherChip(it).isNotBlank()) }
    }

    @Test fun skyReason() {
        assertTrue(HomeFixCore.skyWhy("夜晚", "shader", true, false, null, 21, 13).contains("没有天气，按时间 21:13"))
        assertTrue(HomeFixCore.skyWhy("夜晚", "shader", true, true, 3, 21, 13).contains("照片壁纸"))
    }

    @Test fun densityCap() {
        assertEquals(2.7f, HomeFixCore.density(3.0f, 1080), 0.001f)     // phone 480 dpi → ~400 dp wide
        assertEquals(2.53f, HomeFixCore.density(2.53f, 1080), 0.001f)   // design emulator (405 dpi) unchanged
        assertEquals(2.0f, HomeFixCore.density(2.0f, 0), 0.001f)
    }

    @Test fun unfinishedBadge() {
        val now = 1_000_000_000L
        val all = listOf(t("a", TaskProgressCore.St.RUNNING, now - 10), t("b", TaskProgressCore.St.WAITING, now - 50), t("c", TaskProgressCore.St.DONE, now),
            t("d", TaskProgressCore.St.ERROR, now - 20), t("old", TaskProgressCore.St.RUNNING, now - 8 * 24 * 3_600_000L), t("e", TaskProgressCore.St.STOPPED, now - 1))
        val u = HomeFixCore.unfinished(all, null, now, mapOf("a" to "写报告"))
        assertEquals(listOf("b", "d", "a", "e"), u.map { it.sid })
        assertEquals("写报告", u[2].title); assertTrue(u[0].line.startsWith("等你"))
        assertEquals("", HomeFixCore.badge(0)); assertEquals("4", HomeFixCore.badge(u.size)); assertEquals("99+", HomeFixCore.badge(120))
    }
}

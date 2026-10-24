package com.openminis.app.plus

import com.openminis.app.plus.HomeWeatherCore.GreetIn
import com.openminis.app.plus.HomeWeatherCore.LocSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.5 i] home weather never located by IP (user runs a VPN), rain-soon hint, varied greeting. */
class HomeWeatherCoreTest {

    @Test fun noIpBasedLocationSource() {
        LocSource.values().forEach { assertFalse(it.name, it.name.contains("IP")) }
        for (perm in listOf(true, false)) for (age in listOf(null, 0L, 10 * 60_000L, 3 * 3600_000L)) for (city in listOf(true, false)) {
            val o = HomeWeatherCore.order(perm, age, city)
            assertTrue(o.all { it in LocSource.values() })
        }
        // no permission, no fix, no city → nothing (the weather hides), never a network guess
        assertTrue(HomeWeatherCore.order(false, null, false).isEmpty())
    }

    /** The weather / location code must not call any IP-geolocation service (VPN exit ≠ where the user is). */
    @Test fun weatherSourcesHaveNoIpGeolocation() {
        val banned = Regex("(?i)ipapi|ip-api|ipinfo|ipify|ipwho|geoip|ip\\.sb|ipgeolocation|myip|ifconfig\\.co|freegeoip|extreme-ip|db-ip")
        val dir = listOf("src/main/java/com/openminis/app/plus", "app/src/main/java/com/openminis/app/plus").map { File(it) }.first { it.isDirectory }
        for (f in listOf("HomeGreeting.kt", "HomeWeatherCore.kt")) {
            val code = File(dir, f).readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse("$f calls an IP geolocation service", banned.containsMatchIn(code))
        }
    }

    @Test fun cachedFixFirstWithin30Min() {
        assertEquals(listOf(LocSource.CACHED_FIX, LocSource.FRESH_FIX, LocSource.LAST_KNOWN_FIX), HomeWeatherCore.order(true, 10 * 60_000L, false))
        assertEquals(listOf(LocSource.FRESH_FIX, LocSource.LAST_KNOWN_FIX, LocSource.MANUAL_CITY, LocSource.STALE_FIX), HomeWeatherCore.order(true, 2 * 3600_000L, true))
        assertEquals(listOf(LocSource.MANUAL_CITY), HomeWeatherCore.order(false, null, true))
    }

    private val times = (20..23).map { "2026-10-17T%02d:00".format(it) } + (0..5).map { "2026-10-18T%02d:00".format(it) }

    @Test fun rainInOneHour() {
        val prob = listOf(10, 20, 70, 80, 0, 0, 0, 0, 0, 0)
        assertEquals("1 小时后有雨", HomeWeatherCore.rainSoon("2026-10-17T21:00", times, prob, List(10) { 0.0 }, false))
        assertEquals("快下雨了", HomeWeatherCore.rainSoon("2026-10-17T21:50", times, prob, List(10) { 0.0 }, false))
    }

    @Test fun rainLaterAndTomorrow() {
        val prob = listOf(0, 0, 0, 0, 0, 0, 90, 0, 0, 0)
        assertEquals("明天凌晨 2 点有雨", HomeWeatherCore.rainSoon("2026-10-17T21:00", times, prob, List(10) { 0.0 }, false))
        assertEquals("", HomeWeatherCore.rainSoon("2026-10-17T20:00", times, List(10) { 0 }, List(10) { 0.0 }, false))
        assertEquals("正在下雨", HomeWeatherCore.rainSoon("2026-10-17T20:00", times, List(10) { 0 }, List(10) { 0.0 }, true))
        // millimetres alone also count
        val mm = List(10) { if (it == 2) 0.6 else 0.0 }
        assertEquals("2 小时后有雨", HomeWeatherCore.rainSoon("2026-10-17T20:05", times, List(10) { 0 }, mm, false))
    }

    @Test fun greetingVariesAndNeverRepeatsBackToBack() {
        val i = GreetIn(hour = 22, dow = 7, weather = "cloudy", temp = 26, running = 3, name = "主人")
        var last: String? = null
        val seen = HashSet<String>()
        for (seed in 0 until 40) {
            val g = HomeWeatherCore.choose(i, last, seed)
            assertNotEquals(last, g)
            assertFalse(g.contains("主人"))
            assertTrue(g, g.length <= HomeWeatherCore.MAX_LEN)
            seen += g; last = g
        }
        assertTrue("only ${seen.size} different greetings", seen.size >= 4)
    }

    @Test fun greetingMixesRainTasksAndSlots() {
        val c = HomeWeatherCore.candidates(GreetIn(hour = 18, dow = 6, rainSoon = "1 小时后有雨", running = 2, failed = 1, name = "杨arling"))
        assertTrue(c.contains("1 小时后有雨，带把伞"))
        assertTrue(c.contains("1 个任务出错了"))
        assertTrue(c.contains("2 个任务在跑"))
        assertTrue(c.contains("周五了，快周末啦"))
        assertTrue(c.contains("傍晚好，杨arling"))
        val slots = mapOf(6 to "清晨", 9 to "上午", 12 to "中午", 15 to "下午", 18 to "傍晚", 23 to "深夜")
        slots.forEach { (h, w) -> assertEquals(w, HomeWeatherCore.slotWord(h)) }
    }
}

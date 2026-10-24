package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] user 2026-10-17 22:34–22:35: 「首页天气加回去，还有晚上好主人太单调了」「天气不能对 VPN」.
 * Pure pieces (unit-tested in HomeWeatherCoreTest):
 *  - [LocSource]: where the home weather's place comes from — the phone's own location only (fix cached 30 min), then a
 *    city the user typed. There is deliberately NO IP-based source: behind the user's VPN the IP says the exit country.
 *  - [rainSoon]: 「1 小时后有雨」 from the hourly forecast.
 *  - [Greet]: a short varied greeting (time of day · weather · weekday / holiday · task status), never the same twice in a row.
 */
object HomeWeatherCore {

    /** Every way the home weather may learn where the phone is. No member may be IP / network-address based. */
    enum class LocSource { CACHED_FIX, FRESH_FIX, LAST_KNOWN_FIX, MANUAL_CITY, STALE_FIX }

    const val FIX_CACHE_MS = 30 * 60_000L

    /**
     * The order to try. [fixAgeMs] = age of the remembered device fix (null = none). A device fix younger than 30 min is
     * reused without asking the radio again; a city the user set beats an old fix (they moved, or have no permission).
     */
    fun order(hasPermission: Boolean, fixAgeMs: Long?, manualCity: Boolean): List<LocSource> {
        val out = ArrayList<LocSource>()
        if (fixAgeMs != null && fixAgeMs in 0..FIX_CACHE_MS) out += LocSource.CACHED_FIX
        if (hasPermission) { out += LocSource.FRESH_FIX; out += LocSource.LAST_KNOWN_FIX }
        if (manualCity) out += LocSource.MANUAL_CITY
        if (fixAgeMs != null && fixAgeMs > FIX_CACHE_MS) out += LocSource.STALE_FIX
        return out
    }

    /**
     * 「1 小时后有雨」 / 「现在在下雨」 / 「下午 3 点有雨」 — the first of the next [horizon] hours whose precipitation probability is
     * ≥ 50 % or precipitation ≥ 0.2 mm. [times] are local ISO hours ("2026-10-17T23:00"), [now] the place's local time.
     * Empty when dry. [rainingNow] = the current code is rain / drizzle / showers / thunder.
     */
    fun rainSoon(now: String, times: List<String>, prob: List<Int>, mm: List<Double>, rainingNow: Boolean, horizon: Int = 6): String {
        if (rainingNow) return "正在下雨"
        if (now.length < 13) return ""
        val nowKey = now.take(13)
        val idx = times.indexOfFirst { it.take(13) == nowKey }
        if (idx < 0) return ""
        val nowMin = now.substring(14, 16).toIntOrNull() ?: 0
        for (k in 1..horizon) {
            val i = idx + k
            if (i >= times.size) break
            val p = prob.getOrNull(i) ?: 0
            val r = mm.getOrNull(i) ?: 0.0
            if (p >= 50 || r >= 0.2) {
                // the hour slot starts (k*60 - nowMin) minutes from now
                val mins = k * 60 - nowMin
                return when {
                    mins <= 45 -> "快下雨了"
                    mins <= 90 -> "1 小时后有雨"
                    mins <= 150 -> "2 小时后有雨"
                    else -> {
                        val hh = times[i].substring(11, 13).toInt()
                        val day = if (times[i].take(10) != now.take(10)) "明天" else ""
                        day + slotWord(hh) + " ${if (hh % 12 == 0) 12 else hh % 12} 点有雨"
                    }
                }
            }
        }
        return ""
    }

    fun slotWord(h: Int): String = when (h) {
        in 0..4 -> "凌晨"; in 5..7 -> "清晨"; in 8..10 -> "上午"; in 11..12 -> "中午"
        in 13..16 -> "下午"; in 17..18 -> "傍晚"; in 19..21 -> "晚上"; else -> "深夜"
    }

    // ───────────────────────────── greeting ─────────────────────────────

    data class GreetIn(
        val hour: Int,
        /** java.util.Calendar day of week: 1 = Sunday … 7 = Saturday. */
        val dow: Int,
        val holiday: String? = null,
        /** "rain" "snow" "hot" "cold" "clear" "cloudy" "fog" or null (no weather). */
        val weather: String? = null,
        val temp: Int? = null,
        /** [rainSoon] text, "" when dry. */
        val rainSoon: String = "",
        val running: Int = 0,
        val waiting: Int = 0,
        val failed: Int = 0,
        /** The user's name; optional, "主人" counts as none. */
        val name: String? = null,
    )

    const val MAX_LEN = 14

    /** Every phrase that fits now, important ones (rain, failures) first and repeated so they come up more often. */
    fun candidates(i: GreetIn): List<String> {
        val slot = slotWord(i.hour)
        val base = when (slot) {
            "凌晨" -> listOf("凌晨了，快去睡", "这么晚还醒着", "夜很深了")
            "清晨" -> listOf("清晨好", "早呀，天刚亮", "清晨好，慢慢来")
            "上午" -> listOf("上午好", "早上好", "上午好，慢慢来")
            "中午" -> listOf("中午好", "午安", "中午了，记得吃饭")
            "下午" -> listOf("下午好", "午后好", "下午好，喝口水")
            "傍晚" -> listOf("傍晚好", "傍晚了，辛苦啦", "黄昏好")
            "晚上" -> listOf("晚上好", "晚上好，歇一歇", "忙完了吗")
            else -> listOf("夜深了，早点睡", "深夜了，别熬太晚", "夜深了")
        }
        val important = ArrayList<String>()
        val extra = ArrayList<String>()
        when {
            i.rainSoon == "正在下雨" || i.weather == "rain" -> important += listOf("下雨了，出门带伞", "${slot}下雨，路上慢点")
            i.rainSoon.isNotBlank() -> important += listOf("${i.rainSoon}，带把伞", i.rainSoon + "，记得带伞")
            i.weather == "snow" -> important += listOf("下雪了，路滑慢走", "下雪了，穿暖和点")
        }
        if (i.temp != null) when {
            i.weather != "rain" && i.temp >= 33 -> extra += listOf("今天 ${i.temp}°，多喝水", "${i.temp}° 好热，少晒太阳")
            i.temp <= 5 -> extra += listOf("${i.temp}°，穿暖和点", "外面 ${i.temp}°，注意保暖")
        }
        if (i.weather == "clear" && i.hour in 7..17) extra += listOf("天气不错，${slot}好")
        if (i.weather == "fog") extra += listOf("有雾，路上慢点")
        if (i.holiday != null && i.hour in 6..22) important += listOf("${i.holiday}快乐", "${i.holiday}快乐，歇一歇")
        val weekend = i.dow == 7 || i.dow == 1
        if (i.hour in 6..21) when {
            weekend -> extra += listOf("周末${slot}好", "周末愉快")
            i.dow == 6 -> extra += listOf("周五了，快周末啦", "周五${slot}好")
            i.dow == 2 -> extra += listOf("周一，慢慢进入状态", "周一${slot}好")
        }
        if (i.failed > 0) important += listOf("${i.failed} 个任务出错了", "${slot}好，${i.failed} 个任务出错")
        if (i.waiting > 0) extra += listOf("${i.waiting} 件事等你看", "${slot}好，${i.waiting} 件事等你")
        if (i.running > 0) extra += listOf("${i.running} 个任务在跑", "${slot}好，${i.running} 个任务在跑")
        val name = i.name?.trim()?.takeIf { it.isNotEmpty() && it != "主人" }
        val named = if (name != null) base.take(2).map { it.substringBefore('，') + "，" + name } else emptyList()
        val all = important + important + important + extra + extra + base + named
        return all.filter { it.length <= MAX_LEN + (name?.length ?: 0) }.ifEmpty { base }
    }

    /** One phrase for this open: never [last], otherwise chosen by [seed] (a per-open counter). */
    fun choose(i: GreetIn, last: String?, seed: Int): String {
        val c = candidates(i)
        val pool = c.filter { it != last }.ifEmpty { c }
        val s = ((seed.toLong() * 2654435761L) ushr 8).toInt() and Int.MAX_VALUE
        return pool[s % pool.size]
    }
}

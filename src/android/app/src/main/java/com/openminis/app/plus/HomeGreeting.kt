package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.7] Home greeting with real care (user 2026-10-15 「首页问候语要多变化，天气变化，夜深早睡之类的，人文关怀得到位，
 * 一直就正常在跑下午好」).
 *
 * Title = the time of day in 8 slots (凌晨 / 清晨 / 上午 / 中午 / 下午 / 傍晚 / 晚上 / 深夜), sometimes the weekend / a
 * holiday / 「好久不见」. Care line = the one thing that matters most right now, in this order: battery low → late night
 * (早点睡) → weather (rain: umbrella, snow, a cold drop: add clothes, heat, cold) → weekend / holiday → long time away →
 * a plain warm line for the slot. Every category has several lines; one is picked at random and the same sentence is not
 * used twice on the same day. Short, warm, plain words (read aloud well for TalkBack).
 *
 * Weather: open-meteo.com (no key). [Mines-38.9.5 i] user 22:35 「天气不能对 VPN」: the place is ONLY the phone's own location
 * (coarse, fix cached 30 min) or a city the user typed (HomeWeatherCore.order) — never the network address. Cached 1 h;
 * any failure → no weather shown.
 */
object HomeGreeting {
    data class Weather(val code: Int, val temp: Double, val max: Double, val min: Double, val maxYesterday: Double?, val rainProb: Int, val place: String = "",
                       /** [Mines-38.8] short 「傍晚可能有雨」 hint for later today (rain probability >= 60 %), never replaces the current word. */
                       val later: String = "",
                       /** [Mines-38.9.5 i] 「1 小时后有雨」 / 「正在下雨」 from the hourly forecast ("" = dry for the next 6 h). */
                       val soon: String = "")
    data class Ctx(val hour: Int, val minute: Int, val dow: Int, val month: Int, val day: Int, val weather: Weather?, val battery: Int, val charging: Boolean, val awayDays: Int)
    data class Greeting(val title: String, val care: String, val kind: String)

    /** smoke: fixed inputs ("hour", "weather" rain/snow/hot/cold/drop/clear, "battery", "away", "weekend", "holiday"). */
    @Volatile var override: JSONObject? = null
    private val _tick = MutableStateFlow(0)
    /** bumps when the weather arrives or an override is set, so the home card recomputes. */
    val tick: StateFlow<Int> = _tick.asStateFlow()
    fun applyOverride(o: JSONObject?) { override = o; _tick.value++ }

    private const val PREFS = "mines_greeting"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- lines ----------------

    private val slots = listOf(
        Triple(0..4, "凌晨", listOf("凌晨了", "夜很深了", "这么晚还没睡呀")),
        Triple(5..6, "清晨", listOf("清晨好", "早呀，起得真早", "天刚亮，早上好")),
        Triple(7..10, "上午", listOf("早上好", "上午好", "新的一天，早上好")),
        Triple(11..12, "中午", listOf("中午好", "午安", "到饭点了，中午好")),
        Triple(13..16, "下午", listOf("下午好", "午后好", "下午好呀")),
        Triple(17..18, "傍晚", listOf("傍晚好", "黄昏了，辛苦一天", "傍晚好呀")),
        Triple(19..21, "晚上", listOf("晚上好", "晚上好呀", "忙完了吗，晚上好")),
        Triple(22..23, "深夜", listOf("夜深了", "很晚了", "深夜了")),
    )

    private val care = mapOf(
        "battery" to listOf("手机电量只剩 %d%%，记得充电", "电量 %d%% 了，找个插座充一下吧", "快没电了（%d%%），先充上电再忙"),
        "sleep" to listOf("早点休息，身体最要紧", "放下手机睡吧，明天再继续", "熬夜伤身，今天就到这儿吧", "该睡了，剩下的交给我"),
        "dawn" to listOf("还没睡的话，先去休息一下吧", "这个点还醒着，别太累了", "天快亮了，抓紧睡一会儿"),
        "rain" to listOf("今天有雨，出门记得带伞", "外面在下雨，路上慢一点", "可能下雨，带把伞再出门"),
        "snow" to listOf("今天有雪，路滑，走慢一点", "下雪了，穿厚点再出门", "外面飘雪，注意保暖和路滑"),
        "drop" to listOf("今天降温了，记得加件衣服", "比昨天冷不少，多穿一点", "气温降了，别着凉"),
        "hot" to listOf("今天很热（%d°），多喝水，少晒太阳", "高温天（%d°），注意防暑", "天热（%d°），出门做好防晒"),
        "cold" to listOf("外面挺冷（%d°），穿暖和些", "气温只有 %d°，注意保暖", "天冷（%d°），出门多穿点"),
        "weekend" to listOf("周末了，给自己放个假吧", "周末愉快，好好休息一下", "难得周末，出去走走也不错"),
        "holiday" to listOf("%s快乐，好好放松一下", "%s到了，祝你开心", "%s快乐，别忘了休息"),
        "away" to listOf("好久不见，欢迎回来", "%d 天没见啦，最近还好吗", "欢迎回来，想你啦"),
        "morning" to listOf("先吃个早饭，今天也顺顺利利", "新的一天，慢慢来", "喝杯水，开始今天吧"),
        "noon" to listOf("记得好好吃午饭", "吃完饭可以眯一会儿", "午饭别凑合"),
        "afternoon" to listOf("累了就起来活动一下", "喝口水，歇一会儿再忙", "下午也别太拼"),
        "evening" to listOf("辛苦一天了，放松一下", "晚饭吃了吗", "今天辛苦了"),
        "night" to listOf("忙完就早点歇着", "今天过得怎么样", "晚上别太累"),
    )

    private val holidays = mapOf(
        "0101" to "元旦", "0214" to "情人节", "0501" to "劳动节", "1001" to "国庆", "1002" to "国庆", "1003" to "国庆", "1225" to "圣诞",
        // lunar festivals, 2026–2027 Gregorian dates
        "20260217" to "春节", "20260303" to "元宵节", "20260405" to "清明", "20260619" to "端午", "20260925" to "中秋", "20261018" to "重阳",
        "20270206" to "春节", "20270220" to "元宵节", "20270405" to "清明", "20270609" to "端午", "20270915" to "中秋",
    )

    // ---------------- context ----------------

    fun context(c: Context, snapBattery: Int, charging: Boolean): Ctx {
        val o = override
        val cal = Calendar.getInstance()
        val p = prefs(c)
        val lastOpen = p.getLong("last_open", 0L)
        val away = if (lastOpen > 0) ((System.currentTimeMillis() - lastOpen) / 86_400_000L).toInt() else 0
        var w = cachedWeather(c)
        var hour = cal.get(Calendar.HOUR_OF_DAY); var dow = cal.get(Calendar.DAY_OF_WEEK)
        var month = cal.get(Calendar.MONTH) + 1; var day = cal.get(Calendar.DAY_OF_MONTH)
        var bat = snapBattery; var awayDays = away
        if (o != null) {
            if (o.has("hour")) hour = o.optInt("hour")
            if (o.has("battery")) bat = o.optInt("battery")
            if (o.has("away")) awayDays = o.optInt("away")
            if (o.optBoolean("weekend")) dow = Calendar.SATURDAY
            if (o.has("weekday")) dow = Calendar.WEDNESDAY
            if (o.has("date")) o.optString("date").takeIf { it.length == 4 }?.let { month = it.take(2).toInt(); day = it.takeLast(2).toInt() }
            w = when (o.optString("weather")) {
                "rain" -> Weather(63, 18.0, 20.0, 15.0, 21.0, 90)
                "snow" -> Weather(73, -2.0, 0.0, -5.0, 1.0, 80)
                "hot" -> Weather(1, 34.0, 36.0, 27.0, 35.0, 0)
                "cold" -> Weather(2, 2.0, 6.0, -1.0, 7.0, 0)
                "drop" -> Weather(3, 12.0, 14.0, 9.0, 22.0, 10)
                "clear" -> Weather(0, 22.0, 25.0, 15.0, 24.0, 0)
                // [Mines-38.8 v5d] home weather ambience smoke states
                "cloudy" -> Weather(3, 20.0, 22.0, 16.0, 21.0, 10)
                "fog" -> Weather(45, 14.0, 17.0, 11.0, 16.0, 10)
                "thunder" -> Weather(95, 24.0, 27.0, 21.0, 26.0, 90)
                "none" -> null
                else -> w
            }
            if (o.has("soon")) w = w?.copy(soon = o.optString("soon"))
        }
        return Ctx(hour, cal.get(Calendar.MINUTE), dow, month, day, w, bat, charging && o?.has("battery") != true, awayDays)
    }

    /** [Mines-38.7] the weather shown above the home avatar: the same source as the greeting (override in smoke). */
    fun weatherNow(c: Context): Weather? {
        val o = override
        if (o != null && o.has("weather")) return context(c, 80, false).weather
        // [Mines-38.9.7] the phone lost its weather after 1 h in the app: show the last reading up to 6 h old while refreshing
        return cachedWeather(c) ?: cachedWeather(c, 6 * 3600_000L)
    }

    /** [Mines-38.9.7] age of the cached reading (null = none) and why there is none / it is old (自检, home chip). */
    fun weatherAgeMs(c: Context): Long? = prefs(c).getLong("w_at", 0L).takeIf { it > 0 && prefs(c).contains("w") }?.let { System.currentTimeMillis() - it }
    fun weatherWhy(c: Context): HomeFixCore.WxWhy {
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val age = weatherAgeMs(c)
        return HomeFixCore.weatherWhy(age != null && age <= 6 * 3600_000L, age, ok, prefs(c).contains("city_name"), prefs(c).getString("w_why", null))
    }

    /** (line-icon name, short words) for an open-meteo WMO code; night swaps the sun for a moon. */
    fun describe(code: Int, hour: Int): Pair<String, String> {
        val night = hour >= 19 || hour < 6
        return when (code) {
            0 -> (if (night) "moon" else "sun") to "晴"
            1, 2 -> (if (night) "moon" else "cloud-sun") to "多云"
            3 -> "cloud" to "阴"
            45, 48 -> "cloud-fog" to "有雾"
            in 51..57 -> "cloud-drizzle" to "小雨"
            in 61..67 -> "cloud-rain" to "下雨"
            in 71..77 -> "cloud-snow" to "下雪"
            in 80..82 -> "cloud-rain" to "阵雨"
            85, 86 -> "cloud-snow" to "阵雪"
            in 95..99 -> "cloud-lightning" to "雷雨"
            else -> "cloud" to "多云"
        }
    }

    /** The app came to the front (for 「好久不见」). The away gap is read before this. */
    fun markOpened(c: Context) { val p = prefs(c); p.edit().putLong("last_open", System.currentTimeMillis()).putInt("g_open", p.getInt("g_open", 0) + 1).apply(); _tick.value++ }

    private fun slotOf(h: Int) = slots.firstOrNull { h in it.first } ?: slots.last()

    private fun isRain(w: Weather) = w.code in 51..67 || w.code in 80..82 || w.code in 95..99 || w.rainProb >= 60
    private fun isSnow(w: Weather) = w.code in 71..77 || w.code in 85..86

    /** The greeting for [x]; [c] remembers which sentences were used today. */
    fun compute(c: Context, x: Ctx): Greeting {
        val slot = slotOf(x.hour)
        val key = "%02d%02d".format(x.month, x.day)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val holiday = holidays[key] ?: holidays["$year$key"]
        val weekend = x.dow == Calendar.SATURDAY || x.dow == Calendar.SUNDAY
        val w = x.weather
        val (kind, arg) = when {
            x.battery in 0..15 && !x.charging -> "battery" to x.battery
            x.hour in 0..4 -> "dawn" to null
            x.hour >= 22 -> "sleep" to null
            w != null && isSnow(w) -> "snow" to null
            w != null && isRain(w) -> "rain" to null
            w != null && w.maxYesterday != null && w.max - w.maxYesterday <= -5 -> "drop" to null
            w != null && w.max >= 33 -> "hot" to w.max.toInt()
            w != null && w.temp <= 5 -> "cold" to w.temp.toInt()
            holiday != null -> "holiday" to holiday
            x.awayDays >= 3 -> "away" to x.awayDays
            weekend -> "weekend" to null
            x.hour in 5..10 -> "morning" to null
            x.hour in 11..12 -> "noon" to null
            x.hour in 13..16 -> "afternoon" to null
            x.hour in 17..18 -> "evening" to null
            else -> "night" to null
        }
        // title: weekend / holiday / welcome-back flavour now and then, the time slot otherwise
        val titles = slot.third + when {
            holiday != null && x.hour in 7..21 -> listOf("$holiday 快乐")
            weekend && x.hour in 7..21 -> listOf("周末好")
            x.awayDays >= 3 -> listOf("好久不见")
            else -> emptyList()
        }
        val title = pick(c, "t_" + slot.second, titles)
        val lines = care[kind] ?: care.getValue("night")
        val raw = pick(c, "c_$kind", lines)
        val line = when (arg) {
            is Int -> runCatching { raw.format(arg) }.getOrDefault(raw.replace("%d", arg.toString()))
            is String -> raw.replace("%s", arg)
            else -> raw
        }
        return Greeting(title, line, kind)
    }

    /**
     * Random line of [lines] not yet used today (per category); stable within the hour so the home card does not flip on
     * every recomposition.
     */
    private fun pick(c: Context, cat: String, lines: List<String>): String {
        val p = prefs(c)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val hourKey = today + Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + (override?.toString()?.hashCode() ?: 0)
        p.getString("cur_$cat", null)?.split('\u0001')?.takeIf { it.size == 2 && it[0] == hourKey && it[1] in lines }?.let { return it[1] }
        val usedRaw = p.getString("used_$cat", "") ?: ""
        val used = if (usedRaw.startsWith("$today\u0001")) usedRaw.split('\u0001').drop(1).toSet() else emptySet()
        val fresh = lines.filter { it !in used }.ifEmpty { lines }
        val chosen = fresh.random()
        p.edit().putString("cur_$cat", hourKey + "\u0001" + chosen)
            .putString("used_$cat", (listOf(today) + (used + chosen)).joinToString("\u0001")).apply()
        return chosen
    }

    // ---------------- weather ----------------

    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    /** [Mines-38.8 v5d] open-meteo's current is_day for the cached weather (null when there is none) — home weather ambience. */
    fun isDayNow(c: Context): Boolean? = runCatching {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("w_at", 0L) > 3600_000L) return null
        val o = JSONObject(p.getString("w", null) ?: return null)
        if (!o.has("isDay")) null else o.optInt("isDay", 1) == 1
    }.getOrNull()

    /** [Mines-38.9.1] (wind m/s, direction ° it blows FROM, weather code) for the fog; null when no fresh weather. */
    fun windNow(c: Context): Triple<Double, Double, Int>? = runCatching {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("w_at", 0L) > 3 * 3600_000L) return null
        val o = JSONObject(p.getString("w", null) ?: return null)
        Triple(o.optDouble("wind", 1.5), o.optDouble("windDir", 270.0), o.optInt("code", 0))
    }.getOrNull()

    fun cachedWeather(c: Context, maxAgeMs: Long = 3600_000L): Weather? = runCatching {
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("w_at", 0L) > maxAgeMs) return null   // [Mines-38.8] max 1 h (display: 6 h)
        val o = JSONObject(p.getString("w", null) ?: return null)
        Weather(o.getInt("code"), o.getDouble("temp"), o.getDouble("max"), o.getDouble("min"),
            if (o.has("maxY")) o.getDouble("maxY") else null, o.optInt("rain"), o.optString("place"), o.optString("later"), o.optString("soon"))
    }.getOrNull()

    /**
     * [Mines-38.8] user 2026-10-16 「我这里晴天，显示阵雨」. Fetches when the cache is older than 1 h, or on app resume when the
     * phone moved more than 5 km from where the cached weather is for. The word / icon is the CURRENT condition
     * (current weather_code); rain later today is only a separate short hint (「傍晚可能有雨」, probability >= 60 %).
     */
    suspend fun refreshWeather(c: Context) = withContext(Dispatchers.IO) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val fresh = cachedWeather(c) != null
        if (fresh && now - p.getLong("w_loc_try", 0L) < 5 * 60_000L) return@withContext
        if (!fresh && now - p.getLong("w_try", 0L) < 2 * 60_000L) return@withContext
        p.edit().putLong("w_loc_try", now).apply()
        val loc = location(c) ?: return@withContext
        if (fresh && p.contains("w_lat") && distKm(loc.first, loc.second, p.getFloat("w_lat", 0f).toDouble(), p.getFloat("w_lon", 0f).toDouble()) <= 5.0) return@withContext
        p.edit().putLong("w_try", now).apply()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}" +
            "&current=temperature_2m,weather_code,precipitation,is_day,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms&hourly=precipitation_probability,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,sunrise,sunset" +
            "&past_days=1&forecast_days=2&timezone=auto"
        val o = runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null } }.getOrNull()
            ?: run { p.edit().putString("w_why", "fetch").apply(); _tick.value++; return@withContext }
        p.edit().remove("w_why").apply()
        runCatching {
            val cur = o.getJSONObject("current"); val d = o.getJSONObject("daily")
            val maxA = d.getJSONArray("temperature_2m_max"); val minA = d.getJSONArray("temperature_2m_min")
            val prA = d.optJSONArray("precipitation_probability_max"); val codeA = d.optJSONArray("weather_code")
            // [Mines-38.9.5 i] forecast_days=2 → today is the second-to-last daily entry (past_days=1 first)
            val i = (maxA.length() - 2).coerceAtLeast(0)
            // [Mines-38.7] sunrise / sunset for the day ↔ night switch
            runCatching { DayNight.saveSun(c, d.optJSONArray("sunrise")?.optString(i), d.optJSONArray("sunset")?.optString(i)) }
            val dayCode = codeA?.optInt(i) ?: 0
            // the word / icon is what it is like outside NOW (was: the day's worst code, so a sunny afternoon showed 阵雨)
            val code = cur.optInt("weather_code", dayCode)
            val later = laterRainHint(o, code)
            val soon = runCatching {
                val h = o.getJSONObject("hourly"); val tA = h.getJSONArray("time"); val pA = h.optJSONArray("precipitation_probability"); val mA = h.optJSONArray("precipitation")
                HomeWeatherCore.rainSoon(cur.optString("time"), (0 until tA.length()).map { tA.optString(it) },
                    (0 until tA.length()).map { pA?.optInt(it, 0) ?: 0 }, (0 until tA.length()).map { mA?.optDouble(it, 0.0) ?: 0.0 },
                    code in 51..67 || code in 80..82 || code in 95..99)
            }.getOrDefault("")
            val j = JSONObject().put("code", code)
                .put("temp", cur.optDouble("temperature_2m")).put("max", maxA.optDouble(i)).put("min", minA.optDouble(i))
                .put("rain", prA?.optInt(i) ?: 0).put("place", loc.third).put("later", later).put("soon", soon)
                .put("precip", cur.optDouble("precipitation", 0.0)).put("isDay", cur.optInt("is_day", 1))
                .put("wind", cur.optDouble("wind_speed_10m", 0.0)).put("windDir", cur.optDouble("wind_direction_10m", 270.0))
            if (i >= 1) j.put("maxY", maxA.optDouble(i - 1))
            p.edit().putString("w", j.toString()).putLong("w_at", System.currentTimeMillis())
                .putFloat("w_lat", loc.first.toFloat()).putFloat("w_lon", loc.second.toFloat()).apply()
            _tick.value++
        }
    }

    /** 「傍晚可能有雨」: the first hour later today with precipitation probability >= 60 %, only when it is dry now. */
    private fun laterRainHint(o: JSONObject, curCode: Int): String = runCatching {
        if (curCode in 51..67 || curCode in 80..82 || curCode in 95..99) return ""
        val h = o.getJSONObject("hourly"); val times = h.getJSONArray("time"); val pr = h.getJSONArray("precipitation_probability")
        val nowT = o.getJSONObject("current").optString("time")   // local time of the place, e.g. 2026-10-16T16:45
        if (nowT.length < 13) return ""
        val day = nowT.take(10); val nowH = nowT.substring(11, 13).toInt()
        for (k in 0 until times.length()) {
            val t = times.optString(k)
            if (!t.startsWith(day) || t.length < 13) continue
            val hh = t.substring(11, 13).toInt()
            if (hh <= nowH) continue
            if (pr.optInt(k, 0) >= 60) return when (hh) {
                in 0..10 -> "上午可能有雨"; in 11..12 -> "中午可能有雨"; in 13..16 -> "下午可能有雨"
                in 17..18 -> "傍晚可能有雨"; else -> "夜里可能有雨"
            }
        }
        ""
    }.getOrDefault("")

    private fun distKm(a: Double, b: Double, c2: Double, d: Double): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c2, d, r); return r[0] / 1000.0
    }


    private val _askLocation = MutableStateFlow(false)
    /** [Mines-38.8] true once when there is no location permission and no remembered place: the home asks in plain words. */
    val askLocation: StateFlow<Boolean> = _askLocation.asStateFlow()
    fun locationAsked(c: Context) { _askLocation.value = false; prefs(c).edit().putBoolean("loc_asked", true).apply() }

    /** A fresh network fix (5 s timeout), or null. */
    @Suppress("MissingPermission")
    private suspend fun freshFix(lm: android.location.LocationManager): android.location.Location? {
        val pr = android.location.LocationManager.NETWORK_PROVIDER
        if (runCatching { lm.isProviderEnabled(pr) }.getOrDefault(false) != true) return null
        return kotlinx.coroutines.withTimeoutOrNull(5000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val cs = android.os.CancellationSignal()
                        cont.invokeOnCancellation { runCatching { cs.cancel() } }
                        lm.getCurrentLocation(pr, cs, java.util.concurrent.Executors.newSingleThreadExecutor()) { l -> cont.resumeWith(Result.success(l)) }
                    } else {
                        val ll = object : android.location.LocationListener {
                            override fun onLocationChanged(l: android.location.Location) { cont.resumeWith(Result.success(l)) }
                            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                            override fun onProviderEnabled(p: String) {}
                            override fun onProviderDisabled(p: String) { cont.resumeWith(Result.success(null)) }
                        }
                        cont.invokeOnCancellation { runCatching { lm.removeUpdates(ll) } }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(pr, ll, android.os.Looper.getMainLooper())
                    }
                }.onFailure { cont.resumeWith(Result.success(null)) }
            }
        }
    }

    /** [Mines-38.9.5 i] a city the user set (fallback when there is no device location): name + coordinates. */
    fun manualCity(c: Context): Triple<Double, Double, String>? {
        val p = prefs(c)
        val n = p.getString("city_name", null) ?: return null
        return Triple(p.getFloat("city_lat", 0f).toDouble(), p.getFloat("city_lon", 0f).toDouble(), n)
    }

    /** Looks [name] up with open-meteo's geocoding (by name — not by network address) and saves it; null = not found. */
    suspend fun setManualCity(c: Context, name: String?): String? = withContext(Dispatchers.IO) {
        val p = prefs(c)
        if (name.isNullOrBlank()) { p.edit().remove("city_name").remove("city_lat").remove("city_lon").putLong("w_at", 0L).apply(); _tick.value++; return@withContext null }
        val u = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&name=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")
        val r = runCatching { http.newCall(Request.Builder().url(u).build()).execute().use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null } }.getOrNull()
            ?.optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        val nm = r.optString("name", name.trim())
        p.edit().putString("city_name", nm).putFloat("city_lat", r.optDouble("latitude").toFloat()).putFloat("city_lon", r.optDouble("longitude").toFloat())
            .putLong("w_at", 0L).putLong("w_try", 0L).putLong("w_loc_try", 0L).apply()
        refreshWeather(c)
        nm
    }

    /** Where the shown weather is for: 「手机定位」 or 「手动城市」 (detail sheet). */
    fun placeSource(c: Context): String = prefs(c).getString("w_src", "") ?: ""

    /** City name for a device fix, from the phone's own Geocoder (offline-capable on most phones); "" when unavailable. */
    @Suppress("DEPRECATION")
    private fun cityOf(c: Context, lat: Double, lon: Double): String = runCatching {
        if (!android.location.Geocoder.isPresent()) return ""
        val a = android.location.Geocoder(c, java.util.Locale.SIMPLIFIED_CHINESE).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return ""
        (a.locality ?: a.subAdminArea ?: a.adminArea ?: "").removeSuffix("市")
    }.getOrDefault("")

    /**
     * (lat, lon, place). [Mines-38.9.5 i] ONLY device-based (HomeWeatherCore.order): a device fix younger than 30 min →
     * a fresh network fix → the newest last-known fix → the city the user set → an older remembered fix. No IP lookup at all:
     * the user always runs a VPN, so the network address says the exit country. Nothing → ask once for coarse location;
     * the weather stays hidden meanwhile.
     */
    private suspend fun location(c: Context): Triple<Double, Double, String>? {
        val p = prefs(c)
        val ok = androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val fixAt = p.getLong("loc_at", 0L)
        val fixAge = if (p.contains("loc_lat") && fixAt > 0) System.currentTimeMillis() - fixAt else null
        fun remembered() = Triple(p.getFloat("loc_lat", 0f).toDouble(), p.getFloat("loc_lon", 0f).toDouble(), p.getString("loc_city", "") ?: "")
        fun remember(l: android.location.Location): Triple<Double, Double, String> {
            val city = cityOf(c, l.latitude, l.longitude)
            p.edit().putFloat("loc_lat", l.latitude.toFloat()).putFloat("loc_lon", l.longitude.toFloat()).putLong("loc_at", System.currentTimeMillis())
                .putString("loc_city", city).putString("w_src", "手机定位").apply()
            return Triple(l.latitude, l.longitude, city)
        }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        for (src in HomeWeatherCore.order(ok, fixAge, p.contains("city_name"))) {
            when (src) {
                HomeWeatherCore.LocSource.CACHED_FIX -> { p.edit().putString("w_src", "手机定位").apply(); return remembered() }
                HomeWeatherCore.LocSource.FRESH_FIX -> runCatching { freshFix(lm) }.getOrNull()?.let { return remember(it) }
                HomeWeatherCore.LocSource.LAST_KNOWN_FIX -> {
                    @Suppress("MissingPermission")
                    val l = runCatching {
                        listOf(android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
                            .mapNotNull { pr -> runCatching { lm.getLastKnownLocation(pr) }.getOrNull() }.maxByOrNull { it.time }
                    }.getOrNull()
                    if (l != null && System.currentTimeMillis() - l.time <= 2 * 3600_000L) return remember(l)
                }
                HomeWeatherCore.LocSource.MANUAL_CITY -> manualCity(c)?.let { p.edit().putString("w_src", "手动城市").apply(); return it }
                HomeWeatherCore.LocSource.STALE_FIX -> { p.edit().putString("w_src", "手机定位").apply(); return remembered() }
            }
        }
        if (!ok && !p.getBoolean("loc_asked", false)) _askLocation.value = true
        p.edit().putString("w_why", if (ok) "nofix" else "noperm").apply()   // [Mines-38.9.7] 自检 / home chip
        return null
    }

    // ---------------- varied greeting (user 22:34 「晚上好主人太单调了」) ----------------

    /** Counts this app open (for the greeting rotation). */
    fun openSeed(c: Context): Int = prefs(c).getInt("g_open", 0)

    /** The greeting for this open: time of day · weather · weekday / holiday · task status; never the same as the last one shown. */
    fun variedGreeting(c: Context, x: Ctx, running: Int, waiting: Int, failed: Int, name: String?): String {
        val p = prefs(c)
        val seed = p.getInt("g_open", 0)
        val shownFor = p.getInt("g_seed", -1)
        val cur = p.getString("g_cur", null)
        val w = x.weather
        val key = "%02d%02d".format(x.month, x.day)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val kind = when {
            w == null -> null
            isSnow(w) -> "snow"
            w.code in 51..67 || w.code in 80..82 || w.code in 95..99 -> "rain"
            w.code in 45..48 -> "fog"
            w.code == 0 -> "clear"
            else -> "cloudy"
        }
        val input = HomeWeatherCore.GreetIn(x.hour, x.dow, holidays[key] ?: holidays["$year$key"], kind, w?.temp?.let { Math.round(it).toInt() },
            w?.soon.orEmpty(), running, waiting, failed, name)
        // same open + still a valid phrase for now → keep it (no flicker on recomposition)
        if (shownFor == seed && cur != null && cur in HomeWeatherCore.candidates(input)) return cur
        val g = HomeWeatherCore.choose(input, cur, seed)
        p.edit().putInt("g_seed", seed).putString("g_cur", g).apply()
        return g
    }
}

package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * [Mines-38.7] Day ↔ night by the real sunrise / sunset (user 2026-10-15 「白天↔夜晚主动动画切换」): open-meteo daily
 * sunrise/sunset of the home-weather place (saved by [HomeGreeting.refreshWeather]), else 6:00 / 18:30. The app's
 * 「随日出日落」 theme mode (the default) follows [night]; MinisTheme animates the change (~2.5 s fog / glass / ink fade,
 * sun sets and moon rises on the home orbit). Smoke: [simulate] runs a fake clock from a given time so the sunset is
 * crossed while the app is open.
 */
object DayNight {
    private const val PREFS = "mines_greeting"
    private val _night = MutableStateFlow(false)
    val night: StateFlow<Boolean> = _night

    /** fake clock for the smoke: (minute of day it started at, real ms when it started); null = real time */
    @Volatile private var sim: Pair<Double, Long>? = null
    @Volatile private var simSunset: Int? = null

    fun simulate(startMinute: Double?, sunsetMinute: Int? = null) {
        sim = startMinute?.let { it to System.currentTimeMillis() }
        simSunset = sunsetMinute
    }
    val simulating get() = sim != null

    /** minute of the day now (fake clock when simulating) */
    fun minuteNow(): Double {
        sim?.let { (m, at) -> return (m + (System.currentTimeMillis() - at) / 60_000.0) % 1440.0 }
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60.0 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
    }

    fun sunrise(c: Context): Int = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sunrise", 6 * 60)
    fun sunset(c: Context): Int = simSunset ?: c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sunset", 18 * 60 + 30)

    /** "2026-10-15T18:21" → 1101 */
    fun saveSun(c: Context, rise: String?, set: String?) {
        fun mins(s: String?): Int? = s?.substringAfter('T', "")?.split(':')?.let { p -> if (p.size >= 2) (p[0].toIntOrNull() ?: return null) * 60 + (p[1].toIntOrNull() ?: return null) else null }
        val r = mins(rise); val s = mins(set)
        val e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (r != null && r in 120..720) e.putInt("sunrise", r)
        if (s != null && s in 900..1380) e.putInt("sunset", s)
        e.apply()
    }

    fun isNightNow(c: Context): Boolean { val m = minuteNow(); return m < sunrise(c) || m >= sunset(c) }

    /** re-checks the clock (MainActivity calls it every 30 s, every 1 s while simulating) */
    fun tick(c: Context): Boolean { val n = isNightNow(c); _night.value = n; return n }
}

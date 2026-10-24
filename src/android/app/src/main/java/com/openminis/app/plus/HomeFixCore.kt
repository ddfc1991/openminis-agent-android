package com.openminis.app.plus

/**
 * [Mines-38.9.7] user 2026-10-18 21:15/21:16 real-phone feedback on 38.9.6 (realme 1080×2412, dark):
 *  (a) 「晚上好，2 件事等你」 said nothing about WHICH two things and could not be tapped → [waiting] is the one list both the
 *      greeting count and the tap-through sheet use (same filter: on the home list, touched in the last 24 h, state 等你);
 *  (b) the home weather was gone: the cache is 1 h and was only refreshed on resume / first composition, so after an hour
 *      in the app (or with no location) HomeWeatherMini drew nothing → it always shows something now ([weatherChip]) and
 *      自检 says why ([weatherWhy]);
 *  (c) the 天气皮肤 looked like a maroon tint: the sky scene was picked once (remember on ticks only) — at 18:xx that is
 *      SUNSET, whose dark variant is maroon, and it never changed again → re-picked every minute, 自检 says which and why
 *      ([skyWhy]); and the type was larger than the design: the design was drawn at 405 dpi (the emulator), the phone runs
 *      480 dpi, so everything was ~18 % bigger → [density] keeps at least 400 dp across the screen.
 * Pure (unit-tested).
 */
object HomeFixCore {
    data class Waiting(val sid: String, val title: String, val why: String, val at: Long)

    /** The 「N 件事等你」 list: newest first; [visible] = chats on the home list (null = all). */
    fun waiting(tasks: Collection<TaskProgressCore.Task>, visible: Set<String>?, now: Long, titles: Map<String, String> = emptyMap(),
                windowMs: Long = 24 * 3_600_000L): List<Waiting> =
        tasks.filter { (visible?.contains(it.sid) ?: true) && now - it.updatedAt <= windowMs && it.st == TaskProgressCore.St.WAITING }
            .sortedByDescending { it.updatedAt }
            .map { Waiting(it.sid, titles[it.sid]?.takeIf { t -> t.isNotBlank() } ?: it.goal.ifBlank { "对话" }, it.reason.ifBlank { "等你回复" }, it.updatedAt) }

    /**
     * [Mines-38.9.8] user 2026-10-19: a marker on Home for unfinished work — every task that is not 完成 (进行中 / 等你 /
     * 出错 / 已停止) within [windowMs] (7 days), newest first; the badge shows the count and opens this list.
     */
    data class Open(val sid: String, val title: String, val st: TaskProgressCore.St, val line: String, val at: Long)

    fun unfinished(tasks: Collection<TaskProgressCore.Task>, visible: Set<String>?, now: Long, titles: Map<String, String> = emptyMap(),
                   windowMs: Long = 7 * 24 * 3_600_000L): List<Open> =
        tasks.filter { (visible?.contains(it.sid) ?: true) && now - it.updatedAt <= windowMs && it.st != TaskProgressCore.St.DONE }
            .sortedWith(compareBy<TaskProgressCore.Task> { rank(it.st) }.thenByDescending { it.updatedAt })
            .map { t ->
                val title = titles[t.sid]?.takeIf { x -> x.isNotBlank() } ?: t.goal.ifBlank { "对话" }
                val line = t.st.label + (t.reason.takeIf { it.isNotBlank() }?.let { " · " + it.take(40) } ?: (if (t.steps.isNotEmpty()) " · ${t.doneSteps}/${t.steps.size} 步" else ""))
                Open(t.sid, title, t.st, line, t.updatedAt)
            }

    private fun rank(st: TaskProgressCore.St) = when (st) {
        TaskProgressCore.St.WAITING -> 0; TaskProgressCore.St.ERROR -> 1; TaskProgressCore.St.RUNNING -> 2; TaskProgressCore.St.STOPPED -> 3; else -> 4
    }

    /** Badge text: "" for none, "99+" above 99. */
    fun badge(n: Int): String = when { n <= 0 -> ""; n > 99 -> "99+"; else -> n.toString() }

    enum class WxWhy(val line: String) {
        OK("天气正常"), STALE("显示的是 1 小时前的天气，正在刷新"), NO_PERMISSION("没有定位权限，也没设城市"),
        NO_FIX("手机还没给出位置"), FETCH_FAILED("天气接口连不上"), NEVER("还没取过天气")
    }

    fun weatherWhy(hasWeather: Boolean, ageMs: Long?, permission: Boolean, hasCity: Boolean, lastError: String?): WxWhy = when {
        hasWeather && ageMs != null && ageMs <= 3_600_000L -> WxWhy.OK
        hasWeather -> WxWhy.STALE
        !permission && !hasCity -> WxWhy.NO_PERMISSION
        lastError == "nofix" -> WxWhy.NO_FIX
        lastError == "fetch" -> WxWhy.FETCH_FAILED
        else -> WxWhy.NEVER
    }

    /** The small home chip when there is no weather to show (never nothing). */
    fun weatherChip(why: WxWhy): String = when (why) {
        WxWhy.NO_PERMISSION, WxWhy.NO_FIX -> "设置城市"
        WxWhy.FETCH_FAILED -> "天气没取到 · 点我"
        else -> "天气获取中…"
    }

    /** 自检: 「天气皮肤：夜晚 · 动画 · 按天气（多云）+ 时间 21:13」. */
    fun skyWhy(sceneZh: String, mode: String, enabled: Boolean, photoWallpaper: Boolean, weatherCode: Int?, hour: Int, minute: Int): String = when {
        !enabled -> "天气皮肤关着（外观调节）"
        photoWallpaper -> "首页用了照片壁纸，天气皮肤在照片下面不显示"
        else -> "天气皮肤：$sceneZh · " + (if (mode.startsWith("static")) "静态（${mode.removePrefix("static: ")}）" else if (mode.contains("still")) "静止画面" else if (mode.contains("slow")) "省电慢速" else "动画") +
            " · " + (if (weatherCode != null) "按天气（代码 $weatherCode）+ 时间" else "没有天气，按时间") + " %02d:%02d".format(hour, minute)
    }

    const val MIN_WIDTH_DP = 400f

    /** Compose density that keeps ≥ [MIN_WIDTH_DP] dp across the short edge (never larger than the system's). */
    fun density(systemDensity: Float, shortEdgePx: Int): Float {
        if (shortEdgePx <= 0 || systemDensity <= 0f) return systemDensity
        val cap = shortEdgePx / MIN_WIDTH_DP
        return if (systemDensity > cap) cap else systemDensity
    }
}

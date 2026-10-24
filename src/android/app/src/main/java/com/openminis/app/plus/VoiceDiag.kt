package com.openminis.app.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.1] Disconnect diary of the live voice call (user 16:09 「语音还是一下断一下断」):
 * every close / failure / hop is kept with time, close code, reason, route and network type
 * (last 200, survives restarts). Shown under 设置 → 测试与上报 and 语音设置 with one-tap copy,
 * and used to rank routes (fewest drops per minute first).
 */
object VoiceDiag {
    private const val FILE = "mines_voice_diag.json"
    private const val STATS = "mines_voice_route_stats"
    @Volatile private var app: Context? = null
    fun init(c: Context) { if (app == null) app = c.applicationContext }

    fun netType(c: Context?): String = runCatching {
        val cm = c?.getSystemService(android.net.ConnectivityManager::class.java) ?: return "?"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络"
        listOfNotNull(
            "Wi-Fi".takeIf { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) },
            "移动网络".takeIf { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) },
            "VPN".takeIf { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) },
            "以太网".takeIf { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) },
        ).joinToString("+").ifBlank { "其他" }
    }.getOrDefault("?")

    @Synchronized fun log(kind: String, code: Int, reason: String, route: String?, extra: String = "") {
        val c = app ?: return
        runCatching {
            val f = java.io.File(c.filesDir, FILE)
            val arr = if (f.exists()) JSONArray(f.readText()) else JSONArray()
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("k", kind).put("code", code).put("why", reason.take(200))
                .put("route", route ?: "").put("net", netType(c)).put("x", extra.take(120)))
            val trimmed = JSONArray(); val from = maxOf(0, arr.length() - 200)
            for (i in from until arr.length()) trimmed.put(arr.get(i))
            f.writeText(trimmed.toString())
        }
    }

    fun text(c: Context, limit: Int = 60): String = runCatching {
        val f = java.io.File(c.filesDir, FILE)
        if (!f.exists()) return "还没有通话断线记录"
        val arr = JSONArray(f.readText())
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
        val out = StringBuilder("Mines 语音断线记录（时间 · 事件 · 代码 · 线路 · 网络 · 原因）\n")
        for (i in maxOf(0, arr.length() - limit) until arr.length()) {
            val o = arr.getJSONObject(i)
            out.append(fmt.format(java.util.Date(o.optLong("t")))).append(" · ").append(o.optString("k")).append(" · ")
                .append(o.optInt("code")).append(" · ").append(o.optString("route").ifBlank { "-" }).append(" · ")
                .append(o.optString("net")).append(" · ").append(o.optString("why").ifBlank { "（服务器没写原因）" })
            o.optString("x").takeIf { it.isNotBlank() }?.let { out.append(" · ").append(it) }
            out.append('\n')
        }
        out.toString()
    }.getOrDefault("断线记录读不出来")

    fun clear(c: Context) { runCatching { java.io.File(c.filesDir, FILE).delete() } }

    // ---- route stability memory: connected time and drops per route label ----
    private fun sp(c: Context) = c.getSharedPreferences(STATS, Context.MODE_PRIVATE)
    fun recordUp(route: String, ms: Long) { val c = app ?: return; val p = sp(c); p.edit().putLong("up:$route", p.getLong("up:$route", 0) + ms).apply() }
    fun recordDrop(route: String) { val c = app ?: return; val p = sp(c); p.edit().putInt("drop:$route", p.getInt("drop:$route", 0) + 1).putLong("lastdrop:$route", System.currentTimeMillis()).apply() }
    fun recordGood(route: String) { val c = app ?: return; sp(c).edit().putString("last_good", route).apply() }
    fun lastGood(): String? = app?.let { sp(it).getString("last_good", null) }
    /** Drops per connected minute (lower = steadier); unknown routes score 0.5. */
    fun score(route: String): Double {
        val c = app ?: return 0.5
        val p = sp(c); val up = p.getLong("up:$route", 0); val d = p.getInt("drop:$route", 0)
        if (up < 60_000 && d == 0) return 0.5
        return d / maxOf(1.0, up / 60_000.0)
    }
    fun summary(): String {
        val c = app ?: return ""
        val p = sp(c).all
        val routes = p.keys.filter { it.startsWith("up:") || it.startsWith("drop:") }.map { it.substringAfter(':') }.distinct()
        if (routes.isEmpty()) return "还没有线路统计"
        return routes.joinToString("\n") { r ->
            val up = (p["up:$r"] as? Long ?: 0L) / 60_000; val d = p["drop:$r"] as? Int ?: 0
            "$r：连上 $up 分钟，断 $d 次"
        }
    }
}

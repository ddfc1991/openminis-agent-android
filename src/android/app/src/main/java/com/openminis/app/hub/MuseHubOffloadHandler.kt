package com.openminis.app.hub

import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * [T-muse-hub] `minis-muse` — the agent's side of the hub.
 *
 *   minis-muse status                      connected? which devices are online
 *   minis-muse ask <device> <text…>        give that device's Muse a whole task, wait for its answer
 *   minis-muse open <device> <url|pkg>     open a page or an app on that device
 *   minis-muse notify <device> <text…>     show a notification there
 *   minis-muse reconnect
 */
class MuseHubOffloadHandler : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val a = request.argv.drop(1)
        val usage = "usage: minis-muse status | ask <device> <text> | open <device> <url|package> | notify <device> <text> | reconnect\n"
        if (a.isEmpty()) return NativeOffloadResult(2, usage)
        return when (a[0]) {
            "status" -> {
                val sb = StringBuilder("hub: ${if (MuseHubClient.connected) "connected" else "NOT connected"}\n")
                val d = MuseHubClient.devices
                for (i in 0 until d.length()) {
                    val o = d.getJSONObject(i)
                    sb.append("- ${o.optString("name")} (${o.optString("kind")}, id ${o.optString("id")}) ${if (o.optBoolean("online")) "online" else "offline"}\n")
                }
                NativeOffloadResult(0, sb.toString())
            }
            "reconnect" -> { MuseHubClient.connect(); NativeOffloadResult(0, "reconnecting\n") }
            "ask", "open", "notify" -> {
                if (a.size < 3) return NativeOffloadResult(2, usage)
                val to = MuseHubClient.resolve(a[1]) ?: return NativeOffloadResult(3, "no online device named '${a[1]}' (try: minis-muse status)\n")
                val rest = a.drop(2).joinToString(" ")
                val (action, args, timeout) = when (a[0]) {
                    "ask" -> Triple("task", JSONObject().put("text", rest).put("conversation", "minis").put("language", "zh-CN"), 20 * 60_000L)
                    "open" -> Triple("open", if (rest.contains("://")) JSONObject().put("url", rest) else JSONObject().put("package", rest), 60_000L)
                    else -> Triple("notify", JSONObject().put("title", com.openminis.app.ui.brand.Brand.NAME).put("text", rest), 30_000L)
                }
                val r = MuseHubClient.call(to, action, args, timeout)
                if (r.optBoolean("ok")) {
                    val body = r.optJSONObject("body")
                    NativeOffloadResult(0, (body?.optString("text")?.takeIf { it.isNotEmpty() } ?: body?.toString() ?: "ok") + "\n")
                } else NativeOffloadResult(1, "failed: ${r.optString("error")} ${r.optString("message")}\n")
            }
            else -> NativeOffloadResult(2, usage)
        }
    }
}

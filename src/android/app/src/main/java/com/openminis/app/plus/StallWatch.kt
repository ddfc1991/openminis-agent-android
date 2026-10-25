package com.openminis.app.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.14.1 stall_watch] The agent must never sit silently on 「正在思考…」.
 *  - model stream: no chunk for [STREAM_IDLE_MS] (before the first one or mid-stream) → the call is cancelled with a
 *    [com.openminis.app.data.model.LLMError.TransientError] whose detail starts with [STALL]; the chat loop retries the same
 *    model once, then falls back to the next working model, and shows 「模型没响应，正在重试 / 已换 X」.
 *  - tools: each call has a limit ([StallWatchCore.toolLimitMs]); past it the call is cancelled and an error result goes back
 *    to the model so the loop continues.
 *  - every stall is logged: logcat (tag MinesStall), filesDir/mines-stalls.log (last 200), 远程诊断 when it is on.
 */
object StallWatch {
    const val STALL = "模型没响应"
    private const val FILE = "mines-stalls.log"
    @Volatile private var app: Context? = null
    private val recent = ArrayDeque<JSONObject>()

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    fun isStall(t: Throwable?): Boolean {
        val d = (t as? com.openminis.app.data.model.LLMError.TransientError)?.detail ?: return false
        return d.startsWith(STALL)
    }

    fun stallError(ms: Long, midStream: Boolean): com.openminis.app.data.model.LLMError.TransientError =
        com.openminis.app.data.model.LLMError.TransientError("$STALL（${ms / 1000} 秒${if (midStream) "没有新输出" else "没有任何输出"}）")

    /** kind: stream_first | stream_mid | retry | fallback | tool_timeout | gave_up */
    fun log(kind: String, model: String, detail: String) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("model", model).put("detail", detail.take(200))
        synchronized(recent) { recent.addLast(o); while (recent.size > 50) recent.removeFirst() }
        android.util.Log.w("MinesStall", "$kind model=$model $detail")
        runCatching { com.openminis.app.logging.AppLogger.warning("MinesStall", "$kind model=$model $detail") }
        runCatching { Diag.event("stall", o.toString()) }
        val c = app ?: return
        runCatching {
            val f = java.io.File(c.filesDir, FILE)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + o.toString()
            f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
        }
    }

    fun json(): JSONArray = synchronized(recent) { JSONArray().apply { recent.forEach { put(it) } } }
}

/** pure parts (unit-tested in StallWatchCoreTest) */
object StallWatchCore {
    /** no token / event for this long → the model call counts as stalled (user, 2026-10-25: 40 s) */
    const val STREAM_IDLE_MS = 40_000L
    const val TOOL_DEFAULT_MS = 90_000L
    const val TOOL_BROWSER_MS = 180_000L
    const val TOOL_APP_AGENT_MS = 180_000L
    /** shell_execute carries its own timeout (default 900 s): the watchdog only catches a shell that overruns it */
    const val SHELL_MARGIN_MS = 30_000L

    /** null = no watchdog (the call waits for the user on purpose) */
    fun toolLimitMs(name: String, argsJson: String): Long? {
        val a = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        return when (name) {
            "shell_execute" -> a.optInt("timeout", 900).coerceIn(1, 3600) * 1000L + SHELL_MARGIN_MS
            "browser_use" -> when (a.optString("action")) {
                "wait_for_human" -> null           // the page is handed to the user (payment, approval)
                else -> TOOL_BROWSER_MS
            }
            "app_agent" -> TOOL_APP_AGENT_MS
            // [38.9.15] accounts login / secret can wait on the user's 「选账号」 / secure input / login card on purpose
            "accounts" -> if (a.optString("action") in setOf("login", "secret")) null else TOOL_DEFAULT_MS
            else -> TOOL_DEFAULT_MS
        }
    }

    fun toolTimeoutText(name: String, ms: Long): String =
        "工具 $name 超过 ${ms / 1000} 秒没有结束，已被取消（不是你的错）。换个办法继续：缩小范围、换一个工具，或者把卡在哪一步告诉用户。"

    /** the small status line under the spinner */
    fun retryLine(): String = "${StallWatch.STALL}，正在重试…"
    fun switchedLine(model: String): String = "${StallWatch.STALL}，已换 $model"
}

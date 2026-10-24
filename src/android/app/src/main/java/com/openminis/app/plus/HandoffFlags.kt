package com.openminis.app.plus

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.13 handoff_v2] feature flag (default on; 设置 → 浏览器验证 / 实验功能). Master `handoff_v2` off = exactly the
 * 38.9.12.1 behaviour. Sub-switches (each also needs the master; `tab` and `label` refine `resume`, so they need it too):
 *  - handoff_v2.resume — the wait leaves the 75 s browser action; event-driven resume; new turn when the turn had ended;
 *    watchdog; 「继续接管」;
 *  - handoff_v2.tab — 去那个页面 opens the recorded tab; the agent adopts the tab the user finished on; user-control lock;
 *  - handoff_v2.oauth — e-mail / phone sign-up first; Google / Apple only as a Chrome hand-off the user starts;
 *  - handoff_v2.label — task name + site + action in every popup / notification, deep link to the conversation + tab.
 * 远程诊断 can switch each one on / off for this process only (DiagCore CONFIG_LABELS), never persisted.
 */
object HandoffFlags {
    const val MASTER = "handoff_v2"
    const val RESUME = "handoff_v2.resume"
    const val TAB = "handoff_v2.tab"
    const val OAUTH = "handoff_v2.oauth"
    const val LABEL = "handoff_v2.label"
    val SUBS = listOf(RESUME, TAB, OAUTH, LABEL)
    val ZH = linkedMapOf(MASTER to "交接 v2（验证/登录后自动继续）", RESUME to "弹窗关掉后立即继续", TAB to "回到你操作的那个标签页",
        OAUTH to "注册优先用邮箱，Google/Apple 交给 Chrome", LABEL to "弹窗写明项目和网站")

    private const val PREFS = "mines_experiments"
    @Volatile private var app: Context? = null
    /** 远程诊断 overrides (in memory only) */
    private val remote = ConcurrentHashMap<String, Boolean>()
    /** bumped on every change so Compose can re-read */
    val rev = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    private fun stored(key: String): Boolean = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(key, true) ?: true

    private fun raw(key: String): Boolean = remote[key] ?: stored(key)

    /** is [key] (a sub-switch or the master) in effect now */
    fun on(key: String): Boolean {
        if (!raw(MASTER)) return false
        if (key == MASTER) return true
        if ((key == TAB || key == LABEL) && !raw(RESUME)) return false
        return raw(key)
    }

    /** the user's own switch (settings UI) */
    fun userValue(key: String): Boolean = stored(key)

    fun set(c: Context, key: String, value: Boolean) {
        init(c)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
        rev.value++
    }

    /** 远程诊断 set_config: "on" / "off" / "default" (= back to the user's value) */
    fun setRemote(key: String, value: String) {
        if (key != MASTER && key !in SUBS) return
        if (value == "default") remote.remove(key) else remote[key] = value == "on"
        rev.value++
    }

    fun remoteValue(key: String): String = remote[key]?.let { if (it) "on" else "off" } ?: "default"
}

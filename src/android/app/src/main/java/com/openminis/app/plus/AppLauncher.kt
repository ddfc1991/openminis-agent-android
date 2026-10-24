package com.openminis.app.plus

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.ShizukuManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Mines-launch-29] Open another app from the agent, honestly.
 *
 * build-28 bug: `android-a11y-cli open <pkg>` (and `do "open <pkg>"`) failed
 * for every third-party app on the realme (Android 16). Two causes in code:
 *  1. Package visibility. targetSdk 35 and the manifest had no
 *     `<queries>` entry for launcher activities and no QUERY_ALL_PACKAGES, so
 *     `getLaunchIntentForPackage("com.tencent.mm")` returned null for every
 *     app the system didn't make visible — the open failed before trying.
 *     (Manifest now declares both.)
 *  2. Background activity launch (BAL) limits on Android 14–16. The open ran
 *     from the app process with Mines in the background; `startActivity`
 *     does not throw when BAL blocks it, so a launch that never happened was
 *     reported as "打开了". Since Android 15, holding SYSTEM_ALERT_WINDOW only
 *     exempts an app that currently has a visible overlay window.
 *
 * Order now (first that is allowed wins), then verify the app reached the
 * foreground:
 *   a. accessibility service context (a system-bound a11y service is BAL-exempt),
 *   b. Shizuku `am start -n` (shell uid may start activities from background),
 *   c. a 1-px overlay window held during the start (BAL "visible window"
 *      exemption; needs 悬浮窗 permission),
 *   d. plain start when Mines itself is in the foreground,
 *   e. a notification 「点这里打开 X」 (one tap for the user) — reported as
 *      not opened, never as success.
 */
object AppLauncher {
    private const val TAG = "AppLauncher"
    private const val CHANNEL = "mines_open_app"

    data class Outcome(val ok: Boolean, val message: String, val pkg: String? = null, val via: String = "")

    /** Package name or app label → launchable package (null = not found). */
    fun resolve(context: Context, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val pm = context.packageManager
        if (runCatching { pm.getLaunchIntentForPackage(q) }.getOrNull() != null) return q
        val apps = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        }.getOrDefault(emptyList())
        fun label(r: android.content.pm.ResolveInfo) = runCatching { r.loadLabel(pm).toString() }.getOrDefault("")
        return (apps.firstOrNull { it.activityInfo.packageName.equals(q, true) }
            ?: apps.firstOrNull { label(it).equals(q, true) }
            ?: apps.firstOrNull { label(it).contains(q, true) })?.activityInfo?.packageName
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Blocking (waits for the foreground check); not on the main thread. */
    fun open(context: Context, query: String): Outcome {
        val app = context.applicationContext
        val pkg = resolve(app, query) ?: return Outcome(false,
            "找不到「$query」：没有这个包名，也没有叫这个名字的应用（已安装的应用都能看到）。")
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Outcome(false, "「${label(app, pkg)}」没有可以打开的主界面（$pkg）", pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val name = label(app, pkg)
        val tried = ArrayList<String>()

        fun verified(via: String): Outcome? {
            when (waitForeground(app, pkg, 2500)) {
                true -> return Outcome(true, "打开了「$name」（$pkg，经 $via）", pkg, via)
                null -> return Outcome(true, "已请求打开「$name」（经 $via）。没开无障碍/Shizuku，无法确认它真的到了前台。", pkg, via)
                false -> tried += "$via：系统没让它到前台"
            }
            return null
        }

        MinisAccessibilityService.getInstance()?.let { svc ->
            val ok = onMain { runCatching { svc.startActivity(Intent(intent)) }.isSuccess } == true
            if (ok) verified("无障碍")?.let { return it } else tried += "无障碍：启动失败"
        }
        if (runCatching { ShizukuManager.refresh(); ShizukuManager.isReady() }.getOrDefault(false)) {
            val comp = intent.component?.flattenToShortString()
            val argv = if (comp != null) arrayOf("am", "start", "-n", comp, "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER)
            else arrayOf("monkey", "-p", pkg, "-c", Intent.CATEGORY_LAUNCHER, "1")
            val r = ShizukuManager.runProcess(argv, timeoutMs = 8_000)
            if (r.exitCode == 0 && !r.combined.contains("Error", true)) verified("Shizuku")?.let { return it }
            else tried += "Shizuku：${r.combined.lines().lastOrNull { it.isNotBlank() }?.take(80) ?: "exit ${r.exitCode}"}"
        } else {
            val st = runCatching { ShizukuManager.snapshot.value.state }.getOrNull()
            if (st == ShizukuManager.State.NEED_PERMISSION) tried += "Shizuku：还没授权给 Mines"
        }
        if (Settings.canDrawOverlays(app)) {
            val ok = withVisibleWindow(app) { runCatching { app.startActivity(Intent(intent)) }.isSuccess } == true
            if (ok) verified("悬浮窗")?.let { return it } else tried += "悬浮窗：启动失败"
        } else tried += "悬浮窗权限没开"
        if (isForeground(app)) {
            runCatching { app.startActivity(Intent(intent)) }
            verified("前台")?.let { return it }
        }
        postTapToOpen(app, intent, name)
        return Outcome(false, "没能直接打开「$name」：安卓不允许后台应用直接打开别的应用。" +
            "已发一条通知「点这里打开 $name」。要让 AI 以后能直接打开：打开 Mines 的无障碍服务，或给 Mines 悬浮窗权限，或授权 Shizuku。" +
            "（试过：${tried.joinToString("；")}）", pkg, "notification")
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = CountDownLatch(1)
        var out: T? = null
        Handler(Looper.getMainLooper()).post { try { out = runCatching(block).getOrNull() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }

    /**
     * Run [block] while Mines shows a 1×1 px, nearly transparent overlay
     * window (BAL "app has a visible window" exemption on Android 14–16).
     * The window is removed 1.5 s later. Through [Overlays] like every other
     * floating window.
     */
    fun <T> withVisibleWindow(context: Context, focusable: Boolean = false, holdMs: Long = 1500, block: (View) -> T): T? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val lp = WindowManager.LayoutParams(1, 1, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
        }
        val shown = CountDownLatch(1)
        var view: View? = null
        val added = onMain {
            val v = View(context).apply { setBackgroundColor(0x05000000) ; alpha = 0.05f }
            view = v
            v.viewTreeObserver.addOnWindowAttachListener(object : android.view.ViewTreeObserver.OnWindowAttachListener {
                override fun onWindowAttached() { v.post { shown.countDown() } }
                override fun onWindowDetached() {}
            })
            Overlays.add(wm, "launch-helper", v, lp)
        }
        if (added != true) return null
        shown.await(800, TimeUnit.MILLISECONDS)
        Thread.sleep(120)
        return try {
            view?.let { v -> onMain { block(v) } }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({ Overlays.remove(wm, view) }, holdMs)
        }
    }

    fun isForeground(context: Context): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        am.runningAppProcesses.orEmpty().any { it.pid == android.os.Process.myPid() &&
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }.getOrDefault(false)

    /** Foreground package as seen by accessibility (null = cannot tell). */
    fun foregroundPackage(): String? = runCatching {
        val svc = MinisAccessibilityService.getInstance() ?: return null
        svc.windows?.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName?.toString() ?: svc.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    private fun foregroundViaShizuku(): String? = runCatching {
        if (!ShizukuManager.isReady()) return null
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1"), timeoutMs = 4000)
        Regex("u0 ([a-zA-Z0-9_.]+)/").find(r.stdout)?.groupValues?.get(1)
    }.getOrNull()

    /**
     * True once [pkg] is in front. When nothing can tell us (no a11y, no
     * Shizuku) we fall back to "Mines left the foreground" after a start
     * request, which is the best a plain app can see.
     */
    fun waitForeground(context: Context, pkg: String, ms: Long): Boolean? {
        val until = System.currentTimeMillis() + ms
        val canSee = MinisAccessibilityService.getInstance() != null || ShizukuManager.isReady()
        val wasFg = isForeground(context)
        if (!canSee && !wasFg) return null
        while (System.currentTimeMillis() < until) {
            Thread.sleep(250)
            if (canSee) {
                val fg = foregroundPackage() ?: foregroundViaShizuku()
                if (fg == pkg) return true
            } else if (!isForeground(context)) return true
        }
        return false
    }

    /** Start any activity intent from the background (a11y → 悬浮窗 → foreground). */
    fun startFromBackground(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        MinisAccessibilityService.getInstance()?.let { svc ->
            if (onMain { runCatching { svc.startActivity(Intent(intent)) }.isSuccess } == true) return true
        }
        if (Settings.canDrawOverlays(app)) {
            if (withVisibleWindow(app) { runCatching { app.startActivity(Intent(intent)) }.isSuccess } == true) return true
        }
        if (isForeground(app)) return runCatching { app.startActivity(Intent(intent)) }.isSuccess
        return false
    }

    fun postTapToOpen(context: Context, intent: Intent, name: String) = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI 打开应用", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("点这里打开「$name」")
            .setContentText("AI 想打开这里，安卓需要你点一下")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(0x4D0E + (name.hashCode() and 0xff), n)
    }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
}

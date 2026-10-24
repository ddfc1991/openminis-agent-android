package com.openminis.app.plus

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [Mines-crash-28] Last-crash recorder. Chained in front of ACRA's handler as
 * early as possible (attachBaseContext), it writes the stack trace of an
 * uncaught exception to filesDir/mines-last-crash.txt synchronously before the
 * process dies, and logs it under tag MinesCrash. The next launch offers it
 * (copy / dismiss), and 设置 → 关于 → 上次崩溃 keeps it until cleared, so a
 * crash on a phone without adb can still be reported.
 */
object MinesCrash {
    private const val TAG = "MinesCrash"
    private const val FILE = "mines-last-crash.txt"
    private const val SEEN = "mines-last-crash.seen"
    @Volatile private var installed = false

    fun file(context: Context) = File(context.filesDir, FILE)

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            // [Mines-38.9.10 i29] OutOfMemoryError: MemGuard records the heap summary; a background one (first in 10 min) is absorbed
            val absorbed = runCatching { MemGuard.onUncaught(app, thread, t) }.getOrDefault(false)
            if (absorbed) return@setDefaultUncaughtExceptionHandler
            if (!MemGuardCore.isOom(t)) runCatching { record(app, thread, t) }
            // [Mines-38.9.12.1] the process dies now: an OOM (or a 2nd crash in 10 min) makes the next launch a safe start
            runCatching { SafeStart.noteFatal(app, MemGuardCore.isOom(t)) }
            prior?.uncaughtException(thread, t)
        }
    }

    fun record(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val build = com.openminis.app.BuildConfig.MINES_BUILD.toString()
        val text = buildString {
            append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
            append("Mines build ").append(build)
            append(" · ").append(com.openminis.app.BuildConfig.VERSION_NAME).append('\n')
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("线程：").append(thread.name).append('\n')
            append(sw.toString().take(60_000))
        }
        // [Mines-38.9.11.3] keys / passwords / tokens / phone numbers never reach the crash record or logcat
        val safe = runCatching { com.openminis.app.crash.CrashRedactCore.redact(text) }.getOrDefault(text)
        Log.e(TAG, safe)
        runCatching {
            context.filesDir.mkdirs()
            file(context).writeText(safe)
            File(context.filesDir, SEEN).delete()
        }
    }

    /** [Mines-38.9.10 i29] an OutOfMemoryError: the normal crash record plus MemGuard's heap summary */
    fun recordOom(context: Context, thread: Thread, t: Throwable, summary: String) {
        record(context, thread, t)
        runCatching { file(context).appendText("\n—— 内存摘要 ——\n" + com.openminis.app.crash.CrashRedactCore.redact(summary.take(4000)) + "\n") }
        Log.e(TAG, "OOM summary: " + summary.take(2000))
    }

    fun last(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    /** A crash not yet shown to the user (one prompt per crash). */
    fun unseen(context: Context): String? {
        val f = file(context)
        if (!f.exists() || File(context.filesDir, SEEN).exists()) return null
        return last(context)
    }

    fun markSeen(context: Context) { runCatching { File(context.filesDir, SEEN).writeText("1") } }

    fun clear(context: Context) { runCatching { file(context).delete(); File(context.filesDir, SEEN).delete() } }
}

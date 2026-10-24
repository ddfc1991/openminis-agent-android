package com.openminis.app.crash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [Mines-38.9.11.3] user: 「卡、闪退」. On the launch after a crash / ANR / native crash (ACRA crash-*.log, native-crash-*.log,
 * exitinfo-*.log from ApplicationExitInfo) one small card offers 「复制崩溃日志」: the newest report, redacted
 * ([CrashRedactCore]), straight to the clipboard. Each report is offered once. All file work off the main thread.
 */
object CrashCard {
    private const val PREFS = "mines_crash_card"
    data class Offer(val file: File, val title: String)
    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    private fun isReport(f: File) = f.isFile && f.name.endsWith(".log") &&
        (f.name.startsWith("crash-") || f.name.startsWith("native-crash-") || f.name.startsWith("exitinfo-"))
    private const val LAST_JVM = "mines-last-crash.txt"   // MinesCrash (written synchronously by the uncaught handler)

    /** background thread, after ExitInfoCollector.collect */
    fun check(c: Context) = runCatching {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("seen", -1L)
        val files = File(c.filesDir, "logs").listFiles()?.filter { isReport(it) }.orEmpty() +
            listOfNotNull(File(c.filesDir, LAST_JVM).takeIf { it.isFile })
        val newest = files.maxByOrNull { it.lastModified() } ?: return@runCatching
        if (seen < 0L) { p.edit().putLong("seen", newest.lastModified()).apply(); return@runCatching }   // first run of this version: old reports are not offered
        if (newest.lastModified() <= seen) return@runCatching
        val head = runCatching { newest.bufferedReader().use { r -> CharArray(400).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("")
        _offer.value = Offer(newest, CrashRedactCore.title(head))
    }

    fun dismiss(c: Context, o: Offer) {
        _offer.value = null
        runCatching { com.openminis.app.plus.MinesCrash.markSeen(c) }
        Thread { runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("seen", o.file.lastModified()).apply() } }.start()
    }

    /** blocking — call off the main thread: the redacted, capped text for the clipboard */
    fun text(o: Offer): String = runCatching { CrashRedactCore.forClipboard(o.file.readText()) }.getOrDefault("（读不到日志文件）")
}

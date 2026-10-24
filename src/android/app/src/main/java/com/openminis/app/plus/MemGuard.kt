package com.openminis.app.plus

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * [Mines-38.9.10 i28] memory guard (user's 1.23.8 OOM at the 512 MB heap cap after getting gradually laggier):
 *  - watches the Java heap every 30 s and on onTrimMemory; when it runs high it trims what can be rebuilt (registered
 *    trimmers: image caches, sky stills, …) and writes one line to files/mem-watch.log (last 200 lines);
 *  - an OutOfMemoryError on a background worker: the heap summary + stack go to the crash log, caches are trimmed and the
 *    app keeps running once per 10 min (main thread or a repeat → crash as before, with the summary in the log);
 *  - [heapJson] for the smoke soak / 自检.
 */
object MemGuard {
    private const val TAG = "MinesMem"
    private val trimmers = java.util.concurrent.CopyOnWriteArrayList<Pair<String, () -> String>>()
    @Volatile private var lastTrim = 0L
    @Volatile private var lastOom = 0L
    @Volatile private var app: Context? = null
    @Volatile var trims: Int = 0

    /** name → action that frees memory and returns a short note of what it freed */
    fun register(name: String, trim: () -> String) { if (trimmers.none { it.first == name }) trimmers += name to trim }

    fun install(a: Application) {
        if (app != null) return
        app = a
        a.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim("系统内存紧（$level）")
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("") override fun onLowMemory() { trim("系统内存不足") }
        })
        register("图片缓存") {
            runCatching { coil.Coil.imageLoader(a).memoryCache?.let { val n = it.size; it.clear(); "清掉 ${MemGuardCore.mb(n.toLong())}" } ?: "" }.getOrDefault("")
        }
        Thread({
            while (true) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                runCatching {
                    val r = Runtime.getRuntime(); val used = r.totalMemory() - r.freeMemory()
                    val lv = MemGuardCore.level(used, r.maxMemory())
                    if (MemGuardCore.shouldTrim(lv, System.currentTimeMillis(), lastTrim)) trim("堆 ${MemGuardCore.mb(used)} / ${MemGuardCore.mb(r.maxMemory())}（$lv）")
                }
            }
        }, "mines-memguard").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun trim(why: String): String {
        lastTrim = System.currentTimeMillis(); trims++
        val notes = trimmers.mapNotNull { (n, f) -> runCatching { f() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "$n $it" } }
        val line = "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} $why → ${notes.joinToString("；").ifBlank { "无可清" }}"
        Log.i(TAG, line)
        app?.let { c -> runCatching {
            val f = File(c.filesDir, "mem-watch.log")
            val old = if (f.exists()) f.readLines().takeLast(199) else emptyList()
            f.writeText((old + line).joinToString("\n") + "\n")
        } }
        return line
    }

    fun summary(c: Context?): String {
        val r = Runtime.getRuntime()
        val pss = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() }.getOrDefault(0L)
        val extra = mutableListOf<Pair<String, String>>()
        extra += "清理次数" to trims.toString()
        runCatching { extra += "聊天缓存" to com.openminis.app.ui.chat.ChatViewModelStore.debugSize() }
        return MemGuardCore.summary(r.totalMemory() - r.freeMemory(), r.totalMemory(), r.maxMemory(), Debug.getNativeHeapAllocatedSize(), pss, Thread.activeCount(), extra)
    }

    fun heapJson(): String {
        val r = Runtime.getRuntime()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return org.json.JSONObject().put("used", r.totalMemory() - r.freeMemory()).put("total", r.totalMemory()).put("max", r.maxMemory())
            .put("native", Debug.getNativeHeapAllocatedSize()).put("pssKb", mi.totalPss).put("threads", Thread.activeCount()).put("trims", trims)
            .put("javaHeapKb", runCatching { mi.getMemoryStat("summary.java-heap").toLong() }.getOrDefault(-1L))
            .put("graphicsKb", runCatching { mi.getMemoryStat("summary.graphics").toLong() }.getOrDefault(-1L)).toString()
    }

    /**
     * Called by MinesCrash's handler first. Returns true when the OOM was absorbed (the caller must NOT pass it on):
     * background thread, first time in 10 min. The summary is recorded either way.
     */
    fun onUncaught(c: Context, thread: Thread, t: Throwable): Boolean {
        if (!MemGuardCore.isOom(t)) return false
        val main = thread === Looper.getMainLooper().thread
        val now = System.currentTimeMillis()
        val survive = MemGuardCore.surviveOom(main, now, lastOom)
        lastOom = now
        val freed = runCatching { trim("内存不足（OutOfMemoryError）") }.getOrDefault("")
        runCatching { System.gc() }
        runCatching { MinesCrash.recordOom(c, thread, t, summary(c) + freed + "\n" + (if (survive) "（后台线程，已清缓存，App 继续运行）" else "（主线程或 10 分钟内第二次，App 退出）")) }
        return survive
    }
}

package com.openminis.app.plus

import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** One process the sandbox started (same Linux uid as the app). */
data class SandboxProc(val pid: Int, val name: String, val rssKb: Long, val cpuTicks: Long) {
    val heavy: Boolean get() = HEAVY.any { name.contains(it, ignoreCase = true) }
    companion object {
        val HEAVY = listOf("chrom", "playwright", "node", "python", "java", "firefox", "ffmpeg", "gradle", "npm", "pip")
    }
}

/**
 * Sandbox housekeeping: lists the processes the sandbox left running, frees
 * memory on request, and (optionally) closes heavy processes that have sat
 * idle — no CPU used — for 10 minutes.
 */
object SandboxCare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lastTicks = HashMap<Int, Pair<Long, Long>>() // pid -> (ticks, sinceMs)

    fun list(): List<SandboxProc> {
        val me = Process.myPid()
        val uid = Process.myUid()
        val out = ArrayList<SandboxProc>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            runCatching {
                val status = File(d, "status").readText()
                val u = Regex("Uid:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toInt()
                if (u != uid) return@runCatching
                val rss = Regex("VmRSS:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLong() ?: 0L
                if (rss == 0L) return@runCatching
                val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrDefault("")
                val comm = runCatching { File(d, "comm").readText().trim() }.getOrDefault("")
                val name = cmd.split(' ').firstOrNull { it.isNotBlank() && !it.contains("libproot") }
                    ?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: comm
                // Skip the app's own helper processes (e.g. :remote services).
                if (name.startsWith("com.openminis")) return@runCatching
                val stat = File(d, "stat").readText().substringAfterLast(')').trim().split(' ')
                val ticks = (stat.getOrNull(11)?.toLongOrNull() ?: 0L) + (stat.getOrNull(12)?.toLongOrNull() ?: 0L)
                out += SandboxProc(pid, name.ifBlank { "pid $pid" }, rss, ticks)
            }
        }
        return out.sortedByDescending { it.rssKb }
    }

    fun kill(pid: Int) { runCatching { Process.killProcess(pid) } }

    /** Close every heavy process (browsers, node, python…). Returns how many. */
    fun cleanHeavy(): Int {
        val heavy = list().filter { it.heavy }
        heavy.forEach { kill(it.pid) }
        return heavy.size
    }

    /** Auto-clean loop: heavy + idle for 10 minutes -> closed. */
    fun startAuto(context: Context) {
        PlusPrefs.init(context)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (PlusPrefs.state.value.autoClean) {
                    val now = System.currentTimeMillis()
                    val procs = list()
                    val alive = procs.map { it.pid }.toSet()
                    lastTicks.keys.retainAll(alive)
                    for (p in procs) {
                        val prev = lastTicks[p.pid]
                        if (prev == null || prev.first != p.cpuTicks) lastTicks[p.pid] = p.cpuTicks to now
                        else if (p.heavy && now - prev.second > 10 * 60_000) { kill(p.pid); lastTicks.remove(p.pid) }
                    }
                }
                delay(2 * 60_000)
            }
        }
    }
}

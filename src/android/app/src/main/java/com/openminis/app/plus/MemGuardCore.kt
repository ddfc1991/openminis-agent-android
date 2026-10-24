package com.openminis.app.plus

/**
 * [Mines-38.9.10 i28] user 2026-10-19 16:21: OutOfMemoryError on a DefaultDispatcher worker at the 512 MB heap cap after the
 * app had been getting laggier for a while. Pure rules (unit-tested) for the memory guard: pressure level, when to trim,
 * whether an OOM on a background thread may be survived, the heap summary written to the crash log, bounded text.
 */
object MemGuardCore {
    enum class Level { OK, HIGH, CRITICAL }

    /** heap pressure from used / max (Java heap) */
    fun level(usedBytes: Long, maxBytes: Long): Level {
        if (maxBytes <= 0) return Level.OK
        val f = usedBytes.toDouble() / maxBytes
        return when { f >= 0.88 -> Level.CRITICAL; f >= 0.72 -> Level.HIGH; else -> Level.OK }
    }

    /** trim at most once per [minGapMs] unless critical */
    fun shouldTrim(level: Level, nowMs: Long, lastTrimMs: Long, minGapMs: Long = 120_000L): Boolean = when (level) {
        Level.OK -> false
        Level.CRITICAL -> nowMs - lastTrimMs >= 10_000L
        Level.HIGH -> nowMs - lastTrimMs >= minGapMs
    }

    /** true when [t] (or any cause) is an OutOfMemoryError */
    fun isOom(t: Throwable?): Boolean {
        var c = t; var n = 0
        while (c != null && n < 8) { if (c is OutOfMemoryError) return true; c = c.cause; n++ }
        return false
    }

    /**
     * An OOM on a background worker thread is survived ONCE per [windowMs] (the guard trims caches and the worker's task is
     * lost); on the main thread, or a second time inside the window, the app crashes as before (a zombie is worse).
     */
    fun surviveOom(mainThread: Boolean, nowMs: Long, lastOomMs: Long, windowMs: Long = 600_000L): Boolean =
        !mainThread && (lastOomMs <= 0L || nowMs - lastOomMs > windowMs)

    fun mb(b: Long): String = "%.1f MB".format(b / 1048576.0)

    /** heap summary for the crash log / 自检 (all inputs in bytes; [extra] = name → size lines from the trimmers) */
    fun summary(used: Long, total: Long, max: Long, nativeAlloc: Long, pssKb: Long, threads: Int, extra: List<Pair<String, String>> = emptyList()): String = buildString {
        append("Java 堆：已用 ").append(mb(used)).append(" / 上限 ").append(mb(max)).append("（已申请 ").append(mb(total)).append("，")
        append(if (max > 0) "${(used * 100 / max)}%" else "?").append("）\n")
        append("Native：").append(mb(nativeAlloc)).append(" · PSS：").append(mb(pssKb * 1024)).append(" · 线程 ").append(threads).append('\n')
        for ((k, v) in extra) append(k).append("：").append(v).append('\n')
    }

    /** keep the head and the tail of a long text, with a note of what was cut (bounded log / output buffers) */
    fun clip(s: String, max: Int, tail: Int = max / 4): String {
        if (s.length <= max) return s
        val head = (max - tail).coerceAtLeast(0)
        return s.take(head) + "\n…（中间省略 ${s.length - head - tail} 字）…\n" + s.takeLast(tail)
    }
}

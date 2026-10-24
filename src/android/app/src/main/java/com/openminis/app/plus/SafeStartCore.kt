package com.openminis.app.plus

/**
 * [Mines-38.9.12.1] user 2026-10-23 06:57: 1.23.11.3.1 died of OutOfMemoryError (Java heap 478/512 MB, 5/5 agent sessions
 * running, one with 275 messages, a browser screenshot asking for a 36 MB buffer) and then would not start again.
 * Pure rules (unit-tested) for the hotfix:
 *  - screenshots are captured small (long edge ≤ [SHOT_MAX_EDGE]) — [shotScale];
 *  - after an OOM death (or 2 crashes inside 10 min) the next launch is a SAFE START: home, not the last chat, nothing
 *    resumes by itself, one card names the paused tasks — [noteCrash] / [safeStart] / [cardTitle];
 *  - no new agent session starts while the Java heap is ≥ [HEAP_GATE] of its cap — [slotLimit];
 *  - only the newest [KEEP_IMAGES] tool images keep their bytes in a running conversation's memory — [imagesToRelease].
 */
object SafeStartCore {
    const val SHOT_MAX_EDGE = 1280
    /** full-page captures: at most this many pixels (≈ 24 MB as ARGB) */
    const val SHOT_MAX_PIXELS_FULL = 6_000_000L
    const val CRASH_WINDOW_MS = 600_000L
    const val MARKER_MAX_AGE_MS = 6L * 3_600_000L
    const val HEAP_GATE = 0.75
    const val KEEP_IMAGES = 6

    /**
     * Scale (0 < s ≤ 1) for a [w]×[h] capture. Viewport shots: long edge ≤ [SHOT_MAX_EDGE]. Full page: width ≤
     * [SHOT_MAX_EDGE] and at most [SHOT_MAX_PIXELS_FULL] pixels.
     */
    fun shotScale(w: Int, h: Int, fullPage: Boolean = false): Double {
        if (w <= 0 || h <= 0) return 1.0
        if (!fullPage) return minOf(1.0, SHOT_MAX_EDGE.toDouble() / maxOf(w, h))
        val byW = SHOT_MAX_EDGE.toDouble() / w
        val byPx = Math.sqrt(SHOT_MAX_PIXELS_FULL.toDouble() / (w.toLong() * h))
        return minOf(1.0, byW, byPx)
    }

    /** output size for [shotScale] (never 0) */
    fun scaled(w: Int, h: Int, s: Double): Pair<Int, Int> =
        maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())

    /** crash timestamps kept after a crash at [now]: the ones inside [windowMs] plus now (bounded) */
    fun noteCrash(prev: List<Long>, now: Long, windowMs: Long = CRASH_WINDOW_MS): List<Long> =
        (prev.filter { now - it in 0..windowMs } + now).takeLast(8)

    /** the next launch is a safe start after an OOM death, or when 2+ crashes fell inside the window */
    fun safeStart(oom: Boolean, crashesInWindow: Int): Boolean = oom || crashesInWindow >= 2

    /** a marker is honoured for [maxAgeMs]; an older one (the user did not open Mines for hours) is ignored */
    fun markerLive(markerAt: Long, now: Long, maxAgeMs: Long = MARKER_MAX_AGE_MS): Boolean =
        markerAt > 0 && now >= markerAt && now - markerAt <= maxAgeMs

    fun cardTitle(paused: Int, oom: Boolean): String {
        val why = if (oom) "上次内存不足退出" else "上次连续出错退出"
        return if (paused > 0) "${why}，已暂停 $paused 个任务" else "${why}，已为你回到首页"
    }

    fun cardBody(paused: Int): String =
        if (paused > 0) "这些任务不会自己继续。想接着做哪个，点它旁边的「继续」。" else "没有任务被打断。可以正常使用了。"

    /**
     * Concurrent agent sessions allowed now: [maxConcurrent] normally; while the heap is ≥ [HEAP_GATE] of its cap no NEW
     * session starts (the limit is what already runs, at least 1 so nothing can deadlock).
     */
    fun slotLimit(usedBytes: Long, maxBytes: Long, maxConcurrent: Int, running: Int): Int {
        if (maxBytes <= 0) return maxConcurrent
        if (usedBytes.toDouble() / maxBytes < HEAP_GATE) return maxConcurrent
        return running.coerceIn(1, maxConcurrent)
    }

    /**
     * how many of [total] image parts (oldest first) lose their in-memory bytes: none until there are more than
     * [keep] + [slack], then down to the newest [keep] in one go (batched, so the request prefix — and the provider's
     * prompt cache — changes once per [slack] new images, not on every turn)
     */
    fun imagesToRelease(total: Int, keep: Int = KEEP_IMAGES, slack: Int = 4): Int = if (total > keep + slack) total - keep else 0

    /** the text the model sees in place of a released image */
    fun releasedImageNote(linuxPath: String?): String =
        if (linuxPath != null) "[older screenshot moved out of memory. Original at $linuxPath — use `read_image $linuxPath` if you need to see it again.]"
        else "[older screenshot moved out of memory to save RAM; it is no longer attached.]"
}

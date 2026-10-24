package com.openminis.app.plus

/**
 * [Mines-38.9.10 i30] performance rules of the v7 living sky (user 「优化下」 after the 38.9.10 smoke: 230 main-thread hangs
 * ≥ 3 s + 4 ANRs on the emulator's software GPU, 38.9.9 photo sky had none). Pure, unit-tested:
 *  - a software GL renderer (SwiftShader / llvmpipe / softpipe …) never runs the procedural shader → pre-rendered stills
 *    (the only way to reach [STILLS]);
 *  - live frame caps: 12 fps (雨 glass 15), battery saver 4, reduce motion / forced still = one frame;
 *  - auto-degrade from measured RENDER cost.
 *
 * [Mines-38.9.10 e / sky-e] build d froze the sky on a real realme Neo7 Turbo (Immortalis, 120 Hz): it judged window
 * FrameMetrics TOTAL_DURATION > 20 ms on 12/30 frames, which counts input / animation / layout / unknown delay of the whole
 * window (chat streaming, scrolling, recomposition), so a fast GPU tripped L1→L2→L3 within seconds and never came back.
 * Now:
 *  - cost = DRAW + SYNC + COMMAND_ISSUE + SWAP_BUFFERS (+ GPU on API 31+); first-draw frames and frames while the sky is not
 *    the visible live layer are not fed;
 *  - a frame is slow when cost > max(2 × vsync, [SLOW_FLOOR_MS]); a window is slow when ≥ [SLOW_N] of [WINDOW] frames are;
 *    [CONSEC] slow windows in a row → one step down, at most one step per [STEP_GAP_MS];
 *  - auto-degrade goes to L1 only; L2 (still frames) only when frames cost > [HANG_MS] the same way (real hangs), and never on
 *    a hardware GPU ([isHardwareGpu]: mali / immortalis / adreno / powervr / xclipse / apple);
 *  - recovery: [RECOVER_MS] of good windows at a degraded level → one level back up (the wait doubles after each repeated
 *    trip, capped at [RECOVER_MAX_MS], so a borderline device does not flap).
 */
object SkyPerfCore {
    const val FPS = 12
    const val RAIN_FPS = 15
    const val SLOW_FPS = 4
    /** lower bound of the slow-frame threshold (ms); the real one is max(2 × vsync, this) */
    const val SLOW_FLOOR_MS = 33f
    /** a frame costing more than this is a hang (the only way to auto-reach L2) */
    const val HANG_MS = 100f
    const val WINDOW = 30
    const val SLOW_N = 20
    /** consecutive slow windows needed for a step down */
    const val CONSEC = 2
    const val STEP_GAP_MS = 10_000L
    const val RECOVER_MS = 30_000L
    const val RECOVER_MAX_MS = 240_000L
    /** highest level the frame watch may reach by itself (L3 = stills only via the software-renderer check) */
    const val MAX_AUTO = 2
    const val MAX_LEVEL = 3
    /** level that skips the shader entirely (stills) */
    const val STILLS = 3

    private val SOFTWARE = listOf("swiftshader", "llvmpipe", "softpipe", "software rasterizer", "software renderer", "lavapipe", "pixelflinger")
    fun isSoftwareRenderer(renderer: String?): Boolean {
        val r = renderer?.lowercase() ?: return false
        return SOFTWARE.any { it in r }
    }
    private val HARDWARE = listOf("mali", "immortalis", "adreno", "powervr", "xclipse", "apple")
    /** a known phone GPU: never auto-degraded past L1 */
    fun isHardwareGpu(renderer: String?): Boolean {
        val r = renderer?.lowercase() ?: return false
        return !isSoftwareRenderer(r) && HARDWARE.any { it in r }
    }
    /** highest auto level for this renderer */
    fun maxAuto(renderer: String?): Int = if (isHardwareGpu(renderer)) 1 else MAX_AUTO

    /** slow-frame threshold for a display's vsync period (ms) */
    fun slowMs(vsyncMs: Float): Float = maxOf(2f * (if (vsyncMs > 0f) vsyncMs else 16.67f), SLOW_FLOOR_MS)

    /** render-only cost of one frame (ms) from FrameMetrics parts (ns); gpuNs < 0 = not available */
    fun renderCostMs(drawNs: Long, syncNs: Long, issueNs: Long, swapNs: Long, gpuNs: Long = -1L): Float {
        var s = maxOf(0L, drawNs) + maxOf(0L, syncNs) + maxOf(0L, issueNs) + maxOf(0L, swapNs)
        if (gpuNs > 0L) s += gpuNs
        return s / 1_000_000f
    }

    /** live fps for a scene at a degrade level (0 = one still frame) */
    fun fps(rain: Boolean, level: Int, powerSave: Boolean, animOff: Boolean, forcedStatic: Boolean): Int = when {
        animOff || forcedStatic || level >= 2 -> 0
        powerSave -> SLOW_FPS
        level == 1 -> (if (rain) RAIN_FPS else FPS) / 2
        else -> if (rain) RAIN_FPS else FPS
    }

    /** pass-1 sky buffer divisor (4 = quarter resolution) */
    fun skyDiv(level: Int): Float = if (level >= 1) 6f else 4f
    /** pass-2 (stars / snow / rain glass) buffer divisor — same at L1 so stars keep their size and twinkle */
    fun pass2Div(level: Int): Float = if (level >= 2) 3f else 2f

    /**
     * Frame-cost governor. Owns the auto level (0..[MAX_AUTO]). Not thread-safe by itself; callers feed it from one thread.
     * Non-overlapping windows of [window] frames; [feed] returns the level after this frame.
     */
    class Degrader(
        private val window: Int = WINDOW,
        private val slowN: Int = SLOW_N,
        private val consec: Int = CONSEC,
        private val stepGapMs: Long = STEP_GAP_MS,
        private val recoverMs: Long = RECOVER_MS,
        private val hangMs: Float = HANG_MS,
    ) {
        /** auto ceiling: [MAX_AUTO], or 1 on a hardware GPU */
        var maxAuto: Int = MAX_AUTO
            set(v) { field = v.coerceIn(0, MAX_AUTO); if (level > field) level = field }
        var level = 0; private set
        private var n = 0; private var slow = 0; private var hang = 0
        private var slowRun = 0; private var hangRun = 0
        private var lastStep = Long.MIN_VALUE / 2
        private var goodSince = -1L
        private var trips = 0
        /** why the last step happened (for the log) */
        var lastWhy = ""; private set

        fun recoverWaitMs(): Long = minOf(RECOVER_MAX_MS, recoverMs shl (trips - 1).coerceIn(0, 4))

        fun feed(costMs: Float, vsyncMs: Float, nowMs: Long): Int {
            n++
            if (costMs > slowMs(vsyncMs)) slow++
            if (costMs > hangMs) hang++
            if (n < window) return level
            val slowWin = slow >= slowN; val hangWin = hang >= slowN
            val s = slow; n = 0; slow = 0; hang = 0
            slowRun = if (slowWin) slowRun + 1 else 0
            hangRun = if (hangWin) hangRun + 1 else 0
            if (slowWin) goodSince = -1L else if (goodSince < 0L) goodSince = nowMs
            val gapOk = nowMs - lastStep >= stepGapMs
            if (gapOk && level == 0 && slowRun >= consec && maxAuto >= 1) {
                step(1, nowMs, "$s/$window frames > ${slowMs(vsyncMs).toInt()} ms render, $consec windows"); trips++
            } else if (gapOk && level == 1 && hangRun >= consec && maxAuto >= 2) {
                step(2, nowMs, "hangs: $s/$window frames, ≥ $slowN > ${hangMs.toInt()} ms, $consec windows")
            } else if (gapOk && level > 0 && goodSince >= 0L && nowMs - goodSince >= recoverWaitMs()) {
                step(level - 1, nowMs, "recovered: ${(nowMs - goodSince) / 1000} s of good frames")
                goodSince = nowMs
            }
            return level
        }

        private fun step(to: Int, nowMs: Long, why: String) {
            level = to; lastStep = nowMs; slowRun = 0; hangRun = 0; lastWhy = why
            if (to > 0 && goodSince >= 0L) goodSince = -1L
        }

        /** restart the current window (page re-registered); keeps the level and step clock */
        fun resetWindow() { n = 0; slow = 0; hang = 0 }
    }

    fun label(level: Int, software: Boolean): String = when {
        software -> "stills: software GPU"
        level >= STILLS -> "stills: too slow"
        level == 2 -> "still frames: slow"
        level == 1 -> "reduced: half fps"
        else -> "full"
    }
}

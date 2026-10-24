package com.openminis.app.plus

/**
 * [Mines-38.9.11.2] user 2026-10-21: the sunny sky's flashing stopped 「Q仔」 (the phone's on-screen recognition assistant)
 * from reading the screen. The sky holds a still frame while the screen is being read or captured, and for 1 s after any
 * touch / key input or scrolling. Pure, unit-tested (SkyFreezeCoreTest).
 * [38.9.11.3] user 2026-10-21 (overrides the above): the sky / fog must NOT stop at all. Q仔 is an always-on
 * accessibility service, so the 38.9.11.2 rule froze the sky permanently on the user's phone. [reason] is kept only as a
 * diagnostic (what 38.9.11.2 would have done); [holds] is false unless the smoke replays the old rule.
 */
object SkyFreezeCore {
    const val INPUT_HOLD_MS = 1000L

    data class Signals(
        val touchExploration: Boolean = false,
        /** enabled accessibility services other than Mines' own (screen readers, recognition assistants …) */
        val otherA11yServices: Int = 0,
        val screenRecording: Boolean = false,
        val screenshotAtMs: Long = 0L,
        val multiWindow: Boolean = false,
        val windowFocused: Boolean = true,
        val lastInputMs: Long = 0L,
    )

    /** why the sky is frozen now ("" = it may move) */
    fun reason(s: Signals, nowMs: Long): String = when {
        s.touchExploration -> "读屏"
        s.otherA11yServices > 0 -> "无障碍服务"
        s.screenRecording -> "录屏"
        s.screenshotAtMs > 0 && nowMs - s.screenshotAtMs in 0..3000 -> "截屏"
        s.multiWindow -> "分屏"
        !s.windowFocused -> "悬浮窗在上面"
        s.lastInputMs > 0 && nowMs - s.lastInputMs in 0 until INPUT_HOLD_MS -> "操作中"
        else -> ""
    }

    /** [38.9.11.3] does the sky stop? never — only the smoke's legacy replay of 38.9.11.2 */
    fun holds(reason: String, legacyReplay: Boolean): Boolean = legacyReplay && reason.isNotEmpty()

    /** packages of enabled a11y services → how many are not Mines itself */
    fun otherServices(enabledPackages: List<String>, ownPackage: String): Int = enabledPackages.count { it.isNotBlank() && it != ownPackage }

    /**
     * Uniform quadratic B-spline weights for three consecutive key frames (k-1, k, k+1) at phase u ∈ [0,1): the blend is
     * C1-continuous, so brightness never kinks at the key rate (a linear cross-fade's slope jumps every key → a visible
     * pulse at 1/keyMs). Weights sum to 1.
     */
    fun bspline(u: Float): FloatArray {
        val x = u.coerceIn(0f, 1f)
        return floatArrayOf((1 - x) * (1 - x) / 2f, (-2 * x * x + 2 * x + 1) / 2f, x * x / 2f)
    }

    /** layer alphas to composite k-1 (opaque), then k, then k+1 so the result equals the B-spline weights */
    fun layerAlphas(u: Float): FloatArray {
        val w = bspline(u)
        val a1 = if (w[0] + w[1] <= 1e-6f) 1f else w[1] / (w[0] + w[1])
        return floatArrayOf(1f, a1, w[2])
    }
}

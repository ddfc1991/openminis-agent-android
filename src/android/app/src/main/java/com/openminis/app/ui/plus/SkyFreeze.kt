package com.openminis.app.ui.plus

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import com.openminis.app.plus.SkyFreezeCore

/**
 * [Mines-38.9.11.2] Android side of [SkyFreezeCore]: listeners update [signals] (still shown in 自检 / smoke).
 * [38.9.11.3] user 2026-10-21: the sky never freezes any more — [frozen] is false unless the smoke replays the 38.9.11.2
 * rule for its before-recording ([legacyFreeze]).
 */
object SkyFreeze {
    @Volatile var signals = SkyFreezeCore.Signals(); private set
    @Volatile var lastReason: String = ""; private set
    /** smoke only: ignore the a11y / capture signals (the emulator's own a11y-driving service would freeze every capture) */
    @Volatile var ignoreSignals: Boolean = false

    /** smoke before-recording only: 38.9.11.2 behaviour (freeze while a11y / capture / input …) */
    @Volatile var legacyFreeze: Boolean = false
    /** smoke: pretend this many other accessibility services are enabled (Q仔 on the user's phone) */
    @Volatile var fakeOtherServices: Int = -1

    fun frozen(now: Long = System.currentTimeMillis()): Boolean {
        var s = if (ignoreSignals) SkyFreezeCore.Signals(lastInputMs = signals.lastInputMs) else signals
        if (fakeOtherServices >= 0) s = s.copy(otherA11yServices = fakeOtherServices)
        val r = SkyFreezeCore.reason(s, now); lastReason = r
        return SkyFreezeCore.holds(r, legacyFreeze)
    }

    fun input() { signals = signals.copy(lastInputMs = System.currentTimeMillis()) }
    fun focus(f: Boolean) { signals = signals.copy(windowFocused = f) }
    fun multiWindow(m: Boolean) { signals = signals.copy(multiWindow = m) }

    private fun refreshA11y(am: AccessibilityManager, own: String) {
        val pk = runCatching { am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.resolveInfo?.serviceInfo?.packageName.orEmpty() } }.getOrDefault(emptyList())
        signals = signals.copy(touchExploration = am.isEnabled && am.isTouchExplorationEnabled, otherA11yServices = SkyFreezeCore.otherServices(pk, own))
    }

    @Volatile private var listening = false
    private var screenshotCb: Any? = null
    private var recordingCb: Any? = null

    fun install(a: Activity) {
        val am = a.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
        val own = a.packageName
        refreshA11y(am, own)
        if (!listening) {
            listening = true
            runCatching { am.addAccessibilityStateChangeListener { refreshA11y(am, own) } }
            runCatching { am.addTouchExplorationStateChangeListener { refreshA11y(am, own) } }
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) runCatching {
            val cb = Activity.ScreenCaptureCallback { signals = signals.copy(screenshotAtMs = System.currentTimeMillis()) }
            a.registerScreenCaptureCallback(a.mainExecutor, cb); screenshotCb = cb
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) runCatching {
            val cb = java.util.function.Consumer<Int> { st -> signals = signals.copy(screenRecording = st == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
            val st = a.windowManager.addScreenRecordingCallback(a.mainExecutor, cb)
            signals = signals.copy(screenRecording = st == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE); recordingCb = cb
        }
        multiWindow(a.isInMultiWindowMode)
    }

    fun uninstall(a: Activity) {
        if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { (screenshotCb as? Activity.ScreenCaptureCallback)?.let { a.unregisterScreenCaptureCallback(it) } }
        if (android.os.Build.VERSION.SDK_INT >= 35) runCatching { @Suppress("UNCHECKED_CAST") (recordingCb as? java.util.function.Consumer<Int>)?.let { a.windowManager.removeScreenRecordingCallback(it) } }
        screenshotCb = null; recordingCb = null
    }
}

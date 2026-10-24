package com.openminis.app.sandbox

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * [T-android-phantom-budget] Sizes [GlobalShellThrottle]'s process budget to
 * what the device actually enforces.
 *
 * The throttle exists because Android 12+ SIGKILLs an app's "phantom"
 * (child) processes beyond 32, which is what tears down PRoot shells
 * mid-command. Its budget of 16 is right for a stock phone, but it is also
 * why a fast phone feels slow: commands queue behind each other even when the
 * CPU is idle. When the killer is not in force (Android 11 and below, or the
 * user turned it off with Developer options → "Disable child process
 * restrictions", or Shizuku/adb ran
 * `settings put global settings_enable_monitor_phantom_procs false`), the
 * budget is raised so parallel work actually runs in parallel.
 */
object PhantomProcessGuard {
    private const val TAG = "PhantomProcessGuard"
    const val RELAXED_BUDGET = 64
    private const val FLAG = "settings_enable_monitor_phantom_procs"

    @Volatile
    var budget: Int = GlobalShellThrottle.DEFAULT_PROCESS_BUDGET
        private set

    /** True when the phantom-process killer cannot fire on this device. */
    fun killerDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val v = runCatching { Settings.Global.getString(context.contentResolver, FLAG) }.getOrNull()
        if (v.equals("false", ignoreCase = true)) return true
        // The other switch: `device_config put activity_manager
        // max_phantom_processes <big>`. DeviceConfig is a system API, so read
        // it reflectively; on builds where the app may not read it this
        // returns null and we stay at the safe budget.
        val max = maxPhantomProcesses()
        return max != null && max >= RELAXED_BUDGET * 2
    }

    private fun maxPhantomProcesses(): Int? = runCatching {
        val cls = Class.forName("android.provider.DeviceConfig")
        val m = cls.getMethod("getInt", String::class.java, String::class.java, Int::class.javaPrimitiveType)
        (m.invoke(null, "activity_manager", "max_phantom_processes", -1) as Int).takeIf { it > 0 }
    }.getOrNull()

    fun refresh(context: Context) {
        val relaxed = killerDisabled(context)
        budget = if (relaxed) RELAXED_BUDGET else GlobalShellThrottle.DEFAULT_PROCESS_BUDGET
        Log.i(TAG, "phantom killer ${if (relaxed) "off" else "on"} -> process budget $budget")
    }

    /**
     * Shell commands that switch the killer off; run them through Shizuku
     * (`android-shizuku-cli`) or `adb shell`. Kept here so the agent and the
     * UI quote the same thing.
     */
    val DISABLE_COMMANDS = listOf(
        "settings put global $FLAG false",
        "device_config set_sync_disabled_for_tests persistent",
        "device_config put activity_manager max_phantom_processes 2147483647",
    )
}

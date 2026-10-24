package com.openminis.app.plus

import android.content.Context

/**
 * [Mines-38.9.4] issue #2 Bug 4: the self-check said 「无障碍 已开启」 while android-a11y-cli answered PERM_DENIED /
 * SERVICE_NOT_RUNNING. 「开着」 now means: the switch is on in system settings AND the service is bound and answering.
 * Android 13+ blocks the accessibility switch of a sideloaded APK behind 「受限设置」; when that is why it is not running,
 * the check says how to allow it (应用信息 › ⋮ › 允许受限制的设置).
 */
object A11yCheck {
    data class Result(val ok: Boolean, val enabled: Boolean, val running: Boolean, val restricted: Boolean, val detail: String)

    const val GUIDE = "设置 → 应用 → Mines → 右上角 ⋮ → 允许受限制的设置，再到 设置 → 无障碍 → Mines 打开"

    /** Pure wording (unit-tested). */
    fun verdict(enabled: Boolean, running: Boolean, restricted: Boolean): Result = when {
        enabled && running -> Result(true, true, true, restricted, "已开启，服务在运行")
        enabled && restricted -> Result(false, true, false, true, "开关是开的，但服务没在运行：系统把下载安装的 App 的无障碍设成了「受限设置」。解决：$GUIDE（打开后关掉再开一次）")
        enabled -> Result(false, true, false, false, "开关是开的，但服务没在运行：到 设置 → 无障碍 → Mines 关掉再打开一次")
        running -> Result(true, false, true, restricted, "服务在运行")
        restricted -> Result(false, false, false, true, "未开启，而且是「受限设置」：$GUIDE")
        else -> Result(false, false, false, false, "未开启（设置 → 无障碍 → Mines）")
    }

    fun enabledInSettings(c: Context): Boolean = runCatching {
        val list = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val on = android.provider.Settings.Secure.getInt(c.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        on && list.split(':').any { it.startsWith(c.packageName + "/") }
    }.getOrDefault(false)

    fun running(): Boolean = runCatching {
        val s = com.openminis.app.accessibility.MinisAccessibilityService.getInstance() ?: return false
        s.serviceInfo != null   // null once the system unbound it (a stale instance is not a running service)
    }.getOrDefault(false)

    /** Android 13+: is this sideloaded APK still under 「受限设置」? (app-op ACCESS_RESTRICTED_SETTINGS not allowed) */
    fun restricted(c: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val ops = c.getSystemService(android.app.AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), c.packageName)
            mode != android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun check(c: Context): Result = verdict(enabledInSettings(c), running(), restricted(c))
}

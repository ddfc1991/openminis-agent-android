package com.openminis.app.plus

import android.content.Context
import com.openminis.app.offload.ShizukuManager
import rikka.shizuku.Shizuku

/**
 * [Mines-shizuku-29] "Shizuku not authorized → pm/am/monkey all fail".
 *
 * Before: every privileged command answered PERMISSION_DENIED "grant it in
 * Settings → Permissions → Shizuku" and stopped — a multi-step trip for a
 * partially sighted user. Now the command itself asks: Shizuku's own
 * 「允许 Mines 使用 Shizuku？」 dialog is shown (started by the Shizuku server,
 * so it appears even while Mines is in the background) and the command waits
 * up to [waitMs] for the tap. When the user once chose 「拒绝且不再询问」
 * Shizuku won't show the dialog again; then its manager app is opened on the
 * 授权应用 list instead and the error says so.
 */
object ShizukuAsk {
    /** True when the allow dialog was shown. */
    fun request(context: Context): Boolean {
        ShizukuManager.refresh()
        if (ShizukuManager.snapshot.value.state != ShizukuManager.State.NEED_PERMISSION) return false
        val permanentlyDenied = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        if (permanentlyDenied) {
            runCatching { ShizukuManager.openShizukuApp(context.applicationContext) }
            return false
        }
        return ShizukuManager.requestPermission()
    }

    fun permanentlyDenied(): Boolean = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    /** Ask and block until granted / denied / [waitMs]. Not on the main thread. */
    fun requestAndWait(context: Context, waitMs: Long = 30_000): Boolean {
        if (ShizukuManager.isReady()) return true
        if (!request(context)) return false
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            Thread.sleep(400)
            ShizukuManager.refresh()
            if (ShizukuManager.isReady()) return true
            if (permanentlyDenied()) return false
        }
        return false
    }

    /** One line for errors and the settings screen. */
    fun statusLine(): String {
        ShizukuManager.refresh()
        return when (ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.READY -> "Shizuku：已授权，可用"
            ShizukuManager.State.NEED_PERMISSION -> if (permanentlyDenied())
                "Shizuku：在运行，但 Mines 被设为「拒绝」——打开 Shizuku → 授权应用 → 打开 Mines"
            else "Shizuku：在运行，还没授权给 Mines（点「授权」，在弹框里点允许）"
            ShizukuManager.State.NOT_RUNNING -> "Shizuku：已安装但没启动（打开 Shizuku，用无线调试启动）"
            ShizukuManager.State.NOT_INSTALLED -> "Shizuku：没安装"
        }
    }
}

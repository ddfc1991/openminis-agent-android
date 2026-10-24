package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * [Mines-perm-30] One shape for every "not allowed / not enabled" answer of the
 * phone CLIs (build-28 smoke report: each tool said it differently):
 *
 *   {"ok":false,"error":{"code":"PERM_DENIED","reason":"<old code>",
 *    "message":"…","action":"<Android settings action>",
 *    "open":"android-a11y-cli perm open <action>"}}
 *
 * `action` names the settings page that fixes it; `perm open <action>` opens it.
 */
internal object PermDenied {
    const val CODE = "PERM_DENIED"
    const val ACCESSIBILITY = "android.settings.ACCESSIBILITY_SETTINGS"
    const val APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val SHIZUKU = "moe.shizuku.privileged.api"
    /** Mines' own Settings → Permissions (agent gate). */
    const val MINES_PERMISSIONS = "mines.settings.PERMISSIONS"

    fun json(reason: String, message: String, action: String): JSONObject =
        JSONObject().put("ok", false).put("error", JSONObject()
            .put("code", CODE).put("reason", reason).put("message", message)
            .put("action", action).put("open", "android-a11y-cli perm open $action"))

    fun result(args: OffloadArgs, reason: String, message: String, action: String, exit: Int = 77): NativeOffloadResult =
        NativeOffloadResult(exit, OffloadOutput.formatBody(json(reason, message, action).toString(), args) + "\n")

    /** `perm open <action>`: start the settings page named by [action]. */
    fun open(context: Context, action: String): Pair<Boolean, String> = runCatching {
        val i = when (action) {
            APP_DETAILS -> Intent(APP_DETAILS, Uri.parse("package:" + context.packageName))
            SHIZUKU -> context.packageManager.getLaunchIntentForPackage(SHIZUKU)
                ?: return false to "没装 Shizuku"
            MINES_PERMISSIONS -> context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return false to "打不开 Mines"
            else -> Intent(action)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        true to "已打开：$action" + (if (action == MINES_PERMISSIONS) "（在 Mines 里进 设置 → 权限）" else "")
    }.getOrElse { false to "打不开 $action：${it.message}" }
}

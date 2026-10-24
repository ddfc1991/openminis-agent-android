package com.openminis.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * [Mines-38.9.11 d] The places the file manager can open, with the names the user sees.
 *  - 共享文件 = /var/minis/shared (filesDir/minis-global/shared), what every chat and the agent share
 *  - 对话工作区 = per-chat /var/minis/workspace (filesDir/minis-sessions/<id>/workspace)
 *  - 沙箱系统 = the Alpine rootfs (filesDir/alpine-rootfs)
 *  - 手机存储 = /storage/emulated/0 (needs 「所有文件访问」, MANAGE_EXTERNAL_STORAGE is already declared;
 *    without it the screen offers the grant and a system-picker import / export instead)
 */
object FileRoots {
    class Root(val id: String, val label: String, val sub: String, val dir: File, val needsGrant: Boolean = false)

    fun shared(c: Context) = File(c.filesDir, "minis-global/shared").apply { mkdirs() }
    fun sessions(c: Context) = File(c.filesDir, "minis-sessions")
    fun rootfs(c: Context) = File(c.filesDir, "alpine-rootfs")
    fun phone(): File = Environment.getExternalStorageDirectory()
    fun work(c: Context) = File(shared(c), "文件管理").apply { mkdirs() }

    fun hasAllFiles(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun allFilesIntent(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))

    fun roots(c: Context): List<Root> = buildList {
        add(Root("shared", "共享文件", "/var/minis/shared · 所有对话都能用", shared(c)))
        add(Root("sessions", "对话工作区", "每个对话自己的 /var/minis/workspace", sessions(c)))
        add(Root("rootfs", "沙箱系统", "Alpine 系统文件（改错可能让沙箱打不开）", rootfs(c)))
        add(Root("phone", "手机存储", if (hasAllFiles()) "内部存储 /storage/emulated/0" else "需要先允许「所有文件访问」", phone(), needsGrant = !hasAllFiles()))
    }

    /** Linux path the agent sees for a host file, when there is one (shown under the title so the user can tell the AI). */
    fun linuxPath(c: Context, f: File): String? {
        val p = f.absolutePath
        val sh = shared(c).absolutePath
        if (p == sh || p.startsWith("$sh/")) return "/var/minis/shared" + p.removePrefix(sh)
        val ss = sessions(c).absolutePath + "/"
        if (p.startsWith(ss)) {
            val rest = p.removePrefix(ss)               // <id>/workspace/…
            val parts = rest.split('/', limit = 3)
            if (parts.size >= 2) return "/var/minis/" + parts[1] + (if (parts.size == 3) "/" + parts[2] else "")
        }
        val rf = rootfs(c).absolutePath
        if (p == rf) return "/"
        if (p.startsWith("$rf/")) return p.removePrefix(rf)
        return null
    }

    /** A friendly label for a path inside one of the roots (breadcrumb start). */
    fun rootOf(c: Context, f: File): Root? = roots(c).filter { r ->
        val rp = r.dir.absolutePath; f.absolutePath == rp || f.absolutePath.startsWith("$rp/")
    }.maxByOrNull { it.dir.absolutePath.length }

    /** Is [f] somewhere the app may write without the all-files grant? */
    fun isAppPrivate(c: Context, f: File): Boolean = f.absolutePath.startsWith(c.filesDir.parentFile!!.absolutePath)

    const val MT_PACKAGE = "bin.mt.plus"
    val MT_PACKAGES = listOf("bin.mt.plus", "bin.mt.plus.canary")

    /** Installed MT Manager package, if any. */
    fun mtPackage(c: Context): String? = MT_PACKAGES.firstOrNull { p ->
        runCatching { c.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
    }
}

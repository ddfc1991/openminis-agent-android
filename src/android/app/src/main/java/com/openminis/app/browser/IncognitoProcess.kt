package com.openminis.app.browser

import android.app.Application
import android.content.Context
import android.webkit.WebView
import java.io.File

/**
 * [Mines-38.9.11.2] the ":incog" process (PROCESS path of [IncognitoCore]). [onAppCreate] runs first thing in
 * MinisApp.onCreate: in the ":incog" process it gives WebView its own data directory suffix BEFORE any WebView exists, wipes
 * what a killed session may have left there, and tells MinisApp to skip all of its normal start-up (no database, no
 * sandbox, no account stores: nothing in this process can read or write Mines' own data).
 */
object IncognitoProcess {
    /** true in the ":incog" process once the WebView data dir suffix is set */
    @Volatile var inProcess: Boolean = false; private set
    @Volatile var webViewStarted: Boolean = false

    fun processName(): String? =
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()

    /** @return true when this is the ":incog" process (MinisApp then returns from onCreate) */
    fun onAppCreate(app: Application): Boolean {
        val pn = processName()
        if (!IncognitoCore.isIncognitoProcess(pn, app.packageName)) return false
        inProcess = true
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { WebView.setDataDirectorySuffix(IncognitoCore.DATA_SUFFIX) }
        wipeDirs(app)   // before the first WebView: nothing of a killed session survives
        return true
    }

    /** deletes the ":incog" WebView data / cache dirs; returns how many existed */
    fun wipeDirs(c: Context): Int {
        val data = File(c.applicationInfo.dataDir)
        var n = 0
        for (rel in IncognitoCore.processDataDirs()) {
            val f = File(data, rel)
            if (f.exists()) { n++; runCatching { f.deleteRecursively() } }
        }
        return n
    }

    /** which ":incog" dirs exist now (smoke / 自检) */
    fun dirsPresent(c: Context): List<String> {
        val data = File(c.applicationInfo.dataDir)
        return IncognitoCore.processDataDirs().filter { File(data, it).exists() }
    }

    /** is the ":incog" process alive (checked from the main process) */
    fun running(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses.orEmpty().any { IncognitoCore.isIncognitoProcess(it.processName, c.packageName) }
    }.getOrDefault(false)
}

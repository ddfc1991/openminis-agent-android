package com.openminis.app.plus

import android.app.Application
import android.util.Log

/**
 * [Mines-38.9.12.1] Which process of the package this is (user's crash loop 2026-10-23 07:20: the ACRA ":acra" reporter
 * process ran the full MinisApp.onCreate, started WebView via ProxyController and held the WebView data-dir lock, so the
 * main process died at every launch). A secondary process (":acra", anything but the main one and ":incog", which has its
 * own handling) gets its own WebView data-dir suffix before anything could touch WebView, and MinisApp skips all init.
 * The main process never waits for another one; it only notes whether one is alive (smoke / logs).
 */
object ProcessGuard {
    @Volatile var processName: String = ""; private set
    @Volatile private var mainKnown: Boolean? = null
    /** true in the main process (computed on first use when [secondary] has not run, e.g. in a unit-less path) */
    val isMain: Boolean get() = mainKnown ?: runCatching {
        val pn = com.openminis.app.browser.IncognitoProcess.processName()
        val pkg = com.openminis.app.BuildConfig.APPLICATION_ID
        ProcessGuardCore.isMain(pn, pkg)
    }.getOrDefault(true).also { mainKnown = it }
    /** other processes of the package alive when the main process started (names), for the smoke readout */
    @Volatile var othersAtStart: List<String> = emptyList(); private set

    /** MinisApp.onCreate, first thing: true = secondary process (init skipped). Never throws. */
    fun secondary(app: Application): Boolean {
        val pn = runCatching { com.openminis.app.browser.IncognitoProcess.processName() }.getOrNull()
        processName = pn ?: ""
        val main = ProcessGuardCore.isMain(pn, app.packageName)
        mainKnown = main
        if (!main) {
            val suffix = ProcessGuardCore.dataSuffix(pn, app.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { android.webkit.WebView.setDataDirectorySuffix(suffix) }
            Log.i("MinesProcess", "secondary process $pn: own WebView dir '$suffix', app init skipped")
            return true
        }
        runCatching {
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val me = android.os.Process.myPid()
            othersAtStart = am?.runningAppProcesses.orEmpty().filter { it.pid != me && it.processName.startsWith(app.packageName) }.map { "${it.processName}(${it.pid})" }
            if (othersAtStart.isNotEmpty()) Log.i("MinesProcess", "main start with other processes alive (not waited for): $othersAtStart")
        }
        // the WebView proxy override is applied once the main looper is idle (after launch), never inside onCreate
        runCatching { android.os.Looper.myQueue().addIdleHandler { runCatching { WebProxy.markReady() }; false } }
        return false
    }
}

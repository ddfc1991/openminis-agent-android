package com.openminis.app.plus

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * [Mines-38.9.12.1] smoke only (started by the adb-only self-test, never by the app): a service in the ":acra" process —
 * the process ACRA's reporter uses — that starts WebView there and stays up for a while, i.e. the user's 2026-10-23
 * state of a stale ":acra" process holding WebView while the main process launches. With [ProcessGuard] the ":acra"
 * WebView uses its own data dir and MinisApp init is skipped there, so the main launch must not crash.
 */
class AcraProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            val wv = android.webkit.WebView(applicationContext)
            wv.loadDataWithBaseURL("https://acra-probe.test/", "<p>acra probe</p>", "text/html", "utf-8", null)
            held = wv
            getSharedPreferences("mines_acra_probe", MODE_PRIVATE).edit()
                .putInt("pid", android.os.Process.myPid()).putString("proc", ProcessGuard.processName).putLong("at", System.currentTimeMillis()).commit()
        }.onFailure { e ->
            getSharedPreferences("mines_acra_probe", MODE_PRIVATE).edit().putString("err", "${e.javaClass.simpleName}: ${e.message}".take(200)).commit()
        }
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { held?.destroy() }; held = null; stopSelf() }, 180_000L)
        return START_NOT_STICKY
    }
    private var held: android.webkit.WebView? = null
}

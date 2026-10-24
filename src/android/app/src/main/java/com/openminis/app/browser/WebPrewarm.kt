package com.openminis.app.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * [Mines-38.8 / 38.7.1] The first WebView in a process starts the Chromium engine + renderer (smoke 38824392362:
 * 「Skipped 82 frames」, Davey 1.6 s) and the first 一键关联 sign-in page showed one blank frame meanwhile.
 * Warm it once when the app goes idle after launch (and again when 一键关联 opens): one off-screen WebView loads
 * about:blank, then is destroyed — the engine and the renderer stay loaded for the real pages.
 */
object WebPrewarm {
    @Volatile private var done = false
    @Volatile private var holder: WebView? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }   // one handler: removeCallbacks only matches its own posts
    private val release = Runnable { runCatching { holder?.destroy() }; holder = null }

    fun warm(context: Context, keepMs: Long = 15_000L, again: Boolean = false) {
        // [Mines-38.8 smoke 38841427883] 一键关联 opened while the idle warm-up was about to end (15 s): the renderer was
        // killed (「isolated not needed」) and the first sign-in page cold-started it again. Asking again keeps it longer.
        if (again && holder != null) { main.removeCallbacks(release); main.postDelayed(release, keepMs); return }
        if (done && !again) return
        runCatching { com.openminis.app.plus.WebProxy.markReady() }   // [Mines-38.9.12.1] proxy override before the first WebView
        main.post {
            if (holder != null) { main.removeCallbacks(release); main.postDelayed(release, keepMs); return@post }
            if (done && !again) return@post
            done = true
            runCatching {
                val w = WebView(context.applicationContext)
                w.settings.javaScriptEnabled = false
                w.loadDataWithBaseURL("about:blank", "<html></html>", "text/html", "utf-8", null)
                holder = w
                main.postDelayed(release, keepMs)
            }.onFailure { done = false }
        }
    }

    /** After the first frame: wait until the main thread is idle, then warm (never on the launch path). */
    fun warmWhenIdle(context: Context) {
        Looper.myQueue().addIdleHandler { main.postDelayed({ warm(context) }, 1500); false }
    }
}

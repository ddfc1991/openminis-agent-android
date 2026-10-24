package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [Mines-sources-33] One off-screen WebView that reads a page with the user's own
 * signed-in session (the app-wide cookie jar), so every login the user already did
 * becomes a data source with no extra setup: Gmail's feed, 163 / QQ webmail,
 * github.com/notifications, a provider console's balance page.
 *
 * No window is ever added: the WebView is created, loads, is read through JS and is
 * destroyed. Everything happens on the main thread (WebView's requirement) and the
 * caller suspends. A page that never finishes is abandoned at [timeoutMs].
 */
object HiddenWeb {
    const val DEFAULT_TIMEOUT = 25_000L

    /** Loads [url] and returns the value of [js] (default: the whole document), or null. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(
        context: Context,
        url: String,
        js: String = "document.documentElement.outerHTML",
        timeoutMs: Long = DEFAULT_TIMEOUT,
        settleMs: Long = 1200,
        until: (String) -> Boolean = { it.length > 80 },
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            val wv = WebView(context.applicationContext)
            LoginWeb.configure(wv)
            var finished = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) { finished = true }
            }
            try {
                wv.loadUrl(url)
                val start = System.currentTimeMillis()
                var out: String? = null
                while (System.currentTimeMillis() - start < timeoutMs - 500) {
                    delay(if (finished) settleMs else 700)
                    val v = eval(wv, js)
                    if (v != null && until(v)) { out = v; break }
                    if (finished && System.currentTimeMillis() - start > settleMs + 4000) { out = v; break }
                }
                LoginWeb.flush()
                out
            } finally {
                runCatching { wv.stopLoading(); wv.destroy() }
            }
        }
    }

    /** Visible text of [url] (what a person would read), for pages whose HTML is noise. */
    suspend fun text(context: Context, url: String, timeoutMs: Long = DEFAULT_TIMEOUT): String? =
        read(context, url, "(document.body&&document.body.innerText)||''", timeoutMs, until = { it.length > 40 })

    private suspend fun eval(wv: WebView, js: String): String? = suspendCancellableCoroutine { c ->
        runCatching {
            wv.evaluateJavascript("(function(){try{return String($js)}catch(e){return ''}})()") { r ->
                val v = runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
                if (c.isActive) c.resume(v)
            }
        }.onFailure { if (c.isActive) c.resume(null) }
    }
}

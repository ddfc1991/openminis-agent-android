package com.openminis.app.plus

/**
 * [Mines-38.9.6 i12] WebView traffic through the local split proxy (androidx.webkit ProxyController). The WebView itself
 * never sees the upstream credentials: 127.0.0.1:[SplitProxy.PORT] adds Proxy-Authorization upstream. Before this the
 * Gmail / Google sign-in pages in Mines ignored the proxy (WebView does not use the app's ProxySelector).
 */
object WebProxy {
    @Volatile var applied: Boolean = false; private set
    /** the port the app's WebViews should use now (split proxy while it works › a local proxy that passed), null = none */
    fun passingPort(): Int? = if (LocalProxy.usable(SplitProxy.PORT) && runCatching { SplitProxy.active }.getOrDefault(false)) SplitProxy.PORT else LocalProxy.best

    /**
     * [Mines-38.9.12.1] ProxyController.getInstance() starts Chromium and takes the WebView data-dir lock. It is no longer
     * touched from Application.onCreate: until [ready] (main looper idle after launch, or the first WebView warm-up) the
     * wanted state is only remembered, then applied once — any failure (a lock held by another process) is caught and
     * logged, never a crash at launch.
     */
    @Volatile private var ready = false
    @Volatile private var pending: Boolean? = null
    @Volatile var lastError: String = ""; private set

    /** main process only, never from Application.onCreate itself */
    fun markReady() {
        if (ready) return
        if (!ProcessGuard.isMain) return
        ready = true
        val p = pending ?: return
        pending = null
        apply(p)
    }

    fun apply(on: Boolean) {
        if (!ready || !ProcessGuard.isMain) { pending = on; return }
        try { applyNow(on) } catch (t: Throwable) { lastError = "${t.javaClass.simpleName}: ${t.message}".take(200); android.util.Log.w("WebProxy", "proxy override failed: $lastError") }
    }

    private fun applyNow(on: Boolean) {
        run {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return
            val pc = androidx.webkit.ProxyController.getInstance()
            val ex = java.util.concurrent.Executor { it.run() }
            // [38.9.11.1] the split proxy only while it really works, else a local proxy that passed, else no override
            // [38.9.11.3] 严格代理: never cleared to direct — a closed port blocks instead (StrictWebCore)
            val strict = SandboxNet.strict
            val rule = StrictWebCore.rule(strict, if (on || strict) passingPort() else null)
            if (rule == null) { if (applied) pc.clearProxyOverride(ex) { applied = false }; return }
            val cfg = androidx.webkit.ProxyConfig.Builder()
                .addProxyRule(rule)
                .addBypassRule("localhost").addBypassRule("127.0.0.1").addBypassRule("10.0.2.2")
                .build()
            pc.setProxyOverride(cfg, ex) { applied = true }
        }
    }

    /** [38.9.11.3] WebRTC IP leak guard at document start (strict / 无痕 WebViews) */
    fun guardWebRtc(wv: android.webkit.WebView) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, StrictWebCore.WEBRTC_JS, setOf("*"))
        }
    }
}

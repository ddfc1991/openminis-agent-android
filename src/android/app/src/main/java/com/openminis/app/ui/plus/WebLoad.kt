package com.openminis.app.ui.plus

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * [Mines-web-35] Load watchdog for every in-app WebView that is not the agent browser
 * (login, key-fetch 一键接入, 网站 ↗). User (2026-10-14, realme): the 通义千问 key page
 * (bailian.console.aliyun.com) stayed pure white with only 「关闭」 — those WebViews had no
 * error handler, no progress and no timeout, so a failed main-frame load (DNS / proxy / SSL /
 * a console that never paints) looked like an empty page forever.
 *
 * Now: thin progress bar, main-frame errors turned into a plain reason, a 25 s watchdog that
 * also checks the page for visible content (LoginWeb.BLANK_JS) after it "finishes", and a
 * panel with 「重试」 / 「用外部浏览器打开」. Zoom: BrowserZoom.onPage on every start/finish.
 */
class WebLoadState {
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var startedAt by mutableLongStateOf(0L)
    var finishedAt by mutableLongStateOf(0L)
    var url by mutableStateOf("")
    var blank by mutableStateOf(false)

    fun started(view: WebView, u: String?) {
        url = u.orEmpty(); startedAt = System.currentTimeMillis(); finishedAt = 0L; error = null; blank = false; challenge = false
        com.openminis.app.browser.BrowserZoom.onPage(view, u, finished = false)
    }

    fun finished(view: WebView, u: String?) {
        url = u.orEmpty(); finishedAt = System.currentTimeMillis()
        com.openminis.app.browser.BrowserZoom.onPage(view, u, finished = true)
        runCatching { view.evaluateJavascript(TOUCH_JS, null) }
    }

    fun mainFrameError(request: WebResourceRequest?, err: WebResourceError?) {
        if (request?.isForMainFrame != true) return
        error = plain(err?.errorCode ?: 0, err?.description?.toString().orEmpty())
    }

    fun httpError(request: WebResourceRequest?, code: Int) {
        if (request?.isForMainFrame != true || code < 500) return
        error = "网站暂时出错了（$code），稍后再试"
    }

    /** [Mines-38.9.11 b] a Cloudflare / WAF challenge (403 / 503 + cf-mitigated …) is the page asking for a tick, not an error. */
    var challenge by mutableStateOf(false)
    fun httpError(request: WebResourceRequest?, r: android.webkit.WebResourceResponse?) {
        if (request?.isForMainFrame != true) return
        val code = r?.statusCode ?: 0
        if (com.openminis.app.plus.WebHintsCore.isChallenge(code, r?.responseHeaders)) { challenge = true; error = null; blank = false; return }
        challenge = false
        httpError(request, code)
    }

    fun sslError(e: SslError?) { error = "网站的安全证书有问题（可能被代理或网络拦截），为了安全没有打开" + (e?.url?.let { "" } ?: "") }

    companion object {
        /** Lets pinch-zoom through pages that switch it off with CSS touch-action. */
        const val TOUCH_JS = """(function(){try{if(document.getElementById('__minesTouch'))return;var s=document.createElement('style');s.id='__minesTouch';
s.textContent='html,body{touch-action:auto!important;-ms-touch-action:auto!important}';(document.head||document.documentElement).appendChild(s);}catch(e){}})()"""

        fun plain(code: Int, desc: String): String {
            val d = desc.uppercase()
            return when {
                d.contains("PROXY") || d.contains("TUNNEL") -> "代理连不上（VPN 或 Mines 分流代理可能断了），关掉代理或换一条线路再试"
                d.contains("NAME_NOT_RESOLVED") || code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "找不到这个网站（网络或 DNS 不通）"
                d.contains("TIMED_OUT") || code == android.webkit.WebViewClient.ERROR_TIMEOUT -> "网站太久没响应（网络慢或被拦截）"
                d.contains("INTERNET_DISCONNECTED") -> "手机没有网络"
                d.contains("CONNECTION_REFUSED") || d.contains("CONNECTION_RESET") || d.contains("CONNECTION_CLOSED") -> "连接被断开了（网络或代理问题）"
                d.contains("SSL") || d.contains("CERT") -> "网站的安全证书有问题（可能被代理或网络拦截）"
                d.contains("TOO_MANY_REDIRECTS") -> "网站一直在跳转（登录状态可能失效），用外部浏览器打开试试"
                else -> "页面没打开" + if (desc.isNotBlank()) "（${desc.take(40)}）" else ""
            }
        }
    }
}

/** Progress bar + watchdog + error panel, drawn over the WebView. [onRetry] reloads, [onExternal] opens Chrome. */
@Composable
fun WebLoadOverlay(state: WebLoadState, webView: WebView?, onRetry: () -> Unit, onExternal: () -> Unit, modifier: Modifier = Modifier) {
    val m = mines()
    // watchdog: no finish after 25 s, or "finished" but nothing visible after 6 s more
    LaunchedEffect(state.startedAt, state.finishedAt) {
        val started = state.startedAt
        if (started == 0L || state.error != null) return@LaunchedEffect
        if (state.finishedAt == 0L) {
            delay(25_000)
            if (state.startedAt == started && state.finishedAt == 0L && state.error == null)
                state.error = "等了 25 秒页面还没打开（网络慢、被拦截，或代理/VPN 有问题）"
        } else {
            // [Mines-web-35.1] keep checking for 40 s (SPAs paint late, or never): show the panel while the page
            // shows nothing visible two checks in a row, hide it again as soon as something appears.
            delay(4_000)
            val wv = webView ?: return@LaunchedEffect
            var hits = 0
            repeat(10) {
                if (state.error != null) return@LaunchedEffect
                val r = kotlinx.coroutines.suspendCancellableCoroutine<String> { c ->
                    runCatching { wv.evaluateJavascript(com.openminis.app.plus.LoginWeb.BLANK_JS) { v -> if (c.isActive) c.resumeWith(Result.success(v ?: "")) } }
                        .onFailure { if (c.isActive) c.resumeWith(Result.success("")) }
                }
                if (r.contains("blank")) { hits++; if (hits >= 2) state.blank = true } else { hits = 0; state.blank = false }
                delay(4_000)
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (state.progress in 1..99 && state.error == null) LinearProgressIndicator(progress = { state.progress / 100f },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "正在加载 ${state.progress}%" })
        else if (state.progress == 0 && state.startedAt == 0L && state.error == null) LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "正在打开页面" })
        val reason = if (state.challenge) null else state.error ?: if (state.blank) "页面打开了但什么都没显示（这个网站可能不支持在 App 里打开，或要先登录）" else null
        if (reason != null) Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp)
            .background(m.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("页面没打开", fontSize = MT.PageTitle, fontWeight = FontWeight.SemiBold, color = m.ink)
            Text(reason, fontSize = MT.Body, lineHeight = MT.BodyLH, color = m.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("重试", Modifier.weight(1f), primary = true) { state.error = null; state.blank = false; onRetry() }
                PillButton("用外部浏览器打开", Modifier.weight(1.4f), primary = false) { onExternal() }
            }
        }
    }
}

/** Standard WebViewClient hooks for [WebLoadState]; call from a client's overrides. */
object WebLoadHooks {
    fun onStarted(s: WebLoadState, v: WebView, u: String?, @Suppress("UNUSED_PARAMETER") f: Bitmap?) = s.started(v, u)
}

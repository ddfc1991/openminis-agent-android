package com.openminis.app.ui.plus

import com.openminis.app.ui.brand.MinesIcon

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import com.openminis.app.ui.theme.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.browser.GoogleAuthRouter
import com.openminis.app.plus.ConnectionKind
import com.openminis.app.plus.MINES_MOBILE_UA
import com.openminis.app.plus.PlusConnect
import com.openminis.app.plus.LoginWeb
import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Full-screen in-app browser on a service's official login page.
 * Uses the app-wide WebView cookie store, so the AI's browser tool shares the
 * sign-in. Detects success from cookies / URL / a JS probe and closes itself;
 * a big "我已登录好了" button is always there as a fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    kind: ConnectionKind,
    url: String,
    title: String,
    detect: Boolean,
    onDone: (account: String, urls: Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(url) }
    val visited = remember { mutableSetOf(url) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val load = remember { WebLoadState() }
    var finished by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // [Mines-login-32] this provider's page did not render inside Mines → it is open in a Chrome Custom Tab
    // [Mines-38.7 v11] user 2026-10-15: every sign-in stays inside Mines. A Chrome switch remembered by an older build
    // (login_external, set by the blank-page fallback) is not followed any more: the page opens here again.
    var external by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(if (detect) "正在检查是否已经登录…" else null) }
    var extOpened by remember { mutableStateOf(false) }
    var extBack by remember { mutableStateOf(false) }
    var reloadKey by remember { androidx.compose.runtime.mutableIntStateOf(0) } // [Mines-38.7] new page after a renderer crash
    var googleInApp by remember { mutableStateOf(kind.id == "gmail" && PlusVault.extra(kind.id, "google_refused") != "1") }

    suspend fun eval(js: String): String {
        val wv = webView ?: return ""
        return suspendCancellableCoroutine { cont ->
            runCatching {
                wv.evaluateJavascript("(function(){try{return String($js)}catch(e){return ''}})()") { r ->
                    // [Mines-38.9.6 i13] a real JSON decode: trim('"') left "\u0001" escape text (Memoh 账号 「\u0001\u0001」)
                    val v = com.openminis.app.plus.LoginValueCore.decodeJs(r)
                    if (cont.isActive) cont.resume(v)
                }
            }.onFailure { if (cont.isActive) cont.resume("") }
        }
    }

    // [Mines-38.9.5 i] finish ONLY on a confirmed session: cookies flushed, then a real authenticated request
    // (WebSession / WebSessionCore). 38.9.4 marked 「已登录」 blindly — a Google sign-in done in Chrome left Mines with no
    // session and the page kept saying 需要登录. A network error (can't check) still lets the user go on.
    suspend fun finish(): Boolean {
        if (finished) return true
        finished = true
        val account = if (external) "" else kind.accountJs?.let { eval(it) }.orEmpty()
        LoginWeb.flush()
        if (detect) {
            val st = com.openminis.app.plus.WebSession.verify(context, kind)
            if (!com.openminis.app.plus.WebSessionCore.finishAllowed(st) && st != com.openminis.app.plus.WebSessionCore.St.ERROR &&
                st != com.openminis.app.plus.WebSessionCore.St.UNCHECKED) {
                finished = false
                status = if (external || extBack) com.openminis.app.plus.WebSessionCore.chromeHint(kind.name) else "还没登录上"
                return false
            }
        }
        LoginWeb.markSignedIn(kind.id, account)
        onDone(account, visited.toSet())
        return true
    }

    // [Mines-login-32] Already signed in (session cookie kept from last time)? Skip the page.
    LaunchedEffect(kind.id) {
        if (detect && LoginWeb.signedIn(kind)) { status = "已经登录过了，直接连上"; delay(400); finish() }
        else if (external) {
            status = "这个网站在 Mines 里显示不出来，已改用 Chrome 打开。登录好后回来点下面的按钮"
            GoogleAuthRouter.openInCustomTab(context, url)
        } else { delay(3500); if (status == "正在检查是否已经登录…") status = null }
    }
    // [Mines-login-32] Blank-page detection: after ~8 s with no visible text / controls
    // (passport.aliyun.com white, supabase.com black on build-31), hand this provider
    // to a Chrome Custom Tab and remember that for next time.
    LaunchedEffect(webView) {
        if (webView == null || external) return@LaunchedEffect
        // [Mines-38.7 v11d] smoke 38758822905: 抖音 sat on an empty dark page with no word for ~10 s. Say it is loading
        // as soon as the page is seen empty (4 s), not only after the 13 s check.
        delay(4000)
        var blanks = 0
        val loadingNote = "页面加载中，请稍等…"
        repeat(3) {
            if (finished || external) return@LaunchedEffect
            if (eval(LoginWeb.BLANK_JS) == "blank") { blanks++; if (status == null) status = loadingNote }
            else if (status == loadingNote) status = null
            delay(2500)
        }
        if (blanks < 2 && status == loadingNote) status = null
        if (blanks >= 2 && !finished) {
            // [Mines-38.7 v11] smoke 2026-10-15 21:59: X (heavy page, slow network) was still blank after ~13 s and the old
            // fallback threw the user out to Chrome — a sign-in there never reaches Mines. Stay here: load it once more, and
            // if it is still empty say what to do (network / proxy, or wait), with the page kept open.
            val waitNote = if (progress < 100) "页面还在加载（网站慢或需要代理），请稍等…" else "页面还没显示出来，正在重新加载…"
            status = waitNote
            if (progress >= 100) runCatching { webView?.reload() }
            // [Mines-38.8 v3] smoke 38845542672: X had painted but the strip still said 「还没显示出来」 for up to 15 s.
            // Look every 1.5 s and clear the note as soon as the page shows something.
            var shownAgain = false
            for (i in 0 until 10) {
                delay(1500)
                if (finished) return@LaunchedEffect
                if (eval(LoginWeb.BLANK_JS) != "blank") { shownAgain = true; break }
            }
            if (shownAgain) { if (status == waitNote) status = null }
            else if (!finished && eval(LoginWeb.BLANK_JS) == "blank")
                status = "页面还是没显示出来：网站慢或需要代理。可以再等一会儿，或检查网络 / 代理后关掉重新打开（登录一直在 Mines 里做）"
            else if (status == waitNote) status = null
        }
    }
    // [Mines-sources-33] Google refused the embedded sign-in → say what still works, stop retrying.
    if (kind.id == "gmail") LaunchedEffect(webView) {
        if (webView == null) return@LaunchedEffect
        while (!finished && googleInApp) {
            delay(2500)
            val refused = eval("((document.body&&document.body.innerText)||'').match(/disallowed_useragent|may not be secure|可能不安全|不安全的浏览器|Couldn.t sign you in|无法让您登录/i)?'1':''")
            if (refused == "1") {
                googleInApp = false
                PlusVault.setExtra(kind.id, "google_refused", "1")
                status = "Google 不让在 App 里登录。验证码仍会自动读：打开「通知使用权」后，Gmail App 的通知里的验证码会自动填；也可以在 Gmail 页填应用专用密码"
            }
        }
    }
    // [Mines-sources-33] Login pages that ask for an SMS / e-mail code: fill it from notifications
    // or the user's signed-in mailboxes, no typing.
    // [Mines-38.7] + the phone number typed in for you, SMS codes (receiver / inbox / Google consent), 「已自动填好验证码」 read aloud
    LaunchedEffect(webView) {
        if (webView == null) return@LaunchedEffect
        com.openminis.app.plus.LoginAssist.run(context, { webView }, auto = LinkAll.autoLogin, isDone = { finished }, onStatus = { status = it })
    }
    // [Mines-38.7] can this phone reach the site at all? (X behind a proxy that does not cover Mines → white page)
    LaunchedEffect(url) {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return@LaunchedEffect
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.net.Socket().use { sk -> sk.connect(java.net.InetSocketAddress(host, 443), 6000) }; true }.getOrDefault(false) ||
            runCatching { (java.net.URL("https://$host/").openConnection() as java.net.HttpURLConnection).run { connectTimeout = 6000; readTimeout = 6000; requestMethod = "HEAD"; responseCode; disconnect() }; true }.getOrDefault(false)
        }
        if (!ok) status = if (host.endsWith("x.com") || host.endsWith("twitter.com")) "连不上 X，请在代理 App 里把 Mines 加入代理/开全局，然后点「重试」"
            else "连不上 $host，请检查网络或代理，然后点「重试」"
    }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) LoginWeb.flush()
            // [Mines-38.7] back from the external browser without any callback: its login cannot reach Mines
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && extOpened) { extOpened = false; extBack = true }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); LoginWeb.flush() }
    }

    // Poll for "signed in": two positive checks in a row, so a page that briefly
    // shows the dashboard before a client-side redirect to /login does not count.
    if (detect && (kind.doneCookies.isNotEmpty() || kind.doneUrl != null || kind.doneJs != null)) {
        LaunchedEffect(kind.id) {
            var hits = 0
            delay(1500)
            while (!finished) {
                val u = current
                val cm = com.openminis.app.plus.LoginWeb.cookies(kind.id)
                val cookieHit = kind.doneCookies.isNotEmpty() && visited.any { v ->
                    val c = cm.getCookie(v).orEmpty()
                    com.openminis.app.plus.MailParse.cookieHas(c, kind.doneCookies)
                }
                val urlHit = kind.doneUrl?.matches(u) == true
                val jsHit = kind.doneJs?.let { eval(it).isNotBlank() } == true
                hits = if (cookieHit || urlHit || jsHit) hits + 1 else 0
                if (hits >= 2) { status = "已登录，正在确认…"; if (finish()) break else { hits = 0; delay(5000) } }
                delay(1500)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { webView?.stopLoading(); webView?.destroy() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(PlusConnect.hostOf(current), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { MinesIcon(Icons.Filled.Close, contentDescription = "关闭") }
                },
            )
        },
        bottomBar = {
            // [Mines-38.8] user 2026-10-16: no heavy solid full-width orange bar — translucent porcelain glass bar
            // with one compact terracotta pill (#B5532F), same family as the glass dialogs.
            val gm = minesColors(currentCharacter(), false)   // porcelain in day AND night (user: no brown bands)
            Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(PORCELAIN.copy(alpha = 0.86f), PORCELAIN.copy(alpha = 0.94f))))
                .drawBehind { drawLine(gm.glassBorder, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                GlassPill(if (detect) (if (external) "我在 Chrome 里登录好了" else "我已登录好了") else "完成",
                    modifier = Modifier.widthIn(min = 200.dp).semantics { contentDescription = if (detect) "我已登录好了" else "完成" }) {
                    if (detect) scope.launch { finish() } else onClose()
                }
            }
        },
    ) { padding ->
        // [Mines-webfit-38.3] keyboard: the page shrinks above it (bottom bar height already counted)
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
          // [Mines-38.8] user 2026-10-16: the solid brown bands (「已填好手机号」, 「在 Mines 内登录…」) are now ONE frosted-glass
          // strip: status (or the in-Mines hint) on the left, 「外部打开 · 不同步」 on the right.
          val gm = minesColors(currentCharacter(), false)
          val stripShape = RoundedCornerShape(16.dp)
          Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(stripShape)
              .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(PORCELAIN.copy(alpha = 0.80f), PORCELAIN.copy(alpha = 0.90f))))
              .border(1.dp, gm.glassBorder, stripShape)
              .then(if (extBack) Modifier.clickable { extBack = false; webView?.let { w -> if (w.url.isNullOrBlank() || w.url == "about:blank") w.loadUrl(url) else w.reload() } } else Modifier)
              .padding(start = 14.dp, end = 2.dp).heightIn(min = 44.dp),
              verticalAlignment = Alignment.CenterVertically) {
              val hint = when {
                  extBack -> "外部登录 Mines 拿不到，点这里在 Mines 内一键登录"
                  status != null -> status!!
                  else -> "在 Mines 内登录，自动关联"
              }
              Text(hint, fontSize = MT.Sub, lineHeight = MT.SubLH, color = if (status != null || extBack) gm.ink else gm.ink2,
                  fontWeight = if (status != null || extBack) FontWeight.Medium else FontWeight.Normal,
                  maxLines = if (status != null && status!!.length > 20) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
              androidx.compose.material3.TextButton(onClick = { extOpened = true
                  GoogleAuthRouter.openInCustomTab(context, webView?.url?.takeIf { it.startsWith("http") } ?: url) }) {
                  Text("外部打开 · 不同步", fontSize = MT.Meta, color = gm.primaryText, maxLines = 1, softWrap = false)
              }
          }
          Box(Modifier.fillMaxWidth().weight(1f)) {
            androidx.compose.runtime.key(reloadKey) { AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // [Mines-38.7] fixed size + focusable (typed text must never come out reversed)
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        isFocusable = true; isFocusableInTouchMode = true
                        LoginWeb.configure(this, kind.id)
                        settings.setSupportMultipleWindows(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress; load.progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                // Google blocks sign-in inside WebViews; hand that hop to Chrome.
                                // [Mines-sources-33] Gmail is tried inside Mines first (clean Chrome UA, no
                                // X-Requested-With) so its session can feed codes + mail; Google's refusal
                                // page is detected below and then we stop trying.
                                val tryGoogleHere = googleInApp && !u.contains("apple.com")
                                if (GoogleAuthRouter.shouldRouteExternally(u) && !tryGoogleHere) {
                                    // [Mines-38.7] user 2026-10-15: every sign-in stays inside Mines (no other app, no system
                                    // browser — a browser sign-in never reaches Mines' own cookies). Google / Apple block
                                    // their buttons inside apps, so say so and stay on the site's own sign-in page.
                                    status = "「用 Google / Apple 登录」在 App 里会被拦。请用这个网站自己的账号、手机号或扫码登录"
                                    com.openminis.app.plus.LoginAssist.providerBlockedAt = System.currentTimeMillis()   // [Mines-38.9.1] 一键关联 moves on to e-mail / phone
                                    return true
                                }
                                if (!u.startsWith("http")) return true // app deep links (weixin://, etc.)
                                return false
                            }
                            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                                current = u; visited.add(u); load.started(view, u)
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest?): android.webkit.WebResourceResponse? =
                                com.openminis.app.plus.WebBlock.intercept(request, current)   // [Mines-38.9.1] faster sign-in pages
                            override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: android.webkit.WebResourceError?) { load.mainFrameError(request, error) }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest?, r: android.webkit.WebResourceResponse?) { load.httpError(request, r) }
                            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) { handler.cancel(); load.sslError(error) }
                            override fun onPageFinished(view: WebView, u: String) {
                                current = u; visited.add(u); load.finished(view, u)
                                runCatching { com.openminis.app.plus.AutoAccounts.onPage(view, u) }   // [Mines-38.8] auto-record
                                LoginWeb.flush()
                            }
                            // [Mines-38.7] X (heavy page) could kill the page process, and an unhandled loss took the
                            // whole app down (「跳到 X 登录就闪退」). Keep the app, drop this page and load it again.
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                com.openminis.app.ui.webview.WebViewRenderProcess.handle("weblogin-${kind.id}", detail)
                                status = "页面刚才崩了一下，已重新打开。还不行就点「重试」"
                                webView = null
                                runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
                                runCatching { view.destroy() }
                                reloadKey++
                                return true
                            }
                        }
                        if (!external) loadUrl(url)
                        webView = this
                    }
                },
            ) }
            // [Mines-web-35] progress + watchdog + plain reason with 重试 / 用外部浏览器打开
            WebLoadOverlay(load, webView, onRetry = { webView?.let { w -> if (w.url.isNullOrBlank() || w.url == "about:blank") w.loadUrl(url) else w.reload() } },
                // [Mines-38.7] 「用外部浏览器登录」: only opens the page — no platform here has an official OAuth client_id set
                // up in Mines, so a browser sign-in can never reach Mines. Said plainly; coming back offers the in-app login.
                onExternal = { extOpened = true; GoogleAuthRouter.openInCustomTab(context, webView?.url?.takeIf { it.startsWith("http") } ?: url) })
          }
        }
    }
}

/** [Mines-38.8] porcelain glass base for the sign-in page chrome (same as the light glass veil). */
private val PORCELAIN = androidx.compose.ui.graphics.Color(0xFFFBF7F2)

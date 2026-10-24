package com.openminis.app.ui.plus

import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.horizontalScroll

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.plus.ConnectionKind
import com.openminis.app.plus.KeyAgent
import com.openminis.app.plus.KeyTester
import com.openminis.app.plus.LoginWeb
import com.openminis.app.plus.PlusConnect
import com.openminis.app.plus.PlusVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * [Mines-keys-31] Full-screen "Mines 帮你拿 Key": the provider's key page in an
 * in-app WebView driven by [KeyAgent]. The user only signs in; one big line
 * says what is happening, and one button appears only when the user must act.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KeyAgentDialog(kind: ConnectionKind, onFallback: ((candidate: String?) -> Unit)? = null, onClose: (saved: Boolean) -> Unit) {
    val context = LocalContext.current
    val recipe = KeyAgent.recipe(kind.id) ?: run { onFallback?.invoke(null); onClose(false); return }
    // [Mines-keys-35.3] keys already on the page before Mines clicked anything are never taken (generic recipes)
    val baseline = remember { java.util.Collections.synchronizedSet(HashSet<String>()) }
    var status by remember { mutableStateOf(recipe.note.ifBlank { "正在打开官方页面…" }) }
    var waitUser by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val captured = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    val known = PlusVault.secret(kind.id)
    // [Mines-fix-35.1] 一键修复 = same account row, new key replaces the old one only after it tested OK
    val fixing = known != null
    var usedName by remember { mutableStateOf<String?>(null) }
    var askDeleteOld by remember { mutableStateOf(false) }
    var onLoginPage by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var pickEmail by remember { mutableStateOf(false) }
    // [Mines-38.9.1] 火山方舟 / 百炼 / 硅基流动 / OpenAI consoles stayed WHITE here: since 38.3 every in-app WebView forced a
    // MOBILE user agent (WebFit), and these desktop-only consoles answer a phone UA with an empty shell (火山 only drew its
    // floating 在线咨询 widget). Their key pages now open in 电脑版 (desktop UA + wide viewport, pinch to zoom) from the
    // start; any other page that stays blank is reloaded once in 电脑版 before falling back to Chrome + clipboard capture.
    var desktop by remember { mutableStateOf(when (PlusVault.extra(kind.id, "ua_desktop")) { "1" -> true; "0" -> false; else -> KeyAgent.desktopFirst(recipe.url) }) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun pick(t: String, useBaseline: Boolean): String? = recipe.keyRegex.findAll(t).map { it.value }
        .firstOrNull { it != known && !it.all { c -> c.isDigit() } && (!useBaseline || it !in baseline) }
    /** closing without a saved key: hand what could be read (page copy or clipboard) to the manual field */
    fun close(saved: Boolean) {
        if (!saved) onFallback?.invoke(captured.get()?.let { pick(it, false) } ?: captured.get()?.trim()?.takeIf { it.length in 16..400 && !it.contains(' ') })
        onClose(saved)
    }

    suspend fun eval(js: String): String {
        val wv = webView ?: return ""
        return suspendCancellableCoroutine { cont ->
            runCatching {
                wv.evaluateJavascript("(function(){try{${KeyAgent.JS_HELPER};return String($js)}catch(e){return ''}})()") { r ->
                    val v = runCatching { org.json.JSONTokener(r ?: "\"\"").nextValue() as? String }.getOrNull() ?: ""
                    if (cont.isActive) cont.resume(v)
                }
            }.onFailure { if (cont.isActive) cont.resume("") }
        }
    }

    suspend fun finishWith(key: String) {
        status = "拿到了，正在测试这个密钥…"
        val r = withContext(Dispatchers.IO) { KeyTester.test(PlusVault.baseId(kind.id), key) }
        if (!r.ok) { status = "拿到的密钥测试没通过：${r.message}。可以点「继续」再试一次"; waitUser = true; return }
        PlusVault.save(context, kind, r.identity ?: PlusVault.entries.value[kind.id]?.account ?: "", key,
            share = PlusVault.entries.value[kind.id]?.shareWithAi ?: (kind.envKey != null), via = "key")
        r.identity?.let { PlusVault.setExtra(kind.id, "identity", it) }
        PlusVault.setExtra(kind.id, "token_name", usedName ?: "-")
        done = (if (fixing) "✓ 已修复，换上了新密钥：" else "✓ 已接入：") + com.openminis.app.plus.AccountLabel.maskSecret(key) + "\n" + r.message +
            (if (fixing) "\n旧密钥在 Mines 里保留 7 天，可在账号行上一键恢复" else "")
        if (fixing && known != key) askDeleteOld = true
    }

    LaunchedEffect(webView, resumeTick) {
        val wv = webView ?: return@LaunchedEffect
        waitUser = false
        var step = 0
        var misses = 0
        // [Mines-keys-32] after the user signs in (or taps a remembered account, as on
        // DigitalOcean) the site often lands on its dashboard: go back to the key page once.
        var sawLogin = false
        var reopened = 0
        var blanks = 0
        var lastUrl = ""
        var urlSince = System.currentTimeMillis()
        val until = System.currentTimeMillis() + 5 * 60_000
        delay(2500)
        while (done == null && System.currentTimeMillis() < until) {
            if (registering) { delay(1500); continue }
            val text = eval("window.__mines.text()")
            if (step == 0) recipe.keyRegex.findAll(text).forEach { baseline += it.value }
            val key = captured.get()?.let { pick(it, false) } ?: pick(text, true)
            if (key != null && step > 0) { finishWith(key); if (done != null || waitUser) return@LaunchedEffect }
            val url = wv.url.orEmpty()
            // [Mines-38.1b] coze.cn's sign-in hops through 3 hosts (blank between hops): a new URL is a new page, not a
            // white page — only a page that stays blank on the SAME url counts towards handing off to Chrome
            if (url != lastUrl) { lastUrl = url; blanks = 0; urlSince = System.currentTimeMillis() }
            val onLogin = url.contains("login", true) || url.contains("signin", true) || url.contains("sign-in", true) ||
                url.contains("/sign?", true) || url.endsWith("/sign", true) ||
                url.contains("auth", true) && !url.contains("token", true) && !url.contains("oauth/pats", true) ||
                (step == 0 && recipe.loginHints.any { text.contains(it) } && text.length < 4000 && misses > 2)
            // [Mines-38.1b] a phone + SMS-code LOGIN page is just sign-in: keep polling so Mines continues by itself after
            // the user logs in (before, 「要验证手机号」 stopped the loop and the PAT page never came back)
            if (!onLogin) KeyAgent.needsUser(text)?.let { status = it; waitUser = true; return@LaunchedEffect }
            // [Mines-login-32] page shows nothing → open it in Chrome; the key is picked up from the clipboard.
            // [Mines-web-35.1] checked on login pages too (siliconflow's login stayed white behind 「请先登录」).
            if (eval(LoginWeb.BLANK_JS) == "blank") {
                // [Mines-38.2] smoke 39: www.coze.cn/open/oauth/pats is a big SPA that renders nothing for ~10 s on a slow
                // phone (then hops to signin.volcengine.com); 4 blank checks in ~7 s sent it to Chrome. Only a page that has
                // FINISHED loading and stayed blank for 12 s on the same URL counts now.
                if (wv.progress >= 100 && System.currentTimeMillis() - urlSince > 12_000) blanks++
                if (blanks >= 4 && !desktop) {
                    desktop = true; blanks = 0; urlSince = System.currentTimeMillis()
                    PlusVault.setExtra(kind.id, "ua_desktop", "1")
                    wv.settings.userAgentString = KeyAgent.desktopUa(context); wv.reload()
                    status = "页面是空白的，已改用电脑版打开…"
                    delay(3000); continue
                }
                if (blanks >= 4) {
                    PlusVault.setExtra(kind.id, "login_external", "1")
                    com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(context, recipe.url)
                    status = "这个页面在 Mines 里显示不出来，已改用 Chrome 打开。创建好密钥点「复制」，回到 Mines 会自动填入"
                    waitUser = true; return@LaunchedEffect
                }
                delay(1500); continue
            } else blanks = 0
            onLoginPage = onLogin
            if (onLogin) { sawLogin = true; status = "请先在页面上登录（点你的账号也行），登录好 Mines 会自动继续"; misses = 0; delay(2000); continue }
            val recipeHost = PlusConnect.hostOf(recipe.url)
            val recipePath = runCatching { java.net.URI(recipe.url).path.orEmpty() }.getOrDefault("")
            if (sawLogin && reopened < 2 && step == 0 && (PlusConnect.hostOf(url) != recipeHost || (recipePath.length > 1 && !url.contains(recipePath)))) {
                reopened++; sawLogin = false
                status = "已登录，正在打开创建密钥的页面…"
                wv.loadUrl(recipe.url); delay(3000); continue
            }
            if (step < recipe.steps.size) {
                val s = recipe.steps[step]
                val nm = s.fill?.let { KeyAgent.tokenName(it) }
                val ok = if (nm != null) eval("window.__mines.fill(${org.json.JSONObject.quote(nm)})").isNotBlank().also { if (it) usedName = nm }
                    else eval("window.__mines.click(${org.json.JSONArray(s.click)})").isNotBlank()
                if (ok) { status = if (s.fill != null) "已填好名称…" else "已点「${s.click.first()}」…"; step++; misses = 0; delay(900)
                    if (s.fill == null) eval(KeyAgent.CLOSE_DRAWER_JS)   // [Mines-38.9.1] a menu left open covered the key page (Groq)
                    continue }
                misses++
                if (s.optional && misses >= 3) { step++; misses = 0 }
                else if (misses >= 12) {
                    // A payment wall or a page we don't know: say it plainly, never click "pay".
                    status = if (KeyAgent.payWords.any { text.contains(it, true) }) "这里需要绑卡或充值，Mines 不会替你付钱。你可以自己处理后点「继续」"
                        else "页面和预想的不一样，请你点一下「${s.click.firstOrNull() ?: "创建"}」，然后点「继续」"
                    waitUser = true; return@LaunchedEffect
                }
            } else {
                status = "正在读取新密钥…"
                misses++
                if (misses == 4) eval("window.__mines.click(['Copy','复制','Copy key','Copy token'])")
                if (misses > 12) { status = "没在页面上找到新密钥。请点页面上的「复制」，然后点「继续」"; waitUser = true; return@LaunchedEffect }
            }
            delay(800)
        }
        if (done == null && !waitUser) { status = "时间太久了。可以点「继续」再试，或关闭后手动粘贴"; waitUser = true }
    }

    DisposableEffect(Unit) { onDispose { runCatching { webView?.stopLoading(); webView?.destroy() } } }
    val load = remember { WebLoadState() }

    // [Mines-webfit-38.3] decorFitsSystemWindows = false: the dialog window is the full screen and the insets below are real.
    // With the default (true) the window was shorter than the content measured for it on the 1280x2800 emulator, and the
    // bottom 帮我注册 / 重试 / 浏览器打开 / 关闭 row was drawn below the window (unclipped, transparent, not tappable) — smoke 44.
    Dialog(onDismissRequest = { close(done != null) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // [Mines-webpaint-38.4] smoke 44: the settings page behind showed through the bottom 帮我注册 / 重试 / 浏览器打开 / 关闭
        // row: the theme's `surface` is Color.Transparent (pages sit on AppNavigation's porcelain backdrop), so this dialog's
        // Surface was see-through wherever the WebView did not cover it. Now an opaque porcelain colour, and the dialog
        // window is forced to the full screen (not wrap-content).
        val dialogView = androidx.compose.ui.platform.LocalView.current
        val bannerArgb = android.graphics.Color.parseColor("#FBF7F2")   // [Mines-38.8] porcelain status bar over the glass strip (was the peach banner)
        androidx.compose.runtime.SideEffect {
            ((dialogView as? androidx.compose.ui.window.DialogWindowProvider) ?: (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider))
                ?.window?.let { w ->
                    w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    // [Mines-38.7 v11] smoke shots: a black status bar over the light banner. Same colour as the banner, dark icons.
                    @Suppress("DEPRECATION") runCatching { w.statusBarColor = bannerArgb }
                    runCatching { androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightStatusBars = true }
                }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SecureWindow()  // [Mines-privacy-35.3] the key is on this page
            // [Mines-webfit-38.3] the page shrinks above the keyboard instead of hiding its input fields
            val imeUp = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                // [Mines-38.8] user 2026-10-16 13:46: the peach banner (3 lines of big text, ~1/6 of the screen) is now ONE compact
                // frosted-glass strip: title 15 sp on one line, one 12 sp gray line merging the notes, ellipsized.
                run {
                    val gm = mines()
                    val stripShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    val title = if (done != null) (if (fixing) "已修复 ${kind.name}" else "已接入 ${kind.name}") else if (fixing) "正在修复 ${kind.name}" else "正在接入 ${kind.name}"
                    val note = done ?: listOfNotNull(status.takeIf { it.isNotBlank() }, "不会点任何付费按钮").distinct().joinToString(" · ")
                    // [Mines-38.9.1] keyboard up (Groq 「Create API Key」 dialog): one slim line, the page gets the rest
                    if (imeUp) Text(title + " · " + note, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = Color(0xFF2B2420), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(minesGlassFill(strong = true)).padding(horizontal = 14.dp, vertical = 4.dp))
                    else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(stripShape)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFBF7F2).copy(alpha = 0.86f), Color(0xFFFBF7F2).copy(alpha = 0.94f))))
                        .border(1.dp, gm.glassBorder, stripShape).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                      // mock3 接入页-紧凑: shield-check disc on the left
                      Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFFAE4DA)), contentAlignment = Alignment.Center) {
                          com.openminis.app.ui.brand.MinesIcon(com.openminis.app.ui.brand.MinesIcons.named("shield-check")!!, null, tint = Color(0xFFC05A34), modifier = Modifier.size(17.dp))
                      }
                      androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                      Column(Modifier.weight(1f)) {
                        Text(title, fontSize = MT.Body, lineHeight = MT.BodyTightLH, fontWeight = FontWeight.SemiBold, color = Color(0xFF2B2420), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(note, fontSize = MT.Caption, lineHeight = MT.CaptionLH, color = if (done != null) Color(0xFF1F7A3F) else Color(0xFF6E625A), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                      }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx0 ->
                        // [Mines-38.9.1] the site's OWN light theme: Mines in dark mode made pages see prefers-color-scheme: dark
                        // while the WebView canvas stayed white, so dark-theme menus with see-through backgrounds sat on top of
                        // the light page (Groq's drawer, 「ui重叠」). Nothing of Mines' theme is injected into third-party pages.
                        val ctx = android.view.ContextThemeWrapper(ctx0, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                        WebView(ctx).apply {
                            LoginWeb.configure(this, kind.id)
                            if (desktop) { settings.userAgentString = KeyAgent.desktopUa(ctx); settings.useWideViewPort = true; settings.loadWithOverviewMode = true }
                            setBackgroundColor(android.graphics.Color.WHITE)
                            // OAuth / SSO pop-ups (window.open) load in this same page instead of being dropped
                            settings.setSupportMultipleWindows(true)
                            com.openminis.app.browser.WebFit.shown = java.lang.ref.WeakReference(this)  // [Mines-webfit-38.3] self-test probe
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { load.progress = newProgress }
                                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                    val main = view
                                    val child = WebView(view.context)
                                    child.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean {
                                            main.loadUrl(r.url.toString()); runCatching { v.destroy() }; return true
                                        }
                                    }
                                    val t = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    t.webView = child; resultMsg.sendToTarget(); return true
                                }
                            }
                            addJavascriptInterface(object {
                                @JavascriptInterface fun found(t: String?) { if (!t.isNullOrBlank()) captured.set(t) }
                            }, "MinesKey")
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                    val u = request.url.toString()
                                    // [Mines-login-32] Google / Apple sign-in hops always go to Chrome
                                    if (com.openminis.app.browser.GoogleAuthRouter.shouldRouteExternally(u)) {
                                        com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(ctx, u)
                                        status = "Google / Apple 登录要在 Chrome 里完成。登录好后回到这里点「继续」"
                                        waitUser = true
                                        return true
                                    }
                                    return !u.startsWith("http")
                                }
                                @Volatile var pageUrl: String? = recipe.url
                                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? =
                                    com.openminis.app.plus.WebBlock.intercept(request, pageUrl)   // [Mines-38.9.1] trackers / chat widgets
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { pageUrl = url; load.started(view, url) }
                                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) { load.mainFrameError(request, error) }
                                override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest?, r: android.webkit.WebResourceResponse?) { load.httpError(request, r) }
                                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError?) { handler.cancel(); load.sslError(error) }
                                override fun onPageFinished(view: WebView, url: String) {
                                    load.finished(view, url)
                                    // Catch the page's own 「复制」 button: clipboard writes go to Mines too.
                                    view.evaluateJavascript("""(function(){try{if(window.__minesClip)return;window.__minesClip=1;
                                        var c=navigator.clipboard;if(c&&c.writeText){var o=c.writeText.bind(c);c.writeText=function(t){try{MinesKey.found(String(t))}catch(e){};return o(t)}}
                                        document.addEventListener('copy',function(){try{MinesKey.found(String(window.getSelection()))}catch(e){}},true);}catch(e){}})()""", null)
                                    LoginWeb.flush()
                                }
                            }
                            loadUrl(recipe.url)
                            webView = this
                        }
                    })
                    WebLoadOverlay(load, webView, onRetry = { webView?.let { w -> if (w.url.isNullOrBlank() || w.url == "about:blank") w.loadUrl(recipe.url) else w.reload() } },
                        onExternal = { com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(context, webView?.url?.takeIf { it.startsWith("http") } ?: recipe.url) })
                }
                // [Mines-webpaint-38.4] the action row has its own opaque background and its own space below the page (never
                // over it); it sits above the navigation bar, and above the keyboard through the column's imePadding
                // (the nav-bar inset is not added twice while the keyboard is up).
                // [Mines-38.8] compact pills (40 dp, 14 sp) on the frosted porcelain bar, like the 38.8 WebLoginScreen bottom bar
                if (!imeUp) Row(Modifier.fillMaxWidth().background(minesGlassFill(strong = true))
                    .drawBehind { drawLine(Color(0x22B5532F), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                    .navigationBarsPadding().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    if (done != null) KaPill("完成", primary = true) { close(true) }
                    else {
                        if (waitUser) KaPill("继续", primary = true) { resumeTick++ }
                        // [Mines-register-35.1] no account yet → 帮我注册 (one account per service, for the user only)
                        if (onLoginPage && !fixing && !registering) KaPill("帮我注册", primary = !waitUser) { pickEmail = true }
                        // [Mines-web-35.1] always a way out of a white page: reload, or the same page in Chrome
                        KaPill("重试", icon = "rotate-ccw") { webView?.let { w -> if (w.url.isNullOrBlank() || w.url == "about:blank") w.loadUrl(recipe.url) else w.reload() } }
                        KaPill("浏览器打开", primary = !waitUser && !(onLoginPage && !fixing && !registering), icon = "globe") {
                            PlusVault.setExtra(kind.id, "login_external", "1")
                            com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(context, webView?.url?.takeIf { it.startsWith("http") } ?: recipe.url)
                            status = "已用浏览器打开。创建好密钥点「复制」，回到 Mines 会自动填入"; waitUser = true
                        }
                        // [Mines-38.9.1] 电脑版 / 手机版 switch for consoles that need it
                        KaPill(if (desktop) "手机版" else "电脑版", icon = "laptop") {
                            desktop = !desktop; PlusVault.setExtra(kind.id, "ua_desktop", if (desktop) "1" else "0")
                            webView?.let { w -> w.settings.userAgentString = if (desktop) KeyAgent.desktopUa(context) else LoginWeb.userAgent(context); w.reload() }
                        }
                        KaPill("关闭", icon = "x") { close(false) }
                    }
                }
            }
        }
    }
    if (askDeleteOld) com.openminis.app.ui.glass.AlertDialog(onDismissRequest = { askDeleteOld = false },
        title = { Text("要在 ${kind.name} 上删除旧密钥吗？") },
        text = { Text("新密钥已经测通并换上了。网站上那个失效的旧密钥" +
            (PlusVault.extra(kind.id, "prev_token_name")?.takeIf { it != "-" }?.let { "（名字「$it」）" } ?: "") +
            "还留着。要删的话，我打开密钥列表并把它标出来，你点一下删除就好。", fontSize = MT.Body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            askDeleteOld = false
            val listUrl = KeyAgent.listUrl(kind.id)
            webView?.let { w ->
                w.loadUrl(listUrl)
                val old = PlusVault.extra(kind.id, "prev_token_name")?.takeIf { it != "-" }
                scope.launch {
                    kotlinx.coroutines.delay(3500)
                    if (old != null) eval(KeyAgent.highlightJs(old))
                }
            }
            done = (done ?: "") + "\n已打开密钥列表" + (PlusVault.extra(kind.id, "prev_token_name")?.takeIf { it != "-" }?.let { "，旧的「$it」已用红框标出" } ?: "") + "。点它旁边的删除（Mines 不会替你点删除）"
        }) { Text("要删") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { askDeleteOld = false }) { Text("先留着") } })

    if (pickEmail) {
        val emails = remember { RegisterHelper.emails(context) }
        var typed by remember { mutableStateOf(emails.firstOrNull() ?: "") }
        com.openminis.app.ui.glass.AlertDialog(onDismissRequest = { pickEmail = false },
            title = { Text("用哪个邮箱注册 ${kind.name}？") },
            text = { androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("页面上有「用 Google / GitHub 登录」时，我会优先用它（用你手机上已登录的账号）。没有的话用这个邮箱注册，密码由 Mines 生成并加密保存，邮件里的验证码会自动填。", fontSize = MT.Body)
                emails.forEach { e -> androidx.compose.material3.TextButton(onClick = { typed = e }) { Text((if (typed == e) "● " else "○ ") + e, fontSize = MT.Body) } }
                androidx.compose.material3.OutlinedTextField(typed, { typed = it.trim() }, label = { Text("邮箱") }, singleLine = true)
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = typed.contains("@"), onClick = {
                pickEmail = false; registering = true
                scope.launch {
                    val wv = webView
                    if (wv != null) status = RegisterHelper.run(context, kind.id, typed, { js -> eval(js) }, { st -> status = st })
                    registering = false
                    // signed up / code filled → the key flow continues by itself; otherwise the line says the one next step
                    if (status.startsWith("注册好了") || status.startsWith("已点「用 GitHub")) resumeTick++ else waitUser = true
                }
            }) { Text("开始注册") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pickEmail = false }) { Text("取消") } })
    }

}



/** [Mines-38.8] compact 40 dp / 14 sp pill for the key page's frosted bottom bar (primary = terracotta, else quiet outline). */
@Composable
private fun KaPill(text: String, primary: Boolean = false, icon: String? = null, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(Modifier.heightIn(min = 40.dp).clip(shape)
        .background(if (primary) Color(0xFFC05A34) else Color(0xFFFFFDFA).copy(alpha = 0.92f))
        .border(1.dp, if (primary) Color(0xFFC05A34) else Color(0xFFE8DED6), shape)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { n -> com.openminis.app.ui.brand.MinesIcons.named(n) }?.let { iv ->
                com.openminis.app.ui.brand.MinesIcon(iv, null, tint = if (primary) Color.White else Color(0xFF2B2420), modifier = Modifier.size(15.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
            }
            Text(text, fontSize = MT.Body, lineHeight = MT.CaptionLH, fontWeight = FontWeight.Medium, color = if (primary) Color.White else Color(0xFF2B2420),
                maxLines = 1, softWrap = false)
        }
    }
}

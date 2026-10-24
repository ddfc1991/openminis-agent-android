package com.openminis.app.plus

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.BrowserUseJS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [Mines-38.9.13 mines_browser] Mines' own browser: GeckoView (Firefox engine, MPL-2.0) driven by the agent through a
 * built-in WebExtension (assets/mines_browser). One private, isolated session per task (owner = chat session / sub-agent),
 * wiped at task end; traffic through the app's passing local proxy (严格代理 → blocked, never direct); human-like
 * clicks (native touch) and typing. The WebView pool stays the default; this is used for `engine:"gecko"` calls.
 *
 * Control API for later skills (social accounts, mail clean-up): [task] + [evaluate] / [execute]; anything that posts or
 * messages other people must check [MinesBrowserCore.needsUserConfirm] and get the user's yes first.
 */
object MinesBrowser {
    private const val TAG = "MinesBrowser"
    private const val EXT_URI = "resource://android/assets/mines_browser/"
    private const val EXT_ID = "mines-browser@openminis"
    private const val NAV_TIMEOUT_MS = 30_000L
    private const val PORT_WAIT_MS = 8_000L
    private const val EVAL_TIMEOUT_MS = 20_000L

    @Volatile private var app: Context? = null
    private var runtime: GeckoRuntime? = null
    private var ext: WebExtension? = null
    @Volatile var startError: String = ""; private set
    private val tasks = ConcurrentHashMap<String, Task>()
    private val serial = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val frames: Handler by lazy { Handler(HandlerThread("mines-gecko-frames").apply { start() }.looper) }
    @Volatile private var sweeping = false

    class Task(val key: String, val contextId: String, val session: GeckoSession) {
        var display: GeckoDisplay? = null
        var reader: ImageReader? = null
        var width = 0; var height = 0
        @Volatile var port: WebExtension.Port? = null
        @Volatile var portWait = CompletableDeferred<Unit>()
        @Volatile var pageStop = CompletableDeferred<Unit>()
        @Volatile var url: String = ""
        @Volatile var title: String = ""
        @Volatile var dead: String = ""
        @Volatile var lastUsed: Long = SystemClock.elapsedRealtime()
        @Volatile var proxyLabel: String = ""
        @Volatile var handedToUser = false
        val pending = ConcurrentHashMap<Int, CompletableDeferred<MinesBrowserCore.Reply>>()
        val ids = AtomicInteger()
    }

    // ── flag ─────────────────────────────────────────────────────────────────

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    fun on(): Boolean = app?.getSharedPreferences(MinesBrowserCore.PREFS, Context.MODE_PRIVATE)
        ?.getBoolean(MinesBrowserCore.FLAG, true) ?: true

    fun setOn(c: Context, v: Boolean) {
        init(c)
        c.getSharedPreferences(MinesBrowserCore.PREFS, Context.MODE_PRIVATE).edit().putBoolean(MinesBrowserCore.FLAG, v).apply()
        if (!v) scope.launch { tasks.keys.toList().forEach { endTask(it) } }
    }

    private fun key(owner: String?) = owner ?: "chat"
    fun hasTask(owner: String?): Boolean = tasks.containsKey(key(owner))
    fun promptLine(): String? = if (on()) MinesBrowserCore.PROMPT else null

    // ── router used by ChatViewModel ─────────────────────────────────────────

    /**
     * browser_use entry point. Gecko when [MinesBrowserCore.useGecko] says so, else [webView] (the existing pool). If Gecko
     * cannot start, the call falls back to the WebView pool with a one-line note.
     */
    suspend fun route(
        context: Context, argsJson: String, input: BrowserActionInput, owner: String?,
        webView: suspend () -> BrowserActionResult,
    ): BrowserActionResult {
        init(context)
        val engine = MinesBrowserCore.engineOf(argsJson)
        val action = runCatching { JSONObject(argsJson).optString("action") }.getOrDefault(input.action.value)
        if (!MinesBrowserCore.useGecko(on(), engine, hasTask(owner), action)) return webView()
        if (action !in MinesBrowserCore.SUPPORTED) return BrowserActionResult.error(MinesBrowserCore.unsupported(action).removePrefix("Error: "))
        return try {
            execute(owner, input, action)
        } catch (e: GeckoUnavailable) {
            val r = webView()
            r.copy(text = "[Mines 浏览器没能启动（${e.message}），这次改用普通内置浏览器]\n" + r.text)
        }
    }

    class GeckoUnavailable(msg: String) : Exception(msg)

    // ── runtime / task lifecycle ─────────────────────────────────────────────

    private suspend fun <T> GeckoResult<T>.await(ms: Long = EVAL_TIMEOUT_MS): T? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(ms) {
            suspendCancellableCoroutine<T?> { c ->
                this@await.accept({ v -> if (c.isActive) c.resume(v) }, { e -> if (c.isActive) c.resumeWithException(e ?: Exception("gecko")) })
            }
        }
    }

    private suspend fun ensureRuntime(): GeckoRuntime = withContext(Dispatchers.Main) {
        runtime?.let { return@withContext it }
        val c = app ?: throw GeckoUnavailable("no context")
        if (!ProcessGuard.isMain) throw GeckoUnavailable("not the main process")
        val rt = try {
            GeckoRuntime.create(c, GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)   // no Marionette → no navigator.webdriver
                .consoleOutput(false)
                .aboutConfigEnabled(false)
                .loginAutofillEnabled(false)
                .build())
        } catch (t: Throwable) {
            startError = "${t.javaClass.simpleName}: ${t.message}".take(200)
            throw GeckoUnavailable(startError)
        }
        runtime = rt
        val e = try {
            rt.webExtensionController.ensureBuiltIn(EXT_URI, EXT_ID).await(30_000L)
        } catch (t: Throwable) { null } ?: run { startError = "bridge extension did not load"; throw GeckoUnavailable(startError) }
        ext = runCatching { rt.webExtensionController.setAllowedInPrivateBrowsing(e, true).await(10_000L) }.getOrNull() ?: e
        rt
    }

    /** the task's private session, created on first use (proxy applied first) */
    suspend fun task(owner: String?): Task {
        val k = key(owner)
        tasks[k]?.takeIf { it.dead.isEmpty() }?.let { it.lastUsed = SystemClock.elapsedRealtime(); return it }
        tasks[k]?.let { endTask(k) }
        val rt = ensureRuntime()
        val label = applyProxy()
        val t = withContext(Dispatchers.Main) { openSession(rt, k) }
        t.proxyLabel = label
        tasks[k] = t
        startSweeper()
        return t
    }

    private fun openSession(rt: GeckoRuntime, k: String): Task {
        val cid = MinesBrowserCore.contextId(k, serial.incrementAndGet())
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(cid).useTrackingProtection(true).build())
        val t = Task(k, cid, s)
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                t.url = url ?: ""
            }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                session.loadUri(uri)   // target=_blank / window.open: stay in this task's one tab
                return null
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                t.port = null; t.portWait = CompletableDeferred(); t.pageStop = CompletableDeferred()
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) { t.pageStop.complete(Unit) }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: "" }
            override fun onCrash(session: GeckoSession) { t.dead = "页面进程崩溃" }
            override fun onKill(session: GeckoSession) { t.dead = "页面进程被系统回收" }
        }
        ext?.let { e ->
            s.webExtensionController.setMessageDelegate(e, object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    t.port = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            MinesBrowserCore.parseReply(message)?.let { r -> t.pending.remove(r.id)?.complete(r) }
                        }
                        override fun onDisconnect(port: WebExtension.Port) { if (t.port === port) t.port = null }
                    })
                    t.portWait.complete(Unit)
                }
            }, "mines")
        }
        s.open(rt)
        attachOffscreen(t)
        s.setActive(true)
        return t
    }

    /** offscreen surface (frames drained) so the page lays out, paints and can be captured without a visible view */
    private fun attachOffscreen(t: Task) {
        val dm = app!!.resources.displayMetrics
        t.width = dm.widthPixels; t.height = dm.heightPixels
        val r = if (android.os.Build.VERSION.SDK_INT >= 29)
            ImageReader.newInstance(t.width, t.height, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        else ImageReader.newInstance(t.width, t.height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> runCatching { rd.acquireLatestImage()?.close() } }, frames)
        val d = t.session.acquireDisplay()
        d.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(r.surface).size(t.width, t.height).build())
        t.reader = r; t.display = d
    }

    private fun detachOffscreen(t: Task) {
        runCatching { t.display?.let { it.surfaceDestroyed(); t.session.releaseDisplay(it) } }
        runCatching { t.reader?.close() }
        t.display = null; t.reader = null
    }

    /** task end: close the session and wipe its cookies / storage / cache */
    suspend fun endTask(owner: String?) {
        val t = tasks.remove(key(owner)) ?: return
        withContext(Dispatchers.Main) {
            detachOffscreen(t)
            runCatching { t.session.close() }
            runCatching { runtime?.storageController?.clearDataForSessionContext(t.contextId) }
        }
        t.pending.values.forEach { it.completeExceptionally(Exception("task closed")) }
    }

    private fun startSweeper() {
        if (sweeping) return
        sweeping = true
        scope.launch {
            while (tasks.isNotEmpty()) {
                delay(60_000L)
                val now = SystemClock.elapsedRealtime()
                tasks.values.filter { !it.handedToUser && now - it.lastUsed > MinesBrowserCore.IDLE_MS }.forEach { endTask(it.key) }
            }
            sweeping = false
        }
    }

    /** same port the app's WebViews use; 严格代理 without one → blocked */
    @androidx.annotation.OptIn(markerClass = [org.mozilla.geckoview.ExperimentalGeckoViewApi::class])
    private suspend fun applyProxy(): String {
        val port = runCatching { WebProxy.passingPort() }.getOrNull()
        val strict = runCatching { SandboxNet.strict }.getOrDefault(false)
        val prefs = MinesBrowserCore.proxyPrefs(port, strict).map { (k, v) ->
            val b = GeckoPreferenceController.PREF_BRANCH_USER
            when (v) {
                is Int -> GeckoPreferenceController.SetGeckoPreference.setIntPref(k, v, b)
                is Boolean -> GeckoPreferenceController.SetGeckoPreference.setBoolPref(k, v, b)
                else -> GeckoPreferenceController.SetGeckoPreference.setStringPref(k, v.toString(), b)
            }
        }
        runCatching { withContext(Dispatchers.Main) { GeckoPreferenceController.setGeckoPrefs(prefs) }.await(5_000L) }
            .onFailure { Log.w(TAG, "proxy prefs: ${it.message}") }
        return MinesBrowserCore.proxyLabel(port, strict)
    }

    // ── page access ──────────────────────────────────────────────────────────

    /** run [js] in the page (content-script scope); returns the string value, throws on a JS error */
    suspend fun evaluate(t: Task, js: String, timeoutMs: Long = EVAL_TIMEOUT_MS): String {
        if (t.dead.isNotEmpty()) throw Exception(t.dead)
        if (t.port == null) withTimeoutOrNull(PORT_WAIT_MS) { t.portWait.await() }
        val port = t.port ?: throw Exception("页面还没准备好（或这个页面不允许脚本，例如 about: 页面）")
        val id = t.ids.incrementAndGet()
        val d = CompletableDeferred<MinesBrowserCore.Reply>()
        t.pending[id] = d
        withContext(Dispatchers.Main) { port.postMessage(JSONObject().put("id", id).put("code", js)) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: run { t.pending.remove(id); throw Exception("页面 ${timeoutMs / 1000} 秒没有回应") }
        if (!r.ok) throw Exception(r.value)
        return r.value
    }

    private suspend fun evalSoft(t: Task, js: String): String? = runCatching { evaluate(t, js, 6_000L) }.getOrNull()

    private suspend fun capture(t: Task): Bitmap? = runCatching { t.display?.capturePixels()?.await(8_000L) }.getOrNull()

    private fun saveJpeg(bmp: Bitmap, quality: Int): Pair<File, ByteArray> {
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        val dir = File(app!!.cacheDir, "mines_browser").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        return f to bytes
    }

    /** native touch at device-pixel coordinates of the offscreen surface (trusted events, like a finger) */
    private suspend fun tap(t: Task, x: Float, y: Float) = withContext(Dispatchers.Main) {
        val pz = t.session.panZoomController
        val down = SystemClock.uptimeMillis()
        val e1 = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        pz.onTouchEvent(e1); e1.recycle()
        delay(MinesBrowserCore.pressMs())
        val up = SystemClock.uptimeMillis()
        val e2 = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0)
        pz.onTouchEvent(e2); e2.recycle()
    }

    private fun q(s: String) = "'" + BrowserUseJS.jsQuote(s) + "'"

    // ── actions ──────────────────────────────────────────────────────────────

    suspend fun execute(owner: String?, input: BrowserActionInput, action: String): BrowserActionResult {
        if (action == "close_tab") {
            endTask(owner)
            return BrowserActionResult("已关闭这个任务的 Mines 浏览器，Cookie、缓存和登录状态都已清除。")
        }
        val t = task(owner)
        t.lastUsed = SystemClock.elapsedRealtime()
        var r: BrowserActionResult = try {
            when (action) {
                "navigate" -> navigate(t, input.url)
                "get_text" -> plain(t, BrowserUseJS.getText(input.selector))
                "get_readable" -> plain(t, BrowserUseJS.getReadable())
                "get_page_info" -> plain(t, BrowserUseJS.getPageInfo())
                "find_elements" -> input.selector?.let { plain(t, BrowserUseJS.findElements(it)) } ?: BrowserActionResult.error("find_elements requires 'selector'")
                "hover" -> input.selector?.let { plain(t, BrowserUseJS.hover(it)) } ?: BrowserActionResult.error("hover requires 'selector'")
                "click" -> click(t, input)
                "type" -> type(t, input)
                "scroll" -> scroll(t, input)
                "execute_js" -> input.script?.let { plain(t, MinesBrowserCore.wrapUserScript(it), 30_000L) } ?: BrowserActionResult.error("execute_js requires 'script'")
                "screenshot" -> screenshot(t)
                "wait_for_dom_stable" -> domStable(t, input.timeoutMs)
                "wait_for_otp" -> waitForOtp(t, input.selector)
                "wait_for_human" -> openForUser(t)   // gecko: the user does it on the live page (payment, approval, a check Mines can't pass)
                else -> BrowserActionResult.error(MinesBrowserCore.unsupported(action).removePrefix("Error: "))
            }
        } catch (e: GeckoUnavailable) { throw e } catch (e: Exception) {
            BrowserActionResult.error(e.message ?: e.javaClass.simpleName)
        }
        if (r.success && input.action in BrowserAction.visualChangeActions && input.action != BrowserAction.HOVER) {
            if (input.action != BrowserAction.SCROLL) r = afterAction(t, r)
            r = snapshot(t, r)
        }
        return r.copy(pageURL = t.url, text = r.text + "\n[Mines 浏览器 · 无痕 · ${t.proxyLabel}]")
    }

    private suspend fun plain(t: Task, js: String, timeoutMs: Long = EVAL_TIMEOUT_MS): BrowserActionResult {
        val raw = evaluate(t, js, timeoutMs)
        val err = runCatching { JSONObject(raw).optString("error") }.getOrNull()
        return if (!err.isNullOrBlank()) BrowserActionResult.error(err) else BrowserActionResult(text = raw)
    }

    private suspend fun navigate(t: Task, url: String?): BrowserActionResult {
        if (url.isNullOrBlank()) return BrowserActionResult.error("navigate requires 'url'")
        val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
        withContext(Dispatchers.Main) { t.pageStop = CompletableDeferred(); t.session.loadUri(u) }
        val stopped = withTimeoutOrNull(NAV_TIMEOUT_MS) { t.pageStop.await() } != null
        withTimeoutOrNull(PORT_WAIT_MS) { t.portWait.await() }
        delay(400)
        val title = t.title.ifBlank { evalSoft(t, "document.title") ?: "" }
        return BrowserActionResult(text = buildString {
            append("Navigated to ").append(t.url.ifBlank { u })
            if (title.isNotBlank()) append("\nTitle: ").append(title)
            if (!stopped) append("\nNote: the page was still loading after ${NAV_TIMEOUT_MS / 1000}s.")
        })
    }

    private suspend fun click(t: Task, input: BrowserActionInput): BrowserActionResult {
        val sel = input.selector
        if (sel == null) {
            val x = input.coordinateX ?: return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
            val y = input.coordinateY ?: return BrowserActionResult.error("click requires 'coordinate_y'")
            val dpr = evalSoft(t, "String(window.devicePixelRatio||1)")?.toFloatOrNull() ?: 1f
            delay(MinesBrowserCore.clickPauseMs())
            tap(t, x * dpr, y * dpr)
            return BrowserActionResult(text = "Tapped ($x, $y) with a native touch")
        }
        val raw = evaluate(t, "__minesRect(${q(sel)})")
        val o = JSONObject(raw)
        if (o.has("error")) return BrowserActionResult.error(o.getString("error"))
        val dpr = o.optDouble("dpr", 1.0).toFloat()
        val w = (o.optDouble("w") * dpr).toFloat(); val h = (o.optDouble("h") * dpr).toFloat()
        val x = (o.optDouble("x") * dpr).toFloat() + MinesBrowserCore.jitter(w)
        val y = (o.optDouble("y") * dpr).toFloat() + MinesBrowserCore.jitter(h)
        delay(MinesBrowserCore.clickPauseMs())
        val native = runCatching { tap(t, x, y) }.isSuccess
        if (!native) return plain(t, BrowserUseJS.click(sel))
        return BrowserActionResult(text = "Clicked <${o.optString("tag")}> ${o.optString("text").take(80)}".trim())
    }

    private suspend fun type(t: Task, input: BrowserActionInput): BrowserActionResult {
        val text = input.text ?: return BrowserActionResult.error("type requires 'text'")
        // {{secret:ID}} / {{user:ID}} become real values only inside the page (same as the WebView backend)
        val real = runCatching { SecretRefs.fill(text) }.getOrDefault(text)
        val delays = MinesBrowserCore.typingDelays(real)
        val js = "__minesType(${input.selector?.let { q(it) } ?: "null"}, ${q(real)}, ${JSONArray(delays)})"
        val r = plain(t, js, delays.sum() + 15_000L)
        return if (real == text) r else runCatching { r.copy(text = SecretRefs.scrub(r.text)) }.getOrDefault(r)
    }

    private suspend fun scroll(t: Task, input: BrowserActionInput): BrowserActionResult {
        val px = (input.amount ?: 500) * (if (input.direction?.value == "up") -1 else 1)
        val steps = MinesBrowserCore.scrollSteps(px)
        return plain(t, "__minesScroll(${input.selector?.let { q(it) } ?: "null"}, ${JSONArray(steps)})", 15_000L)
    }

    private suspend fun screenshot(t: Task): BrowserActionResult {
        val bmp = capture(t) ?: return BrowserActionResult.error("截图失败（页面还没画出来），可以先用 get_text 读内容")
        val (f, bytes) = saveJpeg(bmp, 80)
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        return BrowserActionResult(text = "Screenshot captured (${w}x$h device px)\nURL: ${t.url}\nTitle: ${t.title}",
            base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP), imageFilePath = f.absolutePath)
    }

    private suspend fun snapshot(t: Task, r: BrowserActionResult): BrowserActionResult {
        delay(300)
        val bmp = capture(t) ?: return r
        val (f, _) = saveJpeg(bmp, 70)
        bmp.recycle()
        return r.copy(imageFilePath = f.absolutePath)
    }

    private suspend fun domStable(t: Task, timeoutSec: Int?): BrowserActionResult {
        val until = SystemClock.elapsedRealtime() + ((timeoutSec ?: 10).coerceIn(1, 60) * 1000L)
        var last = -1; var same = 0
        while (SystemClock.elapsedRealtime() < until) {
            val n = evalSoft(t, "String(document.body ? document.body.innerHTML.length : 0)")?.toIntOrNull() ?: -2
            if (n == last) { if (++same >= 3) return BrowserActionResult("DOM is stable") } else { same = 0; last = n }
            delay(500)
        }
        return BrowserActionResult("DOM still changing when the wait ended")
    }

    // ── verification (reuses HumanVerify: SMS / e-mail codes, captcha detection) ──

    private suspend fun scan(t: Task): HumanVerify.Scan = HumanVerify.parseScan(evalSoft(t, HumanVerify.DETECT_JS))

    private suspend fun afterAction(t: Task, r: BrowserActionResult): BrowserActionResult {
        val c = app ?: return r
        HumanVerify.init(c)
        val st = HumanVerify.settings.value
        val s = scan(t)
        var text = r.text
        if (s.kind.isNotBlank()) text += "\n[人机验证] 页面上有「${s.kind}」验证。先等几秒再截图看看；简单的勾选/点击可以自己点；" +
            "要用户本人操作（滑块失败、手机上确认）时调用 wait_for_human（engine gecko），页面会交给用户。"
        if (st.otpAutofill && s.otpField) {
            HumanVerify.armOtp()
            if (s.otpEmpty) {
                val code = HumanVerify.takeCode()
                if (code != null) {
                    if (evalSoft(t, HumanVerify.fillJs(code, null)) == "ok") text += "\n[验证码] 已自动填入刚收到的验证码（${code.length} 位）。"
                    else HumanVerify.putBack(code)
                } else text += "\n[验证码] 点完「发送验证码」后调用 wait_for_otp（engine gecko），Mines 自己从短信/邮件里取码填入，不要让用户手动输入。"
            }
        }
        return r.copy(text = text)
    }

    private suspend fun waitForOtp(t: Task, selector: String?): BrowserActionResult {
        val c = app ?: return BrowserActionResult.error("no app context")
        HumanVerify.init(c)
        if (!HumanVerify.settings.value.otpAutofill) return BrowserActionResult.error("验证码自动填写已在设置里关闭，请让用户把验证码告诉你")
        HumanVerify.expectSite = runCatching { AccountsCore.accountSite(t.url) }.getOrDefault("")
        HumanVerify.armOtp()
        val code = HumanVerify.awaitCode(HumanVerify.WAIT_SLICE_MS)
            ?: return BrowserActionResult("约 50 秒内没收到验证码短信/邮件。确认已经点了发送验证码后，可以再调用一次 wait_for_otp。")
        return if (evalSoft(t, HumanVerify.fillJs(code, selector)) == "ok") {
            snapshot(t, BrowserActionResult("已收到验证码并填入（${code.length} 位，内容不显示）。接下来点击提交/登录按钮。"))
        } else {
            HumanVerify.putBack(code)
            BrowserActionResult("收到了验证码，但页面上找不到验证码输入框。请用 selector 指定输入框再调用 wait_for_otp。")
        }
    }

    // ── hand-over to the user (payments, approvals) ──────────────────────────

    @Volatile var handoverKey: String? = null; private set

    private suspend fun openForUser(t: Task): BrowserActionResult {
        val c = app ?: return BrowserActionResult.error("no app context")
        if (!t.handedToUser) {
            t.handedToUser = true
            handoverKey = t.key
            withContext(Dispatchers.Main) {
                c.startActivity(Intent(c, MinesBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val until = SystemClock.elapsedRealtime() + HumanVerify.WAIT_SLICE_MS
        while (t.handedToUser && SystemClock.elapsedRealtime() < until) delay(1_000)
        return if (t.handedToUser) BrowserActionResult("页面正显示给用户（${t.url}），用户还没交还。再调用一次 wait_for_human 继续等。")
        else snapshot(t, BrowserActionResult("用户已交还页面，可以继续。当前网址：${t.url}"))
    }

    /** called by [MinesBrowserActivity] */
    internal fun sessionForUser(): GeckoSession? {
        val t = tasks[handoverKey ?: return null] ?: return null
        detachOffscreen(t)
        return t.session
    }

    internal fun userDone() {
        val t = tasks[handoverKey ?: return] ?: return
        runCatching { attachOffscreen(t) }
        t.lastUsed = SystemClock.elapsedRealtime()
        t.handedToUser = false
        handoverKey = null
    }

    internal fun handoverUrl(): String = tasks[handoverKey ?: ""]?.url.orEmpty()
}

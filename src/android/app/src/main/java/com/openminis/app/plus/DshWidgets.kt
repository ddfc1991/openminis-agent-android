package com.openminis.app.plus

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.openminis.app.R
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

/**
 * [Mines-dsh-ui] Host for dsh Web UI / widget plugins (e.g. dsh-whale-widget).
 *
 * `socket.mjs --web` loads the installed plugins with a `webServer` service,
 * serves their routes on 127.0.0.1:8791 and renders an index page with the
 * scripts the plugins inject. [PluginWidgetService] shows that page in a small
 * transparent floating window, the phone's equivalent of DSH's Web UI corner.
 */
object DshWebHost {
    private const val TAG = "DshWebHost"
    const val PORT = 8791
    const val URL_INDEX = "http://127.0.0.1:$PORT/"
    @Volatile private var process: Process? = null
    private val lock = Mutex()

    fun home(context: Context) = File(RootfsManager.getInstance(context).rootfsDir, "root/.minis-dsh")

    /** Installed, enabled plugins that inject a Web UI widget (from status.json). */
    fun widgetPlugins(context: Context): List<String> = runCatching {
        val st = JSONObject(File(home(context), "status.json").readText())
        st.keys().asSequence().filter { k -> st.optJSONObject(k)?.let { it.optBoolean("ok") && (it.optBoolean("ui") || it.optString("kind") == "widget") } == true }.toList()
    }.getOrDefault(emptyList())

    /**
     * [Mines-dsh-25] status.json is only trusted when status.meta.json says it
     * was written by this socket version. build-24 devices kept a status from
     * the build-23 socket (no widget support), which is why dsh-whale-widget
     * read 「已加载（只加提示词）」 and had no 显示挂件 button.
     */
    const val STATUS_VERSION = 25
    fun statusFresh(context: Context): Boolean = runCatching {
        JSONObject(File(home(context), "status.meta.json").readText()).optInt("v") >= STATUS_VERSION
    }.getOrDefault(false)

    /** Static hint: does the plugin's code register a Web UI widget? */
    fun looksLikeWidget(context: Context, pkg: String): Boolean = runCatching {
        val dir = File(home(context), "node_modules/$pkg")
        val pj = JSONObject(File(dir, "package.json").readText())
        val main = pj.optString("main").ifBlank { "index.js" }
        val code = File(dir, main).takeIf { it.exists() }?.readText() ?: ""
        val kw = pj.optJSONArray("keywords")?.toString() ?: ""
        code.contains("webserver/index-inject") || code.contains("tapIndex") || kw.contains("widget")
    }.getOrDefault(false)

    /** Last lines of the socket log, to say why the widget host didn't start. */
    fun lastLog(context: Context): String = runCatching {
        File(home(context), "socket.log").readLines().takeLast(3).joinToString("\n")
    }.getOrDefault("")

    fun healthy(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$PORT/__mines/health").openConnection() as HttpURLConnection
        c.connectTimeout = 800; c.readTimeout = 1500
        c.responseCode == 200
    }.getOrDefault(false)

    fun restart() { process?.let { runCatching { it.destroyForcibly() } }; process = null }

    suspend fun ensure(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            var waited = 0
            while (!PRootKernel.isBooted && waited < 60_000) { delay(500); waited += 500 }
            if (!PRootKernel.isBooted) return@withLock false
            if (healthy()) return@withLock true
            start(context.applicationContext)
            var t = 0
            while (!healthy() && t < 30_000) { delay(500); t += 500 }
            healthy()
        }
    }

    private fun start(context: Context) {
        process?.let { if (it.isAlive) return; process = null }
        // [Mines-dsh-25] The exact command the CI self-test starts (`minis-dsh web`).
        val cmd = "MINIS_DSH_HOME=/root/.minis-dsh MINIS_DSH_WEB_PORT=$PORT exec /usr/local/bin/minis-dsh web"
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(cmd)).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        // Keys shared with AI under 账号连接 (DEEPSEEK_API_KEY for the whale's
        // balance, etc.) — the widget reads them through ctx.credentials.
        runCatching { com.openminis.app.sandbox.ExecutionCoordinator.envVarRepository?.allAsDict()?.forEach { (k, v) -> env[k] = v } }
        val p = pb.start()
        process = p
        Thread({ runCatching { p.inputStream.use { s -> val b = ByteArray(4096); while (s.read(b) >= 0) {} } } }, "dsh-web-drain")
            .apply { isDaemon = true }.start()
        Log.i(TAG, "web host started")
    }
}

/**
 * Floating window showing the dsh widget page. Drag the handle to move it,
 * long-press the handle to close. Uses the same overlay permission as the
 * desk pet.
 */
class PluginWidgetService : Service() {
    companion object {
        private const val CHANNEL = "mines_plugin_widget"
        private const val NOTIF_ID = 4412
        const val ACTION_STOP = "com.openminis.mines.plugin_widget.STOP"

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        /** [Mines-dsh-35] app start: bring the widget back if the user had it on before the app was killed. */
        fun resumeIfWanted(context: Context) {
            if (running || !canShow(context)) return
            if (context.getSharedPreferences("mines_dsh", Context.MODE_PRIVATE).getBoolean("widget_on", false)) runCatching { start(context) }
        }

        fun start(context: Context) {
            val i = Intent(context, PluginWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PluginWidgetService::class.java).setAction(ACTION_STOP))
        }

        @Volatile var running = false
            private set
    }

    private var wm: WindowManager? = null
    private var root: View? = null
    private var web: WebView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("mines_dsh", MODE_PRIVATE).edit().putBoolean("widget_on", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        goForeground()
        if (root == null && canShow(this)) addWindow()
        running = true
        getSharedPreferences("mines_dsh", MODE_PRIVATE).edit().putBoolean("widget_on", true).apply()
        watch()
        return START_STICKY
    }

    // [Mines-dsh-29] `minis-dsh off <widget>` must really make the widget go
    // away: it used to only edit plugins.json while this window and the web
    // host kept running. Every 3 s: when no enabled widget plugin is left
    // (status.json from the socket's check, plugins.json enabled flags) the
    // window closes; when the set changed the page reloads.
    private val watchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSet: Set<String>? = null
    private var watching = false
    private var emptyReads = 0
    @Volatile private var lastRestart = 0L
    /** null = can't tell (stale/missing status) → leave the window alone. */
    private fun enabledWidgets(): Set<String>? = runCatching {
        if (!DshWebHost.statusFresh(this)) return@runCatching null
        val home = java.io.File(com.openminis.app.sandbox.RootfsManager.getInstance(this).rootfsDir, "root/.minis-dsh")
        val off = runCatching {
            val arr = org.json.JSONObject(java.io.File(home, "plugins.json").readText()).optJSONArray("plugins") ?: org.json.JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("enabled", true) }.map { it.optString("package") }.toSet()
        }.getOrDefault(emptySet())
        DshWebHost.widgetPlugins(this).filter { it !in off }.toSet()
    }.getOrNull()
    private fun watch() {
        if (watching) return
        watching = true
        val tick = object : Runnable {
            override fun run() {
                if (!running) { watching = false; return }
                Thread {
                    val now = enabledWidgets()
                    // [Mines-dsh-35] supervisor: the widget host is a sandbox process that dies with the app
                    // process / battery saver; bring it back (every 3 s check, restart at most every 20 s)
                    if (!now.isNullOrEmpty() && !DshWebHost.healthy() && System.currentTimeMillis() - lastRestart > 20_000) {
                        lastRestart = System.currentTimeMillis()
                        Log.i("PluginWidget", "web host down → restarting")
                        val ok = runCatching { kotlinx.coroutines.runBlocking { DshWebHost.ensure(applicationContext) } }.getOrDefault(false)
                        if (ok) watchHandler.post { runCatching { web?.reload() } }
                    }
                    watchHandler.post {
                        if (now == null) return@post
                        val prev = lastSet
                        lastSet = now
                        emptyReads = if (now.isEmpty()) emptyReads + 1 else 0
                        if (emptyReads >= 2) { Log.i("PluginWidget", "no enabled widget left → closing"); stopSelf(); return@post }
                        if (prev != null && prev != now) runCatching { web?.reload() }
                    }
                }.start()
                watchHandler.postDelayed(this, 3000)
            }
        }
        watchHandler.postDelayed(tick, 3000)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "插件挂件", NotificationManager.IMPORTANCE_MIN))
        }
        val stop = PendingIntent.getService(this, 2, Intent(this, PluginWidgetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("插件挂件在桌面上")
            .setContentText("拖动顶部小条移动，长按小条关闭")
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "关闭挂件", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    /**
     * [Mines-pet-26] The overlay window is only as big as what the widget
     * actually draws. build-25 used a fixed 300×380 dp window with a
     * transparent WebView: the empty part (and the 「⋯ 挂件」 handle at its
     * top) stayed on screen and swallowed every touch there, which looked like
     * a leftover box after the pet was dragged away. Now the page reports the
     * bounding box of its visible elements once a second; the WebView keeps
     * its layout size but is shifted inside a window cut to that box, and with
     * nothing visible the window turns GONE + NOT_TOUCHABLE.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "JavascriptInterface")
    private fun addWindow() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val d = resources.displayMetrics.density
        val pageW = (300 * d).toInt(); val pageH = (380 * d).toInt()
        val handleH = (18 * d).toInt()
        val lp = WindowManager.LayoutParams(
            (64 * d).toInt(), (64 * d).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - (120 * d).toInt()
            y = (resources.displayMetrics.heightPixels * 0.3f).toInt()
        }
        val frame = FrameLayout(this).apply { clipChildren = true; clipToPadding = true }
        val wv = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
        }
        web = wv
        // The WebView keeps a fixed layout size; only the window around it changes.
        frame.addView(wv, FrameLayout.LayoutParams(pageW, pageH).apply { topMargin = handleH })
        val handle = TextView(this).apply {
            text = "⋯"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 9 * d; setColor(Color.argb(120, 0, 0, 0)) }
            contentDescription = "插件挂件：拖动移动，长按关闭"
        }
        frame.addView(handle, FrameLayout.LayoutParams((36 * d).toInt(), handleH, Gravity.TOP or Gravity.START))
        val main = android.os.Handler(mainLooper)
        var lastBox = ""
        fun applyBox(l: Float, t: Float, r: Float, b: Float) {
            val visible = r > l + 2 && b > t + 2
            if (!visible) { Overlays.setHidden(manager, frame, lp, true); return }
            val pad = (4 * d).toInt()
            val left = ((l * d).toInt() - pad).coerceIn(0, pageW); val top = ((t * d).toInt() - pad).coerceIn(0, pageH)
            val right = ((r * d).toInt() + pad).coerceIn(left + 1, pageW); val bottom = ((b * d).toInt() + pad).coerceIn(top + 1, pageH)
            val key = "$left,$top,$right,$bottom"
            if (frame.visibility != android.view.View.VISIBLE) Overlays.setHidden(manager, frame, lp, false)
            if (key == lastBox) return
            // keep the visible content where it is on screen while the window is re-cut
            val oldLeft = lastBox.split(',').getOrNull(0)?.toIntOrNull() ?: left
            val oldTop = lastBox.split(',').getOrNull(1)?.toIntOrNull() ?: top
            lastBox = key
            wv.translationX = -left.toFloat(); wv.translationY = -top.toFloat()
            lp.width = max(right - left, (36 * d).toInt()); lp.height = bottom - top + handleH
            lp.x += left - oldLeft; lp.y += top - oldTop
            Overlays.update(manager, frame, lp)
        }
        wv.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun bounds(l: Float, t: Float, r: Float, b: Float) { main.post { applyBox(l, t, r, b) } }
        }, "MinesWidget")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(MEASURE_JS, null) }
        }
        wv.loadUrl(DshWebHost.URL_INDEX)
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var downAt = 0L; var moved = false
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; sx = lp.x; sy = lp.y; downAt = System.currentTimeMillis(); moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (moved || abs(dx) > 10 * d || abs(dy) > 10 * d) {
                        moved = true; lp.x = (sx + dx).toInt(); lp.y = (sy + dy).toInt()
                        Overlays.update(manager, frame, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved && System.currentTimeMillis() - downAt > 600) stopSelf()
            }
            true
        }
        root = frame
        // Hidden (GONE + NOT_TOUCHABLE) until the page reports something visible.
        frame.visibility = android.view.View.GONE
        if (!Overlays.add(manager, "dsh-widget", frame, lp)) { root = null; stopSelf() }
    }

    override fun onDestroy() {
        running = false
        watchHandler.removeCallbacksAndMessages(null)
        runCatching { web?.destroy() }; web = null
        root?.let { v -> Overlays.remove(wm, v) }; root = null
        Overlays.removeAll("dsh-widget")
        super.onDestroy()
    }

}

/** Union of the visible elements' boxes (CSS px), reported once a second. */
private const val MEASURE_JS = """(function(){if(window.__minesMeasure)return;window.__minesMeasure=1;
function m(){var L=1e9,T=1e9,R=-1,B=-1,W=innerWidth,H=innerHeight;var a=document.body?document.body.getElementsByTagName('*'):[];
for(var i=0;i<a.length;i++){var e=a[i];var tg=e.tagName;if(tg=='SCRIPT'||tg=='STYLE'||tg=='TEXTAREA'&&e.id=='mines-composer')continue;
var cs=getComputedStyle(e);if(cs.display=='none'||cs.visibility=='hidden'||parseFloat(cs.opacity)<0.05)continue;
var leaf=!e.children.length||tg=='svg'||tg=='CANVAS'||tg=='IMG'||tg=='VIDEO'||tg=='BUTTON';
var bg=cs.backgroundColor&&cs.backgroundColor!='rgba(0, 0, 0, 0)'&&cs.backgroundColor!='transparent'||cs.backgroundImage!='none';
if(!leaf&&!bg)continue;var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;
if(r.right<=0||r.bottom<=0||r.left>=W||r.top>=H)continue;
L=Math.min(L,Math.max(0,r.left));T=Math.min(T,Math.max(0,r.top));R=Math.max(R,Math.min(W,r.right));B=Math.max(B,Math.min(H,r.bottom));}
try{MinesWidget.bounds(L,T,R,B)}catch(x){}}
m();setInterval(m,1000);})();"""

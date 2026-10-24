package com.openminis.app.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * [Mines-38.9.11.2] 无痕 browser for phones whose WebView has no profile support: runs in the ":incog" process
 * ([IncognitoProcess] gave it its own WebView data dir, wiped at process start). Plain views (no Compose / app theme state:
 * nothing of the main app is initialised in this process). Top bar: 「无痕」 indicator · address · ⋮ (保存到密码库 / 刷新) ·
 * 「清除并关闭」. Closing wipes cookies, storage, cache, history and form data, destroys the WebView, deletes the data dir
 * and ends the process, so the next 无痕 session starts from nothing.
 */
class IncognitoActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PROXY = "mines_proxy_rule"   // [38.9.11.3]
        private const val TAG = "MinesIncog"
    }

    private var web: WebView? = null
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private var closing = false

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IncognitoProcess.inProcess) { finish(); return }   // never in the main process (would share its WebView data)
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { setRecentsScreenshotEnabled(false) }   // no page thumbnail in 最近任务
        val bg = if (night) Color.rgb(28, 28, 30) else Color.rgb(246, 246, 248)
        val fg = if (night) Color.rgb(235, 235, 240) else Color.rgb(28, 28, 30)
        val field = if (night) Color.rgb(44, 44, 48) else Color.WHITE
        val accent = if (night) Color.rgb(255, 159, 10) else Color.rgb(196, 90, 0)
        window.statusBarColor = bg
        if (!night && android.os.Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); fitsSystemWindows = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }
        // the indicator: normal text size, high contrast, never large type
        val chip = TextView(this).apply {
            text = IncognitoCore.INDICATOR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(Color.rgb(60, 60, 67)) }
            setPadding(dp(8f).toInt(), dp(3f).toInt(), dp(8f).toInt(), dp(3f).toInt())
            contentDescription = "无痕模式"
        }
        address = EditText(this).apply {
            setSingleLine(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(fg); setHintTextColor(Color.GRAY)
            hint = "输入网址或搜索"; imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(field) }
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO   // the address is not saved by any autofill service
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val more = TextView(this).apply {
            text = "⋮"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(fg); gravity = Gravity.CENTER
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt()); contentDescription = "更多"
            setOnClickListener { v ->
                PopupMenu(this@IncognitoActivity, v).apply {
                    menu.add(0, 1, 0, IncognitoCore.SAVE_ACTION); menu.add(0, 2, 1, "刷新")
                    setOnMenuItemClickListener { when (it.itemId) { 1 -> saveLogin(); 2 -> web?.reload() }; true }
                }.show()
            }
        }
        val clear = TextView(this).apply {
            text = IncognitoCore.CLEAR_ACTION; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(accent); gravity = Gravity.CENTER
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            setOnClickListener { clearAndClose() }
        }
        bar.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(address, LinearLayout.LayoutParams(0, dp(36f).toInt(), 1f).apply { marginStart = dp(8f).toInt() })
        bar.addView(more, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }

        IncognitoProcess.webViewStarted = true
        // [38.9.11.3] this process's own proxy override: the passing proxy, or a closed port (strict) — never direct
        intent?.getStringExtra(EXTRA_PROXY)?.let { rule -> runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE))
                androidx.webkit.ProxyController.getInstance().setProxyOverride(androidx.webkit.ProxyConfig.Builder().addProxyRule(rule)
                    .addBypassRule("localhost").addBypassRule("127.0.0.1").build(), { it.run() }) {}
        } }
        val wv = WebView(this)
        web = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true   // pages need it; it lives in this process's own data dir and is wiped on close
            @Suppress("DEPRECATION") databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
            userAgentString = WebUaCore.chromeLike(userAgentString)
        }
        Incognito.harden(wv, null)
        CookieManager.getInstance().setAcceptCookie(true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme?.lowercase()
                return !(s == "http" || s == "https" || s == "about")   // no intent:// / app links out of 无痕
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!address.hasFocus()) address.setText(url.orEmpty()); progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                if (!address.hasFocus()) address.setText(url.orEmpty())
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { progress.progress = p }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)   // denied, not remembered
            }
            override fun onPermissionRequest(request: PermissionRequest?) { runCatching { request?.deny() } }
        }
        wv.setDownloadListener { _, _, _, _, _ -> Toast.makeText(this, "无痕标签不下载文件", Toast.LENGTH_SHORT).show() }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f).toInt()))
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        android.util.Log.i(TAG, "opened (process path)")
        open(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); open(intent) }

    private fun open(i: Intent?) {
        val u = i?.getStringExtra(EXTRA_URL)
        if (!u.isNullOrBlank()) go(u) else if (web?.url == null) web?.loadUrl("about:blank")
    }

    private fun go(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") || t == "about:blank" -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        address.clearFocus(); web?.loadUrl(url)
    }

    /** the user's tap: the filled-in form goes to the main process's 密码库 (never logged) */
    private fun saveLogin() {
        val wv = web ?: return
        wv.evaluateJavascript(IncognitoCore.SAVE_JS) { raw ->
            val r = IncognitoCore.parseSaveResult(raw)
            if (r == null) { Toast.makeText(this, IncognitoCore.NOTHING_TO_SAVE_MSG, Toast.LENGTH_SHORT).show(); return@evaluateJavascript }
            sendBroadcast(Intent(this, IncognitoReceiver::class.java).setAction(IncognitoReceiver.ACTION_SAVE)
                .putExtra("url", r.first).putExtra("user", r.second).putExtra("pass", r.third))
            Toast.makeText(this, IncognitoCore.SAVED_MSG, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity back")
    override fun onBackPressed() {
        val wv = web
        if (wv != null && wv.canGoBack()) wv.goBack() else clearAndClose()
    }

    override fun onPause() { super.onPause(); web?.onPause() }
    override fun onResume() { super.onResume(); web?.onResume(); runCatching { web?.resumeTimers() } }

    /** 「清除并关闭」 / back on the first page / the task removed */
    private fun clearAndClose() {
        if (closing) return
        closing = true
        wipe()
        Toast.makeText(applicationContext, IncognitoCore.CLOSED_MSG, Toast.LENGTH_SHORT).show()
        runCatching { sendBroadcast(Intent(this, IncognitoReceiver::class.java).setAction(IncognitoReceiver.ACTION_CLOSED)) }
        finishAndRemoveTask()
        // end the process: its WebView state (renderer, in-memory cache, cookies) dies with it; the data dir is gone
        android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    private fun wipe() {
        val wv = web ?: return
        web = null
        runCatching {
            wv.stopLoading(); wv.loadUrl("about:blank")
            wv.clearHistory(); wv.clearCache(true); wv.clearFormData(); wv.clearSslPreferences(); wv.clearMatches()
        }
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { wv.onPause(); wv.pauseTimers() }
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv); wv.removeAllViews(); wv.destroy() }
        val n = IncognitoProcess.wipeDirs(this)
        android.util.Log.i(TAG, "wiped (dirs=$n)")
    }

    override fun onDestroy() {
        if (!closing && web != null) {
            closing = true; wipe()
            runCatching { sendBroadcast(Intent(this, IncognitoReceiver::class.java).setAction(IncognitoReceiver.ACTION_CLOSED)) }
            super.onDestroy()
            android.os.Handler(mainLooper).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
            return
        }
        super.onDestroy()
    }
}

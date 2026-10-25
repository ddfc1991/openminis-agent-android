package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openminis.app.plus.RegisterAutoCore.Item
import com.openminis.app.plus.RegisterAutoCore.Page
import com.openminis.app.plus.RegisterAutoCore.Platform
import com.openminis.app.plus.RegisterAutoCore.Run
import com.openminis.app.plus.RegisterAutoCore.St
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [Mines-38.9.15 register_auto] 一键注册 that runs by itself: the user ticks platforms (+ 自定义网址) once, then for each one,
 * one after another: a new Mail.tm box → a strong password (saved to the 台账 / vault first, 备注 注册中) → the sign-up page in
 * Mines' persistent in-app browser (app cookie jar, kept between runs) → fill + submit → the code or link from the box →
 * 台账 备注 「注册于 … · Mail.tm」 (已验证) or 「注册中 … · why」 (未完成). Captcha / phone number / payment stop the item and
 * ask the user with one short line (+ 来电 when it is on); the user finishes that step on the same page
 * ([RegisterTakeover] screen) and taps 继续. Progress is one task (ChatHome 任务 tab + the 注册台账 card). See [RegisterAutoCore].
 */
object RegisterAuto {
    const val SID_PREFIX = "regauto:"
    private const val PREFS = "mines_register_auto"

    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _run = MutableStateFlow<Run?>(null)
    val run: StateFlow<Run?> = _run.asStateFlow()
    @Volatile private var job: Job? = null
    /** the user tapped 继续 / 跳过 on a stop-and-ask */
    @Volatile private var answer: CompletableDeferred<Boolean>? = null
    /** Mail.tm API base (smoke points it at the runner's fake Mail.tm) */
    @Volatile var mailBase: String = "https://api.mail.tm"
    /** smoke / 自检: what each step did */
    val log = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun note(s: String) { log.add(s); if (log.size > 200) log.poll(); com.openminis.app.logging.AppLogger.info("RegisterAuto", s) }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val rnd = java.security.SecureRandom()

    fun init(c: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            app = c.applicationContext
            RegLedger.init(c)
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("mail_base", null)?.let { mailBase = it }
            RegisterAutoCore.fromJson(p.getString("run", null))?.let { _run.value = it }
        }
    }

    fun running(): Boolean = job?.isActive == true

    // ── start ──

    /** one tap: start registering [list] (already picked + validated). false = a run is still going. */
    fun start(c: Context, list: List<Platform>): Boolean {
        init(c)
        if (running() || list.isEmpty()) return false
        val now = System.currentTimeMillis()
        val r = Run("r$now", list.map { Item(it) }, startedAt = now)
        publish(r)
        job = scope.launch {
            for (i in r.items.indices) {
                runCatching { one(i) }.onFailure { e ->
                    note("item $i crashed ${e.javaClass.simpleName}: ${e.message}")
                    finishItem(i, false, "出错了：" + (e.message ?: e.javaClass.simpleName).take(40))
                }
            }
            withContext(Dispatchers.Main) { RegisterBrowser.close() }
            _run.value?.let { publish(it.copy(endedAt = System.currentTimeMillis())) }
        }
        return true
    }

    /** the user finished the asked step (true) or wants this platform skipped (false) */
    fun answer(go: Boolean) { answer?.complete(go) }
    fun waitingItem(): Item? = _run.value?.items?.firstOrNull { it.st == St.WAITING }

    // ── one platform ──

    private suspend fun one(i: Int) {
        val c = app ?: return
        val item = _run.value?.items?.getOrNull(i) ?: return
        val p = item.p
        val host = p.host
        setItem(i) { it.copy(st = St.RUNNING, line = "建临时邮箱") }
        // 1. a Mail.tm box of its own (一人一号). A box made for this host by an earlier, unfinished run is reused.
        val box = reuseBox(c, host) ?: newBox() ?: run { finishItem(i, false, "临时邮箱没建成（Mail.tm）"); return }
        note("box ${box.addr} for $host")
        // 2. password first into the 台账 (vault), 备注 注册中
        val pw = SavedLogins.find(c, host, box.addr)?.pass?.takeIf { it.isNotBlank() } ?: RegisterCore.password(rnd)
        SavedLogins.put(c, SavedLogins.Entry(p.name, origin(p.url), box.addr, pw))
        Ledger.setNote(c, host, box.addr, RegisterAutoCore.ledgerNote(today(), false, "Mail.tm"))
        setItem(i) { it.copy(email = box.addr, line = "打开注册页") }
        // 临时邮箱台账: box (password / token in the vault), platform, sign-up URL, 进行中, time
        RegLedger.put(RegLedgerCore.Rec(box.addr, host, p.name, p.url, st = RegLedgerCore.RSt.RUNNING, createdAt = System.currentTimeMillis()))
        // 3. the sign-up page in the persistent in-app browser
        val since = System.currentTimeMillis() - 60_000
        if (!RegisterBrowser.load(c, p.url)) { finishItem(i, false, "注册页打不开"); return }
        var fills = 0; var verified = false; var mailWaited = false; var lastUrl = ""
        for (round in 0 until 12) {
            delay(1200)
            val s = RegisterBrowser.scan()
            val page = RegisterAutoCore.classify(s)
            note("$host round $round page $page url ${s.url.take(80)}")
            if (s.url != lastUrl) { lastUrl = s.url }
            // [ledger v2] an IP-only custom site is named by its page (「SmokeCloud sign up」 → SmokeCloud), never by the IP
            if (round == 0 && RegLedgerCore.isIpHost(host) || round == 0 && p.name == "自定义网站") {
                RegLedgerCore.brandFromTitle(RegisterBrowser.eval("document.title"))?.let { b ->
                    note("$host brand $b")
                    setItem(i) { it.copy(p = it.p.copy(name = b)) }
                    SavedLogins.put(c, SavedLogins.Entry(b, origin(p.url), box.addr, pw))
                    RegLedger.put(RegLedgerCore.Rec(box.addr, host, b, p.url, st = RegLedgerCore.RSt.RUNNING))
                }
            }
            when (page) {
                Page.CAPTCHA, Page.PHONE, Page.PAYMENT -> {
                    if (!ask(i, page, s.captcha)) { finishItem(i, false, when (page) { Page.CAPTCHA -> "人机验证没完成"; Page.PHONE -> "要手机号，跳过了"; else -> "要付款，跳过了" }); return }
                    setItem(i) { it.copy(st = St.RUNNING, ask = null, line = "接着注册") }
                }
                Page.FORM -> {
                    if (verified) { finishItem(i, true); return }   // a sign-in page after the link = done
                    if (fills >= 2) { finishItem(i, false, s.error.ifBlank { "表单提交后没有往下走" }); return }
                    setItem(i) { it.copy(line = "填表") }
                    val r = RegisterBrowser.eval(RegisterAutoCore.fillJs(box.addr, pw, RegisterAutoCore.userFor(box.addr))).orEmpty()
                    note("$host fill $r")
                    if (!r.startsWith("filled")) { finishItem(i, false, "认不出这个注册表单"); return }
                    fills++
                    RegisterBrowser.settle()
                }
                Page.CODE -> {
                    setItem(i) { it.copy(line = "等邮件验证码") }
                    val mail = waitMail(box, since) ?: run { finishItem(i, false, "3 分钟没收到验证码邮件"); return }
                    val code = MailParse.extractCode(mail)
                    if (code == null) {
                        val link = RegisterAutoCore.pickLink(mail, host) ?: run { finishItem(i, false, "邮件里没找到验证码"); return }
                        RegisterBrowser.load(c, link); verified = true
                    } else {
                        val r = RegisterBrowser.eval(HumanVerify.fillJs(code, null))
                        note("$host code fill $r")
                        if (r != "ok") { finishItem(i, false, "验证码填不进去"); return }
                        delay(400); RegisterBrowser.eval(RegisterAutoCore.SUBMIT_CODE_JS); RegisterBrowser.settle(); verified = true
                    }
                }
                Page.CHECK_MAIL, Page.UNKNOWN -> {
                    if (page == Page.UNKNOWN && fills == 0) { finishItem(i, false, "认不出这个注册页"); return }
                    if (mailWaited) { if (verified) { finishItem(i, true); return }; finishItem(i, false, "邮件链接打开后没有确认"); return }
                    setItem(i) { it.copy(line = "等验证邮件") }
                    val mail = waitMail(box, since, if (page == Page.UNKNOWN) 60_000L else RegisterAutoCore.MAIL_WAIT_MS)
                    mailWaited = true
                    if (mail == null) { finishItem(i, false, if (page == Page.UNKNOWN) "提交后没认出下一步" else "3 分钟没收到验证邮件"); return }
                    val link = RegisterAutoCore.pickLink(mail, host)
                    if (link != null) { note("$host link ${link.take(80)}"); RegisterBrowser.load(c, link); verified = true }
                    else if (MailParse.extractCode(mail) == null) { finishItem(i, false, "邮件里没有验证链接"); return }
                    else mailWaited = false   // a code: the next round sees the code box
                }
                Page.DONE -> { finishItem(i, true); return }
            }
        }
        finishItem(i, verified, if (verified) "" else "步骤太多，停下了")
    }

    /** stop-and-ask: one short line on the item, 来电 when on, then wait for 继续 / 跳过 (≤ 15 min) */
    private suspend fun ask(i: Int, page: Page, detail: String): Boolean {
        val c = app ?: return false
        val item = _run.value?.items?.getOrNull(i) ?: return false
        val line = RegisterAutoCore.askLine(item.p.name, page, detail)
        val d = CompletableDeferred<Boolean>(); answer = d
        setItem(i) { it.copy(st = St.WAITING, ask = page, line = line) }
        if (item.email.isNotBlank()) RegLedger.put(RegLedgerCore.Rec(item.email, item.p.host, item.p.name, item.p.url, st = stuck(page), why = line))
        note("ask ${item.p.host} $page")
        runCatching { MinesCall.onNeedsAttention(c, "一键注册 · 等你", line) }
        if (!ChatHome.enabled()) runCatching { notifyAsk(c, line) }
        val go = withTimeoutOrNull(RegisterAutoCore.ASK_WAIT_MS) { d.await() } ?: false
        answer = null
        return go
    }

    private fun finishItem(i: Int, ok: Boolean, why: String = "") {
        val c = app ?: return
        val item = _run.value?.items?.getOrNull(i) ?: return
        if (item.email.isNotBlank()) {
            Ledger.setNote(c, item.p.host, item.email, RegisterAutoCore.ledgerNote(today(), ok, why))
            if (ok) Ledger.markOk(c, item.p.host, item.email)
        }
        if (item.email.isNotBlank()) RegLedger.put(RegLedgerCore.Rec(item.email, item.p.host, item.p.name, item.p.url,
            st = if (ok) RegLedgerCore.RSt.VERIFIED else item.ask?.let { stuck(it) } ?: RegLedgerCore.RSt.PENDING, why = if (ok) "" else why))
        note("done ${item.p.host} ok=$ok $why")
        setItem(i) { it.copy(st = if (ok) St.DONE else St.PENDING, ask = null, line = if (ok) "已验证 · ${item.email}" else why) }
    }

    private fun stuck(p: Page): RegLedgerCore.RSt = when (p) {
        Page.CAPTCHA -> RegLedgerCore.RSt.CAPTCHA; Page.PHONE -> RegLedgerCore.RSt.PHONE; Page.PAYMENT -> RegLedgerCore.RSt.PAYMENT; else -> RegLedgerCore.RSt.PENDING
    }

    // ── state → 台账 card + ChatHome task ──

    private fun setItem(i: Int, f: (Item) -> Item) {
        val r = _run.value ?: return
        publish(r.copy(items = r.items.mapIndexed { k, it -> if (k == i) f(it) else it }))
    }

    private fun publish(r: Run) {
        _run.value = r
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("run", RegisterAutoCore.toJson(r))?.apply()
        runCatching {
            val title = "一键注册（${r.items.size} 个平台）"
            ChatHome.putExternal(SID_PREFIX + r.id, "rt_" + r.id, title, r.items.joinToString("、") { it.p.name },
                RegisterAutoCore.chatSt(r), r.items.count { it.st == St.DONE || it.st == St.PENDING }, r.items.size,
                RegisterAutoCore.runLine(r), r.startedAt)
        }
    }

    private fun notifyAsk(c: Context, line: String) {
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        val ch = "mines_register_auto"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val sys = c.getSystemService(android.app.NotificationManager::class.java)
            if (sys?.getNotificationChannel(ch) == null) sys?.createNotificationChannel(android.app.NotificationChannel(ch, "一键注册", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return)
            .putExtra("mines_route", com.openminis.app.ui.plus.RegisterTakeover.ROUTE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(c, 7301, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(c, ch).setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle("一键注册 · 等你").setContentText(line).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(7301, n) } catch (_: SecurityException) {}
    }

    // ── Mail.tm: one box per platform ──

    data class Box(val addr: String, val pass: String)

    private fun req(method: String, path: String, body: JSONObject? = null, bearer: String? = null): Pair<Int, String> = runCatching {
        val b = Request.Builder().url(mailBase.trimEnd('/') + path).header("Accept", "application/json")
        bearer?.let { b.header("Authorization", "Bearer $it") }
        if (body != null) b.method(method, body.toString().toRequestBody("application/json".toMediaType())) else b.get()
        http.newCall(b.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
    }.getOrElse { -1 to (it.message ?: "") }

    private suspend fun newBox(): Box? = withContext(Dispatchers.IO) {
        val (c0, d) = req("GET", "/domains")
        if (c0 !in 200..299) { note("mailtm domains $c0"); return@withContext null }
        val dom = AiMailCore.mailTmDomain(d) ?: return@withContext null
        val pass = java.util.UUID.randomUUID().toString()
        repeat(2) {
            val addr = AiMailCore.userName(System.currentTimeMillis(), rnd.nextInt(900)) + "@" + dom
            val (c1, _) = req("POST", "/accounts", JSONObject().put("address", addr).put("password", pass))
            if (c1 in 200..299) {
                RegLedger.setBoxPass(addr, pass)
                return@withContext Box(addr, pass)
            }
            note("mailtm account $c1")
            if (c1 != 422) return@withContext null
            delay(1100)
        }
        null
    }

    /** an earlier box for this host whose sign-up never finished (备注 注册中 · Mail.tm), password still in the vault */
    private fun reuseBox(c: Context, host: String): Box? = runCatching {
        Ledger.cands(c, host).firstOrNull { it.note.startsWith("注册中") && it.note.contains("Mail.tm") }?.let { cand ->
            RegLedger.boxPass(cand.account)?.let { Box(cand.account, it) }
        }
    }.getOrNull()

    private fun token(b: Box): String? {
        val (c, t) = req("POST", "/token", JSONObject().put("address", b.addr).put("password", b.pass))
        return if (c in 200..299) runCatching { JSONObject(t).optString("token") }.getOrNull()?.ifBlank { null } else null
    }

    /** poll the box (every 4 s) for the first mail since [since]; returns subject + text + links */
    private suspend fun waitMail(b: Box, since: Long, maxMs: Long = RegisterAutoCore.MAIL_WAIT_MS): String? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        var tok: String? = null
        while (System.currentTimeMillis() - t0 < maxMs) {
            if (tok == null) tok = token(b)
            if (tok != null) {
                val (c, body) = req("GET", "/messages?page=1", bearer = tok)
                if (c == 401) tok = null
                val m = if (c in 200..299) RegisterAutoCore.parseMessages(body).firstOrNull { it.at == 0L || it.at >= since } else null
                if (m != null) {
                    val (c2, full) = req("GET", "/messages/" + m.id, bearer = tok)
                    note("mail ${m.subject.take(40)}")
                    return@withContext if (c2 in 200..299) RegisterAutoCore.parseMessage(full) else m.subject + "\n" + m.text
                }
            }
            delay(4_000)
        }
        null
    }

    private fun origin(url: String): String = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') + "/"
    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ── smoke / 自检 ──

    fun setMailBase(c: Context, base: String?) {
        init(c)
        mailBase = base?.ifBlank { null } ?: "https://api.mail.tm"
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mail_base", base?.ifBlank { null }).apply()
    }

    fun stateJson(c: Context): String {
        val r = _run.value
        return JSONObject().put("running", running()).put("mailBase", mailBase).put("run", r?.let { JSONObject(RegisterAutoCore.toJson(it)) })
            .put("items", org.json.JSONArray().apply { r?.items?.forEach { put(JSONObject().put("name", it.p.name).put("host", it.p.host).put("st", it.st.name)
                .put("title", RegisterAutoCore.itemTitle(it)).put("line", it.line).put("email", it.email).put("ask", it.ask?.name ?: "")) } })
            .put("ledger", org.json.JSONArray().apply { r?.items?.forEach { i -> if (i.email.isNotBlank()) runCatching {
                val cand = Ledger.cands(c, i.p.host).firstOrNull { it.account.equals(i.email, true) }
                put(JSONObject().put("host", i.p.host).put("account", i.email).put("note", cand?.note ?: "")
                    .put("pw", SavedLogins.find(c, i.p.host, i.email)?.pass?.isNotBlank() == true)
                    .put("st", Inbox3912Core.regStatus(cand?.note.orEmpty()).zh))
            } } })
            .put("log", org.json.JSONArray(log.toList().takeLast(40))).toString()
    }
}

/**
 * The persistent in-app browser the runner drives: one WebView on the app cookie jar ([LoginWeb.configure]) so sign-ins
 * stay after the run, off-screen while Mines works, shown on [com.openminis.app.ui.plus.RegisterTakeover] when the user
 * has to do a step (the same page, the form already filled).
 */
@SuppressLint("StaticFieldLeak")
object RegisterBrowser {
    @Volatile var web: WebView? = null; private set
    private var ctx: MutableContextWrapper? = null
    @Volatile private var loaded = CompletableDeferred<Unit>()

    private suspend fun ensure(c: Context): WebView = withContext(Dispatchers.Main) {
        web?.let { return@withContext it }
        val w = MutableContextWrapper(c.applicationContext).also { ctx = it }
        val wv = WebView(w)
        LoginWeb.configure(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
        }
        // off-screen it still needs a size for the page's layout (a phone screen)
        val dm = c.resources.displayMetrics
        wv.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY))
        wv.layout(0, 0, dm.widthPixels, dm.heightPixels)
        web = wv
        wv
    }

    /** the takeover screen shows the WebView: its context becomes the Activity (popups, pickers) */
    fun attachTo(activity: Context): WebView? { ctx?.baseContext = activity; return web }
    fun detach() {
        val w = web ?: return
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        app()?.let { ctx?.baseContext = it }
    }
    private fun app(): Context? = ctx?.baseContext?.applicationContext

    suspend fun load(c: Context, url: String): Boolean {
        val wv = ensure(c)
        loaded = CompletableDeferred()
        withContext(Dispatchers.Main) { wv.loadUrl(url) }
        val ok = withTimeoutOrNull(30_000L) { loaded.await() } != null
        delay(900)   // scripts that build the form after load
        return ok || !withContext(Dispatchers.Main) { wv.url }.isNullOrBlank()
    }

    /** wait for a page change after a click (load or in-page update), ≤ 8 s */
    suspend fun settle() {
        loaded = CompletableDeferred()
        withTimeoutOrNull(6_000L) { loaded.await() }
        delay(900)
    }

    suspend fun eval(js: String): String? {
        val wv = web ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { k ->
                runCatching {
                    wv.evaluateJavascript(js) { r ->
                        val v = runCatching { org.json.JSONTokener(r ?: "null").nextValue() as? String }.getOrNull()
                        if (k.isActive) k.resume(v?.removePrefix("R:"))
                    }
                }.onFailure { if (k.isActive) k.resume(null) }
            }
        }
    }

    suspend fun scan(): RegisterAutoCore.Scan {
        val captcha = HumanVerify.parseScan(eval(HumanVerify.DETECT_JS)).kind
        val raw = eval(RegisterAutoCore.SCAN_JS)
        return RegisterAutoCore.parseScan(raw, captcha)
    }

    fun url(): String = runCatching { web?.url }.getOrNull().orEmpty()

    fun close() {
        val w = web ?: return
        detach()
        LoginWeb.flush()
        runCatching { w.stopLoading(); w.destroy() }
        web = null; ctx = null
    }
}

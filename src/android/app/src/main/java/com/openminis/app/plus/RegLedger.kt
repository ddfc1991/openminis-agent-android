package com.openminis.app.plus

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openminis.app.plus.RegLedgerCore.Ka
import com.openminis.app.plus.RegLedgerCore.RSt
import com.openminis.app.plus.RegLedgerCore.Rec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.15 register_auto] Android side of [RegLedgerCore]: the 临时邮箱台账 records (prefs JSON; box passwords and
 * tokens in the vault, so the box can be read again any time), 重新收码, the 自动延时 / 探针保活 switches and their
 * WorkManager job ([RegKeepAliveWorker], every 2 h, only on a network and not on low battery, only while a switch is on),
 * and 整理临时邮箱账号 (once after the upgrade, or from the 台账 menu) with 最近删除 (7 days).
 */
object RegLedger {
    private const val PREFS = "mines_reg_ledger"
    private const val V = "agent_mail"          // vault kind (same as AgentMailbox): regbox_<addr> = box password, regtok_<addr> = token
    private const val WORK = "mines_reg_keepalive"
    @Volatile private var app: Context? = null
    private val _recs = MutableStateFlow<List<Rec>>(emptyList()); val recs: StateFlow<List<Rec>> = _recs.asStateFlow()
    private val _ka = MutableStateFlow<Map<String, Ka>>(emptyMap()); val ka: StateFlow<Map<String, Ka>> = _ka.asStateFlow()
    private val _trash = MutableStateFlow<List<RegLedgerCore.Trashed>>(emptyList()); val trash: StateFlow<List<RegLedgerCore.Trashed>> = _trash.asStateFlow()
    /** the last 整理 summary (shown once as one line on the 台账) */
    val lastTidy = MutableStateFlow<RegLedgerCore.Summary?>(null)
    private val tidyLock = Mutex()
    private val kaLock = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    val log = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun note(s: String) { log.add(s); if (log.size > 120) log.poll(); com.openminis.app.logging.AppLogger.info("RegLedger", s) }

    private fun p(): android.content.SharedPreferences? = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            app = c.applicationContext
            val now = System.currentTimeMillis()
            _recs.value = RegLedgerCore.afterRestart(RegLedgerCore.recsOf(p()?.getString("recs", null)), now)
            _ka.value = RegLedgerCore.kaOf(p()?.getString("ka", null)).associateBy { it.key }
            _trash.value = RegLedgerCore.purge(RegLedgerCore.trashOf(p()?.getString("trash", null)), now)
            saveAll()
        }
    }

    private fun saveAll() {
        p()?.edit()?.putString("recs", RegLedgerCore.recsJson(_recs.value))?.putString("ka", RegLedgerCore.kaJson(_ka.value.values))
            ?.putString("trash", RegLedgerCore.trashJson(_trash.value))?.apply()
    }

    // ── records ──

    fun boxPass(addr: String): String? = PlusVault.secretExtra(V, "regbox_${addr.lowercase()}") ?: PlusVault.secretExtra(V, "regbox_$addr")
        ?: if (AgentMailbox.address("mailtm").equals(addr, true)) PlusVault.secretExtra(V, "mailtm_pass") else null
    fun setBoxPass(addr: String, pass: String) = PlusVault.setSecretExtra(V, "regbox_${addr.lowercase()}", pass)

    @Synchronized fun put(r: Rec) {
        _recs.value = RegLedgerCore.upsert(_recs.value, r.copy(updatedAt = System.currentTimeMillis()))
        saveAll()
    }
    fun recsFor(box: String): List<Rec> = _recs.value.filter { it.box.equals(box, true) }
    /** every box that is temporary (records, vault passwords, the old Mail.tm box) */
    fun knownBoxes(): Set<String> = (_recs.value.map { it.box.lowercase() } + listOfNotNull(AgentMailbox.address("mailtm")?.lowercase())).toSet()

    // ── Mail.tm reads (重新收码 / 整理 / 保活) ──

    private fun req(method: String, path: String, body: JSONObject? = null, bearer: String? = null): Pair<Int, String> = runCatching {
        val b = Request.Builder().url(RegisterAuto.mailBase.trimEnd('/') + path).header("Accept", "application/json")
        bearer?.let { b.header("Authorization", "Bearer $it") }
        if (body != null) b.method(method, body.toString().toRequestBody("application/json".toMediaType())) else b.get()
        http.newCall(b.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
    }.getOrElse { -1 to (it.message ?: "") }

    /** token for [addr]: the saved one if it still works, else a new login with the vault password (saved back) */
    fun token(addr: String): String? {
        PlusVault.secretExtra(V, "regtok_${addr.lowercase()}")?.let { t -> if (req("GET", "/me", bearer = t).first in 200..299) return t }
        val pass = boxPass(addr) ?: return null
        val (c, b) = req("POST", "/token", JSONObject().put("address", addr).put("password", pass))
        val t = if (c in 200..299) runCatching { JSONObject(b).optString("token") }.getOrNull()?.ifBlank { null } else null
        if (t != null) PlusVault.setSecretExtra(V, "regtok_${addr.lowercase()}", t)
        return t
    }

    /** the box's mail (newest first, full text + links); null = the box can not be signed in to any more */
    suspend fun read(addr: String, max: Int = 10): List<RegLedgerCore.Mail>? = withContext(Dispatchers.IO) {
        val t = token(addr) ?: return@withContext null
        val (c, body) = req("GET", "/messages?page=1", bearer = t)
        if (c !in 200..299) return@withContext null
        RegisterAutoCore.parseMessages(body).take(max).map { m ->
            val (c2, full) = req("GET", "/messages/" + m.id, bearer = t)
            RegLedgerCore.Mail(m.from, m.subject, if (c2 in 200..299) RegisterAutoCore.parseMessage(full) else m.subject + "\n" + m.text, m.at)
        }
    }

    data class Code(val text: String, val subject: String, val at: Long, val isLink: Boolean)
    /** 重新收码: codes and verification links in the box right now */
    suspend fun codes(addr: String, host: String): List<Code>? = read(addr)?.mapNotNull { m ->
        MailParse.extractCode(m.text)?.let { Code(it, m.subject, m.at, false) } ?: RegisterAutoCore.pickLink(m.text, host.ifBlank { "x" })?.let { Code(it, m.subject, m.at, true) }
    }

    // ── 自动延时 / 探针保活 ──

    fun kaOf(key: String): Ka = _ka.value[key] ?: Ka(key)
    fun setKa(c: Context, k: Ka) {
        init(c)
        _ka.value = _ka.value + (k.key to k)
        saveAll()
        schedule(c)
    }

    /** the 2-hourly job exists only while some switch is on */
    fun schedule(c: Context) {
        val wm = runCatching { WorkManager.getInstance(c.applicationContext) }.getOrNull() ?: return
        if (!RegLedgerCore.anyOn(_ka.value.values)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<RegKeepAliveWorker>(2, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun workState(c: Context): String = runCatching {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                WorkManager.getInstance(c.applicationContext).getWorkInfosForUniqueWorkFlow(WORK).first()
            }?.joinToString(",") { w: androidx.work.WorkInfo -> w.state.name } ?: "?"
        }
    }.getOrDefault("?")

    /** everything due (or everything switched on when [force]); one at a time; returns what it did */
    suspend fun runDue(c: Context, force: Boolean = false): List<String> = kaLock.withLock {
        init(c); RegisterAuto.init(c)
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        for (k in _ka.value.values.toList()) {
            if (k.extend && (force || RegLedgerCore.extendDue(k, now))) out += extend(c, k).also { note("extend ${k.key} $it") }
            val k2 = kaOf(k.key)
            if (k2.probe && (force || RegLedgerCore.probeDue(k2, now))) out += probe(c, k2).also { note("probe ${k.key} $it") }
        }
        out
    }

    private fun urlFor(c: Context, key: String): String? {
        if (!key.startsWith("acct|")) return null
        val host = key.split('|').getOrNull(1) ?: return null
        val acct = key.split('|').getOrNull(2).orEmpty()
        val rec = _recs.value.firstOrNull { it.host.equals(host, true) && it.box.equals(acct, true) }
        val u = rec?.url?.takeIf { it.isNotBlank() } ?: SavedLogins.find(c, host, acct, anyUser = true)?.url ?: "https://$host/"
        return u.substringBefore("://") + "://" + u.substringAfter("://").substringBefore('/') + "/"
    }

    /** 自动延时: a temporary box = sign in again (Mail.tm keeps a box that is used); an account = its free 续期 button */
    private suspend fun extend(c: Context, k: Ka): String {
        val now = System.currentTimeMillis()
        val result = if (k.key.startsWith("box|")) {
            if (withContext(Dispatchers.IO) { token(k.key.removePrefix("box|")) } != null) "已续期" else "邮箱登录失败"
        } else {
            val url = urlFor(c, k.key) ?: return "没有网址"
            if (RegisterAuto.running()) return "注册进行中，稍后再续"
            val loaded = RegisterBrowser.load(c, url)
            val cap = HumanVerify.parseScan(RegisterBrowser.eval(HumanVerify.DETECT_JS)).kind
            when {
                !loaded -> "页面打不开"
                cap.isNotBlank() -> { ask(c, k.key, "续期要做人机验证"); "卡在人机验证" }
                else -> when (RegisterBrowser.eval(RegLedgerCore.RENEW_JS)) {
                    "clicked" -> { RegisterBrowser.settle(); "已续期" }
                    "pay" -> { ask(c, k.key, "续期要付款，我不替你付"); "要付款，没续" }
                    else -> "没找到续期按钮"
                }
            }.also { withContext(Dispatchers.Main) { RegisterBrowser.close() } }
        }
        _ka.value = _ka.value + (k.key to kaOf(k.key).copy(lastExtendAt = now, extendNote = if (result == "已续期") "" else result)); saveAll()
        if (result != "已续期" && !result.startsWith("卡在") && !result.startsWith("要付款")) alert(c, k.key, "自动延时没成功：$result")
        return result
    }

    /** 探针保活: a box = read its inbox; an account = open its site with the saved session (a ping when it is a bare host) */
    private suspend fun probe(c: Context, k: Ka): String {
        val now = System.currentTimeMillis()
        var ok: Boolean; var why = ""
        if (k.key.startsWith("box|")) {
            ok = read(k.key.removePrefix("box|"), 1) != null; if (!ok) why = "邮箱登录失败"
        } else {
            val url = urlFor(c, k.key) ?: return "没有网址"
            val code = withContext(Dispatchers.IO) { runCatching { http.newCall(Request.Builder().url(url).get().build()).execute().use { it.code } }.getOrDefault(-1) }
            ok = code in 200..399; if (!ok) why = if (code < 0) "连不上" else "HTTP $code"
            if (ok && !RegisterAuto.running()) {   // keep the sign-in fresh: open it in the persistent browser (same cookie jar)
                RegisterBrowser.load(c, url)
                val cap = HumanVerify.parseScan(RegisterBrowser.eval(HumanVerify.DETECT_JS)).kind
                if (cap.isNotBlank()) { ask(c, k.key, "探活要做人机验证"); why = "卡在人机验证" }
                withContext(Dispatchers.Main) { RegisterBrowser.close() }
            }
        }
        _ka.value = _ka.value + (k.key to kaOf(k.key).copy(lastProbeAt = now, probeOk = ok && why.isBlank(), probeNote = why)); saveAll()
        if (!ok) alert(c, k.key, "探活失败：$why")
        return if (ok && why.isBlank()) "ok" else why
    }

    private fun label(key: String): String = key.split('|').let { p -> if (p[0] == "box") Inbox3912Core.maskMail(p[1]) else p.getOrElse(1) { "" } }

    /** a failure → 消息 (notification + a line in 聊天 when 聊天主页 is on) */
    private suspend fun alert(c: Context, key: String, body: String) {
        val text = "台账 ${label(key)}：$body"
        lastAlert = text
        notify(c, text, 7302)
        if (ChatHome.enabled()) runCatching { ChatHome.appendToMain(listOf("assistant" to text)) }
    }
    @Volatile var lastAlert: String = ""

    /** a captcha / payment on 续期 or 探活: stop and ask (one line, 来电 when on) */
    private fun ask(c: Context, key: String, body: String) {
        val text = "台账 ${label(key)}：$body，点这里处理。"
        runCatching { MinesCall.onNeedsAttention(c, "台账保活 · 等你", text) }
        notify(c, text, 7303)
    }

    private fun notify(c: Context, text: String, id: Int) {
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        val ch = "mines_register_auto"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val sys = c.getSystemService(android.app.NotificationManager::class.java)
            if (sys?.getNotificationChannel(ch) == null) sys?.createNotificationChannel(android.app.NotificationChannel(ch, "一键注册", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return)
            .putExtra("mines_route", "plus_register_ledger").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(c, id, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(c, ch).setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle("注册台账").setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    // ── 整理临时邮箱账号 ──

    fun tidyDone(): Boolean = p()?.getBoolean("tidy_v1", false) == true

    /** group / dedupe / fill / verify / delete (→ 最近删除) every temporary box; long-term boxes are never touched */
    suspend fun tidy(c: Context): RegLedgerCore.Summary = tidyLock.withLock {
        init(c); RegisterAuto.init(c)
        val now = System.currentTimeMillis()
        val all = runCatching { SavedLogins.all(c) }.getOrDefault(emptyList())
        val domains = withContext(Dispatchers.IO) {
            val (code, d) = req("GET", "/domains")
            if (code in 200..299) runCatching {
                val t = d.trim(); val a = if (t.startsWith("[")) org.json.JSONArray(t) else JSONObject(t).let { it.optJSONArray("hydra:member") ?: it.optJSONArray("member") ?: org.json.JSONArray() }
                (0 until a.length()).map { a.getJSONObject(it).optString("domain").lowercase() }.toSet()
            }.getOrDefault(emptySet()) else emptySet()
        } + knownBoxes().map { it.substringAfter('@') }
        val known = knownBoxes()
        val boxes = (all.map { it.user.lowercase() } + _recs.value.map { it.box.lowercase() }).filter { RegLedgerCore.isTemp(it, domains, known + (if (boxPass(it) != null) setOf(it) else emptySet())) }.distinct()
        var total = 0; var filled = 0; var deleted = 0
        for (b in boxes) {
            val es = all.filter { it.user.equals(b, true) }.map { e ->
                val note = runCatching { Ledger.cands(c, e.host).firstOrNull { it.account.equals(e.user, true) }?.note }.getOrNull().orEmpty()
                RegLedgerCore.Entry(e.host, e.user, e.pass.isNotBlank(), note, e.name)
            }
            val mails = read(b)
            val plan = RegLedgerCore.tidy(b, es, recsFor(b), mails, now)
            total += plan.keep.size + plan.trash.size; filled += plan.filled
            // the 台账 (SavedLogins) rows of this box: one per host (dedupe)
            all.filter { it.user.equals(b, true) }.groupBy { it.host.lowercase() }.values.forEach { dup ->
                if (dup.size > 1) dup.sortedByDescending { it.pass.length }.drop(1).forEach { d -> if (d.pass.isBlank()) runCatching { SavedLogins.remove(c, d.host, d.user) } }
            }
            synchronized(this) { _recs.value = _recs.value.filterNot { it.box.equals(b, true) } + plan.keep }
            for (t in plan.trash) { moveToTrash(c, b, t, plan.keep.firstOrNull { it.host.equals(t.host, true) }, now); deleted++ }
            for ((r, link) in plan.verify) {
                note("tidy verify ${r.host} ${link.take(60)}")
                if (RegisterAuto.running()) break
                if (RegisterBrowser.load(c, link)) {
                    val s = RegisterBrowser.scan()
                    if (RegisterAutoCore.classify(s) == RegisterAutoCore.Page.DONE || s.success) {
                        put(r.copy(st = RSt.VERIFIED, why = ""))
                        Ledger.setNote(c, r.host, b, RegisterAutoCore.ledgerNote(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(r.createdAt.takeIf { it > 0 } ?: now)), true))
                        Ledger.markOk(c, r.host, b)
                    }
                }
            }
            if (plan.verify.isNotEmpty()) withContext(Dispatchers.Main) { RegisterBrowser.close() }
            // 台账 备注 for kept rows that had none
            plan.keep.forEach { r -> if (r.host.isNotBlank()) runCatching {
                val cur = Ledger.cands(c, r.host).firstOrNull { it.account.equals(b, true) }?.note.orEmpty()
                if (cur.isBlank() && SavedLogins.find(c, r.host, b) != null) Ledger.setNote(c, r.host, b, if (r.st == RSt.VERIFIED) "注册于 " + day(r.createdAt.takeIf { it > 0 } ?: now) + " · Mail.tm" else "注册中 " + day(now) + " · " + r.why.ifBlank { "Mail.tm" })
            } }
        }
        saveAll()
        p()?.edit()?.putBoolean("tidy_v1", true)?.apply()
        RegLedgerCore.Summary(total, filled, deleted).also { lastTidy.value = it; note("tidy $it") }
    }

    private fun day(t: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    private fun moveToTrash(c: Context, box: String, e: RegLedgerCore.Entry, rec: Rec?, now: Long) {
        val id = "t" + now + "_" + (e.host + e.account).hashCode().toUInt().toString(16)
        val pw = runCatching { SavedLogins.find(c, e.host, e.account)?.pass }.getOrNull()
        if (!pw.isNullOrBlank()) PlusVault.setSecretExtra(V, "trash_$id", pw)
        val r = rec ?: Rec(box, e.host, e.name, if (e.host.isNotBlank()) "https://${e.host}/" else "", st = RSt.PENDING, why = "邮箱失效", createdAt = 0, updatedAt = now)
        if (e.host.isNotBlank()) runCatching { SavedLogins.remove(c, e.host, e.account) }
        synchronized(this) {
            _recs.value = _recs.value.filterNot { it.id == r.id }
            _trash.value = _trash.value + RegLedgerCore.Trashed(id, r, e.host, e.account, e.name, now)
        }
        note("trash ${e.host} ${e.account}")
    }

    fun restore(c: Context, id: String) {
        val t = _trash.value.firstOrNull { it.id == id } ?: return
        PlusVault.secretExtra(V, "trash_$id")?.let { pw -> if (t.host.isNotBlank()) SavedLogins.put(c, SavedLogins.Entry(t.name.ifBlank { t.host }, "https://${t.host}/", t.account, pw)) }
        PlusVault.setSecretExtra(V, "trash_$id", null)
        synchronized(this) { _trash.value = _trash.value.filterNot { it.id == id }; _recs.value = RegLedgerCore.upsert(_recs.value, t.rec) }
        saveAll()
    }

    // ── smoke ──

    fun stateJson(c: Context): String = JSONObject()
        .put("recs", org.json.JSONArray().apply { _recs.value.forEach { r -> put(RegLedgerCore.recJson(r).put("boxPass", boxPass(r.box) != null)
            .put("pw", runCatching { SavedLogins.find(c, r.host, r.box)?.pass?.isNotBlank() == true }.getOrDefault(false))) } })
        .put("ka", org.json.JSONArray(RegLedgerCore.kaJson(_ka.value.values)))
        .put("trash", org.json.JSONArray(RegLedgerCore.trashJson(_trash.value)))
        .put("tidy", lastTidy.value?.line ?: "").put("alert", lastAlert).put("tidyDone", tidyDone()).put("work", workState(c))
        .put("log", org.json.JSONArray(log.toList().takeLast(30))).toString()
}

/** [Mines-38.9.15 register_auto] the 2-hourly 自动延时 / 探针保活 run (WorkManager: network + battery not low) */
class RegKeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { RegLedger.runDue(applicationContext) }
        return Result.success()
    }
}

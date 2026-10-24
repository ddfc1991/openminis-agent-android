package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [Mines-patrol-31] 账号巡查: a battery-friendly background check of every
 * connected account (JobScheduler, every ~4 h, only on network + battery not
 * low). Per account: key / token still valid (one cheap auth call), balance
 * low, rate-limited, server reachable. Result = 正常 / 注意 / 异常 + one line,
 * shown as a dot on 账号连接. A notification goes out only for a NEW 异常
 * (same reason is not repeated within 24 h).
 *
 * Not yet: login/security e-mails via Gmail, OAuth expiry dates, trial /
 * credit expiry (no API) — noted for a later build.
 */
object AccountPatrol {
    enum class Level(val label: String) { OK("正常"), WARN("注意"), BAD("异常") }
    data class Status(val level: Level, val reason: String, val at: Long, val fix: String? = null)

    private const val PREFS = "mines_account_patrol"
    private const val JOB_ID = 73101
    private const val CHANNEL = "mines_patrol"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun load(context: Context) {
        if (_status.value.isNotEmpty()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString("status", null) ?: return
        runCatching {
            val o = JSONObject(raw)
            _status.value = o.keys().asSequence().associateWith { k ->
                val e = o.getJSONObject(k)
                Status(Level.valueOf(e.optString("level", "OK")), e.optString("reason"), e.optLong("at"), e.optString("fix").ifBlank { null })
            }
        }
    }

    private fun save(context: Context) {
        val o = JSONObject()
        _status.value.forEach { (k, s) -> o.put(k, JSONObject().put("level", s.level.name).put("reason", s.reason).put("at", s.at).put("fix", s.fix ?: "")) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status", o.toString()).apply()
    }

    /** [Mines-38.9.3] smoke only: put a known patrol result on one account (no network). */
    fun smokeSet(context: Context, id: String, level: Level, reason: String) {
        load(context); _status.value = _status.value + (id to Status(level, reason, System.currentTimeMillis())); save(context)
    }

    /** Problems for 消息汇总 (要处理). */
    fun problems(): List<Pair<String, Status>> = _status.value.filter { it.value.level != Level.OK && !runCatching { ServiceIgnore.ignored(it.key) }.getOrDefault(false) }.toList()   // [Mines-38.9.5 i] 忽略

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        runCatching {
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, AccountPatrolJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(4 * 60 * 60 * 1000L, 60 * 60 * 1000L)
                .setPersisted(false)
                .build())
        }
    }

    fun runNow(context: Context) { scope.launch { run(context.applicationContext) } }

    /** [Mines-patrol-35.1] ids being re-checked right now (row shows 「检测中…」). */
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()

    /**
     * [Mines-patrol-35.1] Re-test ONE account now (after its key was replaced, or 「重新检测」 on its row). The old
     * error is cleared at once, the account's 账号异常 cards leave 消息, then the fresh result is stored.
     * User 2026-10-14: a new Cloudflare token kept showing the old error until the next 4-hourly patrol.
     */
    fun recheck(context: Context, kindId: String) {
        val app = context.applicationContext
        scope.launch {
            load(app)
            _checking.value = _checking.value + kindId
            _status.value = _status.value - kindId
            runCatching { Digest.dropAccount(app, kindId) }
            try {
                val now = System.currentTimeMillis()
                val e = PlusVault.entries.value[kindId]
                val secret = PlusVault.secret(kindId)
                val st = if (e != null && secret != null) {
                    val base = PlusVault.baseId(kindId)
                    val r = KeyTester.test(base, secret)
                    r.identity?.takeIf { it.isNotBlank() }?.let { PlusVault.setExtra(kindId, "identity", it) }
                    when {
                        r.message.startsWith("测试时连不上") -> Status(Level.WARN, "这次没连上服务器，稍后再试", now)
                        !r.ok -> Status(Level.BAD, r.message, now, fix = if (KeyAgent.recipe(base) != null) "regen" else "relogin")
                        r.needsCredit -> Status(Level.WARN, "余额不足，需要你自己充值", now)
                        else -> Status(Level.OK, "密钥有效", now)
                    }
                } else null
                _status.value = if (st == null) _status.value - kindId else _status.value + (kindId to st)
                save(app)
                if (st?.level != Level.OK) runCatching { Digest.refresh(app) }
            } finally { _checking.value = _checking.value - kindId }
        }
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        if (_running.value) return@withContext
        _running.value = true
        try {
            load(context)
            PlusVault.init(context)
            runCatching { PlusVault.purgePrevKeys() }
            // [Mines-apps-32] keys already in the 模型组 count as connected accounts
            runCatching { ProviderKeys.adopt(context) }
            val now = System.currentTimeMillis()
            val out = HashMap<String, Status>()
            for (e in PlusVault.entries.value.values) {
                val secret = PlusVault.secret(e.kindId)
                val base = PlusVault.baseId(e.kindId)
                out[e.kindId] = if (secret != null && (e.via == "key" || e.via == "token" || e.via == "oauth" || e.via == "model" || base == "github")) {
                    val r = KeyTester.test(base, secret)
                    when {
                        r.message.startsWith("测试时连不上") -> Status(Level.WARN, "这次没连上服务器，下次再试", now)
                        !r.ok -> Status(Level.BAD, r.message, now, fix = if (KeyAgent.recipe(base) != null) "regen" else "relogin")
                        r.needsCredit -> Status(Level.WARN, "余额不足，需要你自己充值", now)
                        r.message.contains("限流") -> Status(Level.WARN, "最近被限流（429）", now)
                        else -> Status(Level.OK, "密钥有效", now)
                    }
                } else Status(Level.OK, if (e.via == "web") "网页登录（不自动检查）" else "已连接", now)
            }
            // [Mines-apps-32] per-account balance (plain HTTP, no model) + expiry reminders
            for (e in PlusVault.entries.value.values) {
                runCatching { KeyBalance.refresh(e.kindId) }
                if (KeyBalance.low(e.kindId) && out[e.kindId]?.level == Level.OK) out[e.kindId] = Status(Level.WARN, (KeyBalance.text(e.kindId) ?: "").let { t -> if (t.startsWith("欠费")) "已$t" else "余额低：$t" }, now)
                Expiry.check(e.kindId, now)?.let { st -> if ((out[e.kindId]?.level ?: Level.OK).ordinal <= st.level.ordinal) out[e.kindId] = st }
            }
            // balances that are low
            runCatching { Balances.refresh(context, 0) }.getOrNull()?.filter { it.low }?.forEach { b ->
                val id = PlusVault.entries.value.keys.firstOrNull { PlusVault.kind(it)?.name?.let { n -> b.name.contains(n) || n.contains(b.name) } == true }
                if (id != null && out[id]?.level != Level.BAD) out[id] = Status(Level.WARN, if (b.text.startsWith("欠费")) "已${b.text}" else "余额低：${b.text}", now)
            }
            // servers
            PlusPrefs.state.value.servers.filter { it.enabled }.forEach { s ->
                out["vps:" + s.id] = if (s.lastLatencyMs >= 0 || MinesLink.states.value[s.id]?.first == MinesLink.State.ONLINE)
                    Status(Level.OK, "服务器在线", now)
                else Status(Level.BAD, "服务器 ${s.name.ifBlank { s.host }} 连不上：${s.lastError.lineSequence().firstOrNull().orEmpty().take(60)}", now, fix = "vps")
            }
            val before = _status.value
            _status.value = out
            save(context)
            notifyNew(context, before, out)
            // [Mines-offers-32] free-credit offers: JSON + rules, no model call
            runCatching { FreeOffers.load(context); FreeOffers.notifyNew(context) }
        } finally { _running.value = false }
    }

    private fun notifyNew(context: Context, before: Map<String, Status>, now: Map<String, Status>) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = now.filter { (k, s) ->
            s.level == Level.BAD && before[k]?.reason != s.reason && !runCatching { ServiceIgnore.ignored(k) }.getOrDefault(false) &&
                System.currentTimeMillis() - p.getLong("notified_" + (k + s.reason).hashCode(), 0) > 24 * 3600_000L
        }
        if (fresh.isEmpty()) return
        fresh.forEach { (k, s) -> p.edit().putLong("notified_" + (k + s.reason).hashCode(), System.currentTimeMillis()).apply() }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "账号巡查", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 73, Intent(context, com.openminis.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val first = fresh.entries.first()
        val name = if (first.key.startsWith("vps:")) "服务器" else PlusVault.kind(first.key)?.name ?: first.key
        runCatching {
            nm.notify(73102, android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (fresh.size == 1) "$name 有问题" else "${fresh.size} 个账号有问题")
                .setContentText(AccountLabel.maskText(first.value.reason))  // [Mines-privacy-35.3]
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/**
 * [Mines-patrol-32] Per-account expiry reminders: trial end, credits running
 * out, an OAuth token's expiry. Set by the user on the account page or by the
 * agent (`minis-connect remind <service> <yyyy-mm-dd> [note]`). 7 days before
 * → 注意, on/after the day → 异常 (which notifies once).
 */
object Expiry {
    private val fmt get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

    fun set(kindId: String, date: String, note: String) {
        PlusVault.setExtra(kindId, "remind_date", date.trim())
        PlusVault.setExtra(kindId, "remind_note", note.trim().ifBlank { "到期" })
    }

    fun clear(kindId: String) { PlusVault.setExtra(kindId, "remind_date", "-") }

    fun date(kindId: String): String? = PlusVault.extra(kindId, "remind_date")?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
    fun note(kindId: String): String = PlusVault.extra(kindId, "remind_note") ?: "到期"

    fun check(kindId: String, now: Long): AccountPatrol.Status? {
        val list = ArrayList<Pair<Long, String>>()
        date(kindId)?.let { d -> runCatching { fmt.parse(d)!!.time }.getOrNull()?.let { list += it to "${note(kindId)}（$d）" } }
        PlusVault.extra(kindId, "expires")?.toLongOrNull()?.let { t -> if (PlusVault.secretExtra(kindId, "refresh") == null) list += t to "登录授权过期" }
        val (t, what) = list.minByOrNull { it.first } ?: return null
        val days = ((t - now) / 86_400_000L).toInt()
        return when {
            t <= now -> AccountPatrol.Status(AccountPatrol.Level.BAD, "$what 已到期", now, fix = "relogin")
            days <= 7 -> AccountPatrol.Status(AccountPatrol.Level.WARN, "$what 还有 $days 天", now)
            else -> null
        }
    }
}

class AccountPatrolJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            runCatching { AccountPatrol.run(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean = true
}

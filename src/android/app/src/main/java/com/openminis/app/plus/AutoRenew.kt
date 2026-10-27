package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-auto-renew] 账号自动续期（用户 2026-10-27 清单 ①）。
 *
 * 现状：登录一次之后，会话过期了要用户重新打开页面才触发 [AutoLogin.tryBefore]。
 * 目标：**登录一次后自动保持**，不用反复登。
 *
 * 做法（三层，从轻到重）：
 *  1. **探针**（[probe]）——对一个站点发一次不带凭据的 GET，看返回是不是登录页
 *     （isLoginWall）。便宜，不解密任何凭据。
 *  2. **续期**（[renewOne]）——探针判定失效时，把 [LoginMemory] 里保存的 cookie
 *     快照重新写回该账号的 CookieManager（等价于 tryBefore 的 REUSE_SESSION 分支，
 *     但不需要打开页面）。多数站点的会话靠 cookie 续，这一步就够。
 *  3. **重登**（交给 [AutoLogin.tryBefore]）——cookie 也救不回来时，等用户下次打开
 *     这个站点时按既有链路走密码 / OAuth，不在这里硬试（避免风控）。
 *
 * 调度：[schedule] 用 [AccountPatrol] 的 JobScheduler 一起跑（同一个 ~4h 的周期），
 * 不额外开后台常驻进程。
 *
 * 状态：[state] 暴露给 UI / CLI 看「上次续期什么时候、几个站点需要人工重登」。
 */
object AutoRenew {
    private const val PREFS = "mines_auto_renew"
    private const val KEY_LAST = "last_run"
    private const val KEY_JSON = "state"

    /** 一次续期最多同时探测几个站点。8 路够快，也不至于把手机网络打满。 */
    private val sem = Semaphore(8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false

    data class SiteState(
        val site: String,
        val host: String,
        /** "ok" 会话有效 / "reused" 已用 cookie 续回 / "need_login" 要人工重登 / "skip" 跳过 */
        val state: String,
        val at: Long,
        val note: String = "",
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<List<SiteState>>(emptyList())
    val state: kotlinx.coroutines.flow.StateFlow<List<SiteState>> = _state

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (_state.value.isNotEmpty()) return
        val raw = p(c).getString(KEY_JSON, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<SiteState>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SiteState(
                        o.optString("site"),
                        o.optString("host"),
                        o.optString("state"),
                        o.optLong("at"),
                        o.optString("note"),
                    ),
                )
            }
            _state.value = out
        }
    }

    private fun save(c: Context, list: List<SiteState>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("site", it.site)
                    .put("host", it.host)
                    .put("state", it.state)
                    .put("at", it.at)
                    .put("note", it.note),
            )
        }
        p(c).edit().putString(KEY_JSON, arr.toString()).putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    fun lastRun(c: Context): Long = p(c).getLong(KEY_LAST, 0L)

    /** 只跑一次，不常驻。给 AccountPatrol 或用户手动触发。 */
    fun schedule(c: Context) {
        if (running) return
        scope.launch { run(c.applicationContext) }
    }

    /**
     * 主流程：遍历台账里的站点，并发探测 + 尝试续期。
     * 返回这次的快照。
     */
    suspend fun run(c: Context): List<SiteState> = withContext(Dispatchers.IO) {
        if (running) return@withContext _state.value
        running = true
        try {
            load(c)
            AutoAccounts.init(c)
            val entries = AutoAccounts.list.value
            if (entries.isEmpty()) {
                _state.value = emptyList()
                return@withContext emptyList()
            }
            val now = System.currentTimeMillis()
            val results = coroutineScope {
                entries.map { e ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            runCatching { renewOne(c, e, now) }
                                .getOrElse { SiteState(e.site, e.host, "skip", now, it.message.orEmpty().take(40)) }
                        }
                    }
                }.awaitAll()
            }
            _state.value = results
            save(c, results)
            results
        } finally {
            running = false
        }
    }

    /** 单站点续期（minis-accounts login 用）。返回 null 表示没找到该站点 */
    suspend fun renewSite(c: Context, host: String): SiteState? = withContext(Dispatchers.IO) {
        load(c)
        AutoAccounts.init(c)
        val e = AutoAccounts.list.value.firstOrNull { it.host.equals(host, true) || it.site.equals(host, true) }
        if (e == null) return@withContext null
        runCatching { renewOne(c, e, System.currentTimeMillis()) }.getOrNull()
    }

    /**
     * 单个站点：先探针，失效就把 cookie 快照写回去。
     *
     * 注意：这里**不解密密码**，只用 [LoginMemory] 的 cookie 快照（本来就是加密存的，
     * 这里只做「读出来 → 写进 CookieManager」）。密码级重登留给 AutoLogin.tryBefore，
     * 因为那需要真实页面上下文（OAuth 按钮、验证码）。
     */
    private suspend fun renewOne(c: Context, e: AutoAccounts.Entry, now: Long): SiteState {
        val host = e.host.ifBlank { return SiteState(e.site, e.host, "skip", now, "没有域名") }
        val url = "https://$host/"

        // 1) 有 cookie 快照吗？没有就只能标记为要人工登录
        val snap = runCatching { LoginMemory.snapshotCookies(c, host) }.getOrNull()
        if (snap.isNullOrEmpty()) {
            return SiteState(e.site, host, "need_login", now, "没有保存的会话快照")
        }

        // 2) 探针：带 cookie 发一次 HEAD/GET，看是不是被踢回登录页
        val alive = runCatching { probe(c, url, host, snap) }.getOrDefault(true)
        if (alive) return SiteState(e.site, host, "ok", now)

        // 3) 失效 → 把快照写回 CookieManager（下次打开页面自动生效）
        val wrote = withContext(Dispatchers.Main) {
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                var n = 0
                for ((name, value) in snap) {
                    for (u in listOf("https://$host/", "https://www.$host/")) {
                        runCatching { cm.setCookie(u, "$name=$value; path=/") }.onSuccess { n++ }
                    }
                }
                runCatching { cm.flush() }
                n
            }.getOrDefault(0)
        }
        return if (wrote > 0) {
            SiteState(e.site, host, "reused", now, "写回 $wrote 条 cookie")
        } else {
            SiteState(e.site, host, "need_login", now, "写回失败")
        }
    }

    /**
     * 探针：带 cookie 请求一次，看 final URL 是不是登录页 / HTTP 是不是 401。
     * 返回 true = 会话还活着。
     *
     * 保守策略：网络错误一律当「活着」（不误报、不折腾），只有明确看到登录页才判死。
     */
    private fun probe(c: Context, url: String, host: String, snap: Map<String, String>): Boolean {
        return runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mines-Renew")
                setRequestProperty("Cookie", snap.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location").orEmpty()
            conn.disconnect()
            when {
                code == 401 || code == 403 -> false          // 明确被拒
                code in 300..399 && looksLikeLogin(loc) -> false  // 重定向到登录页
                else -> true
            }
        }.getOrDefault(true)
    }

    private fun looksLikeLogin(u: String): Boolean {
        val s = u.lowercase()
        return s.contains("login") || s.contains("signin") || s.contains("sign-in") ||
            s.contains("auth") || s.contains("sso") || s.contains("passport") ||
            s.contains("登录")
    }

    /** 给 UI / CLI 的摘要：几个需要人工重登 */
    fun summary(c: Context): String {
        load(c)
        val l = _state.value
        if (l.isEmpty()) return "还没跑过续期"
        val ok = l.count { it.state == "ok" }
        val reused = l.count { it.state == "reused" }
        val need = l.count { it.state == "need_login" }
        return "共 ${l.size} 个站点：有效 $ok · 已续 $reused · 要重登 $need（上次 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(lastRun(c)))}）"
    }
}

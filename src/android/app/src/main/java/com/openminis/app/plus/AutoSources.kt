package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [Mines-sources-33] Every login becomes a working data source, with no
 * 「连接这个来源」 step (user, 2026-10-13: 「应该是智能的，你搞成半自动有点恼火」).
 *
 * [scan] looks at everything Mines already holds — WebView session cookies for every
 * service in the catalog, Google / Gmail cookies, OAuth tokens (GitHub, Microsoft),
 * API keys (incl. keys sitting in 模型组), IMAP app passwords, notification access —
 * and, for a signed-in service that has no 账号连接 row yet, adds the row itself
 * (via "web", marked auto) so every page treats it as connected. It runs on every
 * app start (cheap: cookie reads only) and after each login; the first launch of a new
 * build shows one toast 「已自动接入 N 个来源」, later launches only when something new
 * turned up.
 *
 * What each source feeds, automatically:
 *  - mailboxes (Gmail web session / Outlook token / IMAP) → one-time codes for the
 *    browser + 消息汇总 ([MailSources])
 *  - GitHub token, or just the github.com session → notifications in 消息汇总
 *  - X session → mentions + followed keywords in 消息汇总 (read off-screen)
 *  - provider consoles with a session but no balance API → balance from the billing
 *    page ([sessionBalances])
 */
object AutoSources {
    private const val PREFS = "mines_auto_sources"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    data class Src(val id: String, val name: String, val how: String, val feeds: String)

    private val _active = MutableStateFlow<List<Src>>(emptyList())
    val active: StateFlow<List<Src>> = _active.asStateFlow()

    /** Services whose session cookie we can check (catalog + Gmail). */
    private fun cookieKinds(): List<ConnectionKind> = PlusVault.kinds.filter { it.doneCookies.isNotEmpty() && !it.hidden }

    /** Signed-in services found in the WebView cookie jar: kindId → account hint. */
    fun sessions(): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return out
        for (k in cookieKinds()) {
            if (!LoginWeb.signedIn(k)) continue
            val c = listOfNotNull(k.loginUrl, k.panelUrl).firstNotNullOfOrNull { runCatching { cm.getCookie(it) }.getOrNull() }
            out[k.id] = MailParse.accountFromCookies(k.id, c)
        }
        return out
    }

    fun xSignedIn(): Boolean = PlusVault.kind("x")?.let { LoginWeb.signedIn(it) } == true
    fun githubSession(): Boolean = PlusVault.kind("github")?.let { LoginWeb.signedIn(it) } == true
    fun githubToken(): String? = (PlusVault.defaultAccount("github")?.let { PlusVault.secret(it) } ?: PlusVault.secret("github"))?.takeIf { it.isNotBlank() }

    /**
     * Adopts every signed-in service and key; returns the ids that were added now.
     * Cookie reads only (plus the 模型组 key scan): safe on the main thread.
     */
    fun scan(context: Context): List<String> {
        PlusVault.init(context)
        val added = ArrayList<String>()
        runCatching { if (ProviderKeys.adopt(context) > 0) added += "keys" }
        for ((id, account) in sessions()) {
            val kind = PlusVault.kind(id) ?: continue
            if (PlusVault.accountsOf(id).isNotEmpty()) {
                // already a row: make sure it is marked signed in (the session may be newer than the row)
                PlusVault.defaultAccount(id)?.let { if (PlusVault.extra(it, "session") != "1") LoginWeb.markSignedIn(it, account) }
                continue
            }
            PlusVault.save(context, kind, account ?: "网页登录（自动发现）", null, share = false, via = "web")
            PlusVault.setExtra(id, "auto", "1")
            LoginWeb.markSignedIn(id, account)
            runCatching { PlusConnect.snapshotCookies(kind, listOfNotNull(kind.loginUrl, kind.panelUrl)) }
            added += id
        }
        _active.value = list(context)
        return added
    }

    /** What is feeding Mines right now (for the toast, 消息 and 账号连接). */
    fun list(context: Context): List<Src> {
        PlusVault.init(context)
        val out = ArrayList<Src>()
        if (com.openminis.app.offload.MinisNotificationListenerService.isEnabled(context)) out += Src("notif", "手机通知", "通知使用权", "验证码 · 消息")
        MailSources.boxes(context).forEach { out += Src(it.id, it.label, it.how, "验证码 · 邮件") }
        if (githubToken() != null) out += Src("github", "GitHub", "授权", "通知")
        else if (githubSession()) out += Src("github", "GitHub", "网页登录", "通知")
        if (xSignedIn()) out += Src("x", "X", "网页登录", "@你 · 关注")
        (KeyBalance.billingUrl.keys + extraBilling.keys).map { PlusVault.defaultAccount(it) ?: it }.filter { PlusVault.extra(it, "balance_via") == "web" }.forEach { id ->
            out += Src("bal:$id", PlusVault.kind(id)?.name ?: id, "网页登录", "余额")
        }
        PlusVault.entries.value.values.filter { it.via in setOf("key", "model", "token") && KeyBalance.hasApi(PlusVault.baseId(it.kindId)) }.forEach { e ->
            out += Src("key:" + e.kindId, PlusVault.kind(e.kindId)?.name ?: e.kindId, "密钥", "余额")
        }
        return out.distinctBy { it.id }
    }

    /** 「接入更多来源」 rows: only what is truly missing. Pairs of (key, why). */
    fun missing(context: Context): List<Pair<String, String>> = buildList {
        if (!com.openminis.app.offload.MinisNotificationListenerService.isEnabled(context)) add("通知" to "打开「通知使用权」，微信、短信验证码等才能自动读到")
        if (githubToken() == null && !githubSession()) add("GitHub" to "登录 GitHub，看 @你、审代码和安全提醒")
        if (!xSignedIn()) add("X" to "登录 X，按你关注的词看实时推文")
        if (MailSources.boxes(context).isEmpty()) add("Gmail" to "在 Mines 里登录一次 Gmail，验证码邮件会自动填")
    }

    // ---------------- app start ----------------

    /** Called from MainActivity.onStart: scan, toast, then refresh what the sources feed. */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(2500) // let the first frame and the cookie store settle
            val added = withContext(Dispatchers.Main) { runCatching { scan(app) }.getOrDefault(emptyList()) }
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val build = com.openminis.app.BuildConfig.MINES_BUILD
            val firstOfBuild = p.getInt("scan_build", -1) != build
            val n = if (firstOfBuild) _active.value.size else added.size
            p.edit().putInt("scan_build", build).apply()
            if (n > 0 && (firstOfBuild || added.isNotEmpty())) withContext(Dispatchers.Main) {
                Toast.makeText(app, "已自动接入 $n 个来源", Toast.LENGTH_SHORT).show()
            }
            // feed the sources: balances via sessions, then the digest (each throttled)
            runCatching { sessionBalances(app) }
            runCatching { ChatKnowledge.run(app) } // [Mines-kb-33] chats → 对话整理 knowledge
            if (System.currentTimeMillis() - p.getLong("digest_at", 0L) > 30 * 60_000L) {
                p.edit().putLong("digest_at", System.currentTimeMillis()).apply()
                runCatching { Digest.refresh(app) }
            }
        }
    }

    /** After a login screen closed: adopt it right away (no toast; the screen already said 已连接). */
    fun afterLogin(context: Context) {
        val app = context.applicationContext
        scope.launch(Dispatchers.Main) { runCatching { scan(app) }; MailSources.invalidate() }
    }

    // ---------------- GitHub through the web session ----------------

    fun githubWebItems(): List<Digest.Item> {
        if (githubToken() != null || !githubSession()) return emptyList()
        val url = "https://github.com/notifications?query=is%3Aunread"
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: return emptyList()
        val html = http.newCall(Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", MINES_MOBILE_UA)
            .header("Accept", "text/html").build()).execute().use { if (it.code == 200) it.body?.string() else null } ?: return emptyList()
        return MailParse.githubNotifications(html).take(40).map { n ->
            val need = when {
                n.reason.contains("review") -> "请你审代码"; n.reason.contains("mention") -> "有人 @你"; n.reason.contains("assign") -> "分配给你"
                n.reason.contains("security") -> "安全警告"; n.reason.contains("ci") -> "CI 结果"; else -> "有更新"
            }
            Digest.Item("ghw:" + n.url.hashCode(), "GitHub", "github:" + n.repo, n.repo, n.title, "$need · ${n.title}".take(120),
                System.currentTimeMillis() - 60_000L, url = n.url)
        }
    }

    // ---------------- X through the web session (off-screen) ----------------

    @Volatile private var xAt = 0L

    /** [Mines-38.9.2] how an X read went (FAILED = signed in but every page came back empty / unreadable). */
    enum class XRead { SKIPPED, OK, EMPTY, TIMEOUT, FAILED }

    /** Mentions + followed keywords. Background: at most every 10 minutes; a pull passes maxAgeMs = 0 and a deadline. */
    suspend fun readX(context: Context, maxAgeMs: Long = 10 * 60_000L, deadlineMs: Long = Long.MAX_VALUE): XRead {
        if (!xSignedIn()) return XRead.SKIPPED
        if (System.currentTimeMillis() - xAt < maxAgeMs) return XRead.SKIPPED
        xAt = System.currentTimeMillis()
        val out = ArrayList<Digest.Item>()
        val pages = listOf("https://x.com/notifications/mentions" to "@你") +
            Digest.follows(context).take(2).map { q -> "https://x.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&f=live" to q }
        var timedOut = false; var read = 0
        for ((u, q) in pages) {
            val left = deadlineMs - System.currentTimeMillis()
            if (left < 3_000L) { timedOut = true; break }
            val json = withTimeoutOrNull(minOf(30_000L, left)) {
                HiddenWeb.read(context, u, DigestX.EXTRACT_JS, timeoutMs = minOf(28_000L, left - 1_000L), settleMs = 1500, until = { it.length > 10 && it != "[]" })
            }
            if (json == null) { if (System.currentTimeMillis() >= deadlineMs - 500) timedOut = true; continue }
            read++
            out += DigestX.parse(json, q)
        }
        if (out.isEmpty()) return when { timedOut && read == 0 -> XRead.TIMEOUT; read == 0 -> XRead.FAILED; else -> XRead.EMPTY }
        DigestX.store(out.distinctBy { it.id })
        return XRead.OK
    }

    // ---------------- balances through console sessions ----------------

    private val extraBilling = mapOf(
        "aliyun" to "https://usercenter2.aliyun.com/finance/fund-management/overview",
        "tencentcloud" to "https://console.cloud.tencent.com/expense/overview",
        "digitalocean" to "https://cloud.digitalocean.com/account/billing",
    )

    /**
     * For every service with a billing page whose console the user is signed in to in
     * Mines but whose balance has no API (or no key): read the page off-screen and keep
     * the figure. At most 4 pages per pass, each service at most every 6 hours.
     */
    suspend fun sessionBalances(context: Context) {
        PlusVault.init(context)
        val cm = withContext(Dispatchers.Main) { runCatching { CookieManager.getInstance() }.getOrNull() } ?: return
        val now = System.currentTimeMillis()
        val todo = (KeyBalance.billingUrl + extraBilling).filter { (base, url) ->
            val hasKeyBalance = KeyBalance.hasApi(base) && PlusVault.accountsOf(base).any { PlusVault.secret(it) != null }
            if (hasKeyBalance) return@filter false
            val id = PlusVault.defaultAccount(base) ?: base
            if (now - (PlusVault.extra(id, "balance_try")?.toLongOrNull() ?: 0L) < 6 * 3_600_000L) return@filter false
            val c = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
            c.split(';').count { it.contains('=') } >= 3 // a console that has seen a login
        }.toList().take(4)
        for ((base, url) in todo) {
            val id = PlusVault.defaultAccount(base) ?: base
            PlusVault.setExtra(id, "balance_try", now.toString())
            val text = HiddenWeb.text(context, url, 25_000L) ?: continue
            val bal = MailParse.balance(text) ?: continue
            PlusVault.setExtra(id, "balance", "余额 $bal（网页）")
            PlusVault.setExtra(id, "balance_low", if ((bal.drop(1).replace(",", "").toDoubleOrNull() ?: 99.0) < 2.0) "1" else "0")
            PlusVault.setExtra(id, "balance_at", now.toString())
            PlusVault.setExtra(id, "balance_via", "web")
        }
        _active.value = list(context)
    }
}

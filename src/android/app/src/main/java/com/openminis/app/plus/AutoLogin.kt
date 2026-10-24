package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.12] 账号自动调用 (Android side of [AutoLoginCore]). Called by [AgentLogin] before the 台账 password path.
 *
 * Fix for the user's report (「GitHub 关联了、能自动登录，还让我输密码」): each conversation's agent browser has its own
 * WebView profile since 38.9.11.2, so it never saw the GitHub / Google session the user signed in with inside Mines, and
 * an account recorded as 「通过 Google 登录」 has no password → the password card popped. Now:
 *  - the site's cookies Mines already holds (default jar, else the encrypted snapshot of the last good sign-in) are copied
 *    into the conversation's profile and the page reloads — signed in, nothing asked;
 *  - for an OAuth account the provider's cookies are copied too and the page's 「Continue with Google」 is pressed; on
 *    Google's account chooser the recorded account is picked;
 *  - only then the 台账 password (never shown), and the card last.
 * Cookie values never reach the agent or the log.
 */
object AutoLogin {
    private const val PREFS = "mines_autologin"
    private val tried = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 180_000L

    data class Policy(val autoUse: Boolean = true, val codeAutoRead: Boolean = true, val payAsk: Boolean = true, val exchangeReadOnly: Boolean = true)

    private val _policy = MutableStateFlow(Policy())
    val policy: StateFlow<Policy> = _policy.asStateFlow()
    private val _log = MutableStateFlow<List<AutoLoginCore.LogLine>>(emptyList())
    val log: StateFlow<List<AutoLoginCore.LogLine>> = _log.asStateFlow()
    /** the last sign-in Mines handled (for the 账号自动调用 progress card) */
    data class Live(val site: String, val steps: List<Pair<String, Boolean>>, val at: Long)
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()
    @Volatile private var loaded = false

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (loaded) return
        loaded = true
        val sp = p(c)
        val hv = runCatching { HumanVerify.init(c); HumanVerify.settings.value.otpAutofill }.getOrDefault(true)
        _policy.value = Policy(sp.getBoolean("auto", true), hv, true, sp.getBoolean("exro", true))
        _log.value = runCatching {
            val a = JSONArray(sp.getString("log", "[]")!!)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                AutoLoginCore.LogLine(o.optLong("at"), o.optString("s"), o.optString("h"), o.optBoolean("a", true), if (o.has("ok")) o.optBoolean("ok") else null) } }
        }.getOrDefault(emptyList())
    }

    fun setAutoUse(c: Context, on: Boolean) { init(c); _policy.value = _policy.value.copy(autoUse = on); p(c).edit().putBoolean("auto", on).apply() }
    fun setExchangeReadOnly(c: Context, on: Boolean) { init(c); _policy.value = _policy.value.copy(exchangeReadOnly = on); p(c).edit().putBoolean("exro", on).apply() }
    fun setCodeAutoRead(c: Context, on: Boolean) {
        init(c); _policy.value = _policy.value.copy(codeAutoRead = on)
        runCatching { HumanVerify.update(c) { it.copy(otpAutofill = on) } }
    }

    private fun addLog(c: Context, l: AutoLoginCore.LogLine) {
        val n = AutoLoginCore.push(_log.value, l); _log.value = n
        val a = JSONArray(); n.forEach { x -> a.put(JSONObject().put("at", x.at).put("s", x.site).put("h", x.how).put("a", x.auto).apply { x.ok?.let { put("ok", it) } }) }
        runCatching { p(c).edit().putString("log", a.toString()).apply() }
    }

    /** a sign-in Mines handled finished (the password form went away) — marks the last log line for [host] as a success */
    fun markOk(c: Context, host: String) {
        init(c)
        val site = Keychain.root(host).ifBlank { host }
        val l = _log.value.firstOrNull() ?: return
        if (l.site == site && l.ok == null && System.currentTimeMillis() - l.at < RETRY_MS) {
            addLog(c, l.copy(ok = true, at = System.currentTimeMillis()))
            _live.value = _live.value?.takeIf { it.site == site }?.let { it.copy(steps = it.steps + ("已登录，继续执行任务" to true)) }
        }
    }

    private fun urls(site: String, host: String) = listOf("https://$site/", "https://www.$site/", "https://$host/").distinct()

    /** cookies Mines holds for the site: default jar first, else the saved snapshot (url → raw cookie line) */
    private fun sourceCookies(c: Context, site: String, host: String, snapshot: Map<String, String> = emptyMap()): Map<String, String> {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val out = LinkedHashMap<String, String>()
        if (cm != null) urls(site, host).forEach { u -> runCatching { cm.getCookie(u) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { out[u] = it } }
        if (out.isEmpty()) out.putAll(snapshot)
        return out
    }

    private fun names(line: String): Set<String> = line.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }.toSet()

    /** the conversation profile's cookie jar (null = the agent browser uses the default jar, nothing to copy) */
    private fun convJar(sid: String?): CookieManager? {
        val name = sid?.let { com.openminis.app.browser.ConvProfiles.profiles()[it] } ?: return null
        return runCatching { androidx.webkit.ProfileStore.getInstance().getProfile(name)?.cookieManager }.getOrNull()
    }

    /** copy [src] into [dst]; returns how many cookie names were new there */
    private fun copy(dst: CookieManager, src: Map<String, String>): Int {
        var added = 0
        src.forEach { (u, line) ->
            val have = names(runCatching { dst.getCookie(u) }.getOrNull().orEmpty())
            line.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { kv ->
                if (kv.substringBefore('=') !in have) added++
                runCatching { dst.setCookie(u, "$kv; path=/; secure") }
            }
        }
        runCatching { dst.flush() }
        return added
    }

    /** would a copy bring anything new into this conversation's browser? */
    private fun missing(dst: CookieManager?, src: Map<String, String>): Boolean {
        if (src.isEmpty()) return false
        if (dst == null) return false
        return src.any { (u, line) -> (names(line) - names(runCatching { dst.getCookie(u) }.getOrNull().orEmpty())).isNotEmpty() }
    }

    private fun recordedMethod(c: Context, host: String): String {
        val memo = runCatching { LoginMemory.memoFor(c, host) }.getOrNull()
        val rec = runCatching { IdentityStore.recFor(c, host) }.getOrNull()
        return AutoLoginCore.methodId(memo?.method?.id, rec?.provider?.id, rec?.method == IdentityCore.Method.OAUTH)
    }

    /**
     * Runs on a sign-in page that wants a password (before the 台账 / card path). Returns the agent note when it acted,
     * null to let [AgentLogin] continue with the 台账 password / card.
     */
    suspend fun tryBefore(c: Context, url: String, sid: String?, hasLedgerPassword: Boolean, eval: suspend (String) -> String?): String? {
        init(c)
        val host = Keychain.hostOf(url).ifBlank { return null }
        val site = Keychain.root(host).ifBlank { host }
        val siteName = AutoAccounts.siteOf(host).ifBlank { site }
        val now = System.currentTimeMillis()
        val k = (sid ?: "-") + "|" + site
        val method = recordedMethod(c, host)
        // the encrypted snapshot is decrypted off the main thread (Android Keystore can take seconds on this phone)
        val snap = withContext(Dispatchers.IO) { runCatching { LoginMemory.snapshotCookies(c, host) }.getOrDefault(emptyMap()) }
        val (src, dst) = withContext(Dispatchers.Main) { sourceCookies(c, site, host, snap) to convJar(sid) }
        val sessionAvailable = withContext(Dispatchers.Main) { missing(dst, src) }
        val buttons = if (AutoLoginCore.isOAuth(method)) runCatching {
            val o = JSONObject(eval(LoginAssist.OFFERS_JS) ?: "{}"); val a = o.optJSONArray("buttons") ?: JSONArray()
            (0 until a.length()).map { a.optString(it) }
        }.getOrDefault(emptyList()) else emptyList()
        val btn = AutoLoginCore.matchProvider(buttons, method)
        val act = AutoLoginCore.decide(AutoLoginCore.In(method, sessionAvailable, tried["s|$k"]?.let { now - it < RETRY_MS } == true,
            btn, tried["p|$k"]?.let { now - it < RETRY_MS } == true, hasLedgerPassword, _policy.value.autoUse))
        val ro = if (_policy.value.exchangeReadOnly && AutoLoginCore.isExchange(host)) "（交易所账号只读：只查看，不下单、不划转、不提现）" else ""
        val match = "台账匹配：$siteName" + when {
            AutoLoginCore.isOAuth(method) -> " · 登录方式 " + AutoLoginCore.howLabel(AutoLoginCore.Act.PROVIDER, method).removeSuffix(" 登录")
            method == "password" -> " · 登录方式 密码"
            else -> ""
        }
        when (act) {
            AutoLoginCore.Act.REUSE_SESSION -> {
                tried["s|$k"] = now
                val n = withContext(Dispatchers.Main) { if (dst != null) copy(dst, src) else 0 }
                if (n <= 0) return null
                if (AutoLoginCore.isOAuth(method)) withContext(Dispatchers.Main) {
                    AutoLoginCore.providerHosts(method).flatMap { h -> sourceCookies(c, Keychain.root(h).ifBlank { h }, h).entries }.associate { it.key to it.value }
                        .let { pc -> if (dst != null && pc.isNotEmpty()) copy(dst, pc); Unit }
                }
                runCatching { eval("(function(){location.reload();return 'ok';})()") }
                _live.value = Live(site, listOf(match to true, "复用已保存的登录会话，无需密码" to true), now)
                addLog(c, AutoLoginCore.LogLine(now, site, AutoLoginCore.howLabel(act, method), true))
                return "[登录] Mines 把已保存的 $siteName 登录会话放进了这个对话的浏览器并刷新页面（不需要密码，内容不给你看）$ro。等页面加载完截图确认；还在登录页就再操作一次，Mines 会换下一种方式。"
            }
            AutoLoginCore.Act.PROVIDER -> {
                tried["p|$k"] = now
                withContext(Dispatchers.Main) {
                    val pc = AutoLoginCore.providerHosts(method).flatMap { h -> sourceCookies(c, Keychain.root(h).ifBlank { h }, h).entries }.associate { it.key to it.value }
                    if (dst != null && pc.isNotEmpty()) copy(dst, pc)
                    Unit
                }
                val r = runCatching { eval(LoginAssist.clickJs(btn!!)) }.getOrNull()
                if (r != "clicked") return null
                val how = AutoLoginCore.howLabel(act, method)
                _live.value = Live(site, listOf(match to true, "用 $how，复用浏览器里的账号会话，无需密码" to true), now)
                addLog(c, AutoLoginCore.LogLine(now, site, how, true))
                return "[登录] $siteName 这个账号是「$how」的，Mines 已点了页面上的「$btn」（不需要密码）$ro。等跳转完成截图确认；出现账号选择页时 Mines 会自动选。"
            }
            else -> return null
        }
    }

    /** the 台账 password was filled (AgentLogin) — logged for the 最近调用 list */
    fun notePassword(c: Context, host: String) {
        init(c)
        val site = Keychain.root(host).ifBlank { host }
        val now = System.currentTimeMillis()
        _live.value = Live(site, listOf("台账匹配：" + AutoAccounts.siteOf(host).ifBlank { site } to true, "会话已失效，台账密码已填入（不显示明文）" to true), now)
        addLog(c, AutoLoginCore.LogLine(now, site, "密码", true))
    }

    /** Google's account chooser in the agent browser: pick the recorded account (or the first signed-in one) */
    suspend fun onChooser(c: Context, url: String, eval: suspend (String) -> String?): String? {
        val host = Keychain.hostOf(url)
        if (host != "accounts.google.com") return null
        val acct = runCatching { IdentityStore.current(c).firstOrNull { it.type == IdentityCore.IdType.GOOGLE || it.value.endsWith("@gmail.com") }?.value }.getOrNull().orEmpty()
        val r = runCatching { eval(LoginAssist.googlePickJs(acct)) }.getOrNull()
        return when (r) {
            "picked" -> "[登录] Google 账号选择页：Mines 已选了" + (if (acct.isNotBlank()) " ${AccountsCore.maskPhone(acct)}" else "已登录的账号") + "。"
            "continue" -> "[登录] Google 授权页：Mines 已点「继续」。"
            else -> null
        }
    }
}

package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Rule-based split proxy (分流) for the sandbox and the app.
 *
 * A small HTTP proxy on 127.0.0.1:[PORT] decides per host:
 *   代理 (PROXY)  → through the user's own upstream proxy (HTTP or SOCKS5);
 *   直连 (DIRECT) → straight out;
 *   自动 (AUTO)   → try direct first, fall back to the proxy when the host
 *                   can't be reached, and remember that for 30 minutes.
 * Rules are domain suffixes; presets cover Google/Gemini (proxy) and Chinese
 * sites (direct). No upstream server is pre-filled: until the user enters
 * one, 代理 traffic goes direct and the page says so.
 *
 * When on, the sandbox gets http(s)_proxy pointing here and the app's
 * default ProxySelector routes through it (WebView is not covered).
 */
object SplitProxy {
    private const val TAG = "SplitProxy"
    const val PORT = 7899
    private const val FILE = "mines_split_proxy"
    private const val AUTO_TTL_MS = 30 * 60_000L

    enum class Mode(val label: String) { PROXY("代理"), DIRECT("直连"), AUTO("自动") }

    data class Rule(val pattern: String, val mode: Mode)

    data class Settings(
        val enabled: Boolean = false,
        val upstreamType: String = "http",   // "http" | "socks5"
        val upstreamHost: String = "",
        val upstreamPort: Int = 0,
        val defaultMode: Mode = Mode.AUTO,
        val rules: List<Rule> = emptyList(),
        val forSandbox: Boolean = true,
        val forApp: Boolean = true,
        // [Mines-proxy-auto] Backup proxies + periodic health checks; when the
        // current one stops working traffic moves to the next healthy one.
        val backups: List<Upstream> = emptyList(),
        val autoSwitch: Boolean = true,
        // [Mines-38.9.6 i12] user 2026-10-18: an authenticated HTTP proxy (HK :24445) never worked — Mines sent CONNECT / the
        // forwarded request with NO Proxy-Authorization, got 407, marked the proxy dead and went direct. User name in prefs,
        // password in PlusVault (Keystore AES-GCM), never logged.
        val upstreamUser: String = "",
        val upstreamPass: String = "",
    ) {
        val primary: Upstream? get() = Upstream(upstreamType, upstreamHost.trim(), upstreamPort, user = upstreamUser.trim(), pass = upstreamPass).takeIf { it.valid }
        /** Primary first, then backups, no duplicates. */
        /** [38.9.10] one entry per proxy: the same host:port (localhost = 127.0.0.1, HTTP or SOCKS5) is listed once */
        val all: List<Upstream> get() = (listOfNotNull(primary) + backups.filter { it.valid }).distinctBy { SplitProxyCore.sameProxyKey(it.host, it.port) }
        val hasUpstream: Boolean get() = all.isNotEmpty()
    }

    data class Upstream(val type: String, val host: String, val port: Int, val label: String = "", val user: String = "", val pass: String = "") {
        val valid: Boolean get() = host.isNotBlank() && port in 1..65535
        val key: String get() = "$type://$host:$port"
        /** "Proxy-Authorization: Basic …\r\n" or "" (HTTP upstreams with a user) */
        val authHeader: String get() = SplitProxyCore.authLine(user, pass)
        override fun toString(): String = "Upstream($type://$host:$port${if (user.isNotEmpty()) " auth" else ""})"   // never the password
    }

    /** Health of one upstream: latency in ms, or null when the check failed. */
    data class Health(val ok: Boolean, val ms: Long?, val at: Long, val error: String? = null)

    data class Preset(val name: String, val mode: Mode, val domains: List<String>)

    val presets = listOf(
        Preset("Google / Gemini 走代理", Mode.PROXY, listOf("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "googlevideo.com", "youtube.com", "ytimg.com", "ggpht.com", "gvt1.com", "gemini.google.com", "aistudio.google.com")),
        Preset("OpenAI / Claude / GitHub 走代理", Mode.PROXY, listOf("openai.com", "chatgpt.com", "oaistatic.com", "anthropic.com", "claude.ai", "github.com", "githubusercontent.com", "githubassets.com")),
        Preset("国内网站直连", Mode.DIRECT, listOf(".cn", "baidu.com", "qq.com", "weixin.qq.com", "aliyun.com", "aliyuncs.com", "alicdn.com", "alipay.com", "taobao.com", "tmall.com", "jd.com", "bilibili.com", "hdslb.com", "163.com", "126.com", "deepseek.com", "xiaohongshu.com", "douyin.com", "bytedance.com", "volces.com", "mi.com", "huawei.com", "okx.com", "codebuddy.ai", "workbuddy.ai")),
    )

    data class Hit(val host: String, val route: String, val at: Long)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _recent = MutableStateFlow<List<Hit>>(emptyList())
    val recent: StateFlow<List<Hit>> = _recent.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health.asStateFlow()
    /** Key of the upstream traffic currently goes through (auto-switch). */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    @Volatile private var checker: Thread? = null

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "split-proxy").apply { isDaemon = true } }
    /** host → (useProxy, decidedAt) for AUTO decisions. */
    private val autoCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    @Volatile private var previousSelector: ProxySelector? = null

    // [38.9.11.1] only while a real request through 127.0.0.1:7899 passed (LocalProxy probe); listening alone is not enough
    val active: Boolean get() = _settings.value.enabled && _running.value && LocalProxy.usable(PORT)
    /** [38.9.11.1] re-apply the WebView override after a local-proxy probe changed what works */
    fun reapplyWeb() { val s = _settings.value; if (s.enabled && _running.value) WebProxy.apply(s.forApp) else if (SandboxNet.strict) WebProxy.apply(false) }   // [38.9.11.3] strict: block instead of direct
    fun webOn(): Boolean = _settings.value.let { it.enabled && it.forApp } && _running.value

    fun init(context: Context) {
        ctxRef = context.applicationContext
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            _settings.value = read(p)
            // [38.9.14.2] the upstream password sits in the vault, which no longer opens on the main thread at start-up:
            // fill it in as soon as the vault is open
            if (_settings.value.upstreamUser.isNotBlank() && _settings.value.upstreamPass.isEmpty()) PlusVault.onSecretsReady {
                val pw = runCatching { PlusVault.secretExtra("splitproxy", "upass").orEmpty() }.getOrDefault("")
                if (pw.isNotEmpty()) { _settings.value = _settings.value.copy(upstreamPass = pw); _current.value = null; autoCache.clear() }
            }
            installSelector()
            if (_settings.value.enabled) start()
        }
    }

    private fun read(p: SharedPreferences): Settings {
        val rules = runCatching {
            val a = JSONArray(p.getString("rules", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Rule(it.getString("p"), Mode.valueOf(it.getString("m"))) } }
        }.getOrDefault(emptyList())
        return Settings(
            enabled = p.getBoolean("enabled", false),
            upstreamType = p.getString("utype", "http") ?: "http",
            upstreamHost = p.getString("uhost", "") ?: "",
            upstreamPort = p.getInt("uport", 0),
            defaultMode = runCatching { Mode.valueOf(p.getString("default", "AUTO")!!) }.getOrDefault(Mode.AUTO),
            rules = rules,
            forSandbox = p.getBoolean("sandbox", true),
            forApp = p.getBoolean("app", true),
            backups = runCatching {
                val a = JSONArray(p.getString("backups", "[]"))
                (0 until a.length()).map { i -> a.getJSONObject(i).let { Upstream(it.optString("t", "http"), it.optString("h"), it.optInt("p"), it.optString("l")) } }
            }.getOrDefault(emptyList()),
            autoSwitch = p.getBoolean("autoswitch", true),
            upstreamUser = p.getString("uuser", "") ?: "",
            upstreamPass = runCatching { ctxRef?.let { PlusVault.init(it) }; PlusVault.secretExtra("splitproxy", "upass").orEmpty() }.getOrDefault(""),
        )
    }

    fun update(context: Context, block: (Settings) -> Settings) {
        init(context)
        val n = block(_settings.value)
        _settings.value = n
        // [Mines-38.9.6 i14] the Clash config generated from 自己填代理 follows every change
        if (ClashCore.enabledNow) Thread({ runCatching { ClashCore.restart(context.applicationContext) } }, "mines-clash-reload").start()
        val rules = JSONArray().apply { n.rules.forEach { put(JSONObject().put("p", it.pattern).put("m", it.mode.name)) } }
        prefs?.edit()
            ?.putBoolean("enabled", n.enabled)?.putString("utype", n.upstreamType)?.putString("uhost", n.upstreamHost.trim())
            ?.putInt("uport", n.upstreamPort)?.putString("default", n.defaultMode.name)?.putString("rules", rules.toString())
            ?.putBoolean("sandbox", n.forSandbox)?.putBoolean("app", n.forApp)
            ?.putString("backups", JSONArray().apply { n.backups.forEach { put(JSONObject().put("t", it.type).put("h", it.host).put("p", it.port).put("l", it.label)) } }.toString())
            ?.putBoolean("autoswitch", n.autoSwitch)?.putString("uuser", n.upstreamUser.trim())?.apply()
        runCatching { PlusVault.setSecretExtra("splitproxy", "upass", n.upstreamPass.ifEmpty { null }) }
        _current.value = null
        autoCache.clear()
        if (n.enabled) start() else stop()
        if (n.enabled && _running.value) WebProxy.apply(n.forApp)   // [Mines-38.9.6 i13] 用于 App toggled while running
    }

    fun addPreset(context: Context, preset: Preset) = update(context) { s ->
        val existing = s.rules.associateBy { it.pattern }.toMutableMap()
        preset.domains.forEach { existing[it] = Rule(it, preset.mode) }
        s.copy(rules = existing.values.toList())
    }

    // ── routing decisions ──────────────────────────────────────────────

    fun ruleFor(host: String): Mode = userRuleFor(host) ?: when {
        builtinDirect(host) -> Mode.DIRECT
        else -> _settings.value.defaultMode
    }

    private fun matches(h: String, pattern: String): Boolean {
        val p = pattern.lowercase().trim().removePrefix("*")
        return if (p.startsWith(".")) h.endsWith(p) || h == p.removePrefix(".") else h == p || h.endsWith(".$p")
    }

    /** The user's own rule for this host (longest match), or null. */
    private fun userRuleFor(host: String): Mode? {
        val h = host.lowercase().trimEnd('.')
        var best: Rule? = null
        for (r in _settings.value.rules) {
            if (matches(h, r.pattern) && (best == null || r.pattern.length > best.pattern.length)) best = r
        }
        return best?.mode
    }

    // [Mines-net-26] Built-in routing for package sources, below the user's rules.
    /** Domestic mirrors / CN sites: always direct. */
    private fun builtinDirect(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return SandboxNet.DIRECT_SUFFIXES.any { matches(h, it) }
    }

    /**
     * Foreign package/code hosts that accept TCP from mainland China and then
     * stall or reset TLS. In 自动 they go through a healthy proxy when one is
     * configured instead of being judged by a TCP connect.
     */
    private val FOREIGN_DEV = listOf(
        "pypi.org", "pythonhosted.org", "alpinelinux.org", "npmjs.org", "npmjs.com", "yarnpkg.com", "nodejs.org",
        "github.com", "githubusercontent.com", "githubassets.com", "ghcr.io", "docker.io", "docker.com",
        "crates.io", "golang.org", "go.dev", "rustup.rs", "rust-lang.org", "huggingface.co", "gradle.org", "maven.org",
        "googleapis.com", "google.com", "gstatic.com",
        // [Mines-38.9.6 i13] Gmail sign-in / 验证码 pages and the 自检 egress probe always use the proxy in 自动
        "gmail.com", "googlemail.com", "googleusercontent.com", "api.ipify.org",
    )
    private fun builtinForeign(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return FOREIGN_DEV.any { matches(h, it) }
    }

    /**
     * 自动: is the direct path really usable? For 443 a full TLS handshake
     * (with SNI and certificate check), because a bare TCP connect succeeds
     * even where the TLS handshake is reset or stalls.
     */
    private fun directUsable(host: String, port: Int): Boolean = runCatching {
        val raw = direct(host, port, 2500)
        if (port != 443) { raw.close(); return@runCatching true }
        raw.soTimeout = 4000
        val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        (f.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
        true
    }.getOrDefault(false)

    private fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".local")) return true
        return Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|127\\.)").containsMatchIn(h)
    }

    // ── app-wide ProxySelector ─────────────────────────────────────────

    private fun installSelector() {
        if (previousSelector != null) return
        val prev = ProxySelector.getDefault()
        previousSelector = prev
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): MutableList<Proxy> {
                val host = uri?.host
                val s = _settings.value
                if (s.enabled && s.forApp && host != null && !isLocal(host)) {
                    // [38.9.11.1] 7899 only while it really works; else a local proxy that passed (e.g. the Clash app's 7890); else direct
                    if (_running.value && LocalProxy.usable(PORT)) return mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", PORT)))
                    LocalProxy.best?.let { return mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))) }
                    return mutableListOf(Proxy.NO_PROXY)
                }
                return prev?.select(uri) ?: mutableListOf(Proxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                if ((sa as? InetSocketAddress)?.address?.isLoopbackAddress == true) LocalProxy.reportFailure()   // [38.9.11.1]
                prev?.connectFailed(uri, sa, ioe)
            }
        })
    }

    /** Env block for the sandbox (null = leave the system proxy alone). */
    fun sandboxEnv(): Map<String, String>? {
        val s = _settings.value
        if (!s.enabled || !s.forSandbox) return null
        // [Mines-net-26] Never point the sandbox at a port nobody listens on.
        if (!_running.value || server?.isClosed != false) return null
        // [38.9.11.1] listening is not enough: a split proxy whose upstream is dead (user's phone: 0/5) is not exported
        if (!LocalProxy.usable(PORT)) return null
        val url = "http://127.0.0.1:$PORT"
        return linkedMapOf(
            "http_proxy" to url, "https_proxy" to url, "HTTP_PROXY" to url, "HTTPS_PROXY" to url,
            // [Mines-38.9.6 i13] ALL_PROXY too (git/ssh-less tools, some curl builds); the local split proxy adds the
            // upstream's Proxy-Authorization itself, so no credential ever reaches the sandbox env
            "all_proxy" to url, "ALL_PROXY" to url,
            "no_proxy" to "localhost,127.0.0.1,::1", "NO_PROXY" to "localhost,127.0.0.1,::1",
        ) + (ctxRef?.let { runCatching { ClashCore.agentEnv(it) }.getOrNull() } ?: emptyMap())   // [Mines-38.9.6 i14] Clash API for the agent
    }

    // ── the proxy server ───────────────────────────────────────────────

    @Synchronized
    private fun start() {
        if (server != null) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 64)
            server = ss
            _running.value = true
            startChecker()
            Thread({
                while (!ss.isClosed) {
                    val c = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.execute { handle(c) }
                }
            }, "split-proxy-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "listening on $PORT")
            WebProxy.apply(_settings.value.forApp)
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            _running.value = false
        }
    }

    @Synchronized
    private fun stop() {
        WebProxy.apply(false)
        runCatching { server?.close() }
        server = null
        _running.value = false
        checker?.interrupt(); checker = null
    }

    // ── [Mines-proxy-auto] health checks + failover ────────────────────

    private const val CHECK_EVERY_MS = 60_000L
    /** [Mines-38.9.6 i14] time of the last failure per proxy (backoff, see SplitProxyCore.backoffMs) */
    private val lastFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** [Mines-38.9.6 i14] a Google host was refused because no proxy worked (shown as 代理不可用, never a silent direct try) */
    private val _down = MutableStateFlow<String?>(null)
    val down: StateFlow<String?> = _down.asStateFlow()

    private fun startChecker() {
        if (checker?.isAlive == true) return
        checker = Thread({
            try {
                var lastFull = 0L
                while (!Thread.currentThread().isInterrupted && server != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastFull >= CHECK_EVERY_MS) { checkAll(); lastFull = System.currentTimeMillis() }
                    else reprobeFailed(now)
                    Thread.sleep(5_000L)   // [Mines-38.9.6 i14] a failed proxy is re-probed as soon as its short pause is over
                }
            } catch (_: InterruptedException) {}
        }, "split-proxy-health").apply { isDaemon = true; start() }
    }

    /** re-checks only the proxies whose backoff pause has ended (background, every 5 s) */
    private fun reprobeFailed(now: Long) {
        val ups = _settings.value.all.filter { u -> val f = fails[u.key] ?: 0; f > 0 && SplitProxyCore.usable(f, lastFailAt[u.key] ?: 0L, now) }
        if (ups.isEmpty()) return
        for (u in ups) noteResult(u, check(u))
        pickCurrent()
    }

    private fun noteResult(u: Upstream, h: Health) {
        _health.value = _health.value + (u.key to h)
        if (h.ok) { fails.remove(u.key); lastFailAt.remove(u.key); _down.value = null }
        else { fails.merge(u.key, 1) { a, b -> a + b }; lastFailAt[u.key] = System.currentTimeMillis() }
    }

    /** Checks every configured upstream once (blocking; call off the main thread). */
    /** [Mines-design-33] consecutive failed checks per proxy (memory only). */
    private val fails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var ctxRef: Context? = null

    fun checkAll() {
        val ups = _settings.value.all
        val m = HashMap<String, Health>()
        for (u in ups) m[u.key] = check(u)
        _health.value = m
        for (u in ups) if (m[u.key]?.ok == true) { fails.remove(u.key); lastFailAt.remove(u.key) } else { fails.merge(u.key, 1) { a, b -> a + b }; lastFailAt[u.key] = System.currentTimeMillis() }
        if (m.values.any { it.ok }) _down.value = null
        pruneDead()
        pickCurrent()
    }

    /**
     * [Mines-design-33] Auto-detected proxies (system proxy / app ports, e.g. the random
     * 127.x ports a VPN app leaves behind) that failed 3 checks in a row are dropped instead
     * of sitting in red; when the primary is such a proxy and a backup works, the working
     * one becomes the primary. Proxies the user typed in are never removed.
     */
    private fun pruneDead() {
        val c = ctxRef ?: return
        val st = _settings.value
        val h = _health.value
        fun auto(u: Upstream) = u.label.isNotBlank()
        fun dead(u: Upstream) = (fails[u.key] ?: 0) >= 3
        val primaryAuto = st.primary?.let { p -> st.backups.firstOrNull { it.key == p.key }?.label?.isNotBlank() == true || PlusVaultLess.isAutoPrimary(c) } == true
        var n = st.copy(backups = st.backups.filterNot { auto(it) && dead(it) && it.key != st.primary?.key })
        val p = n.primary
        if (p != null && dead(p) && primaryAuto) {
            val alt = n.backups.firstOrNull { h[it.key]?.ok == true }
            n = if (alt != null) n.copy(upstreamType = alt.type, upstreamHost = alt.host, upstreamPort = alt.port, backups = n.backups.filter { it.key != alt.key })
                else n
        }
        if (n != st) { Log.i(TAG, "pruned dead auto-detected proxies"); update(c, { n }); fails.keys.retainAll(n.all.map { it.key }.toSet()) }
    }

    /**
     * One upstream: a REAL request through it. [Mines-38.9.10 i28] user 16:29 screenshot: 「Clash HTTP 127.0.0.1:7890 能用 5ms」
     * while it could not reach anything — the old check only opened the CONNECT tunnel, and a local VPN-app port answers
     * 「200 Connection established」 at once (5 ms) even when its own outbound is dead. Now: tunnel → TLS handshake →
     * GET /generate_204 → must answer 204 (www.gstatic.com, then cp.cloudflare.com); latency = the whole round trip.
     */
    fun check(u: Upstream, target: String = "www.gstatic.com"): Health {
        var last: Throwable? = null
        for (host in listOf(target, "cp.cloudflare.com").distinct()) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { probe204(u, host) }
            r.getOrNull()?.let { code ->
                val ms = System.currentTimeMillis() - t0
                return if (code == 204) Health(true, ms, System.currentTimeMillis())
                    else Health(false, null, System.currentTimeMillis(), SplitProxyCore.probeReason(null, code))
            }
            last = r.exceptionOrNull()
        }
        return Health(false, null, System.currentTimeMillis(), SplitProxyCore.probeReason(last?.let { (it.javaClass.simpleName + ": " + (it.message ?: "")) }, 0))
    }

    /** HTTPS GET /generate_204 to [host] through [u]; returns the HTTP status (blocking, ≤ ~8 s) */
    private fun probe204(u: Upstream, host: String): Int {
        val raw = viaUpstream(u, host, 443, tunnel = true, timeout = 5000)
        raw.use { sock ->
            sock.soTimeout = 6000
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(sock, host, 443, true) as javax.net.ssl.SSLSocket
            ssl.use { t ->
                t.soTimeout = 6000
                t.startHandshake()
                t.outputStream.apply {
                    write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mines-proxy-check\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); flush()
                }
                val line = t.inputStream.bufferedReader(Charsets.ISO_8859_1).readLine() ?: throw IOException("closed before a reply")
                return SplitProxyCore.statusCode(line) ?: throw IOException("not HTTP: ${line.take(40)}")
            }
        }
    }

    private fun pickCurrent() {
        val s = _settings.value
        val ups = s.all
        if (ups.isEmpty()) { _current.value = null; return }
        if (!s.autoSwitch) { _current.value = ups.first().key; return }
        val h = _health.value
        val pick = ups.firstOrNull { h[it.key]?.ok == true } ?: ups.first()
        if (_current.value != pick.key) Log.i(TAG, "upstream → ${pick.key}")
        _current.value = pick.key
    }

    /**
     * [Mines-net-26] The upstream to use now, or null when every configured
     * proxy is known to be down: traffic then goes direct instead of into a
     * dead proxy. Unchecked proxies count as usable until the first check.
     */
    private fun activeUpstream(s: Settings): Upstream? {
        // [Mines-38.9.6 i14] the embedded Clash core, when it runs, is the one upstream (it picks / fails over nodes itself)
        ClashCore.upstream()?.let { return it }
        val ups = s.all
        if (ups.isEmpty()) return null
        val h = _health.value
        val now = System.currentTimeMillis()
        // [Mines-38.9.6 i14] a failed proxy is out only for its short backoff pause (5 s … 60 s), then tried again
        fun alive(u: Upstream) = h[u.key]?.ok != false || SplitProxyCore.usable(fails[u.key] ?: 0, lastFailAt[u.key] ?: 0L, now)
        if (!s.autoSwitch) return ups.first().takeIf { alive(it) }
        val key = _current.value
        return ups.firstOrNull { it.key == key && alive(it) } ?: ups.firstOrNull { alive(it) }
    }

    /** Marks a failed upstream and moves to the next one right away. */
    private fun markFailed(u: Upstream, e: Throwable) {
        noteResult(u, Health(false, null, System.currentTimeMillis(), e.message))
        pickCurrent()
    }

    // ── [Mines-proxy-auto] detect proxies on this phone ───────────────

    data class Found(val upstream: Upstream, val source: String)

    private val commonPorts = listOf(
        7890 to "Clash", 7891 to "Clash", 7897 to "Clash Verge", 10808 to "v2rayNG", 10809 to "v2rayNG",
        2080 to "sing-box / NekoBox", 1080 to "SOCKS", 12334 to "Hiddify", 9090 to "代理", 8889 to "代理",
    )

    /**
     * Finds proxies the user already runs: the system HTTP proxy (Wi-Fi or
     * VPN app setting) and common local ports of VPN apps (Clash, v2rayNG,
     * sing-box/NekoBox, Hiddify). Each port is probed for HTTP CONNECT first,
     * then SOCKS5. Blocking; call off the main thread.
     */
    fun detect(context: Context): List<Found> {
        val out = ArrayList<Found>()
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val pi = cm?.defaultProxy ?: cm?.getLinkProperties(cm.activeNetwork)?.httpProxy
            val h = pi?.host
            val pp = pi?.port ?: 0
            if (!h.isNullOrBlank() && pp in 1..65535 && !(isLoopback(h) && pp == PORT)) {
                out += Found(Upstream("http", h, pp, "系统代理"), "系统代理")
            }
        }
        runCatching {
            val h = System.getProperty("http.proxyHost"); val p = System.getProperty("http.proxyPort")?.toIntOrNull()
            if (!h.isNullOrBlank() && p != null && p in 1..65535 && !(isLoopback(h) && p == PORT)) out += Found(Upstream("http", h, p, "系统代理"), "系统代理")
        }
        val seen = out.map { it.upstream.host + ":" + it.upstream.port }.toMutableSet()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = commonPorts.distinctBy { it.first }.map { (port, app) ->
                pool.submit<Found?> { probeLocal(port)?.let { t -> Found(Upstream(t, "127.0.0.1", port, app), app) } }
            }
            for (f in futures) runCatching { f.get(4, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()?.let {
                if (seen.add(it.upstream.host + ":" + it.upstream.port)) out += it
            }
        } finally { pool.shutdownNow() }
        return out.distinctBy { SplitProxyCore.sameProxyKey(it.upstream.host, it.upstream.port) }
    }

    private fun isLoopback(h: String) = h == "127.0.0.1" || h == "localhost" || h == "::1"

    /** "http", "socks5" or null when nothing (usable) listens on 127.0.0.1:[port]. */
    private fun probeLocal(port: Int): String? {
        if (port == PORT) return null
        // HTTP CONNECT
        runCatching {
            Socket(Proxy.NO_PROXY).use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 400); s.soTimeout = 1500
                s.getOutputStream().apply { write("CONNECT www.gstatic.com:443 HTTP/1.1\r\nHost: www.gstatic.com:443\r\n\r\n".toByteArray()); flush() }
                val b = ByteArray(16); val n = s.getInputStream().read(b)
                if (n >= 8 && String(b, 0, n, Charsets.ISO_8859_1).startsWith("HTTP/")) return "http"
            }
        }
        // SOCKS5 greeting (no auth)
        runCatching {
            Socket(Proxy.NO_PROXY).use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 400); s.soTimeout = 1500
                s.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                val b = ByteArray(2); val n = s.getInputStream().read(b)
                if (n == 2 && b[0] == 5.toByte() && b[1] == 0.toByte()) return "socks5"
            }
        }
        return null
    }

    private fun record(host: String, route: String) {
        val l = (listOf(Hit(host, route, System.currentTimeMillis())) + _recent.value.filter { it.host != host }).take(30)
        _recent.value = l
    }

    private fun readHead(inp: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (buf.size() < 64 * 1024) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) return buf.toByteArray()
        }
        return null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            runCatching {
                c.soTimeout = 60_000
                val inp = c.getInputStream()
                val head = readHead(inp) ?: return
                val text = String(head, Charsets.ISO_8859_1)
                val first = text.substringBefore("\r\n")
                val parts = first.split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val target = parts[1]
                val (host, port) = if (method.equals("CONNECT", true)) {
                    val hp = target.substringBeforeLast(":") to (target.substringAfterLast(":").toIntOrNull() ?: 443)
                    hp.first.removePrefix("[").removeSuffix("]") to hp.second
                } else {
                    val u = runCatching { URI(target) }.getOrNull() ?: return
                    (u.host ?: return) to (if (u.port > 0) u.port else 80)
                }
                val leg = open(host, port, method.equals("CONNECT", true)) ?: run {
                    c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
                }
                if (leg.down || leg.sock == null) {
                    c.getOutputStream().write(SplitProxyCore.downResponse().toByteArray(Charsets.UTF_8))
                    c.getOutputStream().write(SplitProxyCore.DOWN_BODY.toByteArray(Charsets.UTF_8)); c.getOutputStream().flush(); return
                }
                leg.sock.use { up ->
                    val uo = up.getOutputStream()
                    if (method.equals("CONNECT", true)) {
                        c.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    } else if (up.getTag() == "http-proxy") {
                        // absolute-form to the upstream proxy (+ its Proxy-Authorization, [Mines-38.9.6])
                        val auth = auths.remove(up)
                        uo.write(if (auth == null) head else SplitProxyCore.withAuth(text, auth).toByteArray(Charsets.ISO_8859_1))
                    } else {
                        val u = URI(target)
                        val origin = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
                        val rewritten = text.replaceFirst(first, "$method $origin ${parts[2]}")
                            .replace(Regex("(?im)^proxy-connection:.*\\r\\n"), "")
                        uo.write(rewritten.toByteArray(Charsets.ISO_8859_1))
                    }
                    uo.flush()
                    // [Mines-net-26] No more endless tunnels: both legs read with a
                    // short timeout and the tunnel closes after IDLE_MS without a byte
                    // in either direction, so a stalled download ends with an error.
                    up.soTimeout = 15_000; c.soTimeout = 15_000
                    val act = Activity()
                    val t = Thread({ pipe(up.getInputStream(), c.getOutputStream(), act, act.down); runCatching { c.shutdownOutput() } }, "split-proxy-down")
                    t.isDaemon = true; t.start()
                    pipe(inp, uo, act, act.up)
                    runCatching { up.shutdownOutput() }
                    t.join(IDLE_MS)
                    // 自动 picked direct but almost nothing came back (reset / stalled
                    // TLS): use the proxy for this host next time.
                    if (leg.autoDirect && act.up.get() > 0 && (act.down.get() < 1024 || act.idled)) {
                        autoCache[host] = true to System.currentTimeMillis()
                        record(host, "自动→直连不通，下次走代理")
                    }
                }
            }
        }
    }

    private val tags = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Socket, String>())
    private fun Socket.getTag(): String? = tags[this]
    private val auths = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Socket, String>())

    private const val IDLE_MS = 180_000L

    private class Activity {
        @Volatile var last = System.currentTimeMillis()
        @Volatile var idled = false
        val up = java.util.concurrent.atomic.AtomicLong()
        val down = java.util.concurrent.atomic.AtomicLong()
    }

    private fun pipe(i: InputStream, o: OutputStream, act: Activity, counter: java.util.concurrent.atomic.AtomicLong) {
        val b = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = try { i.read(b) } catch (_: java.net.SocketTimeoutException) {
                    if (System.currentTimeMillis() - act.last > IDLE_MS) { act.idled = true; break } else continue
                }
                if (n < 0) break
                o.write(b, 0, n); o.flush()
                act.last = System.currentTimeMillis(); counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {}
    }

    /** Opens the outbound leg according to the rules. */
    private class Leg(val sock: Socket?, val autoDirect: Boolean, val down: Boolean = false)
    /** [Mines-38.9.6 i14] a Google host with no working proxy: answer 代理不可用 at once (a direct try would just hang) */
    private fun googleDown(host: String, why: String): Leg {
        record(host, "代理不可用（$why，Google 不直连）"); _down.value = host
        return Leg(null, false, down = true)
    }

    private fun open(host: String, port: Int, tunnel: Boolean): Leg? {
        val s = _settings.value
        val mode = if (isLocal(host)) Mode.DIRECT else ruleFor(host)
        // [Mines-net-26] Only a proxy that is alive counts; a dead one means direct.
        val alive = activeUpstream(s)
        var autoDirect = false
        val useProxy = when (mode) {
            Mode.DIRECT -> false
            Mode.PROXY -> alive != null
            Mode.AUTO -> when {
                alive == null -> false
                builtinForeign(host) -> true
                else -> {
                    val cached = autoCache[host]?.takeIf { System.currentTimeMillis() - it.second < AUTO_TTL_MS }
                    if (cached != null) cached.first
                    else {
                        val ok = directUsable(host, port)
                        autoCache[host] = !ok to System.currentTimeMillis()
                        !ok
                    }
                }
            }
        }
        if (mode == Mode.AUTO && !useProxy) autoDirect = alive != null
        if (!useProxy && mode != Mode.DIRECT && (s.hasUpstream || ClashCore.enabledNow) && alive == null && SplitProxyCore.isGoogle(host)) return googleDown(host, "代理连不上")
        return runCatching {
            if (!useProxy) {
                record(host, when {
                    mode == Mode.DIRECT -> "直连"
                    !s.hasUpstream -> "${mode.label}→直连（未设置代理服务器）"
                    alive == null -> "${mode.label}→直连（代理连不上）"
                    else -> "${mode.label}→直连"
                })
                Leg(direct(host, port, 10_000), autoDirect)
            } else {
                val u = alive ?: return@runCatching Leg(direct(host, port, 10_000), false)
                record(host, "${mode.label}→代理 ${u.host}:${u.port}")
                try {
                    Leg(viaUpstream(u, host, port, tunnel), false)
                } catch (e: Exception) {
                    // Fail over to the next healthy proxy, or direct when none is left.
                    markFailed(u, e)
                    val next = activeUpstream(_settings.value)
                    if ((next == null || next.key == u.key) && SplitProxyCore.isGoogle(host)) googleDown(host, "代理 ${u.host}:${u.port} 失败")
                    else if (next == null || next.key == u.key) {
                        record(host, "${mode.label}→代理 ${u.host}:${u.port} 失败，改直连")
                        Leg(direct(host, port, 10_000), false)
                    } else {
                        record(host, "${mode.label}→备用代理 ${next.host}:${next.port}")
                        Leg(viaUpstream(next, host, port, tunnel), false)
                    }
                }
            }
        }.getOrNull()
    }

    /** [Mines-38.9.6 i17] the proxy in use now (Clash core / user's upstream), null when none is set or all are down */
    fun activeUpstreamNow(): Upstream? = activeUpstream(_settings.value)

    /** [Mines-38.9.6 i17] a raw tunnel to host:port through [u] (ReachProbe: "走代理" path of the server check) */
    fun tunnelVia(u: Upstream, host: String, port: Int, timeout: Int = 6_000): Socket = viaUpstream(u, host, port, tunnel = true, timeout = timeout)

    private fun direct(host: String, port: Int, timeout: Int): Socket =
        Socket(Proxy.NO_PROXY).apply { tcpNoDelay = true; keepAlive = true; connect(InetSocketAddress(host, port), timeout) }

    // [Mines-38.9.6 i14] 6 s to reach the proxy, 8 s for its CONNECT answer (was 10 s + 10 s: the WebView sat on a dead proxy)
    private fun viaUpstream(u: Upstream, host: String, port: Int, tunnel: Boolean, timeout: Int = 6_000): Socket {
        if (u.type == "socks5") {
            val sock = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(u.host, u.port)))
            sock.connect(InetSocketAddress.createUnresolved(host, port), timeout)
            return sock
        }
        val sock = Socket(Proxy.NO_PROXY).apply { tcpNoDelay = true; keepAlive = true; connect(InetSocketAddress(u.host, u.port), timeout) }
        if (tunnel) sock.soTimeout = maxOf(timeout, 8_000)
        if (!tunnel) { tags[sock] = "http-proxy"; if (u.user.isNotEmpty()) auths[sock] = u.authHeader; return sock }
        val o = sock.getOutputStream()
        o.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n${u.authHeader}\r\n".toByteArray(Charsets.ISO_8859_1))
        o.flush()
        val head = readHead(sock.getInputStream()) ?: throw IOException("upstream closed")
        val status = String(head, Charsets.ISO_8859_1).substringBefore("\r\n")
        if (!status.contains(" 200")) { sock.close(); throw IOException("upstream refused: $status") }
        sock.soTimeout = 0
        return sock
    }
}


/** [Mines-design-33] whether the primary proxy was filled by 一键检测 (not typed by the user). */
internal object PlusVaultLess {
    fun isAutoPrimary(c: Context): Boolean = c.getSharedPreferences("mines_split_proxy_meta", Context.MODE_PRIVATE).getBoolean("primary_auto", false)
    fun setAutoPrimary(c: Context, v: Boolean) = c.getSharedPreferences("mines_split_proxy_meta", Context.MODE_PRIVATE).edit().putBoolean("primary_auto", v).apply()
}

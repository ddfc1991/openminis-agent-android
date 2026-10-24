package com.openminis.app.plus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [Mines-38.9.6 i14] The embedded Clash core — mihomo (Clash.Meta, MetaCubeX/mihomo, GPL-3.0; licence in
 * assets/licenses/mihomo.txt), shipped as the native library `libmihomo.so` (an arm64 executable; Android only lets an app
 * run binaries from its nativeLibraryDir) and started in its own process. It listens on 127.0.0.1 only: a mixed HTTP/SOCKS
 * port that the split proxy uses as its upstream (so the App, the in-app WebView and the sandbox all go through it), and the
 * REST controller with a secret. No TUN / VPN service.
 *
 * Config: an imported subscription URL or YAML (kept encrypted in PlusVault, never logged), or one generated from
 * 自己填代理 (+ backups). The agent / sandbox get the controller address, secret and file paths through the sandbox env
 * (MINES_CLASH_*), so Mines can manage nodes when the user asks. The process is restarted if it dies (2 s … 30 s).
 */
object ClashCore {
    private const val TAG = "MinesClash"
    private const val VAULT = "clash"
    private val _state = MutableStateFlow("未开启")
    /** short Chinese status for the 分流代理 page / 自检 */
    val state: StateFlow<String> = _state.asStateFlow()
    /** [Mines-38.9.10 i28] the foreground service (ClashService) shows every status line in its notification */
    @Volatile var serviceAlive: Boolean = false
    @Volatile var onState: ((String) -> Unit)? = null
    private fun st(line: String) { _state.value = line; onState?.invoke(line) }
    /** restarts in a row that never reached 「运行中」 (reset when it runs) */
    @Volatile var failedRestarts: Int = 0
    /** last automatic (re)start reason + time, e.g. 「开机自动启动 · 10-19 21:04」 (for the page / 自检 / smoke) */
    fun lastRestart(c: Context): String = prefs(c).getString("last_restart", "") ?: ""
    fun noteRestart(why: String, ctx: Context? = null) {
        val c = ctx?.applicationContext ?: app ?: return
        val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        prefs(c).edit().putString("last_restart", "$why · $t").apply()
    }
    @Volatile private var proc: Process? = null
    @Volatile private var watchdog: Thread? = null
    @Volatile private var app: Context? = null
    @Volatile var lastExit: String = ""
    private val tail = ArrayDeque<String>()

    private fun prefs(c: Context) = c.getSharedPreferences("mines_clash", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false).also { enabledNow = it }
    /** last known switch state (for the split proxy, no Context there) */
    @Volatile var enabledNow: Boolean = false
    fun binary(c: Context): File = File(c.applicationInfo.nativeLibraryDir, "libmihomo.so")
    fun home(c: Context): File = File(c.filesDir, "clash").apply { mkdirs() }
    fun configFile(c: Context): File = File(home(c), "config.yaml")
    fun bundled(c: Context) = binary(c).isFile
    fun running(): Boolean = proc?.isAlive == true

    /** the controller secret (random, made once, encrypted) */
    fun secret(c: Context): String {
        PlusVault.init(c)
        PlusVault.secretExtra(VAULT, "secret")?.let { return it }
        val s = java.util.UUID.randomUUID().toString().replace("-", "")
        PlusVault.setSecretExtra(VAULT, "secret", s); return s
    }
    fun controller() = "http://127.0.0.1:${ClashConfigCore.CONTROLLER_PORT}"
    /** the split proxy's upstream while the core runs */
    fun upstream(): SplitProxy.Upstream? = if (running()) SplitProxy.Upstream("http", "127.0.0.1", ClashConfigCore.MIXED_PORT, "Clash") else null

    /** the sandbox / agent gets where the core is and how to reach its API (no proxy credentials) */
    fun agentEnv(c: Context): Map<String, String> = if (!enabled(c) || !bundled(c)) emptyMap() else linkedMapOf(
        "MINES_CLASH_API" to controller(), "MINES_CLASH_SECRET" to secret(c),
        "MINES_CLASH_CONFIG" to configFile(c).absolutePath, "MINES_CLASH_BIN" to binary(c).absolutePath,
    ) + (LocalProxy.bestUrl()?.let { mapOf("MINES_CLASH_PROXY" to it) } ?: emptyMap())   // [38.9.11.1] only a local proxy that passed (was: 7898 hard-coded, even when dead)

    // ── import ────────────────────────────────────────────────────────

    /** [Mines-38.9.8] a link handed over by clash://install-config or a share from the Clash app (shown, imported on tap) */
    @Volatile var pendingLink: String? = null
    @Volatile var pendingFrom: String = ""
    private val _note = MutableStateFlow("")
    /** last import / download result line (survives leaving the page) */
    val note: StateFlow<String> = _note.asStateFlow()

    /** Saves a subscription URL or a pasted YAML (encrypted) and rebuilds the config. Returns a Chinese result line. */
    fun import(c: Context, input: String): String {
        if (input.isNotBlank()) _note.value = "正在下载订阅…"
        return importInner(c, input).also { _note.value = it }
    }

    private fun importInner(c: Context, input: String): String {
        PlusVault.init(c)
        var t = input.trim()
        if (t.isEmpty()) {
            PlusVault.setSecretExtra(VAULT, "sub", null); PlusVault.setSecretExtra(VAULT, "yaml", null); PlusVault.setSecretExtra(VAULT, "links", null)
            writeConfig(c); restartIfOn(c); return "已清除导入，改用自己填代理"
        }
        // [Mines-38.9.8] clash://install-config?url=… or a link inside pasted text
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true) && !ClashConfigCore.looksLikeClashYaml(t)) ClashSubCore.findLink(t)?.let { t = it }
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            val (body, why) = download(c, t)
            if (body == null) return why
            when (ClashSubCore.kind(body)) {
                "yaml" -> {
                    PlusVault.setSecretExtra(VAULT, "sub", t); PlusVault.setSecretExtra(VAULT, "yaml", body); PlusVault.setSecretExtra(VAULT, "links", null)
                    writeConfig(c); restartIfOn(c); "已导入订阅（${Regex("(?m)^\\s*-\\s*name:").findAll(body).count()} 项）$why"
                }
                "links" -> {
                    PlusVault.setSecretExtra(VAULT, "sub", t); PlusVault.setSecretExtra(VAULT, "links", body); PlusVault.setSecretExtra(VAULT, "yaml", null)
                    writeConfig(c); restartIfOn(c); "已导入订阅（${ClashSubCore.countLinks(body)} 个节点，交给 mihomo 解析）$why"
                }
                "html" -> "订阅地址返回的是网页，不是订阅内容：链接可能失效，或要在机场网站登录后重新复制"
                else -> "这个链接下载到的内容不是 Clash 订阅（没有节点）"
            }
        } else if (ClashConfigCore.looksLikeClashYaml(t)) {
            PlusVault.setSecretExtra(VAULT, "sub", null); PlusVault.setSecretExtra(VAULT, "yaml", t); PlusVault.setSecretExtra(VAULT, "links", null)
            writeConfig(c); restartIfOn(c); "已导入配置"
        } else if (ClashSubCore.kind(t) == "links") {
            PlusVault.setSecretExtra(VAULT, "sub", null); PlusVault.setSecretExtra(VAULT, "links", t); PlusVault.setSecretExtra(VAULT, "yaml", null)
            writeConfig(c); restartIfOn(c); "已导入 ${ClashSubCore.countLinks(t)} 个节点"
        } else "不是订阅链接，也不是 Clash 配置（缺少 proxies:）"
    }

    private fun restartIfOn(c: Context) { if (enabled(c)) restart(c) }

    fun importedKind(c: Context): String { PlusVault.init(c); return when {
        PlusVault.secretExtra(VAULT, "sub") != null -> "订阅"; PlusVault.secretExtra(VAULT, "yaml") != null -> "导入的配置"
        PlusVault.secretExtra(VAULT, "links") != null -> "导入的节点"; else -> "自己填代理" } }

    /**
     * [Mines-38.9.8] real phone 「订阅下载失败：connection closed」: the direct fetch of a foreign subscription host is reset.
     * Tries direct, then every proxy already running on the phone (Clash 7890, system proxy, 自己填代理), with Clash user
     * agents, ≤ 60 s in total. Returns (body, " · 经 X 下载") or (null, one plain reason line — never the link).
     */
    fun download(c: Context, url: String): Pair<String?, String> {
        val routes = ArrayList<ClashSubCore.Route>()
        runCatching { SplitProxy.detect(c) }.getOrDefault(emptyList()).forEach { f ->
            routes += ClashSubCore.Route("${f.source} ${f.upstream.port}", if (f.upstream.type == "socks5") "socks5" else "http", f.upstream.host, f.upstream.port)
        }
        runCatching { SplitProxy.settings.value.all }.getOrDefault(emptyList()).forEach { u ->
            if (!(u.host == "127.0.0.1" && u.port == ClashConfigCore.MIXED_PORT)) routes += ClashSubCore.Route("自己填代理", if (u.type == "socks5") "socks5" else "http", u.host, u.port)
        }
        val fails = ArrayList<Pair<String, String>>()
        val t0 = System.currentTimeMillis()
        for (a in ClashSubCore.plan(routes)) {
            if (System.currentTimeMillis() - t0 > 60_000) break
            val r = runCatching { fetchVia(url, a) }
            r.getOrNull()?.let { (code, body) ->
                if (code in 200..299 && body.isNotBlank()) return body to (if (a.route.type == "direct") "" else " · 经${a.route.label}下载")
                fails += a.route.label to ClashSubCore.httpReason(code)
            }
            r.exceptionOrNull()?.let { fails += a.route.label to ClashSubCore.errorReason(it.message ?: it.javaClass.simpleName) }
        }
        Log.i(TAG, "subscription download failed on ${fails.size} attempts")
        val hint = if (routes.isEmpty()) "（手机上没找到在跑的代理：先打开 Clash App 再导入）" else ""
        return null to ClashSubCore.summary(fails) + hint
    }

    private fun fetchVia(url: String, a: ClashSubCore.Attempt): Pair<Int, String> {
        val proxy = when (a.route.type) {
            "direct" -> java.net.Proxy.NO_PROXY
            "socks5" -> java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(a.route.host, a.route.port))
            else -> java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(a.route.host, a.route.port))
        }
        val conn = (java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", a.ua); setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            return code to body
        } finally { conn.disconnect() }
    }

    /**
     * [Mines-38.9.8] The Clash app on the phone (mihomo / CMFA) may expose its REST controller on 127.0.0.1:9090. When it
     * answers without a secret its providers / groups are read (names and counts only). One line for the page.
     */
    fun phoneClash(): String {
        for (port in listOf(9090, 9091)) {
            fun get(path: String): Pair<Int, String> = runCatching {
                val conn = (java.net.URL("http://127.0.0.1:$port$path").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply { connectTimeout = 1500; readTimeout = 3000 }
                val code = conn.responseCode
                code to (((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }) ?: "")
            }.getOrDefault(0 to "")
            val (code, v) = get("/version")
            if (code == 401) return "手机上的 Clash 控制接口（127.0.0.1:$port）设了密钥，Mines 读不到：直接借用它的代理端口即可"
            if (code != 200 || !v.contains("version")) continue
            val pv = ClashSubCore.parseProviders(get("/providers/proxies").second)
            val gs = ClashSubCore.parseGroups(get("/proxies").second)
            return "手机上的 Clash 控制接口可用（127.0.0.1:$port）：" + (if (pv.isNotEmpty()) pv.joinToString("、") { "${it.name} ${it.nodes} 个节点" } else "没有订阅提供者") +
                (gs.firstOrNull()?.let { "；${it.first} 现在用 ${it.second}" } ?: "")
        }
        return "手机上的 Clash 没开控制接口（127.0.0.1:9090）"
    }

    /** Writes config.yaml from the import or from 自己填代理; false when there is nothing to use. */
    fun writeConfig(c: Context): Boolean {
        PlusVault.init(c)
        val sec = secret(c)
        val imported = PlusVault.secretExtra(VAULT, "yaml")
        val links = PlusVault.secretExtra(VAULT, "links")
        val text = if (imported != null) ClashConfigCore.override(imported, sec) else if (links != null) {
            // [Mines-38.9.8] share links / base64 subscription → mihomo parses them as a file provider
            val pf = File(home(c), "sub.txt"); pf.writeText(links)
            runCatching { pf.setReadable(false, false); pf.setReadable(true, true) }
            ClashSubCore.providerConfig(ClashConfigCore.header(sec), "./sub.txt")
        } else {
            val st = SplitProxy.settings.value
            val nodes = st.all.filter { !(it.host == "127.0.0.1" && it.port == ClashConfigCore.MIXED_PORT) }.mapIndexed { i, u ->
                ClashConfigCore.Node(if (i == 0) "自己填代理" else "备用 $i", u.type, u.host, u.port, u.user, u.pass)
            }
            if (nodes.isEmpty()) { st("没有节点：先在下面填代理服务器或导入订阅"); return false }
            ClashConfigCore.fromNodes(nodes, sec)
        }
        val f = configFile(c)
        f.writeText(text)
        runCatching { f.setReadable(false, false); f.setReadable(true, true) }
        return true
    }

    // ── process ───────────────────────────────────────────────────────

    fun setEnabled(c: Context, on: Boolean) {
        app = c.applicationContext
        prefs(c).edit().putBoolean("on", on).commit()   // [38.9.10] commit: the switch must survive an immediate kill
        // [38.9.10] the foreground service owns the core (sticky restart, boot, notification); in-process start as fallback
        if (on) { if (!ClashService.sync(c)) start(c) } else { ClashService.sync(c); stop() }
        runCatching { kotlinx.coroutines.runBlocking { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() } }
    }

    /** called at app start: brings the core up when it is switched on */
    fun init(c: Context) {
        app = c.applicationContext
        if (!enabled(c)) return
        // [38.9.10] app process (re)started with the switch on → the service brings the core up; if the system refuses a
        // foreground start here (background cold start), run it in-process now
        if (!ClashService.sync(c)) start(c)
    }

    /**
     * [38.9.10] a mihomo left running by a killed app process still holds 7898 / the controller port, so a fresh one exits
     * at once ("address already in use") and the watchdog loops. Kill every libmihomo.so of this app (same uid) first.
     */
    private fun killOrphans(c: Context): Int {
        val bin = binary(c).absolutePath; var n = 0; val uid = android.os.Process.myUid()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            if (!cmd.startsWith(bin) && !cmd.contains("/libmihomo.so ")) return@forEach
            val owner = runCatching { java.nio.file.Files.getAttribute(d.toPath(), "unix:uid") as Int }.getOrNull()
            if (owner != null && owner != uid) return@forEach
            runCatching { android.os.Process.killProcess(pid); n++ }
        }
        if (n > 0) { Log.i(TAG, "killed $n orphaned core(s)"); runCatching { Thread.sleep(300) } }
        return n
    }

    @Synchronized
    fun start(c: Context) {
        app = c.applicationContext
        if (running()) return
        if (!bundled(c)) { st("这个版本没有带 Clash 内核"); return }
        if (!writeConfig(c)) return
        killOrphans(c)
        launch(c)
        if (watchdog?.isAlive != true) watchdog = Thread({
            var backoff = 2_000L; var tick = 0; var badHealth = 0
            while (enabled(c)) {
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
                val p = proc
                if (p != null && !p.isAlive && enabled(c)) {
                    lastExit = "退出码 ${runCatching { p.exitValue() }.getOrDefault(-1)}"
                    failedRestarts++
                    st(ClashSubCore.restartLine(failedRestarts, lastExit, outputTail(), backoff / 1000))
                    if (failedRestarts >= 3) prefs(c).edit().putString("last_fail", state.value).apply()
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    synchronized(this) { if (enabled(c) && proc?.isAlive != true) { killOrphans(c); launch(c) } }
                } else if (p != null && p.isAlive) {
                    backoff = 2_000L
                    if (state.value.startsWith("运行中")) failedRestarts = 0
                    // [38.9.10] hung core: alive but the controller stops answering (3 checks, 30 s apart) → restart it
                    if (++tick % 10 == 0) {
                        badHealth = if (api(c, "/version", 2500).first == 200) 0 else badHealth + 1
                        if (badHealth >= 3) { badHealth = 0; lastExit = "控制接口 90 秒没响应"; runCatching { p.destroyForcibly() } }
                    }
                }
            }
        }, "mines-clash-watchdog").apply { isDaemon = true; start() }
    }

    private fun launch(c: Context) {
        runCatching {
            val pb = ProcessBuilder(binary(c).absolutePath, "-d", home(c).absolutePath, "-f", configFile(c).absolutePath)
                .directory(home(c)).redirectErrorStream(true)
            pb.environment()["HOME"] = home(c).absolutePath
            val p = pb.start(); proc = p
            st("正在启动…")
            // [Mines-38.9.8] real phone: 「正在启动…」 forever — readiness was only read from log lines that mihomo prints at
            // info level (an imported config with log-level: warning never prints them). Now the controller is polled.
            val started = System.currentTimeMillis()
            Thread({
                while (proc === p) {
                    val el = System.currentTimeMillis() - started
                    val up = p.isAlive && api(c, "/version", 1500).first == 200
                    val exit = if (p.isAlive) "" else "退出码 ${runCatching { p.exitValue() }.getOrDefault(-1)}"
                    val stLine = ClashSubCore.startState(el, p.isAlive, up, exit, outputTail())
                    if (up) { st("运行中 · 127.0.0.1:${ClashConfigCore.MIXED_PORT}"); break }
                    if (!p.isAlive) { st(stLine); lastExit = exit; break }
                    st(stLine)
                    if (el > 30_000) break
                    try { Thread.sleep(700) } catch (_: InterruptedException) { break }
                }
            }, "mines-clash-ready").apply { isDaemon = true; start() }
            Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { l ->
                        synchronized(tail) { tail.addLast(l.take(300)); while (tail.size > 40) tail.removeFirst() }
                        if (l.contains("Mixed(http+socks) proxy listening", true) || l.contains("RESTful API listening", true)) st("运行中 · 127.0.0.1:${ClashConfigCore.MIXED_PORT}")
                    }
                }
            }, "mines-clash-out").apply { isDaemon = true; start() }
            Log.i(TAG, "core started")
        }.onFailure { st("启动失败：${it.message?.take(100) ?: it.javaClass.simpleName}"); Log.w(TAG, "start failed: ${it.javaClass.simpleName}") }
    }

    /** reloads the config into the running core (PUT /configs), or restarts it */
    fun restart(c: Context) {
        if (!enabled(c)) return
        stop(keepWatchdog = true); start(c)
    }

    @Synchronized
    fun stop(keepWatchdog: Boolean = false) {
        proc?.let { runCatching { it.destroy() }; runCatching { if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() } }
        proc = null
        if (!keepWatchdog) { watchdog?.interrupt(); watchdog = null }
        st("未开启")
    }

    /** last lines of the core's own output (for 自检 details; mihomo does not print proxy passwords) */
    fun outputTail(): String = synchronized(tail) { tail.takeLast(6).joinToString(" | ") }

    /** GET on the controller with the secret (blocking) */
    fun api(c: Context, path: String, timeoutMs: Int = 4000): Pair<Int, String> = runCatching {
        val conn = (java.net.URL(controller() + path).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs; setRequestProperty("Authorization", "Bearer " + secret(c))
        }
        val code = conn.responseCode
        code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
    }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }

    /** exit IP seen through the core's mixed port (blocking) */
    fun exitIp(timeoutMs: Int = 15_000): String? = runCatching {
        val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", ClashConfigCore.MIXED_PORT))
        val conn = (java.net.URL("https://api.ipify.org").openConnection(proxy) as java.net.HttpURLConnection).apply { connectTimeout = timeoutMs; readTimeout = timeoutMs }
        conn.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { Regex("""^[0-9a-fA-F.:]+$""").matches(it) }
    }.getOrNull()
}

package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * [Mines-net-26] Sandbox networking: which proxy the sandbox gets, the CA
 * bundle, package-mirror auto-selection, the phone-side facts mines-doctor
 * prints, and the one-tap developer toolkit.
 *
 * Root cause this fixes (build-21…25): with 分流代理 on, every sandbox process
 * got http(s)_proxy=127.0.0.1:7899 — even when that port was not listening —
 * and the proxy's 自动 mode decided "direct" from a bare TCP connect. From
 * mainland China dl-cdn.alpinelinux.org / pypi.org / files.pythonhosted.org
 * accept TCP and then stall or reset TLS, so 自动 picked 直连 for exactly the
 * hosts that need a proxy, and the tunnel had no timeout: apk (which has no
 * network timeout of its own) waited forever, pip ended in SSL errors. The
 * fresh Mines rootfs also never got a domestic mirror (the old app's rootfs
 * had one picked on its first boot; upstream skips the switch whenever the
 * official CDN answers its HEAD test first, and wrote pip's mirror to
 * /etc/pip/pip.conf, a file pip never reads).
 */
object SandboxNet {
    private const val TAG = "SandboxNet"
    private const val FILE = "mines_sandbox_net"
    const val CA_PATH = "/etc/ssl/minis-ca.pem"

    enum class ProxyMode(val label: String) { AUTO("自动"), OFF("不用代理") }

    /** Domestic mirrors and CN sites: never through a proxy. */
    val DIRECT_SUFFIXES = listOf(
        ".aliyun.com", ".aliyuncs.com", ".alicdn.com", ".tsinghua.edu.cn", ".ustc.edu.cn", ".npmmirror.com",
        ".huaweicloud.com", ".tencent.com", ".tencentyun.com", ".goproxy.cn", ".rsproxy.cn", ".cn",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var prefs: SharedPreferences? = null
    private val _mode = MutableStateFlow(ProxyMode.AUTO)
    val mode: StateFlow<ProxyMode> = _mode.asStateFlow()
    private val _status = MutableStateFlow("")
    /** One line: where sandbox traffic goes now (shown under the toggle). */
    val status: StateFlow<String> = _status.asStateFlow()
    @Volatile private var proxySource = "none"
    @Volatile private var mirrorJob: Job? = null
    @Volatile private var lastMirrorKick = 0L

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = runCatching { ProxyMode.valueOf(p.getString("proxy_mode", "AUTO")!!) }.getOrDefault(ProxyMode.AUTO)
        strict = p.getBoolean("strict_proxy", false)
    }

    fun setMode(context: Context, m: ProxyMode) {
        init(context)
        _mode.value = m
        prefs?.edit()?.putString("proxy_mode", m.name)?.apply()
        scope.launch { runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() } }
    }

    /** [38.9.11.2] 严格代理 (default off): no passing proxy → sandbox network blocked instead of direct */
    @Volatile var strict: Boolean = false; private set
    fun setStrict(context: Context, on: Boolean) {
        init(context); strict = on
        prefs?.edit()?.putBoolean("strict_proxy", on)?.apply()
        runCatching { WebProxy.apply(SplitProxy.webOn()) }   // [38.9.11.3] the app's WebViews follow 严格代理 too (block, never direct)
        scope.launch { runCatching { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() } }
    }

    fun noProxy(): String = (listOf("localhost", "127.0.0.1", "::1") + DIRECT_SUFFIXES).joinToString(",")

    /** Does anything accept a TCP connection at host:port within [ms]? */
    fun reachable(host: String, port: Int, ms: Int = 1200): Boolean = runCatching {
        Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(host, port), ms) }
        true
    }.getOrDefault(false)

    private fun block(url: String): Map<String, String> {
        val np = if (url.isNotEmpty()) noProxy() else ""
        return linkedMapOf(
            "http_proxy" to url, "https_proxy" to url, "HTTP_PROXY" to url, "HTTPS_PROXY" to url,
            "all_proxy" to url, "ALL_PROXY" to url,   // [Mines-38.9.6 i13] cleared together with the rest
            "no_proxy" to np, "NO_PROXY" to np, "MINES_PROXY_SOURCE" to proxySource,
        )
    }

    /**
     * The proxy env block for every sandbox process (PRootKernel.systemProxyEnv).
     * A proxy is exported only after it answered: the split proxy only while
     * its port is really listening, the system proxy only after a TCP check.
     * Blocking for at most ~1.2 s; called on IO threads.
     */
    fun proxyEnv(context: Context, systemProxyUri: String): Map<String, String> {
        init(context)
        if (_mode.value == ProxyMode.OFF) {
            proxySource = "off"; _status.value = "沙箱不用代理，全部直连（软件源自动选国内镜像）"
            return block("")
        }
        // [38.9.11.1] probe the local proxy ports first (≤ 3 s, cached 10 min / re-probed on network change and failures)
        runCatching { LocalProxy.ensureFresh(3000L) }
        SplitProxy.sandboxEnv()?.let { env ->
            proxySource = "split"
            val up = SplitProxy.settings.value.hasUpstream
            _status.value = if (up) "走分流代理 127.0.0.1:${SplitProxy.PORT}（国内源直连，境外源走你的代理；代理坏了自动直连）"
            else "走分流代理 127.0.0.1:${SplitProxy.PORT}，但没填代理服务器：境外源会直连"
            return env + ("MINES_PROXY_SOURCE" to proxySource) + ("no_proxy" to noProxy()) + ("NO_PROXY" to noProxy())
        }
        val splitOn = SplitProxy.settings.value.enabled && SplitProxy.settings.value.forSandbox
        if (splitOn && !SplitProxy.running.value) Log.w(TAG, "split proxy is on but not listening — not exporting it")
        // [38.9.11.2] a local proxy that passed a real outbound check (Clash app 7890 / Mines' own 7898 …) is used ALWAYS,
        // not only when 分流代理 is on (user 自检 2026-10-21: sandbox left through the phone's own IP)
        val alt = LocalProxy.best
        val hp = systemProxyUri.substringAfter("://").substringAfter('@').substringBefore('/')
        val sysOk = systemProxyUri.isNotEmpty() && alt == null && run {
            val host = hp.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            reachable(host, hp.substringAfterLast(':').toIntOrNull() ?: 80)
        }
        when (SandboxProxyCore.choose(false, alt, systemProxyUri.isNotEmpty(), sysOk, strict)) {
            SandboxProxyCore.Way.LOCAL -> {
                proxySource = "local:$alt"
                _status.value = (if (splitOn) "分流代理 127.0.0.1:${SplitProxy.PORT} 不通，" else "") + "走本机代理 127.0.0.1:$alt（国内源直连）"
                return block(LocalProxyCore.url(alt!!)) + runCatching { ClashCore.agentEnv(context) }.getOrDefault(emptyMap())
            }
            SandboxProxyCore.Way.SYSTEM -> { proxySource = "system"; _status.value = "走系统代理 $hp（国内源直连）"; return block(systemProxyUri) }
            SandboxProxyCore.Way.BLOCK -> {
                proxySource = "blocked"; _status.value = SandboxProxyCore.BLOCK_MSG
                Log.w(TAG, "strict proxy: nothing passed (${LocalProxy.summary()}) — sandbox network blocked")
                val np = listOf("localhost", "127.0.0.1", "::1").joinToString(",")
                return block(SandboxProxyCore.BLOCK_URL) + ("no_proxy" to np) + ("NO_PROXY" to np) + ("MINES_NET_BLOCKED" to SandboxProxyCore.BLOCK_MSG)
            }
            else -> {}
        }
        if (systemProxyUri.isNotEmpty()) {
            proxySource = "system-dead"; _status.value = "系统代理 $hp 连不上，沙箱已改为直连"
            Log.w(TAG, "system proxy $hp unreachable — sandbox goes direct")
            return block("")
        }
        proxySource = "none"; _status.value = "直连（没有可用的代理；软件源自动选最快的）"
        return block("")
    }

    /**
     * Boot step (after the default_mount overlay): builds /etc/ssl/minis-ca.pem
     * (Mozilla bundle fetched at build time + the rootfs bundle + anything in
     * /usr/local/share/ca-certificates) and returns the env from
     * /etc/minis/net.env. CA variables are dropped when no bundle was built.
     */
    fun prepareRootfs(context: Context, rootfs: File): Map<String, String> {
        init(context)
        val out = linkedMapOf<String, String>()
        var caOk = false
        runCatching {
            val sb = StringBuilder()
            File(rootfs, "etc/ssl/minis-mozilla.pem").takeIf { it.isFile }?.let { sb.append(it.readText()).append('\n') }
            File(rootfs, "etc/ssl/certs/ca-certificates.crt").takeIf { it.isFile }?.let { sb.append(it.readText()).append('\n') }
            File(rootfs, "usr/local/share/ca-certificates").listFiles()?.filter { it.name.endsWith(".crt") || it.name.endsWith(".pem") }
                ?.forEach { sb.append(it.readText()).append('\n') }
            if (sb.contains("BEGIN CERTIFICATE")) {
                val f = File(rootfs, CA_PATH.removePrefix("/"))
                val text = sb.toString()
                if (!f.isFile || f.length() != text.toByteArray().size.toLong()) {
                    f.parentFile?.mkdirs(); f.writeText(text)
                }
                caOk = true
            }
        }.onFailure { Log.w(TAG, "CA bundle: ${it.message}") }
        runCatching {
            File(rootfs, "etc/minis/net.env").takeIf { it.isFile }?.readLines()?.forEach { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#") || !l.contains('=')) return@forEach
                val k = l.substringBefore('=').trim(); val v = l.substringAfter('=').trim()
                if (!caOk && v == CA_PATH) return@forEach
                out[k] = v
            }
        }
        writeNetState(context, rootfs)
        return out
    }

    /** Phone-side facts for mines-doctor + the signature mines-mirror re-probes on. */
    fun writeNetState(context: Context, rootfs: File? = null) {
        runCatching {
            val root = rootfs ?: File(context.applicationContext.filesDir, "alpine-rootfs")
            if (!root.isDirectory) return
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val transport = when {
                caps == null -> "无网络"
                vpn -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> "其它"
            }
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(" ").orEmpty()
            val v6 = lp?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
            val sig = "$transport|$vpn|${lp?.interfaceName}|$dns|${SplitProxy.settings.value.enabled}|${_mode.value}".hashCode().toUInt().toString(16)
            val text = "vpn=${if (vpn) 1 else 0}\ntransport=$transport\ndns=$dns\nipv6=${if (v6) 1 else 0}\nproxy_source=$proxySource\nsig=$sig\nupdated=${System.currentTimeMillis() / 1000}\n"
            val f = File(root, "etc/minis/net.state")
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "net.state.tmp"); tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        }.onFailure { Log.w(TAG, "net.state: ${it.message}") }
    }

    /** Categories the user pinned in Settings → mirrors (left alone by mines-mirror). */
    private fun pinned(context: Context): String = runCatching {
        val vm = com.openminis.app.ui.sandbox.MirrorSpeedTestViewModel
        vm.ensureLoaded(context)
        buildList {
            if (vm.isActive(com.openminis.app.ui.sandbox.MirrorCategory.ALPINE)) add("apk")
            if (vm.isActive(com.openminis.app.ui.sandbox.MirrorCategory.PIP)) add("pip")
            if (vm.isActive(com.openminis.app.ui.sandbox.MirrorCategory.NPM)) add("npm")
        }.joinToString(" ")
    }.getOrDefault("")

    /**
     * Network changed / boot: re-probe mirrors from inside the sandbox (the
     * real path apk and pip will use) when the network signature changed.
     * Silent background job, debounced, at most every 2 minutes.
     */
    fun kickMirrors(context: Context, delayMs: Long = 15_000) {
        val app = context.applicationContext
        if (System.currentTimeMillis() - lastMirrorKick < 120_000 && mirrorJob?.isActive == true) return
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            delay(delayMs)
            writeNetState(app)
            lastMirrorKick = System.currentTimeMillis()
            val skip = pinned(app)
            val cmd = (if (skip.isNotEmpty()) "MINIS_MIRROR_SKIP='$skip' " else "") + "mines-mirror auto-if-changed apk pip npm --quiet"
            MinesJobs.run(app, "选择软件源", cmd, successText = "软件源已选好", timeoutMs = 120_000, silent = true)
        }
    }

    // ── one-tap actions (Settings → 沙箱与防杀) ─────────────────────────

    fun runDoctor(context: Context, onDone: (Int) -> Unit = {}): Long =
        MinesJobs.run(context, "网络体检", "mines-doctor", successText = "体检完成", timeoutMs = 240_000, onDone = onDone)

    fun reselectMirrors(context: Context): Long {
        val skip = pinned(context)
        return MinesJobs.run(context, "切换软件源",
            (if (skip.isNotEmpty()) "MINIS_MIRROR_SKIP='$skip' " else "") + "mines-mirror auto apk pip npm",
            successText = "已换到最快的软件源", timeoutMs = 180_000)
    }

    fun installDevKit(context: Context, withJava: Boolean = true, onDone: (Int) -> Unit = {}): Long =
        MinesJobs.run(context, "安装 开发工具包", "mines-devkit install" + if (withJava) "" else " --no-java",
            successText = "开发工具包装好了（gcc、Python、Node、Git、Java）", timeoutMs = 45 * 60_000L, onDone = onDone)

    /** Same kit on a VPS (in-app SSH, no sandbox ssh needed). Blocking; IO thread. */
    fun installDevKitOnVps(context: Context, server: VpsServer): Pair<Boolean, String> = runCatching {
        val script = DEVKIT_VPS
        val (r, _) = VpsSsh.run(context, server, "sh -c '" + script.replace("'", "'\\''") + "' 2>&1 | tail -n 40", timeoutMs = 40 * 60_000L, attempts = 1)
        (r.exit == 0 && r.output.contains("MINES_DEVKIT_OK")) to r.output.trim().lines().takeLast(12).joinToString("\n")
    }.getOrElse { false to (VpsSsh.reason(it)) }

    /** Kept identical to `mines-devkit vps-command`. */
    private const val DEVKIT_VPS = "set -e; if command -v apt-get >/dev/null 2>&1; then S=\"\"; [ \"\$(id -u)\" = 0 ] || S=sudo; \$S env DEBIAN_FRONTEND=noninteractive apt-get update -q && \$S env DEBIAN_FRONTEND=noninteractive apt-get install -y -q build-essential python3 python3-pip nodejs npm git openjdk-17-jdk-headless; elif command -v dnf >/dev/null 2>&1; then dnf install -y gcc gcc-c++ make python3 python3-pip nodejs npm git java-17-openjdk-devel; elif command -v yum >/dev/null 2>&1; then yum install -y gcc gcc-c++ make python3 python3-pip nodejs npm git java-17-openjdk-devel; elif command -v apk >/dev/null 2>&1; then apk add build-base python3 py3-pip nodejs npm git openjdk17-jdk; else echo \"unknown distro\"; exit 2; fi; gcc --version | head -1; python3 --version; node --version; git --version; java -version 2>&1 | head -1; echo MINES_DEVKIT_OK"

    /** Current mirrors as "apk 阿里云 · pip 清华 · npm npmmirror" (reads the rootfs). */
    fun mirrorSummary(context: Context): String = runCatching {
        val root = File(context.applicationContext.filesDir, "alpine-rootfs")
        val repo = File(root, "etc/apk/repositories").takeIf { it.isFile }?.readLines()?.firstOrNull { it.contains("/main") }.orEmpty()
        val pip = File(root, "etc/pip.conf").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("index-url") }?.substringAfter('=')?.trim().orEmpty()
        val npm = File(root, "root/.npmrc").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("registry=") }?.substringAfter('=').orEmpty()
        fun name(u: String) = when {
            u.isBlank() -> "默认"
            "dl-cdn.alpinelinux" in u || "pypi.org" in u || "registry.npmjs" in u -> "官方"
            "aliyun" in u -> "阿里云"; "tuna" in u -> "清华"; "ustc" in u -> "中科大"; "npmmirror" in u -> "npmmirror"
            else -> "自定义"
        }
        "apk ${name(repo)} · pip ${name(pip)} · npm ${name(npm)}"
    }.getOrDefault("")

}

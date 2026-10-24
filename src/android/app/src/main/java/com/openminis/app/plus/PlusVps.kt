package com.openminis.app.plus

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Health checks for the user's VPS list, and the bridge into the sandbox.
 *
 * Every check opens a TCP connection to the SSH port and reads the server
 * banner, so "online" means sshd is really answering (not just a ping).
 * The ordered list of healthy servers is published to the sandbox as env
 * vars, which `minis-vps` reads: it tries servers in order (lowest latency
 * first) and moves to the next one when a connection fails. When every
 * server is down and auto-fallback is on, the effective mode drops to local.
 */
object PlusVps {
    private const val TAG = "PlusVps"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null

    /** Result of one reachability check: latency (or -2) and why it failed. */
    data class ProbeResult(val latencyMs: Int, val error: String = "")

    /**
     * TCP connect + SSH banner, IPv4 first. Returns latency in ms or -2.
     * [Mines-vps-link] Keeps the reason ("连接超时", "端口拒绝连接", "握手被中断")
     * so the 工作位置 row can say WHY a server is unreachable instead of a
     * bare 连不上 — a connect that succeeds but never shows a banner is the
     * signature of something on the path interfering with SSH.
     */
    suspend fun probeDetailed(server: VpsServer, timeoutMs: Int = 6000, context: Context? = null): ProbeResult = withContext(Dispatchers.IO) {
        // [Mines-vps-31] every route of the box (22 → 443 → Tailscale); first SSH banner wins.
        val errs = ArrayList<String>()
        for (ep in VpsSsh.endpoints(server)) {
            if (ep.viaHub) continue   // [Mines-38.9.13 tunnel_v1] not a phone-side port; checked by the real login
            val r = if (ep.ws != null) probeWs(ep.ws, timeoutMs + 4000) else probeOne(server.copy(host = ep.host, port = ep.port), timeoutMs)
            if (r.latencyMs >= 0) return@withContext r
            errs += ep.label + "：" + r.error
        }
        val cut = errs.any { it.contains("没有 SSH 回应") || it.contains("握手被中断") || it.contains("被重置") || it.contains("超时") }
        val ctx = context ?: appContext
        ProbeResult(-2, errs.joinToString("；") + if (cut && ctx != null && VpsSsh.vpnActive(ctx)) "\n" + VpsSsh.vpnHint(server) else "")
    }

    /** [Mines-vps-31] SSH banner through a WebSocket route (Cloudflare Tunnel / websocat bridge). */
    fun probeWs(url: String, timeoutMs: Int): ProbeResult {
        val t0 = System.nanoTime()
        val latch = java.util.concurrent.CountDownLatch(1)
        var res = ProbeResult(-2, "隧道没有回应")
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val ws = client.newWebSocket(okhttp3.Request.Builder().url(url).build(), object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                res = if (bytes.utf8().startsWith("SSH-")) ProbeResult(((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1))
                    else ProbeResult(-2, "隧道那头不是 SSH")
                latch.countDown()
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                res = ProbeResult(-2, "隧道连不上" + (response?.let { "（HTTP ${it.code}）" } ?: "：" + VpsSsh.reason(t))); latch.countDown()
            }
        })
        latch.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { ws.cancel() }
        return res
    }

    /** Set once from anywhere that has a Context (for the VPN hint in background probes). */
    @Volatile var appContext: Context? = null

    private fun probeOne(server: VpsServer, timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        return try {
            Socket().use { sock ->
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(VpsSsh.preferIpv4(server.host), server.port), timeoutMs)
                sock.soTimeout = timeoutMs
                val buf = ByteArray(64)
                val n = try { sock.getInputStream().read(buf) } catch (e: java.net.SocketTimeoutException) { -3 }
                // [Mines-38.9.3] latency = time to the SSH banner, not the TCP connect: with a TUN / fake-ip VPN (Clash) the
                // connect is answered by the phone's own tunnel in 1–3 ms; the banner has to come from the real server
                val connectMs = ((System.nanoTime() - start) / 1_000_000).toInt()
                when {
                    n == -3 -> ProbeResult(-2, "端口通了但没有 SSH 回应（可能被网络干扰）")
                    n > 0 && String(buf, 0, n).startsWith("SSH-") -> ProbeResult(connectMs.coerceAtLeast(1))
                    else -> ProbeResult(-2, "这个端口不是 SSH")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "probe ${server.host}:${server.port} failed: ${e.message}")
            ProbeResult(-2, VpsSsh.reason(e))
        }
    }

    /** Returns latency in ms, or -2 when unreachable / not an SSH server. */
    suspend fun probe(server: VpsServer, timeoutMs: Int = 6000): Int = probeDetailed(server, timeoutMs).latencyMs

    private data class Check(
        val latency: Int, val error: String, val authorized: Boolean?, val viaRelay: Boolean,
        val os: String?, val egress: String?,
    )

    /**
     * One full check of a server: banner probe, then — when the app has a key —
     * a real key login over the cached session (direct, else relay) that also
     * reads OS and egress IP in the same round trip. A server whose direct port
     * is blocked but whose relay works counts as online (via relay).
     */
    private suspend fun check(context: Context, s: VpsServer): Check = withContext(Dispatchers.IO) {
        val c = checkRaw(context, s)
        if (c.latency >= 0) { reachCache.remove(s.id); return@withContext c }
        // [Mines-38.9.6 i17] the phone could not log in: say WHICH path failed (direct / proxy / outside) instead of 「连不上」,
        // so neither the row nor the agent calls a live server dead (user 2026-10-18: 8.217 「全端口失联」 while it answered)
        val r = reachFor(s)
        c.copy(error = r.verdict.text + (c.error.takeIf { it.isNotBlank() && !r.verdict.text.contains(it) }?.let { "；登录：" + it.take(80) } ?: ""))
    }

    /** [Mines-38.9.6 i17] three-path verdict per server, cached 10 min (the outside check costs up to 15 s) */
    private val reachCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ReachProbe.Report>>()
    fun reachFor(s: VpsServer, maxAgeMs: Long = 10 * 60_000L): ReachProbe.Report {
        reachCache[s.id]?.let { (t, r) -> if (System.currentTimeMillis() - t < maxAgeMs) return r }
        val r = ReachProbe.check(s.host, s.port, ssh = true)
        reachCache[s.id] = System.currentTimeMillis() to r
        return r
    }

    private suspend fun checkRaw(context: Context, s: VpsServer): Check = withContext(Dispatchers.IO) {
        val direct = probeDetailed(s, context = context)
        if (!VpsSsh.hasKey(context) && MinesLink.parse(s.linkUrl) == null) {
            if (direct.latencyMs >= 0 || s.relay.isBlank()) {
                return@withContext Check(direct.latencyMs, if (direct.latencyMs >= 0) "还没授权" else direct.error, false, false, null, null)
            }
        }
        val t0 = System.nanoTime()
        runCatching {
            val (r, relay) = VpsSsh.run(context, s, DESCRIBE, timeoutMs = 15_000, attempts = 2)
            val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
            val lines = r.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val os = lines.firstOrNull()?.take(48)
            val ip = lines.lastOrNull { it.matches(Regex("^[0-9a-fA-F.:]{7,45}$")) }?.take(45)
            runCatching { WorkspaceStore.probeRunningAsync(context, s) }   // [Mines-38.9.5 i] read-only 「在跑什么」, ≤ every 30 min
            Check(if (direct.latencyMs >= 0 && !relay) direct.latencyMs else ms.coerceAtLeast(1), "", true, relay, os, ip)
        }.getOrElse { e ->
            when (e) {
                is VpsSsh.NoKeyException ->
                    Check(direct.latencyMs, if (direct.latencyMs >= 0) "还没授权" else direct.error, false, false, null, null)
                else -> {
                    val why = VpsSsh.reason(e)
                    val authFail = why.contains("钥匙")
                    // Reachable but the key was refused → online, not authorized.
                    if (direct.latencyMs >= 0) Check(direct.latencyMs, if (authFail) "钥匙被拒绝，请重新授权" else why, if (authFail) false else null, false, null, null)
                    else Check(-2, (e.message ?: why).take(120), null, false, null, null)
                }
            }
        }
    }

    private const val DESCRIBE = "uname -sr 2>/dev/null; " +
        "(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null || " +
        " curl -fsS --max-time 6 https://ifconfig.me 2>/dev/null || " +
        " wget -qO- --timeout=6 https://api.ipify.org 2>/dev/null) | tail -n 1"

    suspend fun probeAll(context: Context) {
        PlusPrefs.init(context)
        val servers = PlusPrefs.state.value.servers
        if (servers.isEmpty()) { syncToSandbox(context); return }
        val results = withContext(Dispatchers.IO) {
            servers.map { s -> async { s.id to (if (s.enabled) check(context, s) else null) } }.awaitAll()
        }.toMap()
        val now = System.currentTimeMillis()
        PlusPrefs.update(context) { st ->
            st.copy(servers = st.servers.map { s ->
                val r = results[s.id] ?: return@map if (results.containsKey(s.id)) s.copy(lastLatencyMs = -1) else s
                s.copy(
                    lastLatencyMs = r.latency, lastCheckedAt = now, lastError = r.error, viaRelay = r.viaRelay,
                    authorized = r.authorized ?: s.authorized,
                    osLabel = r.os ?: s.osLabel, egressIp = r.egress ?: s.egressIp,
                )
            })
        }
        syncToSandbox(context)
        runCatching { WorkspaceStore.checkExpiry(context) }   // [Mines-38.9.5 i] 到期提醒 (3 days / 1 day / 2 h before)
    }

    /** Kept for callers of the old API: OS + egress of a reachable server. */
    suspend fun describe(context: Context, server: VpsServer): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val (r, _) = VpsSsh.run(context, server, DESCRIBE, 15_000, 2)
            val lines = r.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val os = lines.firstOrNull()?.take(48) ?: return@runCatching null
            val ip = lines.lastOrNull { it.matches(Regex("^[0-9a-fA-F.:]{7,45}$")) } ?: return@runCatching null
            os to ip
        }.getOrNull()
    }

    /** Servers in the order minis-vps should try them. */
    fun orderedTargets(st: PlusState): List<VpsServer> {
        val enabled = st.servers.filter { it.enabled }
        val online = enabled.filter { it.lastLatencyMs >= 0 }.sortedBy { it.lastLatencyMs }
        val unknown = enabled.filter { it.lastLatencyMs == -1 }
        return WorkspaceStore.primaryFirst(online + unknown)   // [Mines-38.9.5 i] 主设备 first, the others as fallback
    }

    fun effectiveMode(st: PlusState): String {
        if (!st.isVps) return PlusState.MODE_LOCAL
        if (st.autoFallback && orderedTargets(st).isEmpty()) return PlusState.MODE_LOCAL
        return PlusState.MODE_VPS
    }

    /** Publish work mode + server list into the sandbox environment. */
    fun syncToSandbox(context: Context) {
        val st = PlusPrefs.state.value
        Tunnel.init(context)   // [Mines-38.9.13 tunnel_v1]
        // [Mines-vps-link] user@ipv4:port[|relay]: the IPv4 literal spares the
        // sandbox a DNS lookup and a possibly broken IPv6 attempt; the relay
        // lets minis-vps fall back the same way the app does.
        // [Mines-vps-31] every route of a server becomes its own entry, in route order
        // (Cloudflare tunnel "!…|wss://" = relay only → direct port → backup port → Tailscale);
        // minis-vps already moves to the next entry when one does not connect.
        val list = orderedTargets(st).flatMap { s ->
            val relay = WsLinkCore.relayFor(s.host, s.relay).takeIf { it.isNotEmpty() && !it.contains(',') && !it.contains(' ') }?.let { "|$it" } ?: ""
            VpsSsh.endpoints(s).map { ep ->
                if (ep.viaHub) Tunnel.sandboxEntry(s) ?: ""   // [Mines-38.9.13 tunnel_v1] ProxyJump via the AWS hub
                else if (ep.ws != null) "!${s.user}@${s.host}:${s.port}|${WsLinkCore.fixScheme(ep.ws)}"
                else {
                    val host = if (ep.host.contains(':')) ep.host else VpsSsh.preferIpv4(ep.host)
                    "${s.user}@$host:${ep.port}$relay"
                }
            }.filter { it.isNotEmpty() }
        }.joinToString(",")
        runCatching { VpsSsh.materializeKey(context) }
        val mode = effectiveMode(st)
        val app = context.applicationContext as? MinisApp ?: return
        runCatching {
            val repo = app.envVarRepository
            fun put(key: String, value: String, note: String) {
                val existing = repo.entries.value.firstOrNull { it.key == key }
                if (existing == null) repo.add(key, value, note)
                else if (repo.getValue(key) != value) repo.update(existing.id, key, value, note)
            }
            put("MINIS_WORK_MODE", mode,
                "Mines 工作位置: local=手机沙箱, vps=把重任务交给服务器（minis-vps run/bg 执行；断线自动重连并从断点续上输出，任务在服务器上不会中断；若提示超时，用 minis-vps attach <名字> 接着看，不要重跑。超过 1 分钟的远程任务一律用 minis-vps bg，或在服务器上 tmux/nohup 里跑；ssh 偶尔断一下先自己静默重连重试（最多 3 次，间隔 2/5/10 秒），重连成功就接着做，不要跟用户说「SSH 断了」；只有重连都失败时才告诉用户，并说明原因。判断服务器在不在线一律用 minis-vps probe <host[:port]>（直连 + 走代理 + 外部节点三条路）：只有它说「服务器挂了」才能这么说；单一路径连不上（沙箱代理、mines-link、自己写的 python/nc 探测）只能说「手机这边网络到不了」）")
            // [Mines-link-31] the app's HTTPS link to the servers, for minis-vps (preferred over ssh when up)
            put("MINIS_LINK_PORT", LinkBridge.PORT.toString(), "Mines 快速线路本机桥接端口（minis-vps 自动使用）")
            put("MINIS_LINK_TOKEN", LinkBridge.token, "Mines 快速线路本机桥接口令（每次启动更换）")
            put("MINIS_VPS_SERVERS", list.ifEmpty { "none" },
                "Mines Plus 服务器列表，按延迟排序；minis-vps 自动选第一个可用的，断开切下一台")
            // [Mines-38.9.9] reference copy: minis-vps warns when a command overrides the list and drops a configured relay
            put("MINIS_VPS_SERVERS_APP", list.ifEmpty { "none" },
                "Mines 设置里的服务器列表原样副本（minis-vps 用它发现命令里手改 MINIS_VPS_SERVERS / --direct 丢掉了中转）；不要改")
        }.onFailure { Log.w(TAG, "env sync failed: ${it.message}") }
    }

    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var netCallbackRegistered = false

    /** Ask the monitor to re-check now (network change, screen opened, task start). */
    fun poke() { wake.trySend(Unit) }

    /**
     * [Mines-vps-link] Server monitor with auto-reconnect. While VPS mode is
     * on it re-checks every 60 s when everything is fine, and backs off
     * 5 → 10 → 20 → 40 s while a server is down so a dropped link is picked up
     * again within seconds. A network change (Wi-Fi ↔ mobile, VPN toggled)
     * drops the cached SSH sessions and re-checks immediately. When a server
     * comes back the server list is re-published to the sandbox, which takes
     * the effective mode from local back to VPS on its own.
     */
    fun startMonitor(context: Context) {
        appContext = context.applicationContext
        LinkBridge.start(context)
        AccountPatrol.load(context); AccountPatrol.schedule(context)
        // [Mines-38.9.1] the one-time 「known backup routes」 for two specific servers was removed: backup routes come only
        // from what the user enters in 编辑服务器 (nothing about one user's boxes ships in the APK).
        val appCtx = context.applicationContext
        PlusPrefs.init(appCtx)
        registerNetworkCallback(appCtx)
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            delay(3000)
            var backoff = 5_000L
            while (isActive) {
                var healthy = true
                if (PlusPrefs.state.value.isVps) {
                    runCatching { probeAll(appCtx) }
                    val st = PlusPrefs.state.value
                    healthy = st.servers.filter { it.enabled }.all { it.lastLatencyMs >= 0 }
                }
                val wait = if (healthy) { backoff = 5_000L; 60_000L } else backoff.also { backoff = (backoff * 2).coerceAtMost(40_000L) }
                kotlinx.coroutines.withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    private fun registerNetworkCallback(context: Context) {
        if (netCallbackRegistered) return
        netCallbackRegistered = true
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    VpsSsh.dropAll(); MinesLink.kick(); poke()
                }
                override fun onLost(network: android.net.Network) { VpsSsh.dropAll() }
            })
        }.onFailure { netCallbackRegistered = false; Log.w(TAG, "network callback: ${it.message}") }
    }

    // ── [Mines-vps-28] 测试连接: try every route, keep the one that works ──

    /** One route tried by [testRoutes]. [relay] is what to store as the server's relay to use it. */
    data class RouteTry(val name: String, val relay: String?, val ok: Boolean, val ms: Int, val why: String, val viaTunnel: Boolean = false) {
        /** [Mines-38.9.3] a 「直连」 answered in < 5 ms while a VPN / TUN is on went through the phone's proxy, not direct */
        val label: String get() = if (viaTunnel && name == "直连") "经代理（手机 VPN）" else name
    }

    /**
     * [Mines-vps-32] Plain verdict when nothing works. If the routes through the
     * phone's proxies / VPN fail too (or every route just times out / gets no SSH
     * answer), the problem is on the server side, not the phone, so do not blame
     * the proxy.
     */
    fun unreachableSummary(s: VpsServer, tries: List<RouteTry>): String {
        val name = s.name.ifBlank { s.host }
        val viaProxy = tries.filter { it.name.startsWith("经 ") || it.name.startsWith("中转") }
        val kinds = tries.map { kind(it.why) }
        val serverSide = (viaProxy.isNotEmpty() && viaProxy.none { it.ok }) ||
            kinds.all { it == "超时" || it == "网络不通" || it == "这个端口不是 SSH" || it == "其他" }
        if (serverSide) return "$name 现在从外面连不上（可能服务器或防火墙问题），不是你手机的问题"
        return "所有线路都不通：" + (tries.firstOrNull()?.let { "${kind(it.why)}（${it.why}）" } ?: "")
    }

    /** Short failure class for the UI: 超时 / 拒绝 / 域名 / 认证 / 干扰 / 其他. */
    fun kind(why: String): String = when {
        why.contains("超时") -> "超时"
        why.contains("拒绝连接") -> "端口拒绝"
        why.contains("域名") -> "域名解析失败"
        why.contains("钥匙") || why.contains("密码") || why.contains("授权") -> "认证"
        why.contains("干扰") || why.contains("重置") || why.contains("中断") -> "被干扰"
        why.contains("网络不通") -> "网络不通"
        why.contains("不是 SSH") -> "这个端口不是 SSH"
        else -> "其他"
    }

    private fun banner(sock: java.net.Socket, timeoutMs: Int): String? {
        sock.soTimeout = timeoutMs
        val buf = ByteArray(64)
        val n = try { sock.getInputStream().read(buf) } catch (e: java.net.SocketTimeoutException) { return "端口通了但没有 SSH 回应（可能被网络干扰）" }
        return if (n > 0 && String(buf, 0, n).startsWith("SSH-")) null else "这个端口不是 SSH"
    }

    /** SSH banner through nothing (null proxy), an HTTP CONNECT proxy or a SOCKS5 proxy. Blocking. */
    private fun bannerProbe(host: String, port: Int, proxyType: String?, ph: String?, pp: Int, timeoutMs: Int = 6000): Pair<Int, String> {
        val t0 = System.nanoTime()
        return try {
            when (proxyType) {
                null -> java.net.Socket(java.net.Proxy.NO_PROXY).use { s ->
                    s.connect(java.net.InetSocketAddress(VpsSsh.preferIpv4(host), port), timeoutMs)
                    banner(s, timeoutMs)?.let { -2 to it } ?: (((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1) to "")   // [Mines-38.9.3] to the banner
                }
                "socks5" -> java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(ph, pp))).use { s ->
                    s.connect(java.net.InetSocketAddress.createUnresolved(host, port), timeoutMs)
                    banner(s, timeoutMs)?.let { -2 to it } ?: (((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1) to "")   // [Mines-38.9.3] to the banner
                }
                else -> java.net.Socket(java.net.Proxy.NO_PROXY).use { s ->
                    s.connect(java.net.InetSocketAddress(ph, pp), timeoutMs); s.soTimeout = timeoutMs
                    s.getOutputStream().apply { write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray()); flush() }
                    // read the response header up to the blank line
                    val inp = s.getInputStream(); val hdr = StringBuilder()
                    while (!hdr.endsWith("\r\n\r\n") && hdr.length < 4096) { val c = inp.read(); if (c < 0) break; hdr.append(c.toChar()) }
                    val code = hdr.toString().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    if (code != 200) return -2 to "代理不让连（HTTP $code）"
                    banner(s, timeoutMs)?.let { -2 to it } ?: (((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1) to "")   // [Mines-38.9.3] to the banner
                }
            }
        } catch (e: Throwable) {
            -2 to VpsSsh.reason(e)
        }
    }

    /**
     * Tries the server directly, through every proxy found on the phone
     * (system proxy, VPN-app local ports) and through its configured relay.
     * Blocking; call on Dispatchers.IO. Never throws.
     */
    fun testRoutes(context: Context, s: VpsServer): List<RouteTry> {
        val out = ArrayList<RouteTry>()
        runCatching {
            val (ms, why) = bannerProbe(s.host, s.port, null, null, 0)
            out += RouteTry("直连", "", ms >= 0, ms, why, viaTunnel = ms >= 0 && SessionStatus.viaLocalTunnel(ms, VpsSsh.vpnActive(context)))
        }.onFailure { out += RouteTry("直连", "", false, -2, VpsSsh.reason(it)) }
        // [Mines-vps-31] backup port and Tailscale name, direct
        VpsSsh.endpoints(s).filterNot { it.viaHub }.drop(1).forEach { ep ->
            runCatching {
                val (ms, why) = bannerProbe(ep.host, ep.port, null, null, 0)
                out += RouteTry(ep.label, "", ms >= 0, ms, why)
            }.onFailure { out += RouteTry(ep.label, "", false, -2, VpsSsh.reason(it)) }
        }
        val found = runCatching { SplitProxy.detect(context) }.getOrDefault(emptyList())
        for (f in found) runCatching {
            val u = f.upstream
            val type = if (u.type.startsWith("socks")) "socks5" else "http"
            val (ms, why) = bannerProbe(s.host, s.port, type, u.host, u.port)
            out += RouteTry("经 ${f.source}（${u.host}:${u.port}）", "$type://${u.host}:${u.port}", ms >= 0, ms, why)
        }
        val relay = WsLinkCore.relayFor(s.host, s.relay)   // [38.9.13] no WS relay test on AWS (SSH 22 only)
        if (relay.isNotEmpty() && out.none { it.relay == relay }) {
            runCatching {
                val t0 = System.nanoTime()
                val sess = VpsSsh.open(context, s, viaRelay = true, timeoutMs = 12_000)
                runCatching { sess.disconnect() }
                out += RouteTry("中转 $relay", relay, true, ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1), "")
            }.onFailure { e ->
                val why = if (e is VpsSsh.NoKeyException) "要先授权才能测中转" else VpsSsh.reason(e)
                out += RouteTry("中转 $relay", relay, false, -2, why)
            }
        }
        return out
    }

    /**
     * Picks the fastest working route and stores it: direct keeps the relay
     * as configured, a proxy route becomes the server's relay. Returns a
     * one-line summary for the UI.
     */
    fun applyBestRoute(context: Context, s: VpsServer, tries: List<RouteTry>): String {
        val best = tries.filter { it.ok }.minByOrNull { it.ms }
            ?: return unreachableSummary(s, tries)
        val direct = tries.firstOrNull { it.name == "直连" }
        val newRelay = when {
            best.name == "直连" -> s.relay
            direct?.ok == true -> s.relay
            else -> best.relay ?: s.relay
        }
        PlusPrefs.update(context) { st ->
            st.copy(servers = st.servers.map {
                if (it.id == s.id) it.copy(relay = newRelay, lastLatencyMs = best.ms, lastError = "",
                    viaRelay = best.name != "直连", lastCheckedAt = System.currentTimeMillis()) else it
            })
        }
        VpsSsh.drop(s.id)
        runCatching { syncToSandbox(context) }
        if (direct?.ok == true && direct.viaTunnel) return "可用 · 经手机代理（${SessionStatus.msText(direct.ms)}）"
        return if (best.name == "直连" || direct?.ok == true) "直连可用（${SessionStatus.msText(direct?.ms ?: best.ms)}）"
        else "直连不通，已改用：${best.name}（${best.ms} ms）"
    }
}

package com.openminis.app.plus

import com.openminis.app.plus.ProviderRouteCore.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.11.3] Android side of [ProviderRouteCore]: per-host route memory (direct vs proxy), the proxy to use, a quick
 * two-route probe, and the chat status line (「正在连接 … / 正在思考 …」). Used by the provider HTTP clients.
 */
object ProviderRoutes {
    private val mem = ConcurrentHashMap<String, ProviderRouteCore.Mem>()

    /** smoke before-measurement: 38.9.11.2 networking (app-wide proxy for every host, 30 s connect, 120 s caps, 60 s deadline) */
    @Volatile var legacy: Boolean = false
    /** smoke: force the proxy behind the PROXY route (e.g. a stalled "Clash" on 127.0.0.1:7890); null = automatic */
    @Volatile var forcedProxy: Proxy? = null

    /** the proxy behind the PROXY route: Mines' split proxy while it works › a local proxy that passed › none */
    fun proxy(): Proxy? {
        forcedProxy?.let { return it }
        runCatching { if (SplitProxy.active) return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SplitProxy.PORT)) }
        // [38.9.11.3] the user's own node stays the PROXY route even when its last check failed (still listening) — each
        // host is then probed through it for real and falls back to direct fast
        LocalProxy.node()?.let { return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) }
        return null
    }

    fun proxyFor(r: Route): Proxy = if (r == Route.DIRECT) Proxy.NO_PROXY else (proxy() ?: Proxy.NO_PROXY)

    fun memOf(host: String): ProviderRouteCore.Mem? = mem[host.lowercase()]

    fun order(host: String): List<Route> = ProviderRouteCore.order(host, memOf(host), System.currentTimeMillis(), proxy() != null)

    fun bothDown(host: String): Boolean = ProviderRouteCore.bothDownRecently(memOf(host), System.currentTimeMillis(), proxy() != null)

    fun ok(host: String, r: Route, ms: Long) { mem[host.lowercase()] = ProviderRouteCore.recordOk(memOf(host), r, ms, System.currentTimeMillis()) }
    fun fail(host: String, r: Route) { mem[host.lowercase()] = ProviderRouteCore.recordFail(memOf(host), r, System.currentTimeMillis()) }
    fun forget() = mem.clear()

    /** TCP + TLS handshake to host:port over [p] (HTTP CONNECT for a proxy); ms, or null when it failed within [budgetMs] */
    fun probeOne(host: String, port: Int, p: Proxy, budgetMs: Long): Long? = runCatching {
        val t0 = System.currentTimeMillis()
        val raw: Socket
        if (p.type() == Proxy.Type.HTTP) {
            val pa = p.address() as InetSocketAddress
            raw = Socket(Proxy.NO_PROXY); raw.connect(InetSocketAddress(pa.hostString, pa.port), budgetMs.toInt())
            raw.soTimeout = budgetMs.toInt()
            raw.getOutputStream().write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
            val ins = raw.getInputStream(); val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n") && sb.length < 4096) { val c = ins.read(); if (c < 0) break; sb.append(c.toChar()) }
            if (!sb.startsWith("HTTP/1.1 200") && !sb.startsWith("HTTP/1.0 200")) { raw.close(); return@runCatching null }
        } else {
            raw = Socket(Proxy.NO_PROXY)
            val left = (budgetMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300L).toInt()
            raw.connect(InetSocketAddress(host, port), left); raw.soTimeout = budgetMs.toInt()
        }
        if (port != 443) { raw.close(); return@runCatching System.currentTimeMillis() - t0 }
        raw.soTimeout = (budgetMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300L).toInt()
        val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        (f.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
        System.currentTimeMillis() - t0
    }.getOrNull()

    private val probePool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mines-route-probe").apply { isDaemon = true } }

    /** both routes in parallel (≤ [ProviderRouteCore.PROBE_MS]); remembers and returns the pick (null = neither answered) */
    fun probe(host: String, port: Int): Route? {
        val px = proxy() ?: return Route.DIRECT
        val b = ProviderRouteCore.PROBE_MS
        val fd = probePool.submit<Long?> { probeOne(host, port, Proxy.NO_PROXY, b) }
        val fp = probePool.submit<Long?> { probeOne(host, port, px, b) }
        val d = runCatching { fd.get(b + 500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        val p = runCatching { fp.get(b + 500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        lastProbe = "$host 直连 ${d?.let { "${it}ms" } ?: "✗"} · 代理 ${p?.let { "${it}ms" } ?: "✗"}"
        android.util.Log.i("ProviderRoutes", "probe $lastProbe")
        val pick = ProviderRouteCore.pickByProbe(d, p, preferProxy = ProviderRouteCore.isForeign(host))
        if (d == null) fail(host, Route.DIRECT)
        if (p == null) fail(host, Route.PROXY)
        if (pick != null) ok(host, pick, (if (pick == Route.DIRECT) d else p) ?: 0L)
        return pick
    }
    @Volatile var lastProbe: String = ""; private set

    /** routes for one request, probing first when nothing fresh is remembered */
    fun plan(host: String, port: Int): List<Route> {
        if (legacy) return listOf(if (proxy() != null) Route.PROXY else Route.DIRECT)
        val now = System.currentTimeMillis(); val hasProxy = proxy() != null
        if (ProviderRouteCore.needsProbe(host, memOf(host), now, hasProxy)) {
            status(ProviderRouteCore.Phase.CONNECTING, host, null)
            probe(host, port)
        }
        return order(host)
    }

    /**
     * For clients that cannot pick a route per request (Anthropic / Gemini): an ordered proxy list per host — OkHttp tries
     * the next entry when connecting on one fails; failures are remembered.
     */
    val selector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): MutableList<Proxy> {
            val h = uri?.host ?: return mutableListOf(Proxy.NO_PROXY)
            return order(h).map { proxyFor(it) }.distinct().toMutableList().ifEmpty { mutableListOf(Proxy.NO_PROXY) }
        }
        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            val h = uri?.host ?: return
            val viaProxy = (sa as? InetSocketAddress)?.address?.isLoopbackAddress == true
            fail(h, if (viaProxy) Route.PROXY else Route.DIRECT)
        }
    }

    /** DNS with a 5 s cap: a stalled resolver would otherwise block the call past its connect budget */
    object TimedDns : okhttp3.Dns {
        private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mines-provider-dns").apply { isDaemon = true } }
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val f = pool.submit<List<java.net.InetAddress>> { okhttp3.Dns.SYSTEM.lookup(hostname) }
            return try { f.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
                f.cancel(true); throw java.net.UnknownHostException("DNS timeout: $hostname")
            } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? java.io.IOException) ?: java.net.UnknownHostException(hostname) }
        }
    }

    // ── status line for the chat (one stream at a time is what the user watches) ──
    data class Status(val phase: ProviderRouteCore.Phase, val host: String, val route: Route?, val since: Long)
    val status = kotlinx.coroutines.flow.MutableStateFlow(Status(ProviderRouteCore.Phase.IDLE, "", null, 0L))
    fun status(phase: ProviderRouteCore.Phase, host: String, route: Route?) {
        val cur = status.value
        val since = if (cur.phase == phase && cur.host == host) cur.since else System.currentTimeMillis()
        status.value = Status(phase, host, route, since)
    }
    fun idle() { status.value = Status(ProviderRouteCore.Phase.IDLE, "", null, 0L) }

    /** 自检 / smoke: one line per remembered host */
    fun summary(): String = mem.entries.joinToString(" · ") { (h, m) ->
        "$h " + (m.okRoute?.zh ?: "?") + (if (m.failDirectAt > 0) " 直连✗" else "") + (if (m.failProxyAt > 0) " 代理✗" else "")
    }
}

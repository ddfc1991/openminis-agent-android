package com.openminis.app.plus

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * [Mines-38.9.11.1 hotfix] Android side of [LocalProxyCore]: probes the local proxy ports (listener check, then a real
 * request through the port) and keeps the result. Callers (sandbox env, app ProxySelector, WebView override,
 * MINES_CLASH_PROXY) export a local proxy only when it passed; none passing = direct. Re-probes at start, on a default
 * network change and after a failure (≤ every 15 s), plus when the last probe is older than 10 min.
 */
object LocalProxy {
    private const val TAG = "LocalProxy"
    @Volatile var probes: List<LocalProxyCore.Probe> = emptyList(); private set
    @Volatile var best: Int? = null; private set
    /** [38.9.11.3] the user's node: the port that last passed (kept across a failed check while it still listens) */
    @Volatile var lastGood: Int? = null; private set
    /** [38.9.11.3] the PROXY route for AI provider calls (LocalProxyCore.nodeFor); re-checks itself every 30 s while failing */
    fun node(): Int? {
        val n = LocalProxyCore.nodeFor(probes, best, lastGood)
        if (best == null && n != null && System.currentTimeMillis() - at >= LocalProxyCore.NODE_RECHECK_MS) request(LocalProxyCore.Why.FAILURE)
        return n
    }
    @Volatile var at: Long = 0L; private set
    @Volatile var lastWhy: String = ""; private set
    @Volatile private var ctx: Context? = null
    private val lock = Object()
    @Volatile private var running = false
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "mines-localproxy").apply { isDaemon = true } }

    val probedOnce: Boolean get() = at > 0L
    fun usable(port: Int): Boolean = LocalProxyCore.usable(probes, port, probedOnce)
    fun bestUrl(): String? = best?.let { LocalProxyCore.url(it) }
    fun summary(): String = if (!probedOnce) "本机代理还没检测" else LocalProxyCore.summary(probes, best) +
        (LocalProxyCore.nodeNote(best, LocalProxyCore.nodeFor(probes, best, lastGood))?.let { "；$it" } ?: "")

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        request(LocalProxyCore.Why.START)
        runCatching {
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { request(LocalProxyCore.Why.NETWORK) }
                override fun onLost(network: android.net.Network) { request(LocalProxyCore.Why.NETWORK) }
            })
        }.onFailure { Log.w(TAG, "network callback: ${it.message}") }
    }

    /** a request through a local proxy failed → re-probe (rate-limited) */
    fun reportFailure() = request(LocalProxyCore.Why.FAILURE)

    fun request(why: LocalProxyCore.Why) {
        if (!LocalProxyCore.shouldProbe(why, at, System.currentTimeMillis())) return
        pool.execute { runCatching { probeNow(why.name.lowercase()) } }
    }

    /** blocks the caller (IO thread) up to [maxWaitMs] when nothing was probed yet / the result is stale */
    fun ensureFresh(maxWaitMs: Long = 3000L) {
        if (probedOnce && !LocalProxyCore.shouldProbe(LocalProxyCore.Why.STALE, at, System.currentTimeMillis())) return
        val t0 = System.currentTimeMillis(); val before = at
        pool.execute { runCatching { probeNow("ensure") } }
        while (System.currentTimeMillis() - t0 < maxWaitMs && at == before) Thread.sleep(50)
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true
    }.getOrDefault(false)

    private fun through(port: Int, url: String): Int? = runCatching {
        val c = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500; c.instanceFollowRedirects = false; c.useCaches = false
        try { c.responseCode.takeIf { it in 100..599 } } finally { c.disconnect() }
    }.getOrNull()

    private fun probeOne(port: Int): LocalProxyCore.Probe {
        if (!listening(port)) return LocalProxyCore.Probe(port, LocalProxyCore.Result.DOWN)
        val t0 = System.currentTimeMillis()
        // 204 is required for a working proxy. Other HTTP codes prove an HTTP peer
        // replied, but must not make a proxy operational; try the HTTPS fallback first.
        val http = through(port, "http://connectivitycheck.gstatic.com/generate_204")
        val https = if (http == 204) null else through(port, "https://www.google.com/generate_204")
        val response = when {
            https == 204 -> https
            http != null -> http
            else -> https
        }
        val reachable = LocalProxyCore.reachableStatus(response)
        val ok = response == 204
        return LocalProxyCore.Probe(port, if (ok) LocalProxyCore.Result.OK else LocalProxyCore.Result.FAIL,
            System.currentTimeMillis() - t0, response, reachable)
    }

    fun probeNow(why: String) {
        synchronized(lock) {
            if (running) return
            running = true
        }
        try {
            val builtinPort = ClashConfigCore.MIXED_PORT
            val builtinUp = runCatching { ClashCore.running() }.getOrDefault(false) && listening(builtinPort)
            val order = LocalProxyCore.candidates(builtinPort, builtinUp)
            val fs = order.map { p -> pool.submit<LocalProxyCore.Probe> { probeOne(p) } }
            val res = fs.mapIndexed { i, f -> runCatching { f.get(7, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(LocalProxyCore.Probe(order[i], LocalProxyCore.Result.FAIL)) }
            val before = best; val beforeProbed = probedOnce
            val beforeSplit = LocalProxyCore.ok(probes, SplitProxy.PORT)
            probes = res; best = LocalProxyCore.select(res, order); at = System.currentTimeMillis(); lastWhy = why
            best?.let { lastGood = it }
            LocalProxyCore.nodeNote(best, LocalProxyCore.nodeFor(res, best, lastGood))?.let { Log.w(TAG, it) }
            Log.i(TAG, "probe ($why): ${summary()}")
            if (!beforeProbed || before != best || beforeSplit != LocalProxyCore.ok(res, SplitProxy.PORT)) {
                runCatching { SplitProxy.reapplyWeb() }
                runCatching { kotlinx.coroutines.runBlocking { com.openminis.app.sandbox.ExecutionCoordinator.broadcastProxyChange() } }
            }
        } finally { running = false }
    }
}

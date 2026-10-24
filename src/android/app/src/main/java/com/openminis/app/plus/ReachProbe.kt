package com.openminis.app.plus

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.6 i17] Three-path reachability for one server port (see [ReachCore]): direct from the phone (SSH ports need
 * the "SSH-" banner — a TUN / fake-ip VPN answers the TCP connect itself), through the active proxy, and from outside via
 * check-host.net TCP nodes. Blocking; call off the main thread. ~6 s direct + 8 s proxy + ≤ 15 s outside.
 */
object ReachProbe {
    data class Report(val host: String, val port: Int, val paths: ReachCore.Paths, val verdict: ReachCore.Verdict)

    private val http = okhttp3.OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    /** banner = the port speaks first (SSH); otherwise a connect that is not reset within 1.2 s counts */
    private fun answer(sock: Socket, ssh: Boolean, timeoutMs: Int): Pair<Boolean, String> {
        sock.soTimeout = if (ssh) timeoutMs else 1200
        val buf = ByteArray(64)
        return try {
            val n = sock.getInputStream().read(buf)
            when {
                n > 0 && String(buf, 0, n).startsWith("SSH-") -> true to ""
                ssh -> false to (if (n < 0) "连上就被断开" else "不是 SSH")
                else -> (n >= 0) to (if (n < 0) "连上就被断开" else "")
            }
        } catch (e: java.net.SocketTimeoutException) {
            if (ssh) false to "端口通了但没有 SSH 回应" else true to ""
        }
    }

    fun direct(host: String, port: Int, ssh: Boolean, timeoutMs: Int = 6000): Pair<Boolean, String> = runCatching {
        Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress(VpsSsh.preferIpv4(host), port), timeoutMs)
            answer(s, ssh, timeoutMs)
        }
    }.getOrElse { false to VpsSsh.reason(it) }

    /** null = no proxy configured / running */
    fun viaProxy(host: String, port: Int, ssh: Boolean, timeoutMs: Int = 8000): Pair<Boolean, String>? {
        val u = runCatching { SplitProxy.activeUpstreamNow() }.getOrNull() ?: return null
        return runCatching { SplitProxy.tunnelVia(u, host, port, timeoutMs).use { answer(it, ssh, timeoutMs) } }
            .getOrElse { false to ("代理 ${u.host}:${u.port} " + VpsSsh.reason(it)) }
    }

    /** (ok, answered) from up to 3 check-host.net nodes, or (0, 0) when the service cannot be reached */
    fun outside(host: String, port: Int, budgetMs: Long = 15_000): Pair<Int, Int> = runCatching {
        val start = http.newCall(okhttp3.Request.Builder().url("https://check-host.net/check-tcp?host=$host:$port&max_nodes=3")
            .header("Accept", "application/json").build()).execute().use { it.body?.string().orEmpty() }
        val id = org.json.JSONObject(start).optString("request_id").ifBlank { return@runCatching 0 to 0 }
        val t0 = System.currentTimeMillis(); var last = Triple(0, 0, 0)
        while (System.currentTimeMillis() - t0 < budgetMs) {
            Thread.sleep(2000)
            val body = http.newCall(okhttp3.Request.Builder().url("https://check-host.net/check-result/$id")
                .header("Accept", "application/json").build()).execute().use { it.body?.string().orEmpty() }
            last = ReachCore.parseCheckHost(body)
            if (last.second > 0 && last.third == 0) break
        }
        last.first to last.second
    }.getOrDefault(0 to 0)

    fun check(host: String, port: Int, ssh: Boolean = true): Report {
        val d = direct(host, port, ssh)
        val x = if (d.first) null else viaProxy(host, port, ssh)
        val o = if (d.first || x?.first == true) 0 to 0 else outside(host, port)
        val paths = ReachCore.Paths(d.first, x?.first, o.first, o.second, d.second, x?.second.orEmpty())
        return Report(host, port, paths, ReachCore.verdict(paths))
    }
}

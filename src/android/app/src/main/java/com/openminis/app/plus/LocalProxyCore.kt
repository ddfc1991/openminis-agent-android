package com.openminis.app.plus

/**
 * [Mines-38.9.11.1 hotfix] user's phone: http(s)_proxy / MINES_CLASH_PROXY / WebView pointed at 127.0.0.1:7899 (Mines' own
 * split proxy, whose upstream was down: 0/5) while 127.0.0.1:7890 (the user's Clash app) worked 3/3 and direct ~60 %.
 * Rule now: never assume a local proxy works because a port is configured — probe the usual local proxy ports with a
 * real request through each (generate_204 via HTTP forward, else HTTPS CONNECT), export only a port that passed, and
 * nothing (= direct) when none did. Re-probe at start, on a network change and after a failure. Pure, unit-tested
 * (LocalProxyCoreTest); the Android side is [LocalProxy].
 */
object LocalProxyCore {
    /** in preference order: Clash / Clash Meta / CMFA (7890), Clash Verge (7897), Mines split proxy (7899), 7891, SOCKS-style 1080 / 10808 */
    val CANDIDATES = listOf(7890, 7897, 7899, 7891, 1080, 10808)
    const val TTL_MS = 10 * 60_000L
    const val FAIL_GAP_MS = 15_000L

    enum class Result { DOWN, FAIL, OK }
    data class Probe(val port: Int, val result: Result, val ms: Long = 0L, val httpStatus: Int? = null, val reachable: Boolean = result == Result.OK)

    /** Any received HTTP status proves the TCP/proxy path reached an HTTP peer; 204 is only the expected payload. */
    fun reachableStatus(code: Int?): Boolean = code in 100..599

    /** candidate ports; the built-in mihomo's port only while its listener is up */
    fun candidates(builtinPort: Int?, builtinUp: Boolean): List<Int> =
        (CANDIDATES + listOfNotNull(builtinPort?.takeIf { builtinUp && it in 1..65535 })).distinct()

    /** the first port (candidate order) whose probe passed; null = export nothing (direct) */
    fun select(probes: List<Probe>, order: List<Int>): Int? =
        order.firstOrNull { p -> probes.any { it.port == p && it.result == Result.OK } }

    /** true = passed, false = probed and down / failing, null = not probed yet */
    fun ok(probes: List<Probe>, port: Int): Boolean? = probes.firstOrNull { it.port == port }?.let { it.result == Result.OK }

    /** a port may be used unless the last probe saw it down / failing (unknown before the first probe → allowed) */
    fun usable(probes: List<Probe>, port: Int, probedOnce: Boolean): Boolean = if (!probedOnce) true else ok(probes, port) == true

    enum class Why { START, NETWORK, FAILURE, STALE }
    /** should a new probe run now? network change / start always; failures at most every [FAIL_GAP_MS]; otherwise after [TTL_MS] */
    fun shouldProbe(why: Why, lastAt: Long, now: Long): Boolean = when (why) {
        Why.START, Why.NETWORK -> true
        Why.FAILURE -> lastAt <= 0L || now - lastAt >= FAIL_GAP_MS
        Why.STALE -> lastAt <= 0L || now - lastAt >= TTL_MS
    }

    /**
     * [38.9.11.3] user: the proxy is their own node, deliberately configured — never dropped silently. For AI provider calls
     * the PROXY route uses [best] (passed a real request); when the last check failed but the user's node (the port that last
     * passed, [lastGood]) is still LISTENING, it stays the PROXY route: each foreign host is then checked by a real
     * CONNECT + TLS through it (ProviderRoutes.probe, ≤ PROBE_MS) and falls back to direct at once when it does not answer.
     * A port that stopped listening (Clash closed) is not used.
     */
    fun nodeFor(probes: List<Probe>, best: Int?, lastGood: Int?): Int? =
        best ?: lastGood?.takeIf { lg -> probes.any { it.port == lg && it.result == Result.FAIL } }

    /** while the user's node fails its check: re-check every [NODE_RECHECK_MS] (instead of the 10 min TTL) */
    const val NODE_RECHECK_MS = 30_000L
    fun nodeNote(best: Int?, node: Int?): String? =
        if (best == null && node != null) "代理节点 127.0.0.1:$node 刚才检测没通过：仍保留给国外服务先试（不通会立刻直连），${NODE_RECHECK_MS / 1000} 秒后再测" else null

    fun url(port: Int) = "http://127.0.0.1:$port"

    /** one line for 自检 / logs, e.g. "7890 ✓ 120ms · 7899 ✗ · 7897 无监听" (only listening ports + the chosen one) */
    fun summary(probes: List<Probe>, chosen: Int?): String {
        val seen = probes.filter { it.result != Result.DOWN }
        val head = chosen?.let { "用 127.0.0.1:$it" } ?: "没有能用的本机代理 → 直连"
        if (seen.isEmpty()) return "$head（本机代理端口都没在监听）"
        return head + "（" + seen.joinToString(" · ") { probe ->
            "${probe.port} " + when {
                probe.result == Result.OK -> "✓ ${probe.ms}ms"
                probe.reachable && probe.httpStatus != null -> "⚠ 可达 · HTTP ${probe.httpStatus}（探测未通过）"
                else -> "✗"
            }
        } + "）"
    }
}

package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] user 2026-10-21: 「AI 回复很慢，几乎每个供应商都经常 60 秒超时」 (already before 38.9.11.2).
 *
 * Root cause (code): every provider call used the app-wide ProxySelector. With 分流代理 on (or a local proxy that passed
 * the gstatic probe, e.g. the Clash app on 7890 whose nodes were all dead) EVERY host went through that proxy — China-hosted
 * providers too (token.sensenova.cn, developer.amd.com.cn …). The CONNECT tunnel and the TLS handshake then ran under the
 * client's readTimeout (600 s), so a stalled proxy path was only cut by Mines' 60 s first-chunk deadline (or the 120 s
 * TTFB watchdog); the turn then moved to the next model over the SAME dead path → another 60 s.
 *
 * Now, per host:
 *  - China / directly reachable providers go DIRECT first, foreign API hosts through the proxy first, the other route is
 *    the automatic fallback; the route that last worked is remembered (30 min) and a route that failed is avoided (5 min);
 *  - with no memory, a quick probe of both routes (TCP + TLS, ≤ 3 s) picks the one that answered (direct wins ties);
 *  - connect (DNS + TCP + proxy tunnel + TLS) has its own [CONNECT_MS] budget per route; a timeout there switches route at
 *    once; both routes failed → the error says so and the turn moves to the next key / provider immediately (no 1/2/4 s
 *    same-model retries, no 60 s wait); a host with both routes failed in the last [BOTH_DOWN_MS] fails fast;
 *  - after connecting, the first token has its own budget ([firstTokenMs]); once tokens flow there is no total cap.
 * Pure, unit-tested (ProviderRouteCoreTest). Android side: [ProviderRoutes].
 */
object ProviderRouteCore {
    enum class Route(val zh: String) { DIRECT("直连"), PROXY("代理") }

    const val CONNECT_MS = 10_000L
    const val PROBE_MS = 3_000L
    const val OK_TTL_MS = 30 * 60_000L
    const val FAIL_TTL_MS = 5 * 60_000L
    const val BOTH_DOWN_MS = 60_000L
    /** first token after the connection is up (response headers + first stream event) */
    const val FIRST_TOKEN_MS = 45_000L
    /** reasoning models may think before the first byte */
    const val FIRST_TOKEN_REASONING_MS = 90_000L

    fun firstTokenMs(reasoning: Boolean): Long = if (reasoning) FIRST_TOKEN_REASONING_MS else FIRST_TOKEN_MS

    /** whole budget the chat loop gives a silent stream before it moves on: two routes' connects + the first token */
    fun silentBudgetMs(reasoning: Boolean): Long = 2 * CONNECT_MS + PROBE_MS + firstTokenMs(reasoning)

    /** mainland-China hosted AI APIs (and .cn): direct first */
    val DOMESTIC = listOf(
        ".cn", "deepseek.com", "sensenova.cn", "sensetime.com", "moonshot.cn", "bigmodel.cn", "zhipuai.cn", "siliconflow.cn",
        "aliyuncs.com", "aliyun.com", "volces.com", "volcengine.com", "baidubce.com", "baidu.com", "tencentcloudapi.com",
        "hunyuan.cloud.tencent.com", "minimax.chat", "minimaxi.com", "xfyun.cn", "lingyiwanwu.com", "stepfun.com",
        "baichuan-ai.com", "modelscope.cn", "qnaigc.com", "ppinfra.com", "infini-ai.com", "coze.cn", "doubao.com",
        "xiaomimimo.com", "longcat.chat", "agnes-ai.com", "codebuddy.ai", "workbuddy.ai",
    )

    /** foreign API hosts usually blocked / slow from mainland China: proxy first */
    val FOREIGN = listOf(
        "openai.com", "chatgpt.com", "anthropic.com", "claude.ai", "googleapis.com", "google.com", "x.ai", "openrouter.ai",
        "groq.com", "mistral.ai", "together.xyz", "together.ai", "fireworks.ai", "cohere.ai", "cohere.com", "perplexity.ai",
        "githubcopilot.com", "github.com", "models.github.ai", "azure.com", "huggingface.co", "cerebras.ai", "sambanova.ai",
        "deepinfra.com", "novita.ai", "workers.dev", "cloudflare.com", "nvidia.com", "opencode.ai",
    )

    private fun m(h: String, p: String): Boolean = if (p.startsWith(".")) h.endsWith(p) else h == p || h.endsWith(".$p")

    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        return Regex("^(10\\.|127\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.)").containsMatchIn(h)
    }
    fun isDomestic(host: String): Boolean { val h = host.lowercase().trimEnd('.'); return DOMESTIC.any { m(h, it) } }
    fun isForeign(host: String): Boolean { val h = host.lowercase().trimEnd('.'); return !isDomestic(h) && FOREIGN.any { m(h, it) } }

    /** what Mines remembers about one host */
    data class Mem(
        val okRoute: Route? = null, val okAt: Long = 0L, val okMs: Long = 0L,
        val failDirectAt: Long = 0L, val failProxyAt: Long = 0L,
    ) {
        fun failedAt(r: Route) = if (r == Route.DIRECT) failDirectAt else failProxyAt
    }

    fun recordOk(m: Mem?, r: Route, ms: Long, now: Long): Mem {
        val b = m ?: Mem()
        return b.copy(okRoute = r, okAt = now, okMs = ms,
            failDirectAt = if (r == Route.DIRECT) 0L else b.failDirectAt, failProxyAt = if (r == Route.PROXY) 0L else b.failProxyAt)
    }

    fun recordFail(m: Mem?, r: Route, now: Long): Mem {
        val b = m ?: Mem()
        val c = if (r == Route.DIRECT) b.copy(failDirectAt = now) else b.copy(failProxyAt = now)
        return if (c.okRoute == r) c.copy(okRoute = null, okAt = 0L) else c
    }

    /** base order for a host before memory: local → direct only; domestic → direct only; foreign → proxy, direct; else direct, proxy */
    fun baseOrder(host: String, proxyAvailable: Boolean): List<Route> = when {
        isLocal(host) || !proxyAvailable -> listOf(Route.DIRECT)
        isDomestic(host) -> listOf(Route.DIRECT)   // [38.9.11.3] user: China providers never go through Clash / the node
        isForeign(host) -> listOf(Route.PROXY, Route.DIRECT)
        else -> listOf(Route.DIRECT, Route.PROXY)
    }

    /** routes to try, in order, for [host] now */
    fun order(host: String, mem: Mem?, now: Long, proxyAvailable: Boolean): List<Route> {
        val base = baseOrder(host, proxyAvailable)
        if (base.size == 1 || mem == null) return base
        val ok = mem.okRoute?.takeIf { now - mem.okAt in 0 until OK_TTL_MS && it in base }
        val ordered = if (ok != null) listOf(ok) + base.filter { it != ok } else base
        // a route that failed recently goes last (both failed: keep the order, the caller decides on fast-fail)
        val (fresh, failed) = ordered.partition { r -> mem.failedAt(r).let { it == 0L || now - it !in 0 until FAIL_TTL_MS } }
        return fresh + failed
    }

    /** both routes failed within [BOTH_DOWN_MS] and nothing worked since → fail at once instead of waiting again */
    fun bothDownRecently(mem: Mem?, now: Long, proxyAvailable: Boolean): Boolean {
        if (mem == null) return false
        val d = mem.failDirectAt > 0 && now - mem.failDirectAt in 0 until BOTH_DOWN_MS
        val p = !proxyAvailable || (mem.failProxyAt > 0 && now - mem.failProxyAt in 0 until BOTH_DOWN_MS)
        val okSince = mem.okAt > maxOf(mem.failDirectAt, mem.failProxyAt)
        return d && p && !okSince
    }

    /** a probe is needed when there are two routes and no fresh success is remembered */
    fun needsProbe(host: String, mem: Mem?, now: Long, proxyAvailable: Boolean): Boolean =
        baseOrder(host, proxyAvailable).size > 1 && (mem?.okRoute == null || now - mem.okAt !in 0 until OK_TTL_MS)

    /** quick probe results (ms, null = failed) → the route to use; direct wins unless the proxy is clearly faster */
    fun pickByProbe(directMs: Long?, proxyMs: Long?, preferProxy: Boolean = false): Route? = when {
        directMs == null && proxyMs == null -> null
        directMs == null -> Route.PROXY
        proxyMs == null -> Route.DIRECT
        preferProxy -> if (directMs * 2 + 300 < proxyMs) Route.DIRECT else Route.PROXY
        else -> if (proxyMs * 2 + 300 < directMs) Route.PROXY else Route.DIRECT
    }

    /** what a failure means for the next step */
    enum class Fail { CONNECT, FIRST_TOKEN, RATE, AUTH, SERVER, OTHER }

    fun classify(message: String?, httpStatus: Int? = null): Fail {
        if (httpStatus == 429) return Fail.RATE
        if (httpStatus == 401 || httpStatus == 403) return Fail.AUTH
        if (httpStatus != null && httpStatus >= 500) return Fail.SERVER
        val s = (message ?: "").lowercase()
        return when {
            "连不上" in s || "connect timed out" in s || "failed to connect" in s || "unable to resolve" in s || "no address" in s ||
                "connection refused" in s || "unexpected end of stream on" in s && "connect" in s || "handshake" in s ||
                "tunnel" in s || "dns timeout" in s -> Fail.CONNECT
            "没有任何回应" in s || "ttfb" in s || "first token" in s || "首字" in s -> Fail.FIRST_TOKEN
            "429" in s || "rate limit" in s || "too many requests" in s -> Fail.RATE
            "invalid api key" in s || "401" in s || "unauthorized" in s -> Fail.AUTH
            else -> Fail.OTHER
        }
    }

    enum class Next { OTHER_ROUTE, NEXT_KEY, NEXT_PROVIDER, RETRY_SAME }

    /**
     * After a failure: a connect failure is about the HOST (every key of it shares the path) → other route, then the next
     * provider; a silent / rate-limited / rejected key → next key of the same provider, then the next provider.
     */
    fun next(f: Fail, routesLeft: Int, keysLeft: Int): Next = when (f) {
        Fail.CONNECT -> if (routesLeft > 0) Next.OTHER_ROUTE else Next.NEXT_PROVIDER
        Fail.FIRST_TOKEN, Fail.RATE, Fail.AUTH -> if (keysLeft > 0) Next.NEXT_KEY else Next.NEXT_PROVIDER
        Fail.SERVER -> if (keysLeft > 0) Next.NEXT_KEY else Next.NEXT_PROVIDER
        Fail.OTHER -> Next.RETRY_SAME
    }

    /** chat status line while waiting (the UI adds the animated dots): 「正在连接 api.x.cn（直连） 3 秒」 / 「正在思考 12 秒」 */
    enum class Phase { IDLE, CONNECTING, THINKING }

    fun statusText(phase: Phase, host: String, route: Route?, elapsedMs: Long): String {
        val s = (elapsedMs / 1000).coerceAtLeast(0)
        val sec = if (s >= 2) " $s 秒" else ""
        return when (phase) {
            Phase.IDLE -> ""
            Phase.CONNECTING -> "正在连接 ${host.take(40)}" + (route?.let { "（${it.zh}）" } ?: "") + sec
            Phase.THINKING -> "正在思考$sec"
        }
    }

    /**
     * [38.9.11.3] user screenshots: 「出错 · Invalid API key …」 (商汤) and 「unexpected end of stream on https://api.lixil…」
     * (AWS中转). The chat shows one short Chinese line naming the provider instead of the raw English exception.
     * [switched] = the turn already moved on to another key / provider. null = not a known pattern (leave as is).
     */
    fun zhError(raw: String?, label: String, switched: Boolean = false): String? {
        val s = (raw ?: "").lowercase(); val who = label.ifBlank { "这个服务商" }
        val tail = if (switched) "，已换下一个" else ""
        return when {
            "invalid api key" in s || "incorrect api key" in s || "invalid_api_key" in s || "unauthorized" in s && "401" in s ||
                Regex("\\b401\\b").containsMatchIn(s) && ("key" in s || "auth" in s) ->
                "$who 的密钥无效" + (if (switched) "，已换下一个" else "（401）：在 服务商 里点「测试全部密钥」换一个能用的")
            "unexpected end of stream" in s || "connection reset" in s || "stream was reset" in s || "streamresetexception" in s ||
                "connection closed" in s || "connection abort" in s || "broken pipe" in s || "socket closed" in s || "中途断开" in s || "没回完就断开" in s ->
                "$who 中途断开，已自动重试" + (if (switched) "，换了下一个" else "，仍不行：稍后再试或换个服务商")
            "rate limited" in s || "429" in s || "too many requests" in s -> "$who 限流（429）$tail"
            "没有任何回应" in s || "首字超时" in s -> null   // already Chinese
            "timeout" in s || "timed out" in s -> "$who 等太久没回应（超时）$tail"
            "unable to resolve host" in s || "no address associated" in s -> "连不上 $who（域名解析失败）$tail"
            "failed to connect" in s || "connection refused" in s -> "连不上 $who$tail"
            else -> null
        }
    }

    /** the error text when both routes failed */
    fun bothFailedText(host: String, directWhy: String?, proxyWhy: String?, triedProxy: Boolean): String =
        "连不上 $host：直连" + (directWhy?.let { "不通（$it）" } ?: "不通") +
            (if (triedProxy) "，代理" + (proxyWhy?.let { "不通（$it）" } ?: "不通") else "，没有可用代理") + "。已换下一个"
}

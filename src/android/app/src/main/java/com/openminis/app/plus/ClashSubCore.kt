package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.8] Clash: use the subscription the user already has (user 2026-10-19 00:01; real phone: 「订阅下载失败：
 * connection closed」 and the switch stuck on 「正在启动…」). Pure part, unit-tested in ClashSubCoreTest:
 *  - the download is tried directly AND through every proxy already running on the phone (127.0.0.1:7890, system proxy)
 *    with Clash user agents and a retry — a direct fetch of a foreign subscription host is often reset mid-way in China;
 *  - errors in one plain Chinese line, never the subscription URL itself (it is a credential);
 *  - a body that is not Clash YAML (base64 / share links) becomes a mihomo file provider instead of being refused;
 *  - links are found in pasted / clipboard text and in clash://install-config?url=… deep links;
 *  - the phone's Clash (mihomo / CMFA) external controller, when reachable, is read for its groups and nodes.
 * Without root Mines cannot read another app's private files: [NO_ROOT] says so in one line.
 */
object ClashSubCore {
    const val NO_ROOT = "没有 root，Mines 读不到 Clash App 里存的订阅：复制订阅链接（Mines 会自动认出）或在 Clash 里分享给 Mines，也可以直接借用它的代理端口。"
    val UAS = listOf("clash.meta", "ClashMetaForAndroid/2.11.5.Meta", "clash-verge/v2.2.3")

    data class Route(val label: String, val type: String, val host: String, val port: Int)   // type "direct" / "http" / "socks5"
    data class Attempt(val route: Route, val ua: String)

    /** Direct first, then each local proxy (deduped), each with the first UA; then the other UAs over the first working-looking route. */
    fun plan(proxies: List<Route>): List<Attempt> {
        val routes = (listOf(Route("直连", "direct", "", 0)) + proxies.distinctBy { "${it.host}:${it.port}" }).take(5)
        val out = ArrayList<Attempt>()
        routes.forEach { out += Attempt(it, UAS[0]) }
        routes.forEach { r -> UAS.drop(1).forEach { out += Attempt(r, it) } }
        return out.take(12)
    }

    /** Removes URLs (a subscription link is a credential) and query secrets from an error text. */
    fun scrub(s: String?): String = (s ?: "").replace(Regex("(?i)(https?|clash|clashmeta|mihomo)://\\S+"), "<链接>").replace(Regex("(?i)token=\\S+"), "token=***").trim()

    fun httpReason(code: Int): String = when (code) {
        401, 403 -> "订阅服务器拒绝（$code）：链接过期 / 被重置，或它只认特定网络；在 Clash App 里更新一次订阅看是否正常"
        404 -> "订阅地址不存在（404）：链接可能已更换"
        429 -> "订阅服务器限流（429）：过几分钟再试"
        in 500..599 -> "订阅服务器出错（$code）"
        else -> "订阅服务器没有正常回复（HTTP $code）"
    }

    fun errorReason(raw: String?): String {
        val t = (raw ?: "").lowercase()
        return when {
            t.contains("connection closed") || t.contains("unexpected end of stream") || t.contains("connection reset") || t.contains("eof") ->
                "下载被中途断开（直连多半被重置）"
            t.contains("timeout") || t.contains("timed out") -> "连接超时"
            t.contains("unable to resolve") || t.contains("unknownhost") || t.contains("no address") -> "域名解析失败"
            t.contains("certificate") || t.contains("ssl") || t.contains("handshake") -> "TLS 握手失败"
            t.contains("refused") -> "连接被拒绝"
            t.isBlank() -> "没有回复"
            else -> scrub(raw).take(60)
        }
    }

    /** One line summarising every attempt that failed: 「直连：下载被中途断开；Clash 7890：订阅服务器拒绝（403）…」. */
    fun summary(fails: List<Pair<String, String>>): String =
        if (fails.isEmpty()) "订阅下载失败" else "订阅下载失败：" + fails.distinctBy { it.first }.take(3).joinToString("；") { "${it.first}：${it.second}" }

    /** Body kind: "yaml" (Clash config), "links" (base64 / share links mihomo can parse), "html" (a web page), "" (unknown). */
    fun kind(body: String): String {
        val t = body.trim()
        if (Regex("(?m)^proxies\\s*:").containsMatchIn(t) || Regex("(?m)^proxy-providers\\s*:").containsMatchIn(t)) return "yaml"
        if (t.startsWith("<!") || t.startsWith("<html", true)) return "html"
        if (Regex("(?m)^(vmess|vless|trojan|ss|ssr|hysteria2?|hy2|tuic|anytls)://").containsMatchIn(t)) return "links"
        val compact = t.replace(Regex("\\s"), "")
        if (compact.length >= 16 && Regex("^[A-Za-z0-9+/=_\\-]+$").matches(compact)) {
            val dec = runCatching { String(java.util.Base64.getMimeDecoder().decode(compact.replace('-', '+').replace('_', '/')), Charsets.UTF_8) }.getOrNull().orEmpty()
            if (Regex("(?m)^(vmess|vless|trojan|ss|ssr|hysteria2?|hy2|tuic|anytls)://").containsMatchIn(dec)) return "links"
        }
        return ""
    }

    fun countLinks(body: String): Int {
        val t = body.trim()
        val dec = if (Regex("(?m)^[a-z0-9]+://").containsMatchIn(t)) t else runCatching {
            String(java.util.Base64.getMimeDecoder().decode(t.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')), Charsets.UTF_8)
        }.getOrDefault("")
        return Regex("(?m)^(vmess|vless|trojan|ss|ssr|hysteria2?|hy2|tuic|anytls)://").findAll(dec).count()
    }

    /** Config that lets mihomo parse share links itself from [providerPath] (written next to config.yaml). */
    fun providerConfig(header: String, providerPath: String): String = header +
        "mode: rule\nlog-level: info\nipv6: false\n" +
        // [38.9.11.3] live node for the sandbox: real 204 check every 60 s (subscriptions can hold many nodes), not lazy, 3 s timeout
        "proxy-providers:\n  订阅:\n    type: file\n    path: $providerPath\n    health-check:\n      enable: true\n      url: https://www.gstatic.com/generate_204\n      interval: 60\n      timeout: 3000\n      lazy: false\n      expected-status: 204\n" +
        "proxy-groups:\n  - name: 自动选择\n    type: url-test\n    use: [订阅]\n    url: https://www.gstatic.com/generate_204\n    expected-status: 204\n    interval: 60\n    timeout: 3000\n    max-failed-times: 2\n    tolerance: 80\n    lazy: false\n" +
        "  - name: 代理\n    type: select\n    proxies: [自动选择]\n    use: [订阅]\n" +
        "rules:\n  - GEOIP,LAN,DIRECT\n  - DOMAIN-SUFFIX,cn,DIRECT\n  - MATCH,代理\n"

    /** A subscription link in pasted / clipboard text or a clash://install-config deep link, else null. */
    fun findLink(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        Regex("(?i)(?:clash|clashmeta|mihomo)://install-config\\?(?:[^ ]*&)?url=([^&\\s]+)").find(t)?.let { m ->
            return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()?.takeIf { it.startsWith("http", true) }
        }
        val urls = Regex("(?i)https?://[^\\s\"'<>，。]+").findAll(t).map { it.value.trimEnd(')', ']', '>', '.', ',') }.toList()
        return urls.firstOrNull { u ->
            val l = u.lowercase()
            Regex("(/sub(/|\\?|$)|/subscri|clash|mihomo|/link/|/api/v1/client|[?&]token=|\\.ya?ml(\\?|$))").containsMatchIn(l)
        }
    }

    data class Provider(val name: String, val vehicle: String, val nodes: Int)

    /** GET /providers/proxies of a mihomo / CMFA controller → providers with their node counts (no node secrets in it). */
    fun parseProviders(json: String): List<Provider> = runCatching {
        val p = JSONObject(json).optJSONObject("providers") ?: return emptyList()
        p.keys().asSequence().mapNotNull { k ->
            val o = p.optJSONObject(k) ?: return@mapNotNull null
            val v = o.optString("vehicleType")
            if (v.equals("Compatible", true) && k == "default") return@mapNotNull null
            Provider(o.optString("name", k), v, o.optJSONArray("proxies")?.length() ?: 0)
        }.toList()
    }.getOrDefault(emptyList())

    /** GET /proxies → selectable groups (Selector / URLTest / Fallback) with their current node. */
    fun parseGroups(json: String): List<Pair<String, String>> = runCatching {
        val p = JSONObject(json).optJSONObject("proxies") ?: return emptyList()
        p.keys().asSequence().mapNotNull { k ->
            val o = p.optJSONObject(k) ?: return@mapNotNull null
            if (o.optString("type") in setOf("Selector", "URLTest", "Fallback", "LoadBalance") && k != "GLOBAL") k to o.optString("now") else null
        }.toList()
    }.getOrDefault(emptyList())

    /** The switch's subtitle while starting: never a bare 「正在启动…」 for longer than the wait. */
    fun startState(elapsedMs: Long, alive: Boolean, apiUp: Boolean, exit: String, tail: String): String = when {
        apiUp -> "运行中"
        !alive -> "启动失败（$exit）" + (if (tail.isNotBlank()) "：" + scrub(tail).take(80) else "")
        elapsedMs < 8_000 -> "正在启动…"
        else -> "启动超时（${elapsedMs / 1000} 秒没就绪）" + (if (tail.isNotBlank()) "：" + scrub(tail).take(80) else "")
    }

    /** [Mines-38.9.10 i28] one status line after the core died: a plain retry note, and after 3 failed restarts in a row the
     *  real reason (exit code + the core's last output, secrets scrubbed) so the user sees why it does not come back */
    fun restartLine(failures: Int, exit: String, tail: String, nextInS: Long): String =
        if (failures < 3) "内核退出（$exit），$nextInS 秒后重启"
        else "自动重启失败 $failures 次（$exit" + (if (tail.isNotBlank()) "：" + scrub(tail).take(60) else "") + "），$nextInS 秒后再试"
}

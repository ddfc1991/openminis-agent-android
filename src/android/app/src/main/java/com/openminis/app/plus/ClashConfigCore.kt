package com.openminis.app.plus

/**
 * [Mines-38.9.6 i14] Clash (mihomo) config text, pure (unit-tested in ClashConfigCoreTest). Mines owns the listening keys:
 * an imported subscription / YAML keeps its proxies, groups and rules, but its ports, LAN access, controller, secret and any
 * TUN block are replaced so the core only ever listens on 127.0.0.1 (mixed port + REST controller with a secret). No TUN.
 */
object ClashConfigCore {
    const val MIXED_PORT = 7898
    const val CONTROLLER_PORT = 9097

    /** top-level keys Mines always sets itself (an imported value is dropped together with its indented block) */
    private val OWNED = setOf("port", "socks-port", "mixed-port", "redir-port", "tproxy-port", "allow-lan", "bind-address",
        "external-controller", "external-controller-tls", "external-controller-unix", "external-ui", "secret", "tun",
        "interface-name", "routing-mark", "authentication", "lan-allowed-ips", "lan-disallowed-ips")

    fun header(secret: String): String = """
        |mixed-port: $MIXED_PORT
        |allow-lan: false
        |bind-address: 127.0.0.1
        |external-controller: 127.0.0.1:$CONTROLLER_PORT
        |secret: "${esc(secret)}"
        |""".trimMargin()

    /** Drops [OWNED] top-level keys (and their nested lines) from [yaml] and puts Mines' header first. */
    fun override(yaml: String, secret: String): String {
        val out = StringBuilder(header(secret))
        var skipping = false
        for (line in yaml.replace("\r\n", "\n").lines()) {
            val top = line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#") && !line.startsWith("-")
            if (top) {
                val key = line.substringBefore(':').trim().trim('"', '\'')
                skipping = key in OWNED
            }
            if (!skipping) out.append(line).append('\n')
        }
        return out.toString()
    }

    /** One upstream proxy as a Clash node (type http / socks5, optional user + password). */
    data class Node(val name: String, val type: String, val host: String, val port: Int, val user: String = "", val pass: String = "")

    /**
     * Config generated from 自己填代理 (+ backups): a fallback group (checks every 60 s, lazy) over the nodes, Google /
     * Gmail forced through it, China sites direct, everything else (only what the split proxy already sends upstream)
     * through the group.
     */
    fun fromNodes(nodes: List<Node>, secret: String): String {
        val sb = StringBuilder(header(secret))
        sb.append("mode: rule\nlog-level: warning\nipv6: false\n")
        sb.append("proxies:\n")
        for (n in nodes) {
            sb.append("  - name: \"${esc(n.name)}\"\n    type: ${if (n.type == "socks5") "socks5" else "http"}\n    server: ${n.host}\n    port: ${n.port}\n")
            if (n.user.isNotEmpty()) sb.append("    username: \"${esc(n.user)}\"\n    password: \"${esc(n.pass)}\"\n")
        }
        val names = nodes.joinToString(", ") { "\"${esc(it.name)}\"" }
        sb.append("proxy-groups:\n")
        sb.append("  - name: 自动选择\n    type: url-test\n    proxies: [$names]\n$HC    tolerance: 80\n")
        sb.append("  - name: 代理\n    type: fallback\n    proxies: [自动选择, $names]\n$HC")
        sb.append("rules:\n")
        for (d in listOf("google.com", "gmail.com", "googlemail.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "youtube.com", "ggpht.com"))
            sb.append("  - DOMAIN-SUFFIX,$d,代理\n")
        for (d in listOf("cn", "baidu.com", "qq.com", "aliyun.com", "taobao.com", "bilibili.com", "163.com", "weixin.qq.com"))
            sb.append("  - DOMAIN-SUFFIX,$d,DIRECT\n")
        sb.append("  - MATCH,代理\n")
        return sb.toString()
    }

    /**
     * [38.9.11.3] user: the embedded Clash is there so the sandbox goes through the proxy — the group must always sit on a
     * LIVE node: a real HTTP check (generate_204 must answer 204 within 3 s) every 30 s, not lazy (checked even when idle),
     * a node failing twice is skipped until it passes again. url-test picks the fastest live node; fallback the first live.
     */
    const val HC = "    url: https://www.gstatic.com/generate_204\n    expected-status: 204\n    interval: 30\n    timeout: 3000\n    max-failed-times: 2\n    lazy: false\n"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** A subscription body is base64 node links (not Clash YAML) when it has no "proxies:" key. */
    fun looksLikeClashYaml(text: String): Boolean = Regex("(?m)^proxies\\s*:").containsMatchIn(text) || Regex("(?m)^proxy-providers\\s*:").containsMatchIn(text)
}

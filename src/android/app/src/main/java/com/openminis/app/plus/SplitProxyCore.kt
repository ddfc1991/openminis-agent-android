package com.openminis.app.plus

/**
 * [Mines-38.9.6 i12] Pure helpers for the authenticated upstream proxy (unit-tested in SplitProxyCoreTest):
 * Basic credentials (RFC 7617, UTF-8), the header injected into a forwarded request, and "http://user:pass@host:port"
 * parsing for the 自己填代理 fields. Nothing here logs; [Parsed.toString] hides the password.
 */
object SplitProxyCore {
    fun basic(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** the whole header line (with CRLF) or "" when there is no user */
    fun authLine(user: String, pass: String): String = if (user.isEmpty()) "" else "Proxy-Authorization: ${basic(user, pass)}\r\n"

    /** Adds [authLine] to a request head (replacing a client's own Proxy-Authorization); keeps the blank line at the end. */
    fun withAuth(head: String, authLine: String): String {
        if (authLine.isEmpty()) return head
        val i = head.indexOf("\r\n")
        if (i < 0) return head
        val rest = head.substring(i + 2).replace(Regex("(?im)^proxy-authorization:.*\\r\\n"), "")
        return head.substring(0, i + 2) + authLine + rest
    }

    data class Parsed(val type: String, val host: String, val port: Int, val user: String, val pass: String) {
        override fun toString() = "Parsed($type://$host:$port${if (user.isNotEmpty()) " auth" else ""})"
    }

    /** "http://u:p@h:24445", "socks5://h:1080", "u:p@h:3128", "h:8080" → parts (user / pass URL-decoded); null if no host/port. */
    fun parse(input: String): Parsed? {
        var s = input.trim()
        if (s.isEmpty()) return null
        val type = when {
            s.startsWith("socks5://", true) || s.startsWith("socks://", true) -> "socks5"
            else -> "http"
        }
        s = s.substringAfter("://", s).trimEnd('/')
        val at = s.lastIndexOf('@')
        val cred = if (at >= 0) s.substring(0, at) else ""
        val hp = if (at >= 0) s.substring(at + 1) else s
        val host = hp.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = hp.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        fun dec(x: String) = runCatching { java.net.URLDecoder.decode(x, "UTF-8") }.getOrDefault(x)
        val user = dec(cred.substringBefore(':', cred)); val pass = if (cred.contains(':')) dec(cred.substringAfter(':')) else ""
        return Parsed(type, host, port, user, pass)
    }

    /**
     * [Mines-38.9.6 i14] user 「沙箱老是连不上gmail或者浏览器上网就卡」: a failed check / connection keeps a proxy out only for a
     * short, growing pause (5 s, 10 s, 20 s, 40 s, then 60 s max), after which it is tried again (and re-probed in the
     * background) — it is never written off for a whole minute after one hiccup.
     */
    fun backoffMs(fails: Int): Long = if (fails <= 0) 0L else minOf(60_000L, 5_000L shl minOf(fails - 1, 4))

    /** usable now: never failed, or its pause is over */
    fun usable(fails: Int, lastFailAt: Long, now: Long): Boolean = fails <= 0 || now - lastFailAt >= backoffMs(fails)

    /** Google hosts (Gmail, sign-in, APIs, static): blocked on a direct route in China, so they never silently go direct. */
    private val GOOGLE = listOf("google.com", "gmail.com", "googlemail.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
        "google.com.hk", "googlevideo.com", "youtube.com", "ggpht.com", "withgoogle.com", "recaptcha.net")
    fun isGoogle(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return GOOGLE.any { h == it || h.endsWith(".$it") }
    }

    /** what the WebView / sandbox gets for a Google host when no proxy works (instead of a hang on a dead direct route) */
    const val DOWN_BODY = "代理不可用：代理服务器暂时连不上，Google 不能直连，稍后会自动重试。"
    fun downResponse(): String {
        val b = DOWN_BODY.toByteArray(Charsets.UTF_8)
        return "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
    }

    // ── [Mines-38.9.10 i28] real proxy health check ──
    data class ProbeOutcome(val reachable: Boolean, val protocolOk: Boolean, val statusCode: Int?, val detail: String?)

    /** Build a bounded HTTP CONNECT request. Host/port are destination inputs, never proxy credentials. */
    fun httpConnectRequest(host: String, port: Int, authLine: String = ""): String {
        require(ProxyEndpointCore.validHost(host) && validDestination(host, port)) { "invalid destination" }
        require(authLine.isEmpty() || Regex("^Proxy-Authorization: Basic [A-Za-z0-9+/=]*\\r\\n$").matches(authLine)) { "invalid auth header" }
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${authLine}\r\n"
    }

    /** A SOCKS connect target stays unresolved locally so DNS is performed by the SOCKS proxy. */
    fun socksAddress(host: String, port: Int): java.net.InetSocketAddress {
        require(ProxyEndpointCore.validHost(host) && validDestination(host, port)) { "invalid destination" }
        return java.net.InetSocketAddress.createUnresolved(host, port)
    }

    fun httpConnectAccepted(statusLine: String): Boolean =
        statusCode(statusLine) == 200

    private fun validDestination(host: String, port: Int): Boolean =
        host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/\\?#@" } && port in 1..65535


    /** 204 is the expected payload. Any HTTP status proves a TCP/HTTP peer was reached. */
    fun probeOutcome(code: Int): ProbeOutcome = when {
        code == 204 -> ProbeOutcome(true, true, code, null)
        code in 100..599 -> ProbeOutcome(true, false, code, "已连通，但探测端点返回 HTTP $code（预期 204）")
        else -> ProbeOutcome(false, false, code, probeReason(null, code))
    }

    /** Host label for diagnostics; strip any accidental user-info and control characters. */
    fun endpoint(type: String, host: String, port: Int): String {
        // Host fields should be DNS/IP literals only. Drop URL/path/query/control syntax so a
        // malformed or credential-bearing value cannot leak tokens into a diagnostic label.
        val rawHost = host.trim()
        val pathOrQuery = rawHost.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = (if (pathOrQuery >= 0) rawHost.substring(0, pathOrQuery) else rawHost).substringAfterLast('@')
        val safeHost = authority.filter { it.isLetterOrDigit() || it in ".:-_[]" }.trim().removePrefix("[").removeSuffix("]")
        val shownHost = if (safeHost.contains(':')) "[$safeHost]" else safeHost
        val safeType = type.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(16).ifBlank { "proxy" }
        return "$safeType://$shownHost:$port"
    }

    /** "HTTP/1.1 204 No Content" → 204 */
    fun statusCode(line: String): Int? = Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})""").find(line.trim())?.groupValues?.get(1)?.toIntOrNull()
    /** dedupe key: the same proxy listed twice (localhost / 127.0.0.1, http / socks5 on one port) */
    fun sameProxyKey(host: String, port: Int): String {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        val n = if (h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1") "127.0.0.1" else h
        return "$n:$port"
    }
    /** a failed check → the raw reason kept for [plainProxyReason]-style display (Chinese first, technical tail) */
    fun probeReason(error: String?, code: Int): String = when {
        code in 300..399 -> "被重定向（HTTP $code）：探测端点返回了重定向，不是预期的 204"
        code == 400 -> "已连通，但探测端点返回 HTTP 400（预期 204）"
        code > 0 -> "探测端点返回 HTTP $code（预期 204）"
        error == null -> "没有回应"
        error.contains("SSLHandshake", true) || error.contains("handshake", true) -> "加密握手失败：代理接受了连接，但没真正连到外网（$error）"
        error.contains("closed before", true) || error.contains("reset", true) || error.contains("EOF", true) -> "连上后被断开：代理自己的外网可能断了（$error）"
        error.contains("refused", true) -> "代理没在运行（connection refused）"
        error.contains("timed out", true) || error.contains("timeout", true) -> "太慢，超时了（$error）"
        error.contains("407") -> "需要账号密码（407）"
        else -> error
    }
}

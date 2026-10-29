package com.openminis.app.plus

/** Pure validation shared by the sandbox proxy CLI and unit tests. It accepts an endpoint, never a URL or credentials. */
object ProxyEndpointCore {
    data class Endpoint(val type: String, val host: String, val port: Int)

    fun parse(type: String, host: String, portText: String): Endpoint? {
        val protocol = type.trim().lowercase()
        if (protocol !in setOf("http", "socks5")) return null
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        if (!validHost(normalizedHost)) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Endpoint(protocol, normalizedHost, port)
    }

    fun validHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        if (host.any { it.isWhitespace() || it in "/\\?#@%" || it.code < 0x21 }) return false
        if (host.contains(':')) {
            // A colon is accepted only for a syntactically valid IPv6 literal; getByName does not
            // perform DNS lookup for colon-containing literals.
            return runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false)
        }
        if (host.startsWith(".") || host.endsWith(".") || ".." in host) return false
        return host.split('.').all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' } && label.all { it.code < 128 }
        }
    }

    fun label(endpoint: Endpoint): String =
        SplitProxyCore.endpoint(endpoint.type, endpoint.host, endpoint.port)
}

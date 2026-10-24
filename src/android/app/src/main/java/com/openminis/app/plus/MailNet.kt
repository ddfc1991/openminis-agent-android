package com.openminis.app.plus

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:27 「Gmail 登不上」. The IMAP client opened a raw TLS socket straight to
 * imap.gmail.com:993: that bypasses Mines' 分流代理 (its ProxySelector only covers HTTP clients), and from a mainland
 * network a direct Gmail connection is cut → 「连不上」 though the app password was right. Now: foreign mail hosts go
 * through the local split proxy (HTTP CONNECT on 127.0.0.1:7899) first when it runs, else direct with the proxy as the
 * fallback; the error says which way failed. The app password itself is cleaned (spaces / NBSP / zero-width) everywhere.
 */
object MailNet {
    private val FOREIGN = listOf("gmail.com", "googlemail.com", "outlook.office365.com", "office365.com", "outlook.com", "yahoo.com", "icloud.com", "me.com")
    fun foreign(host: String): Boolean = FOREIGN.any { host.equals(it, true) || host.endsWith(".$it", true) }

    private fun tls(raw: Socket, host: String, port: Int): SSLSocket =
        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket).apply { soTimeout = 15_000; startHandshake() }

    private fun direct(host: String, port: Int, timeoutMs: Int): SSLSocket =
        tls(Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = 15_000 }, host, port)

    private fun viaProxy(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", SplitProxy.PORT), 3000); soTimeout = 15_000 }
        try {
            s.getOutputStream().apply { write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); flush() }
            val inp = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && head.length < 4096) { val b = inp.read(); if (b < 0) break; head.append(b.toChar()) }
            val status = head.lineSequence().firstOrNull().orEmpty()
            if (!Regex("^HTTP/1\\.[01] 200").containsMatchIn(status)) throw IOException("代理没接通（${status.take(40)}）")
            return tls(s, host, port)
        } catch (e: Exception) { runCatching { s.close() }; throw e }
    }

    /** TLS socket to [host]:[port], through the split proxy when that is the way that works. */
    fun open(host: String, port: Int = 993, timeoutMs: Int = 8000): SSLSocket {
        val proxy = runCatching { SplitProxy.active }.getOrDefault(false)
        val order = if (proxy && foreign(host)) listOf(true, false) else if (proxy) listOf(false, true) else listOf(false)
        var last: Exception? = null
        for (p in order) {
            try { return if (p) viaProxy(host, port, timeoutMs) else direct(host, port, timeoutMs) } catch (e: Exception) { last = e }
        }
        val hint = if (foreign(host) && !proxy) "：国内网络直连 $host 会被拦，开 VPN，或在 设置 › 网络 › 分流代理 填上游代理" else ""
        throw IOException("连不上 $host（${last?.javaClass?.simpleName ?: "网络"}）$hint", last)
    }
}

package com.openminis.app.plus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production upstream connector and carries bytes through loopback-only proxy fixtures. */
class SplitProxyTunnelIntegrationTest {
    @Test fun httpConnectCarriesPayloadThroughTheUpstream() = verifyTunnel("http")

    @Test fun socks5ConnectCarriesPayloadThroughTheUpstream() = verifyTunnel("socks5")

    private fun verifyTunnel(type: String) {
        val payload = "proxy-data-roundtrip/$type".toByteArray(Charsets.UTF_8)
        val loopback = InetAddress.getByName("127.0.0.1")
        val targetFailure = AtomicReference<Throwable?>()
        val proxyFailure = AtomicReference<Throwable?>()
        val handshakeComplete = java.util.concurrent.atomic.AtomicBoolean(false)

        ServerSocket(0, 1, loopback).use { targetServer ->
            val targetThread = Thread {
                try {
                    targetServer.accept().use { target ->
                        target.soTimeout = 3_000
                        val received = ByteArray(payload.size)
                        readFully(target.getInputStream(), received)
                        assertArrayEquals(payload, received)
                        target.getOutputStream().apply { write(payload); flush() }
                    }
                } catch (t: Throwable) {
                    targetFailure.set(t)
                }
            }.apply { isDaemon = true; name = "proxy-test-target"; start() }

            ServerSocket(0, 1, loopback).use { proxyServer ->
                val proxyThread = Thread {
                    try {
                        proxyServer.accept().use { client ->
                            client.soTimeout = 3_000
                            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                            val destination = if (type == "http") {
                                readHttpConnect(input)
                            } else {
                                readSocksConnect(input, client)
                            }
                            val upstream = Socket(Proxy.NO_PROXY)
                            try {
                                upstream.connect(InetSocketAddress(destination.first, destination.second), 2_000)
                                upstream.soTimeout = 3_000
                                if (type == "http") {
                                    client.getOutputStream().apply {
                                        write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                                        flush()
                                    }
                                } else {
                                    client.getOutputStream().apply {
                                        write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                                        flush()
                                    }
                                }
                                handshakeComplete.set(true)
                                relay(client, upstream)
                            } finally {
                                runCatching { upstream.close() }
                            }
                        }
                    } catch (t: Throwable) {
                        if (!handshakeComplete.get()) proxyFailure.set(t)
                    }
                }.apply { isDaemon = true; name = "proxy-test-upstream"; start() }

                SplitProxy.tunnelVia(
                    SplitProxy.Upstream(type, "127.0.0.1", proxyServer.localPort),
                    "127.0.0.1",
                    targetServer.localPort,
                    timeout = 2_000,
                ).use { tunnel ->
                    tunnel.soTimeout = 3_000
                    tunnel.getOutputStream().apply { write(payload); flush() }
                    val echoed = ByteArray(payload.size)
                    readFully(tunnel.getInputStream(), echoed)
                    assertArrayEquals(payload, echoed)
                }

                proxyThread.join(3_500)
                targetThread.join(3_500)
                assertFalse("loopback proxy fixture did not finish", proxyThread.isAlive)
                assertFalse("loopback target fixture did not finish", targetThread.isAlive)
                proxyFailure.get()?.let { throw AssertionError("$type upstream handshake/forwarding failed", it) }
                targetFailure.get()?.let { throw AssertionError("$type target did not receive the payload", it) }
            }
        }
    }

    private fun readHttpConnect(input: InputStream): Pair<String, Int> {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (head.size() < 8_192 && matched < 4) {
            val b = input.read()
            if (b < 0) throw AssertionError("HTTP CONNECT ended before headers")
            head.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
        }
        assertEqualsHeaderEnd(matched, "HTTP CONNECT")
        val first = head.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n")
        val parts = first.split(' ')
        if (parts.size != 3 || parts[0] != "CONNECT") throw AssertionError("unexpected HTTP proxy request: $first")
        val authority = parts[1]
        val split = authority.substringAfterLast(':')
        val host = authority.substringBeforeLast(':')
        return host.removePrefix("[").removeSuffix("]") to (split.toIntOrNull() ?: throw AssertionError("invalid CONNECT port"))
    }

    private fun readSocksConnect(input: DataInputStream, client: Socket): Pair<String, Int> {
        if (input.readUnsignedByte() != 5) throw AssertionError("not a SOCKS5 greeting")
        val count = input.readUnsignedByte()
        val methods = ByteArray(count); input.readFully(methods)
        if (methods.none { it.toInt() == 0 }) throw AssertionError("proxy client did not offer no-auth")
        client.getOutputStream().apply { write(byteArrayOf(5, 0)); flush() }

        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1) throw AssertionError("not a SOCKS5 CONNECT request")
        input.readUnsignedByte() // reserved
        val host = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).let { address -> input.readFully(address); InetAddress.getByAddress(address).hostAddress }
            3 -> ByteArray(input.readUnsignedByte()).let { address -> input.readFully(address); String(address, Charsets.US_ASCII) }
            4 -> ByteArray(16).let { address -> input.readFully(address); InetAddress.getByAddress(address).hostAddress }
            else -> throw AssertionError("unsupported SOCKS address type")
        }
        val port = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        return host to port
    }

    private fun relay(client: Socket, upstream: Socket) {
        val downstream = Thread {
            runCatching { copy(upstream.getInputStream(), client.getOutputStream()) }
            runCatching { client.shutdownOutput() }
        }.apply { isDaemon = true; name = "proxy-test-downstream"; start() }
        runCatching { copy(client.getInputStream(), upstream.getOutputStream()) }
        runCatching { upstream.shutdownOutput() }
        downstream.join(3_000)
    }

    private fun copy(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(1_024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            output.write(buffer, 0, n)
            output.flush()
        }
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw AssertionError("unexpected EOF after $offset/${bytes.size} bytes")
            offset += n
        }
    }

    private fun assertEqualsHeaderEnd(matched: Int, label: String) {
        assertEquals("$label did not terminate with CRLF CRLF", 4, matched)
    }
}

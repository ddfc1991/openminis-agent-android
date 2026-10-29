package com.openminis.app.plus

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class ProxyEndpointCoreTest {
    @Test fun acceptsExplicitHttpAndSocksEndpointsButRejectsCredentialUrls() {
        assertEquals(ProxyEndpointCore.Endpoint("http", "proxy.example.net", 8080), ProxyEndpointCore.parse("HTTP", "proxy.example.net", "8080"))
        assertEquals(ProxyEndpointCore.Endpoint("socks5", "proxy.example.net", 19080), ProxyEndpointCore.parse("socks5", "proxy.example.net", "19080"))
        assertNull(ProxyEndpointCore.parse("socks", "host", "19080"))
        assertNull(ProxyEndpointCore.parse("http", "http://user:pass@host", "8080"))
        assertNull(ProxyEndpointCore.parse("http", "host", "70000"))
        assertNull(ProxyEndpointCore.parse("http", "host/path", "8080"))
        assertNull(ProxyEndpointCore.parse("http", "bad_host", "8080"))
        assertNull(ProxyEndpointCore.parse("http", "bad..host", "8080"))
        assertNull(ProxyEndpointCore.parse("http", "2001:::1", "8080"))
        assertNotNull(ProxyEndpointCore.parse("http", "2001:db8::1", "8080"))
    }

    @Test fun actualHttpTunnelRequestHandlesLocalMockAndFailureStatus() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val received = AtomicReference<String>()
            val worker = Thread {
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    val r = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.ISO_8859_1))
                    val lines = mutableListOf<String>()
                    while (true) { val line = r.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    received.set(lines.joinToString("\n"))
                    peer.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    peer.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }
            Socket().use { client ->
                client.connect(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), server.localPort), 1000)
                client.soTimeout = 2000
                client.getOutputStream().write(SplitProxyCore.httpConnectRequest("target.example", 443).toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val status = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1)).readLine()
                assertTrue(SplitProxyCore.httpConnectAccepted(status))
            }
            worker.join(2000)
            assertTrue(received.get().contains("CONNECT target.example:443 HTTP/1.1"))
            assertTrue(received.get().contains("Host: target.example:443"))
            assertFalse(received.get().contains("Proxy-Authorization"))
            assertFalse(SplitProxyCore.httpConnectAccepted("HTTP/1.1 407 Proxy Authentication Required"))
        }
    }

    @Test fun javaSocksClientPerformsRemoteDnsDomainHandshakeAgainstLocalMock() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val requested = AtomicReference<String>()
            val worker = Thread {
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    val input = BufferedInputStream(peer.getInputStream())
                    val greeting = ByteArray(2); readFully(input, greeting)
                    assertArrayEquals(byteArrayOf(5, 1), greeting)
                    val method = ByteArray(1); readFully(input, method)
                    assertEquals(0, method[0].toInt())
                    peer.getOutputStream().write(byteArrayOf(5, 0)); peer.getOutputStream().flush()
                    val head = ByteArray(4); readFully(input, head)
                    assertEquals(5, head[0].toInt()); assertEquals(1, head[1].toInt()); assertEquals(3, head[3].toInt())
                    val length = ByteArray(1); readFully(input, length)
                    val host = ByteArray(length[0].toInt() and 0xff); readFully(input, host)
                    val port = ByteArray(2); readFully(input, port)
                    requested.set(String(host, Charsets.US_ASCII) + ":" + (((port[0].toInt() and 0xff) shl 8) or (port[1].toInt() and 0xff)))
                    peer.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); peer.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), server.localPort))).use { client ->
                client.connect(SplitProxyCore.socksAddress("dns-only.example", 443), 1000)
            }
            worker.join(2000)
            assertEquals("dns-only.example:443", requested.get())
            assertTrue(SplitProxyCore.socksAddress("dns-only.example", 443).isUnresolved)
        }
    }

    private fun readFully(input: BufferedInputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw AssertionError("unexpected EOF")
            offset += n
        }
    }
}

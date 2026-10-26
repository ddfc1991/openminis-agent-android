package com.openminis.app.network

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * [Mines-38.9.14.1 net_drop] every LLM provider socket gets SO_KEEPALIVE, so a long silent stream (a model thinking for
 * minutes) still carries TCP keepalives through NAT / proxies / VPN that drop idle flows. Together with OkHttp's HTTP/2
 * pingInterval this keeps an idle-but-alive stream from being cut. Plain delegation otherwise (same as SocketFactory.getDefault()).
 */
object KeepAliveSocketFactory : SocketFactory() {
    private val d: SocketFactory = SocketFactory.getDefault()
    private fun ka(s: Socket): Socket = s.apply { runCatching { keepAlive = true; tcpNoDelay = true } }
    override fun createSocket(): Socket = ka(d.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = ka(d.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = ka(d.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = ka(d.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = ka(d.createSocket(address, port, localAddress, localPort))

    /** HTTP/2 PING every 20 s on provider connections (a dead connection is noticed and evicted instead of reused) */
    const val PING_S = 20L
}

package com.openminis.app.provider.openai

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * [Mines-38.9.9] user: every 快速测试 on a paid relay ended in 「请求失败：connection closed」. OkHttp says that when the server
 * (or a proxy in front of it) drops a pooled / HTTP/2 connection before any answer (stream reset, unexpected end of stream,
 * "connection closed"). Such a request is retried once on a NEW HTTP/1.1 connection (no pool reuse); a host where that
 * helped keeps using HTTP/1.1 for this app run. Messages become plain Chinese. Pure parts are unit-tested.
 */
object ConnRetry {
    private val h1Hosts = ConcurrentHashMap.newKeySet<String>()
    /** [38.9.11.3] one HTTP/1.1 client per route (proxy), so the direct / proxy choice of a request is kept */
    private val h1Clients = ConcurrentHashMap<String, OkHttpClient>()

    fun useH1(host: String): Boolean = host.lowercase() in h1Hosts
    fun rememberH1(host: String) { h1Hosts.add(host.lowercase()) }

    /** the server / proxy hung up before answering (worth one retry on a fresh connection) */
    fun isDropped(e: Throwable): Boolean = isDroppedText(e.javaClass.simpleName + " " + (e.message ?: ""))

    fun isDroppedText(t: String): Boolean {
        val l = t.lowercase()
        return l.contains("connection closed") || l.contains("unexpected end of stream") || l.contains("streamresetexception")
            || l.contains("stream was reset") || l.contains("connection reset") || l.contains("broken pipe")
            || l.contains("eofexception") || l.contains("socket closed") || (l.contains("closed") && l.contains("ioexception"))
    }

    /** one short Chinese line for a dropped / failed connection */
    fun zh(e: Throwable): String = zhText(e.javaClass.simpleName + " " + (e.message ?: ""))

    fun zhText(t: String): String {
        val l = t.lowercase()
        return when {
            l.contains("stream was reset") || l.contains("streamreset") -> "服务器中途重置了连接（HTTP/2 流被重置），多半是中转站或代理不稳"
            l.contains("connection reset") -> "连接被对方重置：服务器或代理中途断开"
            l.contains("unexpected end of stream") || l.contains("eof") -> "服务器没回完就断开了（回复不完整）"
            l.contains("connection closed") || l.contains("socket closed") || l.contains("closed") -> "服务器中途断开连接（还没回复就关了），可能是中转站限流、代理掐断或接口地址不对"
            l.contains("broken pipe") -> "发送请求时连接断了"
            else -> "连接出错：" + t.trim().take(120)
        }
    }

    /** same settings as the provider's client, HTTP/1.1 only and a private, non-reused pool */
    fun h1(base: OkHttpClient): OkHttpClient = h1Clients.getOrPut(base.proxy.toString()) {
        base.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }
}

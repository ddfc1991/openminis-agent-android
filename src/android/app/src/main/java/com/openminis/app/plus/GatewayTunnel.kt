package com.openminis.app.plus

import android.content.Context
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.1] user 2026-10-16 (WorkBuddy 「本机网关 127.0.0.1:8788」 → 「连不上（网络问题，可能要开代理）」): 127.0.0.1 on
 * the phone is the phone itself, but the user's model gateway runs on a server. When a 127.0.0.1 / localhost endpoint
 * refuses, Mines looks for the port on the user's OWN servers (设置 › 网络 › 电脑与服务器 — nothing built in), and when
 * one is listening there it opens an SSH local forward (phone 127.0.0.1:<port> → server 127.0.0.1:<port>) over the
 * existing JSch session (keepalive 15 s × 4) and retries. The forward is re-made when the session drops.
 */
object GatewayTunnel {
    private class T(val serverId: String, val name: String, val session: Session)
    private val open = HashMap<Int, T>()
    @Volatile var lastNote: String? = null

    fun isLocal(base: String): Pair<String, Int>? {
        val m = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1\\]):(\\d+)", RegexOption.IGNORE_CASE).find(base.trim()) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    /** Name of the server the port is now forwarded to, or null (no server of the user has it / no server set up). */
    suspend fun ensure(c: Context, port: Int): String? = withContext(Dispatchers.IO) {
        synchronized(open) { open[port]?.let { t -> if (t.session.isConnected) return@withContext t.name else open.remove(port) } }
        val app = c.applicationContext
        PlusPrefs.init(app)
        val servers = PlusPrefs.state.value.servers.filter { it.enabled && it.host.isNotBlank() }
        for (sv in servers) {
            val listening = runCatching {
                VpsSsh.run(app, sv, "(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -E '[:.]$port[[:space:]]' >/dev/null && echo MINES_YES", 12_000, 1).first.output
            }.getOrNull()?.contains("MINES_YES") == true
            if (!listening) continue
            val s = runCatching { VpsSsh.openAny(app, sv) }.getOrNull() ?: continue
            val ok = runCatching { s.setPortForwardingL("127.0.0.1", port, "127.0.0.1", port); true }.getOrDefault(false)
            if (!ok) { runCatching { s.disconnect() }; continue }
            synchronized(open) { open[port] = T(sv.id, sv.name.ifBlank { sv.host }, s) }
            lastNote = "已经通过 SSH 接到 ${sv.name.ifBlank { sv.host }} 的 $port 端口"
            return@withContext sv.name.ifBlank { sv.host }
        }
        null
    }

    fun close(port: Int) { synchronized(open) { open.remove(port)?.let { runCatching { it.session.disconnect() } } } }

    /** Specific words for a failed call to [base] (never a vague 「可能要开代理」 unless a proxy really is involved). */
    fun explain(base: String, msg: String, proxyOn: Boolean, triedTunnel: Boolean): String {
        val m = msg.lowercase()
        val host = runCatching { java.net.URI(base).host + ":" + java.net.URI(base).port.let { if (it > 0) it else if (base.startsWith("https")) 443 else 80 } }.getOrDefault(base)
        val local = isLocal(base)
        return when {
            local != null && (m.contains("refused") || m.contains("failed to connect")) ->
                "${local.first}:${local.second} 上没有服务（这是手机本机）" + if (triedTunnel) "；你的服务器上也没找到在 ${local.second} 端口的网关" else "；网关在服务器上的话，到「设置 › 网络 › 电脑与服务器」加上那台服务器，我会自动用 SSH 接过去"
            m.contains("refused") -> "$host 拒绝连接（端口没开或服务没跑）"
            m.contains("timeout") || m.contains("timed out") -> if (proxyOn) "$host 等太久没回复（开着代理：可能被代理拦了，试试让 Mines 直连）" else "$host 等太久没回复（超时）"
            m.contains("unable to resolve") || m.contains("no address") -> if (proxyOn) "找不到 $host（域名解析失败；开着代理，可能是代理的 DNS）" else "找不到 $host（域名解析失败）"
            m.contains("cleartext") -> "$host 用的是 http，系统不让明文连接"
            m.contains("ssl") || m.contains("certificate") || m.contains("handshake") -> "$host 的 HTTPS 证书有问题"
            m.contains("canceled") -> "已停止"
            m.contains("reset") || m.contains("broken pipe") || m.contains("unexpected end") -> "$host 中途断开了连接"
            else -> "连不上 $host（${msg.take(60).ifBlank { "网络错误" }}）"
        }
    }

    fun proxyOn(c: Context?): Boolean = runCatching { c != null && VpsSsh.vpnActive(c) }.getOrDefault(false)
}

package com.openminis.app.plus

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import java.net.InetSocketAddress
import java.net.Socket

/** Explicit sandbox-only management for SplitProxy. No UI, credential output, remote config writes or destination traffic. */
class ProxyOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("help", "h")) return ok(HELP)
        return try {
            SplitProxy.init(context)
            when (args.positional.firstOrNull()?.lowercase() ?: "status") {
                "status" -> status()
                "set" -> setPrimary(args.positional)
                "backup" -> backup(args.positional)
                "route" -> route(args.positional)
                "rule" -> rule(args.positional)
                "probe" -> probe(args.positional)
                else -> invalid("未知命令。用 minis-proxy --help")
            }
        } catch (_: Exception) {
            invalid("操作失败；未输出配置值或敏感输入。")
        }
    }

    private fun status(): NativeOffloadResult {
        val s = SplitProxy.settings.value
        val primary = s.primary?.let { safeEndpoint(it.type, it.host, it.port) } ?: "未设置"
        val backups = s.backups.filter { it.valid }.map { safeEndpoint(it.type, it.host, it.port) }.filter { it != "无效端点" }.joinToString().ifBlank { "无" }
        return ok("沙箱代理：${if (s.enabled && s.forSandbox) "启用" else "关闭"}\n主端点：$primary\n备用：$backups\n默认路由：${s.defaultMode.name.lowercase()}\n分流规则：${s.rules.size} 条\n仅显示端点，不显示认证信息。")
    }

    private fun setPrimary(p: List<String>): NativeOffloadResult {
        if (p.size != 4) return invalid("用法：minis-proxy set <http|socks5> <host> <port>")
        val ep = ProxyEndpointCore.parse(p[1], p[2], p[3]) ?: return invalid("端点格式无效；仅支持 http/socks5、主机名/IP 和 1–65535 端口。")
        // Enabling/updating is limited to sandbox traffic; preserve the user's independent app-routing setting.
        // Clear old endpoint credentials so they cannot be sent to a different host by accident.
        SplitProxy.update(context) { it.copy(enabled = true, upstreamType = ep.type, upstreamHost = ep.host, upstreamPort = ep.port,
            upstreamUser = "", upstreamPass = "", forSandbox = true, defaultMode = SplitProxy.Mode.PROXY) }
        return ok("沙箱主代理已设置为 ${ProxyEndpointCore.label(ep)}；没有发起外网请求。可用 `minis-proxy probe ${ep.host} ${ep.port}` 做 TCP 可达性检查。")
    }

    private fun backup(p: List<String>): NativeOffloadResult {
        return when (p.getOrNull(1)?.lowercase()) {
            "add" -> {
                if (p.size != 5) return invalid("用法：minis-proxy backup add <http|socks5> <host> <port>")
                val ep = ProxyEndpointCore.parse(p[2], p[3], p[4]) ?: return invalid("备用端点格式无效。")
                SplitProxy.update(context) { s ->
                    val next = (s.backups.filterNot { SplitProxyCore.sameProxyKey(it.host, it.port) == SplitProxyCore.sameProxyKey(ep.host, ep.port) } + SplitProxy.Upstream(ep.type, ep.host, ep.port, "CLI备用"))
                    s.copy(enabled = true, forSandbox = true, backups = next)
                }
                ok("沙箱备用端点已加入：${ProxyEndpointCore.label(ep)}；没有发起外网请求。")
            }
            "clear" -> {
                SplitProxy.update(context) { it.copy(backups = emptyList()) }
                ok("备用端点已清空。")
            }
            else -> invalid("用法：minis-proxy backup add|clear …")
        }
    }

    private fun route(p: List<String>): NativeOffloadResult {
        val mode = when (p.getOrNull(1)?.lowercase()) {
            "proxy" -> SplitProxy.Mode.PROXY
            "direct" -> SplitProxy.Mode.DIRECT
            "auto" -> SplitProxy.Mode.AUTO
            else -> return invalid("用法：minis-proxy route <proxy|direct|auto>")
        }
        SplitProxy.update(context) { it.copy(enabled = true, forSandbox = true, defaultMode = mode) }
        return ok("沙箱默认路由已设为 ${mode.name.lowercase()}。不会修改 App 单独的路由开关。")
    }

    private fun rule(p: List<String>): NativeOffloadResult {
        return when (p.getOrNull(1)?.lowercase()) {
            "list" -> ok(SplitProxy.settings.value.rules.mapNotNull { r -> safePattern(r.pattern)?.let { "$it → ${r.mode.name.lowercase()}" } }.joinToString("\n").ifBlank { "暂无有效规则。" })
            "add" -> {
                val pattern = p.getOrNull(2)?.trim()?.removePrefix("*")
                val mode = when (p.getOrNull(3)?.lowercase()) { "proxy" -> SplitProxy.Mode.PROXY; "direct" -> SplitProxy.Mode.DIRECT; else -> null }
                if (pattern.isNullOrBlank() || !ProxyEndpointCore.validHost(pattern.removePrefix(".")) || mode == null)
                    return invalid("用法：minis-proxy rule add <domain|.suffix> <proxy|direct>")
                SplitProxy.update(context) { s -> s.copy(forSandbox = true, rules = (s.rules.filterNot { it.pattern == pattern } + SplitProxy.Rule(pattern, mode))) }
                ok("沙箱分流规则已保存：$pattern → ${mode.name.lowercase()}。")
            }
            "remove" -> {
                val pattern = p.getOrNull(2)?.let(::safePattern) ?: return invalid("规则格式无效。")
                SplitProxy.update(context) { s -> s.copy(rules = s.rules.filterNot { it.pattern == pattern }) }
                ok("已移除匹配规则：$pattern")
            }
            else -> invalid("用法：minis-proxy rule list|add|remove …")
        }
    }

    /** This intentionally checks only TCP connect; no CONNECT, SOCKS handshake, DNS query or target-service access. */
    private fun probe(p: List<String>): NativeOffloadResult {
        if (p.size != 3) return invalid("用法：minis-proxy probe <host> <port>")
        val host = p[1]
        if (!ProxyEndpointCore.validHost(host)) return invalid("主机名/IP 格式无效。")
        val port = p[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return invalid("端口必须是 1–65535。")
        val began = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
            val ms = (System.nanoTime() - began) / 1_000_000
            ok("TCP 可连接：${SplitProxyCore.endpoint("tcp", host, port)}（${ms}ms）。这不证明 HTTP/SOCKS 握手、认证、DNS 或代理转发可用。")
        } catch (e: Exception) {
            val reason = when (e) {
                is java.net.ConnectException -> "连接被拒绝或目标不可达"
                is java.net.SocketTimeoutException -> "连接超时（1.5秒）"
                else -> e.javaClass.simpleName
            }
            ok("TCP 不可连接：${SplitProxyCore.endpoint("tcp", host, port)}；$reason。未发送应用层数据。")
        }
    }

    private fun safeEndpoint(type: String, host: String, port: Int): String =
        ProxyEndpointCore.parse(type, host, port.toString())?.let(ProxyEndpointCore::label) ?: "无效端点"

    private fun safePattern(raw: String): String? {
        val pattern = raw.trim().removePrefix("*")
        val host = pattern.removePrefix(".")
        if (pattern.isBlank() || !ProxyEndpointCore.validHost(host)) return null
        return pattern
    }

    private fun ok(text: String) = NativeOffloadResult(0, text.trimEnd() + "\n")
    private fun invalid(text: String) = NativeOffloadResult(1, text.trimEnd() + "\n")

    companion object {
        private const val HELP = """minis-proxy — 沙箱代理配置与诊断（不会访问目标站点）
用法：
  minis-proxy status
  minis-proxy set <http|socks5> <host> <port>
  minis-proxy backup add <http|socks5> <host> <port>
  minis-proxy backup clear
  minis-proxy route <proxy|direct|auto>
  minis-proxy rule list|add|remove ...
  minis-proxy probe <host> <port>     仅 TCP connect，不做协议/外网探测
示例：
  minis-proxy set http proxy.example.net <port>
  minis-proxy backup add socks5 backup.example.net <port>
认证凭据不进入命令行；此版本只提供无认证端点设置。set 仅启用沙箱代理并清除旧主端点认证，保留 App 自身路由设置。
"""
    }
}

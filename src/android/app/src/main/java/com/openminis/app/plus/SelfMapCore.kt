package com.openminis.app.plus

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * [Mines-38.9.8] 自我地图 (user 2026-10-19 00:01): the agent did not know its own app — which features and settings exist,
 * which providers / 模型组 / agent-loop groups the user configured, and which ports on the user's own servers (工作位置)
 * are reachable from the phone. [SelfMap] gathers that at run time from the user's own config (nothing is hard-coded: the
 * repository is public, so no server address of the user is ever in code) and this pure part renders it: a compact block
 * for the system prompt and a detailed answer for the `app_self` tool. Unit-tested in SelfMapCoreTest.
 */
object SelfMapCore {
    data class Provider(val name: String, val type: String, val enabled: Boolean, val models: Int, val host: String = "")
    data class Group(val name: String, val members: List<String>, val isDefault: Boolean = false, val agentLoop: Boolean = false)
    data class Port(val port: Int, val open: Boolean, val ms: Int = -1, val label: String = "")
    data class Server(val name: String, val host: String, val sshPort: Int, val ports: List<Port>, val probedAt: Long, val note: String = "")
    data class Snapshot(
        val version: String,
        val features: List<String>,
        val providers: List<Provider>,
        val groups: List<Group>,
        val servers: List<Server>,
        val skills: Int = 0,
        val workflows: Int = 0,
    )

    /**
     * Ports worth probing on one server: its SSH port, every `:port` the server's own 「在跑什么」 lines and notes mention
     * (「nginx :8090」, 「:8794 Memoh API」, 「0.0.0.0:7860」) and any the user / agent asked for. Sorted, ≤ [max].
     */
    private val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")

    /** TCP reachability only: HTTP response codes belong to a higher-level protocol probe. */
    fun portReachable(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
        if (host.isBlank() || port !in 1..65535 || timeoutMs <= 0) return false
        return runCatching {
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getInputStream().read() >= 0
            }
        }.getOrDefault(false)
    }

    fun portsFor(sshPort: Int, running: List<String>, notes: List<String>, extra: List<Int> = emptyList(), max: Int = 12): List<Int> {
        val out = LinkedHashSet<Int>()
        if (sshPort in 1..65535) out += sshPort
        extra.filter { it in 1..65535 }.forEach { out += it }
        (running + notes).forEach { l -> Regex("(?<![0-9])[:：]([0-9]{2,5})(?![0-9])").findAll(l.replace(IPV4, "IP")).forEach { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 }?.let(out::add) } }
        return out.toList().take(max).sorted()
    }

    /** What a port line mentions next to `:port` (「8794 Memoh API」 → "Memoh API"), for the label. */
    fun labelFor(port: Int, running: List<String>, notes: List<String>): String {
        val l = (notes + running).firstOrNull { Regex("(?<![0-9])[:：]$port(?![0-9])").containsMatchIn(it.replace(IPV4, "IP")) }?.replace(IPV4, "") ?: return ""
        return l.replace(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"), "").replace(Regex("[:：]$port"), "").replace(Regex("\\s+"), " ").trim(' ', '·', '-', ',', '，').take(30)
    }

    fun ago(now: Long, at: Long): String {
        if (at <= 0L) return "没测过"
        val m = ((now - at) / 60_000L).coerceAtLeast(0)
        return when { m < 1 -> "刚测"; m < 60 -> "${m} 分钟前测"; m < 1440 -> "${m / 60} 小时前测"; else -> "${m / 1440} 天前测" }
    }

    private fun portLine(p: Port) = "${p.port}" + (if (p.open) " 通" else " 不通") + (if (p.label.isNotBlank()) "（${p.label}）" else "")

    /** Compact block for the system prompt (≤ ~1.5 KB). */
    fun promptBlock(s: Snapshot, now: Long): String {
        val sb = StringBuilder("Mines self-map (this app = Mines ${s.version}; facts about the user's own setup, read at run time — use app_self for details or a fresh port check; never guess):\n")
        if (s.features.isNotEmpty()) sb.append("- 功能: ").append(s.features.joinToString("、")).append('\n')
        val on = s.providers.filter { it.enabled }
        if (on.isNotEmpty()) sb.append("- 供应商(").append(on.size).append("): ").append(on.take(12).joinToString("、") { "${it.name}·${it.models}个模型" }).append(if (on.size > 12) " …" else "").append('\n')
        if (s.groups.isNotEmpty()) sb.append("- 模型组: ").append(s.groups.take(8).joinToString("、") { g ->
            g.name + (if (g.isDefault) "(默认)" else "") + (if (g.agentLoop) "(agent loop)" else "") + "[${g.members.size}]" }).append('\n')
        s.servers.take(6).forEach { sv ->
            sb.append("- 工作位置 ").append(sv.name).append(" ").append(sv.host).append(": ")
            if (sv.ports.isEmpty()) sb.append("端口没测过") else sb.append(sv.ports.joinToString("，") { portLine(it) }).append("（手机上").append(ago(now, sv.probedAt)).append("）")
            sb.append('\n')
        }
        sb.append("- 技能 ").append(s.skills).append(" 个 · 工作流记录 ").append(s.workflows).append(" 条")
        return sb.toString()
    }

    /** Detailed answer for the tool ([section] = all / features / providers / groups / servers). */
    fun detail(s: Snapshot, section: String, now: Long): String {
        val sec = section.lowercase().ifBlank { "all" }
        val sb = StringBuilder()
        fun want(k: String) = sec == "all" || sec == k
        if (want("features")) { sb.append("功能与设置入口：\n"); s.features.forEach { sb.append("- ").append(it).append('\n') } }
        if (want("providers")) {
            sb.append("供应商：\n")
            if (s.providers.isEmpty()) sb.append("- （还没加）\n")
            s.providers.forEach { p -> sb.append("- ").append(p.name).append("（").append(p.type).append(if (p.host.isNotBlank()) " · ${p.host}" else "").append("）")
                .append(if (p.enabled) "" else " 已停用").append(" · ").append(p.models).append(" 个模型\n") }
        }
        if (want("groups")) {
            sb.append("模型组：\n")
            if (s.groups.isEmpty()) sb.append("- （没有）\n")
            s.groups.forEach { g -> sb.append("- ").append(g.name).append(if (g.isDefault) "（默认）" else "").append(if (g.agentLoop) "（agent loop 可用）" else "")
                .append("：").append(g.members.take(20).joinToString("、").ifBlank { "空" }).append('\n') }
        }
        if (want("servers")) {
            sb.append("工作位置（端口是从这台手机实测的）：\n")
            if (s.servers.isEmpty()) sb.append("- （没加服务器）\n")
            s.servers.forEach { sv ->
                sb.append("- ").append(sv.name).append(" ").append(sv.host).append(" · SSH ").append(sv.sshPort).append(" · ").append(ago(now, sv.probedAt)).append('\n')
                sv.ports.forEach { p -> sb.append("    ").append(p.port).append(if (p.open) " 通" else " 不通").append(if (p.ms >= 0 && p.open) " ${p.ms}ms" else "")
                    .append(if (p.label.isNotBlank()) " · ${p.label}" else "").append('\n') }
                if (sv.note.isNotBlank()) sb.append("    ").append(sv.note).append('\n')
            }
        }
        return sb.toString().trimEnd().ifBlank { "没有这一项：可选 all / features / providers / groups / servers" }
    }
}

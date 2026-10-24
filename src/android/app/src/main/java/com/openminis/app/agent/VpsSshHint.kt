package com.openminis.app.agent

/**
 * [Mines-38.9.9] user 2026-10-19 09:56: the agent ignored the taught method (plain `minis-vps run`, direct then the configured
 * ws relay) and ran bare `ssh` / `scp` to a server from 工作位置, hitting datacenter rate limits. A shell command that does
 * bare ssh / scp / sftp / rsync to a host in MINIS_VPS_SERVERS gets one hint line appended to its result. Pure, unit-tested.
 */
object VpsSshHint {
    /** hosts from a MINIS_VPS_SERVERS value: user@host:port[|relay],… (leading ! allowed) */
    fun hosts(servers: String?): Set<String> = servers.orEmpty().split(',').mapNotNull { e ->
        val ent = e.trim().removePrefix("!").substringBefore('|')
        if (ent.isEmpty() || ent == "none") null else ent.substringAfter('@').substringBeforeLast(':').lowercase().ifEmpty { null }
    }.toSet()

    fun hint(command: String, servers: String?): String? {
        val hs = hosts(servers); if (hs.isEmpty()) return null
        val c = command.lowercase()
        if (!Regex("""(^|[\s;&|(`])(ssh|scp|sftp|rsync)\s""").containsMatchIn(c)) return null
        // only when the ssh word itself is not part of a minis-vps call on the same line
        val lines = c.lines().filter { Regex("""(^|[\s;&|(`])(ssh|scp|sftp|rsync)\s""").containsMatchIn(it) && !it.contains("minis-vps") }
        val hit = hs.firstOrNull { h -> lines.any { l -> Regex("""(@|\s)${Regex.escape(h)}(\s|:|$|/)""").containsMatchIn(l) } } ?: return null
        return "[提示] $hit 在「工作位置」服务器列表里：请用用户教过的方法 `minis-vps run <命令>`（先直连，不通自动走已配置的中转），" +
            "不要裸 ssh/scp、不要手改 MINIS_VPS_SERVERS 或加 --direct（会触发机房限流）。传文件用 minis-vps 的 put/get 或 run 里 base64。"
    }
}

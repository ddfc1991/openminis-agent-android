package com.openminis.app.plus

/**
 * [Mines-38.9.12] 协作工作区 (design ws.jpg): the user's servers pooled into one scheduler — pure rules, WorkPoolCoreTest.
 *  - roles: siyun (AWS) = 浏览器节点, 阿里云 = 采集 / 定时节点, 梦云 = 网关节点 (受保护, keeps 600 MB free);
 *  - the Hatch / 青怡 trading VM is never part of the pool (excluded, never scheduled);
 *  - a shard goes to the node with the most schedulable RAM (free − reserve), heavy / browser work prefers the browser
 *    node; a node above 88 % memory gets no new shards and its shards migrate; every node keeps 300 MB reserve;
 *  - 云端扩容 is off by default and never starts paid resources on its own.
 */
object WorkPoolCore {
    enum class Role(val zh: String, val tags: List<String>) {
        BROWSER("浏览器节点", listOf("远程浏览器", "截图", "下载")),
        CRAWL("采集节点", listOf("网页采集", "定时任务", "备用中转")),
        GATEWAY("网关节点", listOf("模型网关", "WSS 通道")),
        GENERAL("通用节点", listOf("命令", "文件")),
        EXCLUDED("交易专用", emptyList()),
    }

    const val MIGRATE_PCT = 88
    const val RESERVE_MB = 300L
    const val GATEWAY_RESERVE_MB = 600L

    data class Node(val id: String, val name: String, val host: String, val role: Role, val online: Boolean,
                    val cores: Int = 0, val cpuPct: Float = 0f, val memUsedMb: Long = 0, val memTotalMb: Long = 0, val at: Long = 0L) {
        val memPct: Int get() = if (memTotalMb <= 0) 0 else (memUsedMb * 100 / memTotalMb).toInt()
        val reserveMb: Long get() = if (role == Role.GATEWAY) GATEWAY_RESERVE_MB else RESERVE_MB
        /** RAM a shard may use here (free − reserve), 0 when offline / excluded */
        val schedulableMb: Long get() = if (!online || role == Role.EXCLUDED || memTotalMb <= 0) 0 else (memTotalMb - memUsedMb - reserveMb).coerceAtLeast(0)
        val protected: Boolean get() = role == Role.GATEWAY
    }

    private val HATCH = Regex("(?i)hatch|青怡|qingyi|trading|交易")

    /** role from the server's name / address (the user's three boxes are known; anything else is a general node) */
    fun roleOf(name: String, host: String): Role {
        val t = "$name $host".lowercase()
        return when {
            HATCH.containsMatchIn(t) -> Role.EXCLUDED
            "siyun" in t || "aws" in t || "18.143.139.29" in t -> Role.BROWSER
            "阿里" in t || "aliyun" in t || "8.217.89.133" in t -> Role.CRAWL
            "梦云" in t || "mengyun" in t || "38.76.209.77" in t -> Role.GATEWAY
            else -> Role.GENERAL
        }
    }

    data class Pool(val cores: Int, val usedPct: Int, val schedulableMb: Long, val totalMb: Long, val online: Int, val members: Int)

    fun pool(nodes: List<Node>): Pool {
        val m = nodes.filter { it.role != Role.EXCLUDED }
        val on = m.filter { it.online && it.memTotalMb > 0 }
        val cores = on.sumOf { it.cores }
        val busy = if (cores == 0) 0 else (on.sumOf { (it.cpuPct * it.cores).toDouble() } / cores).toInt()
        return Pool(cores, busy, on.sumOf { it.schedulableMb }, on.sumOf { it.memTotalMb }, on.size, m.size)
    }

    enum class Kind { BROWSER, CRAWL, LIGHT, HEAVY }

    /** the node for one shard needing [needMb]; null = nothing has room (queue it) */
    fun pick(nodes: List<Node>, kind: Kind, needMb: Long): Node? {
        val ok = nodes.filter { it.schedulableMb >= needMb && it.memPct < MIGRATE_PCT }
        if (ok.isEmpty()) return null
        val prefer = when (kind) { Kind.BROWSER, Kind.HEAVY -> Role.BROWSER; Kind.CRAWL -> Role.CRAWL; Kind.LIGHT -> null }
        // heavy work never lands on the protected gateway while another node can take it
        val pool = if (kind == Kind.HEAVY || kind == Kind.BROWSER) ok.filterNot { it.protected }.ifEmpty { ok } else ok
        return pool.sortedWith(compareByDescending<Node> { prefer != null && it.role == prefer }.thenByDescending { it.schedulableMb }).first()
    }

    /** nodes whose shards must move now (memory above the threshold) */
    fun overloaded(nodes: List<Node>): List<Node> = nodes.filter { it.online && it.role != Role.EXCLUDED && it.memPct > MIGRATE_PCT }

    /** split [items] work units into shards of at most [per] each (≥ 1 shard) */
    fun shards(items: Int, per: Int): List<Int> {
        if (items <= 0) return emptyList()
        val p = per.coerceAtLeast(1)
        return List((items + p - 1) / p) { i -> minOf(p, items - i * p) }
    }

    /** status pill text */
    fun status(n: Node): String = when {
        n.role == Role.EXCLUDED -> "已排除"
        !n.online -> "离线"
        n.memPct > MIGRATE_PCT -> "内存紧张"
        n.protected -> "受保护"
        else -> "在线"
    }

    fun mem(mb: Long): String = if (mb >= 1024) String.format(java.util.Locale.US, "%.1fG", mb / 1024.0) else "${mb}M"
}

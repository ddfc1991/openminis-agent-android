package com.openminis.app.plus

import java.util.Locale

/**
 * [Mines-38.9.13 vps_page_v2] Machine monitor (VPS page card detail, user 2026-10-24 22:11, reference cf-server-monitor.jpg).
 * Pure logic: the probe script run on the server over the app's own SSH / WS link, and its parser. Every value comes from a
 * real reading; anything missing stays null and is shown as 「—」.
 */
object VpsMonitorCore {
    const val CELLS = 20

    /** Carrier latency targets: long-standing public DNS resolvers of 北京电信 / 北京联通 / 北京移动 (used by common VPS test scripts). */
    val CARRIERS = listOf(Carrier("ct", "电信", "219.141.136.12"), Carrier("cu", "联通", "202.106.50.1"), Carrier("cm", "移动", "221.179.155.161"))

    data class Carrier(val key: String, val name: String, val ip: String)

    /**
     * Two samples of /proc/stat and /proc/net/dev 1 s apart, /proc/meminfo, df /, /proc/loadavg, nproc, /proc/uptime, then 5
     * pings per carrier in parallel (ICMP; TCP connect to port 53 when ping is not installed).
     */
    val PROBE: String = buildString {
        append("echo CORES $(nproc 2>/dev/null)\n")
        append("echo LOAD $(cat /proc/loadavg 2>/dev/null)\n")
        append("echo UP $(cut -d' ' -f1 /proc/uptime 2>/dev/null)\n")
        append("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo 2>/dev/null\n")
        append("echo DF $(df -kP / 2>/dev/null | tail -1)\n")
        append("head -1 /proc/stat; awk 'NR>2 && \$1 !~ /^lo:/{rx+=\$2;tx+=\$10}END{print \"NET\",rx,tx}' /proc/net/dev 2>/dev/null\n")
        append("sleep 1\n")
        append("head -1 /proc/stat; awk 'NR>2 && \$1 !~ /^lo:/{rx+=\$2;tx+=\$10}END{print \"NET\",rx,tx}' /proc/net/dev 2>/dev/null\n")
        append("T=$(mktemp -d 2>/dev/null || echo /tmp/mmon$$); mkdir -p \$T\n")
        for (c in CARRIERS) {
            append("( if command -v ping >/dev/null 2>&1; then ping -n -q -c 5 -i 0.3 -w 5 ${c.ip} 2>&1 | tail -2; else ")
            append("for i in 1 2 3 4 5; do s=$(date +%s%N); if timeout 2 bash -c '</dev/tcp/${c.ip}/53' 2>/dev/null; then e=$(date +%s%N); echo TCP $(( (e-s)/1000000 )); else echo TCP x; fi; done; fi ) > \$T/${c.key} &\n")
        }
        append("wait\n")
        for (c in CARRIERS) append("echo PING ${c.key}; cat \$T/${c.key}\n")
        append("rm -rf \$T\n")
    }

    data class Lat(val ms: Double?, val lossPct: Double?)

    data class Reading(
        val cpuPct: Double? = null, val memPct: Double? = null, val diskPct: Double? = null,
        val load1: Double? = null, val cores: Int? = null,
        val rxRate: Double? = null, val txRate: Double? = null,     // bytes / s
        val rxTotal: Long? = null, val txTotal: Long? = null,       // bytes since boot
        val uptimeSec: Long? = null,
        val lat: Map<String, Lat> = emptyMap(),
    ) {
        /** load as % of the cores (bar) */
        val loadPct: Double? get() = if (load1 != null && cores != null && cores > 0) (load1 * 100 / cores).coerceIn(0.0, 100.0) else null
        val trafficTotal: Long? get() = if (rxTotal != null && txTotal != null) rxTotal + txTotal else null
    }

    private val WS = Regex("\\s+")

    fun parse(out: String): Reading {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun after(tag: String) = lines.firstOrNull { it.startsWith("$tag ") }?.substringAfter("$tag ")?.trim()
        val cores = after("CORES")?.toIntOrNull()
        val load1 = after("LOAD")?.split(WS)?.firstOrNull()?.toDoubleOrNull()
        val up = after("UP")?.toDoubleOrNull()?.toLong()
        fun kb(key: String) = lines.firstOrNull { it.startsWith(key) }?.split(WS)?.getOrNull(1)?.toLongOrNull()
        val mt = kb("MemTotal:"); val ma = kb("MemAvailable:")
        val mem = if (mt != null && ma != null && mt > 0) (mt - ma) * 100.0 / mt else null
        val disk = after("DF")?.split(WS)?.getOrNull(4)?.removeSuffix("%")?.toDoubleOrNull()
        val cpu = lines.filter { it.startsWith("cpu ") }.map { l -> l.split(WS).drop(1).mapNotNull { it.toLongOrNull() } }
        val cpuPct = if (cpu.size >= 2 && cpu[0].size >= 4 && cpu[1].size >= 4) {
            val a = cpu[0]; val b = cpu[1]
            val dt = b.sum() - a.sum()
            if (dt > 0) (100.0 * (dt - ((b[3] + b.getOrElse(4) { 0 }) - (a[3] + a.getOrElse(4) { 0 }))) / dt).coerceIn(0.0, 100.0) else null
        } else null
        val net = lines.filter { it.startsWith("NET ") }.map { l -> l.split(WS).drop(1).mapNotNull { it.toDoubleOrNull()?.toLong() } }.filter { it.size == 2 }
        val n1 = net.getOrNull(1) ?: net.getOrNull(0)
        val rate = if (net.size >= 2) (net[1][0] - net[0][0]).coerceAtLeast(0).toDouble() to (net[1][1] - net[0][1]).coerceAtLeast(0).toDouble() else null
        // latency blocks
        val lat = mutableMapOf<String, Lat>()
        var cur: String? = null
        val block = mutableListOf<String>()
        fun flush() { cur?.let { k -> parseLat(block)?.let { lat[k] = it } }; block.clear() }
        for (l in lines) {
            if (l.startsWith("PING ") && l.split(WS).size == 2 && CARRIERS.any { it.key == l.substringAfter("PING ") }) { flush(); cur = l.substringAfter("PING ") }
            else if (cur != null) block += l
        }
        flush()
        return Reading(cpuPct, mem, disk, load1, cores, rate?.first, rate?.second, n1?.get(0), n1?.get(1), up, lat)
    }

    private val LOSS = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received.*?([\d.]+)% packet loss""")
    private val RTT = Regex("""(?:rtt|round-trip) min/avg/max(?:/mdev|/stddev)? = [\d.]+/([\d.]+)/""")

    /** one carrier's lines: ping -q summary, or 5 「TCP <ms>|x」 lines */
    fun parseLat(lines: List<String>): Lat? {
        val text = lines.joinToString("\n")
        LOSS.find(text)?.let { m ->
            val loss = m.groupValues[3].toDoubleOrNull()
            val avg = RTT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            return Lat(avg, loss)
        }
        val tcp = lines.filter { it.startsWith("TCP ") }.map { it.substringAfter("TCP ").trim() }
        if (tcp.isNotEmpty()) {
            val ok = tcp.mapNotNull { it.toDoubleOrNull() }
            return Lat(if (ok.isEmpty()) null else ok.average(), (tcp.size - ok.size) * 100.0 / tcp.size)
        }
        return null
    }

    /** filled cells of a [CELLS]-cell bar for [pct] (null → 0) */
    fun cells(pct: Double?): Int = if (pct == null) 0 else Math.round(pct.coerceIn(0.0, 100.0) * CELLS / 100.0).toInt()

    /** latency health: 0 green, 1 yellow, 2 red, -1 no reading */
    fun latLevel(l: Lat?): Int = when {
        l == null || (l.ms == null && l.lossPct == null) -> -1
        l.ms == null || (l.lossPct ?: 0.0) >= 20.0 -> 2
        l.ms >= 200 || (l.lossPct ?: 0.0) > 0.0 -> if (l.ms >= 300) 2 else 1
        l.ms >= 100 -> 1
        else -> 0
    }

    fun lossLevel(l: Lat?): Int = when {
        l?.lossPct == null -> -1
        l.lossPct >= 20.0 -> 2
        l.lossPct > 0.0 -> 1
        else -> 0
    }

    fun bytes(b: Long?): String {
        if (b == null) return "—"
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
    }

    fun rate(bps: Double?): String = if (bps == null) "—" else bytes(bps.toLong()).let { if (it.endsWith(" B")) it.removeSuffix(" B") + " B/s" else "$it/s" }

    fun uptime(sec: Long?): String = when {
        sec == null -> "—"
        sec >= 86_400 -> "${sec / 86_400}天"
        sec >= 3_600 -> "${sec / 3_600}小时"
        else -> "${(sec / 60).coerceAtLeast(1)}分钟"
    }

    fun pct(v: Double?): String = if (v == null) "—" else "${Math.round(v)}%"

    /** whole days left until [expiresAt] (0 = no end date registered → null) */
    fun daysLeft(expiresAt: Long, now: Long): Long? = if (expiresAt <= 0L) null else ((expiresAt - now).coerceAtLeast(0L) + 86_399_999L) / 86_400_000L

    private val QUOTA = Regex("""流量[^\d]{0,8}(\d+(?:\.\d+)?)\s*([GT])""", RegexOption.IGNORE_CASE)

    /** the traffic allowance written in the machine's registered limits (「流量 1TB/月」); null = none written → ∞ */
    fun quotaBytes(limits: String): Long? = QUOTA.find(limits)?.let { m ->
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        (v * (if (m.groupValues[2].equals("T", true)) 1024.0 * 1024 * 1024 * 1024 else 1024.0 * 1024 * 1024)).toLong()
    }
}

package com.openminis.app.plus

/**
 * [Mines-38.9.14.1 net_drop] pure parts (unit-tested in NetDropCoreTest).
 *
 * User 2026-10-25 13:51 (real phone, realme UI 16, VPN on): two tasks on two different models died in the same second with
 * 「Software caused connection abort」 after 「已自动重试 24 / 19 次」. ECONNABORTED on Android is what the kernel returns when
 * netd destroys the app's TCP sockets (SOCK_DESTROY): the network they were on went away (VPN reconnect, Wi-Fi↔5G), or the
 * app's UID lost network access (battery manager froze / restricted it in the background). A gateway cutting an idle stream
 * gives ECONNRESET / 「unexpected end of stream」 instead. The old loop retried 3 times in 7 s on the same dead path and gave up.
 *
 * Now: a connection drop → evict the pool, wait for the network (≤ [WAIT_MS]), back off, resume the SAME turn (finished tool
 * steps stay in the history); after [UP_DROPS_BEFORE_SWITCH] drops while the network stayed up → next working model.
 */
object NetDropCore {
    /** the status line under the spinner while waiting */
    const val LINE = "网络断了，等网络恢复后继续"
    const val WAIT_MS = 120_000L
    /** total drop-retries for one model in one turn */
    const val MAX_RETRIES = 8
    /** drops while the network never went away = this provider/path is the problem → next model */
    const val UP_DROPS_BEFORE_SWITCH = 3

    private val PATTERNS = listOf(
        "software caused connection abort", "econnaborted",
        "connection reset", "econnreset",
        "broken pipe", "epipe",
        "stream was reset", "unexpected end of stream",
        "connection abort", "network is unreachable", "enetunreach",
        "unable to resolve host", "no address associated with hostname",
        "socket closed", "connection closed by peer", "recvfrom failed", "sendto failed",
    )

    /** true when the message (or any cause in the chain) is a connection-level drop */
    fun isDrop(t: Throwable?): Boolean {
        var c = t; var n = 0
        while (c != null && n < 8) {
            if (isDropText(c.message) || c.javaClass.name.let { it.endsWith("StreamResetException") || it.endsWith("ConnectionShutdownException") }) return true
            c = c.cause; n++
        }
        return false
    }

    fun isDropText(m: String?): Boolean {
        val s = m?.lowercase() ?: return false
        return PATTERNS.any { s.contains(it) }
    }

    /** wait between resume attempts: 2 s, 4 s, 8 s, 16 s, then 30 s */
    fun backoffMs(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 4)).coerceAtMost(30_000L)

    enum class Plan { RESUME, SWITCH, GIVE_UP }

    /** [retries] drop-retries already spent on this model this turn; [upDrops] of those where the network never went away */
    fun plan(retries: Int, upDrops: Int): Plan = when {
        upDrops >= UP_DROPS_BEFORE_SWITCH -> Plan.SWITCH
        retries >= MAX_RETRIES -> Plan.GIVE_UP
        else -> Plan.RESUME
    }

    /** What the device state at the moment of the drop points to. */
    data class Snap(
        val screenOn: Boolean,
        val foreground: Boolean,
        /** ms since the last default-network change (onAvailable/onLost/onCapabilities with a different transport), -1 = none seen */
        val sinceNetChangeMs: Long,
        /** ms since the VPN came up / went away / changed, -1 = none seen */
        val sinceVpnChangeMs: Long,
        /** ms since the last byte of this stream arrived, -1 = unknown */
        val idleMs: Long,
        val vpn: Boolean,
        val transport: String,
        val batteryExempt: Boolean,
        val fgService: Boolean,
        val backgroundRestricted: Boolean,
        val networkBlocked: Boolean,
    )

    const val CAUSE_FREEZE = "后台被省电冻结/断网"
    const val CAUSE_NET = "网络切换"
    const val CAUSE_VPN = "VPN 重连"
    const val CAUSE_IDLE = "长时间无数据被掐断"
    const val CAUSE_UNKNOWN = "原因不明"

    /** best guess at the cause, most specific evidence first */
    fun cause(s: Snap): String = when {
        s.sinceVpnChangeMs in 0..15_000 -> CAUSE_VPN
        s.sinceNetChangeMs in 0..15_000 -> CAUSE_NET
        s.networkBlocked || s.backgroundRestricted -> CAUSE_FREEZE
        !s.foreground && !s.screenOn && (!s.batteryExempt || !s.fgService) -> CAUSE_FREEZE
        s.idleMs >= 25_000 -> CAUSE_IDLE
        else -> CAUSE_UNKNOWN
    }

    fun describe(s: Snap): String =
        "cause=${cause(s)} screen=${if (s.screenOn) "on" else "off"} app=${if (s.foreground) "fg" else "bg"} " +
            "net=${s.transport}${if (s.vpn) "+vpn" else ""} netChange=${ago(s.sinceNetChangeMs)} vpnChange=${ago(s.sinceVpnChangeMs)} " +
            "idle=${ago(s.idleMs)} batteryExempt=${s.batteryExempt} fgs=${s.fgService} bgRestricted=${s.backgroundRestricted} blocked=${s.networkBlocked}"

    private fun ago(ms: Long) = if (ms < 0) "-" else "${ms / 1000}s"
}

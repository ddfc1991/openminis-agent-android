package com.openminis.app.plus

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.14.1 net_drop] device side of [NetDropCore]:
 *  - watches the default network (id, transport, VPN, blocked-status) so a drop can be matched to what happened just before;
 *  - [snapshot] at the moment of a drop: screen, app fg/bg, last network / VPN change, idle seconds since the stream's last
 *    byte, battery exemption, foreground service, background restriction, netd "blocked" status;
 *  - every drop is logged: logcat MinesNet, filesDir/mines-net.log (last 300), 远程诊断 event, i3914 state;
 *  - [awaitNetwork] waits (≤ 2 min) for a validated default network instead of hammering a dead path.
 */
object NetDrop {
    private const val FILE = "mines-net.log"
    @Volatile private var app: Context? = null
    @Volatile private var lastByteAt = 0L
    @Volatile private var lastNetChangeAt = -1L
    @Volatile private var lastVpnChangeAt = -1L
    @Volatile private var lastBlockedAt = -1L
    @Volatile private var blocked = false
    @Volatile private var curNet: String = ""
    @Volatile private var curTransport: String = "?"
    @Volatile private var curVpn = false
    @Volatile private var available = true
    private val recent = ArrayDeque<JSONObject>()

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { noteCaps(n, it, initial = true) } } }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available = true
                    if (network.toString() != curNet) { if (curNet.isNotEmpty()) mark("net_available", "net=$network"); curNet = network.toString() }
                }
                override fun onLost(network: Network) {
                    available = false; mark("net_lost", "net=$network ($curTransport${if (curVpn) "+vpn" else ""})")
                    // the dead connections must not be handed to the retry
                    runCatching { com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool.evictAll() }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { noteCaps(network, caps, initial = false) }
                override fun onBlockedStatusChanged(network: Network, b: Boolean) {
                    if (b != blocked) { blocked = b; lastBlockedAt = SystemClock.elapsedRealtime(); log("blocked", "", "netd blocked=$b net=$network", null) }
                }
            })
        }
    }

    private fun noteCaps(n: Network, caps: NetworkCapabilities, initial: Boolean) {
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val t = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            vpn -> "vpn"
            else -> "other"
        }
        if (!initial) {
            if (vpn != curVpn) { lastVpnChangeAt = SystemClock.elapsedRealtime(); log("vpn", "", if (vpn) "VPN up" else "VPN gone", null) }
            if (t != curTransport && t != "vpn") mark("net_switch", "$curTransport → $t")
        }
        curVpn = vpn; if (t != "vpn" || curTransport == "?") curTransport = t; curNet = n.toString()
    }

    private fun mark(kind: String, detail: String) {
        lastNetChangeAt = SystemClock.elapsedRealtime()
        log(kind, "", detail, null)
    }

    /** the stream received bytes (called per chunk; one volatile write) */
    fun noteByte() { lastByteAt = SystemClock.elapsedRealtime() }

    fun snapshot(): NetDropCore.Snap {
        val c = app
        val now = SystemClock.elapsedRealtime()
        val pm = c?.getSystemService(android.os.PowerManager::class.java)
        val am = c?.getSystemService(android.app.ActivityManager::class.java)
        val fg = runCatching { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }.getOrDefault(false)
        return NetDropCore.Snap(
            screenOn = runCatching { pm?.isInteractive == true }.getOrDefault(true),
            foreground = fg,
            sinceNetChangeMs = if (lastNetChangeAt < 0) -1 else now - lastNetChangeAt,
            sinceVpnChangeMs = if (lastVpnChangeAt < 0) -1 else now - lastVpnChangeAt,
            idleMs = if (lastByteAt <= 0) -1 else now - lastByteAt,
            vpn = curVpn, transport = curTransport,
            batteryExempt = runCatching { c != null && pm?.isIgnoringBatteryOptimizations(c.packageName) == true }.getOrDefault(false),
            fgService = runCatching { com.openminis.app.service.SessionActivityTracker.shouldRunServiceNow() }.getOrDefault(false),
            backgroundRestricted = runCatching { android.os.Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true }.getOrDefault(false),
            networkBlocked = blocked || (lastBlockedAt >= 0 && now - lastBlockedAt < 15_000),
        )
    }

    fun evict() { runCatching { com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool.evictAll() } }

    /** true when a default network with INTERNET is up right now */
    fun online(): Boolean {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull() ?: return false
        // INTERNET only: VALIDATED needs the captive-portal probe, which fails on many networks in China (gstatic blocked)
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Waits for the network (≤ [timeoutMs]). Returns (network was down at some point, ms waited, back online). */
    suspend fun awaitNetwork(timeoutMs: Long): Triple<Boolean, Long, Boolean> {
        val t0 = SystemClock.elapsedRealtime()
        var wasDown = !available
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val on = available && online() && !blocked
            if (!on) wasDown = true
            if (on) {
                // a network that just came back: give it a moment (DNS / VPN routes settle)
                if (wasDown) kotlinx.coroutines.delay(1_500)
                return Triple(wasDown, SystemClock.elapsedRealtime() - t0, true)
            }
            kotlinx.coroutines.delay(1_000)
        }
        return Triple(true, SystemClock.elapsedRealtime() - t0, false)
    }

    /** kind: drop | resume | switch | gave_up | net_lost | net_available | net_switch | vpn | blocked */
    fun log(kind: String, model: String, detail: String, snap: NetDropCore.Snap?) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("model", model).put("detail", detail.take(200))
        if (snap != null) o.put("cause", NetDropCore.cause(snap)).put("state", NetDropCore.describe(snap))
        synchronized(recent) { recent.addLast(o); while (recent.size > 60) recent.removeFirst() }
        val line = "$kind model=$model $detail" + (snap?.let { " | " + NetDropCore.describe(it) } ?: "")
        android.util.Log.w("MinesNet", line)
        runCatching { com.openminis.app.logging.AppLogger.warning("MinesNet", line) }
        runCatching { Diag.event("net", o.toString()) }
        val c = app ?: return
        runCatching {
            val f = java.io.File(c.filesDir, FILE)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + o.toString()
            f.writeText(lines.takeLast(300).joinToString("\n") + "\n")
        }
    }

    fun json(): JSONArray = synchronized(recent) { JSONArray().apply { recent.forEach { put(it) } } }
}

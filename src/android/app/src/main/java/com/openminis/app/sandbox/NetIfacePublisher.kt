package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import java.io.File
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * [T-android-fake-netlink-real-ifaces] Publishes the device's real network
 * interfaces to the sandbox.
 *
 * The guest cannot open a NETLINK_ROUTE socket (SELinux), so proot's
 * `--fake-netlink` answers interface dumps itself. On its own it only knows
 * loopback, so `ip addr`, Go's net.Interfaces(), Node's os.networkInterfaces()
 * and friends saw a machine with no network ("没有 IP"). The app CAN ask
 * ConnectivityManager, so it writes the active network's addresses to a small
 * file that proot re-reads on every dump:
 *
 *     <ifname> <4|6> <address> <prefixlen>
 *
 * Only true addresses are written, so a guest that binds one gets a socket the
 * kernel accepts. The default-network callback keeps the file current across
 * Wi-Fi / mobile / VPN switches without restarting any shell.
 */
object NetIfacePublisher {
    private const val TAG = "NetIfacePublisher"
    const val ENV_KEY = "PROOT_FAKE_NETLINK_IFACES"

    @Volatile private var callbackRegistered = false

    private fun file(context: Context): File = File(PRootKernel.getProotTmpDir(context), "netifs")

    /** Call from boot, before any shell spawns. Idempotent. */
    fun start(context: Context) {
        val app = context.applicationContext
        val target = file(app)
        PRootKernel.customEnvironment[ENV_KEY] = target.absolutePath

        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { write(target, cm.getLinkProperties(cm.activeNetwork)) }
            .onFailure { Log.w(TAG, "initial snapshot failed: ${it.message}") }

        if (callbackRegistered) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // linkAddresses can still be empty at onAvailable (VPN, dual
                // stack coming up), so every event re-reads the current
                // LinkProperties; writing the same content twice is harmless.
                override fun onAvailable(network: Network) {
                    write(target, cm.getLinkProperties(network))
                    // [Mines-net-26] New network (Wi-Fi ↔ mobile, VPN on/off): refresh
                    // what minis-doctor reports, re-check the proxy, re-pick mirrors.
                    runCatching {
                        com.openminis.app.plus.SandboxNet.writeNetState(app)
                        com.openminis.app.plus.SandboxNet.kickMirrors(app)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            runCatching { ExecutionCoordinator.broadcastProxyChange() }
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                    write(target, cm.getLinkProperties(network))
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    write(target, lp)
                }

                override fun onLost(network: Network) {
                    write(target, null)
                }
            })
            callbackRegistered = true
        }.onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}") }
    }

    @Synchronized
    private fun write(target: File, lp: LinkProperties?) {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        if (lp != null) {
            val name = lp.interfaceName?.takeIf { it.isNotBlank() && it.length < 16 } ?: "net0"
            for (la in lp.linkAddresses) {
                val addr = la.address
                val family = when (addr) {
                    is Inet4Address -> 4
                    is Inet6Address -> 6
                    else -> continue
                }
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                if (!seen.add(host)) continue
                sb.append(name).append(' ').append(family).append(' ')
                    .append(host).append(' ').append(la.prefixLength).append('\n')
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "netifs.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(sb.toString())
                tmp.delete()
            }
            Log.i(TAG, "published ${sb.lines().count { it.isNotBlank() }} address(es)")
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
    }
}

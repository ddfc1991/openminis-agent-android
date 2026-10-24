package com.openminis.app.plus

/**
 * [Mines-38.9.13 tunnel_v1] For other pages (e.g. the VPS page): the 内网穿透 status of a machine by its name or IP.
 * 「穿透中」 / 「断开」 / 「未开启」 / 「检测中」 (marked, first real probe not back yet); null when the flag is off or no
 * such machine. Non-blocking: returns the last real probe and re-probes in the background at most once a minute.
 */
object TunnelState {
    fun of(machine: String): String? {
        if (Tunnel.app == null || !Tunnel.on.value) return null
        val key = machine.trim()
        val s = PlusPrefs.state.value.servers.firstOrNull { it.name.equals(key, true) || it.host == key || it.tailscaleHost == key }
            ?: return null
        if (Tunnel.entry(s.id) != null) Tunnel.kick()
        return Tunnel.status(s.id)
    }
}

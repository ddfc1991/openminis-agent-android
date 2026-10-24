package com.openminis.app.plus

/**
 * [Mines-38.9.13] SSH-over-WebSocket bridges on the user's boxes (server repair 2026-10-24): port 2053 only speaks wss://
 * (plain ws:// there shows up as 「http request」 in 梦云's log), port 8880 only speaks ws://. AWS 18.143.139.29 has no WS
 * bridge at all (its 2053 is the subscription service), so it is always reached by SSH 22. Pure logic.
 */
object WsLinkCore {
    const val AWS_HOST = "18.143.139.29"

    /** ws:// or wss:// [url] with the scheme its port speaks (2053 → wss, 8880 → ws); anything else unchanged */
    fun fixScheme(url: String): String {
        val u = url.trim()
        val rest = when {
            u.startsWith("wss://", true) -> u.substring(6)
            u.startsWith("ws://", true) -> u.substring(5)
            else -> return u
        }
        val port = rest.substringBefore('/').substringAfterLast(':', "").toIntOrNull()
        return when (port) { 2053 -> "wss://$rest"; 8880 -> "ws://$rest"; else -> u }
    }

    fun isWs(url: String): Boolean = url.trim().let { it.startsWith("ws://", true) || it.startsWith("wss://", true) }

    /** false for a box with no WS bridge (AWS): its default and only route is SSH */
    fun hasWs(host: String): Boolean = host.trim() != AWS_HOST

    /** the relay to use for [host]: a WS relay gets its port's scheme; a WS relay on a box without a bridge is dropped (""), other relays unchanged */
    fun relayFor(host: String, relay: String): String = when {
        !isWs(relay) -> relay.trim()
        !hasWs(host) -> ""
        else -> fixScheme(relay)
    }
}

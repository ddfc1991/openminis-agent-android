package com.openminis.app.plus

/**
 * [Mines-38.9.13 tunnel_v1] Pure logic for 内网穿透 via the AWS hub: port assignment, the restricted authorized_keys
 * line for the hub, the filled install script, and reading the hub's listening sockets. See specs/tunnel.md.
 */
object TunnelCore {
    /** machine sshd, hub loopback only */
    val SSH_PORTS = 22100..22199
    /** machine web port, hub loopback only */
    val WEB_PORTS = 22200..22299
    /** reachable from the internet (machine web port made public, phone port exposure) */
    val PUBLIC_PORTS = 22300..22399

    /** Lowest port of [range] not in [used], or null when the range is full. */
    fun assign(used: Collection<Int>, range: IntRange): Int? = range.firstOrNull { it !in used }

    /** Hub-side bind address for a web port. */
    fun webBind(public: Boolean) = if (public) "0.0.0.0" else "127.0.0.1"

    /**
     * The one line for the hub's ~/.ssh/authorized_keys: the machine's key may only listen on its own ports and only
     * open its own ssh port; no shell, no pty, no agent/X11 forwarding.
     */
    fun keyLine(pubKey: String, sshPort: Int, webPort: Int = 0, webPublic: Boolean = false): String {
        val opts = buildList {
            add("restrict"); add("port-forwarding"); add("command=\"echo mines-tunnel-only\"")
            add("permitopen=\"127.0.0.1:$sshPort\"")
            add("permitlisten=\"127.0.0.1:$sshPort\"")
            if (webPort > 0) add("permitlisten=\"${webBind(webPublic)}:$webPort\"")
        }
        return opts.joinToString(",") + " " + pubKey.trim()
    }

    /** `-R` arguments of the machine's ssh command. */
    fun forwards(sshPort: Int, localSsh: Int, webPort: Int = 0, localWeb: Int = 0, webPublic: Boolean = false): List<String> =
        buildList {
            add("-R 127.0.0.1:$sshPort:127.0.0.1:$localSsh")
            if (webPort > 0 && localWeb > 0) add("-R ${webBind(webPublic)}:$webPort:127.0.0.1:$localWeb")
        }

    /** [template] = assets/mines-tunnel/install.sh with @HUB_USER@ @HUB_HOST@ @HUB_PORT@ @FORWARDS@ filled in. */
    fun installScript(template: String, hubUser: String, hubHost: String, hubPort: Int, forwards: List<String>): String {
        require(hubUser.matches(SAFE) && hubHost.matches(SAFE)) { "bad hub address" }
        return template.replace("@HUB_USER@", hubUser).replace("@HUB_HOST@", hubHost)
            .replace("@HUB_PORT@", hubPort.toString()).replace("@FORWARDS@", forwards.joinToString(" "))
    }
    private val SAFE = Regex("[A-Za-z0-9._:\\-\\[\\]]+")

    /** The machine's public key from the install output (`MINES_PUB ssh-ed25519 AAAA… comment`). */
    fun pubFrom(output: String): String? = output.lineSequence().map { it.trim() }
        .firstOrNull { it.startsWith("MINES_PUB ssh-") }?.removePrefix("MINES_PUB ")?.trim()

    /** Does `ss -ltn` / `netstat -ltn` output show a listener on [port] (any address)? */
    fun listening(output: String, port: Int): Boolean = output.lineSequence().any { line ->
        line.trim().split(Regex("\\s+")).any { col -> col.endsWith(":$port") && col.length > ":$port".length }
    }

    const val ON = "穿透中"; const val DOWN = "断开"; const val OFF = "未开启"; const val CHECKING = "检测中"

    /** Status words from the real probe: marked? probed? listening on the hub? */
    fun status(marked: Boolean, probed: Boolean, listening: Boolean): String = when {
        !marked -> OFF
        !probed -> CHECKING
        listening -> ON
        else -> DOWN
    }

    /** Sandbox (minis-vps) entry for a machine reached through the hub: ProxyJump relay, target on hub loopback. */
    fun sandboxEntry(user: String, sshPort: Int, hubUser: String, hubHost: String, hubPort: Int) =
        "$user@127.0.0.1:$sshPort|ssh://$hubUser@$hubHost:$hubPort"
}

package com.openminis.app.plus

/**
 * [Mines-38.9.11.2] SSH from Mines (user 自检 2026-10-21):
 *  B. SSH kept getting cut through the proxy → SSH sockets go DIRECT: no Java proxy (the app's ProxySelector / split
 *     proxy never sees them), bound to the phone's real network (Wi-Fi / mobile) when a VPN app is the default route;
 *     only if direct fails, the default route (through the VPN), and last a passing local proxy (SOCKS on the mixed port).
 *  C. 「Algorithm negotiation fail server_host_key」 (InstaBox api.instabox.sh:2222): on Android JSch drops ssh-ed25519 /
 *     curve25519 (no Java 15 EdDSA / XDH) unless Bouncy Castle is present → BC is bundled and the lists are explicit.
 * Pure, unit-tested (SshNetCoreTest).
 */
object SshNetCore {
    enum class Route(val label: String) { UNDERLYING("直连（绕过 VPN）"), DEFAULT("直连"), LOCAL_PROXY("本机代理") }

    fun routes(vpnActive: Boolean, hasUnderlying: Boolean, localProxy: Int?): List<Route> = buildList {
        if (vpnActive && hasUnderlying) add(Route.UNDERLYING)
        add(Route.DEFAULT)
        if (localProxy != null) add(Route.LOCAL_PROXY)
    }

    /** [38.9.11.3] OpenSSH host certificates (JSch ≥ 2.27, mwiede): InstaBox api.instabox.sh:2222 offers ONLY
     *  ssh-ed25519-cert-v01@openssh.com. Plain keys stay first so a server that offers both keeps its pinned fingerprint;
     *  with no @cert-authority known, JSch checks the certificate's own key (host_certificate_to_key_fallback = yes),
     *  so the pin is the same SHA256 fingerprint as the plain key. */
    const val HOST_CERTS = "ssh-ed25519-cert-v01@openssh.com,ecdsa-sha2-nistp256-cert-v01@openssh.com,ecdsa-sha2-nistp384-cert-v01@openssh.com," +
        "ecdsa-sha2-nistp521-cert-v01@openssh.com,rsa-sha2-512-cert-v01@openssh.com,rsa-sha2-256-cert-v01@openssh.com"
    const val SERVER_HOST_KEY = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256," + HOST_CERTS + ",ssh-rsa"
    const val PUBKEY_ALGOS = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
    const val KEX = "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
        "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1"

    /** JSch global config (JSch.setConfig): algorithm lists + the Bouncy Castle implementations Android needs */
    val GLOBAL: Map<String, String> = linkedMapOf(
        "server_host_key" to SERVER_HOST_KEY,
        "PubkeyAcceptedAlgorithms" to PUBKEY_ALGOS,
        "PubkeyAcceptedKeyTypes" to PUBKEY_ALGOS,
        "kex" to KEX,
        "ssh-ed25519" to "com.jcraft.jsch.bc.SignatureEd25519",
        "ssh-ed448" to "com.jcraft.jsch.bc.SignatureEd448",
        "xdh" to "com.jcraft.jsch.bc.XDH",
        "host_certificate_to_key_fallback" to "yes",
    )
}

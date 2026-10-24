package com.openminis.app.plus

import com.openminis.app.plus.SshNetCore.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.2] SSH goes direct (proxy only as the last fallback); host-key / pubkey algorithms Android can use. */
class SshNetCoreTest {
    @Test fun directFirstProxyLast() {
        assertEquals(listOf(Route.UNDERLYING, Route.DEFAULT, Route.LOCAL_PROXY), SshNetCore.routes(true, true, 7890))
        assertEquals(listOf(Route.DEFAULT, Route.LOCAL_PROXY), SshNetCore.routes(true, false, 7890))
        assertEquals(listOf(Route.DEFAULT), SshNetCore.routes(false, true, null))
        assertEquals(listOf(Route.DEFAULT, Route.LOCAL_PROXY), SshNetCore.routes(false, false, 7898))
    }

    @Test fun algorithms() {
        val hk = SshNetCore.SERVER_HOST_KEY.split(',')
        for (a in listOf("ssh-ed25519", "rsa-sha2-512", "rsa-sha2-256", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521"))
            assertTrue("server_host_key has $a", a in hk)
        assertEquals("ssh-ed25519", hk.first()); assertEquals("ssh-rsa", hk.last())   // SHA-1 RSA only as the very last resort
        assertEquals(SshNetCore.SERVER_HOST_KEY, SshNetCore.GLOBAL["server_host_key"])
        assertEquals(SshNetCore.PUBKEY_ALGOS, SshNetCore.GLOBAL["PubkeyAcceptedAlgorithms"])
        assertTrue("ssh-ed25519" in SshNetCore.PUBKEY_ALGOS.split(',') && "rsa-sha2-512" in SshNetCore.PUBKEY_ALGOS.split(','))
        assertEquals("com.jcraft.jsch.bc.SignatureEd25519", SshNetCore.GLOBAL["ssh-ed25519"])
        assertTrue(SshNetCore.KEX.startsWith("curve25519-sha256"))
    }

    /** [38.9.11.3] InstaBox offers only ssh-ed25519-cert-v01@openssh.com (negotiation failed with 0.2.21) */
    @Test fun hostCertificates() {
        val hk = SshNetCore.SERVER_HOST_KEY.split(',')
        for (c in listOf("ssh-ed25519-cert-v01@openssh.com", "rsa-sha2-512-cert-v01@openssh.com", "rsa-sha2-256-cert-v01@openssh.com", "ecdsa-sha2-nistp256-cert-v01@openssh.com"))
            assertTrue("server_host_key has $c", c in hk)
        // plain keys before certificates (servers offering both keep their pinned plain-key fingerprint)
        assertTrue(hk.indexOf("rsa-sha2-256") < hk.indexOf("ssh-ed25519-cert-v01@openssh.com"))
        // the negotiation InstaBox needs: first client entry the server also offers
        val server = listOf("ssh-ed25519-cert-v01@openssh.com")
        assertEquals("ssh-ed25519-cert-v01@openssh.com", hk.firstOrNull { it in server })
        assertEquals("yes", SshNetCore.GLOBAL["host_certificate_to_key_fallback"])
        fun f(vararg c: String) = c.map { File(it) }.first { it.exists() }.readText()
        val gradle = f("build.gradle.kts", "app/build.gradle.kts")
        val v = Regex("com.github.mwiede:jsch:(\\d+)\\.(\\d+)").find(gradle)!!.groupValues
        assertTrue("JSch with OpenSSH certificate support (>= 2.27)", v[1].toInt() > 2 || (v[1].toInt() == 2 && v[2].toInt() >= 27))
    }

    @Test fun wiring() {
        fun f(vararg c: String) = c.map { File(it) }.first { it.exists() }.readText()
        val gradle = f("build.gradle.kts", "app/build.gradle.kts")
        assertTrue("Bouncy Castle bundled for JSch ed25519", gradle.contains("org.bouncycastle:bcprov-jdk18on"))
        val ssh = f("src/main/java/com/openminis/app/plus/VpsSsh.kt", "app/src/main/java/com/openminis/app/plus/VpsSsh.kt")
        assertTrue(ssh.contains("SshNetCore.GLOBAL.forEach") && ssh.contains("java.net.Proxy.NO_PROXY") && ssh.contains("bindSocket(s)"))
    }
}

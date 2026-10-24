package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.13 tunnel_v1] port assignment, the hub's restricted key line, the generated install script. */
class TunnelCoreTest {
    private val pub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample mines-tunnel@nas"

    @Test fun assignsLowestFreePortInRange() {
        assertEquals(22100, TunnelCore.assign(emptyList(), TunnelCore.SSH_PORTS))
        assertEquals(22102, TunnelCore.assign(listOf(22100, 22101, 22103), TunnelCore.SSH_PORTS))
        assertEquals(22300, TunnelCore.assign(listOf(22100), TunnelCore.PUBLIC_PORTS))
        assertNull(TunnelCore.assign(TunnelCore.WEB_PORTS.toList(), TunnelCore.WEB_PORTS))
    }

    @Test fun rangesDoNotOverlapAndAvoidHubServices() {
        val all = TunnelCore.SSH_PORTS.toList() + TunnelCore.WEB_PORTS + TunnelCore.PUBLIC_PORTS
        assertEquals(all.size, all.toSet().size)
        for (p in listOf(22, 443, 2053, 3000, 8000, 8443, 22022)) assertFalse("$p", p in all)
    }

    @Test fun keyLineAllowsOnlyItsOwnPorts() {
        val l = TunnelCore.keyLine(pub, 22100)
        assertEquals("restrict,port-forwarding,command=\"echo mines-tunnel-only\",permitopen=\"127.0.0.1:22100\"," +
            "permitlisten=\"127.0.0.1:22100\" $pub", l)
        val w = TunnelCore.keyLine(pub, 22101, 22200)
        assertTrue(w.contains("permitlisten=\"127.0.0.1:22200\""))
        assertFalse(w.contains("0.0.0.0"))
        val p = TunnelCore.keyLine("$pub\n", 22101, 22300, webPublic = true)
        assertTrue(p.contains("permitlisten=\"0.0.0.0:22300\""))
        assertTrue(p.endsWith(pub))
        assertTrue(p.startsWith("restrict,"))   // restrict = no pty, no agent / X11, no shell besides the forced command
    }

    @Test fun forwardsMatchTheKeyLine() {
        assertEquals(listOf("-R 127.0.0.1:22100:127.0.0.1:22"), TunnelCore.forwards(22100, 22))
        assertEquals(listOf("-R 127.0.0.1:22100:127.0.0.1:2222", "-R 127.0.0.1:22200:127.0.0.1:5000"),
            TunnelCore.forwards(22100, 2222, 22200, 5000))
        assertEquals("-R 0.0.0.0:22300:127.0.0.1:8080", TunnelCore.forwards(22100, 22, 22300, 8080, webPublic = true)[1])
        assertEquals(1, TunnelCore.forwards(22100, 22, 22200, 0).size)
    }

    private fun template(): String = listOf("src/main/assets/mines-tunnel/install.sh", "app/src/main/assets/mines-tunnel/install.sh")
        .map { File(it) }.first { it.isFile }.readText()

    @Test fun installScriptIsFilledCompletely() {
        val s = TunnelCore.installScript(template(), "ubuntu", "18.143.139.29", 22, TunnelCore.forwards(22100, 22, 22200, 5000))
        assertFalse(s.contains("@HUB_") || s.contains("@FORWARDS@"))
        assertTrue(s.contains("-p 22 -R 127.0.0.1:22100:127.0.0.1:22 -R 127.0.0.1:22200:127.0.0.1:5000 ubuntu@18.143.139.29"))
        assertTrue(s.contains("ServerAliveInterval=15"))
        assertTrue(s.contains("ExitOnForwardFailure=yes"))
        assertTrue(s.contains("Restart=always"))          // systemd
        assertTrue(s.contains("supervise-daemon"))        // OpenRC
        assertTrue(s.contains("while :; do"))             // no init system / not root
        assertTrue(s.contains("echo \"MINES_PUB "))
        assertTrue(s.indexOf("MINES_PUB") < s.indexOf("eval \"\$START\""))   // reply first, restart after
    }

    @Test(expected = IllegalArgumentException::class) fun installScriptRejectsShellInHubAddress() {
        TunnelCore.installScript(template(), "ubuntu", "1.2.3.4; rm -rf /", 22, emptyList())
    }

    @Test fun readsPubKeyFromInstallOutput() {
        assertEquals(pub, TunnelCore.pubFrom("MINES_SVC systemd\nMINES_PUB $pub\n"))
        assertNull(TunnelCore.pubFrom("MINES_ERR 这台机器没有 ssh 客户端"))
    }

    @Test fun listeningOnHub() {
        val ss = """
            State  Recv-Q Send-Q Local Address:Port  Peer Address:Port
            LISTEN 0      128        127.0.0.1:22100      0.0.0.0:*
            LISTEN 0      128          0.0.0.0:22         0.0.0.0:*
            LISTEN 0      128             [::]:22300         [::]:*
        """.trimIndent()
        assertTrue(TunnelCore.listening(ss, 22100))
        assertTrue(TunnelCore.listening(ss, 22300))
        assertFalse(TunnelCore.listening(ss, 22101))
        assertFalse(TunnelCore.listening(ss, 2210))
        assertFalse(TunnelCore.listening(ss, 100))
    }

    @Test fun statusWords() {
        assertEquals("未开启", TunnelCore.status(false, true, true))
        assertEquals("检测中", TunnelCore.status(true, false, false))
        assertEquals("穿透中", TunnelCore.status(true, true, true))
        assertEquals("断开", TunnelCore.status(true, true, false))
    }

    @Test fun sandboxEntryUsesProxyJump() {
        assertEquals("root@127.0.0.1:22100|ssh://ubuntu@18.143.139.29:22", TunnelCore.sandboxEntry("root", 22100, "ubuntu", "18.143.139.29", 22))
    }
}

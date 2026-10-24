package com.openminis.app.plus

import com.openminis.app.agent.VpsSshHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [Mines-38.9.9] bare ssh / scp to a 工作位置 server → hint to use `minis-vps run`. */
class VpsSshHintTest {
    private val sv = "root@8.217.89.133:22|ws://8.217.89.133:8022/,!ubuntu@vps2.example.com:2222|wss://t.example/x"
    @Test fun hosts() = assertEquals(setOf("8.217.89.133", "vps2.example.com"), VpsSshHint.hosts(sv))
    @Test fun bareSsh() {
        assertNotNull(VpsSshHint.hint("ssh -p 22 root@8.217.89.133 'uptime'", sv))
        assertNotNull(VpsSshHint.hint("cd /tmp && scp a.txt root@8.217.89.133:/root/", sv))
        assertNotNull(VpsSshHint.hint("ssh ubuntu@vps2.example.com -p 2222 ls", sv))
    }
    @Test fun fine() {
        assertNull(VpsSshHint.hint("minis-vps run uptime", sv))
        assertNull(VpsSshHint.hint("ssh git@github.com", sv))
        assertNull(VpsSshHint.hint("ssh root@8.217.89.133", "none"))
        assertNull(VpsSshHint.hint("echo 8.217.89.133", sv))
    }
}

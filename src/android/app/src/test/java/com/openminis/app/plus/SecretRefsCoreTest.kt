package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRefsCoreTest {
    private val C = SecretRefsCore

    @Test fun ids() {
        assertEquals("ssh_47_95_1_2_root", C.idFor("ssh", "47.95.1.2", "root"))
        assertEquals("api_openai_com", C.idFor("api", "openai.com", ""))
        assertEquals("secret", C.idFor("", "", ""))
        assertTrue(C.idFor("web", "x".repeat(80), "y").length <= 48)
        assertEquals("MINES_SECRET_SSH_1", C.envName("ssh_1"))
    }

    @Test fun findAndRewrite() {
        val cmd = "sshpass -p {{secret:ssh_1}} ssh {{ user : ssh_1 }}@1.2.3.4 && echo {{secret:other}}"
        assertEquals(setOf("ssh_1", "other"), C.secretIds(cmd))
        assertEquals(setOf("ssh_1"), C.userIds(cmd))
        val out = C.rewrite(cmd, mapOf("ssh_1" to "ro'ot"))
        assertEquals("sshpass -p \"\$MINES_SECRET_SSH_1\" ssh 'ro'\\''ot'@1.2.3.4 && echo \"\$MINES_SECRET_OTHER\"", out)
        assertFalse(out.contains("{{"))
        assertEquals("echo ''", C.rewrite("echo {{user:missing}}", emptyMap()))
        assertTrue(C.secretIds("no refs {{secret:bad id}}").isEmpty())
    }

    @Test fun scrubMasksLongestFirstAndSkipsShort() {
        val t = C.scrub("pw=hunter2 and hunter22 and abc", listOf("hunter2", "hunter22", "abc"))
        assertEquals("pw=•••• and •••• and abc", t)
        assertEquals("x", C.scrub("x", emptyList()))
    }
}

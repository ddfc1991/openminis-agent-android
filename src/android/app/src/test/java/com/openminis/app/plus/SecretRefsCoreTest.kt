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
        val expected = "sshpass -p \"" + '$' + "MINES_SECRET_SSH_1\" ssh 'ro'\\''ot'@1.2.3.4 && echo \"" + '$' + "MINES_SECRET_OTHER\""
        assertEquals(expected, out)
        assertFalse(out.contains("{{"))
        val quoted = C.rewrite("export SSHPASS='{{secret:ssh_1}}'; sshpass -e ssh user@host", emptyMap())
        assertEquals("export SSHPASS=\"" + '$' + "MINES_SECRET_SSH_1\"; sshpass -e ssh user@host", quoted)
        assertEquals("echo \"" + '$' + "MINES_SECRET_SSH_1\"", C.rewrite("echo \"{{secret:ssh_1}}\"", emptyMap()))
        assertEquals(
            "curl -H 'Authorization: Bearer '\"" + '$' + "MINES_SECRET_SSH_1\"'' https://example.test",
            C.rewrite("curl -H 'Authorization: Bearer {{secret:ssh_1}}' https://example.test", emptyMap()),
        )
        assertEquals("echo ''", C.rewrite("echo {{user:missing}}", emptyMap()))
        assertTrue(C.secretIds("no refs {{secret:bad id}}").isEmpty())
    }

    @Test fun unresolvedSecretNeverAppearsAsRunnableShellReference() {
        val command = "curl -H 'Authorization: Bearer {{secret:api_main}}' https://example.test"
        assertEquals(emptySet<String>(), C.missingSecretIds(command, setOf(C.envName("api_main"))))
        assertEquals(setOf("api_main"), C.missingSecretIds(command, emptySet()))
        assertEquals(emptySet<String>(), C.missingSecretIds("echo safe", emptySet()))
    }

    @Test fun scrubMasksLongestFirstAndSkipsShort() {
        val t = C.scrub("pw=hunter2 and hunter22 and abc", listOf("hunter2", "hunter22", "abc"))
        assertEquals("pw=•••• and •••• and abc", t)
        assertEquals("x", C.scrub("x", emptyList()))
    }
}

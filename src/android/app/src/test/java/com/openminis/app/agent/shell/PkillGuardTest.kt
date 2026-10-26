package com.openminis.app.agent.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [38.9.15.1] pkill -f self-kill guard (screenshot 14:52: 「pkill 的 -f 匹配到了 proot 父进程…把自己杀了」). */
class PkillGuardTest {
    @Test fun bracketsFPattern() {
        val r = PkillGuard.check("pkill -f genspark; python3 run.py")
        assertNull(r.refused)
        assertTrue(r.patched)
        assertEquals("pkill -f '[g]enspark'; python3 run.py", r.command)
    }

    @Test fun bracketsQuotedPatternAndPgrep() {
        val r = PkillGuard.check("pgrep -af \"node server.js\" && echo up")
        assertTrue(r.patched)
        assertTrue(r.command, r.command.contains("'[n]ode server.js'"))
        val r2 = PkillGuard.check("cd /x && pkill -9 -f 'uvicorn main:app'")
        assertTrue(r2.command, r2.command.contains("'[u]vicorn main:app'"))
    }

    @Test fun patternMatchesTargetButNotOwnCommandLine() {
        val r = PkillGuard.check("pkill -f genspark")
        val pat = Regex(r.command.substringAfter("-f '").substringBefore("'"))
        assertTrue(pat.containsMatchIn("python3 genspark_proxy.py"))
        assertFalse(pat.containsMatchIn("/bin/sh -c " + r.command))
    }

    @Test fun alreadyBracketedUntouched() {
        val r = PkillGuard.check("pkill -f '[g]enspark'")
        assertFalse(r.patched)
        assertEquals("pkill -f '[g]enspark'", r.command)
    }

    @Test fun refusesSelfKills() {
        assertNotNull(PkillGuard.check("pkill proot").refused)
        assertNotNull(PkillGuard.check("killall sh").refused)
        assertNotNull(PkillGuard.check("kill -9 -1").refused)
        assertNotNull(PkillGuard.check("pkill -f proot").refused)
        assertNotNull(PkillGuard.check("pkill -u root").refused)
    }

    @Test fun normalCommandsUntouched() {
        for (c in listOf("kill 1234", "pkill nginx", "ls -la", "echo pkill -f", "grep -f list.txt file")) {
            val r = PkillGuard.check(c)
            assertNull(c, r.refused)
            assertEquals(c, c, r.command)
        }
    }
}

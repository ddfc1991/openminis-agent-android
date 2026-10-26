package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [38.9.15.1] long scripts / heredocs reach proot intact (file instead of one argv string; E2BIG at 128 KiB). */
class LongCommandTest {
    private val heredoc = "cd /tmp\ncat <<'EOF'\n\$HOME `x` \\ \"q\" 'a'\n\tTAB\nEOF\n"

    @Test fun shortCommandsStayInline() {
        assertFalse(LongCommand.needsFile("ls -la"))
        assertFalse(LongCommand.needsFile(heredoc))
        assertFalse(LongCommand.needsFile("x".repeat(LongCommand.LIMIT_BYTES)))
    }

    @Test fun longOrMultibyteGoesToFile() {
        assertTrue(LongCommand.needsFile(heredoc + "# " + "x".repeat(LongCommand.LIMIT_BYTES)))
        // 20 k CJK chars = 60 kB of UTF-8: over the byte limit although the char count is small
        assertTrue(LongCommand.needsFile("echo " + "中".repeat(20_000)))
        assertTrue(LongCommand.needsFile("echo a\u0000b"))
    }

    @Test fun runnerQuotesPathAndFileKeepsText() {
        assertEquals(". '/.minis-exit/a.sh'", LongCommand.runner("/.minis-exit/a.sh"))
        assertEquals(". '/x/it'\\''s.sh'", LongCommand.runner("/x/it's.sh"))
        assertEquals(heredoc, LongCommand.fileText(heredoc))
        assertEquals("echo hi\n", LongCommand.fileText("echo hi"))
    }

    /** the real shell: a 200 kB script with a heredoc runs byte-for-byte through the file and keeps `exit N` */
    @Test fun realShellRunsLongHeredocIntact() {
        assumeTrue(File("/bin/sh").canExecute())
        val script = heredoc + "# " + "x".repeat(200_000) + "\necho tail; exit 7"
        assertTrue(LongCommand.needsFile(script))
        val f = File.createTempFile("longcmd", ".sh"); f.writeText(LongCommand.fileText(script))
        try {
            val p = ProcessBuilder("/bin/sh", "-c", "/bin/sh -c \"\$1\"; echo rc=\$?", "sh", LongCommand.runner(f.absolutePath)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor()
            assertEquals("\$HOME `x` \\ \"q\" 'a'\n\tTAB\ntail\nrc=7\n", out)
        } finally { f.delete() }
    }
}

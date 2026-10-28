package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Exercises the bundled minis-vps selector with a local fake ssh; no real host or credentials are used. */
class MinisVpsDiagnosticsTest {
    private fun script(): File = listOf(
        "src/main/assets/default_mount/usr/local/bin/minis-vps",
        "app/src/main/assets/default_mount/usr/local/bin/minis-vps",
    ).map(::File).first { it.isFile }

    @Test fun runReportsFailedServerFallbackAndActualConnectedEndpoint() {
        val root = Files.createTempDirectory("minis-vps-test").toFile()
        try {
            val bin = File(root, "bin").apply { mkdirs() }
            val fakeSsh = File(bin, "ssh")
            fakeSsh.writeText("""#!/bin/sh
                dest=""
                last=""
                for arg do
                    last="${'$'}arg"
                    case "${'$'}arg" in *@*) dest="${'$'}arg";; esac
                done
                case "${'$'}dest" in
                    root@bad) echo 'unreachable test host' >&2; exit 255 ;;
                    root@good) [ "${'$'}last" = true ] || echo 'remote-test-output'; exit 0 ;;
                    *) echo 'unexpected test target' >&2; exit 255 ;;
                esac
            """.trimIndent() + "\n")
            assertTrue(fakeSsh.setExecutable(true))
            val envPath = System.getenv("PATH").orEmpty()
            val proc = ProcessBuilder("bash", script().absolutePath, "run", "echo", "safe")
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "${bin.absolutePath}:$envPath"
                    environment()["HOME"] = File(root, "home").apply { mkdirs() }.absolutePath
                    environment()["MINIS_VPS_SERVERS"] = "root@bad:22,root@good:22"
                    environment()["MINIS_VPS_NO_LINK"] = "1"
                    environment()["MINIS_VPS_CONNECT_SECS"] = "1"
                    environment()["MINIS_VPS_RESUME_SECS"] = "1"
                }
                .start()
            assertTrue("minis-vps test timed out", proc.waitFor(8, TimeUnit.SECONDS))
            val output = proc.inputStream.bufferedReader().readText()
            assertEquals("\n$output", 0, proc.exitValue())
            assertTrue(output, output.contains("[fallback] root@bad:22 -> root@good:22"))
            assertTrue(output, output.contains("[connected] root@good:22 via direct"))
            assertTrue(output, output.contains("remote-test-output"))
        } finally {
            root.deleteRecursively()
        }
    }
}

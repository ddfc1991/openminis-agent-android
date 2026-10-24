package com.openminis.app.plus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.2] vault files: tmp + fsync + rename + 2 rolling backups; reads fall back to the backups. */
class AtomicStoreCoreTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("atomic").toFile()
    private val ok = { b: ByteArray -> String(b).startsWith("v") }

    @Test fun writesRotateTwoBackups() {
        val d = dir(); val C = AtomicStoreCore
        C.write(d, "s", "v1".toByteArray())
        assertFalse(C.bak(d, "s", 1).exists())
        C.write(d, "s", "v2".toByteArray()); C.write(d, "s", "v3".toByteArray()); C.write(d, "s", "v4".toByteArray())
        assertEquals("v4", C.main(d, "s").readText()); assertEquals("v3", C.bak(d, "s", 1).readText()); assertEquals("v2", C.bak(d, "s", 2).readText())
        assertFalse("no third backup", File(d, "s.bak3").exists()); assertFalse("no tmp left", C.tmp(d, "s").exists())
        val r = C.read(d, "s", ok); assertArrayEquals("v4".toByteArray(), r.bytes); assertEquals("main", r.source)
        d.deleteRecursively()
    }

    @Test fun readFallsBackThenReportsAllFailed() {
        val d = dir(); val C = AtomicStoreCore
        C.write(d, "s", "v1".toByteArray()); C.write(d, "s", "v2".toByteArray()); C.write(d, "s", "v3".toByteArray())
        C.main(d, "s").writeText("torn")                        // a torn / corrupt main copy
        C.read(d, "s", ok).let { assertEquals("v2", String(it.bytes!!)); assertEquals("bak1", it.source); assertFalse(it.allFailed) }
        C.bak(d, "s", 1).writeBytes(ByteArray(0))               // empty file counts as bad
        C.read(d, "s", ok).let { assertEquals("v1", String(it.bytes!!)); assertEquals("bak2", it.source) }
        C.bak(d, "s", 2).writeText("x")
        C.read(d, "s", ok).let { assertNull(it.bytes); assertTrue(it.allFailed) }
        assertEquals("torn", C.main(d, "s").readText())        // a failed read never overwrites anything
        d.deleteRecursively()
    }

    @Test fun absentIsNotAFailure() {
        val d = dir()
        val r = AtomicStoreCore.read(d, "none", ok)
        assertNull(r.bytes); assertFalse(r.allFailed); assertEquals("absent", r.source)
        AtomicStoreCore.write(File(d, "sub/dir"), "n", "v".toByteArray())   // creates the directory
        assertTrue(AtomicStoreCore.exists(File(d, "sub/dir"), "n"))
        AtomicStoreCore.delete(File(d, "sub/dir"), "n"); assertFalse(AtomicStoreCore.exists(File(d, "sub/dir"), "n"))
        d.deleteRecursively()
    }

    @Test fun storesUseIt() {
        fun src(rel: String) = listOf(File("src/main/java/com/openminis/app/$rel"), File("app/src/main/java/com/openminis/app/$rel")).first { it.exists() }.readText()
        for ((f, name) in listOf("plus/SavedLogins.kt" to "saved_logins.enc", "plus/LoginMemory.kt" to "login_memory.json", "plus/PlusVault.kt" to "plus_vault_entries.json")) {
            val s = src(f)
            assertTrue("$f writes atomically", s.contains("AtomicStore.write(") && s.contains("\"$name\""))
            assertTrue("$f reads the chain + notice", s.contains("AtomicStore.read(") && s.contains("AtomicStore.notice("))
        }
    }
}

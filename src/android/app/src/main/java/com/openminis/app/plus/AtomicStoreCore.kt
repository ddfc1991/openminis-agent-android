package com.openminis.app.plus

import java.io.File
import java.io.FileOutputStream

/**
 * [Mines-38.9.11.2] crash-safe files for Mines' own vaults (PlusVault entries, SavedLogins 密码库, LoginMemory 登录记录).
 * Pure JVM (java.io only), unit-tested (AtomicStoreCoreTest).
 *
 * write: bytes → `<name>.tmp` → fsync → rotate backups (`.bak1` → `.bak2`, current → `.bak1`) → rename tmp → current →
 *        fsync the directory. A crash at any point leaves either the old or the new file complete, plus 2 older copies.
 * read:  current, then `.bak1`, then `.bak2`; the first copy that the caller's [valid] check accepts wins. When every copy that
 *        exists fails, [Read.allFailed] is true (the app shows a one-time notice) and nothing is overwritten by the read.
 */
object AtomicStoreCore {
    const val BACKUPS = 2

    data class Read(val bytes: ByteArray?, val source: String, val allFailed: Boolean) {
        val ok: Boolean get() = bytes != null
    }

    fun main(dir: File, name: String) = File(dir, name)
    fun tmp(dir: File, name: String) = File(dir, "$name.tmp")
    fun bak(dir: File, name: String, i: Int) = File(dir, "$name.bak$i")

    /** all copies, newest first */
    fun chain(dir: File, name: String): List<File> = listOf(main(dir, name)) + (1..BACKUPS).map { bak(dir, name, it) }

    @Synchronized
    fun write(dir: File, name: String, bytes: ByteArray) {
        if (!dir.exists()) dir.mkdirs()
        val t = tmp(dir, name)
        FileOutputStream(t).use { o -> o.write(bytes); o.flush(); runCatching { o.fd.sync() } }
        // rotate: bak(n-1) → bak(n) … current → bak1 (only copies that exist)
        for (i in BACKUPS downTo 2) {
            val from = bak(dir, name, i - 1)
            if (from.exists()) { val to = bak(dir, name, i); to.delete(); from.renameTo(to) }
        }
        val cur = main(dir, name)
        if (cur.exists()) { val b1 = bak(dir, name, 1); b1.delete(); if (!cur.renameTo(b1)) cur.copyTo(b1, overwrite = true) }
        if (!t.renameTo(cur)) { t.copyTo(cur, overwrite = true); t.delete() }
        syncDir(dir)
    }

    /** reads the newest copy that [valid] accepts */
    fun read(dir: File, name: String, valid: (ByteArray) -> Boolean): Read {
        var any = false
        for ((i, f) in chain(dir, name).withIndex()) {
            if (!f.exists()) continue
            any = true
            val b = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (b.isNotEmpty() && runCatching { valid(b) }.getOrDefault(false)) return Read(b, if (i == 0) "main" else "bak$i", false)
        }
        return Read(null, if (any) "none" else "absent", any)
    }

    fun exists(dir: File, name: String) = chain(dir, name).any { it.exists() }

    fun delete(dir: File, name: String) { (chain(dir, name) + tmp(dir, name)).forEach { it.delete() } }

    private fun syncDir(dir: File) {
        // best effort: on Linux a directory can be opened read-only and fsync'ed; on other platforms this is a no-op
        runCatching { java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    }
}

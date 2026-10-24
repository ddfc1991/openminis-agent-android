package com.openminis.app.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [Mines-38.9.11 d] ZIP pack / unpack / listing for the file manager.
 * Pure JVM (java.util.zip) so the rules — path traversal, the zip-bomb ceiling, deterministic entry order — are
 * unit-testable without a device.
 */
object ZipCore {
    class Item(val path: String, val size: Long, val compressed: Long, val isDir: Boolean, val timeMs: Long, val crc: Long, val stored: Boolean) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
        val depth: Int get() = path.trimEnd('/').count { it == '/' }
    }

    /** Result of an unpack: what was written and what was refused (and why). */
    class Extracted(val files: Int, val dirs: Int, val bytes: Long, val skipped: List<String>)

    /**
     * Normalise an entry name to a path INSIDE [dest], or null when it escapes (「../」, absolute, a drive letter,
     * a backslash path, or an empty name). This is the guard against a malicious archive overwriting app files.
     */
    fun safeChild(dest: File, rawName: String): File? {
        val name = rawName.replace('\\', '/')
        if (name.isBlank()) return null
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        var f = dest
        for (p in parts) f = File(f, p)
        val destPath = dest.canonicalFile.path
        val childPath = f.canonicalFile.path
        return if (childPath == destPath || childPath.startsWith(destPath + File.separator)) f else null
    }

    fun list(zip: File, limit: Int = 50_000): List<Item> = ZipFile(zip).use { z ->
        z.entries().asSequence().take(limit).map { e ->
            Item(e.name, if (e.size >= 0) e.size else 0, if (e.compressedSize >= 0) e.compressedSize else 0, e.isDirectory,
                e.time, e.crc, e.method == ZipEntry.STORED)
        }.sortedBy { it.path }.toList()
    }

    /** Read one entry out of an archive (for preview / APK inspection), capped at [maxBytes]. */
    fun readEntry(zip: File, name: String, maxBytes: Int = 64 * 1024 * 1024): ByteArray? = ZipFile(zip).use { z ->
        val e = z.getEntry(name) ?: return null
        z.getInputStream(e).use { ins ->
            val out = java.io.ByteArrayOutputStream(if (e.size in 1..maxBytes.toLong()) e.size.toInt() else 8192)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(buf); if (n <= 0) break
                total += n
                if (total > maxBytes) throw FormatException("条目太大（超过 ${maxBytes / 1024 / 1024} MB）")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    /**
     * Unpack [zip] into [dest]. Entries that would escape [dest] are skipped and named in the result.
     * [maxTotalBytes] stops a zip bomb; progress is reported per entry.
     */
    fun unpack(zip: File, dest: File, maxTotalBytes: Long = 8L * 1024 * 1024 * 1024, onProgress: ((String, Long) -> Unit)? = null): Extracted {
        dest.mkdirs()
        var files = 0; var dirs = 0; var bytes = 0L
        val skipped = ArrayList<String>()
        ZipFile(zip).use { z ->
            val entries = z.entries().toList().sortedBy { it.name }
            for (e in entries) {
                val target = safeChild(dest, e.name)
                if (target == null) { skipped += e.name; continue }
                if (e.isDirectory) { if (target.mkdirs()) dirs++; continue }
                target.parentFile?.mkdirs()
                z.getInputStream(e).use { ins ->
                    target.outputStream().use { outs ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf); if (n <= 0) break
                            bytes += n
                            if (bytes > maxTotalBytes) throw FormatException("解压内容超过上限，已停止")
                            outs.write(buf, 0, n)
                        }
                    }
                }
                if (e.time > 0) target.setLastModified(e.time)
                files++
                onProgress?.invoke(e.name, bytes)
            }
        }
        return Extracted(files, dirs, bytes, skipped)
    }

    /** Pack [sources] (files and/or directories) into [zip]; a directory keeps its own name as the top folder. */
    fun pack(sources: List<File>, zip: File, onProgress: ((String) -> Unit)? = null): Int {
        var n = 0
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.setLevel(6)
            for (src in sources) {
                if (src.isDirectory) {
                    src.walkTopDown().forEach { f ->
                        val rel = src.name + "/" + (f.relativeTo(src).path.replace(File.separatorChar, '/'))
                        val name = rel.trimEnd('/')
                        if (f == src) return@forEach
                        if (f.isDirectory) { out.putNextEntry(ZipEntry("$name/")); out.closeEntry() }
                        else { writeFile(out, name, f); n++; onProgress?.invoke(name) }
                    }
                } else if (src.isFile) { writeFile(out, src.name, src); n++; onProgress?.invoke(src.name) }
            }
        }
        return n
    }

    private fun writeFile(out: ZipOutputStream, name: String, f: File) {
        val e = ZipEntry(name); e.time = f.lastModified()
        out.putNextEntry(e)
        f.inputStream().use { copy(it, out) }
        out.closeEntry()
    }

    private fun copy(ins: InputStream, outs: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) { val n = ins.read(buf); if (n <= 0) break; outs.write(buf, 0, n) }
    }

    /** Is this file a zip container (PK\u0003\u0004 / empty archive / spanned)? */
    fun isZip(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
        ((head[2].toInt() == 3 && head[3].toInt() == 4) || (head[2].toInt() == 5 && head[3].toInt() == 6) || (head[2].toInt() == 7 && head[3].toInt() == 8))

    /** Read the first entry names of a stream-only zip (used for a quick 「里面有什么」 peek). */
    fun peek(ins: InputStream, limit: Int = 20): List<String> = ZipInputStream(ins).use { z ->
        val out = ArrayList<String>()
        while (out.size < limit) { val e = z.nextEntry ?: break; out += e.name; z.closeEntry() }
        out
    }
}

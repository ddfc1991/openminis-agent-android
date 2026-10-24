package com.openminis.app.files

import java.io.File
import java.io.RandomAccessFile

/**
 * [Mines-38.9.11 d] Hex viewer / editor core: one row per 16 bytes, byte ↔ text mapping for the cursor, hex / text
 * search over a window, and an in-place patch that is written only when the file has not changed underneath.
 */
object HexCore {
    const val COLS = 16

    class Row(val offset: Long, val bytes: ByteArray) {
        val hex: String get() = bytes.joinToString(" ") { "%02x".format(it) }.padEnd(COLS * 3 - 1)
        val ascii: String get() = String(CharArray(bytes.size) { i -> val c = bytes[i].toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' })
        val label: String get() = "%08x".format(offset)
    }

    fun rows(data: ByteArray, startOffset: Long = 0): List<Row> =
        (data.indices step COLS).map { i -> Row(startOffset + i, data.copyOfRange(i, minOf(i + COLS, data.size))) }

    /** Read a window of [len] bytes at [off] (clamped to the file). */
    fun window(f: File, off: Long, len: Int): ByteArray {
        val size = f.length()
        if (off >= size) return ByteArray(0)
        val n = minOf(len.toLong(), size - off).toInt()
        val b = ByteArray(n)
        RandomAccessFile(f, "r").use { raf -> raf.seek(off); raf.readFully(b) }
        return b
    }

    /** "ff 00 1a" / "ff001a" / "FF-00" → bytes; null when the text is not a whole number of bytes. */
    fun parseHex(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() && it != '-' && it != ':' && it != ',' }
        if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(clean.length / 2) { ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte() }
    }

    /** Offsets of [needle] in [hay] (all of them, at most [limit]). */
    fun find(hay: ByteArray, needle: ByteArray, from: Int = 0, limit: Int = 1000): List<Int> {
        if (needle.isEmpty() || needle.size > hay.size) return emptyList()
        val out = ArrayList<Int>()
        var i = maxOf(0, from)
        outer@ while (i <= hay.size - needle.size && out.size < limit) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) { i++; continue@outer }
            out += i; i++
        }
        return out
    }

    /** Search the whole file in 1 MB windows that overlap by needle.size - 1, so a match across a window boundary is found. */
    fun findInFile(f: File, needle: ByteArray, limit: Int = 200): List<Long> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        val chunk = 1 shl 20
        var pos = 0L
        val size = f.length()
        RandomAccessFile(f, "r").use { raf ->
            while (pos < size && out.size < limit) {
                val n = minOf(chunk.toLong(), size - pos).toInt()
                val b = ByteArray(n); raf.seek(pos); raf.readFully(b)
                find(b, needle, 0, limit - out.size).forEach { out += pos + it }
                if (n < chunk) break
                pos += (chunk - (needle.size - 1)).coerceAtLeast(1)
            }
        }
        return out
    }

    /**
     * Write [patch] at [off]. [expect] is what the user saw there: when it no longer matches, nothing is written
     * (the file changed under the editor). Never grows the file.
     */
    fun patch(f: File, off: Long, patch: ByteArray, expect: ByteArray? = null): Boolean {
        if (patch.isEmpty()) return false
        if (off < 0 || off + patch.size > f.length()) throw FormatException("修改会超出文件末尾")
        RandomAccessFile(f, "rw").use { raf ->
            if (expect != null) {
                val cur = ByteArray(expect.size); raf.seek(off); raf.readFully(cur)
                if (!cur.contentEquals(expect)) return false
            }
            raf.seek(off); raf.write(patch)
        }
        return true
    }

    /** Printable one-line description of a byte at the cursor. */
    fun describe(b: Byte): String {
        val u = b.toInt() and 0xFF
        val c = if (u in 0x20..0x7E) "'" + u.toChar() + "'" else "不可见"
        return "0x%02x · %d · 有符号 %d · %s".format(u, u, b.toInt(), c)
    }

    /** First bytes → a guessed file kind, for the browser's 「这是什么文件」 line. */
    fun sniff(head: ByteArray): String = when {
        head.size < 4 -> "空文件"
        DexCore.isDex(head) -> "DEX 字节码"
        ZipCore.isZip(head) -> "ZIP 压缩包（APK / JAR 也是）"
        AxmlCore.isAxml(head) -> "二进制 XML（AXML）"
        ArscCore.isArsc(head) -> "resources.arsc 资源表"
        head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() -> "ELF（.so 原生库）"
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "PNG 图片"
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "JPEG 图片"
        head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "WebP 图片"
        head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() -> "PDF 文档"
        head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> "gzip 压缩"
        isMostlyText(head) -> "文本"
        else -> "二进制"
    }

    fun isMostlyText(b: ByteArray): Boolean {
        if (b.isEmpty()) return true
        var printable = 0
        for (x in b) { val u = x.toInt() and 0xFF; if (u == 9 || u == 10 || u == 13 || u in 0x20..0xFF) printable++ }
        return printable * 100 / b.size >= 95 && b.none { it.toInt() == 0 }
    }

    fun humanSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
        else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
    }
}

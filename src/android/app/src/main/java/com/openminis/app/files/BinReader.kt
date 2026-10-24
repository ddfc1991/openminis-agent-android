package com.openminis.app.files

/**
 * [Mines-38.9.11 d] Little-endian reader over a byte array, shared by the AXML / ARSC / DEX / signature-block cores.
 * Pure Kotlin (no Android): every core built on it runs in plain JVM unit tests.
 * All reads are bounds-checked and throw [FormatException] (never ArrayIndexOutOfBounds) so a broken file is a message.
 */
class FormatException(msg: String) : Exception(msg)

class BinReader(val b: ByteArray, val base: Int = 0, val limit: Int = b.size) {
    fun need(off: Int, n: Int) {
        if (off < 0 || n < 0 || off.toLong() + n > limit) throw FormatException("越界读取 @0x${Integer.toHexString(off)} (+$n)，文件可能已损坏")
    }
    fun u8(off: Int): Int { need(off, 1); return b[off].toInt() and 0xFF }
    fun s8(off: Int): Int { need(off, 1); return b[off].toInt() }
    fun u16(off: Int): Int { need(off, 2); return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) }
    fun s16(off: Int): Int = u16(off).toShort().toInt()
    fun s32(off: Int): Int {
        need(off, 4)
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
    fun u32(off: Int): Long = s32(off).toLong() and 0xFFFFFFFFL
    fun s64(off: Int): Long = (u32(off)) or (u32(off + 4) shl 32)
    fun bytes(off: Int, n: Int): ByteArray { need(off, n); return b.copyOfRange(off, off + n) }

    /** ULEB128 at [off] → (value, bytes used). */
    fun uleb(off: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var i = 0
        while (true) {
            val v = u8(off + i); i++
            result = result or ((v and 0x7F) shl shift)
            if (v and 0x80 == 0 || i >= 5) break
            shift += 7
        }
        return result to i
    }

    /** SLEB128 at [off] → (value, bytes used). */
    fun sleb(off: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var i = 0; var v: Int
        while (true) {
            v = u8(off + i); i++
            result = result or ((v and 0x7F) shl shift)
            shift += 7
            if (v and 0x80 == 0 || i >= 5) break
        }
        if (shift < 32 && (v and 0x40) != 0) result = result or (-1 shl shift)
        return result to i
    }
}

object Hex2 {
    private val D = "0123456789abcdef".toCharArray()
    fun of(bytes: ByteArray, sep: String = ""): String {
        val sb = StringBuilder(bytes.size * (2 + sep.length))
        bytes.forEachIndexed { i, x ->
            if (i > 0 && sep.isNotEmpty()) sb.append(sep)
            sb.append(D[(x.toInt() shr 4) and 0xF]).append(D[x.toInt() and 0xF])
        }
        return sb.toString()
    }
    fun sha256(bytes: ByteArray): String = of(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha1(bytes: ByteArray): String = of(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))
    fun md5(bytes: ByteArray): String = of(java.security.MessageDigest.getInstance("MD5").digest(bytes))
}

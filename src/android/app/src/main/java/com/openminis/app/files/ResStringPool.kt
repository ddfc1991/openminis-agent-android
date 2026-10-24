package com.openminis.app.files

/**
 * [Mines-38.9.11 d] ResStringPool chunk (type 0x0001) — the string table used by binary XML (AXML) and resources.arsc.
 * Strings are decoded lazily (an arsc string pool of a big app can hold 100 000 strings).
 */
class ResStringPool(private val r: BinReader, val chunkOff: Int) {
    val headerSize = r.u16(chunkOff + 2)
    val chunkSize = r.s32(chunkOff + 4)
    val count = r.s32(chunkOff + 8)
    val styleCount = r.s32(chunkOff + 12)
    val flags = r.s32(chunkOff + 16)
    val utf8 = (flags and 0x100) != 0
    private val stringsStart = r.s32(chunkOff + 20)
    private val cache = HashMap<Int, String>()

    init {
        if (r.u16(chunkOff) != 0x0001) throw FormatException("不是字符串池 @0x${Integer.toHexString(chunkOff)}")
        if (count < 0 || count > 5_000_000) throw FormatException("字符串池数量异常: $count")
    }

    fun get(i: Int): String? {
        if (i < 0 || i >= count) return null
        cache[i]?.let { return it }
        val off = r.s32(chunkOff + headerSize + i * 4)
        val p = chunkOff + stringsStart + off
        val s = try { if (utf8) readUtf8(p) else readUtf16(p) } catch (e: FormatException) { "<坏字符串 #$i>" }
        cache[i] = s
        return s
    }

    private fun readUtf8(p0: Int): String {
        var p = p0
        // utf-16 length (ignored), then utf-8 byte length; each 1 or 2 bytes
        var n = r.u8(p); p += if (n and 0x80 != 0) 2 else 1
        n = r.u8(p)
        val len: Int
        if (n and 0x80 != 0) { len = ((n and 0x7F) shl 8) or r.u8(p + 1); p += 2 } else { len = n; p += 1 }
        r.need(p, len)
        return String(r.b, p, len, Charsets.UTF_8)
    }

    private fun readUtf16(p0: Int): String {
        var p = p0
        var len = r.u16(p); p += 2
        if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or r.u16(p); p += 2 }
        r.need(p, len * 2)
        val ch = CharArray(len) { i -> r.u16(p + i * 2).toChar() }
        return String(ch)
    }
}

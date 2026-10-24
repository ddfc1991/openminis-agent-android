package com.openminis.app.files

/**
 * [Mines-38.9.11 d] resources.arsc reader: the global string pool, each package's type / key pools, and the entries of
 * one type+config table (string / integer / colour values, references). Read-only.
 */
class ArscCore(bytes: ByteArray) {
    private val r = BinReader(bytes)
    val globalStrings: ResStringPool
    val packages: List<Pkg>

    class TypeSpec(val id: Int, val name: String, val configs: MutableList<TypeChunk> = mutableListOf())
    class TypeChunk(val typeId: Int, val config: String, val off: Int, val entryCount: Int)
    class Pkg(val id: Int, val name: String, val typeStrings: ResStringPool?, val keyStrings: ResStringPool?, val types: List<TypeSpec>)
    class Entry(val resId: Int, val type: String, val key: String, val value: String, val config: String)

    init {
        if (r.u16(0) != 0x0002) throw FormatException("不是 resources.arsc（RES_TABLE）")
        val headerSize = r.u16(2)
        val total = minOf(r.s32(4), bytes.size)
        var pool: ResStringPool? = null
        val pkgs = ArrayList<Pkg>()
        var p = headerSize
        while (p + 8 <= total) {
            val type = r.u16(p); val size = r.s32(p + 4)
            if (size < 8 || p.toLong() + size > total) throw FormatException("arsc 块长度错误 @0x${Integer.toHexString(p)}")
            when (type) {
                0x0001 -> if (pool == null) pool = ResStringPool(r, p)
                0x0200 -> pkgs += readPackage(p, size)
            }
            p += size
        }
        globalStrings = pool ?: throw FormatException("arsc 缺少字符串池")
        packages = pkgs
    }

    private fun readPackage(off: Int, size: Int): Pkg {
        val id = r.s32(off + 8)
        val name = buildString { for (i in 0 until 128) { val c = r.u16(off + 12 + i * 2); if (c == 0) break; append(c.toChar()) } }
        val typeStringsOff = r.s32(off + 268)
        val keyStringsOff = r.s32(off + 276)
        val typeStrings = if (typeStringsOff > 0) ResStringPool(r, off + typeStringsOff) else null
        val keyStrings = if (keyStringsOff > 0) ResStringPool(r, off + keyStringsOff) else null
        val specs = LinkedHashMap<Int, TypeSpec>()
        var p = off + r.u16(off + 2)
        val end = off + size
        while (p + 8 <= end) {
            val t = r.u16(p); val hsz = r.u16(p + 2); val sz = r.s32(p + 4)
            if (sz < 8 || p + sz > end) break
            when (t) {
                0x0202 -> {   // RES_TABLE_TYPE_SPEC
                    val tid = r.u8(p + 8)
                    specs.getOrPut(tid) { TypeSpec(tid, typeStrings?.get(tid - 1) ?: "type$tid") }
                }
                0x0201 -> {   // RES_TABLE_TYPE
                    val tid = r.u8(p + 8)
                    val count = r.s32(p + 12)
                    val cfg = configName(p + 20)
                    specs.getOrPut(tid) { TypeSpec(tid, typeStrings?.get(tid - 1) ?: "type$tid") }
                        .configs += TypeChunk(tid, cfg, p, count)
                }
            }
            p += sz
        }
        return Pkg(id, name, typeStrings, keyStrings, specs.values.toList())
    }

    /** A short readable config name: 「默认」 / zh-rCN / xxhdpi / v26 … (not the full qualifier set). */
    private fun configName(off: Int): String {
        val size = r.s32(off)
        val parts = ArrayList<String>()
        if (size >= 12) {
            unpackLocale(r.u8(off + 8), r.u8(off + 9), 'a')?.let { parts += it }
            unpackLocale(r.u8(off + 10), r.u8(off + 11), '0')?.let { parts += "r$it" }
        }
        if (size >= 16) {
            val dpi = r.u16(off + 14)
            if (dpi != 0) parts += when (dpi) {
                120 -> "ldpi"; 160 -> "mdpi"; 213 -> "tvdpi"; 240 -> "hdpi"; 320 -> "xhdpi"; 480 -> "xxhdpi"; 640 -> "xxxhdpi"
                0xFFFE -> "anydpi"; 0xFFFF -> "nodpi"; else -> "${dpi}dpi"
            }
        }
        if (size >= 28) { val sdk = r.u16(off + 24); if (sdk != 0) parts += "v$sdk" }
        return if (parts.isEmpty()) "默认" else parts.joinToString("-")
    }

    /** 2-letter code, or the packed 3-letter form (high bit set) used for e.g. 「fil」. */
    private fun unpackLocale(b0: Int, b1: Int, base: Char): String? {
        if (b0 == 0) return null
        if (b0 and 0x80 == 0) return "" + b0.toChar() + b1.toChar()
        val first = b1 and 0x1F; val second = ((b1 and 0xE0) shr 5) or ((b0 and 0x03) shl 3); val third = (b0 and 0x7C) shr 2
        return "" + (base + first) + (base + second) + (base + third)
    }

    fun typeOf(pkg: Pkg, typeName: String): TypeSpec? = pkg.types.firstOrNull { it.name == typeName }

    /** Entries of one type chunk, decoded. [limit] caps how many are read (a big app has tens of thousands). */
    fun entries(pkg: Pkg, chunk: TypeChunk, limit: Int = 2000): List<Entry> {
        val p = chunk.off
        val hsz = r.u16(p + 2)
        val entriesStart = r.s32(p + 16)
        val flags = r.u8(p + 9)
        val sparse = flags and 0x01 != 0
        val off16 = flags and 0x02 != 0
        val offsetsOff = p + hsz
        val out = ArrayList<Entry>()
        val n = minOf(chunk.entryCount, limit)
        for (i in 0 until n) {
            val eOff: Int
            var idx = i
            if (sparse) {
                val v = r.s32(offsetsOff + i * 4)
                idx = v and 0xFFFF
                eOff = ((v ushr 16) and 0xFFFF) * 4
            } else if (off16) {
                val v = r.u16(offsetsOff + i * 2)
                if (v == 0xFFFF) continue
                eOff = v * 4
            } else {
                val v = r.s32(offsetsOff + i * 4)
                if (v == -1) continue
                eOff = v
            }
            val e = p + entriesStart + eOff
            if (e + 8 > r.limit) continue
            val eSize = r.u16(e); val eFlags = r.u16(e + 2)
            val compact = eFlags and 0x0008 != 0
            val keyIdx = if (compact) eSize else r.s32(e + 4)
            val key = pkg.keyStrings?.get(keyIdx) ?: "key$keyIdx"
            val resId = (pkg.id shl 24) or (chunk.typeId shl 16) or idx
            val type = pkg.typeStrings?.get(chunk.typeId - 1) ?: "type${chunk.typeId}"
            val value = if (compact) {
                val vType = eFlags ushr 8; val vData = r.s32(e + 4)
                if (vType == 0x03) "\"" + (globalStrings.get(vData) ?: "") + "\"" else AxmlCore.formatValue(vType, vData)
            } else if (eFlags and 0x0001 != 0) {   // complex (bag)
                "（bag，${r.s32(e + 12)} 项）"
            } else {
                val v = e + eSize
                val vType = r.u8(v + 3); val vData = r.s32(v + 4)
                if (vType == 0x03) "\"" + (globalStrings.get(vData) ?: "") + "\"" else AxmlCore.formatValue(vType, vData)
            }
            out += Entry(resId, type, key, value, chunk.config)
        }
        return out
    }

    /** Summary rows for the info panel. */
    fun summary(): List<Pair<String, String>> = buildList {
        add("字符串池" to "${globalStrings.count} 条" + if (globalStrings.utf8) "（UTF-8）" else "（UTF-16）")
        add("包" to packages.joinToString("、") { "${it.name} (0x%02x)".format(it.id) })
        packages.forEach { p -> add("类型" to p.types.joinToString("、") { "${it.name}×${it.configs.size}" }) }
    }

    companion object {
        fun isArsc(b: ByteArray) = b.size >= 12 && (b[0].toInt() and 0xFF) == 0x02 && b[1].toInt() == 0x00
        fun resIdString(id: Int) = "0x%08x".format(id)
    }
}

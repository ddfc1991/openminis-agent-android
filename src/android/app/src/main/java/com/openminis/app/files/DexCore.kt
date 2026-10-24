package com.openminis.app.files

/**
 * [Mines-38.9.11 d] DEX reader: header (magic / version / checksum / SHA-1 signature / section table), string / type /
 * proto / field / method ids, class list and a read-only smali-like disassembly of one class.
 * Pure Kotlin, no Android or third-party code. This is a VIEWER for the user's own / authorised apps: it does not
 * assemble, patch or rewrite dex.
 */
class DexCore(val bytes: ByteArray) {
    private val r = BinReader(bytes)
    val magic: String
    val version: String
    val checksum: Long
    val signature: String
    val fileSize: Long
    val headerSize: Int
    val stringIdsSize: Int; private val stringIdsOff: Int
    val typeIdsSize: Int; private val typeIdsOff: Int
    val protoIdsSize: Int; private val protoIdsOff: Int
    val fieldIdsSize: Int; private val fieldIdsOff: Int
    val methodIdsSize: Int; private val methodIdsOff: Int
    val classDefsSize: Int; private val classDefsOff: Int
    val dataSize: Int; val dataOff: Int
    private val strCache = HashMap<Int, String>()

    init {
        if (bytes.size < 0x70) throw FormatException("文件太小，不是 DEX")
        val m = String(bytes, 0, 4, Charsets.ISO_8859_1)
        if (m != "dex\n") throw FormatException("不是 DEX 文件（魔数 ${Hex2.of(bytes.copyOfRange(0, 4))}）")
        magic = String(bytes, 0, 8, Charsets.ISO_8859_1)
        version = String(bytes, 4, 3, Charsets.ISO_8859_1)
        checksum = r.u32(8)
        signature = Hex2.of(bytes.copyOfRange(12, 32))
        fileSize = r.u32(32)
        headerSize = r.s32(36)
        if (r.u32(40) != 0x12345678L) throw FormatException("不支持的字节序")
        stringIdsSize = r.s32(56); stringIdsOff = r.s32(60)
        typeIdsSize = r.s32(64); typeIdsOff = r.s32(68)
        protoIdsSize = r.s32(72); protoIdsOff = r.s32(76)
        fieldIdsSize = r.s32(80); fieldIdsOff = r.s32(84)
        methodIdsSize = r.s32(88); methodIdsOff = r.s32(92)
        classDefsSize = r.s32(96); classDefsOff = r.s32(100)
        dataSize = r.s32(104); dataOff = r.s32(108)
        for ((n, o) in listOf(stringIdsSize to stringIdsOff, typeIdsSize to typeIdsOff, classDefsSize to classDefsOff))
            if (n < 0 || (n > 0 && (o < 0 || o >= bytes.size))) throw FormatException("DEX 区段表损坏")
    }

    /** Adler-32 over bytes[12..] — what the header's checksum field must hold. */
    fun computedChecksum(): Long { val a = java.util.zip.Adler32(); a.update(bytes, 12, bytes.size - 12); return a.value }
    fun checksumOk(): Boolean = computedChecksum() == checksum
    fun signatureOk(): Boolean = Hex2.sha1(bytes.copyOfRange(32, bytes.size)) == signature

    fun string(i: Int): String {
        if (i < 0 || i >= stringIdsSize) return "<string#$i>"
        strCache[i]?.let { return it }
        val off = r.s32(stringIdsOff + i * 4)
        val (len, n) = r.uleb(off)
        val s = mutf8(off + n, len)
        if (strCache.size < 200_000) strCache[i] = s
        return s
    }

    private fun mutf8(start: Int, utf16Len: Int): String {
        val sb = StringBuilder(utf16Len)
        var p = start
        while (true) {
            val a = r.u8(p)
            if (a == 0) break
            when {
                a < 0x80 -> { sb.append(a.toChar()); p += 1 }
                a and 0xE0 == 0xC0 -> { sb.append((((a and 0x1F) shl 6) or (r.u8(p + 1) and 0x3F)).toChar()); p += 2 }
                a and 0xF0 == 0xE0 -> { sb.append((((a and 0x0F) shl 12) or ((r.u8(p + 1) and 0x3F) shl 6) or (r.u8(p + 2) and 0x3F)).toChar()); p += 3 }
                else -> { sb.append('?'); p += 1 }
            }
            if (sb.length > 1_000_000) break
        }
        return sb.toString()
    }

    fun type(i: Int): String = if (i < 0 || i >= typeIdsSize) "<type#$i>" else string(r.s32(typeIdsOff + i * 4))

    fun protoShorty(i: Int): String = string(r.s32(protoIdsOff + i * 12))
    fun protoReturn(i: Int): String = type(r.s32(protoIdsOff + i * 12 + 4))
    fun protoParams(i: Int): List<String> {
        val off = r.s32(protoIdsOff + i * 12 + 8)
        if (off == 0) return emptyList()
        val n = r.s32(off)
        return List(n) { type(r.u16(off + 4 + it * 2)) }
    }
    fun protoDesc(i: Int): String = "(" + protoParams(i).joinToString("") + ")" + protoReturn(i)

    fun fieldRef(i: Int): String {
        if (i < 0 || i >= fieldIdsSize) return "<field#$i>"
        val o = fieldIdsOff + i * 8
        return type(r.u16(o)) + "->" + string(r.s32(o + 4)) + ":" + type(r.u16(o + 2))
    }
    fun methodRef(i: Int): String {
        if (i < 0 || i >= methodIdsSize) return "<method#$i>"
        val o = methodIdsOff + i * 8
        return type(r.u16(o)) + "->" + string(r.s32(o + 4)) + protoDesc(r.u16(o + 2))
    }
    private fun methodName(i: Int): String = string(r.s32(methodIdsOff + i * 8 + 4))
    private fun methodProto(i: Int): Int = r.u16(methodIdsOff + i * 8 + 2)
    private fun fieldName(i: Int): String = string(r.s32(fieldIdsOff + i * 8 + 4))
    private fun fieldType(i: Int): String = type(r.u16(fieldIdsOff + i * 8 + 2))

    class ClassInfo(val index: Int, val descriptor: String, val access: Int, val superclass: String?, val interfaces: List<String>,
                    val sourceFile: String?) {
        val javaName: String get() = DexCore.javaName(descriptor)
        val packageName: String get() = javaName.substringBeforeLast('.', "")
        val simpleName: String get() = javaName.substringAfterLast('.')
    }

    fun classInfo(i: Int): ClassInfo {
        val o = classDefsOff + i * 32
        val superIdx = r.s32(o + 8); val ifOff = r.s32(o + 12); val srcIdx = r.s32(o + 16)
        val ifs = if (ifOff == 0) emptyList() else List(r.s32(ifOff)) { type(r.u16(ifOff + 4 + it * 2)) }
        return ClassInfo(i, type(r.s32(o)), r.s32(o + 4), if (superIdx == -1) null else type(superIdx), ifs,
            if (srcIdx == -1) null else string(srcIdx))
    }

    fun classes(): List<ClassInfo> = List(classDefsSize) { classInfo(it) }

    fun findClass(descriptorOrJava: String): Int {
        val d = if (descriptorOrJava.startsWith("L") && descriptorOrJava.endsWith(";")) descriptorOrJava else "L" + descriptorOrJava.replace('.', '/') + ";"
        for (i in 0 until classDefsSize) if (type(r.s32(classDefsOff + i * 32)) == d) return i
        return -1
    }

    /** Header summary lines (Chinese labels) for the info panel. */
    fun headerLines(): List<Pair<String, String>> = listOf(
        "版本" to "dex $version",
        "校验和" to "0x%08x %s".format(checksum, if (checksumOk()) "（正确）" else "（不符，实际 0x%08x）".format(computedChecksum())),
        "SHA-1 签名" to signature + if (signatureOk()) "（正确）" else "（不符）",
        "文件大小" to "$fileSize 字节",
        "字符串" to "$stringIdsSize", "类型" to "$typeIdsSize", "方法原型" to "$protoIdsSize",
        "字段" to "$fieldIdsSize", "方法" to "$methodIdsSize", "类" to "$classDefsSize",
    )

    class Member(val idx: Int, val access: Int, val codeOff: Int)

    private fun classData(off: Int): List<List<Member>> {
        if (off == 0) return listOf(emptyList(), emptyList(), emptyList(), emptyList())
        var p = off
        fun u(): Int { val (v, n) = r.uleb(p); p += n; return v }
        val sf = u(); val inf = u(); val dm = u(); val vm = u()
        fun fields(n: Int): List<Member> { var idx = 0; return List(n) { idx += u(); Member(idx, u(), 0) } }
        fun methods(n: Int): List<Member> { var idx = 0; return List(n) { idx += u(); val a = u(); Member(idx, a, u()) } }
        val s = fields(sf); val i = fields(inf); val d = methods(dm); val v = methods(vm)
        return listOf(s, i, d, v)
    }

    /** smali-like listing of class [i] (read-only view). */
    fun disassemble(i: Int, maxChars: Int = 2_000_000): String {
        val c = classInfo(i)
        val o = classDefsOff + i * 32
        val sb = StringBuilder()
        sb.append(".class ").append(accessString(c.access, ACC_CLASS)).append(c.descriptor).append('\n')
        c.superclass?.let { sb.append(".super ").append(it).append('\n') }
        c.sourceFile?.let { sb.append(".source \"").append(escape(it)).append("\"\n") }
        c.interfaces.forEach { sb.append(".implements ").append(it).append('\n') }
        val data = classData(r.s32(o + 24))
        val staticVals = encodedArray(r.s32(o + 28))
        if (data[0].isNotEmpty()) sb.append("\n# static fields\n")
        data[0].forEachIndexed { k, f ->
            sb.append(".field ").append(accessString(f.access, ACC_FIELD)).append(fieldName(f.idx)).append(':').append(fieldType(f.idx))
            staticVals.getOrNull(k)?.let { sb.append(" = ").append(it) }
            sb.append('\n')
        }
        if (data[1].isNotEmpty()) sb.append("\n# instance fields\n")
        data[1].forEach { f -> sb.append(".field ").append(accessString(f.access, ACC_FIELD)).append(fieldName(f.idx)).append(':').append(fieldType(f.idx)).append('\n') }
        for ((title, list) in listOf("direct methods" to data[2], "virtual methods" to data[3])) {
            if (list.isEmpty()) continue
            sb.append("\n# ").append(title).append('\n')
            for (m in list) {
                if (sb.length > maxChars) { sb.append("\n# …（内容过长，已截断）\n"); return sb.toString() }
                sb.append(".method ").append(accessString(m.access, ACC_METHOD)).append(methodName(m.idx)).append(protoDesc(methodProto(m.idx))).append('\n')
                if (m.codeOff != 0) code(m.codeOff, sb)
                sb.append(".end method\n\n")
            }
        }
        return sb.toString()
    }

    private fun code(off: Int, sb: StringBuilder) {
        val regs = r.u16(off); val ins = r.u16(off + 2); val tries = r.u16(off + 6)
        val insnsSize = r.s32(off + 12)
        val start = off + 16
        sb.append("    .registers ").append(regs).append("    # 参数寄存器 v").append(regs - ins).append("…v").append(regs - 1).append('\n')
        r.need(start, insnsSize * 2)
        var pc = 0
        var guard = 0
        while (pc < insnsSize && guard++ < 500_000) {
            val w = r.u16(start + pc * 2)
            val op = w and 0xFF
            if (op == 0 && w != 0) {               // payload pseudo-instruction
                val len = payload(start, pc, w, sb)
                pc += maxOf(1, len); continue
            }
            val len = insn(start, pc, op, w, sb)
            pc += maxOf(1, len)
        }
        if (tries > 0) {
            var t = start + insnsSize * 2
            if (insnsSize % 2 == 1) t += 2
            val handlersBase = t + tries * 8
            for (k in 0 until tries) {
                val s = r.s32(t + k * 8); val n = r.u16(t + k * 8 + 4); val h = r.u16(t + k * 8 + 6)
                var p = handlersBase + h
                val (size, u1) = r.sleb(p); p += u1
                repeat(kotlin.math.abs(size)) {
                    val (ti, a) = r.uleb(p); p += a; val (addr, b) = r.uleb(p); p += b
                    sb.append("    .catch ").append(type(ti)).append(" {:%04x .. :%04x} :%04x\n".format(s, s + n, addr))
                }
                if (size <= 0) { val (addr, _) = r.uleb(p); sb.append("    .catchall {:%04x .. :%04x} :%04x\n".format(s, s + n, addr)) }
            }
        }
    }

    private fun payload(start: Int, pc: Int, w: Int, sb: StringBuilder): Int {
        val p = start + pc * 2
        return when (w) {
            0x0100 -> { val n = r.u16(p + 2); val first = r.s32(p + 4)
                sb.append("    %04x: .packed-switch 0x%x  # %d 项\n".format(pc, first, n)); 4 + n * 2 }
            0x0200 -> { val n = r.u16(p + 2)
                sb.append("    %04x: .sparse-switch  # %d 项\n".format(pc, n))
                for (k in 0 until minOf(n, 64)) sb.append("        0x%x -> +%d\n".format(r.s32(p + 4 + k * 4), r.s32(p + 4 + n * 4 + k * 4)))
                2 + n * 4 }
            0x0300 -> { val width = r.u16(p + 2); val n = r.s32(p + 4)
                sb.append("    %04x: .array-data %d  # %d 个元素\n".format(pc, width, n)); 4 + ((n.toLong() * width + 1) / 2).toInt() }
            else -> { sb.append("    %04x: nop  # 0x%04x\n".format(pc, w)); 1 }
        }
    }

    private fun insn(start: Int, pc: Int, op: Int, w: Int, sb: StringBuilder): Int {
        val name = NAMES[op]; val fmt = FMTS[op]
        val p = start + pc * 2
        fun u(k: Int) = r.u16(p + k * 2)
        val a8 = (w shr 8) and 0xFF; val a4 = (w shr 8) and 0xF; val b4 = (w shr 12) and 0xF
        sb.append("    %04x: ".format(pc))
        if (name.isEmpty()) { sb.append("<未用操作码 0x%02x>\n".format(op)); return 1 }
        sb.append(name).append(' ')
        val len: Int = when (fmt) {
            "10x" -> 1
            "12x" -> { sb.append("v$a4, v$b4"); 1 }
            "11n" -> { sb.append("v$a4, ").append(lit((b4 shl 28) shr 28)); 1 }
            "11x" -> { sb.append("v$a8"); 1 }
            "10t" -> { sb.append(target(pc, a8.toByte().toInt())); 1 }
            "20t" -> { sb.append(target(pc, u(1).toShort().toInt())); 2 }
            "22x" -> { sb.append("v$a8, v${u(1)}"); 2 }
            "21t" -> { sb.append("v$a8, ").append(target(pc, u(1).toShort().toInt())); 2 }
            "21s" -> { sb.append("v$a8, ").append(lit(u(1).toShort().toInt())); 2 }
            "21ih" -> { sb.append("v$a8, ").append(lit(u(1) shl 16)); 2 }
            "21lh" -> { sb.append("v$a8, 0x").append(java.lang.Long.toHexString(u(1).toLong() shl 48)).append('L'); 2 }
            "21c" -> { sb.append("v$a8, ").append(ref(op, u(1))); 2 }
            "23x" -> { val bc = u(1); sb.append("v$a8, v${bc and 0xFF}, v${bc shr 8}"); 2 }
            "22b" -> { val bc = u(1); sb.append("v$a8, v${bc and 0xFF}, ").append(lit((bc shr 8).toByte().toInt())); 2 }
            "22t" -> { sb.append("v$a4, v$b4, ").append(target(pc, u(1).toShort().toInt())); 2 }
            "22s" -> { sb.append("v$a4, v$b4, ").append(lit(u(1).toShort().toInt())); 2 }
            "22c" -> { sb.append("v$a4, v$b4, ").append(ref(op, u(1))); 2 }
            "30t" -> { sb.append(target(pc, u(1) or (u(2) shl 16))); 3 }
            "32x" -> { sb.append("v${u(1)}, v${u(2)}"); 3 }
            "31i" -> { sb.append("v$a8, ").append(lit(u(1) or (u(2) shl 16))); 3 }
            "31t" -> { sb.append("v$a8, ").append(target(pc, u(1) or (u(2) shl 16))); 3 }
            "31c" -> { sb.append("v$a8, ").append(ref(op, u(1) or (u(2) shl 16))); 3 }
            "35c" -> { regs35(b4, a4, u(2), sb); sb.append(", ").append(ref(op, u(1))); 3 }
            "3rc" -> { val c = u(2); sb.append(if (a8 == 0) "{}" else "{v$c .. v${c + a8 - 1}}").append(", ").append(ref(op, u(1))); 3 }
            "45cc" -> { regs35(b4, a4, u(2), sb); sb.append(", ").append(methodRef(u(1))).append(", ").append(protoDesc(u(3))); 4 }
            "4rcc" -> { val c = u(2); sb.append("{v$c .. v${c + a8 - 1}}, ").append(methodRef(u(1))).append(", ").append(protoDesc(u(3))); 4 }
            "51l" -> { val v = (u(1).toLong()) or (u(2).toLong() shl 16) or (u(3).toLong() shl 32) or (u(4).toLong() shl 48)
                sb.append("v$a8, 0x").append(java.lang.Long.toHexString(v)).append('L'); 5 }
            else -> { sb.append("# 格式 $fmt"); 1 }
        }
        sb.append('\n')
        return len
    }

    private fun regs35(count: Int, g: Int, cdef: Int, sb: StringBuilder) {
        val rs = listOf(cdef and 0xF, (cdef shr 4) and 0xF, (cdef shr 8) and 0xF, (cdef shr 12) and 0xF, g)
        sb.append('{').append(rs.take(count.coerceIn(0, 5)).joinToString(", ") { "v$it" }).append('}')
    }

    private fun target(pc: Int, off: Int) = ":%04x".format(pc + off)
    private fun lit(v: Int) = if (v in -9..9) v.toString() else (if (v < 0) "-0x" + Integer.toHexString(-v) else "0x" + Integer.toHexString(v))

    private fun ref(op: Int, idx: Int): String = when (op) {
        0x1a, 0x1b -> "\"" + escape(string(idx)) + "\""
        0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25 -> type(idx)
        in 0x52..0x6d -> fieldRef(idx)
        in 0x6e..0x78 -> methodRef(idx)
        0xfc, 0xfd -> "call_site#$idx"
        0xfe -> "method_handle#$idx"
        0xff -> protoDesc(idx)
        else -> "#$idx"
    }

    private fun encodedArray(off: Int): List<String> {
        if (off == 0) return emptyList()
        var p = off
        val (n, k) = r.uleb(p); p += k
        val out = ArrayList<String>()
        repeat(minOf(n, 100_000)) {
            val head = r.u8(p); p++
            val vt = head and 0x1F; val arg = head shr 5
            when (vt) {
                0x1c -> { out += "{…}"; return out }   // nested array: stop decoding (rare in static values)
                0x1d -> { out += "@…"; return out }
                0x1e -> out += "null"
                0x1f -> out += (arg != 0).toString()
                else -> {
                    val size = arg + 1
                    var v = 0L
                    for (q in 0 until size) v = v or (r.u8(p + q).toLong() shl (8 * q))
                    p += size
                    out += when (vt) {
                        0x00 -> lit(v.toByte().toInt())
                        0x02, 0x04 -> { val sh = 64 - size * 8; lit(((v shl sh) shr sh).toInt()) }
                        0x03 -> "'" + v.toInt().toChar() + "'"
                        0x06 -> { val sh = 64 - size * 8; "0x" + java.lang.Long.toHexString((v shl sh) shr sh) + "L" }
                        0x10 -> java.lang.Float.intBitsToFloat((v shl (32 - size * 8)).toInt()).toString() + "f"
                        0x11 -> java.lang.Double.longBitsToDouble(v shl (64 - size * 8)).toString()
                        0x17 -> "\"" + escape(string(v.toInt())) + "\""
                        0x18 -> type(v.toInt())
                        0x19, 0x1b -> fieldRef(v.toInt())
                        0x1a -> methodRef(v.toInt())
                        else -> "0x" + java.lang.Long.toHexString(v)
                    }
                }
            }
        }
        return out
    }

    companion object {
        const val ACC_CLASS = 0; const val ACC_FIELD = 1; const val ACC_METHOD = 2

        fun isDex(b: ByteArray) = b.size >= 8 && b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() && b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()

        fun javaName(desc: String): String {
            var d = desc; var dims = 0
            while (d.startsWith("[")) { dims++; d = d.substring(1) }
            val base = when (d) {
                "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"; "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"
                else -> if (d.startsWith("L") && d.endsWith(";")) d.substring(1, d.length - 1).replace('/', '.') else d
            }
            return base + "[]".repeat(dims)
        }

        fun accessString(a: Int, kind: Int): String {
            val sb = StringBuilder()
            fun f(bit: Int, s: String) { if (a and bit != 0) sb.append(s).append(' ') }
            f(0x1, "public"); f(0x2, "private"); f(0x4, "protected"); f(0x8, "static"); f(0x10, "final")
            when (kind) {
                ACC_METHOD -> { f(0x20, "synchronized"); f(0x40, "bridge"); f(0x80, "varargs"); f(0x100, "native") }
                ACC_FIELD -> { f(0x40, "volatile"); f(0x80, "transient") }
            }
            if (kind == ACC_CLASS) f(0x200, "interface")
            f(0x400, "abstract")
            if (kind == ACC_METHOD) f(0x800, "strictfp")
            f(0x1000, "synthetic")
            if (kind == ACC_CLASS) { f(0x2000, "annotation"); f(0x4000, "enum") }
            if (kind == ACC_FIELD) f(0x4000, "enum")
            if (kind == ACC_METHOD) { f(0x10000, "constructor"); f(0x20000, "declared-synchronized") }
            return sb.toString()
        }

        fun escape(s: String): String = buildString {
            for (c in s) when {
                c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\t' -> append("\\t"); c == '\r' -> append("\\r")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }

    private val NAMES = (
        "nop|move|move/from16|move/16|move-wide|move-wide/from16|move-wide/16|move-object|move-object/from16|move-object/16|move-result|move-result-wide|move-result-object|move-exception|re" +
        "turn-void|return|return-wide|return-object|const/4|const/16|const|const/high16|const-wide/16|const-wide/32|const-wide|const-wide/high16|const-string|const-string/jumbo|const-class|" +
        "monitor-enter|monitor-exit|check-cast|instance-of|array-length|new-instance|new-array|filled-new-array|filled-new-array/range|fill-array-data|throw|goto|goto/16|goto/32|packed-swit" +
        "ch|sparse-switch|cmpl-float|cmpg-float|cmpl-double|cmpg-double|cmp-long|if-eq|if-ne|if-lt|if-ge|if-gt|if-le|if-eqz|if-nez|if-ltz|if-gez|if-gtz|if-lez|||||||aget|aget-wide|aget-obje" +
        "ct|aget-boolean|aget-byte|aget-char|aget-short|aput|aput-wide|aput-object|aput-boolean|aput-byte|aput-char|aput-short|iget|iget-wide|iget-object|iget-boolean|iget-byte|iget-char|ig" +
        "et-short|iput|iput-wide|iput-object|iput-boolean|iput-byte|iput-char|iput-short|sget|sget-wide|sget-object|sget-boolean|sget-byte|sget-char|sget-short|sput|sput-wide|sput-object|sp" +
        "ut-boolean|sput-byte|sput-char|sput-short|invoke-virtual|invoke-super|invoke-direct|invoke-static|invoke-interface|return-void-no-barrier|invoke-virtual/range|invoke-super/range|in" +
        "voke-direct/range|invoke-static/range|invoke-interface/range|||neg-int|not-int|neg-long|not-long|neg-float|neg-double|int-to-long|int-to-float|int-to-double|long-to-int|long-to-flo" +
        "at|long-to-double|float-to-int|float-to-long|float-to-double|double-to-int|double-to-long|double-to-float|int-to-byte|int-to-char|int-to-short|add-int|sub-int|mul-int|div-int|rem-i" +
        "nt|and-int|or-int|xor-int|shl-int|shr-int|ushr-int|add-long|sub-long|mul-long|div-long|rem-long|and-long|or-long|xor-long|shl-long|shr-long|ushr-long|add-float|sub-float|mul-float|" +
        "div-float|rem-float|add-double|sub-double|mul-double|div-double|rem-double|add-int/2addr|sub-int/2addr|mul-int/2addr|div-int/2addr|rem-int/2addr|and-int/2addr|or-int/2addr|xor-int/" +
        "2addr|shl-int/2addr|shr-int/2addr|ushr-int/2addr|add-long/2addr|sub-long/2addr|mul-long/2addr|div-long/2addr|rem-long/2addr|and-long/2addr|or-long/2addr|xor-long/2addr|shl-long/2ad" +
        "dr|shr-long/2addr|ushr-long/2addr|add-float/2addr|sub-float/2addr|mul-float/2addr|div-float/2addr|rem-float/2addr|add-double/2addr|sub-double/2addr|mul-double/2addr|div-double/2add" +
        "r|rem-double/2addr|add-int/lit16|rsub-int|mul-int/lit16|div-int/lit16|rem-int/lit16|and-int/lit16|or-int/lit16|xor-int/lit16|add-int/lit8|rsub-int/lit8|mul-int/lit8|div-int/lit8|re" +
        "m-int/lit8|and-int/lit8|or-int/lit8|xor-int/lit8|shl-int/lit8|shr-int/lit8|ushr-int/lit8||||||||||||||||||||||||invoke-polymorphic|invoke-polymorphic/range|invoke-custom|invoke-cus" +
        "tom/range|const-method-handle|const-method-type"
    ).split('|')
    private val FMTS = (
        "10x|12x|22x|32x|12x|22x|32x|12x|22x|32x|11x|11x|11x|11x|10x|11x|11x|11x|11n|21s|31i|21ih|21s|31i|51l|21lh|21c|31c|21c|11x|11x|21c|22c|12x|21c|22c|35c|3rc|31t|11x|10t|20t|30t|31t|31" +
        "t|23x|23x|23x|23x|23x|22t|22t|22t|22t|22t|22t|21t|21t|21t|21t|21t|21t|||||||23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|22c|22c|22c|22c|22c|22c|22c|22c|22c|22c|22c|22c|" +
        "22c|22c|21c|21c|21c|21c|21c|21c|21c|21c|21c|21c|21c|21c|21c|21c|35c|35c|35c|35c|35c|10x|3rc|3rc|3rc|3rc|3rc|||12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12" +
        "x|12x|12x|12x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|23x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12" +
        "x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|12x|22s|22s|22s|22s|22s|22s|22s|22s|22b|22b|22b|22b|22b|22b|22b|22b|22b|22b|22b|||||||||||||||" +
        "|||||||||45cc|4rcc|35c|3rc|21c|21c"
    ).split('|')
    }
}

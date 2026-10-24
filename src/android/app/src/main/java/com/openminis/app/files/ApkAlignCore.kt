package com.openminis.app.files

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * [Mines-38.9.11 d] zipalign in Kotlin: rewrites an APK so every STORED entry's data starts on a 4-byte boundary and
 * every uncompressed native library (`.so` under lib/) on a 4 KB (16 KB on request) one, by padding the entry's extra field. Deflated
 * entries are copied as-is. Also a checker that says whether a file is already aligned.
 *
 * Why in-process: the phone has no zipalign binary, and an APK that is signed before being aligned is rejected
 * ("not aligned") by the installer.
 */
object ApkAlignCore {
    const val ALIGN_DEFAULT = 4
    const val ALIGN_SO = 4096
    private const val SAFE_DOS_TIME = 347_155_200_000L   // 1981-01-01 UTC

    class Report(val total: Int, val misaligned: List<String>) { val aligned: Boolean get() = misaligned.isEmpty() }

    private fun alignmentFor(name: String, soAlign: Int): Int = if (name.endsWith(".so")) soAlign else ALIGN_DEFAULT

    /** Central-directory record: name, compression method, local-header offset. */
    class CdEntry(val name: String, val method: Int, val localOffset: Long)

    /** Read the central directory without java.util.zip (we need each entry's local-header offset). */
    fun centralDirectory(raf: RandomAccessFile): List<CdEntry> {
        val cdOff = ApkSigCore.centralDirOffset(raf) ?: throw FormatException("找不到 ZIP 目录（文件不完整？）")
        val len = raf.length()
        val tail = minOf(len, 65_557L).toInt()
        val buf = ByteArray(tail); raf.seek(len - tail); raf.readFully(buf)
        val tr = BinReader(buf)
        var eocd = -1
        for (i in tail - 22 downTo 0) if (tr.s32(i) == 0x06054b50 && i + 22 + tr.u16(i + 20) == tail) { eocd = i; break }
        if (eocd < 0) throw FormatException("找不到 ZIP 结尾记录")
        val cdSize = tr.u32(eocd + 12)
        if (cdSize > Int.MAX_VALUE.toLong() || cdOff + cdSize > len) throw FormatException("ZIP 目录长度错误")
        val cd = ByteArray(cdSize.toInt()); raf.seek(cdOff); raf.readFully(cd)
        val r = BinReader(cd)
        val out = ArrayList<CdEntry>()
        var p = 0
        while (p + 46 <= cd.size && r.s32(p) == 0x02014b50) {
            val method = r.u16(p + 10)
            val n = r.u16(p + 28); val x = r.u16(p + 30); val c = r.u16(p + 32)
            val lho = r.u32(p + 42)
            out += CdEntry(String(cd, p + 46, n, Charsets.UTF_8), method, lho)
            p += 46 + n + x + c
        }
        return out
    }

    /** Check an existing APK: which STORED entries do not start on their boundary. */
    fun check(apk: File, soAlign: Int = ALIGN_SO): Report {
        val bad = ArrayList<String>()
        var total = 0
        RandomAccessFile(apk, "r").use { raf ->
            for (e in centralDirectory(raf)) {
                if (e.name.endsWith("/")) continue
                total++
                if (e.method != 0) continue
                val data = dataOffset(raf, e.localOffset) ?: continue
                val a = alignmentFor(e.name, soAlign)
                if (data % a != 0L) bad += "${e.name} @${data} (需 ${a})"
            }
        }
        return Report(total, bad)
    }

    /**
     * Write an aligned copy of [src] to [dst]. STORED entries keep their bytes and get extra-field padding;
     * DEFLATED entries are re-deflated by ZipOutputStream (content identical).
     */
    fun align(src: File, dst: File, soAlign: Int = ALIGN_SO): Int {
        var padded = 0
        ZipFile(src).use { z ->
            java.io.BufferedOutputStream(dst.outputStream()).use { bos ->
                val counting = CountingStream(bos)
                ZipOutputStream(counting).use { out ->
                    for (e in z.entries().toList()) {
                        if (e.isDirectory) { out.putNextEntry(ZipEntry(e.name)); out.closeEntry(); continue }
                        val bytes = z.getInputStream(e).use { it.readBytes() }
                        val ne = ZipEntry(e.name)
                        // a time outside the DOS range (aapt writes 1980-01-01) makes ZipOutputStream add an extended-timestamp
                        // field to the local header, which would shift the data off the boundary we pad for → clamp
                        ne.time = if (e.time < SAFE_DOS_TIME) SAFE_DOS_TIME else e.time
                        if (e.method == ZipEntry.STORED) {
                            ne.method = ZipEntry.STORED
                            ne.size = bytes.size.toLong()
                            ne.compressedSize = bytes.size.toLong()
                            val crc = CRC32(); crc.update(bytes); ne.crc = crc.value
                            val a = alignmentFor(e.name, soAlign)
                            // local header = 30 + name + extra; pad the extra field so the DATA lands on the boundary
                            val headerEnd = counting.count + 30 + ne.name.toByteArray(Charsets.UTF_8).size
                            // the source extra field is dropped (ZipOutputStream filters some tags, which would change its
                            // length): the new one is only zero padding, like zipalign's
                            val pad = ((a - (headerEnd % a)) % a).toInt()
                            if (pad > 0) { padded++; ne.extra = ByteArray(pad) }
                        } else {
                            ne.method = ZipEntry.DEFLATED
                        }
                        out.putNextEntry(ne)
                        out.write(bytes)
                        out.closeEntry()
                    }
                }
            }
        }
        return padded
    }

    private class CountingStream(val out: java.io.OutputStream) : java.io.OutputStream() {
        var count: Long = 0
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
    }

    /** Where an entry's bytes begin, from its local header. */
    fun dataOffset(raf: RandomAccessFile, localHeaderOffset: Long): Long? {
        if (localHeaderOffset < 0 || localHeaderOffset + 30 > raf.length()) return null
        val h = ByteArray(30)
        raf.seek(localHeaderOffset); raf.readFully(h)
        val r = BinReader(h)
        if (r.s32(0) != 0x04034b50) return null
        return localHeaderOffset + 30 + r.u16(26) + r.u16(28)
    }
}

package com.openminis.app.files

import java.io.File
import java.io.RandomAccessFile

/**
 * [Mines-38.9.11 d] APK signature READER: v1 (JAR, META-INF RSA/DSA/EC PKCS#7 files), v2 (block id 0x7109871a),
 * v3 (0xf05368c0) and v3.1 (0x1b93ad61) signers from the APK Signing Block → certificates with SHA-256 / SHA-1 / MD5,
 * subject, validity. It only reads and reports; nothing here strips or bypasses a signature check.
 */
object ApkSigCore {
    const val ID_V2 = 0x7109871a
    const val ID_V3 = 0xf05368c0.toInt()
    const val ID_V31 = 0x1b93ad61
    const val ID_STAMP = 0x6dff800d
    const val ID_PADDING = 0x42726577
    private const val MAGIC = "APK Sig Block 42"

    class CertInfo(val der: ByteArray) {
        val sha256: String = Hex2.sha256(der)
        val sha1: String = Hex2.sha1(der)
        val md5: String = Hex2.md5(der)
        private val x509: java.security.cert.X509Certificate? = runCatching {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as java.security.cert.X509Certificate
        }.getOrNull()
        val subject: String get() = x509?.subjectX500Principal?.name ?: "?"
        val issuer: String get() = x509?.issuerX500Principal?.name ?: "?"
        val notBefore: java.util.Date? get() = x509?.notBefore
        val notAfter: java.util.Date? get() = x509?.notAfter
        val keyAlgorithm: String get() = x509?.publicKey?.algorithm ?: "?"
        val sigAlgorithm: String get() = x509?.sigAlgName ?: "?"
        val serial: String get() = x509?.serialNumber?.toString(16) ?: "?"
    }

    class Signer(val scheme: String, val certs: List<CertInfo>, val algorithms: List<Int>, val minSdk: Int? = null, val maxSdk: Int? = null)

    class Report(
        val v1: List<Signer>, val v2: List<Signer>, val v3: List<Signer>, val v31: List<Signer>,
        val blockIds: List<Int>, val hasSourceStamp: Boolean, val error: String? = null,
    ) {
        val schemes: List<String> get() = buildList {
            if (v1.isNotEmpty()) add("v1"); if (v2.isNotEmpty()) add("v2"); if (v3.isNotEmpty()) add("v3"); if (v31.isNotEmpty()) add("v3.1")
        }
        /** The certificate Android shows for the app (v3.1 > v3 > v2 > v1). */
        val primary: CertInfo? get() = (v31.firstOrNull() ?: v3.firstOrNull() ?: v2.firstOrNull() ?: v1.firstOrNull())?.certs?.firstOrNull()
    }

    fun algorithmName(id: Int): String = when (id) {
        0x0101 -> "RSASSA-PSS SHA-256"; 0x0102 -> "RSASSA-PSS SHA-512"
        0x0103 -> "RSA PKCS#1 SHA-256"; 0x0104 -> "RSA PKCS#1 SHA-512"
        0x0201 -> "ECDSA SHA-256"; 0x0202 -> "ECDSA SHA-512"; 0x0301 -> "DSA SHA-256"
        0x0421 -> "RSA SHA-256 verity"; 0x0423 -> "ECDSA SHA-256 verity"; 0x0425 -> "DSA SHA-256 verity"
        else -> "0x%04x".format(id)
    }

    fun read(apk: File): Report {
        val v1 = runCatching { readV1(apk) }.getOrDefault(emptyList())
        return try {
            RandomAccessFile(apk, "r").use { raf ->
                val block = signingBlock(raf) ?: return Report(v1, emptyList(), emptyList(), emptyList(), emptyList(), false)
                fromBlock(block, v1)
            }
        } catch (e: Exception) {
            Report(v1, emptyList(), emptyList(), emptyList(), emptyList(), false, e.message ?: e.toString())
        }
    }

    /** Parse an already-extracted APK Signing Block (from the 8-byte size field to the magic). */
    fun fromBlock(block: ByteArray, v1: List<Signer> = emptyList()): Report {
        val pairs = pairs(block)
        val ids = pairs.map { it.first }
        fun signers(id: Int, scheme: String) = pairs.filter { it.first == id }.flatMap { parseSigners(it.second, scheme, id != ID_V2) }
        return Report(v1, signers(ID_V2, "v2"), signers(ID_V3, "v3"), signers(ID_V31, "v3.1"), ids, ids.contains(ID_STAMP))
    }

    /** End-of-central-directory → central directory offset → the block in front of it (null when there is none). */
    fun signingBlock(raf: RandomAccessFile): ByteArray? {
        val cdOff = centralDirOffset(raf) ?: return null
        if (cdOff < 32) return null
        val foot = ByteArray(24)
        raf.seek(cdOff - 24); raf.readFully(foot)
        if (String(foot, 8, 16, Charsets.ISO_8859_1) != MAGIC) return null
        val size = BinReader(foot).s64(0)                                         // size of block excluding this first u64
        if (size < 24 || size > Int.MAX_VALUE - 8 || size + 8 > cdOff) throw FormatException("签名块长度错误")
        val start = cdOff - size - 8
        val block = ByteArray((size + 8).toInt())
        raf.seek(start); raf.readFully(block)
        if (BinReader(block).s64(0) != size) throw FormatException("签名块首尾长度不一致")
        return block
    }

    fun centralDirOffset(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val tail = minOf(len, 65_557L).toInt()
        val buf = ByteArray(tail)
        raf.seek(len - tail); raf.readFully(buf)
        val r = BinReader(buf)
        for (i in tail - 22 downTo 0) {
            if (r.s32(i) == 0x06054b50) {
                val commentLen = r.u16(i + 20)
                if (i + 22 + commentLen == tail) return r.u32(i + 16)
            }
        }
        return null
    }

    private fun pairs(block: ByteArray): List<Pair<Int, ByteArray>> {
        val r = BinReader(block)
        val end = block.size - 24
        var p = 8
        val out = ArrayList<Pair<Int, ByteArray>>()
        while (p + 12 <= end) {
            val len = r.s64(p)
            if (len < 4 || p + 8 + len > end) throw FormatException("签名块条目长度错误")
            val id = r.s32(p + 8)
            out += id to r.bytes(p + 12, (len - 4).toInt())
            p += 8 + len.toInt()
        }
        return out
    }

    /** length-prefixed (u32) sequence → its elements. */
    private fun lp(b: ByteArray, off: Int = 0, end: Int = b.size): List<ByteArray> {
        val r = BinReader(b); val out = ArrayList<ByteArray>(); var p = off
        while (p + 4 <= end) { val n = r.s32(p); if (n < 0 || p + 4 + n > end) throw FormatException("签名数据长度错误"); out += r.bytes(p + 4, n); p += 4 + n }
        return out
    }
    private fun lpOne(b: ByteArray, off: Int): Pair<ByteArray, Int> {
        val n = BinReader(b).s32(off); if (n < 0 || off + 4 + n > b.size) throw FormatException("签名数据长度错误")
        return b.copyOfRange(off + 4, off + 4 + n) to off + 4 + n
    }

    private fun parseSigners(value: ByteArray, scheme: String, v3: Boolean): List<Signer> {
        val (seq, _) = lpOne(value, 0)
        return lp(seq).map { signer ->
            val (signed, _) = lpOne(signer, 0)
            val (digests, p1) = lpOne(signed, 0)
            val (certs, p2) = lpOne(signed, p1)
            val algs = lp(digests).map { BinReader(it).s32(0) }
            val certList = lp(certs).map { CertInfo(it) }
            var minSdk: Int? = null; var maxSdk: Int? = null
            if (v3 && p2 + 8 <= signed.size) { val r = BinReader(signed); minSdk = r.s32(p2); maxSdk = r.s32(p2 + 4) }
            Signer(scheme, certList, algs, minSdk, maxSdk)
        }
    }

    /** v1: every RSA / DSA / EC file directly under META-INF is a PKCS#7 SignedData; the JDK / Android CertificateFactory reads its certs. */
    fun readV1(apk: File): List<Signer> = java.util.zip.ZipFile(apk).use { z ->
        z.entries().asSequence().filter { e ->
            val n = e.name.uppercase()
            n.startsWith("META-INF/") && n.count { it == '/' } == 1 && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        }.mapNotNull { e ->
            val bytes = z.getInputStream(e).use { it.readBytes() }
            pkcs7Certs(bytes).takeIf { it.isNotEmpty() }?.let { Signer("v1", it, emptyList()) }
        }.toList()
    }

    fun pkcs7Certs(p7: ByteArray): List<CertInfo> = runCatching {
        java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(p7.inputStream()).map { CertInfo(it.encoded) }
    }.getOrDefault(emptyList())
}

package com.openminis.app.files

import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * [Mines-38.9.11 d] Re-sign an APK the user owns or is authorised to work on: align → sign v1+v2+v3 with their keystore
 * (or the app's own debug key) via com.android.tools.build:apksig → verify the result.
 *
 * SCOPE RULE (kept in code, not only in docs): this signs the user's own / authorised builds. It does not and must not
 * help remove or defeat another app's signature check, licence check or DRM — nothing here edits dex, patches
 * verification code, or strips a signature from a third-party APK.
 */
object ApkResignCore {
    class Key(val alias: String, val privateKey: PrivateKey, val certs: List<X509Certificate>) {
        val subject: String get() = certs.firstOrNull()?.subjectX500Principal?.name ?: alias
        val sha256: String get() = certs.firstOrNull()?.let { Hex2.sha256(it.encoded) } ?: ""
    }

    class SignResult(val out: File, val schemes: List<String>, val certSha256: String, val alignedPadded: Int, val verified: Boolean, val message: String)

    /** Aliases inside a .jks / .p12 / .keystore, so the user can pick one. */
    fun aliases(keystore: File, storePass: CharArray): List<String> {
        val ks = loadStore(keystore, storePass)
        return ks.aliases().toList()
    }

    private fun loadStore(keystore: File, storePass: CharArray): KeyStore {
        val types = if (keystore.extension.equals("p12", true) || keystore.extension.equals("pfx", true)) listOf("PKCS12", "JKS", "BKS")
        else listOf("JKS", "PKCS12", "BKS")
        var last: Exception? = null
        for (t in types) {
            try {
                val ks = KeyStore.getInstance(t)
                keystore.inputStream().use { ks.load(it, storePass) }
                return ks
            } catch (e: Exception) { last = e }
        }
        throw FormatException("打不开密钥库（密码不对，或格式不支持）：${last?.message ?: ""}")
    }

    fun loadKey(keystore: File, storePass: CharArray, alias: String?, keyPass: CharArray = storePass): Key {
        val ks = loadStore(keystore, storePass)
        val a = alias ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw FormatException("密钥库里没有私钥")
        val pk = ks.getKey(a, keyPass) as? PrivateKey ?: throw FormatException("「$a」不是私钥条目（密码不对？）")
        val chain = (ks.getCertificateChain(a) ?: emptyArray()).filterIsInstance<X509Certificate>()
        if (chain.isEmpty()) throw FormatException("「$a」没有证书链")
        return Key(a, pk, chain)
    }

    /**
     * Align + sign [input] → [output]. [minSdk] must match the app's minSdkVersion: apksig drops v1 for 26+ unless
     * asked, and Android only checks v2/v3 there anyway, so v1 is enabled for compatibility with older devices.
     */
    fun sign(input: File, output: File, key: Key, minSdk: Int = 21, v1: Boolean = true, v2: Boolean = true, v3: Boolean = true,
             workDir: File = output.parentFile): SignResult {
        if (!input.isFile) throw FormatException("找不到要签名的文件")
        val aligned = File(workDir, "." + output.name + ".aligned")
        var padded = 0
        try {
            padded = ApkAlignCore.align(input, aligned)
            val signerCfg = com.android.apksig.ApkSigner.SignerConfig.Builder(key.alias.ifBlank { "MINES" }, key.privateKey, key.certs).build()
            val signer = com.android.apksig.ApkSigner.Builder(listOf(signerCfg))
                .setInputApk(aligned)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(v1)
                .setV2SigningEnabled(v2)
                .setV3SigningEnabled(v3)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(true)      // keep the alignment we just produced
                .setCreatedBy("Mines")
                .build()
            signer.sign()
        } finally { aligned.delete() }
        val report = ApkSigCore.read(output)
        val verified = report.schemes.isNotEmpty() && report.primary?.sha256 == key.sha256
        val msg = if (verified) "已签名：${report.schemes.joinToString(" + ")}，证书 ${key.subject}"
        else "签名完成，但校验对不上（方案 ${report.schemes.joinToString("+").ifBlank { "无" }}）"
        return SignResult(output, report.schemes, report.primary?.sha256 ?: "", padded, verified, msg)
    }

    /** Verify with apksig the way the installer does; returns (ok, lines). */
    fun verify(apk: File, minSdk: Int = 21): Pair<Boolean, List<String>> = try {
        val r = com.android.apksig.ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk).build().verify()
        val lines = ArrayList<String>()
        lines += "v1 " + yn(r.isVerifiedUsingV1Scheme) + " · v2 " + yn(r.isVerifiedUsingV2Scheme) + " · v3 " + yn(r.isVerifiedUsingV3Scheme)
        r.signerCertificates.forEach { lines += "证书 " + it.subjectX500Principal.name + " · SHA-256 " + Hex2.sha256(it.encoded) }
        r.errors.forEach { lines += "错误：" + it.toString() }
        r.warnings.forEach { lines += "提醒：" + it.toString() }
        r.isVerified to lines
    } catch (e: Exception) {
        false to listOf("校验失败：" + (e.message ?: e.javaClass.simpleName))
    }

    private fun yn(b: Boolean) = if (b) "有" else "无"

    /** Old signature files that must not be carried into a re-signed package (they would no longer match). */
    fun isOldSignatureFile(name: String): Boolean {
        val n = name.uppercase()
        if (!n.startsWith("META-INF/") || n.count { it == '/' } != 1) return false
        return n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
    }

    /** Entries Android expects uncompressed: resources.arsc (targetSdk 30+), native libs, already-compressed media. */
    fun shouldStore(name: String, originallyStored: Set<String> = emptySet()): Boolean {
        if (name in originallyStored) return true
        if (name == "resources.arsc" || name.endsWith(".so")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "ogg", "mp3", "mp4", "m4a", "wav", "aac", "zip", "jar", "apk")
    }

    /**
     * Repack an unpacked APK folder ([dir] = what ZipCore.unpack produced, possibly edited) into an UNSIGNED apk.
     * AndroidManifest.xml first, old signature files dropped, deterministic order. Sign it with [sign] afterwards.
     */
    fun repack(dir: File, outUnsigned: File, originallyStored: Set<String> = emptySet()): Int {
        if (!File(dir, "AndroidManifest.xml").isFile) throw FormatException("这个文件夹里没有 AndroidManifest.xml，不像解开的 APK")
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path.replace(File.separatorChar, '/') to it }
            .filter { !isOldSignatureFile(it.first) && it.first != STORED_LIST }
            .sortedWith(compareBy({ it.first != "AndroidManifest.xml" }, { it.first })).toList()
        val stored = originallyStored + (File(dir, STORED_LIST).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList())
        var n = 0
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(outUnsigned.outputStream())).use { out ->
            for ((name, f) in files) {
                val bytes = f.readBytes()
                val e = java.util.zip.ZipEntry(name)
                e.time = 347_155_200_000L
                if (shouldStore(name, stored)) {
                    e.method = java.util.zip.ZipEntry.STORED
                    e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    val crc = java.util.zip.CRC32(); crc.update(bytes); e.crc = crc.value
                } else e.method = java.util.zip.ZipEntry.DEFLATED
                out.putNextEntry(e); out.write(bytes); out.closeEntry(); n++
            }
        }
        return n
    }

    /** Written next to an unpacked APK so a repack keeps the same entries uncompressed. */
    const val STORED_LIST = ".mines-stored.txt"

    /** Names that are STORED in [apk] (to keep them stored on repack). */
    fun storedNames(apk: File): Set<String> = java.util.zip.ZipFile(apk).use { z ->
        z.entries().asSequence().filter { it.method == java.util.zip.ZipEntry.STORED && !it.isDirectory }.map { it.name }.toSet()
    }

    /**
     * The one guard the UI calls before offering 重新签名 on a file the user did not build: signing is for your own or
     * authorised packages. Returns a Chinese warning to show (never blocks — the user states the authorisation), or
     * null for a package that is clearly the user's own build.
     */
    fun authorisationNotice(info: ApkInfoCore.Info, ownPackages: Set<String>): String? =
        if (info.packageName != null && info.packageName in ownPackages) null
        else "重新签名会换掉这个包的证书。只对你自己的、或你有授权的应用这样做；换签名不会绕过别人应用的付费或授权校验，Mines 也不做这件事。"
}

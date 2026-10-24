package com.openminis.app.files

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * [Mines-38.9.11 d] A self-signed X.509 certificate + PKCS#12 keystore built with plain JCA (no BouncyCastle), so the
 * file manager can create the user a signing key on the phone (「新建签名密钥」) or a per-device 「Mines 调试密钥」.
 */
object SelfSignedCore {
    fun certificate(kp: KeyPair, commonName: String, years: Int = 30, nowMs: Long = System.currentTimeMillis()): X509Certificate {
        val name = seq(set(seq(oid("2.5.4.3") + utf8(commonName))))
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        // GeneralizedTime (0x18) works for every year; UTCTime would stop at 2049
        val validity = seq(tag(0x18, fmt.format(java.util.Date(nowMs - 86_400_000L)).toByteArray()) +
            tag(0x18, fmt.format(java.util.Date(nowMs + years * 365L * 86_400_000L)).toByteArray()))
        val algId = seq(oid("1.2.840.113549.1.1.11") + byteArrayOf(0x05, 0x00))   // sha256WithRSAEncryption
        val serial = java.math.BigInteger(63, java.security.SecureRandom()).add(java.math.BigInteger.ONE).toByteArray()
        val tbs = seq(tag(0xA0, int(byteArrayOf(2))) + int(serial) + algId + name + validity + name + kp.public.encoded)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        val der = seq(tbs + algId + tag(0x03, byteArrayOf(0) + sig))
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    /** New RSA-2048 key in a PKCS#12 file at [file] under [alias]. Refuses to overwrite. */
    fun createKeystore(file: File, password: CharArray, alias: String, commonName: String): X509Certificate {
        if (file.exists()) throw FormatException("「${file.name}」已经存在，不会覆盖")
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = certificate(kp, commonName)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        ks.setKeyEntry(alias, kp.private, password, arrayOf(cert))
        file.parentFile?.mkdirs()
        file.outputStream().use { ks.store(it, password) }
        return cert
    }

    private fun tag(t: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(t.toByte()) + len + body
    }
    private fun seq(b: ByteArray) = tag(0x30, b)
    private fun set(b: ByteArray) = tag(0x31, b)
    private fun int(b: ByteArray) = tag(0x02, b)
    private fun utf8(s: String) = tag(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun oid(o: String): ByteArray {
        val p = o.split('.').map { it.toInt() }
        val out = java.io.ByteArrayOutputStream()
        out.write(p[0] * 40 + p[1])
        for (v0 in p.drop(2)) {
            val st = ArrayList<Int>(); var v = v0
            do { st.add(v and 0x7F); v = v shr 7 } while (v > 0)
            st.reversed().forEachIndexed { i, x -> out.write(if (i == st.size - 1) x else x or 0x80) }
        }
        return tag(0x06, out.toByteArray())
    }
}

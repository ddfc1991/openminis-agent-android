package com.openminis.app.plus

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * [Mines-38.8] 消息 → 邮箱: a read-only IMAP reader for the aggregated inbox (no Android imports, unit-tested on the JVM).
 *
 * Only ever LOGIN / SELECT (EXAMINE) / FETCH with BODY.PEEK — Mines never sends mail, never marks mail read, never deletes.
 * An account is "connected" only after a real LOGIN + SELECT succeeded (user rule: never pretend linked).
 */
object MailImap {
    enum class Fail { AUTH, NET, PROTOCOL }

    class ImapError(val kind: Fail, message: String) : Exception(message)

    data class Msg(
        val uid: String, val from: String, val fromAddr: String, val subject: String, val text: String,
        val at: Long, val unread: Boolean,
    )

    data class Box(val exists: Int, val unseen: Int, val msgs: List<Msg>)

    /** Test hook: "host:port" → plain TCP instead of TLS on 993 (JVM unit test + adb self-test fake server only). */
    @Volatile var plainOverride: String? = null

    /** IMAP host for an address, or null when Mines does not know the provider. */
    fun hostFor(email: String): String? {
        val d = email.substringAfter('@', "").lowercase().trim()
        if (d == "smoke.test" && plainOverride != null) return "smoke"
        return MailConfig.of(email)?.host   // [Mines-38.9.1] one host table (MailConfig)
    }

    private val DATE = java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", java.util.Locale.US)

    private fun open(host: String): Socket {
        plainOverride?.let { o ->
            val h = o.substringBefore(':'); val p = o.substringAfter(':').toIntOrNull() ?: 1143
            return Socket().apply { connect(InetSocketAddress(h, p), 8000); soTimeout = 15_000 }
        }
        return MailNet.open(host, 993)   // [Mines-38.9.5 i] through the split proxy when direct is cut (Gmail from CN)
    }

    /** Newest [max] messages of INBOX, newest first, plus the unseen count. Throws [ImapError]. */
    fun inbox(host: String, user: String, pass: String, max: Int = 20): Box {
        val sock = try { open(host) } catch (e: Exception) { throw ImapError(Fail.NET, e.message?.takeIf { it.startsWith("连不上") } ?: "连不上 $host（${e.javaClass.simpleName}）") }
        sock.use { s ->
            val inp = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            try {
                val greet = readLine(inp)
                if (!greet.startsWith("* OK") && !greet.startsWith("* PREAUTH")) throw ImapError(Fail.PROTOCOL, "服务器没有正常应答")
                var n = 0
                fun cmd(c: String): List<Any> { n++; val tag = "m$n"; send(out, "$tag $c"); return readTagged(inp, tag) }
                // 163 / 126 / yeah refuse SELECT ("Unsafe Login") unless the client says who it is first
                // [Mines-38.9.1] RFC 2971 ID before LOGIN for every NetEase box (MailConfig.needsId) — else 「Unsafe Login」
                if (MailConfig.of(user)?.needsId == true || host.contains("163") || host.contains("126") || host.contains("yeah"))
                    cmd("ID (\"name\" \"Mines\" \"version\" \"1.23.1\" \"vendor\" \"Mines\" \"support-email\" \"mines@openminis.app\")")
                val login = cmd("LOGIN ${q(user)} ${q(pass)}")
                if (!lastOk(login)) {
                    val why = (login.lastOrNull() as? String)?.substringAfter(" NO ", "")?.trim()?.take(80).orEmpty()
                    throw ImapError(Fail.AUTH, if (why.isNotBlank()) why else "AUTHENTICATIONFAILED")
                }
                val sel = cmd("EXAMINE INBOX")   // read-only SELECT: nothing is ever marked read
                if (!lastOk(sel)) {
                    val why = (sel.lastOrNull() as? String)?.take(80).orEmpty()
                    throw ImapError(if (why.contains("Unsafe", true)) Fail.AUTH else Fail.PROTOCOL, why.ifBlank { "SELECT INBOX failed" })
                }
                val exists = sel.filterIsInstance<String>().firstNotNullOfOrNull { Regex("^\\* (\\d+) EXISTS").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                val st = cmd("STATUS INBOX (UNSEEN)")
                var unseen = st.filterIsInstance<String>().firstNotNullOfOrNull { Regex("UNSEEN (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1
                if (exists == 0) { runCatching { cmd("LOGOUT") }; return Box(0, 0, emptyList()) }
                val from = maxOf(1, exists - max + 1)
                val res = cmd("FETCH $from:$exists (UID FLAGS INTERNALDATE BODY.PEEK[]<0.16000>)")
                runCatching { cmd("LOGOUT") }
                val msgs = ArrayList<Msg>()
                var i = 0
                while (i < res.size) {
                    val line = res[i] as? String
                    val lit = res.getOrNull(i + 1) as? ByteArray
                    if (line != null && line.contains("FETCH") && lit != null) {
                        val uid = Regex("UID (\\d+)").find(line)?.groupValues?.get(1) ?: line.hashCode().toString()
                        val flags = Regex("FLAGS \\(([^)]*)\\)").find(line + " " + ((res.getOrNull(i + 2) as? String)?.takeIf { !it.contains("FETCH") }.orEmpty()))?.groupValues?.get(1).orEmpty()
                        val at = Regex("INTERNALDATE \"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let {
                            runCatching { java.time.ZonedDateTime.parse(it.trim(), DATE).toInstant().toEpochMilli() }.getOrDefault(0L)
                        } ?: 0L
                        val raw = String(lit, Charsets.UTF_8)
                        val headEnd = Regex("\\r?\\n\\r?\\n").find(raw)?.range?.first ?: raw.length
                        val (subject, fromH) = MailParse.headers(raw.substring(0, headEnd))
                        val text = runCatching { MailParse.mime(raw) }.getOrDefault("")
                        val addr = Regex("<([^>]+)>").find(fromH)?.groupValues?.get(1) ?: fromH.takeIf { it.contains('@') }.orEmpty()
                        msgs += Msg(uid, fromH.substringBefore('<').trim().ifBlank { addr.ifBlank { fromH } }, addr.trim(), subject,
                            text.replace(Regex("[ \\t]+"), " ").trim().take(6000), at, !flags.contains("\\Seen", ignoreCase = true))
                        i += 2
                    } else i++
                }
                if (unseen < 0) unseen = msgs.count { it.unread }
                return Box(exists, unseen, msgs.sortedByDescending { it.uid.toLongOrNull() ?: 0L })
            } catch (e: ImapError) { throw e
            } catch (e: java.net.SocketTimeoutException) { throw ImapError(Fail.NET, "$host 没有应答（超时）")
            } catch (e: java.io.IOException) { throw ImapError(Fail.NET, "和 $host 的连接断了") }
        }
    }

    internal fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun send(out: OutputStream, line: String) { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }

    private fun lastOk(r: List<Any>) = (r.lastOrNull() as? String)?.split(' ')?.getOrNull(1)?.equals("OK", true) == true

    private fun readLine(inp: BufferedInputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) { if (b.size() == 0) throw java.io.EOFException(); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.UTF_8)
    }

    private fun readTagged(inp: BufferedInputStream, tag: String): List<Any> {
        val out = ArrayList<Any>()
        var guard = 0
        while (guard++ < 20000) {
            val line = readLine(inp)
            out += line
            val lit = Regex("\\{(\\d+)\\}$").find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (lit != null) {
                val buf = ByteArray(lit); var got = 0
                while (got < lit) { val r = inp.read(buf, got, lit - got); if (r < 0) break; got += r }
                out += buf
                continue
            }
            if (line.startsWith("$tag ")) break
        }
        return out
    }
}

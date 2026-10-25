package com.openminis.app.persona

import com.openminis.app.plus.MailConfig
import com.openminis.app.plus.MailNet
import com.openminis.app.plus.MailParse
import com.openminis.app.plus.MailSources
import com.openminis.app.plus.PlusVault
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64

/**
 * [Mines-38.9.15 伪人核心] The user's own mail: the Sent folder (to learn how they write) and sending as them over
 * SMTP with the same 授权码 Mines already keeps for IMAP. Only [Persona] / the persona tool call this, and a send is
 * only reached after [Persona.guard] allowed it.
 */
object PersonaMail {
    data class Box(val user: String, val host: String, val pass: String)

    /** the user's IMAP mailboxes Mines has an 授权码 for (same source as 消息 / 验证码) */
    fun boxes(): List<Box> = listOf("gmail", "mail163", "qqmail").flatMap { base ->
        PlusVault.accountsOf(base).mapNotNull { id ->
            val pass = PlusVault.secret(id) ?: return@mapNotNull null
            val user = PlusVault.entries.value[id]?.account?.trim().orEmpty()
            if (!user.contains('@')) return@mapNotNull null
            val host = MailConfig.of(user)?.host ?: return@mapNotNull null
            Box(user, host, MailConfig.normalizeCode(pass))
        }
    }

    /** bodies of the newest [max] mails in the Sent folder of [b] (first 8 KB each); the text never leaves this call's caller */
    fun sentBodies(b: Box, max: Int = 15): List<String> {
        val s = MailNet.open(b.host, 993)
        s.use { sock ->
            val inp = BufferedInputStream(sock.inputStream)
            val out = sock.outputStream
            MailSources.Imap.readLine(inp)
            var n = 0
            fun cmd(c: String): List<Any> { n++; val tag = "p$n"; MailSources.Imap.send(out, "$tag $c"); return MailSources.Imap.readTagged(inp, tag) }
            if (b.host.contains("163") || b.host.contains("126") || b.host.contains("yeah")) cmd("ID (\"name\" \"Mines\" \"version\" \"33\" \"vendor\" \"Mines\")")
            if (!MailSources.Imap.lastOk(cmd("LOGIN ${MailSources.Imap.q(b.user)} ${MailSources.Imap.q(b.pass)}"))) return emptyList()
            val list = cmd("LIST \"\" \"*\"").filterIsInstance<String>()
            val sent = sentFolder(list) ?: return emptyList()
            val sel = cmd("SELECT ${MailSources.Imap.q(sent)}")
            val exists = sel.filterIsInstance<String>().firstNotNullOfOrNull { Regex("^\\* (\\d+) EXISTS").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            if (exists == 0) return emptyList()
            val from = maxOf(1, exists - max + 1)
            val res = cmd("FETCH $from:$exists (BODY.PEEK[]<0.8000>)")
            runCatching { cmd("LOGOUT") }
            return res.filterIsInstance<ByteArray>().mapNotNull { lit ->
                runCatching { ownPart(MailParse.mime(String(lit, Charsets.UTF_8))) }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    /** the Sent mailbox: the \Sent special-use flag first, then the names providers use */
    fun sentFolder(listLines: List<String>): String? {
        fun nameOf(l: String) = Regex("\"([^\"]*)\"\\s*$").find(l)?.groupValues?.get(1) ?: l.substringAfterLast(' ').trim()
        listLines.firstOrNull { it.contains("\\Sent", ignoreCase = true) }?.let { return nameOf(it) }
        val names = listLines.map { nameOf(it) }
        return names.firstOrNull { it.equals("Sent Messages", true) || it.equals("Sent", true) || it.equals("[Gmail]/Sent Mail", true) }
            ?: names.firstOrNull { it.contains("&XfJT0ZAB-") }   // 已发送 in modified UTF-7 (163 / QQ)
    }

    /** the user's own words of a reply: everything above the quoted original */
    fun ownPart(body: String): String {
        val cut = listOf("\n>", "-----原始邮件-----", "------------------ 原始邮件", "-----Original Message-----", "\nOn ", "\n在 ", "发件人:", "From:")
            .mapNotNull { m -> body.indexOf(m).takeIf { it > 0 } }.minOrNull() ?: body.length
        return body.substring(0, cut).trim()
    }

    // ── SMTP (send as the user) ───────────────────────────────────────────────

    fun smtpHost(address: String): String? = when (address.substringAfter('@', "").lowercase()) {
        "gmail.com", "googlemail.com" -> "smtp.gmail.com"
        "163.com" -> "smtp.163.com"; "126.com" -> "smtp.126.com"; "yeah.net" -> "smtp.yeah.net"
        "qq.com", "foxmail.com", "vip.qq.com" -> "smtp.qq.com"
        "sina.com", "sina.cn" -> "smtp.sina.com"
        "aliyun.com" -> "smtp.aliyun.com"
        else -> null
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** RFC 5322 message, UTF-8 base64 body and encoded-word subject */
    fun message(from: String, to: String, subject: String, body: String, nowMs: Long): String = buildString {
        append("From: ").append(from).append("\r\n")
        append("To: ").append(to).append("\r\n")
        append("Subject: =?UTF-8?B?").append(b64(subject)).append("?=\r\n")
        append("Date: ").append(java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).format(java.util.Date(nowMs))).append("\r\n")
        append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        b64(body).chunked(76).forEach { append(it).append("\r\n") }
    }

    /** send over SMTPS :465 with the saved 授权码; returns null on success, else a plain reason */
    fun send(b: Box, to: String, subject: String, body: String): String? {
        val host = smtpHost(b.user) ?: return "不知道 ${b.user} 的发信服务器"
        return runCatching {
            MailNet.open(host, 465, 12_000).use { s ->
                val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                val w = s.outputStream
                fun reply(): String { var l: String; var last = ""; do { l = r.readLine() ?: return last; last = l } while (l.length > 3 && l[3] == '-'); return last }
                fun cmd(c: String): String { w.write((c + "\r\n").toByteArray(Charsets.UTF_8)); w.flush(); return reply() }
                if (!reply().startsWith("220")) return "发信服务器没应答"
                cmd("EHLO mines")
                cmd("AUTH LOGIN"); cmd(b64(b.user))
                if (!cmd(b64(b.pass)).startsWith("235")) return "发信登录被拒（授权码不对或没开 SMTP）"
                if (!cmd("MAIL FROM:<${b.user}>").startsWith("250")) return "发件人被拒"
                if (!cmd("RCPT TO:<${ContactMemoryCore.keyOf(to)}>").startsWith("25")) return "收件人被拒"
                if (!cmd("DATA").startsWith("354")) return "服务器不收正文"
                val msg = message(b.user, to, subject, body, System.currentTimeMillis()).replace("\r\n.", "\r\n..")
                if (!cmd(msg + "\r\n.").startsWith("250")) return "正文被拒"
                runCatching { cmd("QUIT") }
                null
            }
        }.getOrElse { "发信失败（${it.javaClass.simpleName}）" }
    }
}

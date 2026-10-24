package com.openminis.app.plus

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/** [Mines-38.8] 邮箱: the read-only IMAP reader against a tiny in-process server (plain TCP via MailImap.plainOverride). */
class MailImapTest {
    private lateinit var server: ServerSocket
    private val seen = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun raw(from: String, subj: String, body: String): ByteArray =
        ("From: $from\r\nSubject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(subj.toByteArray()) + "?=\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            Base64.getEncoder().encodeToString(body.toByteArray()) + "\r\n").toByteArray()

    @Before fun start() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { sock ->
                        val r = sock.getInputStream().bufferedReader(); val o = sock.getOutputStream()
                        fun w(x: String) { o.write((x + "\r\n").toByteArray()); o.flush() }
                        w("* OK ready"); var authed = false
                        while (true) {
                            val line = r.readLine() ?: break
                            val p = line.split(" ", limit = 3); val tag = p[0]; val cmd = p.getOrElse(1) { "" }.uppercase()
                            seen += cmd
                            when (cmd) {
                                "ID" -> { w("* ID NIL"); w("$tag OK") }
                                "LOGIN" -> { authed = line.contains("\"goodcode\""); w(if (authed) "$tag OK LOGIN" else "$tag NO LOGIN Login error password error") }
                                "EXAMINE", "SELECT" -> { w("* 2 EXISTS"); w("$tag OK [READ-ONLY]") }
                                "STATUS" -> { w("* STATUS INBOX (UNSEEN 1)"); w("$tag OK") }
                                "FETCH" -> {
                                    val a = raw("Alice <alice@example.com>", "周报", "第一封，已读")
                                    val b = raw("网易邮箱 <svc@163.com>", "IMAP 已开启", "第二封，未读")
                                    o.write("* 1 FETCH (UID 11 FLAGS (\\Seen) INTERNALDATE \"16-Oct-2026 01:00:00 +0800\" BODY[]<0> {${a.size}}\r\n".toByteArray() + a + ")\r\n".toByteArray())
                                    o.write("* 2 FETCH (UID 12 FLAGS () INTERNALDATE \"16-Oct-2026 02:00:00 +0800\" BODY[]<0> {${b.size}}\r\n".toByteArray() + b + ")\r\n".toByteArray())
                                    w("$tag OK FETCH")
                                }
                                "LOGOUT" -> { w("* BYE"); w("$tag OK"); break }
                                else -> w("$tag BAD")
                            }
                        }
                    }
                }
            }
        }
        MailImap.plainOverride = "127.0.0.1:${server.localPort}"
    }

    @After fun stop() { MailImap.plainOverride = null; runCatching { server.close() } }

    @Test fun readsInboxReadOnly() {
        val box = MailImap.inbox("imap.163.com", "box1@163.com", "goodcode")
        assertEquals(2, box.exists)
        assertEquals(1, box.unseen)
        assertEquals(listOf("12", "11"), box.msgs.map { it.uid })          // newest first
        assertEquals("IMAP 已开启", box.msgs[0].subject)
        assertEquals("网易邮箱", box.msgs[0].from)
        assertEquals("svc@163.com", box.msgs[0].fromAddr)
        assertTrue(box.msgs[0].unread)
        assertFalse(box.msgs[1].unread)
        assertTrue(box.msgs[1].text.contains("第一封"))
        assertTrue("163 needs ID before LOGIN", seen.indexOf("ID") in 0 until seen.indexOf("LOGIN"))
        assertTrue("read-only EXAMINE, never SELECT", "EXAMINE" in seen && "SELECT" !in seen)
        assertFalse("never STORE / flags change", seen.any { it == "STORE" || it == "EXPUNGE" })
    }

    @Test fun wrongCodeIsAuthFailure() {
        try { MailImap.inbox("imap.163.com", "box1@163.com", "badcode"); fail("must throw") }
        catch (e: MailImap.ImapError) { assertEquals(MailImap.Fail.AUTH, e.kind) }
    }

    @Test fun unreachableIsNetFailure() {
        MailImap.plainOverride = "127.0.0.1:1"
        try { MailImap.inbox("imap.163.com", "x@163.com", "goodcode"); fail("must throw") }
        catch (e: MailImap.ImapError) { assertEquals(MailImap.Fail.NET, e.kind) }
    }

    @Test fun hosts() {
        MailImap.plainOverride = null
        assertEquals("imap.163.com", MailImap.hostFor("demo.one@163.com"))
        assertEquals("imap.gmail.com", MailImap.hostFor("alex.demo@gmail.com"))
        assertEquals("imap.qq.com", MailImap.hostFor("a@qq.com"))
        assertEquals(null, MailImap.hostFor("a@unknown-domain.xyz"))
    }
}

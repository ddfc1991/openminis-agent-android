package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.12.1] self-hosted inbox API client parsing (no token in code: test tokens are made up). */
class SelfMailCoreTest {
    private val tok = "tEsT_0123456789abcdefXYZ"

    @Test fun config() {
        val c = SelfMailCore.parseConfig("mines-mail://mail.example.org/inbox#$tok")!!
        assertEquals("https://mail.example.org/inbox", c.base); assertEquals(tok, c.token)
        val d = SelfMailCore.parseConfig("https://mail.example.org/api/\n$tok\n")!!
        assertEquals("https://mail.example.org", d.base)
        assertEquals("https://x.example.org", SelfMailCore.parseConfig("$tok\nhttps://x.example.org")!!.base)
        assertNull(SelfMailCore.parseConfig("http://mail.example.org\n$tok"))     // https only
        assertNull(SelfMailCore.parseConfig("mines-mail://mail.example.org#short"))
        assertNull(SelfMailCore.parseConfig("https://mail.example.org"))
        assertNull(SelfMailCore.parseConfig(""))
    }

    @Test fun health() {
        assertEquals("ydn44086.dpdns.org", SelfMailCore.parseHealth("""{"ok":true,"domain":"ydn44086.dpdns.org"}"""))
        assertNull(SelfMailCore.parseHealth("""{"ok":false}"""))
        assertNull(SelfMailCore.parseHealth("not json"))
        assertEquals("a1@ydn44086.dpdns.org", SelfMailCore.parseAddress("""{"address":"a1@ydn44086.dpdns.org"}"""))
    }

    @Test fun mailboxes() {
        val b = SelfMailCore.parseMailboxes("""[{"address":"gh-1@d.org","createdAt":1761180000000,"count":3},{"address":"bad"}]""")
        assertEquals(1, b.size); assertEquals(3, b[0].count); assertEquals(1761180000000L, b[0].createdAt)
        assertEquals(1, SelfMailCore.parseMailboxes("""{"mailboxes":[{"address":"x@d.org","createdAt":"2026-10-23T00:00:00Z"}]}""").size)
    }

    @Test fun messages() {
        val j = """[{"id":"m1","to":"gh-1@d.org","from":"GitHub <noreply@github.com>","subject":"Your code","text":"Code: 482913","receivedAt":1761180000000},
                    {"id":"m2","to":"gh-1@d.org","from":"x@y.com","subject":"s","text":"","html":"<p>Hi <b>there</b> 123456</p>","receivedAt":1761180000},
                    {"to":"no-id@d.org"}]"""
        val ms = SelfMailCore.parseMessages(j)
        assertEquals(2, ms.size)
        assertEquals("sh:m1", ms[0].id); assertEquals(SelfMailCore.SOURCE, ms[0].source); assertEquals(1761180000000L, ms[0].at)
        assertEquals("Hi there 123456", ms[1].text); assertEquals(1761180000000L, ms[1].at)   // seconds → ms
        assertEquals("gh-1@d.org", SelfMailCore.toOf(j)["sh:m1"])
        assertTrue(SelfMailCore.parseMessages("garbage").isEmpty())
    }

    @Test fun prefix() {
        assertTrue(SelfMailCore.prefixFor("Hugging Face", 123456).startsWith("huggingface-"))
        assertTrue(SelfMailCore.prefixFor("中文", 1).startsWith("mines-"))
    }
}

package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterCoreTest {
    private val ledger = listOf(
        "github.com" to "a@163.com", "login.github.com" to "B@163.com", "openai.com" to "a@163.com",
        "openai.com" to "c@gmail.com", "x.com" to "not-an-email", "deepseek.com" to "d@qq.com")

    @Test fun poolDedupesAndCounts() {
        val p = RegisterCore.pool(ledger, listOf("e@gmail.com", "A@163.com"), setOf("c@gmail.com"))
        assertEquals(listOf("a@163.com", "B@163.com", "c@gmail.com", "d@qq.com", "e@gmail.com"), p.map { it.addr })
        assertEquals(2, p.first { it.addr == "a@163.com" }.sites)
        assertTrue(p.first { it.addr == "c@gmail.com" }.readable)
        assertFalse(p.any { it.addr == "not-an-email" })
    }

    @Test fun skipsAlreadyRegisteredOnSite() {
        val p = RegisterCore.pool(ledger, emptyList(), emptySet())
        val used = RegisterCore.usedOn(ledger, "www.github.com")
        assertEquals(setOf("a@163.com", "b@163.com"), used)
        val got = RegisterCore.pick(p, used, 5).map { it.addr }
        assertFalse(got.contains("a@163.com")); assertFalse(got.contains("B@163.com"))
        assertEquals(setOf("c@gmail.com", "d@qq.com"), got.toSet())
    }

    @Test fun readableFirstThenLeastUsed_andCount() {
        val p = RegisterCore.pool(ledger, listOf("z@gmail.com"), setOf("d@qq.com"))
        val got = RegisterCore.pick(p, RegisterCore.usedOn(ledger, "new-site.io"), 3).map { it.addr }
        assertEquals("d@qq.com", got[0])            // its mailbox can be read for the code
        assertEquals(3, got.size)
        assertEquals("z@gmail.com", got[1])         // never used anywhere
        assertTrue(RegisterCore.pick(p, emptySet(), 2, exclude = setOf("D@QQ.com")).none { it.addr == "d@qq.com" })
        assertEquals(0, RegisterCore.pick(p, emptySet(), 0).size)
    }

    @Test fun noteAndPassword() {
        assertEquals("注册于 2026-10-21", RegisterCore.note("2026-10-21", true))
        assertEquals("注册中 2026-10-21 · 第2个", RegisterCore.note("2026-10-21", false, "第2个"))
        val pw = RegisterCore.password(java.util.Random(7))
        assertEquals(16, pw.length)
        assertTrue(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() } && pw.any { it.isDigit() } && pw.any { !it.isLetterOrDigit() })
    }
}

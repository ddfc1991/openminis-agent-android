package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.7] Memoh = OpenAI-compatible endpoint with a key (no web sign-in). Demo addresses only. */
class MemohKeyCoreTest {
    @Test fun bases() {
        assertEquals("http://192.0.2.10:8794/v1", MemohKeyCore.v1Base("192.0.2.10:8794"))
        assertEquals("http://192.0.2.10:8794/v1", MemohKeyCore.v1Base("http://192.0.2.10:8794/v1/"))
        assertEquals("https://m.example.com/v1", MemohKeyCore.v1Base("https://m.example.com"))
        assertNull(MemohKeyCore.v1Base("  "))
    }
    @Test fun verdicts() {
        assertTrue(MemohKeyCore.verdict(200, true).first)
        assertEquals("服务器在线，要填 key", MemohKeyCore.verdict(401, false).second)
        assertFalse(MemohKeyCore.verdict(401, true).first)
        assertEquals("gpt-x", MemohKeyCore.firstModel("""{"data":[{"id":"gpt-x"},{"id":"y"}]}"""))
        assertEquals("自建：192.0.2.10:8794", MemohKeyCore.where("http://192.0.2.10:8794/v1"))
        assertEquals("http://192.0.2.10:8794", MemohKeyCore.suggestFrom(listOf("192.0.2.10")))
    }
}
